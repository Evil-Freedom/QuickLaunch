package com.workbuddy.quicklaunch

import com.workbuddy.quicklaunch.data.Automation
import com.workbuddy.quicklaunch.data.RepeatMode
import com.workbuddy.quicklaunch.data.RuleException
import com.workbuddy.quicklaunch.data.TriggerType
import com.workbuddy.quicklaunch.remote.RemoteProtocol
import com.workbuddy.quicklaunch.remote.RemoteRuleApi
import com.workbuddy.quicklaunch.remote.RuleStore
import com.workbuddy.quicklaunch.remote.ScheduleHook
import com.workbuddy.quicklaunch.util.HolidayChecker
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 远程写入的执行序与语义。
 *
 * 用假的 [RuleStore] / [ScheduleHook] 记录**实际调用顺序**，而不是事后看数据库状态 ——
 * 顺序错了数据也可能「看起来对」，但系统层面会留下问题：
 * 先 schedule 后 cancel 会让新闹钟被旧闹钟抵消；
 * 不 backup 则用户在本地点「撤销」时拿不到远程改动前的旧值。
 */
class RemoteRuleApiTest {

    private val ops = mutableListOf<String>()

    private inner class FakeStore : RuleStore {
        val rules = linkedMapOf<Long, Automation>()
        val exceptions = mutableListOf<RuleException>()

        override fun getAll(): List<Automation> = rules.values.sortedByDescending { it.createdAt }
        override fun getById(id: Long): Automation? = rules[id]
        override fun update(a: Automation) {
            ops += "update:${a.id}"
            rules[a.id] = a
        }
        override fun exceptionsOf(ruleId: Long): List<RuleException> =
            exceptions.filter { it.ruleId == ruleId }.sortedBy { it.date }
        override fun addException(e: RuleException) {
            ops += "addException:${e.ruleId}:${e.date}"
            exceptions.removeAll { it.ruleId == e.ruleId && it.date == e.date }
            exceptions += e
        }
        override fun removeException(ruleId: Long, date: String) {
            ops += "removeException:$ruleId:$date"
            exceptions.removeAll { it.ruleId == ruleId && it.date == date }
        }
    }

    private inner class FakeHook : ScheduleHook {
        var lastScheduled: Automation? = null
        var lastExceptions: Set<String> = emptySet()

        override fun cancel(a: Automation) {
            ops += "cancel:${a.id}"
        }

        override fun schedule(a: Automation, exceptions: Set<String>) {
            ops += "schedule:${a.id}"
            lastScheduled = a
            lastExceptions = exceptions
        }
    }

    private val store = FakeStore()
    private val hook = FakeHook()

    /** 固定「现在」= 2026-10-08 06:00，规则时刻 07:00 尚未到，当天是合法候选。 */
    private val nowMs: Long = Calendar.getInstance().apply {
        set(2026, Calendar.OCTOBER, 8, 6, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun api(holidays: HolidayChecker = HolidayChecker.EMPTY) = RemoteRuleApi(
        store = store,
        hook = hook,
        backup = { ops += "backup:${it.id}" },
        holidays = { holidays },
        now = { nowMs }
    )

    private fun rule(
        mode: String = RepeatMode.DAILY,
        hour: Int = 7,
        mask: Int = 0,
        skipHolidays: Boolean = false,
        enabled: Boolean = true
    ) = Automation(
        id = 7,
        name = "飞书",
        targetPackage = "com.ss.android.lark",
        targetAppName = "飞书",
        triggerType = TriggerType.TIME,
        timeHour = hour,
        timeMinute = 0,
        repeatMode = mode,
        repeatDays = mask,
        skipHolidays = skipHolidays,
        enabled = enabled,
        createdAt = 1_600_000_000_000L
    )

    private fun seed(a: Automation) {
        store.rules[a.id] = a
    }

    private fun dateOf(ms: Long?): String? {
        if (ms == null) return null
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        return String.format(
            "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)
        )
    }

    private fun hourOf(ms: Long?): Int {
        if (ms == null) return -1
        return Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.HOUR_OF_DAY)
    }

    // ══════════════════════════════════════════════════════════════════
    // 执行序：这是本类最核心的断言
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `改期按 留档 写库 取消旧闹钟 重排新闹钟 的顺序执行`() {
        seed(rule())
        ops.clear()

        val r = api().patch(7, JSONObject("""{"timeHour":9}"""))
        assertTrue(r is RemoteRuleApi.WriteResult.Ok)

        assertEquals(
            listOf("backup:7", "update:7", "cancel:7", "schedule:7"),
            ops
        )
    }

    @Test
    fun `越权字段被拒绝时不动数据库也不碰闹钟`() {
        seed(rule())
        ops.clear()

        val r = api().patch(7, JSONObject("""{"targetPackage":"com.evil.app"}"""))
        assertTrue(r is RemoteRuleApi.WriteResult.Err)
        assertEquals(RemoteProtocol.E_FORBIDDEN_FIELD, (r as RemoteRuleApi.WriteResult.Err).code)
        assertTrue("非法请求产生了副作用: $ops", ops.isEmpty())
        assertEquals("com.ss.android.lark", store.rules[7]!!.targetPackage)
    }

    @Test
    fun `停用规则只取消闹钟不再重排`() {
        seed(rule())
        ops.clear()

        api().patch(7, JSONObject("""{"enabled":false}"""))
        assertEquals(listOf("backup:7", "update:7", "cancel:7"), ops)
        assertEquals(false, store.rules[7]!!.enabled)
    }

    @Test
    fun `停用的规则被改期时不会顺手启用`() {
        seed(rule(enabled = false))
        ops.clear()

        api().patch(7, JSONObject("""{"timeHour":9}"""))
        assertEquals(false, store.rules[7]!!.enabled)
        assertFalse("停用规则不该被排程", ops.contains("schedule:7"))
    }

    @Test
    fun `createdAt 不被改动`() {
        seed(rule())
        api().patch(7, JSONObject("""{"timeHour":9,"timeMinute":30}"""))
        // 列表按 createdAt DESC 排序；改期若刷新了它，规则会跳到最顶部
        assertEquals(1_600_000_000_000L, store.rules[7]!!.createdAt)
    }

    @Test
    fun `不存在的规则返回 404 且无副作用`() {
        ops.clear()
        val r = api().patch(999, JSONObject("""{"timeHour":9}"""))
        assertTrue(r is RemoteRuleApi.WriteResult.Err)
        assertEquals(RemoteProtocol.E_NOT_FOUND, (r as RemoteRuleApi.WriteResult.Err).code)
        assertTrue(ops.isEmpty())
    }

    // ══════════════════════════════════════════════════════════════════
    // 临时请假（单日豁免）
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `请假当天后下次触发跳到次日`() {
        seed(rule())
        // 没有豁免时：10-08 07:00
        assertEquals("2026-10-08", dateOf(api().dryRun(7, nowMs)))

        ops.clear()
        val r = api().addException(7, "2026-10-08", "请假")
        assertTrue(r is RemoteRuleApi.WriteResult.Ok)

        val ok = r as RemoteRuleApi.WriteResult.Ok
        assertEquals("加了豁免但下次触发仍落在当天", "2026-10-09", dateOf(ok.nextTriggerAt))
        assertEquals(7, hourOf(ok.nextTriggerAt))
    }

    @Test
    fun `请假后立刻重排而不是等下次触发`() {
        // 系统里已经存在一个按 10-08 07:00 排好的闹钟。
        // 如果只写库不重排，那个闹钟仍会准时响 —— 用户会认为"远程改期没生效"。
        seed(rule())
        ops.clear()

        api().addException(7, "2026-10-08", "请假")
        assertEquals(
            listOf("addException:7:2026-10-08", "cancel:7", "schedule:7"),
            ops
        )
        assertEquals(setOf("2026-10-08"), hook.lastExceptions)
    }

    @Test
    fun `请假不改动星期位图`() {
        // 「明天请假」是取消一天，不是取消所有周三。
        // 若实现成改 repeatDays，用户下周三就再也不会被启动了。
        val mask = 1 shl (Calendar.THURSDAY - 1)
        seed(rule(mode = RepeatMode.CUSTOM, mask = mask))

        api().addException(7, "2026-10-08", "请假")
        assertEquals("位图被改动了", mask, store.rules[7]!!.repeatDays)
    }

    @Test
    fun `恢复某一天后回到原节奏`() {
        seed(rule())
        api().addException(7, "2026-10-08", "请假")
        assertEquals("2026-10-09", dateOf(api().dryRun(7, nowMs)))

        ops.clear()
        val r = api().removeException(7, "2026-10-08")
        assertTrue(r is RemoteRuleApi.WriteResult.Ok)
        assertEquals("2026-10-08", dateOf((r as RemoteRuleApi.WriteResult.Ok).nextTriggerAt))
        assertEquals(
            listOf("removeException:7:2026-10-08", "cancel:7", "schedule:7"),
            ops
        )
    }

    @Test
    fun `恢复一个不存在的豁免返回 404`() {
        seed(rule())
        val r = api().removeException(7, "2026-12-25")
        assertTrue(r is RemoteRuleApi.WriteResult.Err)
        assertEquals("E_NO_EXCEPTION", (r as RemoteRuleApi.WriteResult.Err).error)
    }

    @Test
    fun `非法日期被拒绝`() {
        seed(rule())
        ops.clear()
        listOf("2026-02-30", "2026-13-01", "明天", "").forEach { bad ->
            val r = api().addException(7, bad, "")
            assertTrue("$bad 应被拒绝", r is RemoteRuleApi.WriteResult.Err)
            assertEquals("E_DATE", (r as RemoteRuleApi.WriteResult.Err).error)
        }
        assertTrue("非法日期产生了副作用: $ops", ops.isEmpty())
    }

    @Test
    fun `非定时规则不能设置请假`() {
        seed(rule().copy(triggerType = TriggerType.WIFI))
        val r = api().addException(7, "2026-10-08", "请假")
        assertTrue(r is RemoteRuleApi.WriteResult.Err)
        assertEquals("E_NOT_TIME", (r as RemoteRuleApi.WriteResult.Err).error)
    }

    @Test
    fun `重复请假同一天是幂等的`() {
        seed(rule())
        api().addException(7, "2026-10-08", "请假")
        api().addException(7, "2026-10-08", "请假2")
        assertEquals("同一天出现多条豁免", 1, store.exceptionsOf(7).size)
        assertEquals("请假2", store.exceptionsOf(7).first().reason)
    }

    @Test
    fun `连续请多天时逐日跳过`() {
        seed(rule())
        api().addException(7, "2026-10-08", "")
        api().addException(7, "2026-10-09", "")
        api().addException(7, "2026-10-10", "")
        assertEquals("2026-10-11", dateOf(api().dryRun(7, nowMs)))
    }

    // ══════════════════════════════════════════════════════════════════
    // 与节假日/调休的配合
    // ══════════════════════════════════════════════════════════════════

    private fun holidayChecker() = HolidayChecker(
        restDates = setOf("2026-10-01", "2026-10-02", "2026-10-03", "2026-10-04", "2026-10-05", "2026-10-06", "2026-10-07"),
        workdays = setOf("2026-09-20", "2026-10-10")
    )

    @Test
    fun `请假可以压过调休上班日`() {
        // 09-20 是周日·调休，weekdays 规则本来会被强制放行；用户请假 → 必须跳过
        seed(rule(mode = RepeatMode.WEEKDAYS, hour = 7))
        val from = Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, 20, 6, 0, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val before = api(holidayChecker()).dryRun(7, from)
        assertEquals("前提不成立：调休日没被放行", "2026-09-20", dateOf(before))

        api(holidayChecker()).addException(7, "2026-09-20", "请假")
        assertEquals("调休日没被请假压住", "2026-09-21", dateOf(api(holidayChecker()).dryRun(7, from)))
    }

    @Test
    fun `跳过节假日开关打开时请假与假期叠加`() {
        seed(rule(skipHolidays = true))
        val from = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 1, 6, 0, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        // 10-01..10-07 假期 → 节后第一个工作日 10-08
        assertEquals("2026-10-08", dateOf(api(holidayChecker()).dryRun(7, from)))

        api(holidayChecker()).addException(7, "2026-10-08", "请假")
        assertEquals("2026-10-09", dateOf(api(holidayChecker()).dryRun(7, from)))
    }

    // ══════════════════════════════════════════════════════════════════
    // 逐日预览（calendar）
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `日历预览标出请假当天不启动`() {
        seed(rule())
        api().addException(7, "2026-10-09", "请假")

        val days = api().calendar(7, 4)!!
        assertEquals(4, days.size)
        assertEquals("2026-10-08", days[0].date)
        assertTrue(days[0].willRun)
        assertEquals("SCHEDULED", days[0].reason)

        assertFalse("请假当天仍标记为会启动", days[1].willRun)
        assertEquals("EXCEPTED", days[1].reason)

        assertTrue(days[2].willRun)
    }

    @Test
    fun `日历预览标出调休上班日`() {
        seed(rule(mode = RepeatMode.WEEKDAYS))
        val from = Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, 19, 6, 0, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val api = RemoteRuleApi(
            store = store, hook = hook, backup = {},
            holidays = { holidayChecker() }, now = { from }
        )
        val days = api.calendar(7, 3)!!
        assertEquals("2026-09-19", days[0].date)   // 周六
        assertFalse(days[0].willRun)
        assertEquals("FILTERED", days[0].reason)

        assertEquals("2026-09-20", days[1].date)   // 周日·调休
        assertTrue("调休上班日没被标记为启动", days[1].willRun)
        assertEquals("MAKEUP_WORKDAY", days[1].reason)
    }

    @Test
    fun `日历预览标出法定休息日被跳过`() {
        seed(rule(skipHolidays = true))
        val from = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 1, 6, 0, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val api = RemoteRuleApi(
            store = store, hook = hook, backup = {},
            holidays = { holidayChecker() }, now = { from }
        )
        val days = api.calendar(7, 2)!!
        assertFalse(days[0].willRun)
        assertEquals("HOLIDAY_SKIPPED", days[0].reason)
    }

    @Test
    fun `停用的规则在预览里全部不启动`() {
        seed(rule(enabled = false))
        val days = api().calendar(7, 3)!!
        days.forEach {
            assertFalse("停用规则仍标记为启动: ${it.date}", it.willRun)
            assertEquals("DISABLED", it.reason)
        }
    }

    @Test
    fun `预览不存在的规则返回 null`() {
        assertNull(api().calendar(404, 7))
    }

    @Test
    fun `预览天数额外夹紧`() {
        seed(rule())
        assertEquals(1, api().calendar(7, -5)!!.size)
        assertEquals(60, api().calendar(7, 999)!!.size)
    }

    // ══════════════════════════════════════════════════════════════════
    // 展示辅助
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `位图翻成中文星期`() {
        val monWedFri = (1 shl (Calendar.MONDAY - 1)) or
            (1 shl (Calendar.WEDNESDAY - 1)) or
            (1 shl (Calendar.FRIDAY - 1))
        assertEquals("周一、周三、周五", RemoteRuleApi.repeatDaysText(monWedFri))
        assertEquals("", RemoteRuleApi.repeatDaysText(0))
        assertEquals("周日", RemoteRuleApi.repeatDaysText(1))
    }

    @Test
    fun `相对时间描述`() {
        val base = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 8, 6, 0, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val today7 = base + 3_600_000L
        assertEquals("今天 07:00", RemoteRuleApi.humanize(today7, base))
        assertEquals("明天 07:00", RemoteRuleApi.humanize(today7 + 86_400_000L, base))
        // 跨两天以上直接给日期，不编造"后天"这类容易算错的词
        assertTrue(RemoteRuleApi.humanize(today7 + 5 * 86_400_000L, base).startsWith("10-13"))
    }

    @Test
    fun `规则视图包含下次触发与豁免`() {
        seed(rule())
        api().addException(7, "2026-10-09", "请假")

        val view = api().viewOf(7)
        assertNotNull(view)
        assertEquals("2026-10-08", dateOf(view!!.optLong("nextTriggerAt")))
        assertEquals("今天 07:00", view.optString("nextTriggerText"))

        val ex = view.optJSONArray("exceptions")!!
        assertEquals(1, ex.length())
        assertEquals("2026-10-09", ex.optJSONObject(0).optString("date"))
        assertEquals("请假", ex.optJSONObject(0).optString("reason"))
    }
}
