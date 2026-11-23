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

## 9. 锁屏场景：真机验收与由此发现的缺陷（v1.4.2）

2026-11-23 16:00 在真机锁屏状态下做了完整验收，**发现了三个真实缺陷**：

### 9.1 现象

锁屏 + 息屏触发一次启动，结果：

```
16:00:57 新建任务 ... state=PENDING
16:00:57 第 1 次发出启动
16:00:59 ~ 16:01:18 探测 ×14 全部 NOT_VISIBLE src=USAGE_STATS top={} lit=[]
16:01:10 第 2 次发出启动          ← 无意义的重试开始
...最终 44 秒后 MANUAL_REQUIRED
```

屏幕被点亮了（`Dozing → Awake`），但目标 App 始终没有前台。

### 9.2 根因（真机 dump 实证）

锁屏状态下 `dumpsys activity activities` 的结构**完全不同**：

```
Display #0 (activities from top to bottom):
  * Task{7e8393c #1283 ... visible=true visibleRequested=false ...}
    mLastPausedActivity: ActivityRecord{... u0 com.android.settings/.Settings ...}
    isSleeping=true
```

- **没有任何 `topResumedActivity`** —— Keyguard 存在时没有 Activity 会进入 resumed
- 每个 Task 带 `isSleeping=true`
- 目标 App 只出现在 `mLastPausedActivity`

所以解析器返回空 → UNKNOWN → 降级到 UsageStats → 判 NOT_VISIBLE →
按普通失败重试 3 次，**每次都点亮一次屏幕**，而锁屏状态不会因为重试而改变。

### 9.3 三个修复

| # | 缺陷 | 修复 |
| --- | --- | --- |
| 1 | 睡眠态没有 `topResumedActivity` 时被当成「读不到」 | `DumpsysParser.parseState` 解析 `isSleeping` 与 `mLastPausedActivity`；新增 `dumpState.sleepingWith(pkg)` |
| 2 | 「目标已在锁屏后」与「目标没启动」混为一谈 | 新增 `ForegroundJudge.Verdict.BLOCKED_BY_KEYGUARD`；状态机在收到它时**立即收手**（`onBlockedByKeyguard`，不重试） |
| 3 | 亮屏集合读不到（`lit=[]`）导致判定走降级分支 | `litDisplayIds` 增加兜底：`DisplayManager` 全空但 `PowerManager.isInteractive()` 为真时，视为主屏亮着 |

顺带修了第四个问题：`recover()` 会把**刚创建 100ms 的任务**当成遗留任务清掉
（真机复现：`begin()` 写完快照后，系统的 `MY_PACKAGE_REPLACED` 恰好唤起进程）。
现在 5 秒内且内存中已有活动任务时跳过恢复。

### 9.4 修复后的真机结果

```
16:11:59.983 新建任务 63dd6fa7-... pkg=com.android.settings state=PENDING
16:12:00.080 任务结束 63dd6fa7-... state=MANUAL_REQUIRED attempt=1/3 error=KEYGUARD_BLOCKED
```

- **97ms 收手**（修复前 44 秒 + 3 次点亮屏幕）
- 通知文案：`设备处于锁屏状态，目标 App 被挡在锁屏后面（已停止重试，解锁后可手动再试）`
- 尝试次数 1/3：没有消耗重试额度，也没有反复唤醒屏幕
- `dumpsys trust` 确认设备仍锁屏

### 9.5 一个必须说明的测量偏差

同一场景下，`adb shell su -c 'am broadcast ...'` 从**命令发出**到
ActivityManager **收到广播**之间有约 36 秒（真机日志：命令 16:09:42，
`ActivityManager: Broadcasting` 16:10:18）。这一段发生在 adb/Magisk 侧，
与 App 无关 —— ActivityManager 收到广播到任务创建只有 **94ms**。

排查过程中一度误以为是 App 阻塞，因此顺手做了一项仍值得保留的优化：
把「有没有前台探测能力」的判断从广播线程移到探测线程
（它内部要 fork `su`，不该阻塞广播处理）。

### 9.6 合盖外屏启动（后续已在 v1.4.2 解决）

本节写作时合盖外屏启动尚未验证。后续实测发现普通 App 无法跨屏启动
（`setLaunchDisplayId(1)` 返回 true 但目标落到 Display #5 虚拟屏），
改由 `RootDisplayLauncher` 走 `am start --display N` 解决，真机 3.45 秒成功。
详见 `docs/RELEASE_1.4.2.md` 与 §10。

---

## 10. 与自动解锁的协同（v1.4.2）

### 10.1 发现的问题

自动解锁启用后，出现了新形态的失败：

```
17:18:09.358 新建任务 state=PENDING
17:18:09.403 任务结束 MANUAL_REQUIRED error=KEYGUARD_BLOCKED   ← 45 ms 判死
17:18:10.043 AutoUnlock watch 启动
17:18:11.559 自动解锁成功                                        ← 但任务已经死了
```

`onBlockedByKeyguard` 立即收手这个行为，在自动解锁**关闭**时是正确的
（锁屏不会自己消失，重试只会反复点亮屏幕）。但自动解锁开着时，
锁屏**马上就会**被打开 —— 此时判死等于把一次本来会成功的启动丢掉。

### 10.2 修复：只让路一次

`onBlockedByKeyguard` 增加 `keyguardWillOpen` 参数。为 true 时转入 `RETRY_WAIT`
并等 `KEYGUARD_RESCUE_DELAY_MS`（4 秒，覆盖实测 1.18~1.44 秒的解锁耗时 + 动画余量），
到点后重新发起一次启动。

`keyguardWillOpen` 由控制器判定，三个条件全满足才算：

```
AutoUnlock.isEnabled && AutoUnlock.hasPin && !AutoUnlock.isSecurityLocked
```

并且会顺手调一次 `AutoUnlock.watch()`：`SCREEN_ON` 广播可能早于本任务
（闹钟触发时屏幕刚亮），那时 12 秒观察窗口可能已经走完，干等不会有人来开门。

**只让一次**是硬约束（`keyguardRescueTried`）：解锁失败（密码错、被停用、
root 掉了）时必须回到「立即收手」，否则就是无限等待 + 反复点亮屏幕。

### 10.3 真机结果（合盖 + 锁屏 + 触发启动）

```
17:24:46.119 第 1 次发出启动
17:24:46.441 AutoUnlock 判定 ATTEMPT locked=true 屏=1
17:24:46.820 上滑 display=1 设备=/dev/input/event8 缩放=16.0
17:24:47.586 Keyguard 已解除 display=1（自动提交）
17:24:48.265 探测 VISIBLE top={1=com.workbuddy.quicklaunch} lit=[1]
17:24:49.136 探测 VISIBLE（连续 2 次命中）
17:24:49.137 任务结束 state=SUCCESS attempt=1/3
```

这次解锁比首次探测还快（约 2.1 秒），因此没走到让路分支 —— 说明让路只在
解锁较慢时才会用到，是兜底而非主路径。外屏 `topResumedActivity` 确认为目标，
`attempt=1/3`，自动解锁 `failures` 保持 0。
