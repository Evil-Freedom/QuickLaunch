package com.workbuddy.quicklaunch.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.workbuddy.quicklaunch.data.AppDatabase
import com.workbuddy.quicklaunch.data.TriggerType
import com.workbuddy.quicklaunch.service.LaunchService

/**
 * 充电触发：插入电源（AC / USB / 无线）时拉起目标 App。
 */
class PowerConnectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_POWER_CONNECTED) return
        val app = context.applicationContext
        ReceiverWorker.run(this, "PowerConnectionReceiver") {
            val list = runCatching {
                AppDatabase.get(app).automationDao().getEnabledByType(TriggerType.CHARGING)
            }.getOrDefault(emptyList())
            list.forEach {
                runCatching { LaunchService.start(app, it.targetPackage, it.targetAppName) }
            }
        }
    }
}
