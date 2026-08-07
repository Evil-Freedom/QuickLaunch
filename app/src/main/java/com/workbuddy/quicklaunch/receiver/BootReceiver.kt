package com.workbuddy.quicklaunch.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.workbuddy.quicklaunch.service.KeepAliveService
import com.workbuddy.quicklaunch.util.AntiSleep
import com.workbuddy.quicklaunch.util.Scheduler

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
        val app = context.applicationContext

        // 保活服务必须在主线程同步拉起：BOOT_COMPLETED 期间系统允许启动前台服务，
        // 切到后台线程再启动有可能错过这个窗口。
        runCatching { KeepAliveService.start(app) }
        runCatching { WifiReceiver.register(app) }

        // 其余都是耗时操作（全表扫描 + 逐条排程 + root 授权等待），
        // 广播主线程只有约 10 秒预算，一律异步。
        ReceiverWorker.run(this, "BootReceiver") {
            // rescheduleAll 内部只加载一次节假日集合，规则再多也只查一次库
            runCatching { Scheduler.rescheduleAll(app) }
            // reapplyTimeoutOnly 内部已按「用户是否手动关」自判，开机即补上兜底，
            // 与悬浮窗权限无关（悬浮窗由刚拉起的服务负责），无需等授权。
            runCatching { AntiSleep.reapplyTimeoutOnly(app) }
        }
    }
}
