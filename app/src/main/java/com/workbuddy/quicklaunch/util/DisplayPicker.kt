package com.workbuddy.quicklaunch.util

import android.app.ActivityOptions
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.view.Display
import com.workbuddy.quicklaunch.launch.EXTERNAL_DISPLAY_ID

/**
 * 折叠屏选屏：决定把目标 App 投到哪块屏幕。
 *
 * Motorola razr 这类翻盖折叠屏在系统层有两种实现，本策略对两种都成立：
 * - 外屏是独立 Display（razr 40 Ultra 等）：合盖时内屏 STATE_OFF、外屏 STATE_ON，选中外屏
 * - 外屏复用 DEFAULT_DISPLAY 只换分辨率：只枚举到一块屏，直接走默认路径
 *
 * 不做机型型号判断——Google 官方文档明确反对设备白名单，只按运行时屏幕状态决策，
 * 顺带也能覆盖 Galaxy Fold、车机副屏、HDMI 外接等场景。
 *
 * ## 2026-11-23 真机修正：外屏可能不被枚举
 *
 * 合盖实测：`DisplayManager.getDisplays()` **不返回外屏（display 1）**，
 * 导致这里只看到内屏（STATE_OFF）→ 回退到 DEFAULT_DISPLAY(0) →
 * 目标 App 被投到**已经物理关闭的内屏**上，外屏始终显示桌面。
 *
 * `dumpsys activity activities` 的证据：
 *
 * ```
 * Display #1 ... isSleeping=false           ← 外屏活着
 * Display #0 ... isSleeping=true            ← 内屏睡眠
 *   mLastPausedActivity: ... com.android.settings/.Settings    ← 目标进了内屏
 *   Intent { flg=0x10000000 cmp=com.android.settings/.Settings }  ← 只有 NEW_TASK，没有 launchDisplayId
 * ```
 *
 * 修法与 `LaunchForegroundProbe.litDisplayIds` 完全一致：**补查 display 1**。
 * 两处必须同时补 —— 一处负责「投哪块屏」，一处负责「验证哪块屏」，
 * 只补一处会出现「投对了但判定看不到」或反之。
 */
object DisplayPicker {

    /** 当前应当用于启动的屏幕 id；判断不出来一律返回 DEFAULT_DISPLAY。 */
    fun activeDisplayId(context: Context): Int = runCatching {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            ?: return@runCatching Display.DEFAULT_DISPLAY

        val displays = buildList {
            dm.displays?.let { addAll(it) }
            // razr 外屏（display 1）在合盖态可能不出现在枚举结果里，必须单独补查
            dm.getDisplay(EXTERNAL_DISPLAY_ID)?.let { ext ->
                if (none { it.displayId == ext.displayId }) add(ext)
            }
        }
        if (displays.size <= 1) return@runCatching Display.DEFAULT_DISPLAY

        // 只认「唯一一块亮着的屏」这一条判据。
        // 录屏/投屏时主屏同时亮着（2 块 ON），自动落回默认屏，无需额外识别虚拟屏。
        // 两块都灭（息屏定时触发）时也回默认屏，由中转页 turnScreenOn 点亮系统当前主屏。
        val lit = displays.filter { it.state == Display.STATE_ON }
        val picked = if (lit.size == 1) lit[0].displayId else Display.DEFAULT_DISPLAY
        // 选屏结果必须可见：合盖场景的错误在这里表现为「目标投到了已关闭的内屏」，
        // 而外部只能看到「App 没出现」，无从判断是选屏错了还是启动被拒。
        runCatching {
            android.util.Log.i(
                "QL-DisplayPicker",
                "枚举=${displays.map { "${it.displayId}:${it.state}" }} 亮屏=${lit.map { it.displayId }} 选中=$picked"
            )
        }
        return@runCatching picked
        // DisplayManager 在部分 ROM 上枚举副屏会抛，出错一律回默认屏而不是让触发链断掉。
    }.getOrDefault(Display.DEFAULT_DISPLAY)

    /**
     * 生成 startActivity 的 options。
     * 返回 null 表示无需干预，让系统按默认行为处理（单屏设备的正常情况）。
     */
    fun launchOptions(context: Context): Bundle? {
        // setLaunchDisplayId 需要 API 26
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val id = activeDisplayId(context)
        if (id == Display.DEFAULT_DISPLAY) return null
        return runCatching { ActivityOptions.makeBasic().setLaunchDisplayId(id).toBundle() }
            .getOrNull()
    }
}
