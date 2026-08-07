package com.workbuddy.quicklaunch.util

import android.app.ActivityOptions
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.view.Display

/**
 * 折叠屏选屏：决定把目标 App 投到哪块屏幕。
 *
 * Motorola razr 这类翻盖折叠屏在系统层有两种实现，本策略对两种都成立：
 * - 外屏是独立 Display（razr 10 Ultra 等）：合盖时内屏 STATE_OFF、外屏 STATE_ON，选中外屏
 * - 外屏复用 DEFAULT_DISPLAY 只换分辨率：只枚举到一块屏，直接走默认路径
 *
 * 不做机型型号判断——Google 官方文档明确反对设备白名单，只按运行时屏幕状态决策，
 * 顺带也能覆盖 Galaxy Fold、车机副屏、HDMI 外接等场景。
 */
object DisplayPicker {

    /** 当前应当用于启动的屏幕 id；判断不出来一律返回 DEFAULT_DISPLAY。 */
    fun activeDisplayId(context: Context): Int = runCatching {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            ?: return@runCatching Display.DEFAULT_DISPLAY
        val displays = dm.displays ?: return@runCatching Display.DEFAULT_DISPLAY
        if (displays.size <= 1) return@runCatching Display.DEFAULT_DISPLAY

        // ponytail: 只认「唯一一块亮着的屏」这一条判据。
        // 录屏/投屏时主屏同时亮着（2 块 ON），自动落回默认屏，无需额外识别虚拟屏。
        // 两块都灭（息屏定时触发）时也回默认屏，由中转页 turnScreenOn 点亮系统当前主屏。
        val lit = displays.filter { it.state == Display.STATE_ON }
        if (lit.size == 1) lit[0].displayId else Display.DEFAULT_DISPLAY
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
