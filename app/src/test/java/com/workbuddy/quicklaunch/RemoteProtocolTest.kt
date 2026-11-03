package com.workbuddy.quicklaunch

import com.workbuddy.quicklaunch.data.Automation
import com.workbuddy.quicklaunch.data.RepeatMode
import com.workbuddy.quicklaunch.data.TriggerType
import com.workbuddy.quicklaunch.remote.RemoteProtocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 远程报文的字段校验。
 *
 * 这里最关键的不是「合法值能通过」，而是**越权字段必须被拒绝**：
 * 远程接口的全部安全收益建立在一个前提上 ——
 * 即使密钥泄露，攻击者也只能改「几点启动」，不能改「启动哪个应用」。
 * 一旦 `targetPackage` 能从远程改，这个接口就变成了远程执行原语。
 */
class RemoteProtocolTest {

    private fun rule() = Automation(
        id = 7,
        name = "飞书",
        targetPackage = "com.ss.android.lark",
        targetAppName = "飞书",
        triggerType = TriggerType.TIME,
        timeHour = 7,
        timeMinute = 0,
        repeatMode = RepeatMode.DAILY,
        repeatDays = 0,
        skipHolidays = false,
        enabled = true,
        createdAt = 1_700_000_000_000L
    )

    private fun patch(json: String): RemoteProtocol.Result =
        RemoteProtocol.mergePatch(rule(), JSONObject(json))

    private fun ok(json: String): Automation {
        val r = patch(json)
        assertTrue("期望成功，实际 ${(r as? RemoteProtocol.Result.Err)?.detail}", r is RemoteProtocol.Result.Ok)
        return (r as RemoteProtocol.Result.Ok).automation
    }

    private fun err(json: String): RemoteProtocol.Result.Err {
        val r = patch(json)
        assertTrue("期望失败，实际 $r", r is RemoteProtocol.Result.Err)
        return r as RemoteProtocol.Result.Err
    }

    // ══════════════════════════════════════════════════════════════════
    // 越权字段：这是安全边界本身
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `拒绝修改目标应用`() {
        val e = err("""{"targetPackage":"com.evil.app"}""")
        assertEquals(RemoteProtocol.E_FORBIDDEN_FIELD, e.code)
        assertEquals("E_FORBIDDEN_FIELD", e.error)
    }

    @Test
    fun `拒绝修改触发类型`() {
        // 把 TIME 改成 WIFI 会让这条规则彻底脱离定时排程，等价于远程停用它
        assertEquals(RemoteProtocol.E_FORBIDDEN_FIELD, err("""{"triggerType":"WIFI"}""").code)
    }

    @Test
    fun `拒绝修改 createdAt`() {
        // createdAt 决定列表顺序，且是"这条规则什么时候建的"的事实记录
        assertEquals(RemoteProtocol.E_FORBIDDEN_FIELD, err("""{"createdAt":1}""").code)
    }

    @Test
    fun `拒绝修改 id`() {
        assertEquals(RemoteProtocol.E_FORBIDDEN_FIELD, err("""{"id":99}""").code)
    }

    @Test
    fun `合法字段与越权字段混在一起时整体拒绝`() {
        // 不能"应用合法的、忽略非法的" —— 那会让攻击者用合法字段掩护越权字段试探
        val e = err("""{"timeHour":9,"targetPackage":"com.evil.app"}""")
        assertEquals(RemoteProtocol.E_FORBIDDEN_FIELD, e.code)
    }

    // ══════════════════════════════════════════════════════════════════
    // 取值范围
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `时刻越界被拒绝`() {
        listOf(
            """{"timeHour":24}""",
            """{"timeHour":-1}""",
            """{"timeMinute":60}""",
            """{"timeMinute":-5}"""
        ).forEach { assertEquals("$it 应被拒绝", 400, err(it).code) }
    }

    @Test
    fun `时刻类型错误被拒绝`() {
        assertEquals(400, err("""{"timeHour":"8"}""").code)
        assertEquals(400, err("""{"timeHour":8.5}""").code)
        assertEquals(400, err("""{"enabled":"true"}""").code)
    }

    @Test
    fun `repeatMode 只接受全小写枚举`() {
        // 🔴 反直觉陷阱：repeatMode 全小写、triggerType 全大写。
        // 写成 "Daily" 时 when 匹配不到 → 静默不做星期过滤 → 看起来像"排程算错"。
        assertEquals(400, err("""{"repeatMode":"Daily"}""").code)
        assertEquals(400, err("""{"repeatMode":"DAILY"}""").code)
        assertEquals(400, err("""{"repeatMode":"monthly"}""").code)
        assertTrue(patch("""{"repeatMode":"weekdays"}""") is RemoteProtocol.Result.Ok)
    }

    @Test
    fun `custom 模式必须至少勾一天`() {
        val e = err("""{"repeatMode":"custom","repeatDays":0}""")
        assertEquals("E_MASK", e.error)
    }

    @Test
    fun `custom 模式接受合法位图`() {
        val a = ok("""{"repeatMode":"custom","repeatDays":42}""")
        assertEquals(RepeatMode.CUSTOM, a.repeatMode)
        assertEquals(42, a.repeatDays)
    }

    @Test
    fun `位图超过 7 位时被截断`() {
        // 0xFF 只保留 bit0..bit6。不做清洗会让"星期几"判定拿到越界位
        val a = ok("""{"repeatMode":"custom","repeatDays":255}""")
        assertEquals(127, a.repeatDays)
    }

    @Test
    fun `非 custom 模式位图归零`() {
        // 与本地保存口径一致：切回 daily 后残留在库里的位图会让后续判定莫名其妙
        val a = ok("""{"repeatMode":"daily","repeatDays":42}""")
        assertEquals(0, a.repeatDays)
    }

    @Test
    fun `随机窗口起止相同被拒绝`() {
        val e = err("""{"randomWindow":true,"windowStartMin":480,"windowEndMin":480}""")
        assertEquals("E_WINDOW", e.error)
    }

    @Test
    fun `随机窗口乱序时自动交换`() {
        val a = ok("""{"randomWindow":true,"windowStartMin":600,"windowEndMin":480}""")
        assertEquals(480, a.windowStartMin)
        assertEquals(600, a.windowEndMin)
    }

    @Test
    fun `关闭随机窗口时区间归零`() {
        val a = ok("""{"randomWindow":false,"windowStartMin":600,"windowEndMin":480}""")
        assertEquals(0, a.windowStartMin)
        assertEquals(0, a.windowEndMin)
    }

    // ══════════════════════════════════════════════════════════════════
    // 未传字段必须保留原值（尤其 enabled）
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `未传 enabled 时保持原来的停用状态`() {
        // Automation 的默认值是 true。若这里取默认值，用户远程改个时间
        // 会把自己停用的规则悄悄重新启用 —— 这是本项目明令禁止过的 bug 类型。
        val disabled = rule().copy(enabled = false)
        val r = RemoteProtocol.mergePatch(disabled, JSONObject("""{"timeHour":9}"""))
        assertTrue(r is RemoteProtocol.Result.Ok)
        assertEquals(false, (r as RemoteProtocol.Result.Ok).automation.enabled)
    }

    @Test
    fun `未传的字段保持原值`() {
        val a = ok("""{"timeHour":9}""")
        assertEquals(9, a.timeHour)
        assertEquals(0, a.timeMinute)
        assertEquals(RepeatMode.DAILY, a.repeatMode)
        assertEquals("com.ss.android.lark", a.targetPackage)
        assertEquals(1_700_000_000_000L, a.createdAt)
    }

    @Test
    fun `空报文等价于不改任何东西`() {
        val a = ok("""{}""")
        assertEquals(rule(), a)
    }

    // ══════════════════════════════════════════════════════════════════
    // 日期校验
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `日期只接受真实存在的 yyyy-MM-dd`() {
        assertNotNull(RemoteProtocol.parseDate("2026-11-04"))
        assertNotNull(RemoteProtocol.parseDate("2024-02-29"))   // 闰年
        assertNull("2026 不是闰年，2-29 不存在", RemoteProtocol.parseDate("2026-02-29"))
        assertNull("2 月没有 30 号", RemoteProtocol.parseDate("2026-02-30"))
        assertNull("4 月没有 31 号", RemoteProtocol.parseDate("2026-04-31"))
        assertNull(RemoteProtocol.parseDate("2026-13-01"))
        assertNull(RemoteProtocol.parseDate("2026-00-10"))
        assertNull(RemoteProtocol.parseDate("26-11-04"))
        assertNull(RemoteProtocol.parseDate("2026-1-4"))
        assertNull(RemoteProtocol.parseDate("2026/11/04"))
        assertNull(RemoteProtocol.parseDate(""))
        assertNull(RemoteProtocol.parseDate(null))
    }

    @Test
    fun `日期必须能被 HolidayChecker 的格式比对命中`() {
        // 豁免记录与排程判定靠字符串比对，一旦格式不一致会静默失效
        val d = RemoteProtocol.parseDate("2026-09-08")
        assertEquals("2026-09-08", d)
        assertTrue(Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(d!!))
    }
}
