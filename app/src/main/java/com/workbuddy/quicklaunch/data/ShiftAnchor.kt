package com.workbuddy.quicklaunch.data

/**
 * 规则触发时刻的锚定方式。
 *
 * 默认 [FIXED] 沿用旧行为（timeHour/timeMinute 固定时刻）。
 * 另外两种把触发时刻交给「当天班表」，用于轮班制用户：每天几点上班不是固定的，
 * 由公司排班决定，所以规则要跟着班表走，而不是写死一个时刻。
 *
 * 为什么用 Int 而不是 String 枚举：这是 Room 的一列，扩展时加一个数字即可，
 * 不必担心旧库里存着新版本才认识的字面量（那会让 `when` 落进 else 静默降级）。
 */
object ShiftAnchor {
    /** 固定时刻，不依赖班表。 */
    const val FIXED = 0

    /** 锚定上班时刻 + [Automation.shiftOffsetMin]（负数=提前）。 */
    const val CLOCK_IN = 1

    /** 锚定下班时刻 + [Automation.shiftOffsetMin]（正数=延后）。 */
    const val CLOCK_OUT = 2

    fun isValid(v: Int): Boolean = v in FIXED..CLOCK_OUT

    /**
     * 人话描述，例如「上班前 30 分钟」「下班后 10 分钟」「上班时」。
     *
     * 放在这里而不是各自界面里拼字符串：列表卡片、编辑页、远程接口三处都要展示，
     * 各写一份必然会出现「卡片写前 30、接口写后 30」这种对不上的情况。
     */
    fun describe(anchor: Int, offsetMin: Int): String {
        if (anchor == FIXED) return ""
        val name = if (anchor == CLOCK_OUT) "下班" else "上班"
        return when {
            offsetMin == 0 -> "${name}时"
            offsetMin < 0 -> "${name}前 ${-offsetMin} 分钟"
            else -> "${name}后 $offsetMin 分钟"
        }
    }
}
