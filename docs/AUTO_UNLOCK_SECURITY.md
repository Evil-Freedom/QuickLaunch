# 自动解锁专项安全报告（v1.4.1）

> 覆盖需求 §4 全部安全约束的落实情况与证据。
> 基线：`util/AutoUnlock.kt`（v1.3.18 起稳定）+ `PinVault.kt` + `AutoUnlockDecision.kt`。
> 本次升级**没有重写解锁系统**，只在既有实现上扩展了状态记录、降级提示与远程策略门控。

---

## 1. 判定链（未改动，重新核对过源码）

```
enabled 且 hasPin
  → failures < MAX_FAILURES(=2)
  → deviceLocked 且至少一块屏亮着
  → chargingOnly 时要求正在充电
  → 距上次尝试 ≥ 700ms
  → ATTEMPT
```

`AutoUnlockDecision.decide` 是纯函数，13 例既有单测 + 新增门控测试共同钉死。
**判定顺序一个字没动。**

## 2. PIN 存储（PinVault 审计）

| 项 | 实现 | 结论 |
| --- | --- | --- |
| 主路径 | AndroidKeyStore AES-GCM，密钥不可导出、绑定本机 | ✅ 真机 blob 前缀 `ks1:` |
| 降级路径 | XOR + Base64 混淆，前缀 `x1:` | ✅ 明确标注「不是加密」，UI 显示降级警告 |
| 降级可见性 | `PinVault.isKeystoreAvailable()` 真实试产密钥；`storageKindOf(blob)` 读前缀 | ✅ 新增；UI 与远程门控都用它 |
| PIN 出手机 | 不存在。上报快照只含 `hasPin`（布尔）与 `storage`（枚举） | ✅ 见 §5 |
| PIN 进日志 | 全代码 grep 无 `Log.*pin`、异常不含 PIN 内容 | ✅ |

**审计结论**：PIN 的机密性依赖 AndroidKeyStore。降级路径只防肩窥，
界面上会显示黄色警告并建议不要开启自动解锁 —— 这一点在本次升级前
只能靠读源码知道，现在是用户可见的。

## 3. 失败保护（不可放宽）

| 约束 | 落实 | 证据 |
| --- | --- | --- |
| `MAX_FAILURES = 2` 硬上限 | 常量在 `AutoUnlockDecision`，无任何远程修改路径 | 单测 + 源码 |
| 达到上限自动停用 | `giveUp()` 先写 `auto_disabled` 标记再关开关 | 标记与开关分两步，防竞态越过保护 |
| 「手动关闭」与「安全停用」可区分 | `isSecurityLocked()` = 标记或计数达上限 | 新增 |
| 远程不能重新启用安全停用的功能 | `remoteEnableGate` 第一条就是 SECURITY_LOCKED | `AutoUnlockRemoteGateTest` 4 例覆盖优先级 |
| 本地恢复唯一入口 | `AutoUnlockActivity` 的开关 → `setEnabled(true)` 清标记 | 仅 UI 路径 |
| 单次失败通知 | `notifyFailure(count)`，文案不含任何 PIN 信息 | 新增；受界面开关控制 |
| 安全停用通知 | `notifyGiveUp`，**不受开关控制**（必须让用户知道） | 既有行为保留 |

**真机证据**：测试机 `auto_unlock_failures = 2`（用户真实环境里已触发过保护），
此时远程下发 `enabled=true` 的结果：

```
15:41:54.016 QL-VpsSync: v7 应用 3 条；解锁策略被拒绝：SECURITY_LOCKED
                          （自动解锁因连续失败已停用，必须在手机上手动重新启用）
```

同步后 SP 核对：`auto_unlock_enabled=false`、`auto_unlock_charging_only=false` ——
**策略原子拒绝，连 chargingOnly 也没有被改**（拒绝发生在任何写入之前）。
服务端看板记录：`securityLocked=true, failures=2, enabled=false`。

## 4. 远程策略门控（v1.4.1 新增）

下发的策略只有两个字段（`enabled` / `chargingOnly`），
白名单在服务端与客户端各有一份：

| 端 | 位置 | 效果 |
| --- | --- | --- |
| 服务端 | `db.py` 的 `UNLOCK_POLICY_FIELDS` + `set_unlock_policy` 夹紧 | 即使数据库被手工塞进 `pin` 字段也不会下发 |
| 客户端 | `AutoUnlock.RemotePolicy` 只有 2 个属性 | 构造时就传不进别的东西 |

门控顺序（`AutoUnlockDecision.remoteEnableGate`，纯函数，10 例单测）：

```
安全停用 → 无 PIN → PIN 为降级存储 → 无 root → 允许
```

每一条拒绝都有稳定原因码，直接进回报明细，看板可见：

| 原因码 | 场景 |
| --- | --- |
| `SECURITY_LOCKED` | 本地因失败停用（远程永远无法越过） |
| `NO_PIN` | 手机未设置密码 |
| `INSECURE_STORAGE` | PIN 是 XOR 混淆态（远程不启用，本地可由用户知情后启用） |
| `NO_ROOT` | 无可用 root |

**本地优先的五个维度**（需求 §4.8）逐条核对：

| 维度 | 落实 |
| --- | --- |
| 本地失败保护优先 | 门控第一条 |
| 本地 PIN 状态优先 | 门控第二条；PIN 本身只在本地读写 |
| 本地设备锁定状态优先 | `decide()` 每次实时读 `KeyguardManager`，VPS 无法影响 |
| 本地 Root 状态优先 | 门控第四条 |
| 本地安全禁用状态优先 | 门控第一条 |

## 5. 状态上报的边界（AutoUnlockStatus）

上报字段全集（`AutoUnlockStatus.snapshot`）：

```json
{"enabled": false, "hasPin": true, "storage": "KEYSTORE", "chargingOnly": false,
 "failures": 2, "maxFailures": 2, "securityLocked": true,
 "lastAttemptAt": 0, "lastResult": ""}
```

- 没有任何 PIN 派生内容（无长度、无哈希、无掩码）
- 服务端入库前再做一次白名单过滤（`ALLOWED_UNLOCK_STATUS_FIELDS`），
  未来客户端误带多余字段也不会落库

## 6. 真机回归结论（Motorola Razr 40 Ultra / Android 16）

| 场景 | 结果 |
| --- | --- |
| Keyguard 真实状态验证（不看退出码） | ✅ 既有 `injectAndVerify` 未改动，先上滑再注入再回读 `isDeviceLocked` |
| 注入顺序（上滑→text→回车，单条 su） | ✅ 未改动 |
| 失败计数累计 | ✅ 真机实际值 2/2 就是保护触发的直接证据 |
| 达到上限自动停用 + 通知 | ✅ 功能当前处于停用态（用户环境真实触发） |
| 远程启用被拒绝 | ✅ 见 §3 的 logcat 与 SP 证据 |
| VPS 离线 | ✅ 同步失败时策略与状态零变化（`docs/VPS_REMOTE.md` §6） |

## 7. 合盖外屏注入：真因与修复（v1.4.2，真机实证）

### 7.1 现象

2026-11-23 合盖状态下自动解锁连续两次失败，`failures` 直接打到 2/2 触发安全停用。
当时的判断是「外屏 1056×1066 的 Keyguard 布局与内屏不同，注入参数不适用」——
**这个判断是错的**，也因此一度加了一道「只亮外屏就跳过注入」的保护。

### 7.2 真因（三步实测定位）

| 实验 | 结果 | 结论 |
| --- | --- | --- |
| `input -d 1 swipe ...` | `IllegalArgumentException: Invalid arguments for display ID` | 该参数不支持 display 1，**命令压根没执行** |
| `input swipe ...`（不带 `-d`） | 前后截图 MD5 完全相同 | 事件被丢弃，外屏零反应 |
| `sendevent /dev/input/event8 ...` | bouncer 正确弹出（MD5 变化，与手动上滑同图） | 绕过 display 路由才有效 |

根因：`input` 注入的事件默认标注 `displayId=0`，而合盖时内屏
`dumpsys SurfaceFlinger` 显示 `Display ...1762 (inactive) powerMode=Off` ——
InputDispatcher 不会往已关闭的 display 投递触摸事件，于是外屏（display 1）
一个触摸都收不到。

同时暴露第二个问题：**`input text` 与 `input keyevent` 是键盘事件，不走 display 路由，
始终有效**。所以旧实现里「上滑失败但密码照发」的组合会让密码打到空处，
两次之后归零 —— 这才是失败的完整机制。

### 7.3 修复后的注入链

1. **bouncer 巡检**：`dumpsys activity service com.android.systemui/.SystemUIService`
   里的 `mBouncerVisible`，实测 bouncer 关闭时 0、弹出后 6（286 ms/次）
2. **上滑**：按「设备 X/Y 最大原始值 ÷ 屏分辨率」的比例一致性自动识别触摸设备节点
   （真机：内屏 `goodix_ts` 17280/1080 与 42240/2640 都是 16；外屏 `fts_ts`
   16896/1056 与 17056/1066 都是 16），再用 sendevent 按多点触控协议 B 注入
3. **输入**：`input text '<pin>'`（键盘路由，无需 `-d`）
4. **提交**：PIN 键盘输满位数会自动提交，先看自动提交结果；3 次轮询仍未解锁再补回车
5. **判定**：只看 `KeyguardManager.isDeviceLocked`，不看命令退出码

### 7.4 关键安全改进：不再误扣额度

新增 `UnlockOutcome.NOT_READY` —— 区分「环境没准备好」（bouncer 没弹出、
找不到触摸设备、命令执行失败）与「密码不对」：

| 结果 | 计数 | 行为 |
| --- | --- | --- |
| `UNLOCKED` | 清零 | 停止观察 |
| `WRONG_PIN` | +1 | 通知；达上限则停用 |
| `NOT_READY` | **不变** | 只记日志，停止本次观察窗口 |

`NOT_READY` 不计数是硬要求：一次环境抖动就吃掉半份额度、两次抖动直接停用功能，
比「不解锁」更糟。

### 7.5 真机回读

**内屏（展开态）**：

```
17:09:11.979 判定 ATTEMPT locked=true 屏=0 失败=0
17:09:12.422 上滑 display=0 设备=/dev/input/event15 缩放=16.0 执行=true
17:09:13.418 Keyguard 已解除 display=0（自动提交）
17:09:13.419 自动解锁成功 → last_result=SUCCESS、failures=0
```

**外屏（合盖态）**：

```
17:14:49.742 判定 ATTEMPT locked=true 屏=1 失败=0
17:14:50.128 上滑 display=1 设备=/dev/input/event8 缩放=16.0 执行=true
17:14:50.524 输入密码 display=1 命令执行=true（内容不记录）
17:14:50.925 Keyguard 已解除 display=1（自动提交）
17:14:50.925 自动解锁成功 display=1
```

| 项 | 内屏 | 外屏 |
| --- | --- | --- |
| 从判定到解锁耗时 | 1.44 s | **1.18 s** |
| 选中的触摸设备 | goodix_ts `/dev/input/event15` | fts_ts `/dev/input/event8` |
| 缩放比 | 16.0 | 16.0 |
| 失败计数变化 | 0 → 0 | 0 → 0 |
| 视觉证据 | — | 外屏 1056×1066 截图显示 QuickLaunch 主界面，无锁屏 |

两屏都是**一次成功、零额度消耗**，并且触摸设备由比例算法自动选中 —— 内屏不会误选外屏的 `fts_ts`，反之亦然。

### 7.6 顺带修掉的第四个隐患

`litDisplay` 原本只认 `Display.state == STATE_ON`。真机锁屏时这个值不一定报 ON
（会读成空集合），合盖场景下会直接判「没有亮屏」而放弃注入。
现在加了兜底：`PowerManager.isInteractive()` 为真但读不到任何 ON 屏时，
**内屏明确 OFF 就取外屏**，否则取内屏。判错时 bouncer 不会出现在选错的屏上，
上层走 `NOT_READY`，不消耗额度。

## 8. 遗留事项

1. **时间窗策略未实现**：需求 §4.7 的「允许执行自动解锁的策略时段」需要改动
   `AutoUnlockDecision.decide` 判定链。基于「不推翻成熟模块」原则本轮明确不做，
   已在策略字段里预留（只下发 enabled / chargingOnly）。
2. **外屏 bouncer 的 UI 参数未单独标定**：当前复用 `swipeFromSize` 的
   0.82h→0.28h 比例，在 1056×1066 上实测有效。若后续换机型或 system 更新导致
   比例失效，调这一个函数即可，注入链其余部分不受影响。
3. **系统解锁错误计数**：调试期手动注入过一次错误 PIN（Android 在 PIN 输满位数时
   自动提交，无法「只输入不提交」），系统侧错误计数 +1。重启手机即清零，
   不影响数据，也与本应用的重试额度无关。
