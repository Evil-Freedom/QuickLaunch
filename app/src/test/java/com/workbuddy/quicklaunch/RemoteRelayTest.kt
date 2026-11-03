package com.workbuddy.quicklaunch

import com.workbuddy.quicklaunch.data.Automation
import com.workbuddy.quicklaunch.data.RepeatMode
import com.workbuddy.quicklaunch.data.RuleException
import com.workbuddy.quicklaunch.data.TriggerType
import com.workbuddy.quicklaunch.remote.B64
import com.workbuddy.quicklaunch.remote.RelayCommand
import com.workbuddy.quicklaunch.remote.RelayCommandExecutor
import com.workbuddy.quicklaunch.remote.RelayHttp
import com.workbuddy.quicklaunch.remote.RelayPoller
import com.workbuddy.quicklaunch.remote.RelaySettings
import com.workbuddy.quicklaunch.remote.RelayTransport
import com.workbuddy.quicklaunch.remote.RemoteAuth
import com.workbuddy.quicklaunch.remote.RemoteConfig
import com.workbuddy.quicklaunch.remote.RemoteRuleApi
import com.workbuddy.quicklaunch.remote.RuleStore
import com.workbuddy.quicklaunch.remote.ScheduleHook
import com.workbuddy.quicklaunch.util.HolidayChecker
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 备用通道（公网中继）。
 *
 * 这批测试的核心目的不是「跑一遍流程」，而是钉住三件事：
 *
 * 1. **控制端与设备端的密码学口径必须一致** —— 测试里的 [signedCommand] 刻意用
 *    「控制端视角」构造（先加密、再对信封 base64 文本签名），如果哪天有人
 *    把签名对象改成明文或改了分隔符，这里会立刻红。
 * 2. **中继通道不能成为第二个入口** —— `PAIR` / `PING` 必须被拒，否则公网可达的
 *    8 位配对码就成了暴力破解的目标。
 * 3. **重放必须死** —— 中继会重发、会重试，一条「取消明天启动」被执行两次不是灾难，
 *    但一条「改时间」被延迟重放会让人以为程序自己在改配置。
 */
class RemoteRelayTest {

    // ── 假依赖 ──────────────────────────────────────────────────────

    private inner class FakeStore : RuleStore {
        val rules = linkedMapOf<Long, Automation>()
        val exceptions = mutableListOf<RuleException>()
        val ops = mutableListOf<String>()

        override fun getAll(): List<Automation> = rules.values.toList()
        override fun getById(id: Long): Automation? = rules[id]
        override fun update(a: Automation) {
            ops += "update:${a.id}"
            rules[a.id] = a
        }
        override fun exceptionsOf(ruleId: Long): List<RuleException> =
            exceptions.filter { it.ruleId == ruleId }.sortedBy { it.date }
        override fun addException(e: RuleException) {
            exceptions.removeAll { it.ruleId == e.ruleId && it.date == e.date }
            exceptions += e
            ops += "addException:${e.ruleId}:${e.date}"
        }
        override fun removeException(ruleId: Long, date: String) {
            exceptions.removeAll { it.ruleId == ruleId && it.date == date }
            ops += "removeException:$ruleId:$date"
        }
    }

    private inner class FakeHook : ScheduleHook {
        var scheduled = 0
        var cancelled = 0
        override fun cancel(a: Automation) { cancelled++ }
        override fun schedule(a: Automation, exceptions: Set<String>) { scheduled++ }
    }

    /** 固定「现在」= 2026-10-08 06:00，规则 07:00 尚未到，当天是合法候选。 */
    private val nowMs: Long = Calendar.getInstance().apply {
        set(2026, Calendar.OCTOBER, 8, 6, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private val store = FakeStore()
    private val hook = FakeHook()
    private val secret = ByteArray(32) { (it + 1).toByte() }

    private fun api() = RemoteRuleApi(
        store = store,
        hook = hook,
        backup = { store.ops += "backup:${it.id}" },
        holidays = { HolidayChecker.EMPTY },
        now = { nowMs }
    )

    private fun executor() = RelayCommandExecutor(api = { api() }, now = { nowMs })

    private fun seedRule(id: Long = 7, hour: Int = 7) {
        store.rules[id] = Automation(
            id = id,
            name = "飞书",
            targetPackage = "com.ss.android.lark",
            targetAppName = "飞书",
            triggerType = TriggerType.TIME,
            timeHour = hour,
            timeMinute = 0,
            repeatMode = RepeatMode.CUSTOM,
            repeatDays = 0b0010100, // 二、四
            skipHolidays = false,
            enabled = true,
            createdAt = 1_600_000_000_000L
        )
    }

    // ── 控制端视角的构造器（刻意与生产代码分开写）──────────────────

    /**
     * 模拟「公司电脑上的控制端」：先 AES-GCM 加密 payload 得到信封，
     * 再对**信封的 base64 文本**签名。与 `tools/ql_relay.js` 的 buildCommand 同算法。
     */
    private fun signedCommand(
        method: String,
        ruleId: Long? = 7,
        payload: JSONObject? = null,
        ts: Long = nowMs,
        nonce: String = "0123456789abcdef",
        secretBytes: ByteArray = secret,
        pathOverride: String? = null
    ): RelayCommand {
        val path = pathOverride
            ?: if (method == "LIST") "/api/v1/rules" else "/api/v1/rules/$ruleId"
        val body = if (payload == null || payload.length() == 0) {
            ""
        } else {
            val env = RemoteAuth.encrypt(
                RemoteAuth.encKey(secretBytes),
                payload.toString().toByteArray(Charsets.UTF_8)
            )
            B64.encodeToString(env)
        }
        val sign = RemoteAuth.sign(
            secret = secretBytes,
            method = method,
            path = path,
            ts = ts,
            nonce = nonce,
            body = body.toByteArray(Charsets.US_ASCII)
        )
        return RelayCommand(
            id = nonce, method = method, path = path,
            ts = ts, nonce = nonce, sign = sign, body = body
        )
    }

    private fun outcomeOf(cmd: RelayCommand, ex: RelayCommandExecutor = executor()) =
        ex.execute(secret, cmd)

    private fun assertErr(expectedCode: Int, expectedError: String, o: RelayCommandExecutor.Outcome) {
        assertTrue("期望 Err，实际 $o", o is RelayCommandExecutor.Outcome.Err)
        val e = o as RelayCommandExecutor.Outcome.Err
        assertEquals(expectedCode, e.code)
        assertEquals(expectedError, e.error)
    }

    // ── 正常路径 ────────────────────────────────────────────────────

    @Test
    fun `PATCH 合法指令改掉时刻并重排`() {
        seedRule()
        val cmd = signedCommand("PATCH", payload = JSONObject().put("timeHour", 9).put("timeMinute", 30))
        val o = outcomeOf(cmd)

        assertTrue("期望 Ok，实际 $o", o is RelayCommandExecutor.Outcome.Ok)
        assertEquals(9, store.rules[7]!!.timeHour)
        assertEquals(30, store.rules[7]!!.timeMinute)
        // 与局域网一致：cancel → schedule 各一次
        assertEquals(1, hook.cancelled)
        assertEquals(1, hook.scheduled)
    }

    @Test
    fun `SKIP 合法指令加上单日豁免`() {
        seedRule()
        val cmd = signedCommand("SKIP", payload = JSONObject().put("date", "2026-10-14").put("reason", "请假"))
        val o = outcomeOf(cmd)

        assertTrue(o is RelayCommandExecutor.Outcome.Ok)
        assertEquals(1, store.exceptions.size)
        assertEquals("2026-10-14", store.exceptions[0].date)
        assertEquals(1, hook.scheduled)
    }

    @Test
    fun `RESTORE 合法指令删掉豁免`() {
        seedRule()
        store.exceptions += RuleException(ruleId = 7, date = "2026-10-14", reason = "请假")
        val cmd = signedCommand("RESTORE", payload = JSONObject().put("date", "2026-10-14"))
        val o = outcomeOf(cmd)

        assertTrue(o is RelayCommandExecutor.Outcome.Ok)
        assertTrue(store.exceptions.isEmpty())
    }

    @Test
    fun `LIST 返回全部规则`() {
        seedRule(7)
        seedRule(8)
        val o = outcomeOf(signedCommand("LIST", payload = null))

        assertTrue(o is RelayCommandExecutor.Outcome.Ok)
        val payload = (o as RelayCommandExecutor.Outcome.Ok).payload
        assertEquals(2, payload.getJSONArray("rules").length())
    }

    @Test
    fun `CALENDAR 返回逐日判定`() {
        seedRule()
        val o = outcomeOf(signedCommand("CALENDAR", payload = JSONObject().put("days", 7)))

        assertTrue(o is RelayCommandExecutor.Outcome.Ok)
        val days = (o as RelayCommandExecutor.Outcome.Ok).payload.getJSONArray("days")
        assertEquals(7, days.length())
        assertTrue(days.getJSONObject(0).getString("reason").isNotEmpty())
    }

    @Test
    fun `NEXT 干跑给出触发时刻`() {
        seedRule()
        val o = outcomeOf(signedCommand("NEXT", payload = JSONObject().put("at", nowMs)))

        assertTrue(o is RelayCommandExecutor.Outcome.Ok)
        val next = (o as RelayCommandExecutor.Outcome.Ok).payload.getLong("nextTriggerAt")
        assertTrue("下次触发应晚于 now", next > nowMs)
    }

    // ── 中继不能成为第二入口 ────────────────────────────────────────

    @Test
    fun `PAIR 被拒 —— 公网暴露面下不允许配对`() {
        assertErr(403, "E_METHOD_BLOCKED", outcomeOf(signedCommand("PAIR", payload = JSONObject().put("code", "12345678"))))
    }

    @Test
    fun `PING 被拒`() {
        assertErr(403, "E_METHOD_BLOCKED", outcomeOf(signedCommand("PING", pathOverride = "/api/v1/ping")))
    }

    @Test
    fun `方法白名单里确实没有 PAIR 和 PING`() {
        assertFalse(RelayCommandExecutor.ALLOWED_METHODS.contains("PAIR"))
        assertFalse(RelayCommandExecutor.ALLOWED_METHODS.contains("PING"))
        assertTrue(RelayCommandExecutor.ALLOWED_METHODS.containsAll(listOf("PATCH", "SKIP", "RESTORE", "LIST")))
    }

    // ── 密码学负向 ──────────────────────────────────────────────────

    @Test
    fun `篡改信封导致验签失败`() {
        seedRule()
        val cmd = signedCommand("PATCH", payload = JSONObject().put("timeHour", 9))
        // 把信封最后一个字符改掉：base64 仍然是合法字符，但内容变了
        val tampered = cmd.body.dropLast(1) + if (cmd.body.last() == 'A') "B" else "A"
        assertErr(401, "E_AUTH", outcomeOf(cmd.copy(body = tampered)))
    }

    @Test
    fun `篡改路径导致验签失败`() {
        seedRule()
        val cmd = signedCommand("PATCH", ruleId = 7, payload = JSONObject().put("timeHour", 9))
        assertErr(401, "E_AUTH", outcomeOf(cmd.copy(path = "/api/v1/rules/8")))
    }

    @Test
    fun `时间戳超出窗口被拒`() {
        seedRule()
        val stale = signedCommand(
            "PATCH",
            payload = JSONObject().put("timeHour", 9),
            ts = nowMs - RemoteAuth.MAX_SKEW_MS - 1_000
        )
        assertErr(401, "E_AUTH", outcomeOf(stale))
    }

    @Test
    fun `同一 nonce 重放被拒`() {
        seedRule()
        val ex = executor()
        val cmd = signedCommand("PATCH", payload = JSONObject().put("timeHour", 9))
        assertTrue(ex.execute(secret, cmd) is RelayCommandExecutor.Outcome.Ok)
        assertErr(401, "E_REPLAY", ex.execute(secret, cmd))
    }

    @Test
    fun `换一把 secret 签名被拒`() {
        seedRule()
        val other = ByteArray(32) { (it + 99).toByte() }
        assertErr(401, "E_AUTH", outcomeOf(signedCommand("PATCH", payload = JSONObject().put("timeHour", 9), secretBytes = other)))
    }

    @Test
    fun `没有密钥直接拒绝`() {
        assertErr(401, "E_AUTH", executor().execute(null, signedCommand("LIST")))
    }

    @Test
    fun `信封不是合法 base64 时报错但签名仍须先通过`() {
        seedRule()
        // 签名对象就是这串坏文本，所以验签能过，解密前才失败
        val bad = "!!!not-base64!!!"
        val sign = RemoteAuth.sign(secret, "PATCH", "/api/v1/rules/7", nowMs, "1122334455667788", bad.toByteArray(Charsets.US_ASCII))
        val cmd = RelayCommand(
            id = "1122334455667788", method = "PATCH", path = "/api/v1/rules/7",
            ts = nowMs, nonce = "1122334455667788", sign = sign, body = bad
        )
        assertErr(400, "E_BODY", outcomeOf(cmd))
    }

    // ── 参数与越权 ──────────────────────────────────────────────────

    @Test
    fun `越权字段在备用通道同样被拒`() {
        seedRule()
        val cmd = signedCommand("PATCH", payload = JSONObject().put("targetPackage", "com.evil"))
        assertErr(409, "E_FORBIDDEN_FIELD", outcomeOf(cmd))
        // 且数据库没有被改动
        assertEquals("com.ss.android.lark", store.rules[7]!!.targetPackage)
    }

    @Test
    fun `LIST 路径不对被拒`() {
        assertErr(400, "E_PATH", outcomeOf(signedCommand("LIST", pathOverride = "/api/v1/rules/7")))
    }

    @Test
    fun `路径里没有规则 id 被拒`() {
        assertErr(400, "E_ID", outcomeOf(signedCommand("PATCH", pathOverride = "/api/v1/rules/abc")))
    }

    @Test
    fun `规则不存在返回 404`() {
        // 没有 seedRule
        assertErr(404, "E_RULE_NOT_FOUND", outcomeOf(signedCommand("PATCH", payload = JSONObject().put("timeHour", 9))))
    }

    @Test
    fun `ruleIdOf 只认标准路径`() {
        assertEquals(7L, RelayCommandExecutor.ruleIdOf("/api/v1/rules/7"))
        assertEquals(7L, RelayCommandExecutor.ruleIdOf("/api/v1/rules/7/"))
        assertNull(RelayCommandExecutor.ruleIdOf("/api/v1/rules"))
        assertNull(RelayCommandExecutor.ruleIdOf("/api/v1/rules/7/exceptions"))
        assertNull(RelayCommandExecutor.ruleIdOf("/api/v1/rules/abc"))
        assertNull(RelayCommandExecutor.ruleIdOf("/other/rules/7"))
    }

    // ── 通道口径一致性 ──────────────────────────────────────────────

    /**
     * 局域网与备用通道签的必须是同一个串。
     *
     * 这条断言的现实意义：`tools/ql_relay.js` 是照着同一口径实现的。
     * 如果这里悄悄改了分隔符或签名对象，控制端脚本会全部失效，且现象是
     * 「所有指令都 401」，极难定位。
     */
    @Test
    fun `同一请求在两条通道上的签名完全一致`() {
        val body = "ZmFrZS1lbnZlbG9wZQ=="
        val lanSign = RemoteAuth.sign(secret, "PATCH", "/api/v1/rules/7", nowMs, "aabbccdd11223344", body.toByteArray())
        val relaySign = RemoteAuth.sign(secret, "PATCH", "/api/v1/rules/7", nowMs, "aabbccdd11223344", body.toByteArray())
        assertEquals(lanSign, relaySign)

        // 局域网那套 verify 也必须接受同一个签名（说明确实是同一口径，不是各写一份）
        assertTrue(
            RemoteAuth.verify(
                secret, "PATCH", "/api/v1/rules/7", nowMs.toString(),
                "aabbccdd11223344", lanSign, body.toByteArray(), nowMs
            )
        )
    }

    /**
     * 与 `tools/ql_relay.js` 的**跨语言互操作向量**。
     *
     * 期望值由 Node 用固定 IV 生成（生产代码里 IV 必须每次随机，这里固定只为可复现）。
     * 这是整条备用通道最容易悄悄坏掉的地方：一旦有人改了密钥派生 label、
     * canonical 分隔符、或者把签名对象从「信封 base64 文本」改成明文，
     * 现象是**公司电脑发出的每条指令都 401**，而在手机上完全看不出原因。
     * 这条断言把该风险钉死在构建期。
     */
    @Test
    fun `与 Node 中继客户端的密码学口径逐字节一致`() {
        val secretB64 = "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA="
        val bodyB64 = "AAECAwQFBgcICQoLfjOKIw5Svpzhh7PYkdYJZDar7Ht0PMvvNRWdiub1"
        val expectedSign = "s5sz+lLbQ2S9erMBjzq8cp2Fj9HgLxDeZN35GDsqQ/s="

        val s = B64.decode(secretB64)!!
        assertArrayEquals(ByteArray(32) { (it + 1).toByte() }, s)

        // ① 信封能解开，明文与 Node 端 JSON.stringify 的结果一致（无多余空格）
        val env = B64.decode(bodyB64)!!
        val plain = RemoteAuth.decrypt(RemoteAuth.encKey(s), env)!!
        assertEquals("""{"timeHour":9}""", String(plain, Charsets.UTF_8))

        // ② 签名逐字节一致 → 控制端脚本签出来的指令，设备端必然认
        assertEquals(
            expectedSign,
            RemoteAuth.sign(
                s, "PATCH", "/api/v1/rules/7", 1790000000000L,
                "0123456789abcdef", bodyB64.toByteArray(Charsets.US_ASCII)
            )
        )

        // ③ 这个向量也应当被 verify 接受，且能真正走完一次执行
        seedRule()
        val cmd = RelayCommand(
            id = "0123456789abcdef", method = "PATCH", path = "/api/v1/rules/7",
            ts = 1790000000000L, nonce = "0123456789abcdef",
            sign = expectedSign, body = bodyB64
        )
        val ex = RelayCommandExecutor(api = { api() }, now = { 1790000000000L })
        assertTrue(ex.execute(s, cmd) is RelayCommandExecutor.Outcome.Ok)
        assertEquals(9, store.rules[7]!!.timeHour)
    }

    // ── 轮询器 ──────────────────────────────────────────────────────

    private class FakeTransport(
        private val pullBody: (String) -> String? = { null },
        private val pullOk: Boolean = true
    ) : RelayTransport {
        val posts = mutableListOf<Pair<String, String>>()
        override fun post(url: String, json: String, token: String): RelayHttp {
            posts += url to json
            return if (url.endsWith("/ql/pull")) {
                RelayHttp(pullOk, if (pullOk) pullBody(json) else null)
            } else {
                RelayHttp(true, """{"ok":true}""")
            }
        }
    }

    private fun client() = RemoteConfig.Client(
        keyId = "k_test0001",
        secretB64 = B64.encodeToString(secret),
        name = "公司电脑",
        createdAt = 0L,
        lastUsedAt = 0L
    )

    private fun poller(
        transport: RelayTransport,
        settings: RelaySettings? = RelaySettings(true, "https://relay.example.com/", "", 15),
        clients: List<RemoteConfig.Client> = listOf(client()),
        audits: MutableList<String>? = null
    ) = RelayPoller(
        settings = { settings },
        clients = { clients },
        transport = transport,
        executor = { executor() },
        audit = { _, detail -> audits?.add(detail) },
        now = { nowMs }
    )

    private fun pullResponse(vararg cmds: RelayCommand, expired: Int = 0): String {
        val arr = JSONArray()
        cmds.forEach {
            arr.put(JSONObject().apply {
                put("id", it.id)
                put("method", it.method)
                put("path", it.path)
                put("ts", it.ts)
                put("nonce", it.nonce)
                put("sign", it.sign)
                put("body", it.body)
            })
        }
        return JSONObject().put("cmds", arr).put("expired", expired).toString()
    }

    @Test
    fun `轮询器执行拉到的指令并回传结果`() {
        seedRule()
        val cmd = signedCommand("SKIP", payload = JSONObject().put("date", "2026-10-14"))
        val t = FakeTransport(pullBody = { pullResponse(cmd) })

        val stats = poller(t).pollOnce()

        assertEquals(1, stats.pulled)
        assertEquals(1, stats.executed)
        assertEquals(0, stats.failed)
        assertEquals("2026-10-14", store.exceptions[0].date)

        // 必须有一条回传，且打到 /ql/result（内容是密文，所以只断言结构与身份）
        val resultPost = t.posts.firstOrNull { it.first.endsWith("/ql/result") }
        assertNotNull("没有回传结果", resultPost)
        assertTrue(resultPost!!.second.contains("k_test0001"))
        assertTrue(resultPost.second.contains("\"status\":200"))
    }

    @Test
    fun `轮询器把执行失败也回传`() {
        seedRule()
        // 越权字段 → 执行失败，但仍要有结果回去，否则控制端会一直等
        val bad = signedCommand("PATCH", payload = JSONObject().put("targetPackage", "com.evil"))
        val t = FakeTransport(pullBody = { pullResponse(bad) })

        val stats = poller(t).pollOnce()

        assertEquals(1, stats.failed)
        assertEquals(0, stats.executed)
        val resultPost = t.posts.first { it.first.endsWith("/ql/result") }
        assertTrue(resultPost.second.contains("E_FORBIDDEN_FIELD"))
    }

    @Test
    fun `关闭时一次请求都不发`() {
        val t = FakeTransport()
        // 关闭状态必须直接返回，不能有任何网络动作
        val stats = poller(t, settings = RelaySettings(false, "https://relay.example.com", "", 15)).pollOnce()

        assertEquals(0, stats.pulled)
        assertTrue(t.posts.isEmpty())
    }

    @Test
    fun `地址非法时一次请求都不发`() {
        val t = FakeTransport()
        val stats = poller(t, settings = RelaySettings(true, "relay.example.com", "", 15)).pollOnce()

        assertEquals(0, stats.pulled)
        assertTrue(t.posts.isEmpty())
    }

    @Test
    fun `没有已配对客户端时不发请求`() {
        val t = FakeTransport()
        val stats = poller(t, clients = emptyList()).pollOnce()

        assertEquals(0, stats.pulled)
        assertTrue(t.posts.isEmpty())
    }

    @Test
    fun `拉取失败不影响下一轮`() {
        val t = FakeTransport(pullOk = false)
        val audits = mutableListOf<String>()
        val stats = poller(t, audits = audits).pollOnce()

        assertEquals(0, stats.pulled)
        assertTrue(audits.any { it.contains("RELAY_PULL_FAIL") })
    }

    @Test
    fun `中继丢弃的过期指令会被记进审计`() {
        val t = FakeTransport(pullBody = { pullResponse(expired = 2) })
        val audits = mutableListOf<String>()
        poller(t, audits = audits).pollOnce()

        assertTrue("应记录过期条数，实际 $audits", audits.any { it.contains("RELAY_EXPIRED 2") })
    }

    @Test
    fun `地址规范化`() {
        assertEquals("https://a.example.com", RelayPoller.normalize(" https://a.example.com/ "))
        assertEquals("http://a.example.com", RelayPoller.normalize("http://a.example.com"))
        assertEquals("https://a.example.com/x", RelayPoller.normalize("https://a.example.com/x/"))
        assertNull(RelayPoller.normalize(""))
        assertNull(RelayPoller.normalize(null))
        assertNull(RelayPoller.normalize("a.example.com"))
        assertNull(RelayPoller.normalize("ftp://a.example.com"))
    }

    @Test
    fun `多客户端各自拉自己的信箱`() {
        seedRule()
        val c2 = client().copy(keyId = "k_test0002")
        val t = FakeTransport(pullBody = { pullResponse() })

        poller(t, clients = listOf(client(), c2)).pollOnce()

        val pulls = t.posts.filter { it.first.endsWith("/ql/pull") }
        assertEquals(2, pulls.size)
        assertTrue(pulls.any { it.second.contains("k_test0001") })
        assertTrue(pulls.any { it.second.contains("k_test0002") })
    }
}
