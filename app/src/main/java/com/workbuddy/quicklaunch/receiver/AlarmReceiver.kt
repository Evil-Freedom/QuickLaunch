package com.workbuddy.quicklaunch.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.workbuddy.quicklaunch.data.AppDatabase
import com.workbuddy.quicklaunch.data.TriggerType
import com.workbuddy.quicklaunch.service.LaunchService
import com.workbuddy.quicklaunch.util.Scheduler

/**
 * 定时触发：由 AlarmManager 唤醒，拉起目标 App 并对重复任务重新排程。
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("automation_id", -1)
        if (id < 0) return

        val dao = AppDatabase.get(context).automationDao()
        val a = dao.getById(id) ?: return
        if (!a.enabled) return

        LaunchService.start(context, a.targetPackage, a.targetAppName)

        if (a.repeatMode != "once") {
            Scheduler.schedule(context, a)          // 排下一次
        } else {
            dao.update(a.copy(enabled = false))      // 一次性任务执行后自动关闭
        }
    }
}
