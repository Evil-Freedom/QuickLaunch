package com.workbuddy.quicklaunch.util

import android.content.Context
import com.workbuddy.quicklaunch.data.AppDatabase
import java.util.Calendar

/**
 * 判断某天是否命中已同步的中国法定节假日（含调休休息日）。
 * 直接从本地 holidays 表加载日期集合，离线也能用，无需实时联网。
 */
class HolidayChecker(private val dates: Set<String>) {

    fun isHoliday(cal: Calendar): Boolean {
        val s = dateKey(cal)
        return dates.contains(s)
    }

    companion object {
        fun fromDb(context: Context): HolidayChecker {
            val dates = AppDatabase.get(context).holidayDao().getAllDates().toSet()
            return HolidayChecker(dates)
        }

        /** Calendar -> yyyy-MM-dd */
        fun dateKey(cal: Calendar): String = "%04d-%02d-%02d".format(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
    }
}
