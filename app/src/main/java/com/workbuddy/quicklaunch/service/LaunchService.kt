package com.workbuddy.quicklaunch.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.app.ServiceCompat
import android.app.Service
import android.os.IBinder

/**
 * 前台服务：真正去拉起目标 App。
 *
 * Android 10+ 限制后台直接 startActivity，而「前台服务」属于允许从后台启动 Activity 的豁免场景之一；
 * 同时我们也在通知里挂上点击启动的 PendingIntent，作为兜底（万一被系统拦截，用户点通知也能打开）。
 */
class LaunchService : Service() {

    private var pkg: String = ""
    private var appName: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        pkg = intent?.getStringExtra(EXTRA_PKG) ?: ""
        appName = intent?.getStringExtra(EXTRA_APP_NAME) ?: ""

        if (pkg.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+：前台服务必须声明类型，这里用 specialUse（已在 Manifest 中配合 property 声明）
            ServiceCompat.startForeground(
                this, NOTIF_ID, buildNotification(),
                ServiceCompat.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            // Android 13 及以下：直接以 2 参数方式启动前台服务即可
            startForeground(NOTIF_ID, buildNotification())
        }

        try {
            launchApp()
        } catch (_: Exception) {
            // 直接拉起失败也没关系，通知里的 PendingIntent 仍可点击启动
        }

        stopSelf()
        return START_NOT_STICKY
    }

    private fun launchApp() {
        val launch = packageManager.getLaunchIntentForPackage(pkg)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)
        }
    }

    private fun buildNotification(): Notification {
        val channelId = "quicklaunch_launch"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "启动通知", NotificationManager.IMPORTANCE_HIGH)
            )
        }

        val launch = packageManager.getLaunchIntentForPackage(pkg)
        val contentIntent = if (launch != null) {
            PendingIntent.getActivity(
                this,
                0,
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE
            )
        } else null

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("正在启动 $appName")
            .setContentText("点击可直接打开")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
    }

    companion object {
        const val NOTIF_ID = 1001
        private const val EXTRA_PKG = "pkg"
        private const val EXTRA_APP_NAME = "app_name"

        fun start(context: Context, pkg: String, appName: String) {
            val intent = Intent(context, LaunchService::class.java).apply {
                putExtra(EXTRA_PKG, pkg)
                putExtra(EXTRA_APP_NAME, appName)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
