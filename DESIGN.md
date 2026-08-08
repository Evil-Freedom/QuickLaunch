# QuickLaunch · 车机化暗色设计系统（Car Dashboard Dark）

> 本文档记录本次「车机 App 控制面板」暗色重构的设计 Token 与组件规范。
> 所有 Token 均落地于 `app/src/main/res/`，布局/代码**必须引用资源名，禁止硬编码色值/尺寸**。
> 对应主题：`Theme.QuickLaunch.Dark`（`res/values/themes.xml:167`）。

---

## 1. 视觉主题与氛围

- **设计哲学**：深邃夜空下的车机控制面板——OLED 纯黑底、深灰蓝立体卡片、1px 微光描边、极简线条图标。
- **视觉基调**：科技感、冷静、低饱和、高对比文字。
- **核心特征关键词**：`深色毛玻璃` · `圆角卡片` · `线条图标` · `状态绿强调` · `苹果蓝高亮`。
- **质感倾向**：纯扁平 + 1px 描边（无投影阴影），选中态用圆形高亮包围盒替代厚重色块。

---

## 2. 调色板（Color Palette）

| 角色 | 资源名 | HEX | 使用场景 |
|------|--------|-----|----------|
| 根背景 / 窗口底 | `@color/dark_root` `@color/dark_bg_primary` | `#0F111A` | 页面根背景（`bg_dark_root` 渐变 `#0F111A→#14161F`） |
| 一阶卡片 | `@color/dark_card_primary` | `#1C1E26` | 主内容卡片、Surface 容器 |
| 二阶容器 | `@color/dark_card_secondary` | `#252833` | 输入框、次要按钮、列表项底 |
| 按钮底 | `@color/dark_button_bg` | `#252833` | 操作按钮背景 |
| 分割线 | `@color/dark_divider` | `#2C2E3A` | 列表分隔、Outline |
| 微光描边 | `@color/dark_stroke` | `#333542` | 卡片/输入框 1px 描边、底栏上沿 |
| 输入框底 | `@color/dark_input_bg` | `#252833` | 文本框背景 |
| 搜索框底 | `@color/dark_search_bg` | `#1F2129` | 搜索栏 |
| 主文字 | `@color/dark_text_primary` | `#FFFFFF` | 标题、正文 |
| 次文字 | `@color/dark_text_secondary` | `#A1A1AA` | 副标题、未选中标签、图标 tint |
| 辅助文字 | `@color/dark_text_tertiary` | `#71717A` | 占位、弱化信息 |
| 提示文字 | `@color/dark_text_hint` | `#4A4D5A` | hint、空态 |
| 状态绿（主强调） | `@color/dark_accent` `@color/dark_accent_green` | `#34C759` | 开关、开启态、主按钮、选中网格 |
| 绿色按下 | `@color/dark_accent_press` | `#2DBD4E` | 按钮按压态 |
| 苹果蓝（高亮） | `@color/dark_accent_blue` | `#007AFF` | 导航选中态、链接、聚焦 |
| 危险/删除 | `@color/dark_destructive` | `#FF453A` | 删除按钮、错误态 |
| 底栏毛玻璃 | `@color/car_bottom_nav_bg` | `#C0121212` | 贴底导航半透明背景（含 alpha） |
| 底栏上沿 | `@color/car_bottom_nav_stroke` | `#333542` | 底栏顶边微光 |

### 2.1 组件级硬编码色（位于独立 drawable，集中管理）

| 资源 | 背景 HEX | 描边 HEX | 说明 |
|------|----------|----------|------|
| `bg_nav_item_selected.xml` | `#1F334D` | `#2A5580` | 底部导航**选中**圆形包围盒 |
| `bg_trigger_grid_item_selected.xml` | `#1C2E22` | `#34C759` | 4 联排触发卡**选中**态 |

---

## 3. 尺寸 Token（Dimens）

| 资源名 | 值 | 用途 |
|--------|----|------|
| `@dimen/dark_radius_card` | `20dp` | 大卡片 / 网格卡圆角 |
| `@dimen/dark_radius_pill` | `28dp` | 按钮、输入框、胶囊全圆角 |
| `@dimen/car_bottom_nav_height` | `56dp` | 自定义底栏高度（压缩沉闷大块） |
| `@dimen/car_bottom_nav_margin` | `12dp` | 底栏外边距 |
| `@dimen/car_nav_item_glow` | `44dp` | 导航项圆形包围盒直径 |
| `@dimen/dark_stroke_width` | `1px` | 全局描边宽度（hairline） |
| `@dimen/dark_button_height` | `44dp` | 按钮最小高度 |
| `@dimen/dark_input_height` | `44dp` | 输入框高度 |
| `@dimen/dark_chip_height` | `36dp` | 内嵌胶囊高度 |
| `@dimen/dark_card_margin_horizontal` | `16dp` | 卡片左右外边距 |
| `@dimen/dark_card_margin_vertical` | `8dp` | 卡片上下外边距 |
| `@dimen/dark_card_padding` | `16dp` | 卡片内边距 |
| `@dimen/dark_section_gap` | `12dp` | 区块间距 |

### 3.1 文字字号阶梯（iOS Token 复用）

| 资源名 | sp | 角色 |
|--------|----|------|
| `@dimen/ios_text_caption` | `12sp` | 辅助说明 |
| `@dimen/ios_text_body` | `14sp` | 正文基础 |
| `@dimen/ios_text_subhead` | `16sp` | 副标题 |
| `@dimen/ios_text_title` | `20sp` | 区块标题 |
| `@dimen/ios_text_large_title` | `28sp` | 大标题 |

---

## 4. 排版规则（Typography）

- **字体族**：默认 `sans-serif-light`（主题 `android:fontFamily`）；强调/选中用 `sans-serif`；标签小字用 `sans-serif`。
- **字重哲学**：大面积用 300/400 纤细，关键操作/选中态用 500/600 提重，避免全粗。
- **字距**：按钮与标签 `letterSpacing 0.02`，分组标题 `0.05` 并大写。

| 样式资源 | 字号 | 字重 | 颜色 | 用途 |
|----------|------|------|------|------|
| `TextAppearance.QuickLaunch.Dark.Title` | `22sp` | `600` | `dark_text_primary` | 页面标题 |
| `TextAppearance.QuickLaunch.Dark.Subtitle` | `16sp` | `500` | `dark_text_primary` | 卡片副标题 |
| `TextAppearance.QuickLaunch.Dark.Body` | `15sp` | `400` | `dark_text_primary` | 正文 |
| `TextAppearance.QuickLaunch.Dark.Caption` | `12sp` | `400` | `dark_text_secondary` | 说明文字 |
| `TextAppearance.QuickLaunch.Dark.GroupHeader` | `12sp` | `400` | `dark_text_secondary` | 分组头（大写+字距） |
| `TextAppearance.QuickLaunch.BottomNav` | `10sp` | `400` | `dark_text_secondary` | 底栏标签 |

---

## 5. 组件样式（Component Stylings）

### 5.1 卡片（Card）
- 背景 `@color/dark_card_primary` `#1C1E26`；圆角 `dark_radius_card` `20dp`；
- 描边 `@color/dark_stroke` `#333542`，`dark_stroke_width` `1px`；`cardElevation 0dp`。
- 样式：`Widget.QuickLaunch.Dark.Card`（父 `Material3.CardView.Filled`）。

### 5.2 按钮（Button）
- **Primary**：`dark_radius_pill` 28dp 全圆角；背景 `@color/dark_accent_green` `#34C759`；文字 `@color/dark_bg_primary` `#0F111A`；`minHeight 44dp`；`16sp/500/letterSpacing 0.02`。
- **Secondary / Action**：同圆角；背景 `@color/dark_card_secondary` `#252833`；文字 `@color/dark_text_primary`。
- **Outlined**：`dark_radius_pill`；描边 `@color/dark_stroke` 1px；文字 `@color/dark_text_primary`。
- 样式：`Widget.QuickLaunch.Dark.Button.Primary / Secondary / Action / Capsule.Outlined`。

### 5.3 胶囊 Chip（触发条件 / 重复模式平铺）
- 未选中：`bg_dark_capsule_unselected`，文字 `@color/dark_text_secondary`，`14sp`，高 `36dp`。
- 选中：`bg_dark_capsule_selected`，文字 `@color/dark_bg_primary`，`sans-serif`。
- 样式：`Widget.QuickLaunch.Dark.Chip[.Selected]`。

### 5.4 底部导航栏（自定义毛玻璃）
- 容器 `FrameLayout @+id/bottomNav`：`bg_bottom_nav_glass`，高 `car_bottom_nav_height` `56dp`，`elevation 8dp`，贴底（`constraintBottom_toBottomOf`）。
- **底栏背景** `bg_bottom_nav_glass.xml`：主体 `@color/car_bottom_nav_bg` `#C0121212`，顶边 `24dp` 圆角，**上沿 1px 描边** `@color/car_bottom_nav_stroke` `#333542`。
- **选中项**：圆形 `FrameLayout` 直径 `car_nav_item_glow` `44dp`，背景 `bg_nav_item_selected`（`#1F334D` + `#2A5580` 1px 描边）；图标 `22dp` tint `@color/dark_accent_blue`；标签 `10sp` 蓝。
- **未选中项**：无背景；图标 tint `@color/dark_text_secondary` `#A1A1AA`；标签 `10sp` 次灰。
- 切换逻辑见 `MainActivity.selectTab()`（圆形包围盒 + 蓝字蓝图标）。

### 5.5 触发条件 4 联排网格卡（GridChip）
- 未选中：`bg_trigger_grid_item`（卡片底 + 1px 描边），文字/图标 tint `@color/dark_text_secondary`，`13sp`，`drawablePadding 10dp`，`paddingTop 16dp / paddingBottom 12dp`。
- 选中：`bg_trigger_grid_item_selected`（`#1C2E22` 底 + `#34C759` 绿描边），文字/图标 tint `@color/dark_accent_green`。
- 图标：`ic_trigger_time` / `ic_trigger_charging` / `ic_trigger_wifi` / `ic_trigger_bluetooth`（白色矢量）。
- 样式：`Widget.QuickLaunch.Dark.GridChip[.Selected]`。

### 5.6 输入框（Input）
- Filled 模式；背景 `@color/dark_input_bg` `#252833`；圆角 `dark_radius_pill` 28dp；描边 `@color/dark_stroke` 1px；hint `@color/dark_text_hint`。
- 样式：`Widget.QuickLaunch.Dark.Input`。

### 5.7 开关（Switch）
- thumb `@color/dark_switch_thumb` `#34C759`；track `@color/dark_switch_track` `#333542`。

### 5.8 危险操作（删除按钮）
- 背景 `@color/dark_destructive` `#FF453A`，文字白；仅用于不可逆操作。

---

## 6. 通知栏图标规范（Notification Icon Spec）

- **唯一 small icon**：`R.drawable.ic_notification_stat`
  - `res/drawable/ic_notification_stat.xml`：24dp 画布，**纯白 `#FFFFFF` 填充**，极简闪电路径，**无底色圈、无彩色**。
  - 系统会自动套用灰度/背景板，必须单色。
- **强制刷新旧缓存**：CHANNEL_ID 已升版（见下），旧图标缓存随之失效。
  - `LaunchService`（`object Notifier`）：`CHANNEL_ID = "quicklaunch_launch_v2"`
  - `KeepAliveService`：`CHANNEL = "quicklaunch_keepalive_v2"`
- **桌面图标同源**：`ic_launcher(_round)` 前景 `ic_launcher_foreground.xml` 为苹果绿 `#34C759` 闪电，与通知图标视觉一致。

---

## 7. Do's and Don'ts

**Do ✅**
1. 所有颜色/尺寸引用 `@color/*` / `@dimen/*`，不硬编码。
2. 卡片统一 20dp 圆角 + 1px `#333542` 描边 + 0 投影。
3. 强调色仅用状态绿 `#34C759` 与苹果蓝 `#007AFF`，且绿用于「开启/正向」、蓝用于「选中/高亮」。
4. 图标用线性/单色矢量（白色），靠 tint 控制状态色。
5. 通知 small icon 保持纯白单色；改图标必升 CHANNEL_ID 版本。
6. 底栏高度控制在 56~60dp，选中态用圆形高亮包围盒而非整块填充。

**Don't ❌**
1. 不要在布局里写死 `#1C1E26` 这类色值——改用 `@color/dark_card_primary`。
2. 不要把彩色放进通知 small icon（会导致部分 ROM 显示为灰色方块）。
3. 不要给卡片加投影阴影（车机风是纯扁平 + 描边）。
4. 不要把底栏高度撑到 80dp+ 的沉闷大块绿底。
5. 不要混用旧 `preset_mint_*` 浅色主题资源与暗色 Token。
6. 不要遗漏 CHANNEL_ID 升版——否则旧图标缓存不刷新。

---

## 8. Agent 提示指南（AI 代理使用）

**快速参考**：暗色车机主题 = 纯黑底 `#0F111A` + 深灰卡 `#1C1E26` + 1px 描边 `#333542` + 绿 `#34C759`/蓝 `#007AFF` 双强调；圆角 20dp（卡）/ 28dp（胶囊）；底栏自定义毛玻璃 56dp；通知图标纯白单色 `ic_notification_stat`。

**可直接复制的组件生成 Prompt 示例**：
1. 「生成一个车机暗色设置项卡片：左图标 + 标题 + 右侧 Switch，引用 `Widget.QuickLaunch.Dark.Card` 与圆角 20dp。」
2. 「生成一个 28dp 全圆角主按钮，背景 `@color/dark_accent_green`，文字 `@color/dark_bg_primary`，minHeight 44dp。」
3. 「生成一个底部毛玻璃导航项，选中态圆形 44dp 包围盒 `bg_nav_item_selected`，图标 tint 蓝；未选中无背景、tint 次灰。」
4. 「生成一个触发条件 4 联排网格，使用 `Widget.QuickLaunch.Dark.GridChip[.Selected]`，drawableTop 白色矢量图标。」
5. 「新增一条通知渠道，small icon 用 `R.drawable.ic_notification_stat`，并升版 CHANNEL_ID 后缀 `_v2`。」

**迭代建议**：
- 新增页面时复用 `bg_dark_root` 渐变根背景与 `Widget.QuickLaunch.Dark.Card`，不要另起配色。
- 任何新颜色先加进 `colors.xml`（带 `dark_` 前缀），再在布局引用。
- 保持底栏为单一自定义 `FrameLayout`，不要回退到 `BottomNavigationView` 默认样式。
- 触感反馈（ripple）沿用 `?attr/selectableItemBackground`，不要自定义彩色 ripple。
- 改动通知图标后务必验证 CHANNEL_ID 已升版，并在真机杀掉 App 重装以清除缓存。
