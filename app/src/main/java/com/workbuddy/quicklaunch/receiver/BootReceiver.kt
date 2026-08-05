package com.workbuddy.quicklaunch.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.workbuddy.quicklaunch.data.AppDatabase
import com.workbuddy.quicklaunch.data.TriggerType
import com.workbuddy.quicklaunch.service.KeepAliveService
import com.workbuddy.quicklaunch.util.AntiSleep
import com.workbuddy.quicklaunch.util.Scheduler
import kotlin.concurrent.thread

/**
 * 开机完成后重建触发条件：
 * - 定时任务重新注册到 AlarmManager（闹钟不会跨重启保留）
 * - WiFi 网络回调重新注册（同样不跨重启）
 * - 保活前台服务重新拉起
 * - 防息屏设置重新套用（系统重启后熄屏超时会回到默认值）
 * 充电 / 蓝牙走系统豁免广播，清单静态注册即可，无需重排。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        AppDatabase.get(context).automationDao().getEnabledByType(TriggerType.TIME)
            .forEach { Scheduler.schedule(context, it) }
        WifiReceiver.register(context)
        KeepAliveService.start(context)

        // root 命令耗时且可能等待授权，广播主线程只有 10 秒，必须异步
        val app = context.applicationContext
        thread(isDaemon = true) { AntiSleep.reapply(app) }
    }
}
