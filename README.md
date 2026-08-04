# QuickLaunch 快捷启动

安卓端的自动化启动器，对标 iOS「快捷指令」的自动化能力：**按时间**或**按条件**自动拉起指定 App。

- 目标平台：**Android 16（API 36）**，最低支持 Android 7.0（API 24）
- 已针对 **Motorola myui 8.0** 的后台管控做适配引导
- 推送到 GitHub 后由 Actions 自动编译出可直接安装的 APK

---

## 功能

| 触发方式 | 说明 | 实现 |
| --- | --- | --- |
| 定时 | 每天 / 工作日 / 周末 / 一次性 | `AlarmManager` 精确闹钟，触发后自动重排下一次 |
| 充电时 | 插入 AC / USB / 无线充电 | `ACTION_POWER_CONNECTED`（系统豁免广播） |
| 连接 WiFi | 连上任意 WLAN | `ConnectivityManager.registerNetworkCallback` + PendingIntent |
| 连接蓝牙 | 任意设备或指定设备名 | `ACTION_ACL_CONNECTED`（系统豁免广播） |

每条规则可单独启用/停用、删除；重启手机后定时任务与网络回调自动重建。

---

## 折叠屏外屏兼容（razr 10 Ultra / Galaxy Fold 等）

合盖状态下从**外屏**也能正常自动启动目标 App，关键设计：

- `util/DisplayPicker.kt`：运行时枚举所有屏幕，**只认「唯一一块亮着的屏」**这一条判据决定投到哪块屏，**不做机型型号白名单**（Google 官方反对设备白名单，且对两种外屏系统实现都成立：外屏是独立 Display 的型号直接选中，外屏复用主屏只换分辨率的型号自动走默认路径）。
- `LaunchProxyActivity.kt`：透明中转页。触发时先拉起它——它合盖时本身就在外屏上跑，由它启动的目标 App 默认继承同一块屏，再配合 `DisplayPicker` 的 `setLaunchDisplayId` 显式投屏，双保险。
- 透明中转页顺带解决三件事：绕过后台启动限制、息屏点亮屏幕（`setTurnScreenOn`）、锁屏之上弹出（`setShowWhenLocked`）。

按运行时屏幕状态决策，因此车机副屏、HDMI 外接等场景同样覆盖。

---

## 编译

### 方式一：GitHub Actions（推荐，无需本地环境）

推送到 `main` / `master` 分支即自动触发，或在 Actions 页面手动 `Run workflow`。
编译完成后在该次运行的 **Artifacts** 里下载 `quicklaunch-debug-apk`，解压后直接安装。

流水线做三件事：装 Android 16 SDK → 跑单元测试 → 编译 Debug APK（自动 debug 签名）。

### 方式二：本地 Android Studio

用 Android Studio（Ladybug 及以上）打开本目录，等待 Gradle 同步后点 ▶ Run。

构建链版本：AGP 8.9.1 / Gradle 8.11.1 / Kotlin 2.1.0 / JDK 17 / compileSdk 36。

---

## 首次安装后必须开启的权限

装好后首次进入会弹出集中引导。**下面第 1 项不开，后台自动启动基本不会成功**——这是 Android 10 以后的系统限制，不是本应用的 bug。

1. **悬浮窗 / 显示在其他应用上层**（`SYSTEM_ALERT_WINDOW`）
   Android 10+ 禁止后台随意启动 Activity，持有此权限是最可靠的豁免途径。
   未授权时应用会退回「发一条全屏通知」，用户点一下通知同样能打开目标 App。

2. **忽略电池优化** — 否则息屏后定时任务会被系统推迟甚至丢弃。

3. **通知权限** — 前台服务与兜底通知都需要。

4. **精确闹钟** — 已申请 `USE_EXACT_ALARM`，Android 13+ 通常免手动授权；若系统仍要求，请在「闹钟和提醒」中放行。

### Motorola myui 8.0 额外设置

myui 的后台管控比原生激进，需要在系统设置里再放行三项：

```
设置 → 应用 → QuickLaunch → 电池 → 选择「无限制」
设置 → 应用 → QuickLaunch → 允许自启动          （开启）
设置 → 应用 → QuickLaunch → 允许后台弹出界面      （开启）
```

再把 QuickLaunch 在最近任务列表里**加锁**（下拉卡片点锁图标），避免被一键清理杀掉。

---

## 工程结构

```
app/src/main/java/com/workbuddy/quicklaunch/
├── MainActivity.kt              规则列表 + 权限集中引导 + 边到边适配
├── CreateAutomationActivity.kt  新建规则表单
├── AutomationAdapter.kt         列表适配器
├── data/                        Room 实体 / Dao / Database / 触发类型常量
├── receiver/
│   ├── AlarmReceiver.kt         定时触发，并重排下一次
│   ├── BootReceiver.kt          开机重建闹钟与网络回调
│   ├── PowerConnectionReceiver  充电触发
│   ├── WifiReceiver.kt          WiFi 触发（含网络回调注册）
│   └── BluetoothReceiver.kt     蓝牙触发
├── service/LaunchService.kt     前台服务拉起目标 App + 通知兜底
├── LaunchProxyActivity.kt       透明中转页：选屏 + 点亮屏幕 + 绕过锁屏 + 外屏继承
└── util/
    ├── Scheduler.kt             闹钟排程与下次触发时间计算
    ├── AppListLoader.kt         读取可启动应用列表
    └── DisplayPicker.kt         折叠屏选屏（按运行时屏幕状态决策，非机型白名单）

app/src/test/java/.../SchedulerTest.kt   时间计算逻辑单元测试
```

---

## 已知限制

- **后台拉起并非 100% 成功**。Android 从 10 开始持续收紧后台启动 Activity，国产/定制 ROM 更严。本应用采用「前台服务 + 悬浮窗权限」的标准方案，并始终附带一条可点击的全屏通知作为兜底。
- WiFi 触发依赖系统网络回调，连上 WLAN 后触发，通常有 1~3 秒延迟。
- 一次性任务执行后会自动关闭开关，需要再次使用时手动打开。
- 蓝牙设备名需要 `BLUETOOTH_CONNECT` 权限才能读取；未授权时「指定设备名」会退化为「任意设备」。
