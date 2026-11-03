package com.workbuddy.quicklaunch

import com.workbuddy.quicklaunch.remote.B64
import com.workbuddy.quicklaunch.remote.RemoteAuth
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 鉴权与报文加密。
 *
 * 这里每一条都对应一种具体的绕过方式：
 * - 改 body → 签名失配
 * - 改路径 → 签名失配（防止把「改时间」重定向成别的操作）
 * - 重放 → nonce 已被消费
 * - 过期抓包 → 时间窗拒绝
 * - 换密钥 → HMAC 失配 + GCM tag 失配
 */
class RemoteAuthTest {

    private val secret = ByteArray(32) { (it + 1).toByte() }
    private val otherSecret = ByteArray(32) { (it + 9).toByte() }

    private fun sign(
        method: String = "PATCH",
        path: String = "/api/v1/rules/7",
        ts: Long = 1_700_000_000_000L,
        nonce: String = "n1n1n1n1",
        body: ByteArray = ByteArray(0),
        key: ByteArray = secret
    ) = RemoteAuth.sign(key, method, path, ts, nonce, body)

    // ══════════════════════════════════════════════════════════════════
    // 签名
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `正确签名通过校验`() {
        val body = """{"timeHour":9}""".toByteArray()
        assertTrue(
            RemoteAuth.verify(
                secret, "PATCH", "/api/v1/rules/7", "1700000000000", "n1n1n1n1", sign(body = body), body,
                now = 1_700_000_000_000L
            )
        )
    }

    @Test
    fun `篡改请求体导致验签失败`() {
        val body = """{"timeHour":9}""".toByteArray()
        val sig = sign(body = body)
        val tampered = """{"timeHour":8}""".toByteArray()
        assertFalse(
            RemoteAuth.verify(
                secret, "PATCH", "/api/v1/rules/7", "1700000000000", "n1n1n1n1", sig, tampered,
                now = 1_700_000_000_000L
            )
        )
    }

    @Test
    fun `篡改路径导致验签失败`() {
        // 若路径不在签名内，攻击者可以把「请假某一天」的报文重定向成「改整条规则」
        val body = ByteArray(0)
        val sig = sign(path = "/api/v1/rules/7", body = body)
        assertFalse(
            RemoteAuth.verify(
                secret, "PATCH", "/api/v1/rules/8", "1700000000000", "n1n1n1n1", sig, body,
                now = 1_700_000_000_000L
            )
        )
    }

    @Test
    fun `篡改方法导致验签失败`() {
        val body = ByteArray(0)
        val sig = sign(method = "GET", body = body)
        assertFalse(
            RemoteAuth.verify(
                secret, "DELETE", "/api/v1/rules/7", "1700000000000", "n1n1n1n1", sig, body,
                now = 1_700_000_000_000L
            )
        )
    }

    @Test
    fun `错误密钥导致验签失败`() {
        val body = ByteArray(0)
        val sig = sign(body = body, key = otherSecret)
        assertFalse(
            RemoteAuth.verify(
                secret, "PATCH", "/api/v1/rules/7", "1700000000000", "n1n1n1n1", sig, body,
                now = 1_700_000_000_000L
            )
        )
    }

    @Test
    fun `时间戳超出窗口导致验签失败`() {
        val body = ByteArray(0)
        val sig = sign(ts = 1_700_000_000_000L, body = body)
        // 偏 301 秒
        assertFalse(
            RemoteAuth.verify(
                secret, "PATCH", "/api/v1/rules/7", "1700000000000", "n1n1n1n1", sig, body,
                now = 1_700_000_000_000L + 301_000L
            )
        )
        // 偏 299 秒仍在窗内
        assertTrue(
            RemoteAuth.verify(
                secret, "PATCH", "/api/v1/rules/7", "1700000000000", "n1n1n1n1", sig, body,
                now = 1_700_000_000_000L + 299_000L
            )
        )
    }

    @Test
    fun `缺少任一鉴权头都失败`() {
        val body = ByteArray(0)
        val sig = sign(body = body)
        listOf<Pair<String?, String?>>(
            null to "n1n1n1n1",
            "1700000000000" to null,
            "" to "n1n1n1n1"
        ).forEach { (ts, nonce) ->
            assertFalse(
                RemoteAuth.verify(secret, "PATCH", "/api/v1/rules/7", ts, nonce, sig, body, 1_700_000_000_000L)
            )
        }
        assertFalse(RemoteAuth.verify(secret, "PATCH", "/api/v1/rules/7", "1700000000000", "n1n1n1n1", null, body, 1_700_000_000_000L))
    }

    @Test
    fun `非法 base64 签名不崩溃只返回失败`() {
        assertFalse(
            RemoteAuth.verify(
                secret, "PATCH", "/x", "1700000000000", "n1n1n1n1", "!!!not-base64!!!",
                ByteArray(0), 1_700_000_000_000L
            )
        )
    }

    // ══════════════════════════════════════════════════════════════════
    // 防重放
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `同一 nonce 只能用一次`() {
        val cache = RemoteAuth.NonceCache()
        val now = 1_700_000_000_000L
        assertTrue(cache.useOnce("abc12345", now))
        assertFalse("重放没有被拦住", cache.useOnce("abc12345", now + 1000))
    }

    @Test
    fun `不同 nonce 互不影响`() {
        val cache = RemoteAuth.NonceCache()
        val now = 1_700_000_000_000L
        assertTrue(cache.useOnce("abc12345", now))
        assertTrue(cache.useOnce("abc12346", now))
    }

    @Test
    fun `过期 nonce 可被回收`() {
        val cache = RemoteAuth.NonceCache(ttlMs = 1000L, capacity = 8)
        val now = 1_700_000_000_000L
        assertTrue(cache.useOnce("abc12345", now))
        // 超过 TTL 后该记录被清理，窗口外的重放本来就过不了时间戳校验，
        // 这里只验证"缓存不会无限增长"
        assertTrue(cache.useOnce("zzz99999", now + 5000))
        assertTrue(cache.size() <= 8)
    }

    @Test
    fun `容量上限防止内存膨胀`() {
        val cache = RemoteAuth.NonceCache(ttlMs = 60_000L, capacity = 4)
        val now = 1_700_000_000_000L
        repeat(50) { cache.useOnce("nonce$it", now) }
        assertTrue("容量没有上限: ${cache.size()}", cache.size() <= 4)
    }

    // ══════════════════════════════════════════════════════════════════
    // 报文加密
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `AES-GCM 加解密往返`() {
        val key = RemoteAuth.encKey(secret)
        val plain = """{"date":"2026-11-04","reason":"请假"}""".toByteArray()
        val envelope = RemoteAuth.encrypt(key, plain)
        assertArrayEquals(plain, RemoteAuth.decrypt(key, envelope))
    }

    @Test
    fun `密文被篡改则解密失败`() {
        val key = RemoteAuth.encKey(secret)
        val envelope = RemoteAuth.encrypt(key, "hello".toByteArray())
        envelope[envelope.size - 1] = (envelope[envelope.size - 1].toInt() xor 0xFF).toByte()
        assertNull("GCM tag 没有发现篡改", RemoteAuth.decrypt(key, envelope))
    }

    @Test
    fun `错误密钥解密失败`() {
        val envelope = RemoteAuth.encrypt(RemoteAuth.encKey(secret), "hello".toByteArray())
        assertNull(RemoteAuth.decrypt(RemoteAuth.encKey(otherSecret), envelope))
    }

    @Test
    fun `每次加密的 IV 不同`() {
        val key = RemoteAuth.encKey(secret)
        val a = RemoteAuth.encrypt(key, "same".toByteArray())
        val b = RemoteAuth.encrypt(key, "same".toByteArray())
        assertFalse("IV 复用了，GCM 安全性被破坏", a.contentEquals(b))
    }

    @Test
    fun `过短的报文不会崩溃`() {
        val key = RemoteAuth.encKey(secret)
        assertNull(RemoteAuth.decrypt(key, ByteArray(0)))
        assertNull(RemoteAuth.decrypt(key, ByteArray(5)))
    }

    @Test
    fun `macKey 与 encKey 互相独立`() {
        // 两把子密钥相同会让"能验签"等价于"能解密"，失去分离的意义
        assertFalse(RemoteAuth.macKey(secret).contentEquals(RemoteAuth.encKey(secret)))
    }

    // ══════════════════════════════════════════════════════════════════
    // 来源限制
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `只放行私网与回环地址`() {
        listOf(
            "192.168.1.5", "10.0.0.8", "172.16.3.4", "172.31.255.1",
            "127.0.0.1", "169.254.1.1", "::1", "fe80::1", "fd00::1"
        ).forEach { assertTrue("$it 应被放行", RemoteAuth.isAllowedSource(it)) }

        listOf(
            "8.8.8.8", "1.1.1.1", "172.15.0.1", "172.32.0.1",
            "203.0.113.7", "2001:4860:4860::8888", "", "not-an-ip"
        ).forEach { assertFalse("$it 不该被放行", RemoteAuth.isAllowedSource(it)) }

        assertFalse(RemoteAuth.isAllowedSource(null))
    }

    @Test
    fun `Tailscale 网段需显式开启`() {
        assertFalse(RemoteAuth.isAllowedSource("100.64.1.2"))
        assertTrue(RemoteAuth.isAllowedSource("100.64.1.2", allowTailscale = true))
        assertTrue(RemoteAuth.isAllowedSource("100.127.0.9", allowTailscale = true))
        assertFalse("100.128 不在 100.64/10 内", RemoteAuth.isAllowedSource("100.128.0.9", allowTailscale = true))
        // 即使开了 Tailscale，公网地址仍应被拒绝
        assertFalse(RemoteAuth.isAllowedSource("8.8.8.8", allowTailscale = true))
    }

    @Test
    fun `IPv6 zone id 不影响判定`() {
        assertTrue(RemoteAuth.isAllowedSource("fe80::1%wlan0"))
    }

    // ══════════════════════════════════════════════════════════════════
    // 限流
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `连续失败达到阈值后锁定`() {
        val limiter = RemoteAuth.RateLimiter(maxFailures = 5, windowMs = 60_000L, lockMs = 300_000L)
        val ip = "192.168.1.23"
        val now = 1_700_000_000_000L
        repeat(4) { limiter.recordFailure(ip, now) }
        assertFalse("还没到阈值就被锁了", limiter.isLocked(ip, now))
        limiter.recordFailure(ip, now)
        assertTrue("达到阈值没有锁定", limiter.isLocked(ip, now))
        assertTrue("锁定期内提前解锁了", limiter.isLocked(ip, now + 100_000L))
        assertFalse("锁定期结束后没解锁", limiter.isLocked(ip, now + 400_000L))
    }

    @Test
    fun `成功一次即清除该 IP 的失败计数`() {
        val limiter = RemoteAuth.RateLimiter(maxFailures = 2, windowMs = 60_000L, lockMs = 300_000L)
        val ip = "10.0.0.2"
        val now = 1_700_000_000_000L
        limiter.recordFailure(ip, now)
        limiter.recordSuccess(ip)
        limiter.recordFailure(ip, now)
        assertFalse("成功之后仍被历史失败计数影响", limiter.isLocked(ip, now))
    }

    @Test
    fun `不同 IP 的限流互相隔离`() {
        val limiter = RemoteAuth.RateLimiter(maxFailures = 2, windowMs = 60_000L, lockMs = 300_000L)
        val now = 1_700_000_000_000L
        repeat(2) { limiter.recordFailure("192.168.1.5", now) }
        assertTrue(limiter.isLocked("192.168.1.5", now))
        assertFalse("把别人的失败算到了这个 IP 上", limiter.isLocked("192.168.1.6", now))
    }

    // ══════════════════════════════════════════════════════════════════
    // Base64（自带实现，因为 java.util.Base64 需要 API 26）
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `Base64 编解码往返`() {
        listOf(
            ByteArray(0), "a".toByteArray(), "ab".toByteArray(), "abc".toByteArray(),
            "abcd".toByteArray(), ByteArray(300) { (it % 256).toByte() }
        ).forEach { data ->
            val encoded = B64.encodeToString(data)
            assertArrayEquals("长度 ${data.size} 往返失败", data, B64.decode(encoded))
        }
    }

    @Test
    fun `Base64 输出与标准实现一致`() {
        // 用已知向量确认不是"自娱自乐"的编码
        assertEquals("", B64.encodeToString(ByteArray(0)))
        assertEquals("Zg==", B64.encodeToString("f".toByteArray()))
        assertEquals("Zm8=", B64.encodeToString("fo".toByteArray()))
        assertEquals("Zm9v", B64.encodeToString("foo".toByteArray()))
        assertEquals("Zm9vYg==", B64.encodeToString("foob".toByteArray()))
        assertEquals("Zm9vYmE=", B64.encodeToString("fooba".toByteArray()))
        assertEquals("Zm9vYmFy", B64.encodeToString("foobar".toByteArray()))
    }

    @Test
    fun `Base64 非法输入返回 null`() {
        assertNull(B64.decode("abc"))
        assertNull(B64.decode("a==="))
        assertNull(B64.decode("ab!d"))
        assertNull(B64.decode("ab=z"))
    }
}
