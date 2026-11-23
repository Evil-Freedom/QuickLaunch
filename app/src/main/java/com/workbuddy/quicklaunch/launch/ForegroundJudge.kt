package com.workbuddy.quicklaunch.launch

/**
 * 「目标 App 是否真的在前台」的判定（纯逻辑，无 Android 依赖）。
 *
 * ## 判定要同时满足两件事
 *
 * 1. 目标包是某个 display 的顶层 Activity；
 * 2. 那个 display 当前是**亮着的**。
 *
 * 只看第 1 条会误判：合盖时外屏桌面可能在 display 1 上「前台」，而用户看的是
 * 内屏（display 0），此时把 display 1 上的旧 Activity 当成成功就是假阳性。
 *
 * ## 三态之外还有第四态：被锁屏挡住
 *
 * 2026-11-23 真机实测：锁屏状态下整个 dump 没有 `topResumedActivity`，
 * 目标 App 只出现在 `mLastPausedActivity` 且 Task 带 `isSleeping=true`。
 *
 * 此时 App **确实被启动了**，但 Keyguard 让它永远拿不到 resumed —— 用户看不到。
 * 把它归为「未启动」会让状态机按普通失败重试 3 次，每次都点亮一次屏幕，
 * 而锁屏状态不会因为重试而改变，纯属耗电和打扰。
 * 所以单独给出 [Verdict.BLOCKED_BY_KEYGUARD]：结论明确、应立刻收手。
 */
object ForegroundJudge {

    /** 判定结论。用多态而不是布尔：「读不到」「被锁屏挡」「确认不在前台」必须分开。 */
    enum class Verdict {
        /** 确认目标在前台（亮屏 display 的顶层就是它）。 */
        VISIBLE,

        /** 确认不在前台（读到了前台信息，但没有一处是目标包）。 */
        NOT_VISIBLE,

        /**
         * 目标已被启动，但设备处于睡眠/锁屏，它拿不到前台。
         *
         * 这是「已经尽力了」的状态：再重试也不会好转，必须由用户解锁。
         */
        BLOCKED_BY_KEYGUARD,

        /**
         * 无法判断（没有任何可用数据源，或读到的信息不完整）。
         *
         * 调用方不得把它当作「失败」：无 root、未授予「使用情况访问权限」的设备
         * 永远拿不到前台信息，把这种情况判成失败会让重试逻辑变成纯粹的骚扰。
         */
        UNKNOWN
    }

    /**
     * @param targetPkg     目标包名
     * @param topByDisplay  displayId → 该屏顶层 Activity 的包名
     * @param litDisplays   当前处于 STATE_ON 的 displayId 集合
     * @param deviceSleeping     设备当前处于睡眠（锁屏后所有 Task 都带 isSleeping=true）
     * @param sleepingWithTarget 设备睡眠且目标出现在某个睡眠屏的 lastPaused 上
     */
    fun judge(
        targetPkg: String,
        topByDisplay: Map<Int, String>,
        litDisplays: Set<Int>,
        deviceSleeping: Boolean = false,
        sleepingWithTarget: Boolean = false
    ): Verdict {
        if (targetPkg.isEmpty()) return Verdict.UNKNOWN

        // 正常路径：只在亮着的屏上比对
        val onLit = topByDisplay.any { (display, pkg) -> pkg == targetPkg && display in litDisplays }
        if (onLit) return Verdict.VISIBLE

        // 锁屏：目标已启动但拿不到前台。这一条必须在 UNKNOWN 之前判：
        // 睡眠态下 topByDisplay 本来就是空的，先判 UNKNOWN 就永远到不了这里。
        if (sleepingWithTarget) return Verdict.BLOCKED_BY_KEYGUARD

        // 设备在睡眠且没有 resumed Activity：这是一个**确定结论**（没有任何 App
        // 能拿到前台），不是「读不到」。判 NOT_VISIBLE 比 UNKNOWN 更诚实，
        // 也让上层不会把「锁屏 + 目标不对」降级成「探测失效」。
        if (deviceSleeping && topByDisplay.isEmpty()) return Verdict.NOT_VISIBLE

        if (topByDisplay.isEmpty()) return Verdict.UNKNOWN

        // 拿不到亮屏集合（DisplayManager 异常 / 全部 STATE_OFF）时退化为「任一屏命中」。
        // 这是有意的降级：宁可多认一次成功，也不要因为读不到屏幕状态就让
        // 每一次启动都被判失败、把用户拖进无休止的重试与告警。
        if (litDisplays.isEmpty()) {
            return if (topByDisplay.containsValue(targetPkg)) Verdict.VISIBLE else Verdict.NOT_VISIBLE
        }

        return Verdict.NOT_VISIBLE
    }
}
