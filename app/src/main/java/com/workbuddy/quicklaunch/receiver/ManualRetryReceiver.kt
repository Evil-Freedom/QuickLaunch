package com.workbuddy.quicklaunch.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.workbuddy.quicklaunch.launch.LaunchAttemptController

/**
 * 「失败通知 → 再次启动」的落点。
 *
 * 为什么不在通知里直接 PendingIntent 到 LaunchProxyActivity：
 * 那样会绕过验证链路，用户点完还是不知道到底起没起来。走这里回到
 * [LaunchAttemptController.begin] 才是「手动重试」的正确语义 ——
 * 它会先探测目标是否已在前台，然后按同一套重试纪律执行。
 *
 * exported=false：只由本应用的 PendingIntent 唤起，不接受外部广播。
 */
class ManualRetryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pkg = intent.getStringExtra(EXTRA_PKG).orEmpty()
        if (pkg.isBlank()) return
        val appName = intent.getStringExtra(EXTRA_APP_NAME).orEmpty()
        val app = context.applicationContext
        ReceiverWorker.run(this, TAG) {
            LaunchAttemptController.begin(app, ruleId = -1L, pkg = pkg, appName = appName, manual = true)
        }
    }

    companion object {
        const val EXTRA_PKG = "manual_retry_pkg"
        const val EXTRA_APP_NAME = "manual_retry_app_name"
        private const val TAG = "QL-ManualRetry"
    }
}
