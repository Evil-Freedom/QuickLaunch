package com.workbuddy.quicklaunch.util

import android.content.Context
import com.workbuddy.quicklaunch.data.AppDatabase
import java.util.Calendar
import java.util.Locale

/**
 * 判断某天是否命中已同步的中国法定节假日安排。
 * 直接从本地 holidays 表加载日期集合，离线也能用，无需实时联网。
 *
 * 维护两个互斥的日期集合：
 * - [restDates]    休息日 → [isHoliday] 为 true，用于「跳过节假日不触发」
 * - [workdays]     调休上班日（如春节前的周六补班）→ [isMakeupWorkday] 为 true，用于「遇到调休自动运行」
 *
 * 调休上班日必然落在周末，与 [Calendar.DAY_OF_WEEK] 的判断结果相冲突，
 * 因此排程时必须优先查这一集合，命中则直接强制触发，不再看星期规则。
 */
class HolidayChecker(
    private val restDates: Set<String>,
    private val workdays: Set<String>
) {

    /** 命中法定休息日。调休上班日不算休息日，两个集合互斥。 */
    fun isHoliday(cal: Calendar): Boolean = restDates.contains(dateKey(cal))

    /** 命中调休上班日（周末补班）。 */
    fun isMakeupWorkday(cal: Calendar): Boolean = workdays.contains(dateKey(cal))

    /** 无任何节假日数据时的快速路径：省掉每次 dateKey 的字符串拼接。 */
    fun isEmpty(): Boolean = restDates.isEmpty() && workdays.isEmpty()

    /** 两集合都空 —— 即完全没有任何可与日期比对的数据。 */
    fun hasNoData(): Boolean = isEmpty()

    companion object {

        /** 空实例，供「不跳过节假日」场景复用，避免重复建对象与查库。 */
        val EMPTY = HolidayChecker(emptySet(), emptySet())

        /** 读库失败（数据库损坏/磁盘满）时退化为空集合，绝不让排程流程崩掉。 */
        fun fromDb(context: Context): HolidayChecker {
            return runCatching {
                val dao = AppDatabase.get(context).holidayDao()
                HolidayChecker(
                    restDates = dao.getRestDates().toSet(),
                    workdays = dao.getWorkdayDates().toSet()
                )
            }.getOrDefault(EMPTY)
        }

        /**
         * Calendar -> yyyy-MM-dd。
         * 必须固定 Locale.US：阿拉伯语等 locale 下 %d 会输出本地数字（٢٠٢٦），
         * 与数据库里的 ASCII 日期永远匹配不上，导致节假日跳过静默失效。
         */
        fun dateKey(cal: Calendar): String = String.format(
            Locale.US,
            "%04d-%02d-%02d",
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
    }
}
