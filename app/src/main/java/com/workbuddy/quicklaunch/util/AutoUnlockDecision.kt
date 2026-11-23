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
}
