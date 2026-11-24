package com.workbuddy.quicklaunch.util

import android.content.Context

/**
 * 两个独立的清理开关。**本地设置是最终执行权限**，VPS 下发的清理指令不能绕过它。
 *
 * * [beforeLaunch]：启动飞书前清掉遗留进程（默认开）；
 * * [afterCheckin]：只有真实考勤核验成功后才彻底关闭飞书（默认开）。
 *
 * `awaitingCleanup` 是「还有未完成的考勤任务」的本地标记：置位期间禁止启动前清理，
 * 否则会把用户还没打完卡的飞书杀掉。它只由 ActionPoller 在收到核验成功动作时清除。
 */
object CleanupSettings {

    private const val SP = "quicklaunch_cleanup"
    private const val KEY_BEFORE = "cleanup_before_launch"
    private const val KEY_AFTER = "cleanup_after_checkin"
    private const val KEY_AWAITING = "awaiting_checkin_cleanup"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)

    fun beforeLaunch(ctx: Context): Boolean =
        runCatching { sp(ctx).getBoolean(KEY_BEFORE, true) }.getOrDefault(true)

    fun setBeforeLaunch(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean(KEY_BEFORE, v).apply()

    fun afterCheckin(ctx: Context): Boolean =
        runCatching { sp(ctx).getBoolean(KEY_AFTER, true) }.getOrDefault(true)

    fun setAfterCheckin(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean(KEY_AFTER, v).apply()

    /** 有未完成的考勤任务（已启动、尚未核验或尚未清理）。 */
    fun awaitingCleanup(ctx: Context): Boolean =
        runCatching { sp(ctx).getBoolean(KEY_AWAITING, false) }.getOrDefault(false)

    fun setAwaitingCleanup(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean(KEY_AWAITING, v).apply()
}
