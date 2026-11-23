package com.workbuddy.quicklaunch.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.workbuddy.quicklaunch.BuildConfig
import com.workbuddy.quicklaunch.LaunchProxyActivity
import com.workbuddy.quicklaunch.R
import com.workbuddy.quicklaunch.launch.LaunchAttemptController
import com.workbuddy.quicklaunch.util.DisplayPicker

/**
 * 前台服务：保证触发瞬间进程存活，并把启动动作交给 LaunchProxyActivity。
 *
 * 真正的拉起工作全在中转页里做（选屏 / 点亮屏幕 / 绕过锁屏），这里只负责两条通路：
 * - 有悬浮窗权限时直接启动中转页，最快
 * - 没有时靠通知的 fullScreenIntent 让系统去拉，属于官方豁免途径
 */
class LaunchService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Android 16 FGS 超时回调。LaunchService 是短时 FGS，正常流程在 stopSelf() 后就结束了，
     * 此回调仅作兜底防护——若系统提前触发超时，主动 stopSelf() 避免 ANR。
     */
    override fun onTimeout(startId: Int) {
        if (BuildConfig.DEBUG) Log.w("QL-Notifier", "FGS 超时，主动停止: startId=$startId")
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pkg = intent?.getStringExtra(EXTRA_PKG).orEmpty()
        val appName = intent?.getStringExtra(EXTRA_APP_NAME).orEmpty()
        val attemptId = intent?.getStringExtra(EXTRA_ATTEMPT_ID)
        if (pkg.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        // 通知构建失败不应让服务崩掉——真正的启动动作在下面，通知只是保活壳
        runCatching { startForegroundCompat(Notifier.build(this, pkg, appName, ongoing = true)) }

        val launched = runCatching { Settings.canDrawOverlays(this) }.getOrDefault(false) &&
            runCatching { startProxy(pkg, attemptId) }.isSuccess

        // 没有悬浮窗权限时 startActivity 会被系统静默丢弃，统一补一条全屏通知兜底
        if (!launched) {
            Notifier.fallback(this, pkg, appName)
            // 中转页根本没起来 → 不会有它的回调。这里补一条「未派发」，
            // 让验证链路按「命令没发出去」继续走完自己的重试/告警，而不是永远停在 PENDING。
            LaunchAttemptController.onProxyLaunched(this, attemptId, accepted = false)
        }

        stopSelf()
        return START_NOT_STICKY
    }

    /**
     * 启动中转页，并把它**投到目标屏幕**上。
     *
     * ## 为什么要把中转页也投过去
     *
     * 合盖实测：从 display 0（内屏）内的 Activity 用 `setLaunchDisplayId(1)`
     * 启动目标，系统会**静默忽略**这个 display 选项（目标仍落在内屏，
     * 而内屏已物理关闭）。但把中转页自己投到外屏后，从它启动的目标
     * 天然继承同一 display —— 这是 Android 多屏的既定语义，
     * 比「跨屏指定启动」可靠得多（后者受发起方所在 display 限制）。
     *
     * 同时保留中转页内部的 options：投屏成功时它是冗余的双保险；
     * 投屏被忽略时它仍可能生效。
     */
    private fun startProxy(pkg: String, attemptId: String?): Boolean {
        // 不指定 display：中转页会落在触发瞬间活跃的那块屏上，
        // 随后它启动的目标继承同一 display。见 LaunchProxyActivity 里的说明。
        return runCatching { startActivity(LaunchProxyActivity.intent(this, pkg, attemptId)) }
            .isSuccess
    }

    private fun startForegroundCompat(n: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else 0
        runCatching { ServiceCompat.startForeground(this, Notifier.NOTIF_ID, n, type) }
    }

    companion object {
        private const val EXTRA_PKG = "pkg"
        private const val EXTRA_APP_NAME = "app_name"
        private const val EXTRA_ATTEMPT_ID = "attempt_id"

        /**
         * @param attemptId 验证任务的 id。为 null 时表示「不参与启动验证」
         *        （用户点通知手动打开等旧路径），行为与升级前完全一致。
         */
        fun start(context: Context, pkg: String, appName: String, attemptId: String? = null) {
            val intent = Intent(context, LaunchService::class.java).apply {
                putExtra(EXTRA_PKG, pkg)
                putExtra(EXTRA_APP_NAME, appName)
                if (attemptId != null) putExtra(EXTRA_ATTEMPT_ID, attemptId)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (_: Exception) {
                // Android 12+ 从后台启动前台服务会抛 ForegroundServiceStartNotAllowedException
                Notifier.fallback(context, pkg, appName)
                LaunchAttemptController.onProxyLaunched(context, attemptId, accepted = false)
            }
        }
    }
}

/** 通知构建与兜底启动。前台服务和各异常分支共用同一套，避免多处重复。 */
object Notifier {
    const val NOTIF_ID = 1001
    private const val CHANNEL_ID = "quicklaunch_launch_v2"

    /** 历史版本渠道 ID。图标升版换新 ID 时，旧 ID 必须登记在此以便清理。 */
    private val LEGACY_CHANNELS = listOf("quicklaunch_launch")

    fun build(context: Context, pkg: String, appName: String, ongoing: Boolean): Notification {
        ensureChannel(context)
        // 指向中转页而非目标 App，保证点通知启动时同样走选屏与点亮屏幕的逻辑
        val pi = PendingIntent.getActivity(
            context, pkg.hashCode(),
            LaunchProxyActivity.intent(context, pkg),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.launch_notification_title, appName))
            .setContentText("正在启动 $appName · 若未自动打开，点此立即启动")
            .setSmallIcon(R.drawable.ic_notification_stat)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)   // 系统级豁免：允许直接拉起 Activity
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .build()
    }

    /**
     * 兜底通知。Android 13+ 未授予 POST_NOTIFICATIONS 时 notify 是空操作，
     * 先显式判权限并留日志，避免「什么都没发生」这种无从排查的静默失败。
     */
    @SuppressLint("MissingPermission")
    fun fallback(context: Context, pkg: String, appName: String) {
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!allowed) {
            if (BuildConfig.DEBUG) Log.w(TAG, "无通知权限，兜底通知无法送达：$pkg")
            return
        }
        runCatching {
            NotificationManagerCompat.from(context)
                .notify(pkg.hashCode(), build(context, pkg, appName, ongoing = false))
        }.onFailure { if (BuildConfig.DEBUG) Log.e(TAG, "兜底通知发送失败：$pkg", it) }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        // 硬转型在部分 ROM（服务不可用）会抛 ClassCastException/NPE，改安全转型
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            // 先删后建：渠道由系统持久化，换 ID 升版后旧渠道不会自动消失，
            // 会在「设置-通知」里留下同名僵尸开关。delete 幂等，渠道不存在时静默返回。
            LEGACY_CHANNELS.forEach { nm.deleteNotificationChannel(it) }
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "自动启动", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    private const val TAG = "QL-Notifier"
}
