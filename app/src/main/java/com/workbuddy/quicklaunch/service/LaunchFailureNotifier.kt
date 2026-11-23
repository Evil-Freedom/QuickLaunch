package com.workbuddy.quicklaunch.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.workbuddy.quicklaunch.BuildConfig
import com.workbuddy.quicklaunch.R
import com.workbuddy.quicklaunch.launch.LaunchAttemptEngine
import com.workbuddy.quicklaunch.receiver.ManualRetryReceiver

/**
 * 「自动启动最终失败」的可见出口。
 *
 * 设计取向：
 * - 用**独立渠道**而不是复用「正在启动」渠道：用户可以把「启动失败」设为静音，
 *   同时保留启动过程的前台服务通知。渠道一旦发布就改不了重要性，所以一开始就分开。
 * - 通知带一个「再次启动」按钮，走 [ManualRetryReceiver] → 重新进入状态机，
 *   而不是直接拉起目标 App —— 否则手动重试就绕过了验证，用户又会回到
 *   「点了没反应也不知道成没成」的起点。
 * - 通知里写清失败原因与尝试次数。含糊的「启动失败」等于没通知。
 */
object LaunchFailureNotifier {

    private const val TAG = "QL-LaunchFail"
    private const val CHANNEL_ID = "quicklaunch_launch_fail_v1"

    /** 按包名区分通知 id，多个 App 同时失败不会互相覆盖。 */
    private fun notifId(pkg: String): Int = 2000 + (pkg.hashCode() and 0x3FF)

    fun notifyFailure(
        context: Context,
        appName: String,
        pkg: String,
        reason: String,
        tries: Int,
        allowManualRetry: Boolean
    ) {
        val app = context.applicationContext
        val allowed = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!allowed) {
            if (BuildConfig.DEBUG) Log.w(TAG, "无通知权限，失败通知无法送达：$pkg")
            return
        }

        runCatching {
            ensureChannel(app)
            val builder = NotificationCompat.Builder(app, CHANNEL_ID)
                .setContentTitle(app.getString(R.string.launch_fail_title, appName))
                .setContentText(
                    app.getString(R.string.launch_fail_text, tries, humanReason(app, reason))
                )
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        app.getString(R.string.launch_fail_text, tries, humanReason(app, reason))
                    )
                )
                .setSmallIcon(R.drawable.ic_notification_stat)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setAutoCancel(true)

            if (allowManualRetry) {
                builder.addAction(
                    0,
                    app.getString(R.string.launch_fail_action_retry),
                    retryIntent(app, pkg, appName)
                )
                // 点通知主体也走手动重试，避免用户以为点一下就能好、结果只是打开主界面
                builder.setContentIntent(retryIntent(app, pkg, appName))
            }

            NotificationManagerCompat.from(app).notify(notifId(pkg), builder.build())
            if (BuildConfig.DEBUG) Log.i(TAG, "失败通知已发送 pkg=$pkg reason=$reason tries=$tries")
        }.onFailure { if (BuildConfig.DEBUG) Log.e(TAG, "失败通知发送失败：$pkg", it) }
    }

    /** 用户手动确认过（或已成功）后清掉残留的失败通知。 */
    fun clear(context: Context, pkg: String) {
        runCatching {
            NotificationManagerCompat.from(context.applicationContext).cancel(notifId(pkg))
        }
    }

    private fun retryIntent(app: Context, pkg: String, appName: String): PendingIntent {
        val intent = Intent(app, ManualRetryReceiver::class.java).apply {
            putExtra(ManualRetryReceiver.EXTRA_PKG, pkg)
            putExtra(ManualRetryReceiver.EXTRA_APP_NAME, appName)
        }
        return PendingIntent.getBroadcast(
            app,
            notifId(pkg),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 把内部原因码翻译成用户能据此行动的一句话。
     *
     * 保留原始码放在括号里：排查时需要它，而用户看到的必须是「能做什么」而不是
     * `VERIFY_TIMEOUT` 这种人话未翻译的东西。
     */
    private fun humanReason(context: Context, reason: String): String = when (reason) {
        LaunchAttemptEngine.REASON_VERIFY_TIMEOUT ->
            context.getString(R.string.launch_fail_reason_verify_timeout)
        LaunchAttemptEngine.REASON_TOTAL_TIMEOUT ->
            context.getString(R.string.launch_fail_reason_total_timeout)
        LaunchAttemptEngine.REASON_KILLED ->
            context.getString(R.string.launch_fail_reason_interrupted)
        LaunchAttemptEngine.REASON_DISPATCH_REJECTED ->
            context.getString(R.string.launch_fail_reason_rejected)
        else -> reason
    }

    private fun ensureChannel(context: Context) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.launch_fail_channel_name),
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
    }
}
