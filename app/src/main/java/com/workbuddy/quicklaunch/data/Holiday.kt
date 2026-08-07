package com.workbuddy.quicklaunch.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 中国法定节假日（含调休休息日）缓存。date 为 yyyy-MM-dd，name 为节日名（如「元旦」）。
 * 数据来自定时同步的国务院放假安排，仅用于「跳过节假日不触发」。
 */
@Entity(tableName = "holidays")
data class Holiday(
    @PrimaryKey val date: String,
    val name: String
)
