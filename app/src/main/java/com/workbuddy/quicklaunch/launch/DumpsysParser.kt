package com.workbuddy.quicklaunch.launch

/**
 * `dumpsys activity activities` 的解析器（纯函数，可单测）。
 *
 * ## 为什么选这个数据源
 *
 * 2026-11 在目标机（Motorola Razr 40 Ultra / Android 16）实测了四种「谁是前台」的读法：
 *
 * | 读法 | 结果 |
 * | --- | --- |
 * | `dumpsys activity activities` → `topResumedActivity` | **可用**，450ms/次，值与实际操作一致 |
 * | `dumpsys activity top` → `ACTIVITY` | 不可用：合盖时返回外屏的 SecondaryDisplayLauncher，不是用户看到的那块屏 |
 * | `dumpsys window` → `mCurrentFocus` | 不可用：锁屏与部分全屏场景下为 `null` |
 * | `dumpsys activity recents` | 不可用：不给出当前前台，只给最近任务 |
 *
 * 因此只认 `topResumedActivity`，且**必须按 `Display #N` 分段**：这台机器有
 * display 0（内屏）与 display 1（外屏），两块屏各有自己的 top Activity，
 * 不分段就会把「外屏的桌面」当成「目标 App 没起来」。
 *
 * ## 锁屏/睡眠态：必须单独识别
 *
 * 2026-11-23 真机实测（锁屏 + 屏幕已点亮）：整个 dump **没有任何
 * `topResumedActivity` 行**，每个 Task 都带 `isSleeping=true`，
 * 目标 App 只出现在 `mLastPausedActivity` 里：
 *
 * ```
 * * Task{7e8393c #1283 ... visible=true visibleRequested=false ...}
 *   mLastPausedActivity: ActivityRecord{... u0 com.android.settings/.Settings ...}
 *   isSleeping=true
 * ```
 *
 * 这是 Android 的既定行为：Keyguard 存在时没有 Activity 会进入 resumed。
 * 若只按「找不到 topResumedActivity → 读不到」处理，会把它误判成
 * 「探测失败」，进而触发无意义的重试（每次都点亮一次屏幕）。
 * 所以这里把「睡眠 + 见到过目标」明确解析成 [DumpState.sleepingWith]。
 */
object DumpsysParser {

    private val DISPLAY_HEADER = Regex("""^\s*Display #(\d+)""")
    private val ACTIVITY_RECORD = Regex("""ActivityRecord\{([^}]*)""")
    private val WINDOW_FOCUS = Regex("""mCurrentFocus=Window\{[^}]*?\s(\S+)/(\S+?)\}""")
    private val SLEEPING = Regex("""\bisSleeping=true""")

    /**
     * 一次 dump 的结构化结果。
     *
     * @param topByDisplay        displayId → 该屏顶层 resumed Activity 的包名
     * @param lastPausedByDisplay displayId → 该屏最后一个被 pause 的 Activity 包名
     * @param sleepingDisplays    处于 `isSleeping=true` 的 display
     */
    data class DumpState(
        val topByDisplay: Map<Int, String> = emptyMap(),
        val lastPausedByDisplay: Map<Int, String> = emptyMap(),
        val sleepingDisplays: Set<Int> = emptySet()
    ) {
        val anySleeping: Boolean get() = sleepingDisplays.isNotEmpty()

        /**
         * 「设备在睡眠，但目标 App 已经在某个睡眠屏上被启动过」。
         *
         * 这是锁屏场景的唯一正确解释：App 确实被拉起来了（出现在 lastPaused），
         * 但 Keyguard 让它永远拿不到 resumed —— 用户看不到，也不该被重试。
         */
        fun sleepingWith(targetPkg: String): Boolean =
            targetPkg.isNotEmpty() && anySleeping &&
                sleepingDisplays.any { lastPausedByDisplay[it] == targetPkg }
    }

    fun parseState(dump: String): DumpState {
        val top = LinkedHashMap<Int, String>()
        val lastPaused = LinkedHashMap<Int, String>()
        val sleeping = LinkedHashSet<Int>()
        var currentDisplay: Int? = null

        for (rawLine in dump.lineSequence()) {
            val header = DISPLAY_HEADER.find(rawLine)
            if (header != null) {
                currentDisplay = header.groupValues[1].toIntOrNull()
                continue
            }
            val display = currentDisplay ?: continue

            if (SLEEPING.containsMatchIn(rawLine)) sleeping += display

            when {
                // 同一 display 只取第一处（dump 的排列本身就是 top → bottom）
                !top.containsKey(display) && rawLine.contains("topResumedActivity=") -> {
                    val record = ACTIVITY_RECORD.find(rawLine)?.groupValues?.get(1)
                    packageOfRecordFields(record)?.let { top[display] = it }
                }
                !lastPaused.containsKey(display) && rawLine.contains("mLastPausedActivity") -> {
                    val record = ACTIVITY_RECORD.find(rawLine)?.groupValues?.get(1)
                    packageOfRecordFields(record)?.let { lastPaused[display] = it }
                }
            }
        }
        return DumpState(top, lastPaused, sleeping)
    }

    /**
     * 解析出「每个 display 的顶层前台包名」。
     *
     * @return displayId → packageName。只包含确实解析到 resumed Activity 的 display。
     */
    fun parseTopByDisplay(dump: String): Map<Int, String> = parseState(dump).topByDisplay

    /**
     * 解析 `mCurrentFocus=Window{... u0 pkg/pkg.Act}`。
     *
     * 仅作为 Root 不可用时的补充线索：实测在锁屏与部分全屏场景下为 `null`，
     * 所以调用方必须允许它返回 null，并把它当作「未知」而不是「不在前台」。
     */
    fun parseFocusedPackage(dump: String): String? {
        val m = WINDOW_FOCUS.find(dump) ?: return null
        val pkg = m.groupValues[1]
        return pkg.takeIf { it.isNotBlank() && it != "null" }
    }

    /**
     * 从 ActivityRecord 的花括号内容里取包名。
     *
     * 形如 `246024462 u0 com.android.settings/.Settings t1283`。
     * 判据是「含 `/` 的那一段」，而不是固定位置：不同 Android 版本会在
     * `u0` 与组件名之间多插字段（如 `u0 f}}`、`u0 visible=true`）。
     */
    private fun packageOfRecordFields(fields: String?): String? {
        if (fields == null) return null
        for (token in fields.split(' ')) {
            val slash = token.indexOf('/')
            if (slash <= 0) continue
            val pkg = token.substring(0, slash)
            // 包名必须像包名，避免把 `I=com.foo/bar` 这类 Task 标记误当职位
            if (pkg.none { it.isLetter() }) continue
            if (!pkg.contains('.')) continue
            return pkg
        }
        return null
    }
}
