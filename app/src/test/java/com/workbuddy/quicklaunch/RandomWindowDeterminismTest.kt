package com.workbuddy.quicklaunch

import com.workbuddy.quicklaunch.data.Automation
import com.workbuddy.quicklaunch.data.RepeatMode
import com.workbuddy.quicklaunch.data.TriggerType
import com.workbuddy.quicklaunch.util.Scheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 随机窗口的「同一天恒定」约束。
 *
 * 背景：仪表盘的「下次触发」与 AlarmManager 的真实落点共用 [Scheduler]。
 * 若随机时刻每次调用都变，展示层只能整条跳过随机规则 ——
 * 用户看到「已启用 4 条」却只按 3 条算下次触发，会以为有一条规则没生效。
 */
class RandomWindowDeterminismTest {

    private fun randomRule(
        id: Long = 7,
        start: Int = 8 * 60 + 45,
        end: Int = 8 * 60 + 55,
        mode: String = RepeatMode.DAILY
    ) = Automation(
        id = id,
        name = "飞书",
        targetPackage = "com.ss.android.lark",
        targetAppName = "飞书",
        triggerType = TriggerType.TIME,
        repeatMode = mode,
        randomWindow = true,
        windowStartMin = start,
        windowEndMin = end
    )

    @Test
    fun `同一条规则同一天多次计算结果一致`() {
        val rule = randomRule()
        val first = Scheduler.nextTriggerTime(rule)
        repeat(5) {
            assertEquals("随机窗口每次调用都变了，界面无法显示确切时刻", first, Scheduler.nextTriggerTime(rule))
        }
    }

    @Test
    fun `界面预览与真实排程同一时刻`() {
        val rule = randomRule()
        // peekNextTriggerAt 就是界面「下次触发」用的入口，必须与排程结果逐毫秒一致
        val peeked = Scheduler.peekNextTriggerAt(rule, null, emptySet())
        assertEquals(Scheduler.nextTriggerTime(rule), peeked)
    }

    @Test
    fun `不同日期取到的时刻会变化`() {
        val rule = randomRule()
        val seen = mutableSetOf<Long>()
        val cal = Calendar.getInstance()
        repeat(7) { offset ->
            val from = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, offset)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            cal.timeInMillis = Scheduler.nextTriggerTimeFrom(
                rule, from, shouldSkip = { false }, forceRun = { false }
            )
            seen += cal.get(Calendar.HOUR_OF_DAY) * 60L + cal.get(Calendar.MINUTE)
        }
        assertTrue("7 天都落到同一个分钟，等于退化成固定时刻: $seen", seen.size > 1)
    }

    @Test
    fun `时刻始终落在窗口内`() {
        val rule = randomRule(start = 8 * 60 + 45, end = 8 * 60 + 55)
        repeat(20) { day ->
            val from = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, day)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val at = Calendar.getInstance().apply {
                timeInMillis = Scheduler.nextTriggerTimeFrom(
                    rule, from, shouldSkip = { false }, forceRun = { false }
                )
            }
            val mins = at.get(Calendar.HOUR_OF_DAY) * 60 + at.get(Calendar.MINUTE)
            assertTrue("第 $day 天取到 $mins 分，不在 [525,535]", mins in 525..535)
            assertEquals(0, at.get(Calendar.SECOND))
        }
    }
}
