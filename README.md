# 快捷启动（QuickLaunch）

一个类似苹果「快捷指令」的安卓自动化启动器：可以**定时**自动打开指定 App，也可以**按条件**（充电、连上 WiFi、连上蓝牙）自动打开 App。

## 功能

| 触发条件 | 说明 |
| --- | --- |
| 定时 | 每天 / 工作日 / 周末 / 一次性，可指定时间 |
| 充电时 | 插入电源（AC/USB/无线）即启动 |
| 连接 WiFi | 连上 WLAN 即启动 |
| 连接蓝牙 | 连上蓝牙设备即启动（可指定设备名） |

每条规则可单独开关、删除；支持开机后自动恢复定时任务。

## 运行环境

- Android Studio（最新版）
- 手机系统 Android 7.0（API 24）及以上
- 真机调试（模拟器无蓝牙/WiFi 真实事件）

## 如何运行

1. 用 Android Studio 打开本目录（`File → Open` 选择 `QuickLaunch` 文件夹）。
2. Android Studio 会自动下载 Gradle 与依赖（需联网）。
3. 手机开启「开发者选项 → USB 调试」，连接电脑。
4. 点击 ▶ Run，选择设备即可安装运行。

> 也可自行安装 Gradle 8.6+ 后用 `gradle assembleDebug` 在 `app/build/outputs/apk/debug/` 生成 APK。

## 权限与必要设置

应用首次启动会请求以下权限/设置，请全部允许，否则后台触发可能失效：

1. **通知权限**（Android 13+）：用于启动时的前台通知。
2. **电池优化白名单**：弹窗点「允许」。被加入电池优化后，系统会杀掉后台定时与事件监听。
3. **精确闹钟**（Android 12+）：在系统「设置 → 应用 → 快捷启动 → 闹钟与提醒」中授予「精确闹钟」权限，定时更准；未授予则退化为不精确唤醒。
4. **蓝牙权限**（连接蓝牙条件时需要）。

## 后台启动说明（重要）

Android 10 以上限制「后台直接 startActivity」。本应用采用**前台服务 + 通知**的双重方式拉起目标 App：

- 触发时先拉起一个前台服务，由前台服务启动目标 App（前台服务属于系统允许的豁免场景）。
- 同时弹出通知，通知里挂了「点击启动」的入口，作为兜底——万一被厂商系统拦截，点通知也能打开。

若某些国产 ROM（如 MIUI / EMUI）仍拦截，请在该机型「自启动管理 / 后台弹出界面」中允许本应用。

## 项目结构

```
app/src/main
├── AndroidManifest.xml            # 组件与权限声明
├── java/com/workbuddy/quicklaunch
│   ├── MainActivity.kt            # 列表主页
│   ├── CreateAutomationActivity.kt# 新建自动化表单
│   ├── AutomationAdapter.kt       # 列表适配器
│   ├── data/                      # Room 数据层（实体/Dao/库）
│   ├── receiver/                  # 定时/开机/充电/WiFi/蓝牙 广播接收器
│   ├── service/LaunchService.kt   # 真正拉起 App 的前台服务
│   └── util/                      # Scheduler 调度 + 应用列表加载
└── res/                           # 布局、主题、图标、字符串
```

## 可扩展方向

- 增加「地理围栏（到达某地启动）」「摇晃手机」「连接特定 WiFi SSID」等触发条件。
- 支持一条规则启动多个 App，或执行一系列动作（打开网页、发通知等）。
- 用 WorkManager 替代 AlarmManager 以获得更省电的调度。
