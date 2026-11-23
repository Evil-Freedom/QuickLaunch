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

## 7. 遗留事项

1. **真机的自动解锁当前处于「安全停用」状态**（failures=2）。恢复方式：
   打开 App → 同步页 → 「锁屏自动解锁」→ 重新设置密码并打开开关。
   这必须由用户本人在手机上操作，任何远程手段都无法代劳 —— 这是设计使然。
2. **时间窗策略未实现**：需求 §4.7 的「允许执行自动解锁的策略时段」需要改动
   `AutoUnlockDecision.decide` 判定链。基于「不推翻成熟模块」原则本轮明确不做，
   已在策略字段里预留（只下发 enabled / chargingOnly）。
3. 锁屏状态下的端到端解锁验证未执行（测试机实际处于安全停用态，
   强行测试会触发真实 PIN 注入）—— 恢复启用后按 `docs/RELEASE_1.4.0.md` §8 的
   流程补测。
