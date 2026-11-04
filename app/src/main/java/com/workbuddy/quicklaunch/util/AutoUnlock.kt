package com.workbuddy.quicklaunch.util

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import androidx.core.app.NotificationCompat
import com.workbuddy.quicklaunch.BuildConfig
import com.workbuddy.quicklaunch.MainActivity
import com.workbuddy.quicklaunch.R
import java.util.concurrent.atomic.AtomicBoolean

/** 判定结果枚举复用纯逻辑模块的实现，避免两处各写一份。 */
typealias AutoUnlockAction = AutoUnlockDecision.Action

/**
 * 锁屏自动解锁。
 *
 * 场景：这台机器是常开设备，每次手动输密码解锁很烦。开启本功能后，
 * 只要「屏幕亮着 + 确实处于锁屏（deviceLocked）」就用 root 注入密码自动解锁。
 *
 * 实现路径（真机验证过，见 `memory/2026-11-04.md`）：
 * `su -c 'input -d <id> swipe …; input text <pin>; input keyevent 66'`
 * —— 必须**先上滑**把 bouncer（密码键盘）叫出来，直接 `input text` 打到的是空处
 * （实测 MENU 键 82 在这台机器上不会拉起 bouncer，只有上滑有效）。
 *
 * 安全边界（写死，不要放宽）：
 * - 失败即计数，连续 [MAX_FAILURES] 次失败立刻**关闭功能并通知**，避免把
 *   「错误密码」反复重试到系统的 5 次上限而触发数据清除。
 * - 密码用 [PinVault] 保管（KeyStore AES-GCM，退化为混淆）。
 * - 只在「屏幕已经亮着」时动作，绝不在黑屏时注入输入事件。
 */
object AutoUnlock {

    private const val TAG = "QL-AutoUnlock"

    private const val SP_NAME = "quicklaunch"
    private const val KEY_ENABLED = "auto_unlock_enabled"
    private const val KEY_CHARGING_ONLY = "auto_unlock_charging_only"
    private const val KEY_PIN = "auto_unlock_pin_v1"
    private const val KEY_FAILURES = "auto_unlock_failures"

    /** 两次尝试之间的最小间隔，给 keyguard 动画留时间。 */
    const val MIN_INTERVAL_MS = AutoUnlockDecision.MIN_INTERVAL_MS

    /** 连续失败上限。达到即关闭功能，避免撞上系统 5 次清除数据的红线。 */
    const val MAX_FAILURES = AutoUnlockDecision.MAX_FAILURES

    /** 一次「锁屏事件」内的观察窗口：展开/亮屏后 keyguard 出现可能有延迟。 */
    private const val WATCH_WINDOW_MS = 12_000L
    private const val TICK_MS = 600L

    private const val CHANNEL = "quicklaunch_alert_v1"
    private const val NOTIF_ID = 1003


    private fun sp(ctx: Context) = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)

    // ── 配置读写 ──────────────────────────────────────────────────

    fun isEnabled(ctx: Context): Boolean = runCatching { sp(ctx).getBoolean(KEY_ENABLED, false) }.getOrDefault(false)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        sp(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (!enabled) stopWatch()
    }

    fun isChargingOnly(ctx: Context): Boolean =
        runCatching { sp(ctx).getBoolean(KEY_CHARGING_ONLY, false) }.getOrDefault(false)

    fun setChargingOnly(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean(KEY_CHARGING_ONLY, v).apply()
    }

    fun hasPin(ctx: Context): Boolean = pin(ctx) != null

    fun pin(ctx: Context): String? = PinVault.open(ctx, sp(ctx).getString(KEY_PIN, null))

    /**
     * 保存锁屏密码。只接受 4~16 位数字 —— 数字键盘（PIN）才能被 `input text` 注入，
     * 字母/符号密码走的是 password 键盘，注入方式不同且不稳定，这里明确不支持。
     * 保存成功会清零失败计数（用户重新确认过密码，旧的失败记录不再可信）。
     */
    fun setPin(ctx: Context, pin: String?): Boolean {
        if (pin.isNullOrEmpty()) {
            sp(ctx).edit().remove(KEY_PIN).apply()
            return true
        }
        if (!AutoUnlockDecision.validPin(pin)) return false
        val sealed = PinVault.seal(ctx, pin) ?: return false
        sp(ctx).edit().putString(KEY_PIN, sealed).putInt(KEY_FAILURES, 0).apply()
        return true
    }

    fun failures(ctx: Context): Int = runCatching { sp(ctx).getInt(KEY_FAILURES, 0) }.getOrDefault(0)

    private fun resetFailures(ctx: Context) = sp(ctx).edit().putInt(KEY_FAILURES, 0).apply()

    // ── 纯决策（可单测） ──────────────────────────────────────────

    /**
     * 是否该发起一次解锁尝试。所有输入都是显式参数，方便单测把边界钉死。
     *
     * 注意顺序：**先判 GIVE_UP 再判 SKIP** —— 否则一旦屏灭了，
     * 达到上限的状态会一直停留在 SKIP，用户永远收不到「已暂停」的提示。
     */
    fun decide(
        enabled: Boolean,
        hasPin: Boolean,
        deviceLocked: Boolean,
        displayLit: Boolean,
        chargingOnly: Boolean,
        charging: Boolean,
        failures: Int,
        msSinceLastAttempt: Long
    ): AutoUnlockAction = AutoUnlockDecision.decide(
        enabled, hasPin, deviceLocked, displayLit, chargingOnly, charging, failures, msSinceLastAttempt
    )

    // ── 运行时 ────────────────────────────────────────────────────

    private val handler = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)

    @Volatile private var appCtx: Context? = null
    @Volatile private var watching = false
    @Volatile private var watchEnd = 0L
    @Volatile private var lastAttemptAt = 0L

    /**
     * 由保活服务在「亮屏 / 用户呈现 / 换屏（展开折叠）」时调用。
     * 内部是「窗口内轮询」而不是单次判断：keyguard 出现晚于屏幕点亮是常态，
     * 单次判断会漏掉绝大多数场景。
     */
    fun watch(ctx: Context) {
        val app = ctx.applicationContext
        if (!isEnabled(app) || !hasPin(app)) return
        appCtx = app
        watchEnd = SystemClock.elapsedRealtime() + WATCH_WINDOW_MS
        if (watching) return
        watching = true
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, TICK_MS)
    }

    /** 关闭功能或服务销毁时调用，避免 Runnable 持有 Context。 */
    fun stopWatch() {
        watching = false
        handler.removeCallbacks(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            val app = appCtx
            if (app == null || !watching) return
            if (SystemClock.elapsedRealtime() > watchEnd) return
            if (!isEnabled(app)) return stopWatch()

            val locked = deviceLocked(app)
            val action = decide(
                enabled = true,
                hasPin = hasPin(app),
                deviceLocked = locked,
                displayLit = litDisplay(app) != null,
                chargingOnly = isChargingOnly(app),
                charging = isCharging(app),
                failures = failures(app),
                msSinceLastAttempt = if (lastAttemptAt == 0L) Long.MAX_VALUE
                else SystemClock.elapsedRealtime() - lastAttemptAt
            )
            when (action) {
                AutoUnlockAction.GIVE_UP -> giveUp(app)
                AutoUnlockAction.ATTEMPT -> attempt(app)
                AutoUnlockAction.SKIP -> Unit
            }
            // 已经解锁（或本来就没锁）就没必要继续轮询
            if (watching && !locked) stopWatch()
            if (watching) handler.postDelayed(this, TICK_MS)
        }
    }

    private fun deviceLocked(ctx: Context): Boolean = runCatching {
        (ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isDeviceLocked == true
    }.getOrDefault(false)

    private fun isCharging(ctx: Context): Boolean = runCatching {
        (ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)?.isCharging == true
    }.getOrDefault(false)

    /** 当前亮着的屏（displayId + 尺寸）。外屏在合盖态可能不被 getDisplays 枚举，需补查 1。 */
    private fun litDisplay(ctx: Context): Display? = runCatching {
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            ?: return@runCatching null
        val candidates = buildList {
            addAll(dm.displays)
            dm.getDisplay(1)?.let { ext -> if (none { d -> d.displayId == ext.displayId }) add(ext) }
        }
        candidates.filter { it.state == Display.STATE_ON }.maxByOrNull { it.displayId }
    }.getOrNull()

    private fun attempt(ctx: Context) {
        if (!busy.compareAndSet(false, true)) return
        lastAttemptAt = SystemClock.elapsedRealtime()
        val pin = pin(ctx)
        val display = litDisplay(ctx)
        if (pin == null || display == null) {
            busy.set(false)
            return
        }
        QuickLaunchExecutors.io.execute {
            var unlocked = false
            try {
                unlocked = injectAndVerify(ctx, display, pin)
            } catch (t: Throwable) {
                if (BuildConfig.DEBUG) Log.e(TAG, "自动解锁异常", t)
            } finally {
                busy.set(false)
                if (unlocked) {
                    resetFailures(ctx)
                    stopWatch()
                    if (BuildConfig.DEBUG) Log.i(TAG, "自动解锁成功")
                } else {
                    val n = failures(ctx) + 1
                    sp(ctx).edit().putInt(KEY_FAILURES, n).apply()
                    if (BuildConfig.DEBUG) Log.w(TAG, "自动解锁失败，累计 $n/$MAX_FAILURES")
                    if (n >= MAX_FAILURES) handler.post { giveUp(ctx) }
                }
            }
        }
    }

    /**
     * 注入密码并回读真实结果（**看 KeyguardManager，不看命令退出码**）。
     * input 命令成功返回只代表事件发出去了，不代表密码对。
     */
    @Suppress("DEPRECATION")
    private fun injectAndVerify(ctx: Context, display: Display, pin: String): Boolean {
        val p = Point()
        runCatching { display.getRealSize(p) }
        val w = if (p.x > 0) p.x else display.mode.physicalWidth
        val h = if (p.y > 0) p.y else display.mode.physicalHeight
        val id = display.displayId
        val swipe = AutoUnlockDecision.swipeFromSize(w, h)
        val x = swipe[0]
        val y1 = swipe[1]
        val y2 = swipe[3]

        // 一条 su 里跑完：每次 fork `input` 都要起一个 app_process，分开调用会慢一倍以上
        val cmd = "input -d $id swipe $x $y1 $x $y2 250; " +
            "sleep 0.45; input -d $id text $pin; sleep 0.25; input keyevent 66"
        RootUtils.runAsRoot(cmd)

        // keyguard 消失是异步的，给 1.4s；期间每 200ms 读一次真实状态
        repeat(7) {
            SystemClock.sleep(200)
            if (!deviceLocked(ctx)) return true
        }
        return false
    }

    /** 连续失败到达上限：关闭功能 + 通知，绝不继续重试。 */
    private fun giveUp(ctx: Context) {
        stopWatch()
        setEnabled(ctx, false)
        notifyGiveUp(ctx)
    }

    private fun notifyGiveUp(ctx: Context) {
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "自动解锁提醒", NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
            val pi = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setContentTitle(ctx.getString(R.string.auto_unlock_give_up_title))
                .setContentText(ctx.getString(R.string.auto_unlock_give_up_text))
                .setSmallIcon(R.drawable.ic_notification_stat)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            nm.notify(NOTIF_ID, n)
        }
    }
}
