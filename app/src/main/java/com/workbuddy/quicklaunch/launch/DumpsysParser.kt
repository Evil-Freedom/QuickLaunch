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
 * ## 为什么不解析 `mResumedActivity`
 *
 * 部分 ROM 的 Task 段只给 `mLastPausedActivity`。实测这台机器顶层 Task 同时给出
 * `topResumedActivity` 与 `ResumedActivity:`，前者更直接。两者都不存在时不猜。
 */
object DumpsysParser {

    private val DISPLAY_HEADER = Regex("""^\s*Display #(\d+)""")
    private val ACTIVITY_RECORD = Regex("""ActivityRecord\{([^}]*)""")
    private val WINDOW_FOCUS = Regex("""mCurrentFocus=Window\{[^}]*?\s(\S+)/(\S+?)\}""")

    /**
     * 解析出「每个 display 的顶层前台包名」。
     *
     * @return displayId → packageName。只包含确实解析到 Activity 的 display。
     */
    fun parseTopByDisplay(dump: String): Map<Int, String> {
        val result = LinkedHashMap<Int, String>()
        var currentDisplay: Int? = null
        for (rawLine in dump.lineSequence()) {
            val header = DISPLAY_HEADER.find(rawLine)
            if (header != null) {
                currentDisplay = header.groupValues[1].toIntOrNull()
                continue
            }
            val display = currentDisplay ?: continue
            // 同一 display 只取第一处（dump 的排列本身就是 top → bottom）
            if (result.containsKey(display)) continue
            if (!rawLine.contains("topResumedActivity=")) continue
            val record = ACTIVITY_RECORD.find(rawLine)?.groupValues?.get(1) ?: continue
            packageOfRecordFields(record)?.let { result[display] = it }
        }
        return result
    }

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
    private fun packageOfRecordFields(fields: String): String? {
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
