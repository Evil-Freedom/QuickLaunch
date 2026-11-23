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
import android.os.PowerManager
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

    // ── v1.4.1 新增：状态记录与安全停用标记 ──
    /** 上次尝试的绝对时间。0 = 从未尝试。 */
    private const val KEY_LAST_ATTEMPT_AT = "auto_unlock_last_attempt_at"
    /** 上次尝试结果。取值见 [RESULT_*]。 */
    private const val KEY_LAST_RESULT = "auto_unlock_last_result"
    /** 因失败保护自动停用的标记。与「用户手动关」区分：前者远程不能重新打开。 */
    private const val KEY_AUTO_DISABLED = "auto_unlock_auto_disabled"
    private const val KEY_AUTO_DISABLED_REASON = "auto_unlock_auto_disabled_reason"
    /** 每次解锁失败时是否发通知。安全停用通知不受此开关控制（始终发）。 */
    private const val KEY_NOTIFY = "auto_unlock_notify_on_failure"

    /** 两次尝试之间的最小间隔，给 keyguard 动画留时间。 */
    const val MIN_INTERVAL_MS = AutoUnlockDecision.MIN_INTERVAL_MS

    /** 连续失败上限。达到即关闭功能，避免撞上系统 5 次清除数据的红线。 */
    const val MAX_FAILURES = AutoUnlockDecision.MAX_FAILURES

    /** 上次尝试结果取值。 */
    const val RESULT_SUCCESS = "SUCCESS"
    const val RESULT_FAILED = "FAILED"

    /** 安全停用原因。 */
    const val DISABLED_BY_FAILURES = "MAX_FAILURES_REACHED"

    /** 一次「锁屏事件」内的观察窗口：展开/亮屏后 keyguard 出现可能有延迟。 */
    private const val WATCH_WINDOW_MS = 12_000L
    private const val TICK_MS = 600L

    /** bouncer 巡检的超时。dumpsys 一次约 300ms，给足余量但不允许拖住观察窗口。 */
    private const val BOUNCER_PROBE_TIMEOUT_MS = 3_000L

    /** 触摸设备枚举的超时。getevent -pl 要遍历 /dev/input，慢一些。 */
    private const val GETEVENT_TIMEOUT_MS = 4_000L

    private const val CHANNEL = "quicklaunch_alert_v1"
    private const val NOTIF_ID = 1003


    private fun sp(ctx: Context) = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)

    // ── 配置读写 ──────────────────────────────────────────────────

    fun isEnabled(ctx: Context): Boolean = runCatching { sp(ctx).getBoolean(KEY_ENABLED, false) }.getOrDefault(false)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        sp(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) {
            // 本地显式开启 = 用户在手机上确认过。
            // 只有这条路径能清除安全停用标记；远程下发的启用走
            // [applyRemotePolicy]，它会被 [isSecurityLocked] 拦住。
            sp(ctx).edit()
                .putBoolean(KEY_AUTO_DISABLED, false)
                .putString(KEY_AUTO_DISABLED_REASON, "")
                .apply()
        } else {
            stopWatch()
        }
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

    // ── 状态记录（供界面与远程看板展示，不含任何 PIN 内容） ──

    fun lastAttemptAt(ctx: Context): Long =
        runCatching { sp(ctx).getLong(KEY_LAST_ATTEMPT_AT, 0L) }.getOrDefault(0L)

    fun lastResult(ctx: Context): String =
        runCatching { sp(ctx).getString(KEY_LAST_RESULT, "") }.getOrNull().orEmpty()

    /**
     * 是否处于「因失败保护而停用」状态。
     *
     * 两种情况：
     * 1. [KEY_AUTO_DISABLED] 标记（giveUp 写入）
     * 2. failures 已达上限但标记丢失（异常路径兜底）
     * 任一成立都视为安全停用，远程不能重新启用。
     */
    fun isSecurityLocked(ctx: Context): Boolean =
        runCatching { sp(ctx).getBoolean(KEY_AUTO_DISABLED, false) }.getOrDefault(false) ||
            failures(ctx) >= MAX_FAILURES

    fun autoDisabledReason(ctx: Context): String =
        runCatching { sp(ctx).getString(KEY_AUTO_DISABLED_REASON, "") }.getOrNull().orEmpty()

    fun notifyOnFailure(ctx: Context): Boolean =
        runCatching { sp(ctx).getBoolean(KEY_NOTIFY, true) }.getOrDefault(true)

    fun setNotifyOnFailure(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean(KEY_NOTIFY, v).apply()

    /**
     * PIN 的存储形态（KEYSTORE / OBFUSCATED / NONE）。
     * 读的是已保存的 blob 前缀，不触发解密。
     */
    fun pinStorageKind(ctx: Context): PinVault.StorageKind =
        PinVault.storageKindOf(runCatching { sp(ctx).getString(KEY_PIN, null) }.getOrNull())

    /**
     * 把混淆存储的 PIN 就地升级成 AndroidKeyStore 加密。
     *
     * 触发场景只有一种：**已保存的 PIN 是 x1 混淆态，而当前 KeyStore 可用**。
     * 这既包括「保存时 KeyStore 恰好坏了、之后恢复」的机器，也包括由
     * `tools/seed_pin.py` 在装机后注入的 PIN（脚本拿不到 App 进程内的密钥）。
     *
     * 只重写密码本身，不动开关、失败计数与停用标记 —— 升级不该有任何策略副作用。
     */
    fun upgradePinStorageIfPossible(ctx: Context) {
        val blob = runCatching { sp(ctx).getString(KEY_PIN, null) }.getOrNull()
        if (PinVault.storageKindOf(blob) != PinVault.StorageKind.OBFUSCATED) return
        if (!PinVault.isKeystoreAvailable()) return
        val plain = PinVault.open(ctx, blob) ?: return
        PinVault.seal(ctx, plain)?.let { sp(ctx).edit().putString(KEY_PIN, it).apply() }
    }

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
        val enabled = isEnabled(app)
        val hasPin = hasPin(app)
        if (!enabled || !hasPin) {
            // 这两项是「为什么没解锁」的第一现场。只记布尔值，不含密码内容。
            // 真机排查时没有这条日志，就只能看到「什么都没发生」。
            Log.i(TAG, "watch 不启动 enabled=$enabled hasPin=$hasPin")
            return
        }
        appCtx = app
        watchEnd = SystemClock.elapsedRealtime() + WATCH_WINDOW_MS
        Log.i(TAG, "watch 启动 窗口=${WATCH_WINDOW_MS}ms 亮屏=${litDisplay(app)?.displayId ?: -1}")
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
            if (SystemClock.elapsedRealtime() > watchEnd) {
                // 窗口结束必须把 watching 复位：否则后续 watch() 会被
                // 「if (watching) return」挡掉，观察器就此静默死亡（表现为功能时好时坏）
                watching = false
                return
            }
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
                AutoUnlockAction.ATTEMPT -> {
                    Log.i(TAG, "判定 ATTEMPT locked=$locked 屏=${litDisplay(app)?.displayId} 失败=${failures(app)}")
                    attempt(app)
                }
                AutoUnlockAction.SKIP -> {
                    val lit = litDisplay(app)
                    // 只在「有屏亮着但没动」时记一行：黑屏轮询期间刷屏没意义。
                    if (lit != null && locked) {
                        Log.i(
                            TAG,
                            "判定 SKIP 有屏亮且锁屏但未尝试：chargingOnly=${isChargingOnly(app)} " +
                                "charging=${isCharging(app)} failures=${failures(app)}"
                        )
                    }
                }
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
        val inner = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val outer = dm.getDisplay(com.workbuddy.quicklaunch.launch.EXTERNAL_DISPLAY_ID)
        val candidates = buildList {
            addAll(dm.displays)
            outer?.let { ext -> if (none { d -> d.displayId == ext.displayId }) add(ext) }
        }
        candidates.filter { it.state == Display.STATE_ON }.maxByOrNull { it.displayId }
            ?: fallbackLitDisplay(ctx, inner, outer)
    }.getOrNull()

    /**
     * `Display.state` 读不到 ON 时的兜底。
     *
     * 真机实测：锁屏亮屏时 `Display.state` 不一定报 ON（读出来是空集合）。
     * 这时按物理形态判断：**内屏明确 OFF = 合盖**，用外屏；否则用内屏。
     *
     * 判错的代价是可控的：bouncer 不会出现在选错的那块屏上，上层走
     * [UnlockOutcome.NOT_READY]，不计入失败额度。
     */
    private fun fallbackLitDisplay(ctx: Context, inner: Display?, outer: Display?): Display? {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm?.isInteractive != true) return null
        return if (inner != null && inner.state == Display.STATE_OFF && outer != null && outer.isValid) {
            outer
        } else {
            inner ?: outer?.takeIf { it.isValid }
        }
    }

    /**
     * 一次注入的结果。
     *
     * 必须把 [NOT_READY] 与 [WRONG_PIN] 分开：前者是「环境没准备好」（bouncer 没弹出、
     * 找不到触摸设备），后者才是「密码不对」。混在一起的结果是 —— 一次环境抖动就
     * 消耗掉用户一半的重试额度，两次抖动直接把功能停用。
     */
    private enum class UnlockOutcome { UNLOCKED, WRONG_PIN, NOT_READY }

    private fun attempt(ctx: Context) {
        if (!busy.compareAndSet(false, true)) return
        lastAttemptAt = SystemClock.elapsedRealtime()
        val pin = pin(ctx)
        val display = litDisplay(ctx)
        if (pin == null || display == null) {
            // 解密失败（KeyStore key 失效）与「没有亮屏」都在这里被拦下。
            // 不记密码内容，只记两项各自的结果 —— 否则这两种完全不同的
            // 故障在日志里长得一模一样。
            Log.i(
                TAG,
                "放弃本次尝试 密码=${if (pin == null) "读取失败" else "已取得"} " +
                    "亮屏=${display?.displayId?.toString() ?: "无"}"
            )
            busy.set(false)
            return
        }
        // 外屏（display 1）已实测可注入，见 [injectAndVerify] 里的 -d 说明。
        // 之前这里有一道「只亮外屏就跳过」的保护，是在没找到根因前的保守选择：
        // 当时以为外屏 bouncer 布局不同，实际原因是 `input -d 1` 命令本身会失败。
        // 「上次解锁时间」记的是注入开始时刻：这是用户语义里的
        // 「手机是什么时候尝试解锁的」，与验证结果（完成时刻）差不到 3 秒。
        sp(ctx).edit().putLong(KEY_LAST_ATTEMPT_AT, System.currentTimeMillis()).apply()
        QuickLaunchExecutors.io.execute {
            var outcome = UnlockOutcome.NOT_READY
            try {
                outcome = injectAndVerify(ctx, display, pin)
            } catch (t: Throwable) {
                Log.e(TAG, "自动解锁异常（不计失败）", t)
            } finally {
                busy.set(false)
                when (outcome) {
                    UnlockOutcome.UNLOCKED -> {
                        sp(ctx).edit().putString(KEY_LAST_RESULT, RESULT_SUCCESS).apply()
                        resetFailures(ctx)
                        stopWatch()
                        Log.i(TAG, "自动解锁成功 display=${display.displayId}")
                    }
                    UnlockOutcome.WRONG_PIN -> {
                        val n = failures(ctx) + 1
                        sp(ctx).edit()
                            .putString(KEY_LAST_RESULT, RESULT_FAILED)
                            .putInt(KEY_FAILURES, n)
                            .apply()
                        Log.w(TAG, "自动解锁失败（密码不符或提交未生效），累计 $n/$MAX_FAILURES")
                        if (notifyOnFailure(ctx)) notifyFailure(ctx, n)
                        if (n >= MAX_FAILURES) handler.post { giveUp(ctx) }
                    }
                    UnlockOutcome.NOT_READY -> {
                        // 环境没准备好（bouncer 没弹出 / 找不到触摸设备）不是密码错，
                        // **绝不能计进失败次数** —— 否则一次误判就吃掉用户一半额度
                        // （2026-11-23 真机就是这样两次归零的）。
                        // 但必须收手：本次观察窗口不再尝试，等下一次亮屏。
                        stopWatch()
                        Log.w(TAG, "本次未注入（环境未就绪），不计失败")
                    }
                }
            }
        }
    }

    /**
     * 单次解锁失败的通知（安全停用通知见 [notifyGiveUp]，不受开关控制）。
     * 原因只给「次数」与「可能原因」，**绝不包含 PIN 的任何信息**。
     */
    private fun notifyFailure(ctx: Context, count: Int) {
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
                .setContentTitle(ctx.getString(R.string.auto_unlock_fail_title))
                .setContentText(ctx.getString(R.string.auto_unlock_fail_text, count, MAX_FAILURES))
                .setSmallIcon(R.drawable.ic_notification_stat)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            nm.notify(NOTIF_ID, n)
        }
    }

    /**
     * 注入密码并回读真实结果（**看 KeyguardManager，不看命令退出码**）。
     * input 命令成功返回只代表事件发出去了，不代表密码对。
     */
    @Suppress("DEPRECATION")
    private fun injectAndVerify(ctx: Context, display: Display, pin: String): UnlockOutcome {
        // 纵深防御：setPin 时校验过格式，但这里是**从存储读回**的字符串，且下面直接拼进
        // shell 命令字面量（只有单引号包裹）。若不在这里再校验一次，被篡改的
        // SharedPreferences 就能注入任意命令片段。校验失败不注入、按密码问题处理。
        if (!AutoUnlockDecision.validPin(pin)) return UnlockOutcome.WRONG_PIN

        val p = Point()
        runCatching { display.getRealSize(p) }
        val w = if (p.x > 0) p.x else display.mode.physicalWidth
        val h = if (p.y > 0) p.y else display.mode.physicalHeight
        val id = display.displayId

        // 1) 确保 bouncer（密码键盘）已经出现。
        //    没出现就输入 = 密码打空 = 白白消耗一次额度，这是真机上两次归零的直接原因。
        if (!bouncerVisible()) {
            if (!swipeUp(id, w, h)) return UnlockOutcome.NOT_READY
            var shown = false
            for (i in 0 until 6) {
                SystemClock.sleep(180)
                if (bouncerVisible()) {
                    shown = true
                    break
                }
            }
            if (!shown) {
                Log.w(TAG, "上滑后 bouncer 仍未出现（display=$id），放弃注入")
                return UnlockOutcome.NOT_READY
            }
        } else {
            Log.i(TAG, "bouncer 已就绪，跳过上滑（display=$id）")
        }

        // 2) 输入密码。PIN 键盘在输满位数时会**自动提交**，所以先看自动提交的结果。
        val typed = RootUtils.runAsRoot("input text '$pin'")
        Log.i(TAG, "输入密码 display=$id 命令执行=$typed（内容不记录）")

        repeat(5) {
            SystemClock.sleep(200)
            if (!deviceLocked(ctx)) {
                Log.i(TAG, "Keyguard 已解除 display=$id（自动提交）")
                return UnlockOutcome.UNLOCKED
            }
        }

        // 3) 部分 ROM 不自动提交，补一个回车
        RootUtils.runAsRoot("input keyevent 66")
        repeat(8) {
            SystemClock.sleep(200)
            if (!deviceLocked(ctx)) {
                Log.i(TAG, "Keyguard 已解除 display=$id（回车提交）")
                return UnlockOutcome.UNLOCKED
            }
        }

        Log.w(TAG, "Keyguard 未解除 display=$id，判定为密码不符")
        return UnlockOutcome.WRONG_PIN
    }

    /**
     * 上滑叫出 bouncer。
     *
     * 优先 sendevent 直接写触摸设备：`input swipe` 的事件标注 displayId=0，
     * 而合盖时内屏 powerMode=Off，事件会被 InputDispatcher 丢弃 ——
     * 外屏一个触摸都收不到（真机两个命令的截图逐字节对比证实）。
     */
    private fun swipeUp(displayId: Int, w: Int, h: Int): Boolean {
        val swipe = AutoUnlockDecision.swipeFromSize(w, h)
        val x = swipe[0]
        val y1 = swipe[1]
        val y2 = swipe[3]

        val touch = findTouchDevice(w, h)
        return if (touch != null) {
            val (path, scale) = touch
            val ok = RootUtils.runAsRoot(AutoUnlockDecision.swipeScript(path, scale, x, y1, y2))
            Log.i(TAG, "上滑 display=$displayId 设备=$path 缩放=$scale 执行=$ok")
            ok
        } else {
            // 拿不到设备节点（API < 29 或没有匹配的触摸设备）→ 退回 input 命令。
            // 只对默认屏可靠；非默认屏可能整条命令都无效，由 bouncer 巡检兜底。
            val d = if (displayId == Display.DEFAULT_DISPLAY) "-d $displayId " else ""
            val ok = RootUtils.runAsRoot("input ${d}swipe $x $y1 $x $y2 250")
            Log.i(TAG, "上滑 display=$displayId 无设备节点，退回 input 命令 执行=$ok")
            ok
        }
    }

    /** bouncer（PIN 键盘）当前是否显示。SystemUI 的 `mBouncerVisible` 是唯一实测可靠的信号。 */
    private fun bouncerVisible(): Boolean {
        // `|| true` 必须有：grep -c 零匹配时退出码是 1，capture 会把输出吞掉当失败。
        val out = RootUtils.capture(
            "dumpsys activity service com.android.systemui/.SystemUIService 2>/dev/null " +
                "| grep -c 'mBouncerVisible=true' || true",
            BOUNCER_PROBE_TIMEOUT_MS
        ) ?: return false
        return (out.trim().toIntOrNull() ?: 0) > 0
    }

    /**
     * 找到与目标屏匹配的触摸设备，返回（设备节点, 坐标缩放比）。
     *
     * 用 `getevent -pl` 的文本而不是 [InputDevice]：后者拿设备节点的方法是
     * `getPath()`，那是 SystemApi，普通应用编译期就解析不到。
     *
     * 匹配依据见 [AutoUnlockDecision.pickTouchDevice]。
     */
    private fun findTouchDevice(width: Int, height: Int): Pair<String, Float>? {
        val dump = RootUtils.capture("getevent -pl 2>/dev/null", GETEVENT_TIMEOUT_MS) ?: return null
        return AutoUnlockDecision.pickTouchDevice(dump, width, height)
    }

    /** 连续失败到达上限：关闭功能 + 通知，绝不继续重试。 */
    private fun giveUp(ctx: Context) {
        stopWatch()
        // 先落标记再关开关：[setEnabled] 在 false 分支不清标记，
        // 但反过来若先关后标，中间任何一次 setEnabled(true) 都会越过保护。
        sp(ctx).edit()
            .putBoolean(KEY_AUTO_DISABLED, true)
            .putString(KEY_AUTO_DISABLED_REASON, DISABLED_BY_FAILURES)
            .apply()
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

    // ── 远程策略应用 ─────────────────────────────────────────────

    /**
     * 远程（VPS）下发的自动解锁策略。
     *
     * 刻意只有两个字段：**没有 PIN、没有失败次数、没有任何时间窗参数。**
     * PIN 永不出手机；失败保护只在本地（远程重置计数等于拆掉安全网）；
     * 时间窗需要改动 [AutoUnlockDecision.decide] 的判定链，本轮明确不做。
     */
    data class RemotePolicy(
        val enabled: Boolean,
        val chargingOnly: Boolean
    )

    sealed class ApplyResult {
        data class Applied(val enabled: Boolean, val chargingOnly: Boolean) : ApplyResult()
        data class Refused(val reason: String, val detail: String) : ApplyResult()
    }

    /**
     * 应用远程策略。**阻塞调用**（含 root 检测），必须在后台线程执行。
     *
     * 门控顺序（每一条都有明确的拒绝理由，直接写进回报）：
     * 1. 本地安全停用 → 一律拒绝启用。这是需求里最重要的一条：
     *    「远程服务器不能重新打开已因失败自动关闭的自动解锁」，
     *    因为远程无法知道 PIN 为什么会连续错——可能是用户改了锁屏密码，
     *    此时远程强行打开等于让手机反复输错密码。
     * 2. 本地无 PIN → 拒绝。PIN 只能在手机本地设置。
     * 3. PIN 处于降级存储（XOR 混淆）→ 拒绝启用。远程看不到降级状态，
     *    强行启用等于让一台「密码只是被混淆着」的设备自动开屏。
     * 4. 无 root → 拒绝（注入需要 su）。
     * 5. 全部通过 → 应用 chargingOnly 与 enabled。
     */
    fun applyRemotePolicy(ctx: Context, policy: RemotePolicy): ApplyResult {
        if (policy.enabled) {
            // 门控判定抽成纯函数（AutoUnlockDecision.remoteEnableGate），
            // 17 个安全场景在 JVM 单测里逐条钉死；这里只负责查本地真实状态并套用。
            val reason = AutoUnlockDecision.remoteEnableGate(
                securityLocked = isSecurityLocked(ctx),
                hasPin = hasPin(ctx),
                insecureStorage = pinStorageKind(ctx) == PinVault.StorageKind.OBFUSCATED,
                hasRoot = runCatching { RootUtils.hasRoot() }.getOrDefault(false)
            )
            if (reason != null) {
                return ApplyResult.Refused(reason, gateDetail(reason))
            }
        }
        setChargingOnly(ctx, policy.chargingOnly)
        if (policy.enabled != isEnabled(ctx)) {
            setEnabled(ctx, policy.enabled)
        }
        return ApplyResult.Applied(isEnabled(ctx), policy.chargingOnly)
    }

    /** 拒绝原因的人话版本，直接进回报明细，让看板上能看懂为什么没生效。 */
    private fun gateDetail(reason: String): String = when (reason) {
        AutoUnlockDecision.GateReason.SECURITY_LOCKED ->
            "自动解锁因连续失败已停用，必须在手机上手动重新启用"
        AutoUnlockDecision.GateReason.NO_PIN ->
            "手机本地尚未配置解锁 PIN"
        AutoUnlockDecision.GateReason.INSECURE_STORAGE ->
            "PIN 存储处于降级状态（AndroidKeyStore 不可用），远程不能启用自动解锁"
        AutoUnlockDecision.GateReason.NO_ROOT ->
            "设备没有可用的 root 权限"
        else -> reason
    }
}
