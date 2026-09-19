package com.workbuddy.quicklaunch

import com.workbuddy.quicklaunch.data.Automation
import com.workbuddy.quicklaunch.data.TriggerType
import com.workbuddy.quicklaunch.util.Scheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 只测 nextTriggerTime —— 这是整个 App 唯一有分支和循环的逻辑，算错就等于闹钟不响。
 * 其余都是框架回调，交给真机验证。
 */
class SchedulerTest {

    private fun rule(mode: String, h: Int = 8, m: Int = 30) = Automation(
        name = "t", targetPackage = "p", targetAppName = "n",
        triggerType = TriggerType.TIME, timeHour = h, timeMinute = m, repeatMode = mode
    )

    private fun customRule(mask: Int, h: Int = 8, m: Int = 30) = Automation(
        name = "t", targetPackage = "p", targetAppName = "n",
        triggerType = TriggerType.TIME, timeHour = h, timeMinute = m,
        repeatMode = "custom", repeatDays = mask
    )

    private fun randomRule(mode: String, ws: Int, we: Int) = Automation(
        name = "t", targetPackage = "p", targetAppName = "n",
        triggerType = TriggerType.TIME, timeHour = 0, timeMinute = 0,
        repeatMode = mode, randomWindow = true, windowStartMin = ws, windowEndMin = we
    )

    private fun calOf(ms: Long) = Calendar.getInstance().apply { timeInMillis = ms }

    @Test
    fun `任何模式下次触发都在未来`() {
        listOf("daily", "weekdays", "weekend", "once", "custom").forEach { mode ->
            val t = Scheduler.nextTriggerTime(rule(mode))
            assertTrue("$mode 算出了过去的时间", t > System.currentTimeMillis())
        }
    }

    @Test
    fun `时分与规则一致`() {
        val c = calOf(Scheduler.nextTriggerTime(rule("daily", 6, 5)))
        assertEquals(6, c.get(Calendar.HOUR_OF_DAY))
        assertEquals(5, c.get(Calendar.MINUTE))
        assertEquals(0, c.get(Calendar.SECOND))
    }

    @Test
    fun `工作日模式永远落在周一到周五`() {
        val d = calOf(Scheduler.nextTriggerTime(rule("weekdays"))).get(Calendar.DAY_OF_WEEK)
        assertTrue("落到了周末: $d", d != Calendar.SATURDAY && d != Calendar.SUNDAY)
    }

    @Test
    fun `周末模式永远落在周六或周日`() {
        val d = calOf(Scheduler.nextTriggerTime(rule("weekend"))).get(Calendar.DAY_OF_WEEK)
        assertTrue("落到了工作日: $d", d == Calendar.SATURDAY || d == Calendar.SUNDAY)
    }

    @Test
    fun `下次触发不会超过一周`() {
        listOf("daily", "weekdays", "weekend").forEach { mode ->
            val delta = Scheduler.nextTriggerTime(rule(mode)) - System.currentTimeMillis()
            assertTrue("$mode 推得太远: ${delta / 3600_000}h", delta < 8 * 24 * 3600_000L)
        }
    }

    @Test
    fun `随机窗口触发时刻落在窗口内且为未来`() {
        val ws = 8 * 60 + 30
        val we = 8 * 60 + 50
        val t = Scheduler.nextTriggerTime(randomRule("daily", ws, we))
        val c = calOf(t)
        val mins = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        assertTrue("时刻 $mins 不在窗口 [$ws,$we]", mins in ws..we)
        assertEquals(0, c.get(Calendar.SECOND))
        assertTrue("算出了过去时间", t > System.currentTimeMillis())
    }

    @Test
    fun `随机窗口工作日落在周一到周五`() {
        val d = calOf(Scheduler.nextTriggerTime(randomRule("weekdays", 510, 530)))
            .get(Calendar.DAY_OF_WEEK)
        assertTrue("落到了周末: $d", d != Calendar.SATURDAY && d != Calendar.SUNDAY)
    }

    @Test
    fun `随机窗口每次重排时刻不同`() {
        // 重复任务每次重排都重新随机，抽样多次应出现不同分钟
        val minutes = (1..30).map {
            calOf(Scheduler.nextTriggerTime(randomRule("daily", 510, 530))).get(Calendar.MINUTE)
        }.toSet()
        assertTrue("随机窗口每次都一样，未生效", minutes.size > 1)
    }

    @Test
    fun `随机窗口末边界包含结束时刻`() {
        // 结束时刻 530(8:50) 在 200 次抽样中应至少出现一次
        val hit = (1..200).any {
            val c = calOf(Scheduler.nextTriggerTime(randomRule("daily", 510, 530)))
            c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE) == 530
        }
        assertTrue("随机窗口从未抽到结束时刻 8:50", hit)
    }

    @Test
    fun `自定义星期只落在选中的几天`() {
        // 选中周一、周三、周五 (bit 1,3,5)
        val mask = (1 shl 1) or (1 shl 3) or (1 shl 5)
        val d = calOf(Scheduler.nextTriggerTime(customRule(mask))).get(Calendar.DAY_OF_WEEK)
        val ok = d == Calendar.MONDAY || d == Calendar.WEDNESDAY || d == Calendar.FRIDAY
        assertTrue("落到了未选中的星期: $d", ok)
    }

    @Test
    fun `自定义星期下次触发不超过一周`() {
        val mask = (1 shl 1) or (1 shl 3) or (1 shl 5)
        val delta = Scheduler.nextTriggerTime(customRule(mask)) - System.currentTimeMillis()
        assertTrue("自定义推得太远: ${delta / 3600_000}h", delta < 8 * 24 * 3600_000L)
    }

    @Test
    fun `自定义星期随机窗口落在选中日且窗口内`() {
        val mask = (1 shl 1) or (1 shl 3) or (1 shl 5)
        val ws = 8 * 60 + 30
        val we = 8 * 60 + 50
        val t = Scheduler.nextTriggerTime(
            Automation(
                name = "t", targetPackage = "p", targetAppName = "n",
                triggerType = TriggerType.TIME, repeatMode = "custom", repeatDays = mask,
                randomWindow = true, windowStartMin = ws, windowEndMin = we
            )
        )
        val c = calOf(t)
        val d = c.get(Calendar.DAY_OF_WEEK)
        assertTrue("落到未选中日: $d", d == Calendar.MONDAY || d == Calendar.WEDNESDAY || d == Calendar.FRIDAY)
        val mins = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        assertTrue("时刻 $mins 不在窗口 [$ws,$we]", mins in ws..we)
        assertEquals(0, c.get(Calendar.SECOND))
        assertTrue("算出了过去时间", t > System.currentTimeMillis())
    }

    @Test
    fun `自定义星期mask为0不进入死循环且返回未来时间`() {
        val t = Scheduler.nextTriggerTime(customRule(0))
        assertTrue("mask=0 未返回有效未来时间", t > System.currentTimeMillis())
    }

    @Test
    fun `跳过节假日时不会落在节假日且为未来`() {
        // 把“明天”当成节假日，验证 daily 规则会跳过它
        val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
        val key = "%04d-%02d-%02d".format(
            tomorrow.get(Calendar.YEAR), tomorrow.get(Calendar.MONTH) + 1, tomorrow.get(Calendar.DAY_OF_MONTH)
        )
        val skipSet = setOf(key)
        val shouldSkip: (Calendar) -> Boolean = { c ->
            skipSet.contains(
                "%04d-%02d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
            )
        }
        val t = Scheduler.nextTriggerTime(rule("daily"), shouldSkip)
        val c = calOf(t)
        val ds = "%04d-%02d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
        assertTrue("落到了被跳过的节假日 $ds", ds !in skipSet)
        assertTrue("算出了过去时间", t > System.currentTimeMillis())
    }

    @Test
    fun `随机窗口跳过节假日当天不触发`() {
        val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
        val key = "%04d-%02d-%02d".format(
            tomorrow.get(Calendar.YEAR), tomorrow.get(Calendar.MONTH) + 1, tomorrow.get(Calendar.DAY_OF_MONTH)
        )
        val shouldSkip: (Calendar) -> Boolean = { c ->
            "%04d-%02d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)) == key
        }
        val ws = 8 * 60 + 30
        val we = 8 * 60 + 50
        val t = Scheduler.nextTriggerTime(
            Automation(
                name = "t", targetPackage = "p", targetAppName = "n",
                triggerType = TriggerType.TIME, repeatMode = "daily",
                randomWindow = true, windowStartMin = ws, windowEndMin = we
            ),
            shouldSkip
        )
        val c = calOf(t)
        val ds = "%04d-%02d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
        assertTrue("随机窗口落到了被跳过的节假日 $ds", ds != key)
        assertTrue("算出了过去时间", t > System.currentTimeMillis())
    }

    // ═══════════════════════════════════════════════════════════════
    // 调休上班日：哪怕没勾选跳过节假日，也要自动运行
    // ═══════════════════════════════════════════════════════════════

    /** 构造「某天是调休上班日」的判定函数。 */
    private fun forceRunOn(target: Calendar): (Calendar) -> Boolean {
        val key = dateKeyOf(target)
        return { c -> dateKeyOf(c) == key }
    }

    private fun dateKeyOf(c: Calendar): String = "%04d-%02d-%02d".format(
        c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)
    )

    /** 找到「下一个周六」。用于模拟调休上班日。 */
    private fun nextSaturdayFar(weeks: Int = 8): Calendar = Calendar.getInstance().apply {
        add(Calendar.WEEK_OF_YEAR, weeks)
        while (get(Calendar.DAY_OF_WEEK) != Calendar.SATURDAY) add(Calendar.DAY_OF_YEAR, 1)
    }

    /** 把起点设在目标日**当天更早**的时刻，模拟「排程在调休日当天、触发时刻尚未到」。 */
    private fun startAt(target: Calendar, hour: Int = 7): Calendar =
        (target.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

    @Test
    fun `调休上班日不被仅工作日规则过滤掉`() {
        // 规则限定「仅工作日」，而周六本是排除项；这天若是调休上班日则必须放行，不能被推到下周一
        val sat = nextSaturdayFar()
        val t = Scheduler.nextTriggerTimeFrom(
            rule("weekdays"),
            startFrom = startAt(sat),
            shouldSkip = { false },
            forceRun = forceRunOn(sat)
        )
        val c = calOf(t)
        assertEquals("调休日被星期规则推走了", dateKeyOf(sat), dateKeyOf(c))
        assertEquals("调休日未落在周六", Calendar.SATURDAY, c.get(Calendar.DAY_OF_WEEK))
        assertTrue("算出了过去时间", t > startAt(sat).timeInMillis)
    }

    @Test
    fun `调休上班日优先于跳过节假日`() {
        // 同一天既是调休上班日又被标成休息日（数据冲突），forceRun 必须赢
        val sat = nextSaturdayFar()
        val key = dateKeyOf(sat)
        val shouldSkip: (Calendar) -> Boolean = { c -> dateKeyOf(c) == key }
        val t = Scheduler.nextTriggerTimeFrom(
            rule("daily"),
            startFrom = startAt(sat),
            shouldSkip = shouldSkip,
            forceRun = forceRunOn(sat)
        )
        assertEquals("调休日被跳过规则吃掉了", key, dateKeyOf(calOf(t)))
    }

    @Test
    fun `无调休数据时行为与旧版一致`() {
        // forceRun 恒 false 时，仅工作日规则仍然只落在周一到周五
        val t = Scheduler.nextTriggerTime(
            rule("weekdays"),
            shouldSkip = { false },
            forceRun = { false }
        )
        val d = calOf(t).get(Calendar.DAY_OF_WEEK)
        assertTrue("落到了周末 $d", d != Calendar.SATURDAY && d != Calendar.SUNDAY)
    }

    @Test
    fun `随机窗口规则同样尊重调休日`() {
        // 随机窗口下，走到调休上班日时必须选中它，不能被推进到下一个工作日
        val sat = nextSaturdayFar()
        val ws = 8 * 60 + 30
        val we = 8 * 60 + 50
        val t = Scheduler.nextTriggerTimeFrom(
            randomRule("weekdays", ws, we),
            startFrom = startAt(sat),
            shouldSkip = { false },
            forceRun = forceRunOn(sat)
        )
        val c = calOf(t)
        assertEquals("随机窗口调休日未落在周六", Calendar.SATURDAY, c.get(Calendar.DAY_OF_WEEK))
        assertEquals("随机窗口未选中调休上班日", dateKeyOf(sat), dateKeyOf(c))
        val mins = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        assertTrue("时刻 $mins 不在窗口 [$ws,$we]", mins in ws..we)
    }

    @Test
    fun `休息日仍被跳过规则正常跳过`() {
        // 反向验证：非调休日时 shouldSkip 依然生效，没被 forceRun 顺带放开
        val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
        val key = dateKeyOf(tomorrow)
        val t = Scheduler.nextTriggerTime(
            rule("daily"),
            shouldSkip = { c -> dateKeyOf(c) == key },
            forceRun = { false }
        )
        assertTrue("休息日没有跳过", dateKeyOf(calOf(t)) != key)
    }

    @Test
    fun `调休日当天也已过时刻时顺延到次日而非跳过整周`() {
        // 边界：起点是调休周六、但规则时刻已经过了 → 只能顺延。
        // 此时下一天是周日，weekdays 会推到周一 —— 这是正确的（今天确实已经没法触发）
        val sat = nextSaturdayFar()
        val t = Scheduler.nextTriggerTimeFrom(
            rule("weekdays"),
            startFrom = startAt(sat, hour = 23),
            shouldSkip = { false },
            forceRun = forceRunOn(sat)
        )
        val c = calOf(t)
        assertTrue("算出了过去时间", t > startAt(sat, hour = 23).timeInMillis)
        val d = c.get(Calendar.DAY_OF_WEEK)
        assertTrue("顺延后落到周末 $d", d != Calendar.SATURDAY && d != Calendar.SUNDAY)
    }
}
