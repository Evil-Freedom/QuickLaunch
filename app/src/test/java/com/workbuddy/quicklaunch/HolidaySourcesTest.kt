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
        // timor 真实格式：键是 MM-dd，完整日期在 date 字段
        val timorSrc = CustomSource("a", "a", "https://x/{year}.json", ParserType.TIMOR).toHolidaySource()
        val timorJson =
            """{"code":0,"holiday":{"01-01":{"holiday":true,"name":"元旦","date":"2026-01-01"}}}"""
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

    @Test
    fun `timor解析器兼容键为完整日期的镜像`() {
        val src = CustomSource("a", "a", "https://x/{year}.json", ParserType.TIMOR).toHolidaySource()
        val json = """{"code":0,"holiday":{"2026-01-01":{"holiday":true,"name":"元旦"}}}"""
        val out = src.parse(json)
        assertEquals(1, out.size)
        assertEquals("2026-01-01", out[0].date)
    }

    @Test
    fun `timor解析器剔除调休补班日与非法日期`() {
        val src = CustomSource("a", "a", "https://x/{year}.json", ParserType.TIMOR).toHolidaySource()
        val json = """
            {"code":0,"holiday":{
              "01-01":{"holiday":true,"name":"元旦","date":"2026-01-01"},
              "01-04":{"holiday":false,"name":"元旦前补班","date":"2026-01-04"},
              "02-30":{"holiday":true,"name":"脏数据","date":"2026-02-32"},
              "03-01":{"holiday":true,"name":"缺日期"}
            }}
        """.trimIndent()
        val out = src.parse(json)
        // 补班日剔除；非法日期剔除；键非完整日期且无 date 字段的剔除
        assertEquals(1, out.size)
        assertEquals("2026-01-01", out[0].date)
    }

    @Test
    fun `holidaycn解析器剔除空日期与补班日`() {
        val src = CustomSource("b", "b", "https://x/{year}.json", ParserType.NATE_SCARLET).toHolidaySource()
        val json = """
            {"days":[
              {"date":"2026-10-01","isOffDay":true,"name":"国庆"},
              {"date":"2026-10-11","isOffDay":false,"name":"补班"},
              {"date":"","isOffDay":true,"name":"空"}
            ]}
        """.trimIndent()
        val out = src.parse(json)
        assertEquals(1, out.size)
        assertEquals("2026-10-01", out[0].date)
    }

    @Test
    fun `解析器对畸形JSON返回空而不抛异常`() {
        val timorSrc = CustomSource("a", "a", "https://x/{year}.json", ParserType.TIMOR).toHolidaySource()
        val nateSrc = CustomSource("b", "b", "https://x/{year}.json", ParserType.NATE_SCARLET).toHolidaySource()
        for (bad in listOf("", "not json", "{}", "[]", """{"code":1}""", """{"code":0}""")) {
            assertTrue(timorSrc.parse(bad).isEmpty())
            assertTrue(nateSrc.parse(bad).isEmpty())
        }
    }

    @Test
    fun `偏好源与上次成功源相同时不重复`() {
        val order = HolidaySources.ordered("timor", "timor", emptyList())
        assertEquals(3, order.size)
        assertEquals("timor", order[0].id)
        assertEquals(3, order.map { it.id }.toSet().size)
    }

    @Test
    fun `自定义源与内置源id冲突时去重`() {
        val custom = listOf(
            CustomSource("timor", "冒名源", "https://x.com/{year}.json", ParserType.TIMOR).toHolidaySource()
        )
        val order = HolidaySources.ordered(null, null, custom)
        assertEquals(3, order.size)
        assertEquals(3, order.map { it.id }.toSet().size)
    }
}
