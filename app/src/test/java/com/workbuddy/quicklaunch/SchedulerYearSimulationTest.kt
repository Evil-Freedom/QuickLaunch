package com.workbuddy.quicklaunch

import com.workbuddy.quicklaunch.data.Automation
import com.workbuddy.quicklaunch.data.Holiday
import com.workbuddy.quicklaunch.data.TriggerType
import com.workbuddy.quicklaunch.util.HolidayChecker
import com.workbuddy.quicklaunch.util.HolidaySources
import com.workbuddy.quicklaunch.util.Scheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 全年推演：模拟「重复任务每次触发后重排下一次」的完整过程。
 *
 * 这是最接近真机行为的验证方式 —— App 里 [Scheduler.schedule] 就是在每次触发后
 * 重新调用 [Scheduler.nextTriggerTime] 排下一次。因此用真实 2026 年数据
 * 从元旦一路推到年底，就能看出调休日在真实数据下到底会不会被触发。
 */
class SchedulerYearSimulationTest {

    /** 2026 全年调休上班日（来自 holiday-cn 真实数据）。 */
    private val makeupDays2026 = setOf(
        "2026-01-04", "2026-02-14", "2026-02-28",
        "2026-05-09", "2026-09-20", "2026-10-10"
    )

    /** 2026 全年法定休息日（来自 holiday-cn 真实数据）。 */
    private val restDays2026 = setOf(
        "2026-01-01", "2026-01-02", "2026-01-03",
        "2026-02-15", "2026-02-16", "2026-02-17", "2026-02-18",
        "2026-02-19", "2026-02-20", "2026-02-21", "2026-02-22",
        "2026-04-04", "2026-04-05", "2026-04-06",
        "2026-05-01", "2026-05-02", "2026-05-03", "2026-05-04", "2026-05-05",
        "2026-06-19", "2026-06-20", "2026-06-21",
        "2026-09-25", "2026-09-26", "2026-09-27",
        "2026-10-01", "2026-10-02", "2026-10-03", "2026-10-04",
        "2026-10-05", "2026-10-06", "2026-10-07"
    )

    /**
     * 真实 holiday-cn JSON（与 HolidayIntegrationTest 同源）。
     * 这里只需 days 数组，用最小可用子集重放给真实解析器。
     */
    private val realDaysJson: String = buildString {
        append("{\"days\":[")
        val entries = mutableListOf<String>()
        restDays2026.sorted().forEach {
            entries += """{"name":"休","date":"$it","isOffDay":true}"""
        }
        makeupDays2026.sorted().forEach {
            entries += """{"name":"休","date":"$it","isOffDay":false}"""
        }
        append(entries.joinToString(","))
        append("]}")
    }

    private fun checker(): HolidayChecker {
        val src = HolidaySources.ALL.first { it.id == "natescarlet_raw" }
        val all: List<Holiday> = src.parse(realDaysJson)
        return HolidayChecker(
            restDates = all.filter { !it.isWorkday }.map { it.date }.toSet(),
            workdays = all.filter { it.isWorkday }.map { it.date }.toSet()
        )
    }

    private fun rule(mode: String, h: Int, m: Int) = Automation(
        name = "t", targetPackage = "p", targetAppName = "n",
        triggerType = TriggerType.TIME, timeHour = h, timeMinute = m, repeatMode = mode
    )

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

    /**
     * 模拟 App 的真实重排过程：从 start 起反复调用 nextTriggerTimeFrom，
     * 每次把上次结果当作新的「现在」，直到跨出 2026 年。
     */
    private fun simulateYear(
        mode: String,
        skipHolidays: Boolean,
        startAt: String = "2026-01-01",
        maxIterations: Int = 400
    ): List<String> {
        val c = checker()
        val shouldSkip: (Calendar) -> Boolean =
            if (skipHolidays) { cal -> c.isHoliday(cal) } else { { false } }
        val forceRun: (Calendar) -> Boolean = { cal -> c.isMakeupWorkday(cal) }

        val hits = mutableListOf<String>()
        var cursor = calAt(startAt, hour = 6)
        repeat(maxIterations) {
            val t = Scheduler.nextTriggerTimeFrom(
                rule(mode, 7, 0),
                startFrom = cursor,
                shouldSkip = shouldSkip,
                forceRun = forceRun
            )
            val hit = calOf(t)
            if (hit.get(Calendar.YEAR) > 2026) return hits
            hits += dateKeyOf(hit)
            cursor = hit
        }
        return hits
    }

    private fun calOf(ms: Long) = Calendar.getInstance().apply { timeInMillis = ms }

    // ══════════════════════════════════════════════════════════════════
    // 核心断言：全年推演中，6 个调休日必须全部被触发
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `仅工作日规则全年推演命中全部六个调休日`() {
        val hits = simulateYear("weekdays", skipHolidays = false)
        val hitSet = hits.toSet()

        val missed = makeupDays2026.filter { it !in hitSet }
        assertTrue(
            "全年推演漏掉调休日: $missed（共触发 ${hits.size} 次）",
            missed.isEmpty()
        )
    }

    @Test
    fun `勾选跳过节假日后全年推演仍命中全部六个调休日`() {
        val hits = simulateYear("weekdays", skipHolidays = true)
        val hitSet = hits.toSet()

        val missed = makeupDays2026.filter { it !in hitSet }
        assertTrue(
            "勾选跳过后漏掉调休日: $missed（共触发 ${hits.size} 次）",
            missed.isEmpty()
        )
    }

    @Test
    fun `勾选跳过节假日时全年不落在任何休息日`() {
        val hits = simulateYear("weekdays", skipHolidays = true)
        val leaked = hits.filter { it in restDays2026 }
        assertTrue("落在休息日的日期: $leaked", leaked.isEmpty())
    }

    @Test
    fun `不勾选跳过时调休日与普通工作日一起被正常选中`() {
        // 不勾选时，调休日就是「多出来的一天工作日」，应当与普通工作日同等对待
        val hits = simulateYear("weekdays", skipHolidays = false)
        val hitSet = hits.toSet()
        makeupDays2026.forEach {
            assertTrue("$it 未被选中", it in hitSet)
        }
        // 抽查几个普通工作日也被选中（说明不是只挑调休日）
        listOf("2026-01-05", "2026-03-02", "2026-11-02").forEach {
            assertTrue("普通工作日 $it 未被选中", it in hitSet)
        }
    }

    @Test
    fun `全年触发次数在合理区间`() {
        // 2026 全年 365 天，weekdays 规则下加上 6 个调休日，
        // 触发次数应在 250~265 之间（工作日本身约 250 天）
        val hits = simulateYear("weekdays", skipHolidays = false)
        assertTrue("触发次数异常: ${hits.size}", hits.size in 240..270)
    }

    @Test
    fun `每天的规则全年推演次数符合预期`() {
        // daily 规则应触发约 365 次（不勾选跳过时一天不落）
        val hits = simulateYear("daily", skipHolidays = false)
        assertTrue("daily 触发次数异常: ${hits.size}", hits.size in 360..366)
    }

    @Test
    fun `勾选跳过时 daily 规则会跳过全部休息日`() {
        val hits = simulateYear("daily", skipHolidays = true)
        val leaked = hits.filter { it in restDays2026 }
        assertTrue("daily 勾选跳过仍落在休息日: $leaked", leaked.isEmpty())
        // 365 - 33 个休息日 = 332
        assertTrue("daily 勾选跳过次数异常: ${hits.size}", hits.size in 325..340)
    }

    @Test
    fun `春节调休日 0214 和 0228 都被选中`() {
        // 春节是调休最密集的时段，单独验证
        val hits = simulateYear("weekdays", skipHolidays = true).toSet()
        assertTrue("2026-02-14 未触发", "2026-02-14" in hits)
        assertTrue("2026-02-28 未触发", "2026-02-28" in hits)
    }

    @Test
    fun `国庆调休日 0920 在此前一天推演时被选中`() {
        // 逐日推演的最后一跳：从 09-19（周六）出发应直接落到 09-20 这个调休周日
        val c = checker()
        val t = Scheduler.nextTriggerTimeFrom(
            rule("weekdays", 7, 0),
            startFrom = calAt("2026-09-19", hour = 6),
            shouldSkip = { cal -> c.isHoliday(cal) },
            forceRun = { cal -> c.isMakeupWorkday(cal) }
        )
        assertEquals("未落到国庆调休日", "2026-09-20", dateKeyOf(calOf(t)))
    }

    // ══════════════════════════════════════════════════════════════════
    // once 规则不受调休影响（用户明确要求）
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `once 规则不受调休日影响`() {
        // 起点是调休日当天之前一天，once 规则只按「下一个时刻」走，与调休无关
        val c = checker()
        val t = Scheduler.nextTriggerTimeFrom(
            rule("once", 7, 0),
            startFrom = calAt("2026-01-03", hour = 6),
            shouldSkip = { cal -> c.isHoliday(cal) },
            forceRun = { cal -> c.isMakeupWorkday(cal) }
        )
        // 2026-01-03 06:00 早于规则 07:00 → 当天就是候选，与 01-04 是否调休无关
        assertEquals("once 被调休日影响了", "2026-01-03", dateKeyOf(calOf(t)))
    }

    @Test
    fun `once 规则不会因为遇到休息日而顺延`() {
        // 起点 2026-02-15（春节休息日）之前，规则时刻已过 → 顺延一天到 02-15 当天，
        // 即便 02-15 是法定休息日也照常触发（once 语义就是「定好那一天就那一天」）
        val c = checker()
        val t = Scheduler.nextTriggerTimeFrom(
            rule("once", 7, 0),
            startFrom = calAt("2026-02-14", hour = 23),
            shouldSkip = { cal -> c.isHoliday(cal) },
            forceRun = { cal -> c.isMakeupWorkday(cal) }
        )
        assertEquals("once 被跳过规则影响了", "2026-02-15", dateKeyOf(calOf(t)))
    }
}
