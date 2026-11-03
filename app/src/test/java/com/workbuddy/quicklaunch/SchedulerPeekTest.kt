package com.workbuddy.quicklaunch

import com.workbuddy.quicklaunch.data.Automation
import com.workbuddy.quicklaunch.data.RepeatMode
import com.workbuddy.quicklaunch.data.TriggerType
import com.workbuddy.quicklaunch.util.HolidayChecker
import com.workbuddy.quicklaunch.util.Scheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Calendar

/**
 * [Scheduler.peekNextTriggerAt] 的约束：**界面显示的「下次触发」必须等于闹钟的真实落点**。
 *
 * 这个函数存在的唯一目的就是消除「第二套算法」。
 * 旧实现里主界面自己按固定时刻取最早，完全不看请假与节假日，
 * 于是出现「卡片标签写着请假 11-04、仪表盘却显示今天 12:19」的自相矛盾状态。
 *
 * 所以这里断言的是「与 [Scheduler.schedule] 内部完全同一套谓词」，
 * 而不是断言某个写死的时间点 —— 后者会随运行时刻漂移，测不出真问题。
 */
class SchedulerPeekTest {

    private fun checker() = HolidayChecker(
        restDates = setOf("2026-10-01", "2026-10-02"),
        workdays = setOf("2026-09-20", "2026-10-10")
    )

    private fun rule(
        hour: Int,
        minute: Int,
        mode: String = RepeatMode.DAILY,
        skipHolidays: Boolean = false,
        repeatDays: Int = 0
    ) = Automation(
        name = "peek", targetPackage = "p", targetAppName = "n",
        triggerType = TriggerType.TIME, timeHour = hour, timeMinute = minute,
        repeatMode = mode, repeatDays = repeatDays,
        skipHolidays = skipHolidays
    )

    private fun dateKey(ms: Long): String = HolidayChecker.dateKey(
        Calendar.getInstance().apply { timeInMillis = ms }
    )

    private fun todayKey(): String = HolidayChecker.dateKey(Calendar.getInstance())

    private fun tomorrowKey(): String {
        val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
        return HolidayChecker.dateKey(c)
    }

    /**
     * 请假当天必须被跳过 —— 这是新增界面的直接动机。
     *
     * 用「现在 + 1 小时」构造一个今天确实还没到的时刻；
     * 若当前已接近午夜（构造会跨天），则本用例失去意义，跳过而不是给假绿。
     */
    @Test
    fun `今天被请假时 下次触发顺延到明天`() {
        val now = Calendar.getInstance()
        assumeTrue("临近午夜时无法构造『今天还没到』的时刻", now.get(Calendar.HOUR_OF_DAY) <= 22)

        val start = Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, 1) }
        val a = rule(start.get(Calendar.HOUR_OF_DAY), start.get(Calendar.MINUTE))
        val today = todayKey()

        // 对照组：没有请假时就在今天
        val plain = Scheduler.peekNextTriggerAt(a, checker(), emptySet())
        assertEquals("无豁免时应落在今天", today, dateKey(plain))

        // 实验组：只改一个变量（今天的豁免），日期必须整体后移
        val exempted = Scheduler.peekNextTriggerAt(a, checker(), setOf(today))
        assertEquals("请假当天必须被跳过", tomorrowKey(), dateKey(exempted))
    }

    /**
     * 与排程内部谓词逐字一致：同样的规则、同样的豁免，两条路径必须给出同一个毫秒值。
     *
     * 这条断言防的是「以后有人给 schedule 加了新判定，却忘了同步 peek」——
     * 那会让界面重新开始骗人，而且不会有任何报错。
     */
    @Test
    fun `peek 与排程谓词给出的落点完全相同`() {
        val ck = checker()
        val cases = listOf(
            rule(9, 0),
            rule(23, 59, RepeatMode.WEEKDAYS),
            rule(7, 30, RepeatMode.CUSTOM, repeatDays = 0b0111110),
            rule(12, 0, skipHolidays = true),
            rule(6, 15, RepeatMode.ONCE)
        )
        for (a in cases) {
            for (ex in listOf(emptySet(), setOf(todayKey()))) {
                val viaPeek = Scheduler.peekNextTriggerAt(a, ck, ex)
                val viaSchedule = Scheduler.nextTriggerTimeFrom(
                    a,
                    Calendar.getInstance(),
                    Scheduler.buildSkipPredicate(a, ck, ex),
                    Scheduler.buildForceRunPredicate(ck, ex),
                    ex
                )
                assertEquals("规则 ${a.repeatMode} ${a.timeHour}:${a.timeMinute} 豁免=$ex", viaSchedule, viaPeek)
            }
        }
    }

    /** 没有节假日数据（holidays=null）时不能崩，也不能退化成「永远今天」以外的行为。 */
    @Test
    fun `节假日表缺失时只保留豁免判定`() {
        val start = Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, 2) }
        val a = rule(start.get(Calendar.HOUR_OF_DAY), start.get(Calendar.MINUTE))
        val fire = Scheduler.peekNextTriggerAt(a, null, setOf(todayKey()))
        assertTrue("必须返回未来时刻", fire > System.currentTimeMillis())
    }
}
