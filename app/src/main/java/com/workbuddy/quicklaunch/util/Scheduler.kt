package com.workbuddy.quicklaunch.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.workbuddy.quicklaunch.data.AppDatabase
import com.workbuddy.quicklaunch.data.Automation
import com.workbuddy.quicklaunch.data.TriggerType
import com.workbuddy.quicklaunch.receiver.AlarmReceiver
import java.util.Calendar

/**
 * 负责把「定时」类自动化注册到系统的 AlarmManager。
 * 重复任务采用「到点触发后重新排程下一次」的方式，保证精确且可靠。
 */
object Scheduler {

    fun schedule(context: Context, a: Automation) {
        if (a.triggerType != TriggerType.TIME) return
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context, a)
        val triggerAt = nextTriggerTime(a, skipPredicate(context, a))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            // 未授予精确闹钟权限：退化为不精确唤醒（仍会触发，时间略有偏差）
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    /** 构造「是否应跳过该天」的判定：仅在开启 skipHolidays 时查本地节假日表，否则恒不跳过。 */
    private fun skipPredicate(context: Context, a: Automation): (Calendar) -> Boolean {
        if (!a.skipHolidays) return { false }
        val checker = HolidayChecker.fromDb(context)
        return { cal -> checker.isHoliday(cal) }
    }

    fun cancel(context: Context, a: Automation) {
        val pi = pendingIntent(context, a)
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi)
    }

    /** 重新排程所有已启用的「定时」自动化，使最新的节假日/星期设置立即生效。 */
    fun rescheduleAll(context: Context) {
        AppDatabase.get(context)
            .automationDao()
            .getEnabledByType(TriggerType.TIME)
            .forEach { schedule(context, it) }
    }

    private fun pendingIntent(context: Context, a: Automation): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("automation_id", a.id)
        }
        // requestCode 用 id 保证每条规则对应独立的 PendingIntent
        return PendingIntent.getBroadcast(
            context,
            a.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 计算下一次触发时间（毫秒）。
     * - 普通定时：用 timeHour/timeMinute。
     * - 随机窗口（randomWindow=true）：在 [windowStartMin, windowEndMin] 当天分钟区间里随机取一个时刻，
     *   重复任务每次重排都重新随机，实现「每天不固定的触发时刻」。
     */
    /** 公开入口：不感知节假日（供测试与兼容使用），等价于 shouldSkip 恒为 false。 */
    fun nextTriggerTime(a: Automation): Long = nextTriggerTime(a) { false }

    /**
     * 计算下一次触发时间（毫秒）。
     * @param shouldSkip 返回 true 表示该天应被跳过（如命中法定节假日）。仅对重复模式生效，
     *                   一次性(once)不受节假日影响。
     */
    internal fun nextTriggerTime(a: Automation, shouldSkip: (Calendar) -> Boolean): Long {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()

        if (a.randomWindow) {
            val start = a.windowStartMin
            val end = if (a.windowEndMin > a.windowStartMin) a.windowEndMin else a.windowStartMin + 1
            fun resetToStart(c: Calendar) {
                c.set(Calendar.HOUR_OF_DAY, start / 60)
                c.set(Calendar.MINUTE, start % 60)
                c.set(Calendar.SECOND, 0)
                c.set(Calendar.MILLISECOND, 0)
            }
            resetToStart(cal)
            // 先落到本周期内第一个有效日，再逐日推进保证始终是未来且符合 repeatMode / 跳过规则
            advanceToValidDay(cal, a)
            while (cal.timeInMillis <= now || shouldSkip(cal)) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
                advanceToValidDay(cal, a)
            }

            val span = end - start
            val picked = start + java.util.Random().nextInt(span + 1)
            cal.set(Calendar.HOUR_OF_DAY, picked / 60)
            cal.set(Calendar.MINUTE, picked % 60)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }

        // 普通固定时刻
        cal.set(Calendar.HOUR_OF_DAY, a.timeHour)
        cal.set(Calendar.MINUTE, a.timeMinute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        if (a.repeatMode == "once") {
            if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
            return cal.timeInMillis
        }

        // 重复任务：先今天对齐到有效日（以防今天就是无效日但时刻还没到），
        // 再逐日推进保证是未来且每个候选日都满足 repeatMode 约束与跳过规则
        advanceToValidDay(cal, a)
        while (cal.timeInMillis <= now || shouldSkip(cal)) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
            advanceToValidDay(cal, a)
        }
        return cal.timeInMillis
    }

    /**
     * 把日期推进到满足 repeatMode 约束的下一天（含当天）。
     * daily/once 无约束；weekdays/weekend 按周末过滤；custom 按 repeatDays 位图过滤。
     */
    private fun advanceToValidDay(cal: Calendar, a: Automation) {
        when (a.repeatMode) {
            "weekdays" -> while (isWeekend(cal)) cal.add(Calendar.DAY_OF_YEAR, 1)
            "weekend" -> while (!isWeekend(cal)) cal.add(Calendar.DAY_OF_YEAR, 1)
            "custom" -> {
                // mask 为 0 视为数据异常，退化为「任意一天」避免死循环
                if (a.repeatDays != 0) {
                    while (!isSelectedDay(cal, a.repeatDays)) cal.add(Calendar.DAY_OF_YEAR, 1)
                }
            }
        }
    }

    private fun isWeekend(cal: Calendar): Boolean {
        val d = cal.get(Calendar.DAY_OF_WEEK)
        return d == Calendar.SATURDAY || d == Calendar.SUNDAY
    }

    /** repeatDays 位图：bit (Calendar.DAY_OF_WEEK - 1) 表示选中。 */
    private fun isSelectedDay(cal: Calendar, mask: Int): Boolean {
        val bit = cal.get(Calendar.DAY_OF_WEEK) - 1
        return (mask shr bit) and 1 == 1
    }
}
