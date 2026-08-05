package com.workbuddy.quicklaunch.util

import android.content.Context
import android.provider.Settings

/**
 * 防息屏（含 razr 外屏）：通过 root 把系统熄屏超时改成极大值。
 *
 * 外屏（displayId=1）与内屏共用 Settings.System.SCREEN_OFF_TIMEOUT，
 * 因此改这一个 key 即可让外屏保持常亮，等效独立刷机的 NeverSleep 模块，
 * 区别是这里可以在 App 内一键开关，并且会备份原值以便还原。
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

    /** 当前系统实际的熄屏超时，读操作不需要任何权限。 */
    fun currentTimeout(ctx: Context): Int =
        Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, FALLBACK_TIMEOUT)

    /**
     * 开启常亮。先备份当前值（避免把极大值本身当成原值备份进去），再写入最大值。
     * @return 是否写入成功
     */
    fun enable(ctx: Context): Boolean {
        val now = currentTimeout(ctx)
        if (now in 1 until MAX_TIMEOUT) {
            sp(ctx).edit().putInt(KEY_BACKUP, now).apply()
        }
        val ok = RootUtils.runAsRoot("settings put system screen_off_timeout $MAX_TIMEOUT")
        if (ok) sp(ctx).edit().putBoolean(KEY_ENABLED, true).apply()
        return ok
    }

    /** 关闭常亮，恢复备份的超时值。 */
    fun disable(ctx: Context): Boolean {
        val back = sp(ctx).getInt(KEY_BACKUP, FALLBACK_TIMEOUT)
        val ok = RootUtils.runAsRoot("settings put system screen_off_timeout $back")
        if (ok) sp(ctx).edit().putBoolean(KEY_ENABLED, false).apply()
        return ok
    }

    /**
     * 重启或系统改动后重新套用。只有开关处于开启态且当前值被系统改回去了才动手，
     * 避免每次开机都无意义地执行一次 root 命令。
     */
    fun reapply(ctx: Context) {
        if (!isEnabled(ctx)) return
        if (currentTimeout(ctx) >= MAX_TIMEOUT) return
        RootUtils.runAsRoot("settings put system screen_off_timeout $MAX_TIMEOUT")
    }
}
