package com.workbuddy.quicklaunch.receiver

import android.content.BroadcastReceiver
import android.util.Log
import java.util.concurrent.Executors

/**
 * 广播接收器的统一后台执行器。
 *
 * 解决两类共性问题：
 * 1. **onReceive 跑在主线程**，里面直接查 Room、拉起服务会阻塞 UI；
 *    广播还有约 10 秒硬超时，超时即 ANR。用 goAsync() 拿到 PendingResult 后切后台执行。
 * 2. **onReceive 抛异常会直接杀掉进程**（系统按「接收器崩溃」处理）。
 *    这里统一 try/catch，任何异常只记日志，不影响其它触发规则。
 *
 * 单线程池：天然把并发到来的多个广播串行化，避免同时排程/写库互相打架。
 */
internal object ReceiverWorker {

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "receiver-worker").apply { isDaemon = true }
    }

    /** 必须在 onReceive 内（主线程）调用。 */
    fun run(receiver: BroadcastReceiver, tag: String, block: () -> Unit) {
        val pending = runCatching { receiver.goAsync() }.getOrNull()
        val task = Runnable {
            try {
                block()
            } catch (t: Throwable) {
                Log.e(tag, "broadcast handling failed", t)
            } finally {
                runCatching { pending?.finish() }
            }
        }
        // 拿不到 PendingResult 时只能同步执行，至少保证事件不丢
        if (pending == null) task.run()
        else runCatching { io.execute(task) }.onFailure { task.run() }
    }
}
