package com.workbuddy.quicklaunch.remote.vps

import com.workbuddy.quicklaunch.remote.B64
import com.workbuddy.quicklaunch.remote.RemoteAuth
import org.json.JSONArray
import org.json.JSONObject

/**
 * VPS 主动拉取通道的信封格式与校验（纯逻辑，可 JVM 单测）。
 *
 * ## 为什么不用现成的局域网协议
 *
 * 局域网通道是「PC 直连手机的 HTTP 服务」，方向相反、鉴权模型不同（手机当服务端、
 * PC 是被验证方）。VPS 通道是「手机主动出站拉取」，手机是被验证方。
 * 两者共享的是**原语**（HMAC-SHA256、SHA-256、Base64），不是报文格式。
 * 所以这里只复用 `RemoteAuth` 的哈希与 `B64`，协议本身独立定义。
 *
 * ## 安全边界
 *
 * - **签名覆盖**：`alg/version/issuedAt/nonce/payload` 全部进 HMAC 输入，
 *   任何一个字段被改都会验签失败。payload 本身是 Base64 的 JSON，
 *   签名验的是 Base64 字符串原文，避免「解析后重序列化」造成的歧义。
 * - **nonce 回显**：服务端必须把它收到的请求 nonce 原样放进响应并签名。
 *   这样重放一份旧的合法响应会因为 nonce 不匹配而被拒。
 * - **版本单调**：[version] 必须严格大于本地已应用版本。这条挡住「回滚攻击」
 *   （把用户已经改掉的规则偷偷恢复成旧值）。
 * - **时间窗**：`issuedAt` 与本地时间相差超过 [MAX_SKEW_MS] 直接拒。
 *
 * ## 与 `RemoteProtocol` 的关系
 *
 * 规则字段的白名单与范围校验**完全复用** `RemoteProtocol.mergePatch`，
 * 这里只负责「把可信的 JSON 取出来」。不重复实现一份校验规则，
 * 否则两条通道对同一个字段的宽容度会慢慢漂移。
 */
object VpsEnvelope {

    /** 允许的时间偏差。VPS 与手机时钟偏差通常远小于这个值。 */
    const val MAX_SKEW_MS = 10 * 60_000L

    const val ALG = "HMAC-SHA256"

    sealed class Result {
        /** 拿到新配置。 */
        data class Ok(
            val version: Long,
            val issuedAt: Long,
            val rules: List<JSONObject>,
            val retry: JSONObject?
        ) : Result()

        /** 服务端版本没有推进，无事可做。不是错误。 */
        data class NoChange(val version: Long) : Result()

        /** 校验失败。[reason] 是内部原因码，直接写进同步日志。 */
        data class Err(val reason: String, val detail: String) : Result()
    }

    /**
     * 解析并校验一次响应。
     *
     * @param body        HTTP 响应体（整个 JSON）
     * @param secret      设备密钥
     * @param sentNonce   本次请求发出的 nonce，用于比对回显
     * @param appliedVersion 本地已应用的版本；0 表示从未同步
     * @param now         当前时间（测试注入）
     */
    fun parse(
        body: String,
        secret: ByteArray,
        sentNonce: String,
        appliedVersion: Long,
        now: Long = System.currentTimeMillis()
    ): Result {
        val root = runCatching { JSONObject(body) }.getOrElse {
            return Result.Err("E_JSON", "响应不是合法 JSON")
        }

        val alg = root.optString("alg")
        if (alg != ALG) {
            return Result.Err("E_ALG", "不支持的签名算法：$alg")
        }

        val version = root.optLong("version", -1)
        if (version <= 0) return Result.Err("E_VERSION", "缺少有效的 version")
        val issuedAt = root.optLong("issuedAt", 0)
        if (issuedAt <= 0) return Result.Err("E_ISSUED_AT", "缺少 issuedAt")

        // 版本没推进：先返回 NoChange，不必验签 —— 但**必须先确认这是服务端的应答**，
        // 否则任何人都能发一个 {"version":0} 让客户端"安静地不同步"。
        // 因此 updated=false 的分支同样要求签名完整。
        val updated = root.optBoolean("updated", true)
        val nonce = root.optString("nonce")
        if (nonce != sentNonce) {
            return Result.Err("E_NONCE", "响应 nonce 与请求不一致（可能是重放）")
        }

        val payloadB64 = root.optString("payload")
        val sign = root.optString("sign")
        val expected = signature(secret, alg, version, issuedAt, nonce, payloadB64)
        if (!constantTimeEquals(expected, sign)) {
            return Result.Err("E_SIGN", "响应签名校验失败")
        }

        if (kotlin.math.abs(now - issuedAt) > MAX_SKEW_MS) {
            return Result.Err("E_STALE", "配置签发时间超出允许窗口（${now - issuedAt}ms）")
        }

        if (!updated) return Result.NoChange(version)
        if (version <= appliedVersion) {
            return Result.Err(
                "E_ROLLBACK",
                "服务端版本 $version 不高于本地已应用版本 $appliedVersion，拒绝回退"
            )
        }

        val payload = runCatching {
            String(B64.decode(payloadB64) ?: return Result.Err("E_B64", "payload 不是合法 Base64"))
        }.getOrElse { return Result.Err("E_B64", "payload 解码失败") }

        val obj = runCatching { JSONObject(payload) }.getOrElse {
            return Result.Err("E_PAYLOAD", "payload 不是合法 JSON")
        }

        val rules = mutableListOf<JSONObject>()
        val arr: JSONArray? = obj.optJSONArray("rules")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { rules += it }
            }
        }
        return Result.Ok(version, issuedAt, rules, obj.optJSONObject("retry"))
    }

    /** 响应签名。`alg` 也进 HMAC 输入，防止降级算法替换。 */
    fun signature(
        secret: ByteArray,
        alg: String,
        version: Long,
        issuedAt: Long,
        nonce: String,
        payloadB64: String
    ): String = RemoteAuth.hmacSha256Hex(
        secret,
        "$alg\n$version\n$issuedAt\n$nonce\n$payloadB64"
    )

    /** 请求签名：GET + path + ts + nonce + device + since。 */
    fun requestSignature(
        secret: ByteArray,
        path: String,
        ts: Long,
        nonce: String,
        deviceId: String,
        since: Long
    ): String = RemoteAuth.hmacSha256Hex(secret, "GET\n$path\n$ts\n$nonce\n$deviceId\n$since")

    /** 结果回报签名。 */
    fun reportSignature(
        secret: ByteArray,
        deviceId: String,
        version: Long,
        ts: Long,
        ok: Boolean,
        detail: String
    ): String = RemoteAuth.hmacSha256Hex(
        secret,
        "REPORT\n$deviceId\n$version\n$ts\n${if (ok) 1 else 0}\n$detail"
    )

    /** 常数时间比较，避免按字符逐位比较泄露前缀。 */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        val x = a.toByteArray()
        val y = b.toByteArray()
        if (x.size != y.size) return false
        var diff = 0
        for (i in x.indices) diff = diff or (x[i].toInt() xor y[i].toInt())
        return diff == 0
    }
}
