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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.workbuddy.quicklaunch.MainActivity

/**
 * 后台保活前台服务：长驻低优先级通知，降低被系统回收的概率，
 * 保障定时/条件触发的自动启动规则可靠执行。START_STICKY 被杀后由系统重启。
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat(buildNotify())
        return START_STICKY
    }

    private fun buildNotify(): Notification {
        ensureChannel()
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("QuickLaunch 后台保活中")
            .setContentText("保障自动启动规则不被系统回收")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun startForegroundCompat(n: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else 0
        runCatching { ServiceCompat.startForeground(this, NOTIF_ID, n, type) }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "后台保活", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        const val NOTIF_ID = 1002
        private const val CHANNEL = "quicklaunch_keepalive"

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, KeepAliveService::class.java))
            }
        }
    }
}
