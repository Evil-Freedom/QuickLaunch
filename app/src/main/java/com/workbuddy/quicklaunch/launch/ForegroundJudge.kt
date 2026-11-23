package com.workbuddy.quicklaunch.launch

/**
 * 「目标包是否真的在前台」的判定（纯逻辑，无 Android 依赖）。
 *
 * 判定要同时满足两件事，缺一不可：
 * 1. 目标包是某个 display 的顶层 Activity；
 * 2. 那个 display 当前是**亮着的**。
 *
 * 只看第 1 条会误判：合盖时外屏桌面可能在 display 1 上「前台」，而用户看的是
 * 内屏（display 0），此时把 display 1 上的旧 Activity 当成成功就是假阳性。
 * 只看第 2 条显然也不行。
 */
object ForegroundJudge {

    /** 判定结论。用三态而不是布尔：「读不到」与「确认不在前台」必须分开。 */
    enum class Verdict {
        /** 确认目标在前台（亮屏 display 的顶层就是它）。 */
        VISIBLE,

        /** 确认不在前台（读到了前台信息，但没有一处是目标包）。 */
        NOT_VISIBLE,

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
     */
    fun judge(
        targetPkg: String,
        topByDisplay: Map<Int, String>,
        litDisplays: Set<Int>
    ): Verdict {
        if (targetPkg.isEmpty()) return Verdict.UNKNOWN
        if (topByDisplay.isEmpty()) return Verdict.UNKNOWN

        // 正常路径：只在亮着的屏上比对
        val onLit = topByDisplay.any { (display, pkg) -> pkg == targetPkg && display in litDisplays }
        if (onLit) return Verdict.VISIBLE

        // 拿不到亮屏集合（DisplayManager 异常 / 全部 STATE_OFF）时退化为「任一屏命中」。
        // 这是有意的降级：宁可多认一次成功，也不要因为读不到屏幕状态就让
        // 每一次启动都被判失败、把用户拖进无休止的重试与告警。
        if (litDisplays.isEmpty()) {
            return if (topByDisplay.containsValue(targetPkg)) Verdict.VISIBLE else Verdict.NOT_VISIBLE
        }

        return Verdict.NOT_VISIBLE
    }
}
