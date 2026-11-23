# QuickLaunch 1.4.0 交付记录

> 基线 v1.3.18 / versionCode 22 → 本版 v1.4.0 / versionCode 23
> 交付日期：2026-11-23
> 验收设备：Motorola Razr 40 Ultra（XT2321-2 / SN `ZY22HKL48G`）/ Android 16 / Magisk root

---

## 1. 交付物清单

| # | 交付物 | 位置 |
| --- | --- | --- |
| 1 | 完整 Android 项目 | 本仓库（`app/`） |
| 2 | VPS 服务端项目 | `vps/` |
| 3 | 数据库迁移脚本 | 无需新增 —— 见 §6.2 |
| 4 | Docker Compose 配置 | `vps/docker-compose.yml` + `vps/Dockerfile` + `vps/Caddyfile` |
| 5 | 自动重试测试报告 | `docs/LAUNCH_ENGINE.md` §3 |
| 6 | 远程规则同步测试报告 | `docs/VPS_REMOTE.md` §6 |
| 7 | 安全审计 | 本文 §7 + `docs/VPS_REMOTE.md` §5 |
| 8 | Root 真机测试证据 | `docs/LAUNCH_ENGINE.md` §3（原始 logcat） |
| 9 | 正式版 APK | `app/build/outputs/apk/release/app-release.apk` |
| 10 | sig2 APK | `app/build/outputs/apk/sig2/app-sig2.apk` |
| 11 | 回滚方案 | 本文 §5 |
| 12 | 用户操作说明 | 本文 §4 + `docs/VPS_REMOTE.md` §3 |

---

## 2. 变更清单

### 2.1 新增（10 个 Kotlin 文件 + 3 个测试 + 服务端 15 个文件）

```
app/src/main/java/com/workbuddy/quicklaunch/
├── launch/                              新增包：启动验证与重试
│   ├── LaunchAttemptState.kt            状态枚举（含转移表文档）
│   ├── LaunchPolicy.kt                  重试参数 + 越界夹紧
│   ├── LaunchAttemptEngine.kt           纯逻辑状态机（虚拟时钟可测）
│   ├── DumpsysParser.kt                 dumpsys 分段解析
│   ├── ForegroundJudge.kt               三态判定（VISIBLE/NOT_VISIBLE/UNKNOWN）
│   ├── ForegroundProbe.kt               Root / UsageStats / 组合探针
│   ├── LaunchSettings.kt                策略与执行记录持久化（SP）
│   └── LaunchAttemptController.kt       运行时：执行 Decision、去重、通知
├── service/LaunchFailureNotifier.kt     失败通知（独立渠道 + 再次启动按钮）
├── receiver/ManualRetryReceiver.kt      手动重试入口
├── receiver/VpsSyncReceiver.kt          VPS 同步定时入口
├── VpsRemoteActivity.kt                 VPS 设置页
└── remote/vps/
    ├── VpsEnvelope.kt                   信封格式与校验（纯逻辑）
    ├── VpsClient.kt                     传输层（HttpsURLConnection）
    └── VpsSync.kt                       同步编排 + 周期排程

app/src/test/java/com/workbuddy/quicklaunch/
├── LaunchAttemptEngineTest.kt           23 例（含 dumpsys 解析）
├── VpsEnvelopeTest.kt                   17 例
└── VpsInteropTest.kt                    3 例（跨语言签名锁定）

app/src/main/res/
├── layout/activity_vps_remote.xml
├── xml/network_security_config.xml
└── values/strings.xml                   新增 8 条失败通知文案

vps/                                     FastAPI + SQLite + Caddy
├── app/{main,db,auth}.py
├── app/templates/{base,login,rules,versions,devices,audit}.html
├── tests/e2e_test.py                    28 项端到端自检
├── Dockerfile / docker-compose.yml / Caddyfile / .env.example
└── .gitignore
```

### 2.2 修改（7 个既有文件，均为最小改动）

| 文件 | 改动 | 风险控制 |
| --- | --- | --- |
| `LaunchService.kt` | `start()` 增加可选 `attemptId`；未派出时回调失败 | 参数默认 null → 旧调用行为逐字节不变 |
| `LaunchProxyActivity.kt` | 启动后回传 `(attemptId, accepted)` | 无 attemptId 时直接 return，选屏/亮屏/绕锁屏逻辑零改动 |
| `AlarmReceiver.kt` | 改调 `LaunchAttemptController.begin`；规则停用时清理任务 | 排下一次闹钟的代码顺序未动 |
| `WifiReceiver.kt` / `BluetoothReceiver.kt` / `PowerConnectionReceiver.kt` | 同上（只换调用点） | 匹配逻辑零改动 |
| `BootReceiver.kt` | 追加 `recover()` 与 `VpsSyncScheduler.schedule()` | 都包 `runCatching`，放在最后 |
| `LaunchFragment.kt` | 停用/删除规则时 `cancelByRule` | 只加两行 |
| `RemoteRuleApi.kt` / `RuleStore.kt` / `RemoteBindings.kt` | 远程停用规则时清理重试任务（`ScheduleHook.cancelActiveAttempts`） | 新方法默认空实现，既有假实现不受影响 |
| `RootUtils.kt` | 新增 `capture()`（取 stdout） | 既有 `runAsRoot` 未动 |
| `RemoteAuth.kt` | 新增 `hmacSha256Hex()` | 既有签名/加密逻辑未动 |
| `AndroidManifest.xml` | 新增 2 个 receiver、1 个 activity、`PACKAGE_USAGE_STATS`、`networkSecurityConfig` | 无权限移除 |
| `app/build.gradle` | versionCode 22→23，versionName 1.3.18→1.4.0 | 签名配置零改动 |

### 2.3 明确未动

`Scheduler.kt`（四层优先级、调休、班表、随机窗口）、`Service/HolidayChecker/ShiftTable`、
`AutoUnlock.kt`、`PinVault.kt`、`KeepAliveService.kt`、`ScreenOnOverlay.kt`、
`RemoteAuth` 的签名与加密实现、`MiniHttpServer`、`RemoteServer`、`RemoteRelay`、
Room schema（仍为 version 7）、包名、签名方式、minSdk 24。

---

## 3. 验收对照（需求 §10）

### A. 自动启动

| 标准 | 结论 | 证据 |
| --- | --- | --- |
| 目标 App 正常时能够启动 | ✅ | `docs/LAUNCH_ENGINE.md` §3.1，3.16s 判定 SUCCESS |
| 目标 App 启动失败时能够自动重试 | ✅ | §3.2，3 次尝试间隔 5.0s / 15.0s |
| 多次失败后能够正确通知 | ✅ | §3.2 通知 dump：标题/正文/「再次启动」action 齐全 |
| 不会无限循环 | ✅ | 第 3 次后停 `MANUAL_REQUIRED`，44.3s 结束 |
| 不会因重复事件重复启动 | ✅ | §3.4，第二次触发被 `忽略重复触发` 挡掉 |

### B. 远程规则

| 标准 | 结论 | 证据 |
| --- | --- | --- |
| 能从国内 VPS 修改启动规则 | ✅ | `docs/VPS_REMOTE.md` §6，v2 把 id=2 改成 07:15 |
| 手机能安全获取新规则 | ✅ | 签名 + 时间窗 + nonce + 版本单调，`VpsEnvelopeTest` 17 例 |
| 配置修改能持久化 | ✅ | Room 落库核对（含 WAL 回放） |
| 服务器离线时本地规则继续工作 | ✅ | §6 离线测试：规则与闹钟零变化 |
| 可以恢复旧版本配置 | ✅ | 回滚生成 v3 → 手机应用 → id=2 回到 18:00 |

### C. 稳定性

| 标准 | 结论 | 证据 |
| --- | --- | --- |
| 手机重启后规则正常恢复 | ✅（逻辑层） | `BootReceiver` → `recover()`；`canResume` 单测覆盖 |
| 软件更新后规则正常恢复 | ✅ | 覆盖安装 1.3.18→1.4.0 后 schema 7、4 条规则完好 |
| 折叠屏内外屏切换正常 | ⚠️ 待补 | 需物理合盖，见 §8 |
| 息屏和锁屏场景经过真机验证 | ⚠️ 待补 | 需物理锁屏，见 §8 |
| 不出现持续 WakeLock 或线程泄漏 | ✅ | 未新增 WakeLock；探测用单线程池（守护线程），任务结束即无引用 |

### D. 安全

| 标准 | 结论 | 证据 |
| --- | --- | --- |
| 远程配置需要认证 | ✅ | 每请求 HMAC 签名；无签名/错签名/过期/重放全部 401 |
| 不接受任意 Shell 命令 | ✅ | 下发内容只有排程字段白名单；`targetPackage` 不可远程修改 |
| 不暴露不必要的管理接口 | ✅ | 管理界面全部 Cookie 保护；API 只有 3 个端点 |
| 不泄漏 PIN、Token 和密钥 | ✅ | 密钥不回显、不进 URL、不进日志 |
| 不引入境外 VPS 依赖 | ✅ | 仅 Caddy（Let's Encrypt）与 Python 标准库 + FastAPI |

### E. 发布

| 标准 | 结论 |
| --- | --- |
| 所有原有测试通过 | ✅ 257/257 |
| 新增测试通过 | ✅ 43 例（23 + 17 + 3） |
| 真机核心场景通过 | ✅ 成功/失败/去重/已在前台/远程同步/离线容错 |
| APK 可以正常安装和运行 | ✅ 覆盖安装成功，数据无丢失 |
| 提供完整证据 | ✅ 本文 + 两个专项文档的原始日志 |

**测试总计：300 例 JVM 单测全绿 + 28 项服务端自检全绿。**

---

## 4. 用户操作说明

### 4.1 新增的设置项在哪

启动页 → 「远程访问」→ 「国内服务器规则（VPS）」

页面上可以改：

| 项 | 说明 |
| --- | --- |
| 启用 VPS 同步 | 总开关 |
| 服务器地址 / 设备 ID / 设备密钥 | 由服务器「设备」页生成 |
| 同步间隔 | 默认 600 秒 |
| 最多尝试次数 | 1~5，默认 3 |
| 单次验证窗口 | 2000~30000ms，默认 6000 |
| 重试间隔 | 逗号分隔毫秒，默认 `5000,15000` |
| 最终失败时通知 | 默认开 |

页面下方显示「最近启动结果」：时间、成败、次数、失败原因。

### 4.2 失败通知长什么样

- 标题：`<App 名> 没能打开`
- 正文：`已尝试 3 次：启动后未检测到进入前台。点此再试一次`
- 按钮：`再次启动` → 重新走一遍完整验证流程（不是直接拉起）

渠道独立（「启动失败提醒」），可以单独静音而不影响启动过程的前台服务通知。

### 4.3 怎么确认验证真的在工作

```bash
adb logcat -s QL-LaunchAttempt
```

每条任务的完整轨迹（新建 → 发出启动 → 每次探测结果 → 结束状态）都会打印，
release 版本也保留这些日志（这是排查「为什么没启动」的唯一现场）。

---

## 5. 回滚方案

### 5.1 回滚 APK

```bash
# 备份的旧版 APK
adb install -r backups/pre-launch-engine-*/com.workbuddy.quicklaunch-1.3.18-installed.apk
```

- 同签名，可直接覆盖安装
- versionCode 从 23 降到 22 属降级，Android 允许 `adb install -r -d`
- Room：`fallbackToDestructiveMigrationOnDowngrade(true)` 已存在，
  但 **v1.4.0 没有升 schema（仍是 7）**，所以降级不会丢数据

### 5.2 回滚数据（极端情况）

```bash
adb push backups/pre-launch-engine-*/ql-data-backup.tar.gz /sdcard/
adb shell su -c 'cd /sdcard && rm -rf ql-data-backup && tar xzf ql-data-backup.tar.gz'
adb shell su -c 'rm -rf /data/data/com.workbuddy.quicklaunch && cp -r /sdcard/ql-data-backup /data/data/com.workbuddy.quicklaunch'
adb shell su -c 'chown -R $(stat -c %u:%g /data/data/com.workbuddy.quicklaunch) /data/data/com.workbuddy.quicklaunch'
```

### 5.3 关掉新功能（不改 APK）

| 想关的 | 怎么做 |
| --- | --- |
| 自动重试 | VPS 设置页把「最多尝试次数」设为 1 → 行为退化为「启动一次 + 验证一次」 |
| 完全不验证 | 无法从界面关；但无 root 且撤销「使用情况访问权限」时会自动退化为不验证 |
| VPS 同步 | 关闭「启用 VPS 同步」开关 |
| 失败通知 | 关「最终失败时通知」，或在系统设置里静音「启动失败提醒」渠道 |

### 5.4 回滚服务端

```bash
# 代码
cd /opt/quicklaunch-vps && git checkout v1.3.18 -- . 2>/dev/null || true
# 或直接停掉：手机侧关掉同步开关即可，不影响任何本地能力
docker compose down
```

---

## 6. 关键设计决策（供后续维护）

### 6.1 为什么重试逻辑集中在一个 Controller

需求明确禁止「多个 Receiver/Activity/Service 各自实现一套重试」。
实现上更进一步：**策略判断在纯逻辑类里，执行在 Controller 里，两者都不在组件里**。
组件（Receiver/Service/Activity）只负责「报告发生了什么事」。

### 6.2 为什么没有数据库迁移

v1.4.0 的所有新状态都存在 SharedPreferences：

- `quicklaunch_launch`：重试策略、进行中任务快照、最近 20 条结果
- `quicklaunch_vps`：服务器地址、设备身份、已应用版本

原因：这些是「少量键值 + 一个短列表」。为它们加 Room 表就要写第 8 次 Migration，
而迁移正是这个项目历史上最大的数据风险来源（`AUDIT.md` §3.3）。
规则本体仍然只在 `automations` 表里，不存在第二份真源。

### 6.3 为什么用 `setAndAllowWhileIdle` 排 VPS 同步

精确闹钟在 Doze 下每个应用有配额。让 10 分钟一次的配置同步去消耗配额，
会挤掉真正需要精确的「定时启动」闹钟。配置同步允许几分钟抖动。

### 6.4 为什么前台判定只认 dumpsys

见 `docs/LAUNCH_ENGINE.md` §2 的实测对照表。`dumpsys activity top` 在多屏设备上
会返回外屏的桌面 Activity，是这里最容易踩的坑。

---

## 7. 安全复审

### 7.1 新引入的攻击面

| 面 | 风险 | 缓解 |
| --- | --- | --- |
| Root `su -c dumpsys` | 命令注入 | 命令是**常量字面量**，不拼接任何外部输入（连包名都不进命令） |
| 每次探测 fork 一个 su | 资源耗尽 | 单线程池串行 + 6s 超时 + 探测间隔 ≥800ms |
| VPS 出站请求 | 中间人 | TLS + 响应 HMAC 签名（双层） |
| VPS 下发内容 | 远程执行原语 | 字段白名单，`targetPackage` 不可改 |
| 设备密钥存储 | 泄露 | 与既有配对密钥同一策略（应用私有 SP）；威胁模型同 `AUDIT.md` H3 |
| 管理界面 | 口令爆破 | 摘要存储 + 连错 5 次锁 10 分钟 + 审计日志 |
| 明文 HTTP | 配置泄露 | 网络策略默认禁明文，仅回环例外 |

### 7.2 三处「必须审」的实现细节

1. **`VpsEnvelope.signature` 把 `alg` 也放进 HMAC 输入**
   —— 防止攻击者把 `alg` 改成 `NONE` 之类的降级替换。
2. **`updated=false` 的响应同样要求完整签名**
   —— 否则任何人都能发一个 `{"version":0}` 让客户端「安静地停止同步」。
3. **`RemoteRuleApi.patch` 的 `createdAt` 必须沿用旧值**
   —— VPS 通道复用了这条路径，所以远程改规则不会让规则跳到列表顶部。

### 7.3 未修复的已知项

| # | 项 | 说明 |
| --- | --- | --- |
| 1 | 设备密钥在应用私有 SP 明文 | 与既有远程配对密钥一致。前提是攻击者已拿到 `/data/data` 访问权，此时威胁已成立 |
| 2 | 服务端 nonce 缓存在进程内存 | 重启后短暂失忆；但重放仍需过时间窗与版本单调两道关 |
| 3 | 无 mDNS | 与既有远程通道一致，需手输地址 |

---

## 8. 未完成项（需要物理操作）

以下两项需要真实操作设备，本轮未执行，**不能声称已通过**：

1. **合盖（外屏 display 1）状态下的自动启动**
   - 需要：合上手机 → 触发一次启动 → 观察目标是否出现在外屏
2. **锁屏状态下的自动启动**
   - 需要：锁屏 → 触发 → 观察是否点亮并启动

这两项在 1.3.18 是人工验证过的，本次改动没有修改选屏/点亮/绕锁屏逻辑，
只在后面追加了结果回传与验证，因此回归风险低。但按验收标准，
它们必须实测签字。

测试命令（供操作时使用）：

```bash
# 触发一次启动（设置 App 作靶子）
adb shell su -c "am broadcast -n com.workbuddy.quicklaunch/.receiver.ManualRetryReceiver \
  --es manual_retry_pkg com.android.settings --es manual_retry_app_name 设置"

# 观察
adb logcat -s QL-LaunchAttempt
adb shell su -c "dumpsys activity activities | grep -m2 topResumedActivity"
adb exec-out screencap -d 1 -p > outer.png    # 外屏截图
```
