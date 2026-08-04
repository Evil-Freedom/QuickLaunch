package com.workbuddy.quicklaunch.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * 前台服务：真正去拉起目标 App。
 *
 * Android 10+ 起后台不能随意 startActivity，可靠的豁免途径是「悬浮窗权限」(SYSTEM_ALERT_WINDOW)。
 * 已授权则直接拉起；未授权或被厂商 ROM 拦截时，退回全屏通知让用户一点即开。
 */
class LaunchService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pkg = intent?.getStringExtra(EXTRA_PKG).orEmpty()
        val appName = intent?.getStringExtra(EXTRA_APP_NAME).orEmpty()
        if (pkg.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat(Notifier.build(this, pkg, appName, ongoing = true))

        val launched = runCatching {
            packageManager.getLaunchIntentForPackage(pkg)?.let {
                startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } ?: false
        }.getOrDefault(false)

        // 没有悬浮窗权限时 startActivity 常被系统静默丢弃，统一补一条可点击的通知兜底
        if (!launched || !Settings.canDrawOverlays(this)) {
            Notifier.fallback(this, pkg, appName)
        }

        stopSelf()
        return START_NOT_STICKY
    }

    private fun startForegroundCompat(n: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else 0
        ServiceCompat.startForeground(this, Notifier.NOTIF_ID, n, type)
    }

    companion object {
        private const val EXTRA_PKG = "pkg"
        private const val EXTRA_APP_NAME = "app_name"

        fun start(context: Context, pkg: String, appName: String) {
            val intent = Intent(context, LaunchService::class.java).apply {
                putExtra(EXTRA_PKG, pkg)
                putExtra(EXTRA_APP_NAME, appName)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (_: Exception) {
                // Android 12+ 从后台启动前台服务会抛 ForegroundServiceStartNotAllowedException
                Notifier.fallback(context, pkg, appName)
            }
        }
    }
}

/** 通知构建与兜底启动，前台服务和异常分支共用同一套，避免两处重复。 */
object Notifier {
    const val NOTIF_ID = 1001
    private const val CHANNEL_ID = "quicklaunch_launch"

    fun build(context: Context, pkg: String, appName: String, ongoing: Boolean): Notification {
        ensureChannel(context)
        val launch = context.packageManager.getLaunchIntentForPackage(pkg)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = launch?.let {
            PendingIntent.getActivity(context, pkg.hashCode(), it, PendingIntent.FLAG_IMMUTABLE)
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("正在启动 $appName")
            .setContentText("若未自动打开，点此立即启动")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)   // 锁屏/后台时争取直接弹出
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .build()
    }

    fun fallback(context: Context, pkg: String, appName: String) {
        runCatching {
            NotificationManagerCompat.from(context)
                .notify(pkg.hashCode(), build(context, pkg, appName, ongoing = false))
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "自动启动", NotificationManager.IMPORTANCE_HIGH)
        )
    }
}
