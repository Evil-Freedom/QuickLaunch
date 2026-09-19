package com.workbuddy.quicklaunch.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 中国法定节假日缓存。date 为 yyyy-MM-dd，name 为节日名（如「元旦」）。
 * 数据来自定时同步的国务院放假安排。
 *
 * [isWorkday] 区分两类日期，二者用途完全不同：
 * - false = 休息日（春节、国庆，或调休换来的休息日）→ 用于「跳过节假日不触发」
 * - true  = 调休上班日（周末补班，如「春节」后的周六周日）→ 用于「遇到调休自动运行」
 *
 * 调休上班日落在周末，若只按星期判断会被误判为休息日而漏触发，
 * 因此必须显式记录，不能靠 Calendar.DAY_OF_WEEK 推断。
 */
@Entity(tableName = "holidays")
data class Holiday(
    @PrimaryKey val date: String,
    val name: String,
    val isWorkday: Boolean = false
)
