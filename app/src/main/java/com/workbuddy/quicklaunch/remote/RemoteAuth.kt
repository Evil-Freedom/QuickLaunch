package com.workbuddy.quicklaunch.remote

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 远程接口的鉴权与报文加密。
 *
 * ## 为什么不用 TLS
 *
 * Android 上动态生成自签 X.509 需要引入 BouncyCastle（proguard 后约 +1.5 MB）；
 * 改成内置固定证书则私钥可从 APK 中提取，攻击者能伪造服务端，**比不加密更危险**。
 *
 * 这里用「预共享密钥 + 双派生密钥」达到与 TLS-PSK 等价的性质：
 * - `encKey` 负责机密性（AES-256-GCM，自带完整性 tag）
 * - `macKey` 负责身份与请求完整性（HMAC-SHA256，覆盖 method/path/ts/nonce/body 摘要）
 *
 * 攻击者没有 `secret` 时：解不开报文，也伪造不出能通过验签的请求或响应。
 * 代价是 URL 路径与时间戳在链路上可见 —— 本功能只改启动时间，可以接受。
 *
 * 若将来确实需要真 TLS，切换点只在服务器构造处，本类与业务层不受影响。
 */
object RemoteAuth {

    const val HEADER_KEY = "X-QL-Key"
    const val HEADER_TS = "X-QL-Ts"
    const val HEADER_NONCE = "X-QL-Nonce"
    const val HEADER_SIGN = "X-QL-Sign"

    /** 请求时间戳允许的偏差窗口。放宽到 5 分钟是为了容忍手机与客户端之间的时钟漂移。 */
    const val MAX_SKEW_MS = 300_000L

    private const val SECRET_BYTES = 32
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128

    private val secureRandom = SecureRandom()

    // ── 密钥派生 ────────────────────────────────────────────────────

    /** 用不同 label 派生两把子密钥：macKey 泄露不会连带暴露 encKey。 */
    fun macKey(secret: ByteArray): ByteArray = hmacSha256(secret, "ql-mac-v1".toByteArray())
    fun encKey(secret: ByteArray): ByteArray = hmacSha256(secret, "ql-enc-v1".toByteArray())

    fun newSecret(): ByteArray = ByteArray(SECRET_BYTES).also { secureRandom.nextBytes(it) }

    // ── 签名 ────────────────────────────────────────────────────────

    /**
     * 待签名串。字段之间用 `\n` 分隔，避免「拼接口径不一致」导致的越权：
     * 攻击者若能构造出「路径与 body 换位后签名仍然匹配」的报文，就能把改时间变成改别的。
     */
    fun canonical(method: String, path: String, ts: Long, nonce: String, body: ByteArray): String =
        buildString {
            append(method.uppercase(Locale.US)).append('\n')
            append(path).append('\n')
            append(ts).append('\n')
            append(nonce).append('\n')
            append(sha256Hex(body))
        }

    fun sign(secret: ByteArray, method: String, path: String, ts: Long, nonce: String, body: ByteArray): String {
        val canonical = canonical(method, path, ts, nonce, body).toByteArray(Charsets.UTF_8)
        return B64.encodeToString(hmacSha256(macKey(secret), canonical))
    }

    /**
     * 验签。所有分支都返回 false，不区分「密钥不存在 / 时间过期 / 签名不符」，
     * 避免给探测者提供「这个 keyId 是否存在」的旁路信息。
     *
     * 比较必须用 [MessageDigest.isEqual]：`String ==` 会在首个不同字节处提前返回，
     * 理论上可被计时攻击逐字节猜出签名。
     */
    fun verify(
        secret: ByteArray?,
        method: String,
        path: String,
        tsHeader: String?,
        nonce: String?,
        signHeader: String?,
        body: ByteArray,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        if (secret == null || tsHeader == null || nonce == null || signHeader == null) return false
        if (nonce.length !in 8..64) return false
        val ts = tsHeader.toLongOrNull() ?: return false
        if (kotlin.math.abs(now - ts) > MAX_SKEW_MS) return false

        val expected = B64.decode(sign(secret, method, path, ts, nonce, body)) ?: return false
        val actual = B64.decode(signHeader) ?: return false
        if (expected.size != actual.size) return false
        return MessageDigest.isEqual(expected, actual)
    }

    // ── 报文加密 ────────────────────────────────────────────────────

    /** 返回 `iv(12) || ciphertext+tag`。每次调用都重新随机 IV，绝不复用。 */
    fun encrypt(encKey: ByteArray, plain: ByteArray): ByteArray {
        val iv = ByteArray(GCM_IV_BYTES).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(encKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        val ct = cipher.doFinal(plain)
        return iv + ct
    }

    /** 解密失败（密钥不对、被篡改、IV 损坏）一律返回 null，由调用方转 401。 */
    fun decrypt(encKey: ByteArray, envelope: ByteArray): ByteArray? = runCatching {
        if (envelope.size <= GCM_IV_BYTES) return null
        val iv = envelope.copyOfRange(0, GCM_IV_BYTES)
        val ct = envelope.copyOfRange(GCM_IV_BYTES, envelope.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(encKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.doFinal(ct)
    }.getOrNull()

    // ── 摘要 ────────────────────────────────────────────────────────

    fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { String.format(Locale.US, "%02x", it) }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    // ── 防重放 ──────────────────────────────────────────────────────

    /**
     * 已用 nonce 的滑动窗口。
     *
     * 只在内存里，进程重启即清空 —— 可以接受：
     * 时间戳窗口本身只有 5 分钟，重启后重放一个 4 分钟前抓到的报文仍会因 nonce 不在缓存里而通过。
     * 要彻底堵住需要持久化，代价是每次请求一次磁盘写；对「改启动时间」这个威胁模型不值得。
     */
    class NonceCache(
        private val ttlMs: Long = MAX_SKEW_MS,
        private val capacity: Int = 512
    ) {
        private val seen = LinkedHashMap<String, Long>()

        @Synchronized
        fun useOnce(nonce: String, now: Long = System.currentTimeMillis()): Boolean {
            purge(now)
            if (seen.containsKey(nonce)) return false
            if (seen.size >= capacity) {
                // 淘汰最早插入的一条，防止被大量合法请求撑爆内存
                seen.keys.firstOrNull()?.let { seen.remove(it) }
            }
            seen[nonce] = now
            return true
        }

        @Synchronized
        fun size(): Int = seen.size

        @Synchronized
        fun clear() = seen.clear()

        private fun purge(now: Long) {
            val it = seen.entries.iterator()
            while (it.hasNext()) {
                if (now - it.next().value > ttlMs) it.remove() else break
            }
        }
    }

    // ── 来源限制与限流 ──────────────────────────────────────────────

    /**
     * 是否只允许局域网 / 回环来源。
     *
     * 白名单而非黑名单：服务监听在 `0.0.0.0`，不限制来源等于把配置面板挂在公网上
     * （同一个 WiFi 的访客、以及被端口转发的任何地址都能连）。
     *
     * [allowTailscale] 打开时额外放行 `100.64.0.0/10` 与 `fd7a:115c:a1e0::/48`，
     * 供用户通过 Tailscale 组网在外部访问。
     */
    fun isAllowedSource(host: String?, allowTailscale: Boolean = false): Boolean {
        if (host.isNullOrBlank()) return false
        val h = host.trim().lowercase(Locale.US)
            .removePrefix("[")
            .removeSuffix("]")
            .substringBefore('%')          // 去掉 IPv6 的 zone id（fe80::1%wlan0）

        if (h == "::1" || h == "0:0:0:0:0:0:0:1" || h == "localhost") return true

        if (h.contains(':')) {
            if (h.startsWith("fe80:")) return true                       // 链路本地
            if (h.startsWith("fc") || h.startsWith("fd")) return true    // 唯一本地地址
            if (allowTailscale && h.startsWith("fd7a:115c:a1e0:")) return true
            return false
        }

        val parts = h.split('.')
        if (parts.size != 4) return false
        val a = parts[0].toIntOrNull() ?: return false
        val b = parts[1].toIntOrNull() ?: return false
        if (parts.any { (it.toIntOrNull() ?: -1) !in 0..255 }) return false

        return when {
            a == 10 -> true                       // 10/8
            a == 127 -> true                      // 回环
            a == 192 && b == 168 -> true          // 192.168/16
            a == 172 && b in 16..31 -> true       // 172.16/12
            a == 169 && b == 254 -> true          // 链路本地
            allowTailscale && a == 100 && b in 64..127 -> true   // Tailscale CGNAT 100.64/10
            else -> false
        }
    }

    /**
     * 按来源 IP 的失败限流：60 秒内失败 [maxFailures] 次后锁定 [lockMs]。
     *
     * 目的是把「在线暴力猜配对码」的成本抬到不可行：
     * 8 位数字有 1e8 种组合，限流后每秒最多几次尝试。
     * 条目上限 256，防止被随机来源打满内存。
     */
    class RateLimiter(
        private val maxFailures: Int = 5,
        private val windowMs: Long = 60_000L,
        private val lockMs: Long = 300_000L,
        private val maxEntries: Int = 256
    ) {
        private data class State(var failures: Int, var windowStart: Long, var lockedUntil: Long)

        private val states = ConcurrentHashMap<String, State>()

        fun isLocked(ip: String, now: Long = System.currentTimeMillis()): Boolean {
            val s = states[ip] ?: return false
            if (s.lockedUntil > now) return true
            if (s.lockedUntil != 0L && s.lockedUntil <= now) {
                states.remove(ip)      // 锁定期已过，重新计数
            }
            return false
        }

        fun recordFailure(ip: String, now: Long = System.currentTimeMillis()) {
            if (states.size >= maxEntries) {
                val oldest = states.entries.minByOrNull { it.value.windowStart }?.key
                if (oldest != null) states.remove(oldest)
            }
            val s = states.getOrPut(ip) { State(0, now, 0L) }
            if (now - s.windowStart > windowMs) {
                s.failures = 0
                s.windowStart = now
            }
            s.failures++
            if (s.failures >= maxFailures) {
                s.lockedUntil = now + lockMs
            }
        }

        fun recordSuccess(ip: String) {
            states.remove(ip)
        }

        fun reset() = states.clear()
    }
}
