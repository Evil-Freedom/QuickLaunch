package com.workbuddy.quicklaunch

import com.workbuddy.quicklaunch.data.Automation
import com.workbuddy.quicklaunch.data.RepeatMode
import com.workbuddy.quicklaunch.data.TriggerType
import com.workbuddy.quicklaunch.util.HolidayChecker
import com.workbuddy.quicklaunch.util.Scheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 单日豁免（用户请假的「这一天别启动」）的优先级验证。
 *
 * 核心命题只有一条：**用户显式豁免 > 调休上班日强制放行 > 法定休息日跳过 > 星期位图**。
 * 这条优先级一旦被写反，用户的典型场景就会静默失效：
 * 「我明天请假改了规则，结果明天照常给我打开打卡 App」——
 * 而且失败时会表现为「规则看起来设置对了」，极难排查。
 *
 * 测试**直接调用生产的谓词构造函数**（[Scheduler.buildSkipPredicate] /
 * [Scheduler.buildForceRunPredicate]），不在测试里重写一份等价判定 ——
 * 否则只能证明测试自己的逻辑对，证明不了线上跑的那份对。
 */
class RuleExceptionScheduleTest {

    /**
     * 2026 年真实节假日数据的**相关节选**（全量数据与解析验证见 HolidayIntegrationTest）：
     * - 国庆假期 10-01 ~ 10-07 全为休息日
     * - 09-20（周日）、10-10（周六）为调休上班日
     *
     * 只取与本组用例相关的日期，避免测试数据与真实数据漂移。
     */
    private fun checker() = HolidayChecker(
        restDates = setOf(
            "2026-10-01", "2026-10-02", "2026-10-03", "2026-10-04",
            "2026-10-05", "2026-10-06", "2026-10-07"
        ),
        workdays = setOf("2026-09-20", "2026-10-10")
    )

    private fun calAt(date: String, hour: Int = 6): Calendar {
        val (y, mo, d) = date.split("-").map { it.toInt() }
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, y)
            set(Calendar.MONTH, mo - 1)
            set(Calendar.DAY_OF_MONTH, d)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }

    private fun dateKeyOf(ms: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        return "%04d-%02d-%02d".format(
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)
        )
    }

    private fun rule(
        mode: String = RepeatMode.DAILY,
        h: Int = 7,
        m: Int = 0,
        mask: Int = 0,
        skipHolidays: Boolean = false
    ) = Automation(
        name = "t", targetPackage = "p", targetAppName = "n",
        triggerType = TriggerType.TIME, timeHour = h, timeMinute = m,
        repeatMode = mode, repeatDays = mask, skipHolidays = skipHolidays
    )

    /**
     * 走**生产代码**的谓词构造 + 排程计算。
     * 与 Scheduler.schedule() 内部调用的是同一对函数，只是把查库结果换成了注入的 checker。
     */
    private fun nextOf(a: Automation, from: String, exempt: Set<String>, hour: Int = 6): String {
        val c = checker()
        return dateKeyOf(
            Scheduler.nextTriggerTimeFrom(
                a,
                startFrom = calAt(from, hour),
                shouldSkip = Scheduler.buildSkipPredicate(a, c, exempt),
                forceRun = Scheduler.buildForceRunPredicate(c, exempt),
                exceptions = exempt
            )
        )
    }

    /** 二三四五（周二~周五）位图，与设备上真实规则「飞书 18:00」一致。 */
    private val tueToFri = (1 shl (Calendar.TUESDAY - 1)) or
            (1 shl (Calendar.WEDNESDAY - 1)) or
            (1 shl (Calendar.THURSDAY - 1)) or
            (1 shl (Calendar.FRIDAY - 1))

    // ══════════════════════════════════════════════════════════════════
    // 第一层：豁免压过调休强制放行（本功能最关键的一条）
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `豁免普通工作日时顺延到次日`() {
        // 10-08 是节后第一个普通工作日（周四）
        assertEquals("豁免没生效，当天仍然被排上", "2026-10-09", nextOf(rule(), "2026-10-08", setOf("2026-10-08")))
    }

    @Test
    fun `豁免压过调休上班日的强制放行`() {
        // 09-20 是周日·调休上班日。「仅工作日」规则本来会被强制放行到这天（已有测试固化），
        // 但用户请假 → 必须跳过。这条若失败，等于「请假当天照常被叫醒」。
        val c = checker()
        assertTrue("用例前提不成立：09-20 不是调休上班日", c.isMakeupWorkday(calAt("2026-09-20")))

        assertEquals(
            "调休强制放行没被豁免压住",
            "2026-09-21",
            nextOf(rule(RepeatMode.WEEKDAYS), "2026-09-20", setOf("2026-09-20"))
        )
    }

    @Test
    fun `豁免压过自定义星期位图下的调休放行`() {
        // 位图是「二三四五」，09-20（周日）本不在位图里，却因调休被强制放行。
        // 加上豁免后应回到位图的正常节奏 → 下一个周二 09-22。
        assertEquals(
            "自定义位图 + 调休 + 豁免 的组合没走对",
            "2026-09-22",
            nextOf(rule(RepeatMode.CUSTOM, mask = tueToFri), "2026-09-20", setOf("2026-09-20"))
        )
    }

    @Test
    fun `豁免压过调休并且不影响下一天的调休`() {
        // 对照组：只豁免 09-20，不影响 10-10（另一个调休日）
        assertEquals(
            "只豁免一天却连另一天的调休也吃掉了",
            "2026-10-10",
            nextOf(rule(RepeatMode.WEEKDAYS), "2026-10-10", setOf("2026-09-20"))
        )
    }

    // ══════════════════════════════════════════════════════════════════
    // 第二层：豁免与「跳过节假日」开关相互独立
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `未勾选跳过节假日时豁免依然生效`() {
        // 豁免靠的是用户显式点名，不该依赖 skipHolidays 这个开关
        assertEquals(
            "没勾跳过节假日，豁免就失效了",
            "2026-10-10",
            nextOf(rule(RepeatMode.DAILY, skipHolidays = false), "2026-10-09", setOf("2026-10-09"))
        )
    }

    @Test
    fun `豁免法定休息日时无需打开跳过开关`() {
        // 10-04 是国庆休息日。daily + 不跳节假日 → 本来应该启动；豁免后必须跳过。
        assertEquals(
            "休息日的豁免没生效",
            "2026-10-05",
            nextOf(rule(RepeatMode.DAILY, skipHolidays = false), "2026-10-04", setOf("2026-10-04"))
        )
    }

    @Test
    fun `勾选跳过节假日时豁免与节假日跳过叠加`() {
        // 起点 10-01（国庆休息日），10-01~10-07 全假期，10-08 再豁免 →
        // 应落到 10-09（假期跳过 + 单日豁免 共同作用）
        assertEquals(
            "假期跳过与单日豁免没叠加",
            "2026-10-09",
            nextOf(rule(RepeatMode.DAILY, skipHolidays = true), "2026-10-01", setOf("2026-10-08"))
        )
    }

    // ══════════════════════════════════════════════════════════════════
    // 第三层：连续豁免、过期恢复、一次性任务
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `连续多天豁免时逐日跳过`() {
        assertEquals(
            "连续豁免没有逐日生效",
            "2026-10-11",
            nextOf(rule(), "2026-10-08", setOf("2026-10-08", "2026-10-09", "2026-10-10"))
        )
    }

    @Test
    fun `豁免过期后自动恢复正常`() {
        // 豁免记的是 10-08，起点已是 10-09 → 不该再受影响
        assertEquals(
            "过期豁免仍然在挡",
            "2026-10-09",
            nextOf(rule(), "2026-10-09", setOf("2026-10-08"))
        )
    }

    @Test
    fun `撤销豁免后当天恢复启动`() {
        // 远程「恢复」= 删掉豁免记录 → 空集合，行为必须与从未豁免过一致
        assertEquals(
            "撤销豁免后没有回到当天",
            "2026-10-08",
            nextOf(rule(), "2026-10-08", emptySet())
        )
    }

    @Test
    fun `一次性任务命中豁免时顺延`() {
        // once 本来不受节假日影响，但用户显式豁免这一天，必须尊重
        assertEquals(
            "一次性任务的豁免没生效",
            "2026-10-09",
            nextOf(rule(RepeatMode.ONCE), "2026-10-08", setOf("2026-10-08"))
        )
    }

    @Test
    fun `一次性任务顺延只跳过豁免日不跳过节假日`() {
        // 起点 10-04（国庆休息日），once + 豁免 10-04 → 顺延到 10-05，
        // 不会因为 10-05 也是假期继续往后推（once 的既有语义：不管节假日）
        assertEquals(
            "一次性任务被节假日影响了",
            "2026-10-05",
            nextOf(rule(RepeatMode.ONCE), "2026-10-04", setOf("2026-10-04"))
        )
    }

    // ══════════════════════════════════════════════════════════════════
    // 第四层：空豁免 = 完全等价于旧行为（回归保护）
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `空豁免集合不改变调休放行行为`() {
        // 与 HolidayIntegrationTest「自定义星期没勾调休日也会被放行」同条件，
        // 加了豁免机制后结果必须不变，否则就是回归。
        assertEquals(
            "调休放行被豁免机制破坏了",
            "2026-09-20",
            nextOf(rule(RepeatMode.CUSTOM, mask = tueToFri), "2026-09-20", emptySet())
        )
    }

    @Test
    fun `空豁免集合不改变节假日跳过行为`() {
        // 起点 10-01（国庆休息日）→ 整段假期被跳过，落到节后首个工作日 10-08
        assertEquals(
            "节假日跳过被豁免机制破坏了",
            "2026-10-08",
            nextOf(rule(RepeatMode.DAILY, skipHolidays = true), "2026-10-01", emptySet())
        )
    }

    @Test
    fun `空豁免集合时非调休周末依然被位图过滤`() {
        assertEquals(
            "普通周末被位图过滤的行为变了",
            "2026-09-15",
            nextOf(rule(RepeatMode.CUSTOM, mask = tueToFri), "2026-09-12", emptySet())
        )
    }

    @Test
    fun `豁免判定不依赖节假日数据是否存在`() {
        // 空 checker（用户从未同步节假日）+ 豁免 → 仍然跳过
        val empty = HolidayChecker.EMPTY
        val a = rule()
        val t = Scheduler.nextTriggerTimeFrom(
            a,
            startFrom = calAt("2026-10-08"),
            shouldSkip = Scheduler.buildSkipPredicate(a, empty, setOf("2026-10-08")),
            forceRun = Scheduler.buildForceRunPredicate(empty, setOf("2026-10-08")),
            exceptions = setOf("2026-10-08")
        )
        assertEquals("没有节假日数据时豁免失效了", "2026-10-09", dateKeyOf(t))
    }

    @Test
    fun `豁免日期格式必须是 yyyy-MM-dd`() {
        // 记录格式若与 HolidayChecker.dateKey 不一致（例如写成 2026-9-8 或带时间），
        // 判定会静默失效 —— 这里固化格式契约。
        assertTrue(
            "dateKey 格式与豁免记录格式不一致",
            Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(HolidayChecker.dateKey(calAt("2026-09-08")))
        )
        assertEquals("2026-09-08", HolidayChecker.dateKey(calAt("2026-09-08")))
    }

    @Test
    fun `豁免不影响其他日期的正常排程`() {
        // 豁免 10-10（调休日），但规则从 10-08 出发，应正常落在 10-08
        assertEquals(
            "远期豁免把近处日期挤掉了",
            "2026-10-08",
            nextOf(rule(RepeatMode.WEEKDAYS), "2026-10-08", setOf("2026-10-10"))
        )
        assertFalse(
            "豁免了 10-10 却仍然算到 10-10",
            nextOf(rule(RepeatMode.WEEKDAYS), "2026-10-09", setOf("2026-10-10")) == "2026-10-10"
        )
    }
}
