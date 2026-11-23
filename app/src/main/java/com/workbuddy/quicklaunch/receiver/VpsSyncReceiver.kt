package com.workbuddy.quicklaunch.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.workbuddy.quicklaunch.remote.vps.VpsSync
import com.workbuddy.quicklaunch.remote.vps.VpsSyncScheduler
import java.util.concurrent.Executors

/**
 * VPS 配置同步的定时入口。
 *
 * 每次执行后**无论成败都重排下一次**：一次网络抖动不应该让同步永久停摆
 * （这与 `AlarmReceiver` 的「先排下一次再启动」是同一条纪律）。
 *
 * ## 为什么不用 [ReceiverWorker]
 *
 * `ReceiverWorker` 走全局的 `QuickLaunchExecutors.save` 单线程池，所有广播
 * （含定时触发的规则处理）都在同一队列上排队。真机观察到：进程被 force-stop
 * 后恢复的极端场景里，save 池出现过任务长时间不轮转的情况 —— 那会让
 * 「同步」这件低优先级的事被规则触发的长任务（root 调用、全量重排）无限押后。
 *
 * 同步是独立的旁路通道，配独立单线程池：卡住也只卡自己，且 daemon 线程
 * 不阻止进程退出。排程（AlarmManager）仍在 [onReceive] 主线程同步完成 ——
 * 它只是登记 PendingIntent，几微秒的事。
 *
 * exported=false：只由本应用的 PendingIntent 唤起。
 */
class VpsSyncReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        // 真机观察到同一次触发偶尔会被系统投递两遍（间隔约 30ms），
        // 同步本身幂等，但没必要真跑两遍网络请求
        val now = System.currentTimeMillis()
        if (now - lastRunAt < MIN_INTERVAL_MS) return
        lastRunAt = now

        // 排程放主线程：PendingIntent 登记不耗时，先保证「下一次一定在」
        runCatching { VpsSyncScheduler.schedule(app) }

        executor.execute {
            try {
                runCatching { VpsSync.runOnce(app) }
            } finally {
                // 无论同步结果如何，下一次都已排上（见上）
            }
        }
    }

    companion object {
        private const val MIN_INTERVAL_MS = 3_000L

        @Volatile
        private var lastRunAt: Long = 0L

        /** 同步专用单线程池（daemon，不阻止进程退出）。 */
        private val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "ql-vps-sync").apply { isDaemon = true }
        }
    }
}
