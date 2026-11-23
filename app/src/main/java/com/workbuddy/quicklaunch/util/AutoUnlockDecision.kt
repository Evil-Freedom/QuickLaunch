package com.workbuddy.quicklaunch.util

/**
 * 自动解锁的判定逻辑，**刻意不引用任何 Android API** —— 这样能在 JVM 单测里
 * 直接把边界钉死（AutoUnlock 本体在类初始化时就会 new Handler，单测里跑不起来）。
 */
object AutoUnlockDecision {

    enum class Action { SKIP, ATTEMPT, GIVE_UP }

    /** 连续失败上限：达到即关闭功能，避免撞上系统「5 次错误清除数据」的红线。 */
    const val MAX_FAILURES = 2

    /** 两次尝试最小间隔，给 keyguard 动画留时间。 */
    const val MIN_INTERVAL_MS = 700L

    /**
     * @param displayLit 是否至少有一块屏处于亮屏态。黑屏时绝不注入输入事件。
     * @param msSinceLastAttempt 距上次尝试的毫秒数；从未尝试过传 Long.MAX_VALUE。
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
    ): Action {
        if (!enabled || !hasPin) return Action.SKIP
        // 先判上限再判其它：屏幕上没人操作时也要让调用方有机会提示「已暂停」
        if (failures >= MAX_FAILURES) return Action.GIVE_UP
        if (!deviceLocked || !displayLit) return Action.SKIP
        if (chargingOnly && !charging) return Action.SKIP
        if (msSinceLastAttempt in 0 until MIN_INTERVAL_MS) return Action.SKIP
        return Action.ATTEMPT
    }

    /** 密码格式：只要数字 PIN（字母密码走的是另一种键盘，`input text` 注入不稳）。 */
    fun validPin(pin: String?): Boolean = pin != null && pin.matches(Regex("^\\d{4,16}$"))

    /** 远程启用被拒的原因码。与 AutoUnlock.ApplyResult.Refused.reason 一一对应。 */
    object GateReason {
        const val SECURITY_LOCKED = "SECURITY_LOCKED"
        const val NO_PIN = "NO_PIN"
        const val INSECURE_STORAGE = "INSECURE_STORAGE"
        const val NO_ROOT = "NO_ROOT"
    }

    /**
     * 「远程能否启用自动解锁」的门控（纯函数，可单测）。
     *
     * 顺序有讲究，与本地判定链的取舍一致：
     * **安全停用 > 有无凭据 > 凭据质量 > 执行能力**。
     * 安全停用排第一：即使后来补了 PIN，只要没有本地确认，远程就不能打开 ——
     * 这是「两次失败自动停用后必须本地恢复」这条硬规则的落点。
     *
     * @return null 表示允许启用；非 null 是 [GateReason] 里的原因码。
     */
    fun remoteEnableGate(
        securityLocked: Boolean,
        hasPin: Boolean,
        insecureStorage: Boolean,
        hasRoot: Boolean
    ): String? = when {
        securityLocked -> GateReason.SECURITY_LOCKED
        !hasPin -> GateReason.NO_PIN
        insecureStorage -> GateReason.INSECURE_STORAGE
        !hasRoot -> GateReason.NO_ROOT
        else -> null
    }

    /**
     * 由屏幕尺寸推出上滑手势，用于叫出 keyguard bouncer。
     * 必须先上滑：实测直接 `input text` 打不进密码框（MENU 键 82 在这台机器上也拉不起 bouncer）。
     */
    fun swipeFromSize(width: Int, height: Int): IntArray {
        val x = width / 2
        return intArrayOf(x, (height * 0.82f).toInt(), x, (height * 0.28f).toInt())
    }

    /**
     * 拼装「上滑 → 输入密码 → 回车」的注入命令。
     *
     * 抽成纯函数只为一件事：`-d` 的用法必须被测试钉死。
     *
     * `-d <id>` 只能给默认屏用。合盖外屏（displayId=1）会让 `input` 抛
     * `IllegalArgumentException: Invalid arguments for display ID.` ——
     * 命令压根没执行，而调用方若只看「有没有抛异常」就会把它当成一次
     * 正常尝试，连续两次把自动解锁顶到安全停用（真机踩过）。
     *
     * 不带 `-d` 时 InputManager 按当前活跃 viewport 分发：合盖态内屏 state=OFF、
     * 外屏 isActive=[1]，事件正好落到外屏。
     *
     * @param displayId 目标屏。0 = 默认屏（显式指定）；非 0 时不带 `-d`。
     */
    fun injectCommand(displayId: Int, width: Int, height: Int, pin: String): String {
        val swipe = swipeFromSize(width, height)
        val x = swipe[0]
        val y1 = swipe[1]
        val y2 = swipe[3]
        val d = if (displayId == 0) "-d $displayId " else ""
        // 一条命令跑完：每次 fork `input` 都要起一个 app_process，分开调用慢一倍以上。
        return "input ${d}swipe $x $y1 $x $y2 250; " +
            "sleep 0.45; input ${d}text '$pin'; sleep 0.25; input keyevent 66"
    }

    // ── sendevent 上滑脚本 ────────────────────────────────────────
    //
    // 为什么需要它：`input swipe` 注入的事件默认标注 displayId=0，
    // InputDispatcher 只在**处于活动状态**的 display 上找接收者。合盖时
    // 内屏 powerMode=Off（`dumpsys SurfaceFlinger` 实证），事件被直接丢弃 ——
    // 外屏（display 1）一个触摸都收不到，bouncer 永远弹不出来。
    //
    // sendevent 直接写触摸设备节点，绕过 display 路由，是唯一可靠路径。
    // 真机验证：同一坐标下 sendevent 上滑让外屏 bouncer 正确弹出，
    // `input swipe` 前后截图逐字节相同（完全没反应）。

    private const val EV_SYN = 0
    private const val EV_KEY = 1
    private const val EV_ABS = 3
    private const val ABS_MT_SLOT = 47
    private const val ABS_MT_POSITION_X = 53
    private const val ABS_MT_POSITION_Y = 54
    private const val ABS_MT_TRACKING_ID = 57
    private const val BTN_TOUCH = 330
    private const val SYN_REPORT = 0

    /**
     * 生成「上滑叫出 keyguard bouncer」的 sendevent 脚本（多点触控协议 B）。
     *
     * @param path 触摸设备节点，如 `/dev/input/event8`
     * @param scale 屏幕坐标 → 设备原始坐标的倍数（raw = screen × scale）
     * @param steps 中间点数量。给 4 个：太少会被当成「瞬移点击」，滑动识别不出来。
     */
    fun swipeScript(path: String, scale: Float, x: Int, y1: Int, y2: Int, steps: Int = 4): String {
        val events = ArrayList<String>(steps * 2 + 12)
        fun ev(type: Int, code: Int, value: Int) {
            events += "sendevent $path $type $code $value"
        }
        fun rawY(y: Int) = (y * scale).toInt()

        ev(EV_ABS, ABS_MT_SLOT, 0)
        ev(EV_ABS, ABS_MT_TRACKING_ID, 1)
        ev(EV_ABS, ABS_MT_POSITION_X, (x * scale).toInt())
        ev(EV_ABS, ABS_MT_POSITION_Y, rawY(y1))
        ev(EV_KEY, BTN_TOUCH, 1)
        ev(EV_SYN, SYN_REPORT, 0)
        for (i in 1..steps) {
            ev(EV_ABS, ABS_MT_POSITION_Y, rawY(y1 + (y2 - y1) * i / steps))
            ev(EV_SYN, SYN_REPORT, 0)
        }
        ev(EV_ABS, ABS_MT_TRACKING_ID, -1)
        ev(EV_KEY, BTN_TOUCH, 0)
        ev(EV_SYN, SYN_REPORT, 0)
        // 每条之间用 `;` 串起来在**同一个** shell 里跑：分开 fork 会让滑动慢到识别失败。
        return events.joinToString("; ")
    }

    /**
     * 从 `getevent -pl` 的输出里挑出与目标屏匹配的触摸设备。
     *
     * 匹配依据：设备的 X/Y 最大原始值与该屏分辨率的**缩放比应当一致** ——
     * 同一颗触摸 IC 对同一块屏用同一套缩放。真机实测数据：
     *
     * | 设备 | maxX | maxY | 内屏 1080×2640 | 外屏 1056×1066 |
     * | --- | --- | --- | --- | --- |
     * | goodix_ts | 17280 | 42240 | 16 / 16 ✅ | 16.4 / 39.6 ❌ |
     * | fts_ts | 16896 | 17056 | 15.6 / 6.5 ❌ | 16 / 16 ✅ |
     *
     * 拿内屏设备算外屏时两个比例差出一个数量级，据此可唯一区分，不需要硬编码设备名。
     *
     * @return (设备节点, 缩放比)；找不到返回 null
     */
    fun pickTouchDevice(geteventDump: String, width: Int, height: Int): Pair<String, Float>? {
        if (width <= 0 || height <= 0) return null

        var path: String? = null
        var maxX = 0f
        var maxY = 0f
        var best: Pair<String, Float>? = null
        var bestDiff = Float.MAX_VALUE

        /** 结算当前设备块。 */
        fun evaluate() {
            val p = path ?: return
            if (maxX <= 0f || maxY <= 0f) return
            val sx = maxX / width
            val sy = maxY / height
            if (sx < 0.5f || sx > 200f) return
            val diff = kotlin.math.abs(sx - sy) / sx
            if (diff > 0.1f) return
            if (diff < bestDiff) {
                bestDiff = diff
                best = p to sx
            }
        }

        for (raw in geteventDump.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("add device") -> {
                    evaluate()
                    path = line.substringAfter(": ", "").trim().ifEmpty { null }
                    maxX = 0f
                    maxY = 0f
                }
                line.startsWith("ABS_MT_POSITION_X") -> maxX = parseEventMax(line)
                line.startsWith("ABS_MT_POSITION_Y") -> maxY = parseEventMax(line)
            }
        }
        evaluate()
        return best
    }

    /** 解析 `ABS_MT_POSITION_X : value 0, min 0, max 16896, fuzz 0, ...` 里的 max。 */
    private fun parseEventMax(line: String): Float {
        val idx = line.indexOf("max ")
        if (idx < 0) return 0f
        val num = line.substring(idx + 4).takeWhile { it.isDigit() || it == '-' }
        return num.toFloatOrNull() ?: 0f
    }
}
