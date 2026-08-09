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
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.workbuddy.quicklaunch.BuildConfig
import com.workbuddy.quicklaunch.MainActivity
import com.workbuddy.quicklaunch.R
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

    /** 屏幕常亮兜底锁：退后台悬浮窗被系统隐藏时，由前台服务持有，不受窗口可见性限制。 */
    private var wakeLock: PowerManager.WakeLock? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = scheduleSync()
        override fun onDisplayRemoved(displayId: Int) = scheduleSync()
        override fun onDisplayChanged(displayId: Int) = scheduleSync()
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = scheduleSync()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 注册成功才允许反注册，否则 unregisterReceiver 会抛 IllegalArgumentException。 */
    private var receiverRegistered = false
    private var displayListenerRegistered = false

    override fun onCreate() {
        super.onCreate()
        runCatching {
            (getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
                ?.registerDisplayListener(displayListener, handler)
            displayListenerRegistered = true
        }
        runCatching {
            // Android 14(UPSIDE_DOWN_CAKE) 起动态注册非系统广播必须显式声明导出属性，
            // 否则直接抛 SecurityException —— 原实现被 runCatching 吞掉，
            // 表现为「屏幕开关不再触发悬浮窗同步」这种无声故障。
            ContextCompat.registerReceiver(
                this,
                screenReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_USER_PRESENT)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }.onFailure { if (BuildConfig.DEBUG) Log.e("QL-AntiSleep", "屏幕广播注册失败", it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // buildNotify 里的 PendingIntent / 通知构造在极端 ROM 上也可能抛，
        // 这里崩了等于保活服务本身把进程带崩，必须兜住。
        runCatching { startForegroundCompat(buildNotify()) }
            .onFailure { if (BuildConfig.DEBUG) Log.e("QL-AntiSleep", "前台通知启动失败", it) }
        scheduleSync()
        return START_STICKY
    }

    override fun onDestroy() {
        // 清掉所有待执行回调，避免服务销毁后 Runnable 仍持有 Service 引用（内存泄漏）
        handler.removeCallbacksAndMessages(null)
        if (displayListenerRegistered) {
            runCatching {
                (getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
                    ?.unregisterDisplayListener(displayListener)
            }
            displayListenerRegistered = false
        }
        if (receiverRegistered) {
            runCatching { unregisterReceiver(screenReceiver) }
            receiverRegistered = false
        }
        releaseWakeLock()
        runCatching { ScreenOnOverlay.clear(this) }
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
        // 后台即防息屏：服务在跑 + 有悬浮窗权限 + 用户没手动关 → 自动挂常亮窗 + 持锁。
        // 不再依赖显式开关，开关仅作为「手动关闭」覆盖项。
        // 整体包 runCatching：这里跑在主线程，抛异常会直接崩掉整个进程，保活服务反而成了崩溃源。
        runCatching {
            val disabled = AntiSleep.isDisabled(this)
            val can = ScreenOnOverlay.canDraw(this)
            if (BuildConfig.DEBUG) Log.i("QL-AntiSleep", "syncTask 触发, 用户关闭=$disabled, 可悬浮窗=$can")
            if (can && !disabled) {
                ScreenOnOverlay.sync(this)
                acquireWakeLock()
            } else {
                ScreenOnOverlay.clear(this)
                releaseWakeLock()
            }
        }.onFailure { if (BuildConfig.DEBUG) Log.e("QL-AntiSleep", "同步失败", it) }
    }

    /**
     * WakeLock 续期任务。见 [acquireWakeLock] 的说明：
     * 用「带超时 + 定期续期」替代「无超时常驻」，功能等价但可自愈。
     */
    private val renewWakeLockTask = object : Runnable {
        override fun run() {
            runCatching {
                if (wakeLock?.isHeld == true) {
                    wakeLock?.acquire(WAKELOCK_TIMEOUT_MS)   // 非计数锁，重复 acquire 即刷新超时
                    handler.postDelayed(this, WAKELOCK_RENEW_MS)
                }
            }
        }
    }

    /**
     * 屏幕常亮兜底：前台态由悬浮窗 FLAG_KEEP_SCREEN_ON 直接压住外屏 powerGroup；
     * 退后台后悬浮窗可能被系统隐藏/移除（Motorola 对后台 App 隐藏外屏枚举），
     * 改用前台服务持有的屏幕 WakeLock，不受窗口可见性限制，外屏照样常亮。
     * 与悬浮窗并存互不冲突，前台时双保险，后台时靠此兜底。
     */
    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
                "QuickLaunch:antiSleep"
            ).apply {
                setReferenceCounted(false)
                // 原来是无超时 acquire()：进程被异常杀死（不走 onDestroy）时锁不会释放，
                // 屏幕会一直亮着直到重启，是最典型的电量/资源泄漏。
                // 改为带超时 + 定期续期：功能不变，但任何异常路径都会在 30 分钟内自愈。
                acquire(WAKELOCK_TIMEOUT_MS)
            }
            handler.removeCallbacks(renewWakeLockTask)
            handler.postDelayed(renewWakeLockTask, WAKELOCK_RENEW_MS)
            if (BuildConfig.DEBUG) Log.i("QL-AntiSleep", "WakeLock 已持有(屏幕常亮兜底, 自动续期)")
        }.onFailure { if (BuildConfig.DEBUG) Log.e("QL-AntiSleep", "WakeLock 获取失败", it) }
    }

    private fun releaseWakeLock() {
        handler.removeCallbacks(renewWakeLockTask)
        runCatching {
            wakeLock?.takeIf { it.isHeld }?.let {
                it.release()
                if (BuildConfig.DEBUG) Log.i("QL-AntiSleep", "WakeLock 已释放")
            }
        }
        wakeLock = null
    }

    private fun buildNotify(): Notification {
        ensureChannel()
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(applicationContext.getString(R.string.keepalive_notification_title))
            .setContentText(applicationContext.getString(R.string.keepalive_notification_text))
            .setSmallIcon(R.drawable.ic_notification_stat)   // 强制单色透明通知图标，彻底避开旧缓存
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
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            // 先删后建：渠道由系统持久化，换 ID 升版后旧渠道不会自动消失，
            // 会在「设置-通知」里留下同名僵尸开关。delete 幂等，渠道不存在时静默返回。
            LEGACY_CHANNELS.forEach { nm.deleteNotificationChannel(it) }
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "后台保活", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        const val NOTIF_ID = 1002
        private const val CHANNEL = "quicklaunch_keepalive_v2"

        /** 历史版本渠道 ID。图标升版换新 ID 时，旧 ID 必须登记在此以便清理。 */
        private val LEGACY_CHANNELS = listOf("quicklaunch_keepalive")

        /** WakeLock 超时（自愈上限）与续期间隔。续期间隔必须明显小于超时。 */
        private const val WAKELOCK_TIMEOUT_MS = 30 * 60 * 1000L
        private const val WAKELOCK_RENEW_MS = 10 * 60 * 1000L

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, KeepAliveService::class.java))
            }
        }
    }
}
