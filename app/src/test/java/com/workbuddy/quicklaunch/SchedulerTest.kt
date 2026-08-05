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

    private fun randomRule(mode: String, ws: Int, we: Int) = Automation(
        name = "t", targetPackage = "p", targetAppName = "n",
        triggerType = TriggerType.TIME, timeHour = 0, timeMinute = 0,
        repeatMode = mode, randomWindow = true, windowStartMin = ws, windowEndMin = we
    )

    private fun calOf(ms: Long) = Calendar.getInstance().apply { timeInMillis = ms }

    @Test
    fun `任何模式下次触发都在未来`() {
        listOf("daily", "weekdays", "weekend", "once").forEach { mode ->
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
}
