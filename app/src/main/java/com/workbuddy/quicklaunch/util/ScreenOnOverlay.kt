package com.workbuddy.quicklaunch.util

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.Settings
import android.view.Display
import android.view.View
import android.view.WindowManager

/**
 * 防息屏核心实现：在目标屏幕上挂一个 1x1 的透明悬浮窗，带 FLAG_KEEP_SCREEN_ON。
 *
 * 为什么不用 root 改 screen_off_timeout：
 * 真机实测 razr 40 Ultra 的外屏（displayId=1，独立 powerGroup）在锁屏态下有
 * 厂商硬编码的约 10 秒超时，把 screen_off_timeout 设成 Int.MAX_VALUE 也照灭不误
 * （settings 只能让它更早灭，不能让它更晚灭）。
 *
 * FLAG_KEEP_SCREEN_ON 是绑定到「窗口所在的 display group」的，直接压住那个组的
 * user activity 超时，不经过 settings，因此对外屏同样有效，而且不需要 root。
 * 代价是需要悬浮窗权限（SYSTEM_ALERT_WINDOW），本 App 本来就需要它来做后台拉起。
 */
object ScreenOnOverlay {

    /** displayId -> 已挂载的占位 View，便于按屏增删。 */
    private val views = mutableMapOf<Int, View>()

    fun canDraw(context: Context): Boolean = Settings.canDrawOverlays(context)

    /**
     * 同步悬浮窗：给每块「亮着的」屏挂上常亮窗，已灭的屏移除。
     * 屏幕开关、折叠展开后重复调用即可，内部做了幂等。
     */
    @Synchronized
    fun sync(context: Context) {
        if (!canDraw(context)) return
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        val displays = dm.displays ?: return

        val lit = displays.filter { it.state == Display.STATE_ON }.map { it.displayId }.toSet()

        // 已灭的屏先摘掉，避免残留窗口拖住其它显示组
        views.keys.toList().filterNot { it in lit }.forEach { remove(it) }

        // 亮着但还没挂的补上
        displays.filter { it.displayId in lit && it.displayId !in views }.forEach { attach(context, it) }
    }

    /** 全部摘除，关闭开关或服务销毁时调用。 */
    @Synchronized
    fun clear(context: Context) {
        views.keys.toList().forEach { remove(it) }
    }

    @Synchronized
    fun isActive(): Boolean = views.isNotEmpty()

    private fun attach(context: Context, display: Display) {
        runCatching {
            // 必须用目标 display 的 context 取 WindowManager，否则窗口会落到默认屏上
            val dctx = context.createDisplayContext(display)
            val wm = dctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val v = View(dctx)
            wm.addView(v, params())
            views[display.displayId] = v
        }
    }

    private fun remove(displayId: Int) {
        val v = views.remove(displayId) ?: return
        runCatching {
            val wm = v.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.removeViewImmediate(v)
        }
    }

    private fun params(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }
        return WindowManager.LayoutParams(
            1, 1, type,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            // 挪到角落且完全透明，用户不可见也点不到
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            x = 0
            y = 0
            alpha = 0f
        }
    }
}
