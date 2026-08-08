# QuickLaunch UI 升级验收清单

> 对应增量设计 `docs/system_design.md`（T05 交付物）。QA 按此清单在真机/模拟器逐项冒烟。

## 1. 主题与深色模式

- [ ] 浅色模式：主界面背景为暖白 `#F9FAF3` 系，主色为深绿 `#2E7D32`，按钮/FAB/Switch 使用 M3 绿色调
- [ ] 深色模式：跟随系统切换后背景 `#11140F` 系，主色亮绿 `#8BD58C`，无任何残留白底控件
- [ ] 冷启动（浅色 + 深色各一次）：窗口无白闪（windowBackground 生效）
- [ ] 切深色后返回 App：无白闪、无系统强暗二次叠加（forceDarkAllowed=false）
- [ ] 桌面图标：自适应图标深绿底白闪电（API 26+），无图标适配崩坏

## 2. 主界面布局

- [ ] 操作区（防外屏息屏 / 数据源 / 同步 / 管理）收纳进一张圆角卡片，不挤占列表
- [ ] 列表从操作卡片下方开始，底部留白给 FAB
- [ ] 空状态：无规则时显示闪电 outline 图标 + 「还没有自动化规则」；有规则时隐藏

## 3. 列表卡片

- [ ] 每条规则卡片顶行：状态色点（启用绿 / 停用灰）+ 名称 + 开关
- [ ] 描述行（定时/充电/WiFi/蓝牙 + 目标 App）省略号生效（maxLines=2）
- [ ] 删除按钮为 TextButton 风格，右下，触达高度 ≥48dp

## 4. 删除确认 + 撤销

- [ ] 点「删除」必弹 MaterialAlertDialog 二次确认（含规则名）
- [ ] 点「删除」后列表移除，Snackbar「已删除「name」」+ 撤销按钮
- [ ] Snackbar 超时（约 4s）后删除保持，重启 App 规则不恢复
- [ ] 点「撤销」：规则恢复（含原 id），定时规则重新排程
  - logcat 验证：`AlarmManager` setExactAndAllowWhileIdle 重新注册，requestCode 与删除前一致
- [ ] 竞态：删除 → 撤销 → 再删除 无异常，无重复闹钟/漏闹钟
- [ ] 连续删除多条：第二条 Snackbar 的撤销只作用于第二条

## 5. 开关与同步反馈

- [ ] 拨动规则开关：Snackbar「已开启/已关闭「name」」（LENGTH_SHORT）
- [ ] 同步节假日按钮：点击后变「同步中…」并禁用，完成后恢复「同步法定节假日」
- [ ] 同步失败文案：友好提示「已保留上次数据，定时规则不受影响」

## 6. 对比度与无障碍

- [ ] 正文次要文字（?attr/colorOnSurfaceVariant）浅/深模式均 ≥4.5:1
- [ ] 状态色点绿/灰在浅/深模式均 ≥3:1 可辨
- [ ] 无 `#666666` / `#2980b9` 硬编码残留（管理页可读）

## 7. 回归（红线）

- [ ] Scheduler / HolidaySync / RootUtils / KeepAliveService / ScreenOnOverlay / AntiSleep / DisplayPicker 无改动
- [ ] data/（Room 实体/DAO）、receiver/* 无改动
- [ ] 通知权限、蓝牙权限、动态广播注册逻辑无改动
- [ ] 定时触发（TIME）、充电触发、WiFi 触发、蓝牙触发冒烟通过
