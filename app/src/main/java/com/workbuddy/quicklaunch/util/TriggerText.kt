package com.workbuddy.quicklaunch.util

import com.workbuddy.quicklaunch.data.Automation
import com.workbuddy.quicklaunch.data.RepeatMode
import com.workbuddy.quicklaunch.data.ShiftAnchor
import com.workbuddy.quicklaunch.data.TriggerType
import java.util.Locale

/**
 * 规则卡片「触发条件」的唯一实现。
 *
 * 抽成纯函数不是为了好看：随机窗口此前只印 `timeHour/timeMinute`，而
 * `randomWindow = true` 时这两个字段根本不参与调度（见 [Automation.randomWindow]），
 * 卡片于是显示了一个永远不会发生的时刻。这类显示口径必须由单测钉住，见 TriggerTextTest。
 */
object TriggerText {

    private val DAY_LABELS = arrayOf("日", "一", "二", "三", "四", "五", "六")

    fun of(a: Automation): String = when {
        // 班表模式单独一条分支：触发时刻由当天班次算出，随机窗口与固定时刻都不参与
        a.triggerType == TriggerType.TIME && a.shiftAnchor != ShiftAnchor.FIXED ->
            "班表 " + ShiftAnchor.describe(a.shiftAnchor, a.shiftOffsetMin)
        a.triggerType == TriggerType.TIME && a.randomWindow ->
            String.format(
                Locale.US, "随机 %s–%s（%s）%s",
                clockOf(a.windowStartMin), clockOf(a.windowEndMin), repeatLabel(a), holidaySuffix(a)
            )
        a.triggerType == TriggerType.TIME ->
            String.format(
                Locale.US, "定时 %02d:%02d（%s）%s",
                a.timeHour, a.timeMinute, repeatLabel(a), holidaySuffix(a)
            )
        a.triggerType == TriggerType.CHARGING -> "充电时"
        a.triggerType == TriggerType.WIFI ->
            if (a.wifiName.isNotEmpty()) "连接 WiFi:${a.wifiName}" else "连接 WiFi"
        else -> if (a.bluetoothName.isNotEmpty()) "连接蓝牙:${a.bluetoothName}" else "连接蓝牙"
    }

    /** 当天分钟数 → HH:mm。越界值夹紧，宁可显示 00:00 也不抛异常。 */
    private fun clockOf(minutesOfDay: Int): String {
        val m = minutesOfDay.coerceIn(0, 1439)
        return String.format(Locale.US, "%02d:%02d", m / 60, m % 60)
    }

    private fun holidaySuffix(a: Automation): String = if (a.skipHolidays) "·跳假" else ""

    private fun repeatLabel(a: Automation): String = when (a.repeatMode) {
        RepeatMode.DAILY -> "每天"
        RepeatMode.WEEKDAYS -> "工作日"
        RepeatMode.WEEKEND -> "周末"
        RepeatMode.CUSTOM -> customDaysLabel(a.repeatDays)
        else -> "一次"
    }

    /** 星期位图 → 「自定义(一三五)」；bit (DAY_OF_WEEK-1)，bit0 是周日。 */
    private fun customDaysLabel(mask: Int): String {
        val m = mask and 0x7F
        if (m == 0) return "自定义"
        val picks = (0..6).filter { (m shr it) and 1 == 1 }.joinToString("") { DAY_LABELS[it] }
        return if (picks.isEmpty()) "自定义" else "自定义($picks)"
    }
}
