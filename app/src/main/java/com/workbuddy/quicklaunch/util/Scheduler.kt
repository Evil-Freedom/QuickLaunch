package com.workbuddy.quicklaunch.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
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
        val triggerAt = nextTriggerTime(a)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            // 未授予精确闹钟权限：退化为不精确唤醒（仍会触发，时间略有偏差）
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    fun cancel(context: Context, a: Automation) {
        val pi = pendingIntent(context, a)
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi)
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
    fun nextTriggerTime(a: Automation): Long {
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
            when (a.repeatMode) {
                "weekdays" -> while (isWeekend(cal)) cal.add(Calendar.DAY_OF_YEAR, 1)
                "weekend" -> while (!isWeekend(cal)) cal.add(Calendar.DAY_OF_YEAR, 1)
            }
            // 保证是未来；重复/once 错过今天窗口都顺延到下一个有效日
            while (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)

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

        // 重复任务：先保证是未来时间
        while (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)

        when (a.repeatMode) {
            "weekdays" -> while (isWeekend(cal)) cal.add(Calendar.DAY_OF_YEAR, 1)
            "weekend" -> while (!isWeekend(cal)) cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    private fun isWeekend(cal: Calendar): Boolean {
        val d = cal.get(Calendar.DAY_OF_WEEK)
        return d == Calendar.SATURDAY || d == Calendar.SUNDAY
    }
}
