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
}
