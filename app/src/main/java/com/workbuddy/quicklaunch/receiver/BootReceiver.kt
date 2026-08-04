package com.workbuddy.quicklaunch.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.workbuddy.quicklaunch.data.AppDatabase
import com.workbuddy.quicklaunch.data.TriggerType
import com.workbuddy.quicklaunch.service.LaunchService
import com.workbuddy.quicklaunch.util.Scheduler

/**
 * 开机完成后，把已启用的「定时」类任务重新注册到 AlarmManager。
 * 事件类（充电 / WiFi / 蓝牙）由系统在发生时直接广播，无需重排。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val list = AppDatabase.get(context).automationDao().getEnabledByType(TriggerType.TIME)
        list.forEach { Scheduler.schedule(context, it) }
    }
}
