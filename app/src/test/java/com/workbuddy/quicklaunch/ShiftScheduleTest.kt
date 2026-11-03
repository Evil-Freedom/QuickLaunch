package com.workbuddy.quicklaunch

import com.workbuddy.quicklaunch.data.Automation
import com.workbuddy.quicklaunch.data.DailyShift
import com.workbuddy.quicklaunch.data.RepeatMode
import com.workbuddy.quicklaunch.data.ShiftAnchor
import com.workbuddy.quicklaunch.data.TriggerType
import com.workbuddy.quicklaunch.util.HolidayChecker
import com.workbuddy.quicklaunch.util.Scheduler
import com.workbuddy.quicklaunch.util.ShiftTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 班表模式排程的约束集。
 *
 * 班表模式与固定时刻模式是**两条独立路径**，这里钉住的是它自己的语义：
 *
 * 1. 触发时刻 = 当天班次时刻 + 偏移；
 * 2. 今天这一班已经过了就顺延，绝不返回过去时刻（否则闹钟会立刻响）；
 * 3. 班表里没有的日期 = 休息，跳过；
 * 4. 用户请假（豁免）仍然生效；
 * 5. **星期位图与法定节假日完全不参与判定** —— 这正是它存在的理由：
 *    轮班的人周六要上 05:00 的班，而 weekdays 会把它过滤掉；春节值班那天，
 *    国务院通知说放假。叠加这两层就必然算错；
 * 6. 班表为空时退化为固定时刻，不允许「静默永不触发」。
 *
 * 全部用固定日期构造，不依赖「今天」，因此任何时刻跑都稳定。
 */
class ShiftScheduleTest {

    // ── 构造辅助 ─────────────────────────────────────────────────

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int): Calendar =
        Calendar.getInstance().apply {
            clear()
            set(y, m - 1, d, h, min, 0)
        }

    private fun ts(y: Int, m: Int, d: Int, h: Int, min: Int): Long =
        at(y, m, d, h, min).timeInMillis

    private fun shift(date: String, inMin: Int, outMin: Int) =
        DailyShift(date = date, inMin = inMin, outMin = outMin, source = "test")

    private fun table(vararg items: DailyShift) = ShiftTable.of(items.toList())

    private fun rule(
        anchor: Int,
        offset: Int,
        mode: String = RepeatMode.DAILY,
        skipHolidays: Boolean = false
    ) = Automation(
        name = "shift", targetPackage = "p", targetAppName = "n",
        triggerType = TriggerType.TIME,
        // 兜底固定时刻，用于验证「班表为空时退化」这条
        timeHour = 7, timeMinute = 0,
        repeatMode = mode, skipHolidays = skipHolidays,
        shiftAnchor = anchor, shiftOffsetMin = offset
    )

    /** 统一入口：与生产排程同一条函数，不另写简化算法。 */
    private fun fire(a: Automation, from: Calendar, shifts: ShiftTable?, ex: Set<String> = emptySet()): Long =
        Scheduler.nextTriggerTimeFrom(
            a,
            startFrom = from,
            shouldSkip = { false },
            forceRun = { false },
            exceptions = ex,
            shifts = shifts
        )

    // ── 基本换算 ─────────────────────────────────────────────────

    /** 白班 09:00-18:00，「上班前 30 分钟」应该落在 08:30 而不是 09:00。 */
    @Test
    fun `上班前30分钟落在08点30`() {
        val t = table(shift("2026-11-02", 540, 1080))
        val a = rule(ShiftAnchor.CLOCK_IN, -30)
        val got = fire(a, at(2026, 11, 2, 8, 0), t)
        assertEquals(ts(2026, 11, 2, 8, 30), got)
    }

    /** 今天这一班的点已经过了 → 顺延到下一个有班的日子，不能返回过去时刻。 */
    @Test
    fun `今天班次已过则顺延到明天`() {
        val t = table(
            shift("2026-11-02", 540, 1080),
            shift("2026-11-03", 540, 1080)
        )
        val a = rule(ShiftAnchor.CLOCK_IN, -30)
        // 11-02 09:30 已经在上班之后，08:30 早就过了
        val got = fire(a, at(2026, 11, 2, 9, 30), t)
        assertEquals(ts(2026, 11, 3, 8, 30), got)
    }

    /** 班表没有的日期 = 休息，必须跳过（而不是退化成固定时刻）。 */
    @Test
    fun `班表无班次的日期被跳过`() {
        val t = table(
            shift("2026-11-02", 540, 1080),
            // 11-03、11-04 没有班次 = 休息
            shift("2026-11-05", 540, 1080)
        )
        val a = rule(ShiftAnchor.CLOCK_IN, -30)
        val got = fire(a, at(2026, 11, 2, 19, 0), t)
        assertEquals(ts(2026, 11, 5, 8, 30), got)
    }

    /** 用户请假（豁免）优先级最高：那天有班也不触发。 */
    @Test
    fun `豁免日期即使有班次也不触发`() {
        val t = table(
            shift("2026-11-02", 540, 1080),
            shift("2026-11-03", 540, 1080)
        )
        val a = rule(ShiftAnchor.CLOCK_IN, -30)
        val got = fire(a, at(2026, 11, 2, 8, 0), t, ex = setOf("2026-11-02"))
        assertEquals(ts(2026, 11, 3, 8, 30), got)
    }

    // ── 跨天班 ───────────────────────────────────────────────────

    /**
     * 05:00 上班、次日 05:00 下班（outMin=1740）。
     * 锚定下班时必须落在**次日**，不能被 Calendar 归一化成当天 05:00。
     */
    @Test
    fun `跨天班锚定下班落在次日`() {
        val t = table(shift("2026-11-05", 300, 1740))
        val a = rule(ShiftAnchor.CLOCK_OUT, 10)
        val got = fire(a, at(2026, 11, 5, 4, 0), t)
        assertEquals(ts(2026, 11, 6, 5, 10), got)
    }

    /** 跨天班的上班时刻就是当天凌晨 05:00，提前 30 分钟 = 04:30。 */
    @Test
    fun `跨天班锚定上班落在当天凌晨`() {
        val t = table(shift("2026-11-05", 300, 1740))
        val a = rule(ShiftAnchor.CLOCK_IN, -30)
        val got = fire(a, at(2026, 11, 5, 1, 0), t)
        assertEquals(ts(2026, 11, 5, 4, 30), got)
    }

    // ── 绕过星期与节假日 ─────────────────────────────────────────

    /**
     * 关键回归：规则设成 weekdays，但班表里周六（11-07）有 05:00 的班。
     * 班表模式必须照常触发 —— 这正是「轮班的人用固定星期会漏班」的核心问题。
     */
    @Test
    fun `班表模式绕开星期位图 周六照常触发`() {
        val t = table(shift("2026-11-07", 300, 1080))   // 2026-11-07 是周六
        val a = rule(ShiftAnchor.CLOCK_IN, 0, mode = RepeatMode.WEEKDAYS)
        val got = fire(a, at(2026, 11, 6, 19, 0), t)
        assertEquals("班表模式不该被 weekdays 过滤", ts(2026, 11, 7, 5, 0), got)
    }

    /** 勾了「跳过节假日」也一样：班表说上班就上班（春节值班场景）。 */
    @Test
    fun `班表模式绕开跳过节假日开关`() {
        val t = table(shift("2026-10-01", 300, 1080))   // 国庆当天有班
        val a = rule(ShiftAnchor.CLOCK_IN, 0, skipHolidays = true)
        val got = fire(a, at(2026, 9, 30, 20, 0), t)
        assertEquals("班表模式不该被 skipHolidays 拦掉", ts(2026, 10, 1, 5, 0), got)
    }

    // ── 退化路径 ─────────────────────────────────────────────────

    /**
     * 班表为空（刚打开开关、还没同步）时退化为固定时刻。
     *
     * 这条防的是最隐蔽的故障：若返回「很远之后」或抛异常，
     * 用户会看到规则「已启用」但永远不响，而任何界面都不会报错。
     */
    @Test
    fun `班表为空时退化为固定时刻`() {
        val a = rule(ShiftAnchor.CLOCK_IN, -30)
        val got = fire(a, at(2026, 11, 2, 8, 0), ShiftTable.EMPTY)
        // 规则里的固定时刻是 07:00，当天已过 → 次日 07:00
        assertEquals(ts(2026, 11, 3, 7, 0), got)
    }

    /** shifts 传 null（旧调用方）同样走固定时刻，行为与升级前一致。 */
    @Test
    fun `班表缺失时与固定时刻行为一致`() {
        val a = rule(ShiftAnchor.CLOCK_IN, -30)
        val withNull = fire(a, at(2026, 11, 2, 8, 0), null)
        val plain = Scheduler.nextTriggerTimeFrom(
            a.copy(shiftAnchor = ShiftAnchor.FIXED, shiftOffsetMin = 0),
            at(2026, 11, 2, 8, 0),
            { false }, { false }, emptySet(), null
        )
        assertEquals(plain, withNull)
    }

    // ── 与界面展示同源 ───────────────────────────────────────────

    /**
     * peek 在班表模式下不能抛异常、且必须返回未来时刻。
     *
     * 这里刻意**不**断言「peek 与排程相等」：peek 用的是真实「现在」，
     * 而排程用例把起点固定在 2026-11-02，两者本来就该不同。
     * 想验证同源一致，用固定起点的 nextTriggerTimeFrom 对同一入参跑两次比较即可
     * （见本文件的 fire 辅助函数，它直接转调生产代码的唯一入口）。
     */
    @Test
    fun `班表模式下 peek 可正常返回且不崩`() {
        val t = table(
            shift("2026-11-02", 540, 1080),
            shift("2026-11-05", 300, 1740)
        )
        val ck = HolidayChecker.EMPTY
        val nowMs = System.currentTimeMillis()
        for (anchor in intArrayOf(ShiftAnchor.CLOCK_IN, ShiftAnchor.CLOCK_OUT)) {
            for (offset in intArrayOf(-60, -30, 0, 10, 30)) {
                val a = rule(anchor, offset)
                val viaPeek = Scheduler.peekNextTriggerAt(a, ck, emptySet(), t)
                assertTrue(
                    "anchor=$anchor offset=$offset 的 peek 必须返回未来时刻",
                    viaPeek > nowMs
                )
            }
        }
    }

    /** 同一组入参重复调用必须给出同一个落点（排程不能有随机性）。 */
    @Test
    fun `同一入参反复计算结果稳定`() {
        val t = table(shift("2026-11-02", 540, 1080), shift("2026-11-05", 300, 1740))
        val a = rule(ShiftAnchor.CLOCK_IN, -30)
        val first = fire(a, at(2026, 11, 2, 8, 0), t)
        repeat(5) {
            assertEquals("第 $it 次重算结果漂移", first, fire(a, at(2026, 11, 2, 8, 0), t))
        }
    }
}
