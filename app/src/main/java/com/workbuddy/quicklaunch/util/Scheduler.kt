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
     */
    fun nextTriggerTime(a: Automation): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, a.timeHour)
            set(Calendar.MINUTE, a.timeMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        if (a.repeatMode == "once") {
            if (cal.timeInMillis <= System.currentTimeMillis()) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
            return cal.timeInMillis
        }

        // 重复任务：先保证是未来时间
        while (cal.timeInMillis <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }

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
