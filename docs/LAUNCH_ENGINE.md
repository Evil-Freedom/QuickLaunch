# 可靠启动引擎（v1.4.0）

> 本文记录「启动 → 验证 → 重试 → 告警」这条链路的实现依据、真机证据与已知边界。
> 所有数字与日志都来自 2026-11-23 在目标机（Motorola Razr 40 Ultra / Android 16 /
> SN `ZY22HKL48G` / Magisk root）上的实际运行，不是估算。

---

## 1. 要解决的问题

升级前的链路是：

```
AlarmManager → AlarmReceiver → LaunchService → LaunchProxyActivity → startActivity(目标)
```

`startActivity` 返回 `true` 只代表**系统接受了这次请求**。以下情形都能让「请求已发」
与「界面真的在前台」之间出现差距，而用户完全看不见：

| 情形 | 升级前的表现 |
| --- | --- |
| 后台启动被系统拦截 | 命令返回成功，界面没变，无任何提示 |
| 锁屏 / 息屏 | 同上 |
| 折叠屏换屏后目标投到另一块屏 | 同上 |
| 目标 App 启动后立即闪退 | 同上 |
| 目标 App 被卸载 | 同上 |

本次把这条链路改成：

```
触发 → 启动前探测 → 发出命令 → 定时探测前台 → 命中两次判定成功
                              └→ 窗口耗尽 → 按间隔重试 → 用尽则告警并留手动入口
```

---

## 2. 前台检测：为什么只认 `dumpsys activity activities`

2026-11-23 在目标机上实测了四种「谁是前台」的读法：

| 读法 | 实测结果 | 结论 |
| --- | --- | --- |
| `dumpsys activity activities` → `topResumedActivity` | 450ms/次，值与实际操作一致 | **采用** |
| `dumpsys activity top` → `ACTIVITY` | 返回 `com.motorola.launcher.secondarydisplay/.SecondaryDisplayLauncher`（外屏桌面），不是用户看的那块屏 | 弃用 |
| `dumpsys window` → `mCurrentFocus` | 锁屏与部分全屏场景下为 `null` | 弃用 |
| `dumpsys activity recents` | 只给 `mRecentsUid`，不含当前前台 | 弃用 |

原始 dump 结构（节选，两屏并存）：

```
Display #0 (activities from top to bottom):
  * Task{7e8393c #1283 type=standard A=1000:com.android.settings.root ...}
    topResumedActivity=ActivityRecord{246024462 u0 com.android.settings/.Settings t1283}
Display #1 (activities from top to bottom):
  * Task{afdc331 #1219 ...}
    topResumedActivity=ActivityRecord{226945639 u0 com.motorola.launcher.secondarydisplay/...}
```

**必须按 `Display #N` 分段**：这台机器的外屏是独立的 display 1，
不分段就会把「外屏上的桌面」误判成「目标 App 没起来」。

判定规则（`ForegroundJudge`）：

1. 目标包是某个 display 的顶层 Activity，**且**该 display 处于 `STATE_ON` → `VISIBLE`
2. 读到了前台信息但没有一处命中 → `NOT_VISIBLE`
3. 拿不到任何信息 → `UNKNOWN`（**不判失败**，见 §5）

---

## 3. 参数与实测时序

默认策略（`LaunchPolicy`，可在界面改，也可由 VPS 下发）：

| 参数 | 默认值 | 依据 |
| --- | --- | --- |
| 最多尝试次数 | 3 | 首次 + 2 次重试 |
| 首次验证延迟 | 2000ms | 实测 `am start` 返回 325ms 后、+515ms 时前台已是目标 Activity；留到 2s 是为了覆盖冷启动先显示启动图的 App |
| 单次验证窗口 | 6000ms | 窗口内每 800ms 探测一次（单次探测实测 450ms） |
| 连续命中要求 | 2 次 | 能识别「启动后立即闪退」 |
| 重试间隔 | 5000ms / 15000ms | 第一次补偶发拦截，第二次等系统冷却 |
| 总时限 | 90000ms | 超过即收手，不再唤醒手机 |

### 3.1 成功路径（真机原始日志）

```
14:22:20.432 QL-LaunchAttempt: 新建任务 18ec3b41-... pkg=com.android.settings manual=true state=PENDING attempt=0/3
14:22:20.567 QL-LaunchAttempt: 第 1 次发出启动 pkg=com.android.settings
14:22:22.701 QL-LaunchAttempt: 探测 pkg=com.android.settings verdict=VISIBLE src=ROOT_DUMPSYS top={0=com.android.settings} lit=[0]
14:22:23.591 QL-LaunchAttempt: 探测 pkg=com.android.settings verdict=VISIBLE src=ROOT_DUMPSYS top={0=com.android.settings} lit=[0]
14:22:23.593 QL-LaunchAttempt: 任务结束 18ec3b41-... state=SUCCESS attempt=1/3
```

- 触发 → 发出命令：135ms
- 发出命令 → 首次命中：2134ms
- 首次命中 → 二次确认并判定成功：890ms
- **总耗时 3.16s**

### 3.2 失败重试路径（真机原始日志，包名故意用不存在的）

```
14:22:40.574 新建任务 ... pkg=com.nonexistent.app.test state=PENDING attempt=0/3
14:22:40.705 第 1 次发出启动
14:22:42.810 ~ 14:22:48.105 探测 ×7 全部 NOT_VISIBLE（top={0=com.motorola.launcher3}）
14:22:53.749 第 2 次发出启动        ← 距上次失败 5.0s
14:22:55.870 ~ 14:23:01.133 探测 ×7 全部 NOT_VISIBLE
14:23:16.817 第 3 次发出启动        ← 距上次失败 15.0s
14:23:19.042 ~ 14:23:24.359 探测 ×7 全部 NOT_VISIBLE
14:23:24.895 任务结束 ... state=MANUAL_REQUIRED attempt=3/3 error=VERIFY_TIMEOUT
```

- 总耗时 44.3s，与「2+6+5+2+6+15+2+6」的理论值一致
- **没有第 4 次**：`hasNextAttempt(3)` 为 false，直接收手

失败通知（`dumpsys notification` 原始输出）：

```
NotificationRecord(pkg=com.workbuddy.quicklaunch id=2377
  channel=quicklaunch_launch_fail_v1 importance=4 category=err actions=1)
  android.title=String (测试失败场景 没能打开)
  android.bigText=String (已尝试 3 次：启动后未检测到进入前台。点此再试一次)
  [0] "再次启动" -> PendingIntent (broadcastIntent)
  effectiveNotificationChannel=mId='quicklaunch_launch_fail_v1', mName=启动失败提醒, mImportance=4
```

### 3.3 已在前台不重复拉起

```
14:23:51.141 新建任务 ee35f02b-... pkg=com.android.settings state=PENDING attempt=0/3
14:23:51.224 任务结束 ee35f02b-... state=SUCCESS attempt=0/3
```

**没有「第 1 次发出启动」这一行** —— 启动前探测发现目标已在前台，直接判成功，
不重复拉起（避免把用户正在看的界面重置）。

### 3.4 重复触发去重

```
14:23:59.272 新建任务 07910d6c-... pkg=tv.danmaku.bili state=PENDING attempt=0/3
14:23:59.371 第 1 次发出启动 pkg=tv.danmaku.bili
14:23:59.586 忽略重复触发 pkg=tv.danmaku.bili 已有任务 07910d6c-... state=LAUNCHING attempt=1/3
14:24:01.491 探测 ... VISIBLE
14:24:02.373 任务结束 ... state=SUCCESS attempt=1/3
```

第二次触发被显式忽略，没有开第二套重试循环。

---

## 4. 状态机与去重规则

```
PENDING ──派发──► LAUNCHING ──首次验证时刻──► VERIFYING ──连续 N 次命中──► SUCCESS
                                              │
                                              └─窗口耗尽─► RETRY_WAIT ──► PENDING(attemptIndex+1)
                                                              │
                                                              └─次数用尽─► MANUAL_REQUIRED
任意状态 ──取消──► CANCELLED
任意状态 ──超过总时限──► MANUAL_REQUIRED(TOTAL_TIMEOUT)
```

实现上分成两层：

| 层 | 文件 | 职责 |
| --- | --- | --- |
| 纯逻辑 | `launch/LaunchAttemptEngine.kt` | 只决定「下一步做什么」，不读系统时间、不碰 Android API，可用虚拟时钟测 |
| 运行时 | `launch/LaunchAttemptController.kt` | 执行 Decision、去重、持久化、发通知 |

去重是「一个目标包同时只允许一个任务」（`inFlight: Map<pkg, Running>`），
这一个规则同时覆盖了需求里的四种冲突场景：

1. AlarmReceiver 重复触发 → 第二次被 `忽略重复触发` 挡掉
2. 休眠恢复导致重复执行 → 同上
3. 多条规则指向同一个 App → 同上
4. 重试期间原任务又被触发 → 同上

规则被停用/删除时，`cancelByRule(ruleId)` 会把它的任务一起终止
（UI 停用、UI 删除、远程 patch 三条路径都接了这个调用）。

---

## 5. 无探测能力时的降级（重要边界）

如果设备**既没有 root、也没有授予「使用情况访问权限」**，就永远读不到前台信息。
此时：

- **不做验证**，直接发一次启动命令，行为与升级前完全一致
- 在本地记录里写明 `reason=NO_PROBE`、`detail=无前台探测能力…仅确认命令已发出`

为什么不是「一律判失败」：那会让没有 root 的设备每次启动都触发重试与告警，
把一个「读不到」的问题变成骚扰。
为什么不是「一律判成功」：记录里必须写清「本次未验证」，不能让用户误以为验证过。

目标机（已 root）走的是真实验证路径，不受此降级影响。

---

## 6. 进程被杀 / 重启后的恢复

任务进行中会把快照写进 SharedPreferences（`LaunchSettings.ActiveAttempt`）。
`BootReceiver`（覆盖安装与开机）与开机路径会调用 `recover()`：

| 距开始时间 | 处理 |
| --- | --- |
| ≤ 总时限（90s） | 重新走一次完整流程（**会先探测是否已在前台**，已在前台则不重复拉起） |
| > 总时限 | 直接结案，记 `reason=INTERRUPTED`，按配置发通知，**不再自动重试** |

需求里「进程重启后不能无限重复旧任务」就落在第二行。

---

## 7. AutoUnlock 的保护

本次改动**完全没有触碰** AutoUnlock：

- `MAX_FAILURES = 2` 未改
- 「只在屏亮 + 确实锁屏时才注入」未改
- PIN 校验（写入时一次、拼命令前再一次）未改
- 失败即关闭功能并发通知未改

启动引擎与自动解锁唯一的交集是：两者都由 `KeepAliveService` 的显示/亮屏事件驱动，
但走的是各自独立的观察窗口，没有共享状态。

---

## 8. 已知边界

| # | 边界 | 说明 |
| --- | --- | --- |
| 1 | 单次探测 450ms | `su` + `dumpsys` 的固有开销。验证窗口 6s 内约 7 次采样，够用但不可能做到毫秒级 |
| 2 | 无 root 且无使用情况权限时无验证 | 见 §5 |
| 3 | UsageStats 探针有 1~2s 延迟且不分屏 | 因此只作为 Root 不可用时的降级，且只在它明确命中时采信 |
| 4 | 重试策略是全局的 | 不按规则区分。理由是「这次为什么重试了 5 次」必须能被一眼解释 |
| 5 | 前台判定依赖 dumpsys 文本格式 | Android 大版本改字段时需同步改 `DumpsysParser`；解析器已按「取含 `/` 的 token」而不是固定列位，容忍字段插入 |
| 6 | 折叠屏合盖/展开场景 | 代码按 display 分段判定、`litDisplayIds` 补查 display 1；**本轮未做合盖状态的真机验收**（需物理操作），见 §9 |

---

## 9. 待补的真机验收

以下两项需要物理操作，本轮未执行：

1. **合盖（外屏 display 1）状态下的自动启动**
2. **锁屏状态下（Keyguard 存在）的自动启动**

这两项在升级前的版本里是人工验证过的（见 `AUDIT.md` §14），
本次改动没有修改 `LaunchProxyActivity` 的选屏、点亮、绕锁屏逻辑，
只在其后追加了「结果回传 + 验证」，因此回归风险低，但仍需实测确认。
