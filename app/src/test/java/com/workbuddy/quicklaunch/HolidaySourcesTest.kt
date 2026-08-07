package com.workbuddy.quicklaunch.util

import org.junit.Assert.*
import org.junit.Test

class HolidaySourcesTest {

    @Test
    fun `默认顺序包含全部三个内置数据源`() {
        assertEquals(3, HolidaySources.ALL.size)
        assertEquals("timor", HolidaySources.ALL[0].id)
    }

    @Test
    fun `自动模式下上次成功源前置`() {
        val order = HolidaySources.ordered(null, "natescarlet_cdn", emptyList())
        assertEquals("natescarlet_cdn", order[0].id)
        assertEquals(3, order.size)
    }

    @Test
    fun `指定偏好源排最前且上次成功源其次`() {
        val order = HolidaySources.ordered("timor", "natescarlet_cdn", emptyList())
        assertEquals("timor", order[0].id)
        assertEquals("natescarlet_cdn", order[1].id)
        assertEquals(3, order.size)
    }

    @Test
    fun `未知偏好回退到默认顺序`() {
        val order = HolidaySources.ordered("nonexistent", null, emptyList())
        assertEquals("timor", order[0].id)
        assertEquals(3, order.size)
    }

    @Test
    fun `自定义源进入尝试顺序且可被指定为偏好`() {
        val custom = listOf(
            CustomSource("mine", "我的源", "https://x.com/{year}.json", ParserType.NATE_SCARLET)
                .toHolidaySource()
        )
        val order = HolidaySources.ordered("mine", null, custom)
        assertEquals("mine", order[0].id)
        assertEquals(4, order.size)
    }

    @Test
    fun `自定义源URL模板正确替换year`() {
        val src = CustomSource("mine", "我的源", "https://x.com/{year}.json", ParserType.NATE_SCARLET)
            .toHolidaySource()
        assertEquals("https://x.com/2026.json", src.urlForYear(2026))
    }

    @Test
    fun `自定义源按所选解析器解析`() {
        // timor 格式
        val timorSrc = CustomSource("a", "a", "https://x/{year}.json", ParserType.TIMOR).toHolidaySource()
        val timorJson = """{"code":0,"holiday":{"2026-01-01":{"holiday":true,"name":"元旦"}}}"""
        val timorOut = timorSrc.parse(timorJson)
        assertEquals(1, timorOut.size)
        assertEquals("2026-01-01", timorOut[0].date)

        // holiday-cn 格式
        val nateSrc = CustomSource("b", "b", "https://x/{year}.json", ParserType.NATE_SCARLET).toHolidaySource()
        val nateJson = """{"days":[{"date":"2026-10-01","isOffDay":true,"name":"国庆"}]}"""
        val nateOut = nateSrc.parse(nateJson)
        assertEquals(1, nateOut.size)
        assertEquals("2026-10-01", nateOut[0].date)
    }
}
