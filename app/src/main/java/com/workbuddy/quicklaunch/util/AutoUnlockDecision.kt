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

    /**
     * 由屏幕尺寸推出上滑手势，用于叫出 keyguard bouncer。
     * 必须先上滑：实测直接 `input text` 打不进密码框（MENU 键 82 在这台机器上也拉不起 bouncer）。
     */
    fun swipeFromSize(width: Int, height: Int): IntArray {
        val x = width / 2
        return intArrayOf(x, (height * 0.82f).toInt(), x, (height * 0.28f).toInt())
    }
}
