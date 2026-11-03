package com.workbuddy.quicklaunch.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一条「自动化」规则：当 triggerType 满足时，自动拉起 targetPackage 对应的 App。
 *
 * @param repeatMode 仅对 TIME 触发生效：daily / weekdays / weekend / once / custom
 * @param repeatDays 仅 repeatMode="custom" 生效：星期位图，bit (Calendar.DAY_OF_WEEK - 1) 表示选中。
 *   例如周一三五 = (1 shl 1) or (1 shl 3) or (1 shl 5) = 0b0101010。
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
    val repeatDays: Int = 0,
    /** 仅对 TIME 触发生效：true 时跳过中国法定节假日（含调休休息日）不触发 */
    val skipHolidays: Boolean = false,
    val bluetoothName: String = "",
    val wifiName: String = "",
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    /** 随机时间：true 时忽略 timeHour/timeMinute，改在 [windowStartMin, windowEndMin] 当天分钟区间里随机取触发时刻 */
    val randomWindow: Boolean = false,
    /** 随机窗口起始，当天分钟数（例如 8:30 => 510） */
    val windowStartMin: Int = 0,
    /** 随机窗口结束，当天分钟数（例如 8:50 => 530） */
    val windowEndMin: Int = 0,
    /**
     * 触发时刻的锚定方式，见 [ShiftAnchor]。
     *
     * 默认 [ShiftAnchor.FIXED] = 用 timeHour/timeMinute，与旧版本行为逐字节一致。
     * 非 FIXED 时**完全忽略** timeHour/timeMinute、repeatMode、repeatDays、skipHolidays
     * 和随机窗口 —— 触发由当天班表决定，见 [com.workbuddy.quicklaunch.util.Scheduler]。
     */
    val shiftAnchor: Int = ShiftAnchor.FIXED,
    /**
     * 相对班次时刻的偏移分钟数，负数=提前。
     * 仅 [shiftAnchor] != FIXED 时生效；例如上班前 30 分钟 = -30。
     */
    val shiftOffsetMin: Int = 0
)
