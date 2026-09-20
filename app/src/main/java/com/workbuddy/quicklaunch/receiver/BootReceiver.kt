package com.workbuddy.quicklaunch.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.workbuddy.quicklaunch.service.KeepAliveService
import com.workbuddy.quicklaunch.util.AntiSleep
import com.workbuddy.quicklaunch.util.Scheduler

/**
 * 重建触发条件，两个入口共用同一套逻辑：
 * - [Intent.ACTION_BOOT_COMPLETED] 开机完成
 * - [Intent.ACTION_MY_PACKAGE_REPLACED] 本应用被覆盖安装
 *
 * 两者要做的事完全一样，因为**覆盖安装和重启的后果一样**：进程被换掉，
 * AlarmManager 里的闹钟与 registerNetworkCallback 的回调全部丢失。
 * 早期只处理了 BOOT_COMPLETED，导致每次升级 App 后所有定时规则静默失效
 * （真机复现：覆盖安装后 `dumpsys alarm` 里本应用的闹钟为 0 条），
 * 直到手机重启、或用户去动一次节假日设置才恢复。
 *
 * 具体重建：定时任务 → AlarmManager，WiFi 网络回调重新注册，保活前台服务重新拉起，
 * 防息屏设置重新套用（系统重启后熄屏超时会回到默认值）。
 * 充电 / 蓝牙走系统豁免广播，清单静态注册即可，无需重排。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        val app = context.applicationContext

        // 保活服务必须在主线程同步拉起：BOOT_COMPLETED 期间系统允许启动前台服务，
        // 切到后台线程再启动有可能错过这个窗口。
        runCatching { KeepAliveService.start(app) }
        runCatching { WifiReceiver.register(app) }

        // 其余都是耗时操作（全表扫描 + 逐条排程 + root 授权等待），
        // 广播主线程只有约 10 秒预算，一律异步。
        ReceiverWorker.run(this, "BootReceiver:$action") {
            // rescheduleAll 内部只加载一次节假日集合，规则再多也只查一次库
            runCatching { Scheduler.rescheduleAll(app) }
            // reapplyTimeoutOnly 内部已按「用户是否手动关」自判，开机即补上兜底，
            // 与悬浮窗权限无关（悬浮窗由刚拉起的服务负责），无需等授权。
            runCatching { AntiSleep.reapplyTimeoutOnly(app) }
        }
    }
}
