package com.workbuddy.quicklaunch.util

import android.content.Context
import android.provider.Settings

/**
 * 防息屏（含 razr 外屏）开关状态与系统超时设置。
 *
 * 设计目标：只要 App 的后台前台服务在跑（START_STICKY 常驻），且悬浮窗权限已授予、
 * 且用户没手动关，就自动压住息屏 —— 即「后台即防息屏」，不必再显式拨开关。
 *
 * 两层机制配合：
 * 1. [ScreenOnOverlay]：在亮着的屏上挂 FLAG_KEEP_SCREEN_ON 悬浮窗 —— 真正压住外屏的那一层。
 *    razr 40 Ultra 外屏是独立 powerGroup，厂商给了约 10 秒硬编码超时，改 settings 无效。
 *    这层由常驻服务（KeepAliveService）在后台自动挂载，与 Activity 生命周期解耦。
 * 2. root 改 screen_off_timeout：把内屏/全局超时也顶到最大，作为兜底增强，
 *    避免悬浮窗因权限被撤、进程被杀等意外失效时立刻黑屏。没 root 也能用（仅少这层兜底）。
 */
object AntiSleep {

    private const val SP_NAME = "quicklaunch"
    private const val KEY_ENABLED = "anti_sleep_enabled"   // 旧键，仅用于迁移读取
    private const val KEY_DISABLED = "anti_sleep_disabled"  // 新键：用户是否手动关闭
    private const val KEY_BACKUP = "anti_sleep_backup"

    /** Int.MAX_VALUE 毫秒，约 24.8 天，实际等同于永不息屏。 */
    private const val MAX_TIMEOUT = Int.MAX_VALUE

    /** 还原兜底值：读不到备份时按系统常见默认 30 秒处理。 */
    private const val FALLBACK_TIMEOUT = 30_000

    private fun sp(ctx: Context) = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)

    /**
     * 用户是否手动关闭了防息屏。默认 false = 开着。
     * 兼容旧版：若新键不存在但旧版 anti_sleep_enabled 存在，按旧值迁移。
     */
    fun isDisabled(ctx: Context): Boolean {
        val s = sp(ctx)
        if (s.contains(KEY_DISABLED)) return s.getBoolean(KEY_DISABLED, false)
        if (s.contains(KEY_ENABLED)) return !s.getBoolean(KEY_ENABLED, false)
        return false
    }

    /** 设置用户手动关闭状态。 */
    fun setDisabled(ctx: Context, disabled: Boolean) {
        sp(ctx).edit().putBoolean(KEY_DISABLED, disabled).apply()
    }

    /**
     * UI 用：当前是否处于「防息屏生效」状态。
     * 需满足：用户没手动关 + 有悬浮窗权限（没权限挂不了窗，自然不生效）。
     */
    fun isEnabled(ctx: Context): Boolean = !isDisabled(ctx) && ScreenOnOverlay.canDraw(ctx)

    /** 当前系统实际的熄屏超时，读操作不需要任何权限。 */
    fun currentTimeout(ctx: Context): Int =
        Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, FALLBACK_TIMEOUT)

    /**
     * 开启（手动）：若有 root 走 root 策略（改 screen_off_timeout），
     * 否则走悬浮窗策略。两者都兜底增强。
     */
    fun enable(ctx: Context): Boolean {
        if (!ScreenOnOverlay.canDraw(ctx)) return false
        setDisabled(ctx, false)
        val rooted = RootUtils.hasRoot()
        applyRootTimeout(ctx, rooted)
        if (!rooted) {
            // 没 root 才需要悬浮窗兜底
            ScreenOnOverlay.sync(ctx)
        }
        return true
    }

    /** 关闭（手动）：摘掉悬浮窗，有 root 才还原系统超时。 */
    fun disable(ctx: Context): Boolean {
        setDisabled(ctx, true)
        ScreenOnOverlay.clear(ctx)
        val rooted = RootUtils.hasRoot()
        if (rooted) {
            val back = sp(ctx).getInt(KEY_BACKUP, FALLBACK_TIMEOUT)
            if (currentTimeout(ctx) >= MAX_TIMEOUT) {
                RootUtils.runAsRootAsync("settings put system screen_off_timeout $back")
            }
        }
        return true
    }

    /**
     * 开机 / 屏幕状态变化后重新套用。
     * 有 root：root 改超时 + 悬浮窗兜底；没 root：仅悬浮窗。
     */
    fun reapply(ctx: Context) {
        if (isDisabled(ctx)) {
            ScreenOnOverlay.clear(ctx)
            return
        }
        val rooted = RootUtils.hasRoot()
        applyRootTimeout(ctx, rooted)
        if (!rooted) {
            ScreenOnOverlay.sync(ctx)
        }
    }

    /** 只重置系统超时，不碰悬浮窗（窗口由常驻服务统一管理）。 */
    fun reapplyTimeoutOnly(ctx: Context) {
        if (isDisabled(ctx)) return
        val rooted = RootUtils.hasRoot()
        applyRootTimeout(ctx, rooted)
    }

    /** 有 root 才改 settings；没 root 静默跳过。 */
    private fun applyRootTimeout(ctx: Context, rooted: Boolean) {
        if (!rooted) return
        // 读 Settings 可能因 ContentProvider 未就绪抛异常（开机早期尤其常见），不能让它带崩调用链
        val now = runCatching { currentTimeout(ctx) }.getOrNull() ?: return
        if (now >= MAX_TIMEOUT) return
        if (now in 1 until MAX_TIMEOUT) {
            runCatching { sp(ctx).edit().putInt(KEY_BACKUP, now).apply() }
        }
        RootUtils.runAsRootAsync("settings put system screen_off_timeout $MAX_TIMEOUT")
    }
}
