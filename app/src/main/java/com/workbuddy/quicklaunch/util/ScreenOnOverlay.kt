package com.workbuddy.quicklaunch.util

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.View
import android.view.WindowManager
import com.workbuddy.quicklaunch.BuildConfig

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

    private const val TAG = "QL-AntiSleep"

    /** displayId -> 已挂载的占位 View，便于按屏增删。 */
    private val views = mutableMapOf<Int, View>()

    private val main = Handler(Looper.getMainLooper())

    fun canDraw(context: Context): Boolean = Settings.canDrawOverlays(context)

    /**
     * WindowManager.addView 内部要建 Handler，必须跑在有 Looper 的线程上。
     * 调用方常常在线程池里（root 命令阻塞不能占主线程），所以这里统一兜住。
     */
    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
    }

    /**
     * 同步悬浮窗：给每块「亮着的」屏挂上常亮窗，已灭的屏移除。
     * 屏幕开关、折叠展开后重复调用即可，内部做了幂等。
     */
    fun sync(context: Context) = onMain {
        val can = canDraw(context)
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        // 合盖态下 Motorola 对第三方 App 隐藏外屏（displayId=1，物理端口 131，
        // 独立 display group），DisplayManager.getDisplays() 只返回内屏，导致外屏
        // 常亮窗永远挂不上。这里显式补 getDisplay(1) 与 PRESENTATION 类别，
        // 确保外屏被枚举到（该机型外屏 displayId 固定为 1）。
        val displays = mutableListOf<Display>().apply {
            dm?.displays?.let { all -> addAll(all) }
            dm?.getDisplay(1)?.let { ext ->
                if (none { d -> d.displayId == ext.displayId }) add(ext)
            }
            dm?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
                ?.filter { p -> none { d -> d.displayId == p.displayId } }
                ?.let { addAll(it) }
        }
        if (BuildConfig.DEBUG) Log.i(TAG, "sync: canDraw=$can displays=" +
            (displays.joinToString { "#${it.displayId}(state=${it.state})" }) +
            " 已挂=${views.keys}")
        if (can) {
            val byId = displays.associateBy { it.displayId }
            val lit = displays.filter { it.state == Display.STATE_ON }.map { it.displayId }.toSet()
            // 仅移除「当前可枚举且确为灭屏」的窗口。不可枚举的屏（如后台态 Motorola
            // 对第三方 App 隐藏外屏 displayId=1，或合盖态）保持不变，避免误删正在保活
            // 的悬浮窗——该窗口一旦挂上就持续压住对应 powerGroup，即便 App 退后台。
            views.keys.toList().forEach { id ->
                val d = byId[id]
                if (d != null && d.state != Display.STATE_ON) remove(id)
            }
            // 亮着但还没挂的补上
            displays.filter { it.displayId in lit && it.displayId !in views }
                .forEach { attach(context, it) }
        }
    }

    /** 全部摘除，关闭开关或服务销毁时调用。 */
    fun clear(context: Context) = onMain {
        views.keys.toList().forEach { remove(it) }
    }

    fun isActive(): Boolean = views.isNotEmpty()

    private fun attach(context: Context, display: Display) {
        runCatching {
            // 必须用目标 display 的 context 取 WindowManager，否则窗口会落到默认屏上
            val dctx = context.createDisplayContext(display)
            val wm = dctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val v = View(dctx)
            wm.addView(v, params())
            views[display.displayId] = v
            if (BuildConfig.DEBUG) Log.i(TAG, "常亮窗已挂载 display=${display.displayId}")
        }.onFailure {
            if (BuildConfig.DEBUG) Log.w(TAG, "常亮窗挂载失败 display=${display.displayId}: $it")
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
