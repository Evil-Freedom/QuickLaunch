package com.workbuddy.quicklaunch.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.workbuddy.quicklaunch.MainActivity
import com.workbuddy.quicklaunch.util.AntiSleep
import com.workbuddy.quicklaunch.util.ScreenOnOverlay

/**
 * 后台保活前台服务：长驻低优先级通知，降低被系统回收的概率，
 * 保障定时/条件触发的自动启动规则可靠执行。START_STICKY 被杀后由系统重启。
 *
 * 同时承载防息屏悬浮窗的生命周期：悬浮窗必须挂在长生命周期组件上，
 * 挂在 Activity 上一退出就没了。屏幕开关 / 折叠展开会换屏，所以监听
 * DisplayListener 与亮灭屏广播，事件到了就重新同步一次。
 */
class KeepAliveService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = scheduleSync()
        override fun onDisplayRemoved(displayId: Int) = scheduleSync()
        override fun onDisplayChanged(displayId: Int) = scheduleSync()
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = scheduleSync()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        runCatching {
            (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                .registerDisplayListener(displayListener, handler)
        }
        runCatching {
            registerReceiver(screenReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            })
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat(buildNotify())
        scheduleSync()
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching {
            (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                .unregisterDisplayListener(displayListener)
        }
        runCatching { unregisterReceiver(screenReceiver) }
        ScreenOnOverlay.clear(this)
        super.onDestroy()
    }

    /**
     * 屏幕状态变化事件早于 Display.state 更新，立刻读会拿到旧值，延迟 300ms 再同步。
     * 多次事件合并成一次，避免折叠展开瞬间抖动导致反复增删窗口。
     */
    private fun scheduleSync() {
        handler.removeCallbacks(syncTask)
        handler.postDelayed(syncTask, 300)
    }

    private val syncTask = Runnable {
        // 后台即防息屏：服务在跑 + 有悬浮窗权限 + 用户没手动关 → 自动挂常亮窗。
        // 不再依赖显式开关，开关仅作为「手动关闭」覆盖项。
        val disabled = AntiSleep.isDisabled(this)
        val can = ScreenOnOverlay.canDraw(this)
        Log.i("QL-AntiSleep", "syncTask 触发, 用户关闭=$disabled, 可悬浮窗=$can")
        if (can && !disabled) ScreenOnOverlay.sync(this) else ScreenOnOverlay.clear(this)
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
