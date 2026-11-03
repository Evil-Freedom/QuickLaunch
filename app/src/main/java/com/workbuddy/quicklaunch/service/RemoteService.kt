package com.workbuddy.quicklaunch.service

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.workbuddy.quicklaunch.BuildConfig
import com.workbuddy.quicklaunch.R
import com.workbuddy.quicklaunch.RemoteAccessActivity
import com.workbuddy.quicklaunch.remote.RelayPoller
import com.workbuddy.quicklaunch.remote.RemoteBindings
import com.workbuddy.quicklaunch.remote.RemoteConfig
import com.workbuddy.quicklaunch.remote.RemoteServer
import java.net.Inet4Address
import java.net.InetAddress

/**
 * 远程访问服务的前台宿主。
 *
 * 为什么必须是前台服务：Android 不允许普通后台 Service 长期存活，
 * 而「远程随时能改」要求监听口一直在。通知里会写明当前监听地址，
 * 用户随时能看到「这个口是开着的」，而不是偷偷在跑。
 *
 * 与 [KeepAliveService] 完全解耦：远程服务崩掉不影响定时任务，
 * 反之亦然。两者都停掉也不会影响已经排进 AlarmManager 的闹钟。
 */
class RemoteService : Service() {

    private var server: RemoteServer? = null

    /** 中继轮询线程与其存活标志。用线程而不是协程：项目没有协程依赖，保持一致。 */
    private var relayThread: Thread? = null

    @Volatile
    private var relayRunning = false

    /** 落盘节流状态，见 [publishRelayState]。 */
    private var lastPersistAt = 0L
    private var lastPersistedNote = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!RemoteConfig.isEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground(getString(R.string.remote_notif_starting))
        ensureServer()
        // 备用通道与局域网通道共用一个服务：用户开「远程访问」就是想两种方式都能用，
        // 再拆一个服务只会多一份前台通知和一套自愈逻辑。
        ensureRelayLoop()
        isRunning = true
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        stopRelayLoop()
        runCatching { server?.stop() }
        server = null
        super.onDestroy()
    }

    /**
     * Android 16 起 `specialUse` 前台服务运行约 6 小时后系统回调此方法。
     * 不主动 `stopSelf` 会被系统强杀，且可能导致 ANR ——
     * 表现是「远程用着用着就没了」，用户只能重开 App。
     *
     * 这里主动停，同时排一个 60 秒后的自愈闹钟：用户不应感知到这次重启。
     */
    override fun onTimeout(startId: Int) {
        if (BuildConfig.DEBUG) Log.w(TAG, "FGS 超时，主动停止并安排重启")
        stopRelayLoop()
        runCatching { server?.stop() }
        server = null
        stopSelf(startId)
        scheduleSelfRestart(RESTART_DELAY_MS)
    }

    private fun ensureServer() {
        if (server != null) return

        val port = RemoteConfig.getPort(this)
        val candidate = RemoteServer(this, port, RemoteServer.deviceLabel(this))
        if (candidate.start()) {
            server = candidate
            RemoteConfig.setBoundPort(this, candidate.boundPort)
            updateNotification(getString(R.string.remote_notif_running, currentIp(this).orEmpty(), candidate.boundPort))
        } else {
            // 端口全被占用：关闭开关，避免 UI 显示「已开启」但实际没在监听
            RemoteConfig.setEnabled(this, false)
            updateNotification(getString(R.string.remote_notif_failed))
            if (BuildConfig.DEBUG) Log.e(TAG, "远程服务绑定端口失败 port=$port")
            stopSelf()
        }
    }

    // ── 公网中继轮询（备用通道）──────────────────────────────────────

    /**
     * 启动中继轮询线程。
     *
     * 放在前台服务里而不是 AlarmManager：前台服务不受 Doze 打盹影响，
     * 轮询间隔才有意义；AlarmManager 在熄屏后会被推迟到几分钟级，
     * 表现是「明明开着备用通道，指令却半天不到」。
     *
     * 循环每轮都重读一次配置，所以用户在界面上关掉开关后，
     * 最多一个间隔就会自动停下，不需要重启服务。
     */
    private fun ensureRelayLoop() {
        if (relayThread != null) return
        if (!RemoteConfig.relaySettings(this).enabled) return

        val poller = runCatching { RemoteBindings.relayPoller(this) }.getOrNull() ?: return
        relayRunning = true

        // 注意 finally 里必须把 relayThread 置空：否则「关掉备用通道 → 再打开」时
        // ensureRelayLoop 会因为 relayThread != null 直接 return，轮询再也不会恢复，
        // 而界面上开关是开着的 —— 这种「看起来开着但不工作」最难查。
        val thread = Thread({
            try {
                while (relayRunning) {
                    val settings = runCatching { RemoteConfig.relaySettings(this) }.getOrNull()
                    if (settings == null || !settings.enabled) break

                    val stats = runCatching { poller.pollOnce() }
                        .getOrElse { RelayPoller.Stats() }

                    publishRelayState(describeRelay(stats))

                    if (stats.handled > 0) {
                        updateNotification(
                            getString(
                                R.string.remote_notif_relay,
                                stats.executed,
                                settings.intervalSec
                            )
                        )
                    }
                    try {
                        Thread.sleep(settings.intervalSec * 1000L)
                    } catch (e: InterruptedException) {
                        break
                    }
                }
            } finally {
                // 只清理「自己那一份」引用：若期间已经起了新线程，不能被旧线程的收尾清掉，
                // 否则新线程的 while 条件会被误置 false 而无声退出。
                if (relayThread === Thread.currentThread()) {
                    relayRunning = false
                    relayThread = null
                }
            }
        }, "ql-relay")
        relayThread = thread
        thread.isDaemon = true
        thread.start()
    }

    private fun stopRelayLoop() {
        relayRunning = false
        relayThread?.interrupt()
        relayThread = null
    }

    private fun describeRelay(stats: RelayPoller.Stats): String = when {
        stats.pulled == 0 -> "已连接，暂无指令"
        stats.failed == 0 -> "收到 ${stats.pulled} 条，全部执行成功"
        else -> "收到 ${stats.pulled} 条，成功 ${stats.executed}，失败 ${stats.failed}"
    }

    /**
     * 更新内存态供界面读取，并**节流**落盘。
     *
     * 轮询间隔是 15 秒，若每次都写 SharedPreferences，一天要写 5000+ 次 ——
     * 对闪存寿命是实打实的消耗。所以只在「状态变了」或满 10 分钟时落盘，
     * 界面通过 [lastRelayNote] 读到的是内存里的最新值。
     */
    private fun publishRelayState(note: String) {
        val now = System.currentTimeMillis()
        lastRelayAt = now
        lastRelayNote = note
        if (note != lastPersistedNote || now - lastPersistAt > PERSIST_INTERVAL_MS) {
            runCatching { RemoteConfig.setRelayLastPoll(this, now, note) }
            lastPersistedNote = note
            lastPersistAt = now
        }
    }

    private fun startInForeground(text: String) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        runCatching {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(text), type)
        }.onFailure {
            if (BuildConfig.DEBUG) Log.e(TAG, "startForeground failed", it)
        }
    }

    private fun updateNotification(text: String) {
        runCatching {
            val nm = getSystemService(NotificationManager::class.java) ?: return@runCatching
            nm.notify(NOTIF_ID, buildNotification(text))
        }
    }

    private fun buildNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification_stat)
        .setContentTitle(getString(R.string.remote_notif_title))
        .setContentText(text)
        .setOngoing(true)
        .setSilent(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0,
                Intent(this, RemoteAccessActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        .build()

    private fun createChannel() {
        runCatching {
            val nm = getSystemService(NotificationManager::class.java) ?: return@runCatching
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return@runCatching
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.remote_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.remote_channel_desc)
                    setShowBadge(false)
                }
            )
        }
    }

    /**
     * 自愈：排一个闹钟让自己重新起来。
     *
     * 用 `setAndAllowWhileIdle` 而不是 exact 版本 —— 这里晚几十秒无所谓，
     * 但 exact 在没拿到精确闹钟权限时会抛 SecurityException。
     */
    private fun scheduleSelfRestart(delayMs: Long) {
        runCatching {
            val am = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return@runCatching
            val intent = Intent(this, RemoteService::class.java).setAction(ACTION_START)
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(this, RESTART_REQUEST_CODE, intent, flags)
            } else {
                PendingIntent.getService(this, RESTART_REQUEST_CODE, intent, flags)
            }
            am.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + delayMs,
                pi
            )
        }
    }

    companion object {
        private const val TAG = "QL-RemoteService"
        private const val CHANNEL_ID = "quicklaunch_remote"
        private const val NOTIF_ID = 0x514C_0002
        private const val RESTART_REQUEST_CODE = 0x514C10
        private const val RESTART_DELAY_MS = 60_000L

        /** 同一进程内界面读这个值即可，不必每次轮询都写一遍磁盘。 */
        private const val PERSIST_INTERVAL_MS = 600_000L

        /**
         * 中继最近一次轮询的时间与结论，供 `RemoteAccessActivity` 直接读取。
         * 服务与界面同进程，所以内存态足够；落盘只是为了「重启 App 后还能看到上次状态」。
         */
        /**
         * 服务当前是否真的活着（同进程内存态）。
         *
         * 界面上的「已启用」只是用户意愿，不代表端口有人在听：覆盖安装、被系统回收、
         * 前台服务超时都会让服务消失而开关仍是开着的。用这个标记让界面能说实话，
         * 也给 [com.workbuddy.quicklaunch.RemoteAccessActivity] 一个自愈的触发点。
         */
        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var lastRelayAt: Long = 0L
            private set

        @Volatile
        var lastRelayNote: String = ""
            private set

        const val ACTION_START = "com.workbuddy.quicklaunch.remote.START"
        const val ACTION_STOP = "com.workbuddy.quicklaunch.remote.STOP"

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, RemoteService::class.java).setAction(ACTION_START)
                )
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, RemoteService::class.java)) }
        }

        /**
         * 当前 WiFi 下的 IPv4 地址。
         *
         * 用 `LinkProperties` 而不是遍历 `NetworkInterface`：后者会把 VPN / Tailscale /
         * 蓝牙共享网段一起列出来，用户照着填大概率填错一个连不上的地址。
         */
        fun currentIp(context: Context): String? = runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return@runCatching null
            val network = cm.activeNetwork ?: return@runCatching null
            val props = cm.getLinkProperties(network) ?: return@runCatching null
            props.linkAddresses
                .map { it.address }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
                ?.hostAddress
        }.getOrNull()

        /** 供界面展示完整访问地址。 */
        fun addressHint(context: Context): String {
            val ip = currentIp(context) ?: return "未连接 WiFi"
            val port = RemoteConfig.getBoundPort(context)
                .takeIf { it > 0 } ?: RemoteConfig.getPort(context)
            return "http://$ip:$port"
        }
    }
}
