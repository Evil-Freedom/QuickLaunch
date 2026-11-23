package com.workbuddy.quicklaunch

import com.workbuddy.quicklaunch.remote.B64
import com.workbuddy.quicklaunch.remote.RemoteProtocol
import com.workbuddy.quicklaunch.remote.vps.VpsEnvelope
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VPS 配置信封的校验测试。
 *
 * 这些用例守的是「远程配置不可能被伪造/回放/回滚」这条线：
 * 每一个 `Err` 分支都对应一种真实的攻击或故障，删掉任何一条都会让对应的
 * 防护变成摆设。
 */
class VpsEnvelopeTest {

    private val secret = ByteArray(32) { it.toByte() }
    private val deviceId = "dev_test_001"
    private val nonce = "a1b2c3d4e5f60718"
    private val now = 1_763_970_000_000L
    private val version = 7L

    /** 构造一个签名完全合法的响应。 */
    private fun envelope(
        payload: String = """{"rules":[{"id":2,"timeHour":8,"timeMinute":30}],"retry":{"maxAttempts":3}}""",
        ver: Long = version,
        issuedAt: Long = now,
        echoNonce: String = nonce,
        updated: Boolean = true,
        alg: String = VpsEnvelope.ALG,
        secretKey: ByteArray = secret
    ): String {
        val payloadB64 = B64.encodeToString(payload.toByteArray(Charsets.UTF_8))
        val sign = VpsEnvelope.signature(secretKey, alg, ver, issuedAt, echoNonce, payloadB64)
        return JSONObject().apply {
            put("alg", alg)
            put("version", ver)
            put("issuedAt", issuedAt)
            put("nonce", echoNonce)
            put("updated", updated)
            put("payload", payloadB64)
            put("sign", sign)
        }.toString()
    }

    // ── 正常路径 ────────────────────────────────────────────────────

    @Test
    fun `合法响应应解析出规则与重试策略`() {
        val r = VpsEnvelope.parse(envelope(), secret, nonce, appliedVersion = 6, now = now)
        assertTrue("期望 Ok，实际 $r", r is VpsEnvelope.Result.Ok)
        r as VpsEnvelope.Result.Ok
        assertEquals(7L, r.version)
        assertEquals(1, r.rules.size)
        assertEquals(2L, r.rules[0].getLong("id"))
        assertEquals(3, r.retry!!.getInt("maxAttempts"))
    }

    @Test
    fun `规则字段可以直接交给 RemoteProtocol 合并`() {
        // 这是「两条通道共用同一套校验」的守卫：VPS 下发的规则体必须能被
        // mergePatch 接受（去掉 id 之后），否则远程改规则会静默失败。
        val r = VpsEnvelope.parse(envelope(), secret, nonce, appliedVersion = 0, now = now)
            as VpsEnvelope.Result.Ok
        val body = JSONObject(r.rules[0].toString()).apply { remove("id") }
        val old = com.workbuddy.quicklaunch.data.Automation(
            id = 2, name = "飞书", targetPackage = "com.ss.android.lark",
            targetAppName = "飞书", triggerType = "TIME"
        )
        val merged = RemoteProtocol.mergePatch(old, body)
        assertTrue("合并应成功，实际 $merged", merged is RemoteProtocol.Result.Ok)
        assertEquals(8, (merged as RemoteProtocol.Result.Ok).automation.timeHour)
        assertEquals(30, merged.automation.timeMinute)
    }

    // ── 伪造与篡改 ──────────────────────────────────────────────────

    @Test
    fun `签名错误必须拒绝`() {
        val bad = ByteArray(32) { (it + 1).toByte() }
        val r = VpsEnvelope.parse(envelope(secretKey = bad), secret, nonce, 0, now)
        assertTrue(r is VpsEnvelope.Result.Err)
        assertEquals("E_SIGN", (r as VpsEnvelope.Result.Err).reason)
    }

    @Test
    fun `篡改 payload 后签名失效`() {
        val original = JSONObject(envelope())
        // 攻击者把「8:30 启动」改成「23:59 启动」
        val payloadB64 = original.getString("payload")
        val json = String(B64.decode(payloadB64)!!)
        val tampered = json.replace("\"timeHour\":8", "\"timeHour\":23")
        original.put("payload", B64.encodeToString(tampered.toByteArray(Charsets.UTF_8)))

        val r = VpsEnvelope.parse(original.toString(), secret, nonce, 0, now)
        assertTrue(r is VpsEnvelope.Result.Err)
        assertEquals("E_SIGN", (r as VpsEnvelope.Result.Err).reason)
    }

    @Test
    fun `篡改版本号后签名失效`() {
        val obj = JSONObject(envelope())
        obj.put("version", 999L)
        val r = VpsEnvelope.parse(obj.toString(), secret, nonce, 0, now)
        assertEquals("E_SIGN", (r as VpsEnvelope.Result.Err).reason)
    }

    @Test
    fun `拒绝非 HMAC-SHA256 的算法声明`() {
        val r = VpsEnvelope.parse(envelope(alg = "NONE"), secret, nonce, 0, now)
        assertTrue(r is VpsEnvelope.Result.Err)
        assertEquals("E_ALG", (r as VpsEnvelope.Result.Err).reason)
    }

    // ── 重放 ────────────────────────────────────────────────────────

    @Test
    fun `nonce 不匹配视为重放`() {
        val old = envelope(echoNonce = "0000000000000000")
        val r = VpsEnvelope.parse(old, secret, sentNonce = nonce, appliedVersion = 0, now = now)
        assertTrue(r is VpsEnvelope.Result.Err)
        assertEquals("E_NONCE", (r as VpsEnvelope.Result.Err).reason)
    }

    @Test
    fun `超出发行时间窗的配置被拒`() {
        val stale = envelope(issuedAt = now - VpsEnvelope.MAX_SKEW_MS - 1)
        val r = VpsEnvelope.parse(stale, secret, nonce, 0, now)
        assertTrue(r is VpsEnvelope.Result.Err)
        assertEquals("E_STALE", (r as VpsEnvelope.Result.Err).reason)
    }

    @Test
    fun `窗口边界内的轻微时钟偏差可接受`() {
        val slightlyOld = envelope(issuedAt = now - VpsEnvelope.MAX_SKEW_MS + 1_000)
        val r = VpsEnvelope.parse(slightlyOld, secret, nonce, 0, now)
        assertTrue(r is VpsEnvelope.Result.Ok)
    }

    // ── 版本推进 ────────────────────────────────────────────────────

    @Test
    fun `版本回退被拒绝`() {
        // 本地已经是 v7，服务端给 v5：典型的重放旧配置攻击
        val r = VpsEnvelope.parse(envelope(ver = 5), secret, nonce, appliedVersion = 7, now = now)
        assertTrue(r is VpsEnvelope.Result.Err)
        assertEquals("E_ROLLBACK", (r as VpsEnvelope.Result.Err).reason)
    }

    @Test
    fun `同版本重复下发视为无变化而不是错误`() {
        // 服务端显式说 updated=false：正常应答，客户端应该安静跳过
        val r = VpsEnvelope.parse(
            envelope(ver = 7, updated = false), secret, nonce, appliedVersion = 7, now = now
        )
        assertTrue(r is VpsEnvelope.Result.NoChange)
        assertEquals(7L, (r as VpsEnvelope.Result.NoChange).version)
    }

    @Test
    fun `首次同步 appliedVersion 为 0 时任何正版本都可应用`() {
        val r = VpsEnvelope.parse(envelope(ver = 1), secret, nonce, appliedVersion = 0, now = now)
        assertTrue(r is VpsEnvelope.Result.Ok)
    }

    // ── 格式容错 ────────────────────────────────────────────────────

    @Test
    fun `非 JSON 响应不崩且报明确原因`() {
        val r = VpsEnvelope.parse("<html>502 Bad Gateway</html>", secret, nonce, 0, now)
        assertTrue(r is VpsEnvelope.Result.Err)
        assertEquals("E_JSON", (r as VpsEnvelope.Result.Err).reason)
    }

    @Test
    fun `payload 不是合法 Base64 时拒绝`() {
        val obj = JSONObject(envelope())
        obj.put("payload", "!!!not-base64!!!")
        // 签名会先失败（因为 payload 变了），这也是可接受的结果：
        // 关键是不要抛异常、也不要把坏数据当成有效配置
        val r = VpsEnvelope.parse(obj.toString(), secret, nonce, 0, now)
        assertTrue(r is VpsEnvelope.Result.Err)
    }

    @Test
    fun `payload 内部结构缺失时 rules 为空但版本仍可用`() {
        // 服务端只下发重试策略、不改任何规则是合法场景
        val r = VpsEnvelope.parse(
            envelope(payload = """{"retry":{"maxAttempts":2}}"""),
            secret, nonce, 0, now
        )
        assertTrue(r is VpsEnvelope.Result.Ok)
        r as VpsEnvelope.Result.Ok
        assertTrue(r.rules.isEmpty())
        assertEquals(2, r.retry!!.getInt("maxAttempts"))
    }

    @Test
    fun `缺少 issuedAt 直接拒绝`() {
        val obj = JSONObject(envelope())
        obj.remove("issuedAt")
        val r = VpsEnvelope.parse(obj.toString(), secret, nonce, 0, now)
        assertTrue(r is VpsEnvelope.Result.Err)
        assertEquals("E_ISSUED_AT", (r as VpsEnvelope.Result.Err).reason)
    }

    @Test
    fun `缺少 version 直接拒绝`() {
        val obj = JSONObject(envelope())
        obj.remove("version")
        val r = VpsEnvelope.parse(obj.toString(), secret, nonce, 0, now)
        assertTrue(r is VpsEnvelope.Result.Err)
        assertEquals("E_VERSION", (r as VpsEnvelope.Result.Err).reason)
    }
}
