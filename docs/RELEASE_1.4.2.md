# QuickLaunch v1.4.2 交付记录

版本：`1.4.2` / versionCode `25`
基线：`1.4.1` / versionCode `24`（提交 `b56da6a`）
日期：2026-11-23
设备：Motorola Razr 40 Ultra（XT2321-2）/ Android 16 / Magisk root

本轮全部工作围绕**合盖（外屏）场景**。此前该场景只有「启动成功」是通的，
「外屏自动解锁」从未验证过，而两者组合时又暴露出状态机的协同缺陷。

---

## 1. 三个真实缺陷

### 1.1 外屏启动：普通 App 无法跨屏

| 尝试 | 结果 |
| --- | --- |
| `startActivity(target, setLaunchDisplayId(1))` | 返回 `true`，但目标落到 `Display #5`（虚拟屏），外屏看不到 |
| 不指定 display，让系统决定 | 同上 |
| `su -c am start --display 1` | ✅ 目标正确出现在外屏 |

根因：Android 12+ 对普通应用跨屏启动有限制，`am` 能成功是因为 shell uid 持有跨显示权限。
**这是「命令返回成功 ≠ App 真的打开」最典型的一例** —— `startActivity` 返回 true 什么都没说明。

修复：新增 `RootDisplayLauncher`，组件名与 displayId 双重白名单校验后走
`RootUtils.capture`（带超时、排空 stderr、判退出码）。

真机：`16:33:23.858 发出启动 → 16:33:27.036 SUCCESS`，外屏 `topResumedActivity` 即目标，3.45 秒。

### 1.2 外屏解锁：`input` 的 display 路由

上一版把失败归因为「外屏 Keyguard 布局与内屏不同」，**判断错了**。三步实测：

| 实验 | 结果 | 结论 |
| --- | --- | --- |
| `input -d 1 swipe ...` | `IllegalArgumentException: Invalid arguments for display ID` | 命令没执行 |
| `input swipe ...`（不带 `-d`） | 前后截图 MD5 逐字节相同 | 事件被丢弃 |
| `sendevent /dev/input/event8 ...` | bouncer 正确弹出 | 唯一有效路径 |

根因：`input` 注入的事件默认标注 `displayId=0`，而合盖时内屏
`dumpsys SurfaceFlinger` 显示 `Display ...1762 (inactive) powerMode=Off`，
InputDispatcher 不会往已关闭的 display 投递触摸。

同时暴露第二个问题：`input text` / `input keyevent` 是**键盘事件、不走 display 路由**，
始终有效。于是旧实现变成「上滑失败但密码照发」，两次就把额度打到 0。

修复后的注入链：

1. **bouncer 巡检**：SystemUI 的 `mBouncerVisible`（关闭时 0、弹出后 6，286 ms/次），
   看不到键盘就不输入密码
2. **上滑**：按「设备 maxX/maxY 与屏分辨率的缩放比是否一致」自动识别触摸设备
   （内屏 `goodix_ts` 17280/1080 与 42240/2640 都是 16；外屏 `fts_ts` 16896/1056 与
   17056/1066 都是 16），再用 sendevent 按多点触控协议 B 注入
3. **输入**：`input text '<pin>'`；PIN 键盘输满位数会自动提交，先看自动提交结果，
   3 次轮询仍未解锁再补回车
4. **判定**：只看 `KeyguardManager.isDeviceLocked`，不看命令退出码

### 1.3 状态机把「解锁马上会开门」当成「锁屏挡住」

```
17:18:09.358 新建任务
17:18:09.403 任务结束 MANUAL_REQUIRED error=KEYGUARD_BLOCKED   ← 45 ms
17:18:10.043 AutoUnlock 才开始解锁
17:18:11.559 解锁成功                                            ← 但任务已死
```

`onBlockedByKeyguard` 立即收手在自动解锁**关闭**时是对的，开着时是错的。

修复：`keyguardWillOpen` 为 true 时转入 `RETRY_WAIT` 等 4 秒再重试一次。
**只让一次**（`keyguardRescueTried`）—— 解锁失败时必须回到立即收手，
否则变成无限等待 + 反复点亮屏幕。

---

## 2. 顺带修掉的两个隐患

| 隐患 | 说明 |
| --- | --- |
| `litDisplay` 只认 `Display.state == ON` | 真机锁屏时该值读不到（空集合），合盖会直接判「无亮屏」放弃注入。已加兜底：内屏明确 OFF 就取外屏 |
| `giveUp` 缺安全停用标记 | 之前被脚本覆盖，只剩 `failures >= 2` 的兜底。已补回：先落标记再关开关 |

---

## 3. 真机证据

| 场景 | 结果 | 耗时 | 额度 |
| --- | --- | --- | --- |
| 合盖 + 已解锁 + 启动 | ✅ 外屏 `topResumedActivity` = 目标 | 3.45 s | 1/3 |
| 合盖 + 锁屏 + 自动解锁 | ✅ `屏=1 设备=/dev/input/event8 缩放=16.0` | **1.18 s** | 0→0 |
| 展开 + 锁屏 + 自动解锁 | ✅ `屏=0 设备=/dev/input/event15 缩放=16.0` | 1.44 s | 0→0 |
| 合盖 + 锁屏 + 触发启动 | ✅ `state=SUCCESS attempt=1/3` | 3.0 s | 0→0 |
| 服务端离线 | ✅ 规则与闹钟零变化（v1.4.1 已验证） | — | — |

外屏解锁后截图（1056×1066）确认为 QuickLaunch 主界面，无锁屏、无密码框。

---

## 4. 测试

- **343 例 JVM 单测**全绿（1.4.1 的 310 → +33）
  - `OuterScreenInjectTest`：8 例，sendevent 协议序列 + 用**真机 getevent 原始输出**
    验证内外屏设备选择
  - `LockScreenLaunchTest`：+4 例，覆盖「让路一次」的三个边界 + 自动解锁不可用时的回归
- **变异检验**：去掉 `!keyguardRescueTried` 后 `只让一次 解锁失败后回到收手` 立即失败
- 服务端 e2e 32 项沿用 v1.4.1 结论（本轮未改服务端）

---

## 5. 兼容性

| 项 | 状态 |
| --- | --- |
| Room schema | 仍 v7，无迁移 |
| minSdk | 24（未改） |
| 包名 / 签名 | 未改（release + sig2 双签名） |
| Scheduler 四层优先级 | 未触碰 |
| AutoUnlock `MAX_FAILURES = 2` | 未放宽，硬上限 |
| 远程可改策略字段 | 仍只有 `enabled` / `chargingOnly` |

---

## 6. 回滚

```bash
git revert d473a89            # 外屏解锁修复
git revert <本节提交>          # 锁屏让路
```

回滚到 1.4.1（`b56da6a`）后：外屏启动仍可用（root 跨屏在 `6701d6b`），
但外屏自动解锁会退回「只亮外屏就跳过」的保守状态（不会失败，只是不解锁）。

---

## 7. 遗留

1. **时间窗策略未实现**：需求 §4.7 的「允许执行自动解锁的策略时段」需要改
   `AutoUnlockDecision.decide` 判定链。基于「不推翻成熟模块」原则明确不做，
   策略字段已预留（只下发 `enabled` / `chargingOnly`）。
2. **外屏 bouncer UI 参数未单独标定**：当前复用 `swipeFromSize` 的 0.82h→0.28h，
   在 1056×1066 实测有效。若换机型或系统更新导致失效，只需调这一个函数。
3. **系统解锁错误计数**：调试期手动注入过一次错误 PIN（Android 在 PIN 输满位数时
   自动提交，无法「只输入不提交」），系统侧 +1。重启手机清零，与 App 的重试额度无关。
4. **VPS 未部署**：`quicklaunch_vps.xml` 的调试配置已从手机清除
   （原指向 `127.0.0.1:8779`，正式部署后会每分钟连一个不存在的地址）。
   正式部署按 `docs/VPS_REMOTE.md` 执行后，从 App 重新配对。
