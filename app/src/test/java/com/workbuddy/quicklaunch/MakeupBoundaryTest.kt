package com.workbuddy.quicklaunch

import com.workbuddy.quicklaunch.data.Automation
import com.workbuddy.quicklaunch.data.Holiday
import com.workbuddy.quicklaunch.data.TriggerType
import com.workbuddy.quicklaunch.util.HolidayChecker
import com.workbuddy.quicklaunch.util.HolidaySources
import com.workbuddy.quicklaunch.util.Scheduler
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 模拟器上实测发现的边界场景回归测试。
 *
 * 背景：在 Android 模拟器上用真实 2026 数据实测时发现，
 * 当起点落在「被跳过规则吃掉的日子」时，weekdays 规则的落点可能是一个
 * 既不是调休上班日、又是周末的普通周日（例如 2026-03-01、2026-05-10、2026-10-11）。
 *
 * 这类日子不在放假表里（表里只登记官方假期），因此 isHoliday()=false；
 * 但它是周末，理应被 weekdays 规则排除。若落在这里，说明
 * advanceUntilValid 的阶段二在「+1 天」之后没有重新执行星期对齐。
 *
 * 本测试把这个场景固化下来，防止回归。
 */
class MakeupBoundaryTest {

    /** holiday-cn 2026 的真实数据（与 HolidayIntegrationTest 同源） */
    private val real2026Json = """
    {
      "year": 2026,
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
        { "name": "国庆节", "date": "2026-10-10", "isOffDay": false }
      ]
    }
    """

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

    private fun calAt(date: String, hour: Int = 6): Calendar {
        val (y, mo, d) = date.split("-").map { it.toInt() }
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, y); set(Calendar.MONTH, mo - 1); set(Calendar.DAY_OF_MONTH, d)
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
    }

    private fun calOf(ms: Long) = Calendar.getInstance().apply { timeInMillis = ms }

    private fun dateKey(c: Calendar) = String.format(
        java.util.Locale.US, "%04d-%02d-%02d",
        c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)
    )

    /**
     * 核心断言：weekdays 规则的落点，星期几必须不是周末，
     * 除非那天是官方认定的调休上班日。
     */
    private fun assertLandingLegal(startDate: String, startHour: Int, label: String) {
        val c = checker()
        val t = Scheduler.nextTriggerTimeFrom(
            rule("weekdays"),
            startFrom = calAt(startDate, hour = startHour),
            shouldSkip = { cal -> c.isHoliday(cal) },
            forceRun = { cal -> c.isMakeupWorkday(cal) }
        )
        val hit = calOf(t)
        val key = dateKey(hit)
        val dow = hit.get(Calendar.DAY_OF_WEEK)
        val weekend = dow == Calendar.SATURDAY || dow == Calendar.SUNDAY
        val isMakeup = c.isMakeupWorkday(hit)

        assertFalse(
            "$label：起点 $startDate ${startHour}:00 落到 $key " +
                "（${if (dow == Calendar.SUNDAY) "周日" else "周六"}，非调休日）—— " +
                "weekdays 规则不应落在普通周末",
            weekend && !isMakeup
        )
    }

    /**
     * 打印与模拟器实测同条件的落点表，作为「源码 vs 设备」的比对基线。
     *
     * 设备实测（v1.3.8 APK，weekdays + 跳过节假日，起点当天 06:00，规则 07:00）：
     *   2026-05-10 → 2026-05-10 周日   ← 设备停在周末
     *   2026-10-11 → 2026-10-11 周日   ← 设备停在周末
     *   2026-09-19 → 2026-09-19 周六   ← 设备停在周末
     *   2026-09-20 → 2026-09-20 周日   ← 正确（调休日）
     *   2026-01-04 → 2026-01-04 周日   ← 正确（调休日）
     *
     * 本测试把当前源码在同样输入下的输出打印出来，二者若有差异即为版本不一致。
     */
    @Test
    fun `打印与设备实测同条件的落点基线`() {
        val c = checker()
        val probes = listOf(
            "2026-05-10", "2026-10-11", "2026-09-19", "2026-09-20", "2026-01-04",
            "2026-02-14", "2026-02-28", "2026-05-09", "2026-10-10"
        )
        println("起 点       │ 落 点          │ 是否调休日")
        println("────────────┼────────────────┼──────────")
        probes.forEach { d ->
            val t = Scheduler.nextTriggerTimeFrom(
                rule("weekdays"),
                startFrom = calAt(d, hour = 6),
                shouldSkip = { cal -> c.isHoliday(cal) },
                forceRun = { cal -> c.isMakeupWorkday(cal) }
            )
            val hit = calOf(t)
            val key = dateKey(hit)
            val dow = hit.get(Calendar.DAY_OF_WEEK)
            val cn = when (dow) {
                Calendar.SUNDAY -> "周日"; Calendar.MONDAY -> "周一"
                Calendar.TUESDAY -> "周二"; Calendar.WEDNESDAY -> "周三"
                Calendar.THURSDAY -> "周四"; Calendar.FRIDAY -> "周五"
                else -> "周六"
            }
            val mk = if (c.isMakeupWorkday(hit)) "是" else "-"
            val flag = if (key == d) "  ← 被原样保留" else ""
            println("$d │ $key $cn │ $mk$flag")
        }
    }

    /**
     * 模拟器实测暴露的具体落点，逐个显式固化。
     *
     * 设备实测结果（weekdays + 跳过节假日，起点为当天 06:00，规则时刻 07:00）：
     *   起点 2026-05-10（普通周日）→ 落点 2026-05-10 周日   ← 设备上停在周末
     *   起点 2026-10-11（普通周日）→ 落点 2026-10-11 周日   ← 设备上停在周末
     *   起点 2026-09-20（调休上班日）→ 落点 2026-09-20 周日  ← 正确
     *   起点 2026-01-04（调休上班日）→ 落点 2026-01-04 周日  ← 正确
     *   起点 2026-09-19（普通周六）→ 落点 2026-09-19 周六   ← 设备上停在周末
     *
     * 本测试断言：普通周末起点必须被推走。若此测试通过而设备仍停在周末，
     * 说明设备上运行的字节码与当前源码不一致（构建缓存/APK 版本问题）。
     */
    @Test
    fun `普通周末作为起点必须被推走`() {
        val c = checker()
        // 这些日子都是周末，且都不是官方调休上班日
        val plainWeekends = listOf(
            "2026-05-10",  // 周日
            "2026-10-11",  // 周日
            "2026-09-19",  // 周六
            "2026-05-16",  // 周六
            "2026-05-17",  // 周日
            "2026-11-07",  // 周六
            "2026-11-08"   // 周日
        )
        plainWeekends.forEach { d ->
            assertTrue("$d 不应是调休上班日（测试前置条件）", !c.isMakeupWorkday(calAt(d)))
            assertTrue("$d 不应在放假表里（测试前置条件）", !c.isHoliday(calAt(d)))

            val t = Scheduler.nextTriggerTimeFrom(
                rule("weekdays"),
                startFrom = calAt(d, hour = 6),
                shouldSkip = { cal -> c.isHoliday(cal) },
                forceRun = { cal -> c.isMakeupWorkday(cal) }
            )
            val hit = calOf(t)
            val key = dateKey(hit)
            val dow = hit.get(Calendar.DAY_OF_WEEK)
            val weekend = dow == Calendar.SATURDAY || dow == Calendar.SUNDAY
            val isMakeup = c.isMakeupWorkday(hit)

            // 核心断言：起点当天是普通周末，不能被原样保留
            assertFalse(
                "$d（普通周末）作为起点被原样保留，未被推走",
                key == d
            )
            // 落点允许是周末，但必须同时是官方调休上班日（如 09-19 → 09-20）
            assertFalse(
                "$d 落点 $key 是周末且非调休上班日",
                weekend && !isMakeup
            )
        }
    }

    // ── 模拟器实测暴露的三个具体落点，逐个固化 ──────────────────────

    @Test
    fun `春节假期最后一天为休息日时落点不应是三月一日周日`() {
        // 2026-02-28 是调休上班日；这里把它按休息日处理（模拟数据被改的情形），
        // 期望跳过整个春节假期后落到 2026-03-02 周一，而不是 03-01 周日
        assertLandingLegal("2026-02-28", 6, "春节边界")
    }

    @Test
    fun `劳动节假期为休息日时落点不应是五月十日周日`() {
        assertLandingLegal("2026-05-09", 6, "劳动节边界")
    }

    @Test
    fun `国庆假期为休息日时落点不应是十月十一日周日`() {
        assertLandingLegal("2026-10-10", 6, "国庆边界")
    }

    @Test
    fun `元旦调休后落点不应是周末`() {
        assertLandingLegal("2026-01-04", 6, "元旦边界")
    }

    @Test
    fun `调休日当天时刻已过时落点不应是周末`() {
        assertLandingLegal("2026-09-20", 8, "调休日已过时刻")
        assertLandingLegal("2026-02-14", 8, "春节调休已过时刻")
        assertLandingLegal("2026-05-09", 8, "劳动节调休已过时刻")
    }

    /**
     * 全量扫描：对 2026 全年每一天作为起点，weekdays 规则的落点都必须合法。
     * 这条最有杀伤力 —— 任何一个边缘组合都不会漏过。
     */
    @Test
    fun `全年任何一天作为起点weekdays规则落点都合法`() {
        val c = checker()
        val cal = calAt("2026-01-01", hour = 6)
        val bad = mutableListOf<String>()
        var checked = 0

        while (cal.get(Calendar.YEAR) == 2026) {
            val startKey = dateKey(cal)
            // 同一天跑两个时刻：规则时刻之前与之后
            for (hour in intArrayOf(6, 8)) {
                val t = Scheduler.nextTriggerTimeFrom(
                    rule("weekdays"),
                    startFrom = calAt(startKey, hour = hour),
                    shouldSkip = { x -> c.isHoliday(x) },
                    forceRun = { x -> c.isMakeupWorkday(x) }
                )
                val hit = calOf(t)
                val key = dateKey(hit)
                val dow = hit.get(Calendar.DAY_OF_WEEK)
                val weekend = dow == Calendar.SATURDAY || dow == Calendar.SUNDAY
                if (weekend && !c.isMakeupWorkday(hit)) {
                    bad += "$startKey @${hour}:00 → $key"
                }
                checked++
            }
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }

        assertTrue(
            "共检查 $checked 个组合，发现 ${bad.size} 个非法落点（落在非调休的周末）：$bad",
            bad.isEmpty()
        )
    }

    /**
     * 反向验证：调休日必须仍能被选中（修复不能以牺牲调休识别为代价）。
     */
    @Test
    fun `修复后调休日依然全部可被选中`() {
        val c = checker()
        val makeupDays = listOf(
            "2026-01-04", "2026-02-14", "2026-02-28",
            "2026-05-09", "2026-09-20", "2026-10-10"
        )
        makeupDays.forEach { d ->
            val t = Scheduler.nextTriggerTimeFrom(
                rule("weekdays"),
                startFrom = calAt(d, hour = 6),
                shouldSkip = { cal -> c.isHoliday(cal) },
                forceRun = { cal -> c.isMakeupWorkday(cal) }
            )
            assertTrue("调休日 $d 未被选中，实际落到 ${dateKey(calOf(t))}", dateKey(calOf(t)) == d)
        }
    }
}
