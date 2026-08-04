package com.workbuddy.quicklaunch.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一条「自动化」规则：当 triggerType 满足时，自动拉起 targetPackage 对应的 App。
 *
 * @param repeatMode 仅对 TIME 触发生效：daily / weekdays / weekend / once
 * @param bluetoothName 仅对 BLUETOOTH 触发生效，留空表示任意蓝牙设备
 */
@Entity(tableName = "automations")
data class Automation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val targetPackage: String,
    val targetAppName: String,
    val triggerType: String,
    val timeHour: Int = 0,
    val timeMinute: Int = 0,
    val repeatMode: String = "daily",
    val bluetoothName: String = "",
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)
