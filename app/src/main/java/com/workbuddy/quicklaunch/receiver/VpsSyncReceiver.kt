package com.workbuddy.quicklaunch.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.workbuddy.quicklaunch.remote.vps.VpsSync
import com.workbuddy.quicklaunch.remote.vps.VpsSyncScheduler

/**
 * VPS 配置同步的定时入口。
 *
 * 每次执行后**无论成败都重排下一次**：一次网络抖动不应该让同步永久停摆
 * （这与 `AlarmReceiver` 的「先排下一次再启动」是同一条纪律）。
 *
 * exported=false：只由本应用的 PendingIntent 唤起。
 */
class VpsSyncReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        // 真机观察到同一次触发偶尔会被系统投递两遍（间隔约 30ms），
        // 表现为「下次同步已排程」打印两次。同步本身是幂等的，但没必要真跑两遍网络请求。
        val now = System.currentTimeMillis()
        if (now - lastRunAt < MIN_INTERVAL_MS) return
        lastRunAt = now

        ReceiverWorker.run(this, TAG) {
            runCatching { VpsSync.runOnce(app) }
            // 放在 finally 语义里：即使 runOnce 内部抛了未捕获异常，下一次也要排上
            runCatching { VpsSyncScheduler.schedule(app) }
        }
    }

    private companion object {
        const val TAG = "QL-VpsSyncReceiver"
        const val MIN_INTERVAL_MS = 3_000L

        @Volatile
        var lastRunAt: Long = 0L
    }
}
