# QuickLaunch 代码审计材料

> 本文档面向外部代码审计（人工或 AI）。所有数字与行为描述均取自当前仓库 HEAD 的真实代码，
> 不含推测。若审计方需要核对，文件路径与函数名均可直接定位。

---

## 0. 一句话说明

QuickLaunch 是一个**单机、无服务端**的 Android 工具应用：用户为某个 App 配置「触发条件」
（定时 / 蓝牙连上某设备 / 连上某 WiFi / 插上充电器），条件满足时由本应用**自动把目标 App 拉到前台**。
附加三块能力：中国法定节假日与调休感知的排程、局域网/中继远程改规则、以及 rooted 设备的锁屏自动解锁。

它解决的问题是：Android 10+ 禁止后台启动 Activity，普通"快捷方式/定时任务"应用在锁屏或息屏时
拉起目标 App 会静默失败。本项目用「前台服务 + 悬浮窗权限豁免 + 透明中转 Activity」绕开该限制。

---

## 1. 工程技术档案

| 项 | 值 |
| --- | --- |
| 语言 / 构建 | Kotlin 100%，Gradle 8.11.1，AGP（Kotlin DSL 之外的 Groovy），KSP |
| compileSdk / targetSdk | 36（Android 16） |
| minSdk | 24（Android 7.0） |
| versionName / versionCode | 1.3.18 / 22 |
| 主源码 | 79 个 `.kt`，12,003 行 |
| 单元测试 | 15 个测试类，4,433 行，**257 个 `@Test`**，纯 JVM（`testDebugUnitTest`） |
| 第三方依赖 | **仅 10 条** `implementation`，无网络库 / 无 DI 框架 / 无协程（用自建线程池） |
| 包名 | `com.workbuddy.quicklaunch`（签名包另有 `.sig2` 后缀变体） |
| 目标设备 | Motorola Razr 40 Ultra（`ZY22HKL48G`，折叠屏，Android 16），已 root（Magisk） |

依赖清单（`app/build.gradle`）：`core-ktx`、`appcompat`、`activity-ktx`、`material`、
`constraintlayout`、`recyclerview`、`viewpager2`、`room-runtime`、`room-ktx`、`dynamicanimation`。
测试侧另加 `junit` 与**真实 `org.json` 实现**（原因见 §4.3）。

**设计取向（审计时请按此评判，而非按"缺什么"）**：刻意零网络框架、零 DI、零协程。
HTTP 服务端、加密信封、签名客户端全部手写，代价是代码量，收益是「10 条依赖、无传递依赖、
无版本冲突、离线可构建」。

---

## 2. 目录与模块职责

```
QuickLaunch/
├── app/src/main/java/com/workbuddy/quicklaunch/
│   ├── MainActivity.kt / LaunchFragment.kt / SyncFragment.kt   界面（3 个 Tab）
│   ├── CreateAutomationActivity.kt                             规则编辑页
│   ├── LaunchProxyActivity.kt          ★ 透明中转页：选屏 + 亮屏 + 绕锁屏后启动目标 App
│   ├── RemoteAccessActivity.kt                                 远程访问配置页
│   ├── adapter/                        4 个 RecyclerView 适配器
│   ├── data/                           Room 实体 + DAO + 迁移（11 个文件）
│   ├── receiver/                       Alarm / WiFi / 蓝牙 / 充电 / 开机（6 个）
│   ├── remote/                         HTTP 服务端 + 加密 + 协议 + 中继（11 个）
│   ├── service/                        LaunchService / KeepAliveService / RemoteService
│   └── util/                           排程、节假日、班表、自动解锁、UI 控制器等（30 个）
├── app/src/test/java/...               15 个测试类（纯 JVM，不依赖 Robolectric）
├── tools/                              PC 侧配套脚本（Node.js + Python，不打进 APK）
└── docs/                               本文件 + system_design.md + mermaid 图
```

规模热点（行数）：`LaunchFragment` 832、`Scheduler` 562、`RemoteRuleApi` 560、
`AutomationFormController` 523、`RemoteServer` 458、`RemoteRelay` 444、`SyncTabController` 400。

---

## 3. 数据模型与迁移

Room `version = 7`，4 张表，`exportSchema = false`。

### 3.1 `automations`（规则主表，19 列）

关键字段与语义：

| 字段 | 含义 | 陷阱 |
| --- | --- | --- |
| `triggerType` | `TIME` / `BLUETOOTH` / `WIFI` / `POWER` | **枚举字面量全大写** |
| `repeatMode` | `daily` / `weekdays` / `weekend` / `once` / `custom` | **枚举字面量全小写**（与上一行相反，写错不会报错，只会静默不过滤） |
| `repeatDays` | 星期位图，`bit (DAY_OF_WEEK - 1)` | `custom` 专用 |
| `skipHolidays` | 跳过法定休息日 | 只管休息日，**不挡调休上班日** |
| `randomWindow` / `windowStartMin` / `windowEndMin` | 当天分钟区间内随机取点 | 每次重排重新随机 |
| `shiftAnchor` | `0=FIXED` / `1=CLOCK_IN` / `2=CLOCK_OUT` | 非 0 时**完全忽略**上面所有 repeat/skip/random 字段 |
| `shiftOffsetMin` | 相对班次的偏移，负数=提前 | 跨天班边界依赖它 |

### 3.2 `holidays` / `rule_exceptions` / `daily_shifts`

- `holidays(date PK, name, isWorkday)` — `isWorkday=false` 休息日、`=true` 调休上班日，二者互斥。
- `rule_exceptions(ruleId, date, reason, createdAt, PK(ruleId,date))` — **单日豁免**。
  独立建表而非在 `automations` 加字符串列，理由：豁免是 (规则 × 日期) 多对多，"撤销某一天"
  与"查未来 N 天"若塞进逗号分隔字符串都要全表解析。
- `daily_shifts(date PK, inMin, outMin, source, updatedAt)` — 班表。
  `outMin >= 1440` 表示**次日下班**（如 `inMin=300, outMin=1740` = 05:00 上班、次日 05:00 下班）。
  独立建表理由：班表是"共享事实"，多条规则锚定同一班次，更新一次全体生效。

### 3.3 迁移链

`1→2`（随机窗口 3 列）、`2→3`（repeatDays）、`3→4`（holidays 表）、
`4→5`（isWorkday 列，默认 0 回填即正确）、`5→6`（rule_exceptions 表）、
`6→7`（daily_shifts 表 + shiftAnchor/shiftOffsetMin，默认 0 = 旧行为）。

全部为 `ALTER TABLE ADD COLUMN NOT NULL DEFAULT ...` 或 `CREATE TABLE IF NOT EXISTS`，
**无表重建、无数据搬迁**，存量数据零丢失。另启用
`fallbackToDestructiveMigrationOnDowngrade(true)`：装回旧版本 APK 时宁可丢缓存也不要崩溃循环。

---

## 4. 排程引擎（`util/Scheduler.kt`，全项目最复杂的部分）

### 4.1 触发链路

```
AlarmManager.setExactAndAllowWhileIdle(RTC_WAKEUP, at, PendingIntent)
  → AlarmReceiver（exported=false，requestCode = ruleId）
  → LaunchProxyActivity（透明、showWhenLocked、turnScreenOn）
  → 选显示器（折叠屏内/外屏）→ 点亮 → 启动目标包
  → 失败兜底：全屏 Intent 通知
  → 触发后重新排下一次（重复任务靠"到点再排下一次"而非周期闹钟）
```

WiFi / 蓝牙 / 充电走 `registerNetworkCallback` 或静态广播 → 同一套 `LaunchService`。
`BootReceiver` 监听 `BOOT_COMPLETED` **和 `MY_PACKAGE_REPLACED`**——后者缺失会导致
"每次覆盖安装 App 后所有排程静默失效"，已在真机复现并修复。

### 4.2 四层优先级（判定顺序，改动任何一层都会静默出错）

```
① 单日豁免（rule_exceptions）      ← 最高，压过调休
② 调休上班日强制放行（holidays.isWorkday = true）
③ 法定休息日跳过（仅 skipHolidays = true）
④ 星期位图 / weekdays / weekend 过滤
```

调休上班日必然是周末，而周末正是 `weekdays` 要排除的对象，所以 ② 必须能**打断** ④ 的推进循环。

### 4.3 三个反直觉的实现细节（审计重点）

**(a) `buildSkipPredicate` 在 `!skipHolidays` 时返回 `exempt` 而非 `{ false }`**

旧实现直接 `return { false }`，会把"单日豁免"一起废掉——用户请假当天照常启动。
现在返回 `exempt` 谓词，保证豁免独立于 `skipHolidays` 开关生效。
（`SchedulerTest` 与 `MakeupBoundaryTest` 覆盖此点。）

**(b) `buildForceRunPredicate` 命中豁免时返回 `false`**

调休放行必须被请假压过。返回 `false` 让该日"退化为普通日"，随后被 `skipPredicate` 判掉。
副作用是好的：`advanceToValidDay` 内部对 `forceRun` 的早退分支不需要任何改动就自动尊重豁免。

**(c) `advanceToValidDay` 的 while 循环一次跨多天**

`weekdays` 规则下"周六→周日→周一"会一步跳过中间的调休周六。因此必须把 `forceRun` 谓词
**传进循环内部**，命中即 `return`，而不是只在函数入口判一次。Kotlin 只允许一个尾随 lambda，
所以调用点必须具名：`nextTriggerTime(a, shouldSkip = {..}, forceRun = {..})`。

### 4.4 班表模式（`shiftAnchor != FIXED`）

**完全绕过** `repeatMode` / `repeatDays` / `skipHolidays` / 随机窗口 / 调休，只认两个输入：
**班表** 与 **用户豁免**。理由（写进代码注释）：

- 轮班的人周六要上 05:00 的班，`weekdays` 会把它排掉；
- 他春节值班那天，国务院通知说那天放假，`skipHolidays` 会把它排掉。

跨天班换算（`shiftTriggerAt`）：把"当天分钟数"按 1440 拆成「第几天 + 当天第几分钟」再加回日期。
若直接 `set(HOUR_OF_DAY, 1740/60)`，Calendar 会归一化成**当天** 05:00，白丢一整天。

**退化策略**：班表为空、或 60 天扫描窗口内无可用班次（`MAX_SHIFT_DAY_SCAN = 60`）时，
**回退到固定时刻**。设计原则是"宁可多触发一次，绝不静默永不触发"——否则用户看到"已启用"
但实际永不响，是最难排查的一类故障。

### 4.5 安全阀与容错

- `MAX_DAY_ADVANCE = 366*2`：逐日推进硬上限。防"整表都是休息日 / 位图损坏"导致
  `while` 死循环挂死主线程或 `BOOT_COMPLETED` 广播线程。
- 上限用尽时回退到"第一个未来有效日、忽略 shouldSkip"——宁可多触发，绝不让闹钟排到两年后。
- `safeSkip` / `safeForceRun`：谓词抛异常按"不跳过/非调休"处理，排程链路不因注入逻辑崩溃。
- `schedule()` 整体包 `runCatching`：任何异常不得让"保存规则/开机重排"失败。
- 精确闹钟权限被拒时降级 `setAndAllowWhileIdle`（仍会响，只是时间有偏差）；
  `SecurityException` 兜底再降一次。
- `rescheduleAll` 预加载节假日与豁免，避免 N+1（规则再多也只查两次库）。

### 4.6 「界面显示」与「真实落点」一致性

`peekNextTriggerAt` 复用**同一个** `nextTriggerTime` 与同一组谓词，只算不排。
存在的理由：若界面另写一套"取最早固定时刻"的算法，就会出现"卡片写 12:19，实际因请假推到明天"
的自相矛盾。该函数会被主线程调用，因此**约定不查库**，节假日与班表必须由调用方预加载传入。

---

## 5. 远程访问（`remote/`，自研 HTTP + 加密）

### 5.1 两种通道

| 通道 | 拓扑 | 代码 |
| --- | --- | --- |
| 局域网直连 | PC ↔ 手机同一 WiFi，手机监听 8765 | `MiniHttpServer` + `RemoteServer` |
| 中继 | 手机**反向长轮询** PC 上的 Node 中继，跨 NAT | `RemoteRelay` + `tools/ql_relay.js` |

选"设备反连长轮询"而非 Tailscale/mDNS：用户已有 VPN 会与之冲突；且中继**不必被信任**。
已知降级：**mDNS/NSD 未实现**（只能手输 IP）。

### 5.2 手写 HTTP 服务端（`MiniHttpServer.kt`，356 行）

明确支持范围：仅 HTTP/1.1 固定 `Content-Length` 的简单请求。
**主动拒绝** chunked（解析 chunked 会引入一整类边界 bug）、不支持文件服务/keep-alive/HTTP2。
限制：4 线程池、10s 读超时、8KB body 上限、8KB 行长、64 个 header。

### 5.3 加密与鉴权（`RemoteAuth.kt`）

密钥派生：`macKey = HMAC-SHA256(secret, "ql-mac-v1")`、`encKey = HMAC-SHA256(secret, "ql-enc-v1")`。

- **身份与完整性**：`X-QL-Sign = HMAC-SHA256(macKey, canonical)`，
  `canonical` 覆盖 `method / path / ts / nonce / sha256(body)`。
- **机密性**：AES-256-GCM（12 字节 IV、128 bit tag），信封 = `IV || CT||TAG`。
- **时间窗**：`MAX_SKEW_MS = 300_000`（±5 分钟）。
- **签名比较**：`MessageDigest.isEqual`（常量时间），不是 `==` / `equals`。
- **重放**：NonceCache（带时间淘汰）。⚠️ 中继轮次复用同一实例——每轮 `new` 会让重放保护归零。
- **来源白名单**：`isAllowedSource()` 只接受私网网段（可选放行 Tailscale 段）。
- **限流**：按 IP 失败计数 → 5 分钟锁定（429）。

### 5.4 配对流程与威胁模型

`POST /api/v1/pair` 用手机屏幕显示的 **8 位码**换 32 字节 `secret`。该响应在链路上是**明文**
（此时还没有共享密钥）。代码注释明确写出边界：

> 能拿到 secret 的人必须**当时**在同一个 WiFi 上嗅探，且配对码 10 分钟过期、错 3 次作废。
> 如果当前 WiFi 不可信，就不要开这个功能。

即：安全性最终由"同一 WiFi"这个物理前提兜底，属**有意接受的残余风险**，不是遗漏。

### 5.5 路由表（`RemoteServer`，路径分段匹配）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/ping` | 免鉴权，返回设备名 / 是否待配对 |
| POST | `/api/v1/pair` | 免鉴权，8 位码换 secret（只在局域网内允许） |
| GET/POST/PATCH/DELETE | `/api/v1/rules[/{id}]` | 需签名；字段白名单见下 |
| GET/POST/DELETE | `/api/v1/shifts[/{date}]` | 需签名；写后触发 `rescheduleAll()` |

`RemoteProtocol.EDITABLE_FIELDS` 是**白名单**：请求体出现白名单外字段直接 `409 E_FORBIDDEN_FIELD`，
避免客户端误传 `id` / `createdAt` 之类字段造成静默覆盖。

### 5.6 已知降级（审计时请勿重复报告为"新发现"）

1. **配对 secret 明文存 SharedPreferences**（`RemoteConfig` 把 `secretB64` 存进 JSON 数组）。
   前提是设备已 root 且攻击者已拿到 `/data/data` 访问权，此时威胁已成立。
2. **中继通道不支持 `shifts`**（`RemoteRelay` 内无 shift 分支，grep 计数 0）。
3. **无 NSD/mDNS**，只能手输 IP。
4. `java.util.Base64` 需 API 26（minSdk 24），故自带 `B64.kt`。

---

## 6. 锁屏自动解锁（`util/AutoUnlock.kt`，root 专属）

### 6.1 判定顺序（`AutoUnlockDecision`，纯函数，13 例单测）

```
enabled 且 hasPin
  → failures < MAX_FAILURES(=2)
  → deviceLocked 且 有屏亮着
  → chargingOnly 时要求 isCharging
  → 距上次尝试 ≥ MIN_INTERVAL_MS(=700ms)
  → ATTEMPT
```

### 6.2 注入方式（真机实测结论，与常见做法不同）

- `input text <pin>` 直接打 → **无效**（打在空处）；
- `input keyevent 82`(MENU) 拉 bouncer → 本机 **也无效**；
- 必须**先上滑**叫出 PIN 键盘，再注入、再 `keyevent 66`(ENTER)，
  三步串成**一条** `su -c`（分开调用每次都要起一个 `app_process`，慢一倍以上）。

**验证不看命令返回值**，而回读 `KeyguardManager.isDeviceLocked`——`input` 返回 0
只代表事件发出去了。实测解锁耗时 1975–2092ms（3 轮）。

### 6.3 安全约束

- `MAX_FAILURES = 2`，达到即**自动关闭功能并发通知**。系统 5 次错密码会清除设备数据，
  这里留足余量，绝不赌。
- 只接受 4–16 位**纯数字**（数字键盘才可被 `input text` 注入）。
  校验做了两次：写入 `setPin` 时一次，**拼 shell 命令前再从存储读回时一次**（防篡改 SP 注入）。
- 只在"屏亮 + 确实锁屏"时动手，黑屏绝不注入。
- 观察窗口 `WATCH_WINDOW_MS = 12s`，`TICK_MS = 600ms`。
  ⚠️ 曾有 bug：窗口到期 `return` 但未复位 `watching`，导致后续 `watch()` 被
  `if (watching) return` 挡掉、观察器永久停摆（静默失效）。已修。

### 6.4 PIN 存储（`PinVault.kt`）

优先 `AndroidKeyStore` AES-GCM（密钥不可导出、绑定本机）；
KeyStore 不可用（部分定制 ROM）时退化为 XOR + Base64 混淆，前缀区分（`ks1:` / `x1:`）。
退化路径**不是加密**，只是防肩窥，代码注释已声明。

---

## 7. 防息屏与锁屏（`KeepAliveService` / `ScreenOnOverlay` / `AntiSleep`）

语义边界（曾反复踩坑，已写进注释）：

- `SCREEN_BRIGHT_WAKE_LOCK` 只能挡「自动超时息屏」，**挡不住电源键锁屏**。
  "锁屏后保持亮屏"在非 root Android 上做不到。
- 悬浮窗 `TYPE_APPLICATION_OVERLAY` + `KEEP_SCREEN_ON` 只在窗口可见时有效，锁屏即摘窗。
- 折叠屏合盖时**外屏是 `display 1`**，且**不被 `getDisplays()` 枚举**，
  任何时候判断"有没有屏亮着"都必须补查 `getDisplay(1)`。
- v1.3.17 真 bug：`sync()` 锁屏时摘窗，但 `syncTask` 无条件 `acquireWakeLock()`，
  造成"窗口没了、锁还挂着"，正式版锁持续 25 天。现改为 `anyDisplayLit()` 为真才持锁。
- WakeLock 用带超时的 `acquire(WAKELOCK_TIMEOUT_MS)` + 续期任务，防进程被杀后锁不释放。
- 两包（正式 + sig2）锁 tag 同名，必须按 `mPackageName=` 分流。

---

## 8. UI 层结构

3 个 Tab（ViewPager2 + 顶部 Segment）：启动（应用列表 + 规则卡片）、同步源、设置。
表单逻辑抽到 `AutomationFormController` / `RuleFormMapper`，
同步页逻辑抽到 `SyncTabController`（400 行，含所有 `SwitchCompat` 的复位与回调绑定）。
Transparent 主题的中转页 `LaunchProxyActivity` 是触发链路的关键节点。

已知不一致：`MainActivity` 内嵌的快速创建表单未接「跟随班表」开关
（`AutomationFormController.FormViews` 传 `null`）。

---

## 9. PC 侧工具链（`tools/`，不进 APK）

| 文件 | 语言 | 作用 |
| --- | --- | --- |
| `ql_client.js` | Node（零依赖） | 远程 API 客户端：`list / patch / enable / disable / del / shifts`。`shifts del` 强制要求 `--from/--to`，防手滑清空整表 |
| `ql_relay.js` | Node（零依赖） | 公网中继，配合 `cloudflared tunnel`。Python 标准库无 AES-GCM，故用 Node |
| `sheet_shift.py` | Python | 解析飞书值班表（日历式表格）→ 下发班表 |
| `shift_sync.py` | Python | 飞书考勤记录（`check_in/out_shift_time`）备用数据源 |

`sheet_shift.py` 的解析要点（都是踩出来的）：
月份标题可能是两位年份（`26年1月`）；表里有两个「11月」，靠"日期是否落在对应星期列"
交叉验证而非行号；日期格备注必须保留——判「在家班 / 休 / 周末」全靠它。

**过滤发生在下发层**：周六日、含「在家」、含「休」的日期不会进入手机班表，
代价是"某规则确实要周末跑"会被挡住（届时需加开关）。

---

## 10. 构建、签名与发布

- 双签名：`release`（`quicklaunch-release.jks`，在版本库内，alias/口令均为 `quicklaunch`）
  与 `sig2`（`signing/quicklaunch-sig2.jks`，**在 .gitignore**，需仓库外备份）。
- **是 buildType 不是 flavor**：`assembleRelease` / `assembleSig2`。
- `.sig2` 变体有 `applicationIdSuffix`：同 applicationId 换签名平台禁止覆盖安装，
  加后缀是唯一"不覆盖现有版本"的做法。
- ⚠️ `android:label="${appLabel}"` 改动后 merged manifest 不变，
  `packageRelease` 会判 UP-TO-DATE，**增量构建看不出坏没坏**，须删 apk 重打包。
- CI：GitHub Actions，`android-actions/setup-android@v3` 需显式 `packages: 'platform-tools'`。

---

## 11. 测试覆盖分布

| 测试类 | 用例 | 覆盖对象 |
| --- | --- | --- |
| `RemoteRelayTest` | 32 | 中继信封、canonical、跨语言互操作 |
| `RemoteAuthTest` | 27 | HMAC 签名、GCM 信封、时间窗、Nonce、限流 |
| `RemoteRuleApiTest` | 26 | 路由、字段白名单、错误码 |
| `SchedulerTest` | 21 | 四层优先级、星期位图、随机窗口 |
| `RemoteProtocolTest` | 20 | 协议常量与字段映射，含"备份字段集合与实体一致"守卫 |
| `HolidayIntegrationTest` | 19 | 节假日 + 调休 + 豁免组合 |
| `RuleExceptionScheduleTest` | 18 | 单日豁免与调休冲突 |
| `RuleEditingTest` | 17 | 编辑后重排一致性 |
| `MiniHttpServerTest` | 16 | 解析边界（chunked 拒绝、超长行、超限 body） |
| `HolidaySourcesTest` | 13 | 节假日数据源解析 |
| `AutoUnlockTest` | 13 | 判定表边界、失败上限、PIN 格式 |
| `ShiftScheduleTest` | 12 | 班表锚定、跨天班、60 天退化 |
| `SchedulerYearSimulationTest` | 11 | 整年逐日推进仿真 |
| `MakeupBoundaryTest` | 9 | 调休与星期过滤的边界 |
| `SchedulerPeekTest` | 3 | 界面预览与真实落点一致 |

测试策略：**把生产逻辑抽成纯函数**（`buildSkipPredicate` / `AutoUnlockDecision`），
单测直接调用生产代码，而不是在测试里另写一份等价判定——后者只能证明"测试里的逻辑对"。
纯 JVM 运行，不依赖 Robolectric。

---

## 12. 已知风险清单（作者自评，请审计方复核或补充）

### 高

| # | 风险 | 说明 |
| --- | --- | --- |
| H1 | **锁屏自动解锁 = 设备安全性归零** | root + 存储明文 PIN 的等价物 + 注入解锁：任何拿到设备的人开屏即入。功能为默认关闭、需用户显式开启并输入密码，但仍属"用户自愿降低安全性"。失败 2 次自动关停是为避免触发系统的 5 次清数据。 |
| H2 | root 依赖 | `su -c` 注入、`settings put` 均需 Magisk。无 root 时相关功能静默不生效。 |
| H3 | 配对 secret 明文落盘 | 见 §5.6。 |
| H4 | `SYSTEM_ALERT_WINDOW` + 后台启动 Activity | 这是本应用能工作的核心，也是它最"越界"的地方；上架应用商店会被质疑。 |

### 中

| # | 风险 | 说明 |
| --- | --- | --- |
| M1 | 班表/值班表过滤在下发层 | 周末/在家班在 PC 脚本里就被剔掉，App 层无感知。某规则若确实要周末跑会被挡住。 |
| M2 | 中继通道无 shifts | 跨 NAT 场景下无法同步班表。 |
| M3 | 无 mDNS | 用户体验问题，非安全问题。 |
| M4 | `screen_off_timeout = 2147483647` | 防息屏功能把系统超时顶到 ≈24.8 天（有意为之）。防息屏开关在界面为关，但系统值未还原——当前处于不一致状态。 |
| M5 | 班表 60 天退化 | 班表过期时静默退化为固定时刻。若用户不知道这一点，会以为规则"跟着班表走"。 |
| M6 | 双包同装互不感知 | 正式版与 sig2 版各自独立排闹钟、独立 Room，同一条规则会触发两次。 |

### 低 / 可维护性

- `LaunchFragment` 832 行，界面与业务逻辑仍有耦合。
- `MainActivity` 快速创建表单缺"跟随班表"开关（`FormViews` 传 `null`）。
- `exportSchema = false`，Room schema 无版本化导出（迁移靠手写 SQL 与单测守护）。
- Lint 关闭了 `HardcodedText` / `UnusedResources` / `MissingDefaultResource`，且 `abortOnError false`。
- 发布密钥 `quicklaunch-release.jks` **在版本库内**且口令为默认值 `quicklaunch`
  （项目定位为个人工具，公开仓库；若视为商用则是高危项）。

### 已修复记录（供审计方核对修复是否到位）

| 版本 | 缺陷 | 后果 | 修法 |
| --- | --- | --- | --- |
| 1.3.17 | `KeepAliveService` 锁屏时摘窗但无条件 `acquireWakeLock` | 窗口没了锁还挂着，正式版锁持续 25 天 | 改为 `anyDisplayLit()` 为真才持锁，并补查 `getDisplay(1)` |
| 1.3.18 | `AutoUnlock` 观察窗口到期 `return` 未复位 `watching` | 观察器永久停摆，自动解锁静默失效 | 到期即复位 `watching` |
| 1.3.18+ | `injectAndVerify` 直接拼接从存储读回的 PIN | 篡改 SP 即可命令注入 | 拼命令前再 `validPin` 一次 + 单引号包裹 |
| 1.3.18+ | `Scheduler` 退化分支直接调 `Log.w` | `android.util.Log` 在 JVM 单测里是未实现桩，班表过期时**单测必崩**；生产侧日志也不该能打挂排程链路 | `runCatching { Log.w(...) }` |
| 1.3.18+ | `ShiftScheduleTest` 用写死的 2026-11-02/05 | 日期一过就走退化分支，用例随日历变红/变绿 | 班表改为相对「今天 +N 天」构造 |

> 后三条是准备本文档期间发现的，说明"测试全绿"这件事本身也需要审计：
> 那条用例当时绿，是因为班表日期恰好还在未来。

接口面 `RemoteAuth.verify` 已使用 `MessageDigest.isEqual`（常量时间比较），无需整改。

---

## 13. 建议审计方重点关注的问题

1. **排程正确性**：`Scheduler.nextTriggerTimeInternal` 的分支是否在所有
   `(repeatMode × randomWindow × shiftAnchor × 调休/休息日 × 豁免)` 组合下都产出未来时刻？
   特别看 §4.3 那三个反直觉点被改动后的连锁影响。
2. **时间边界**：`Calendar` 的 `set()` 归一化（跨天班）、DST/时区（中国无 DST，但代码未显式处理）、
   `advanceUntilValid` 的两个 while 是否会互相推挤产生死循环。
3. **加密实现**：`RemoteAuth.canonical` 拼接是否可能被构造歧义（分隔符注入）？
   `NonceCache` 淘汰策略在长时间挂起后是否失效？`verify` 是否用了常量时间比较？
4. **HTTP 服务端**：`MiniHttpServer` 的解析器在面对畸形请求（超长 header、负 Content-Length、
   半包、慢速攻击）时是否会读满线程池。这是攻击面最大的手写代码。
5. **root 注入面**：`AutoUnlock.injectAndVerify` 把 PIN 拼进 shell 命令字面量。
   当前有三重防护：写入时 `validPin` 校验、**读回后又校验一次**（本次审计准备期补上）、
   以及命令里用单引号包裹。请复核是否仍有绕过路径（例如 PIN 长度/字符集放宽后）。
6. **并发与生命周期**：`Scheduler` 是 `object` 单例且被主线程与广播线程同时调用；
   `KeepAliveService` 的 DisplayListener / 广播 / 续期任务三方竞争 wakeLock。
7. **数据一致性**：请假/班表写库后必须 `cancel` 再 `schedule`（或 `rescheduleAll`），
   漏掉的表现是"接口返回 200、数据正确、系统里闹钟照旧"——最隐蔽的一类 bug。
8. **测试有效性**：257 例覆盖的是纯逻辑层；`LaunchProxyActivity` 的绕锁屏启动、
   折叠屏选屏、`AlarmReceiver` 的真实触发路径**没有自动化测试**，只有真机手工验证
   （`dumpsys alarm` 读 `origWhen` 与 `dumpsys trust` 读 `deviceLocked` 作为 ground truth）。

---

## 14. 附：真机验证方法论（供审计方判断"证据强度"）

项目里所有"已验证"的结论都不是读函数返回值，而是读系统真实状态：

```bash
# 排程落点：dumpsys alarm 是 ground truth
adb shell dumpsys alarm | grep -A2 "tag=*walarm*"
# 解锁状态
adb shell dumpsys trust | grep -o 'deviceLocked=[01]'
# 屏幕亮暗（状态字会骗人，必须看图）
adb exec-out screencap -d 1 -p > s.png
# 触发入口：AlarmReceiver exported=false，唯一可从外部触发的真实入口是 BOOT_COMPLETED
adb shell am broadcast -a android.intent.action.BOOT_COMPLETED
```

对照组实验：只改一个变量（如 `am force-stop` 后观察 Dozing 是否变化），用于排除巧合。
曾因此排除两个假线索：razr 合盖时 `mWakefulness=Dozing` 是 AOD 常态（截图有内容即正常）；
锁屏时 `mCurrentFocus=null` 也正常（Keyguard 不是 Activity）。
