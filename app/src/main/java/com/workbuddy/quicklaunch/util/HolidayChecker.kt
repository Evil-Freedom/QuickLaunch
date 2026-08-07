package com.workbuddy.quicklaunch.util

import android.content.Context
import com.workbuddy.quicklaunch.data.AppDatabase
import java.util.Calendar
import java.util.Locale

/**
 * 判断某天是否命中已同步的中国法定节假日（含调休休息日）。
 * 直接从本地 holidays 表加载日期集合，离线也能用，无需实时联网。
 */
class HolidayChecker(private val dates: Set<String>) {

    fun isHoliday(cal: Calendar): Boolean {
        val s = dateKey(cal)
        return dates.contains(s)
    }

    /** 无节假日数据时的快速路径：省掉每次 dateKey 的字符串拼接。 */
    fun isEmpty(): Boolean = dates.isEmpty()

    companion object {

        /** 空实例，供「不跳过节假日」场景复用，避免重复建对象与查库。 */
        val EMPTY = HolidayChecker(emptySet())

        /** 读库失败（数据库损坏/磁盘满）时退化为空集合，绝不让排程流程崩掉。 */
        fun fromDb(context: Context): HolidayChecker {
            val dates = runCatching {
                AppDatabase.get(context).holidayDao().getAllDates().toSet()
            }.getOrDefault(emptySet())
            return HolidayChecker(dates)
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
