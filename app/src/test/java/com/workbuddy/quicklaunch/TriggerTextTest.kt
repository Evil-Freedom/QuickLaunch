package com.workbuddy.quicklaunch

import com.workbuddy.quicklaunch.data.Automation
import com.workbuddy.quicklaunch.data.RepeatMode
import com.workbuddy.quicklaunch.data.ShiftAnchor
import com.workbuddy.quicklaunch.data.TriggerType
import com.workbuddy.quicklaunch.util.TriggerText
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 规则卡片上的「触发条件」文案。
 *
 * 这里的每个断言都对应真机上出现过或差点出错的显示口径：
 * 随机窗口曾把被忽略的 timeHour 印成「定时 10:23」，看上去像一条固定时刻的规则。
 */
class TriggerTextTest {

    private fun rule(
        triggerType: String = TriggerType.TIME,
        hour: Int = 9,
        minute: Int = 0,
        repeatMode: String = RepeatMode.DAILY,
        days: Int = 0,
        skipHolidays: Boolean = false,
        randomWindow: Boolean = false,
        windowStartMin: Int = 0,
        windowEndMin: Int = 0,
        shiftAnchor: Int = ShiftAnchor.FIXED,
        shiftOffsetMin: Int = 0,
        wifiName: String = "",
        bluetoothName: String = ""
    ) = Automation(
        name = "测试",
        targetPackage = "pkg",
        targetAppName = "测试",
        triggerType = triggerType,
        timeHour = hour,
        timeMinute = minute,
        repeatMode = repeatMode,
        repeatDays = days,
        skipHolidays = skipHolidays,
        bluetoothName = bluetoothName,
        wifiName = wifiName,
        randomWindow = randomWindow,
        windowStartMin = windowStartMin,
        windowEndMin = windowEndMin,
        shiftAnchor = shiftAnchor,
        shiftOffsetMin = shiftOffsetMin
    )

    @Test
    fun `随机窗口生效时不再显示被忽略的固定时刻`() {
        // 真机 #7：randomWindow=true 时 timeHour=10:23 完全不参与调度，
        // 卡片必须显示窗口，否则用户会以为它 10:23 触发。
        val text = TriggerText.of(
            rule(
                hour = 10, minute = 23, repeatMode = RepeatMode.CUSTOM, days = 0b0011110,
                skipHolidays = true, randomWindow = true, windowStartMin = 525, windowEndMin = 535
            )
        )
        assertEquals("随机 08:45–08:55（自定义(一二三四)）·跳假", text)
    }

    @Test
    fun `关掉随机窗口后回到固定时刻`() {
        val text = TriggerText.of(
            rule(hour = 10, minute = 23, repeatMode = RepeatMode.CUSTOM, days = 0b0011110, skipHolidays = true)
        )
        assertEquals("定时 10:23（自定义(一二三四)）·跳假", text)
    }

    @Test
    fun `凌晨窗口仍按两位补零`() {
        val text = TriggerText.of(rule(randomWindow = true, windowStartMin = 0, windowEndMin = 5))
        assertEquals("随机 00:00–00:05（每天）", text)
    }

    @Test
    fun `重复模式各自的文案`() {
        assertEquals("定时 09:00（每天）", TriggerText.of(rule()))
        assertEquals("定时 09:00（工作日）", TriggerText.of(rule(repeatMode = RepeatMode.WEEKDAYS)))
        assertEquals("定时 09:00（周末）", TriggerText.of(rule(repeatMode = RepeatMode.WEEKEND)))
        assertEquals("定时 09:00（一次）", TriggerText.of(rule(repeatMode = RepeatMode.ONCE)))
    }

    @Test
    fun `自定义位图为 0 时回退为自定义`() {
        assertEquals("定时 09:00（自定义）", TriggerText.of(rule(repeatMode = RepeatMode.CUSTOM, days = 0)))
    }

    @Test
    fun `位图按 bit-DAY_OF_WEEK-1 解码`() {
        // 42 = 0b0101010 -> 周一、周三、周五（Automation 注释里的例子）
        assertEquals(
            "定时 09:00（自定义(一三五)）",
            TriggerText.of(rule(repeatMode = RepeatMode.CUSTOM, days = 42))
        )
        // 124 = 二三四五六，60 = 二三四五
        assertEquals(
            "定时 09:00（自定义(二三四五六)）",
            TriggerText.of(rule(repeatMode = RepeatMode.CUSTOM, days = 124))
        )
        assertEquals(
            "定时 09:00（自定义(二三四五)）",
            TriggerText.of(rule(repeatMode = RepeatMode.CUSTOM, days = 60))
        )
    }

    @Test
    fun `班表锚点优先于随机窗口`() {
        // 班表模式的触发由当天班次决定，随机窗口不参与，文案必须说班表
        val text = TriggerText.of(
            rule(randomWindow = true, windowStartMin = 525, windowEndMin = 535,
                shiftAnchor = ShiftAnchor.CLOCK_IN, shiftOffsetMin = -30)
        )
        assertEquals("班表 上班前 30 分钟", text)
    }

    @Test
    fun `非定时触发的文案保持不变`() {
        assertEquals("充电时", TriggerText.of(rule(triggerType = TriggerType.CHARGING)))
        assertEquals("连接 WiFi:家里", TriggerText.of(rule(triggerType = TriggerType.WIFI, wifiName = "家里")))
        assertEquals("连接 WiFi", TriggerText.of(rule(triggerType = TriggerType.WIFI)))
        assertEquals("连接蓝牙:车机", TriggerText.of(rule(triggerType = TriggerType.BLUETOOTH, bluetoothName = "车机")))
        assertEquals("连接蓝牙", TriggerText.of(rule(triggerType = TriggerType.BLUETOOTH)))
    }
}
