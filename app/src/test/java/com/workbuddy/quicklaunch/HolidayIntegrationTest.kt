package com.workbuddy.quicklaunch

import com.workbuddy.quicklaunch.data.Automation
import com.workbuddy.quicklaunch.data.Holiday
import com.workbuddy.quicklaunch.data.RepeatMode
import com.workbuddy.quicklaunch.data.TriggerType
import com.workbuddy.quicklaunch.util.HolidayChecker
import com.workbuddy.quicklaunch.util.HolidaySources
import com.workbuddy.quicklaunch.util.Scheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 用**真实 2026 年节假日数据**做的端到端验证。
 *
 * 与 SchedulerTest 的区别：那边用构造出来的假数据验证算法分支，
 * 这边直接用 holiday-cn 线上数据源的真实响应，走完整链路：
 *   真实 JSON → 解析器 → Holiday 列表 → HolidayChecker → Scheduler 排程
 *
 * 目的：确认「哪怕没勾选跳过节假日，遇到调休也自动运行」在真实数据下真的成立，
 * 而不是只在造出来的测试数据里成立。
 */
class HolidayIntegrationTest {

    /**
     * holiday-cn 数据源在 2026 年的真实响应（关键字段节选，非全量）。
     * 完整数据 39 条：33 个休息日 + 6 个调休上班日。
     *
     * 下面覆盖了全部 6 个调休上班日，用于验证它们都能被正确识别。
     */
    private val real2026Json = """
    {
      "year": 2026,
      "papers": [],
      "days": [
        { "name": "元旦", "date": "2026-01-01", "isOffDay": true },
        { "name": "元旦", "date": "2026-01-02", "isOffDay": true },
        { "name": "元旦", "date": "2026-01-03", "isOffDay": true },
        { "name": "元旦", "date": "2026-01-04", "isOffDay": false },
        { "name": "春节", "date": "2026-02-14", "isOffDay": false },
        { "name": "春节", "date": "2026-02-15", "isOffDay": true },
        { "name": "春节", "date": "2026-02-16", "isOffDay": true },
        { "name": "春节", "date": "2026-02-17", "isOffDay": true },
        { "name": "春节", "date": "2026-02-18", "isOffDay": true },
        { "name": "春节", "date": "2026-02-19", "isOffDay": true },
        { "name": "春节", "date": "2026-02-20", "isOffDay": true },
        { "name": "春节", "date": "2026-02-21", "isOffDay": true },
        { "name": "春节", "date": "2026-02-22", "isOffDay": true },
        { "name": "春节", "date": "2026-02-28", "isOffDay": false },
        { "name": "清明节", "date": "2026-04-04", "isOffDay": true },
        { "name": "清明节", "date": "2026-04-05", "isOffDay": true },
        { "name": "清明节", "date": "2026-04-06", "isOffDay": true },
        { "name": "劳动节", "date": "2026-05-01", "isOffDay": true },
        { "name": "劳动节", "date": "2026-05-02", "isOffDay": true },
        { "name": "劳动节", "date": "2026-05-03", "isOffDay": true },
        { "name": "劳动节", "date": "2026-05-04", "isOffDay": true },
        { "name": "劳动节", "date": "2026-05-05", "isOffDay": true },
        { "name": "劳动节", "date": "2026-05-09", "isOffDay": false },
        { "name": "端午节", "date": "2026-06-19", "isOffDay": true },
        { "name": "端午节", "date": "2026-06-20", "isOffDay": true },
        { "name": "端午节", "date": "2026-06-21", "isOffDay": true },
        { "name": "中秋节", "date": "2026-09-25", "isOffDay": true },
        { "name": "中秋节", "date": "2026-09-26", "isOffDay": true },
        { "name": "中秋节", "date": "2026-09-27", "isOffDay": true },
        { "name": "国庆节", "date": "2026-09-20", "isOffDay": false },
        { "name": "国庆节", "date": "2026-10-01", "isOffDay": true },
        { "name": "国庆节", "date": "2026-10-02", "isOffDay": true },
        { "name": "国庆节", "date": "2026-10-03", "isOffDay": true },
        { "name": "国庆节", "date": "2026-10-04", "isOffDay": true },
        { "name": "国庆节", "date": "2026-10-05", "isOffDay": true },
        { "name": "国庆节", "date": "2026-10-06", "isOffDay": true },
        { "name": "国庆节", "date": "2026-10-07", "isOffDay": true },
        { "name": "国庆节", "date": "2026-10-10", "isOffDay": false },
        { "name": "元旦", "date": "2027-01-01", "isOffDay": true },
        { "name": "元旦", "date": "2027-01-02", "isOffDay": true },
        { "name": "元旦", "date": "2027-01-03", "isOffDay": true }
      ]
    }
    """.trimIndent()

    /** 真实数据的 6 个调休上班日（全部是周六或周日），本次功能的目标。 */
    private val realMakeupDays = listOf(
        "2026-01-04", // 周日
        "2026-02-14", // 周六
        "2026-02-28", // 周六
        "2026-05-09", // 周六
        "2026-09-20", // 周日
        "2026-10-10"  // 周六
    )

    /** 走真实解析器把 JSON 变成 Holiday 列表。 */
    private fun parseReal(): List<Holiday> {
        val src = HolidaySources.ALL.first { it.id == "natescarlet_raw" }
        return src.parse(real2026Json)
    }

    private fun checker(): HolidayChecker {
        val all = parseReal()
        return HolidayChecker(
            restDates = all.filter { !it.isWorkday }.map { it.date }.toSet(),
            workdays = all.filter { it.isWorkday }.map { it.date }.toSet()
        )
    }

    private fun rule(mode: String, h: Int = 7, m: Int = 0) = Automation(
        name = "t", targetPackage = "p", targetAppName = "n",
        triggerType = TriggerType.TIME, timeHour = h, timeMinute = m, repeatMode = mode
    )

    private fun calOf(ms: Long) = Calendar.getInstance().apply { timeInMillis = ms }

    private fun calAt(date: String, hour: Int = 6): Calendar {
        val (y, mo, d) = date.split("-").map { it.toInt() }
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, y); set(Calendar.MONTH, mo - 1); set(Calendar.DAY_OF_MONTH, d)
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
    }

    private fun dateKeyOf(c: Calendar): String = "%04d-%02d-%02d".format(
        c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)
    )

    // ══════════════════════════════════════════════════════════════════
    // 第一层：真实数据能否被正确解析成两类
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `真实数据解析出六个调休上班日`() {
        val parsed = parseReal()
        val makeup = parsed.filter { it.isWorkday }.map { it.date }.sorted()
        assertEquals("调休上班日数量不符", realMakeupDays.sorted(), makeup)
    }

    @Test
    fun `调休上班日全部落在周六或周日`() {
        // 这是调休的本质：用周末补班。若解析结果里出现工作日，说明 isWorkday 语义被搞反了
        realMakeupDays.forEach { d ->
            val wd = calAt(d).get(Calendar.DAY_OF_WEEK)
            assertTrue(
                "$d 是工作日，不该被标为调休补班日",
                wd == Calendar.SATURDAY || wd == Calendar.SUNDAY
            )
        }
    }

    @Test
    fun `两类日期互不重叠`() {
        val c = checker()
        realMakeupDays.forEach { d ->
            assertTrue("$d 同时被当成休息日", !c.isHoliday(calAt(d)))
            assertTrue("$d 未被识别为调休日", c.isMakeupWorkday(calAt(d)))
        }
        // 反向抽查几个休息日
        listOf("2026-10-01", "2026-02-17", "2026-06-19").forEach { d ->
            assertTrue("$d 未被识别为休息日", c.isHoliday(calAt(d)))
            assertFalse("$d 被误判为调休日", c.isMakeupWorkday(calAt(d)))
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 第二层：真实数据下，调休日真的能被排程选中（本次需求的核心）
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `国庆调休日 2026-09-20 周日 被仅工作日规则选中`() {
        // 2026-09-20 是周日，规则是「仅工作日」；但真实数据标它为调休补班日 → 必须触发
        val c = checker()
        val target = calAt("2026-09-20")
        assertTrue("数据未把 9-20 标为调休日，用例前提不成立", c.isMakeupWorkday(target))

        val t = Scheduler.nextTriggerTimeFrom(
            rule("weekdays"),
            startFrom = calAt("2026-09-20", hour = 6),
            shouldSkip = { false },               // 关键：没勾选「跳过节假日」
            forceRun = { cal -> c.isMakeupWorkday(cal) }
        )
        assertEquals("国庆调休日没被选中，被推到工作日了", "2026-09-20", dateKeyOf(calOf(t)))
        assertEquals("未落在周日", Calendar.SUNDAY, calOf(t).get(Calendar.DAY_OF_WEEK))
    }

    @Test
    fun `春节调休日 2026-02-14 周六 被仅工作日规则选中`() {
        val c = checker()
        val t = Scheduler.nextTriggerTimeFrom(
            rule("weekdays"),
            startFrom = calAt("2026-02-14", hour = 6),
            shouldSkip = { false },
            forceRun = { cal -> c.isMakeupWorkday(cal) }
        )
        assertEquals("春节调休日没被选中", "2026-02-14", dateKeyOf(calOf(t)))
        assertEquals(Calendar.SATURDAY, calOf(t).get(Calendar.DAY_OF_WEEK))
    }

    @Test
    fun `劳动节调休日 2026-05-09 周六 被仅工作日规则选中`() {
        val c = checker()
        val t = Scheduler.nextTriggerTimeFrom(
            rule("weekdays"),
            startFrom = calAt("2026-05-09", hour = 6),
            shouldSkip = { false },
            forceRun = { cal -> c.isMakeupWorkday(cal) }
        )
        assertEquals("劳动节调休日没被选中", "2026-05-09", dateKeyOf(calOf(t)))
    }

    @Test
    fun `每个真实调休日都能被选中`() {
        val c = checker()
        realMakeupDays.forEach { d ->
            val t = Scheduler.nextTriggerTimeFrom(
                rule("weekdays"),
                startFrom = calAt(d, hour = 6),
                shouldSkip = { false },
                forceRun = { cal -> c.isMakeupWorkday(cal) }
            )
            assertEquals("$d 未被排程选中", d, dateKeyOf(calOf(t)))
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 第三层：勾选了跳过节假日时，调休日仍优先（真实数据下的冲突场景）
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `勾选跳过节假日后调休日依然触发`() {
        // 真实场景：用户勾了「跳过节假日」，9-30 是普通工作日、10-01..10-07 是国庆假期。
        // 起点设在 9-30 当天但**已过规则时刻**（23:00 > 07:00），
        // 于是当天不可用，必须跳过 10-01..10-07 的全部休息日，落到假期后第一个工作日 10-08。
        val c = checker()
        val t = Scheduler.nextTriggerTimeFrom(
            rule("daily"),
            startFrom = calAt("2026-09-30", hour = 23),
            shouldSkip = { cal -> c.isHoliday(cal) },
            forceRun = { cal -> c.isMakeupWorkday(cal) }
        )
        assertEquals("国庆假期没被跳过", "2026-10-08", dateKeyOf(calOf(t)))
    }

    @Test
    fun `假期前一天时刻未到时当天就是候选`() {
        // 反向确认上一条的前提：起点 9-30 06:00 早于规则 07:00，
        // 9-30 是普通工作日且不在休息日集合里 → 当天就该被选中，不需要等到假期后
        val c = checker()
        val t = Scheduler.nextTriggerTimeFrom(
            rule("daily"),
            startFrom = calAt("2026-09-30", hour = 6),
            shouldSkip = { cal -> c.isHoliday(cal) },
            forceRun = { cal -> c.isMakeupWorkday(cal) }
        )
        assertEquals("普通工作日当天未被选中", "2026-09-30", dateKeyOf(calOf(t)))
    }

    @Test
    fun `国庆假期中间的调休日 2026-10-10 能突破跳过规则`() {
        // 从 10-08 起，10-09 是周五（工作日），10-10 是周六但为调休补班日
        val c = checker()
        val t = Scheduler.nextTriggerTimeFrom(
            rule("weekdays"),
            startFrom = calAt("2026-10-10", hour = 6),
            shouldSkip = { cal -> c.isHoliday(cal) },
            forceRun = { cal -> c.isMakeupWorkday(cal) }
        )
        assertEquals("国庆调休日 10-10 未被选中", "2026-10-10", dateKeyOf(calOf(t)))
    }

    // ══════════════════════════════════════════════════════════════════
    // 第四层：反向验证 —— 没调休数据时行为不变（回归保护）
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `无节假日数据时工作日规则不落在周末`() {
        // 模拟「用户从未同步过节假日」：checker 为空 → forceRun 恒 false
        val empty = HolidayChecker.EMPTY
        assertTrue("空 checker 应为空", empty.isEmpty())

        val t = Scheduler.nextTriggerTimeFrom(
            rule("weekdays"),
            startFrom = calAt("2026-09-18", hour = 6),
            shouldSkip = { false },
            forceRun = { cal -> empty.isMakeupWorkday(cal) }
        )
        val d = calOf(t).get(Calendar.DAY_OF_WEEK)
        assertTrue("落到了周末 $d", d != Calendar.SATURDAY && d != Calendar.SUNDAY)
    }

    @Test
    fun `调休日已过时刻时顺延而不是卡死`() {
        // 调休日当天但规则时刻已过（起点 23:00 > 规则 07:00）→ 必须顺延到未来
        val c = checker()
        val start = calAt("2026-09-20", hour = 23)
        val t = Scheduler.nextTriggerTimeFrom(
            rule("weekdays"),
            startFrom = start,
            shouldSkip = { false },
            forceRun = { cal -> c.isMakeupWorkday(cal) }
        )
        assertTrue("算出了过去时间", t > start.timeInMillis)
        // 9-21 是周一，顺延后应落到 9-21
        assertEquals("2026-09-21", dateKeyOf(calOf(t)))
    }

    @Test
    fun `调休日不会让排程越过中间的普通工作日`() {
        // 9-20 是调休日但还早：9-18（周五）往前排，应正常落在 9-18 而不被远方的调休日抢占
        val c = checker()
        val t = Scheduler.nextTriggerTimeFrom(
            rule("weekdays"),
            startFrom = calAt("2026-09-18", hour = 6),
            shouldSkip = { false },
            forceRun = { cal -> c.isMakeupWorkday(cal) }
        )
        assertEquals("被远方的调休日抢占了", "2026-09-18", dateKeyOf(calOf(t)))
    }

    /** 二三四五（周二~周五）位图，与设备上真实规则 [2]「飞书 18:00」一致。 */
    private val tueToFri = (1 shl (Calendar.TUESDAY - 1)) or
            (1 shl (Calendar.WEDNESDAY - 1)) or
            (1 shl (Calendar.THURSDAY - 1)) or
            (1 shl (Calendar.FRIDAY - 1))

    private fun customRule() = Automation(
        name = "t", targetPackage = "p", targetAppName = "n",
        triggerType = TriggerType.TIME, timeHour = 7, timeMinute = 0,
        repeatMode = RepeatMode.CUSTOM, repeatDays = tueToFri
    )

    @Test
    fun `自定义星期没勾调休日也会被放行`() {
        // 设备实测同条件：规则「飞书 18:00 二三四五」在 2026-09-20（周日·调休）当天
        // 确实被排进了 AlarmManager（dumpsys 读到 origWhen=09-20 18:00）——
        // 而周日并不在该规则的位图里，说明**调休日优先于自定义星期过滤**，不只是优先于 weekdays。
        val c = checker()
        val t = Scheduler.nextTriggerTimeFrom(
            customRule(),
            startFrom = calAt("2026-09-20", hour = 6),
            shouldSkip = { cal -> c.isHoliday(cal) },
            forceRun = { cal -> c.isMakeupWorkday(cal) }
        )
        assertEquals("调休日被自定义星期过滤掉了", "2026-09-20", dateKeyOf(calOf(t)))
    }

    @Test
    fun `自定义星期在非调休周末依然被过滤`() {
        // 对照组：同一个位图落在普通周末（9-12 周六，既非调休也非节假日）→ 必须推到下周二。
        // 没有这一条，就可能把「无脑放行一切周末」的回归当成「调休功能生效」而放过去。
        val c = checker()
        val t = Scheduler.nextTriggerTimeFrom(
            customRule(),
            startFrom = calAt("2026-09-12", hour = 6),
            shouldSkip = { cal -> c.isHoliday(cal) },
            forceRun = { cal -> c.isMakeupWorkday(cal) }
        )
        assertEquals("普通周末没被星期位图过滤掉", "2026-09-15", dateKeyOf(calOf(t)))
    }
}
