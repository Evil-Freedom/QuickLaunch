package com.workbuddy.quicklaunch.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.workbuddy.quicklaunch.BuildConfig
import com.workbuddy.quicklaunch.util.FeishuProcessManager
import java.util.concurrent.Executors

/**
 * 真机诊断入口：**只**用于验证飞书进程生命周期（force-stop / 进程清零）。
 *
 * 为什么需要它：`FeishuProcessManager` 只有被考勤核验动作唤起才会跑，
 * 而做「杀进程 → 重新启动」的稳定性测试时不该伪造考勤成功、也不该往生产
 * VPS 写动作记录。这里给测试留一个纯本地入口。
 *
 * 三重边界：
 * 1. `BuildConfig.QL_DIAG` 为 false（默认）时**直接返回**，正式包等同一个空实现；
 * 2. 不产生任何网络请求、不写 VPS、不发邮件、不改考勤状态；
 * 3. 只对常量包名 `com.ss.android.lark` 生效，不认识任何外部传入的包名。
 */
class DiagReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.QL_DIAG) return
        if (intent.action != ACTION) return
        val app = context.applicationContext
        val pending = goAsync()
        executor.execute {
            try {
                val before = FeishuProcessManager.processes()
                val report = FeishuProcessManager.forceStop()
                Log.i(
                    TAG,
                    "DIAG forceStop ok=${report.ok} exit=${report.exitCode} " +
                        "elapsed=${report.elapsedMs}ms before=${before.size} " +
                        "after=${report.remaining.size} detail=${report.detail}"
                )
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "com.workbuddy.quicklaunch.DIAG_FORCE_STOP"
        private const val TAG = "QL-Diag"

        private val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "ql-diag").apply { isDaemon = true }
        }
    }
}
