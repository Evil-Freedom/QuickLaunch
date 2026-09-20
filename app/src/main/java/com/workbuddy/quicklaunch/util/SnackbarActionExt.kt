package com.workbuddy.quicklaunch.util

import android.view.View
import com.google.android.material.snackbar.Snackbar

/**
 * 造一条「整条可点」的 Snackbar —— 用来替代 Material 的 `Snackbar.setAction()`。
 *
 * 最终显示为：`<message> · <actionText>`，点**整条**即执行 action。
 *
 * ── 为什么不用 setAction（踩坑记录，别改回去）────────────────────────
 * 实测环境：Razr 40 Ultra（外屏 display 1）/ Material 1.12.0 / 本项目 Theme.QuickLaunch.Dark。
 *
 * `setAction()` 之后动作按钮**完全不绘制任何内容**，但它在布局里正常占位、
 * 而且能正常响应点击 —— 这点是靠点击坐标反证出来的：点在被动作按钮占据的
 * (965, 694) 确实触发了 `undoEdit()`，界面弹出「已还原「飞书」到修改前」。
 * 也就是说用户只会看到一条白条，上面只有正文，右侧一片空白，撤销入口不可发现。
 *
 * 逐层追下来的根因：
 *   1. Snackbar 背景取 `colorSurfaceInverse`，本主题里是 `@color/text_white`（纯白）。
 *   2. 动作按钮文字色在 Material 的 `design_layout_snackbar_include.xml` 里写死为
 *      `android:textColor="?attr/colorAccent"`（**布局里的显式属性，优先级高于 style**，
 *      所以改 `snackbarButtonStyle` / `borderlessButtonStyle` 都盖不住）。
 *   3. 本主题没定义 `colorAccent`，走 MaterialComponents 的默认链
 *      `colorAccent → ?attr/colorSecondary → card_dark_bg → glass_card_bg = #0FFFFFFF`
 *      —— 6% 透明度的白。
 *   4. 白底 + 6% 白字，混合后像素恰好等于 (255,255,255)，与底色无法区分
 *      （截图逐像素验证：动作按钮所在区域恒为纯白，零偏差）。
 *   5. 运行时补调 `setActionTextColor()` **也无效**。反编译 `Snackbar.setActionTextColor`
 *      的字节码可见它确实调到了 `Button.setTextColor()`，但按钮依旧不绘制
 *      （换成不透明深紫 #805AD5 后重新截图，该区域仍是纯白）。
 *      `actionTextColorAlpha` 在 M3 里是 1.0，也不是 alpha 被清零的问题。
 *
 * 结论：不和框架的动作按钮缠斗，改走**确认可用**的通道 ——
 * Snackbar 的**正文**颜色是 `colorOnSurfaceInverse`（本主题里是近黑 #0C0C0C），
 * 在纯白底上清晰可见（截图已证实）。于是把动作提示并进正文，并让整条可点。
 *
 * 注：这里做成工厂函数而不是 `Snackbar.` 扩展，是因为 `Snackbar` 没有公开的
 * `getText()`，扩展函数拿不到已有正文，只能由调用方把正文一起传进来。
 *
 * @param message   正文（例如「已保存「飞书」的修改」）
 * @param actionText 动作提示（例如「点此撤销」）
 */
fun tappableSnackbar(
    root: View,
    message: CharSequence,
    actionText: CharSequence,
    duration: Int = Snackbar.LENGTH_LONG,
    action: () -> Unit
): Snackbar {
    val bar = Snackbar.make(root, "$message · $actionText", duration)
    bar.view.setOnClickListener { action() }
    return bar
}
