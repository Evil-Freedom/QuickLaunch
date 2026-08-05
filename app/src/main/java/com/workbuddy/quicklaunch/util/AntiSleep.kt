package com.workbuddy.quicklaunch.util

import android.content.Context
import android.provider.Settings

/**
 * 防息屏（含 razr 外屏）开关状态与系统超时设置。
 *
 * 两层机制配合，缺一不可：
 * 1. [ScreenOnOverlay]：在亮着的屏上挂 FLAG_KEEP_SCREEN_ON 悬浮窗 —— 真正压住外屏的那一层。
 *    razr 40 Ultra 外屏是独立 powerGroup，厂商给了约 10 秒硬编码超时，改 settings 无效。
 * 2. 本类的 root 改 screen_off_timeout：把内屏/全局超时也顶到最大，
 *    避免悬浮窗因权限被撤、进程被杀等意外失效时立刻黑屏。属于兜底增强，没 root 也能用。
 */
object AntiSleep {

    private const val SP_NAME = "quicklaunch"
    private const val KEY_ENABLED = "anti_sleep_enabled"
    private const val KEY_BACKUP = "anti_sleep_backup"

    /** Int.MAX_VALUE 毫秒，约 24.8 天，实际等同于永不息屏。 */
    private const val MAX_TIMEOUT = Int.MAX_VALUE

    /** 还原兜底值：读不到备份时按系统常见默认 30 秒处理。 */
    private const val FALLBACK_TIMEOUT = 30_000

    private fun sp(ctx: Context) = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_ENABLED, false)

    fun setEnabled(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean(KEY_ENABLED, on).apply()
    }

    /** 当前系统实际的熄屏超时，读操作不需要任何权限。 */
    fun currentTimeout(ctx: Context): Int =
        Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, FALLBACK_TIMEOUT)

    /**
     * 开启：悬浮窗常亮 + （若有 root）顶高系统超时。
     * 挂窗口要切主线程，是异步的，所以这里用「权限是否具备」作为成功判据。
     */
    fun enable(ctx: Context): Boolean {
        if (!ScreenOnOverlay.canDraw(ctx)) return false
        setEnabled(ctx, true)
        applyRootTimeout(ctx)
        ScreenOnOverlay.sync(ctx)
        return true
    }

    /** 关闭：摘掉悬浮窗，并把系统超时还原成开启前备份的值。 */
    fun disable(ctx: Context): Boolean {
        setEnabled(ctx, false)
        ScreenOnOverlay.clear(ctx)
        val back = sp(ctx).getInt(KEY_BACKUP, FALLBACK_TIMEOUT)
        if (currentTimeout(ctx) >= MAX_TIMEOUT) {
            RootUtils.runAsRoot("settings put system screen_off_timeout $back")
        }
        return true
    }

    /** 开机 / 屏幕状态变化后重新套用，未开启时直接返回。 */
    fun reapply(ctx: Context) {
        if (!isEnabled(ctx)) return
        applyRootTimeout(ctx)
        ScreenOnOverlay.sync(ctx)
    }

    /** 只重置系统超时，不碰悬浮窗（窗口由常驻服务统一管理）。 */
    fun reapplyTimeoutOnly(ctx: Context) {
        if (!isEnabled(ctx)) return
        applyRootTimeout(ctx)
    }

    /** 只有真有 root 才动 settings；没 root 静默跳过，不影响悬浮窗那条主路径。 */
    private fun applyRootTimeout(ctx: Context) {
        val now = currentTimeout(ctx)
        if (now >= MAX_TIMEOUT) return
        if (now in 1 until MAX_TIMEOUT) {
            sp(ctx).edit().putInt(KEY_BACKUP, now).apply()
        }
        RootUtils.runAsRoot("settings put system screen_off_timeout $MAX_TIMEOUT")
    }
}
