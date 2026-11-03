package com.workbuddy.quicklaunch.remote

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/**
 * 远程接口的路由与鉴权编排。
 *
 * 一次请求的固定顺序（顺序本身就是安全设计，不能重排）：
 *
 * ```
 * 1. 来源白名单   —— 非私网地址直接 403（服务监听 0.0.0.0，不限来源等于挂在公网）
 * 2. 限流闸门     —— 该 IP 已被锁定则 429（配对码只有 8 位，必须限制尝试速度）
 * 3. 路由识别     —— 只有 /api/v1 下的路径有效，其余一律 404
 * 4. 客户端标识   —— X-QL-Key 对应的密钥必须存在
 * 5. 签名校验     —— 覆盖 method/path/ts/nonce/body，任何一项被改都失败
 * 6. 防重放       —— nonce 在 5 分钟窗口内只能出现一次
 * 7. 解密报文     —— 到达这一步才碰业务数据
 * 8. 业务处理     —— RemoteRuleApi
 * ```
 *
 * 第 4~6 步失败**统一返回 401 且不区分原因**：区分「keyId 不存在」和「签名不对」
 * 会帮探测者确认哪些 keyId 是有效的。
 */
class RemoteServer(
    private val context: Context,
    private val requestedPort: Int,
    private val deviceLabel: String
) {

    private val nonces = RemoteAuth.NonceCache()
    private val limiter = RemoteAuth.RateLimiter()

    private val http = MiniHttpServer(requestedPort) { request -> handle(request) }

    val boundPort: Int get() = http.boundPort

    fun start(): Boolean {
        val ok = http.start()
        if (ok) {
            RemoteConfig.setBoundPort(context, http.boundPort)
        }
        return ok
    }

    fun stop() {
        http.stop()
        nonces.clear()
        limiter.reset()
        secretCache = null
    }

    // ── 路由 ────────────────────────────────────────────────────────

    private fun handle(req: MiniHttpServer.Request): MiniHttpServer.Response {
        val ip = req.remoteAddress
        val clientName = runCatching {
            RemoteConfig.findClient(context, req.header(RemoteAuth.HEADER_KEY).orEmpty())?.name
        }.getOrNull().orEmpty()

        if (!RemoteAuth.isAllowedSource(ip, RemoteConfig.allowTailscale(context))) {
            audit(clientName, ip, "DENY", "来源不在局域网白名单")
            return plain(403, "E_SOURCE", "只接受局域网内设备的访问")
        }
        if (limiter.isLocked(ip)) {
            return plain(429, "E_LOCKED", "尝试过于频繁，请 5 分钟后再试")
        }

        val seg = segments(req.path)
        if (seg.size < 3 || seg[0] != "api" || seg[1] != "v1") {
            return plain(404, "E_NOT_FOUND", "只有 /api/v1/* 可用")
        }

        return when {
            seg[2] == "ping" && req.method == "GET" -> handlePing()
            seg[2] == "pair" && req.method == "POST" -> handlePair(req, ip)
            seg[2] == "rules" -> handleRules(req, ip, seg)
            else -> plain(404, "E_NOT_FOUND", "未知路径 ${req.path}")
        }
    }

    private fun handlePing(): MiniHttpServer.Response {
        val body = JSONObject().apply {
            put("ok", true)
            put("v", RemoteProtocol.VERSION)
            put("device", deviceLabel)
            put("needPair", RemoteConfig.pendingCode(context) != null)
            put("paired", RemoteConfig.clients(context).isNotEmpty())
            put("time", System.currentTimeMillis())
        }
        return MiniHttpServer.Response.json(200, body.toString())
    }

    /**
     * 配对：用设备屏幕上显示的 8 位码换一组长期密钥。
     *
     * ⚠️ 这一步的响应在链路上是明文的（此时还没有共享密钥）。威胁模型上可接受，
     * 但边界必须说清楚：能拿到 secret 的人必须**当时**在同一个 WiFi 上嗅探，
     * 且配对码 10 分钟过期、错 3 次作废。如果当前 WiFi 不可信，就不要开这个功能。
     */
    private fun handlePair(req: MiniHttpServer.Request, ip: String): MiniHttpServer.Response {
        val json = runCatching {
            JSONObject(String(req.body, Charsets.UTF_8))
        }.getOrNull() ?: return plain(400, "E_BAD_JSON", "报文不是合法 JSON")

        val code = json.optString("code").trim()
        val name = json.optString("clientName").trim()
        if (code.isBlank()) return plain(400, "E_CODE", "缺少配对码")

        when (RemoteConfig.consumePairingCode(context, code)) {
            RemoteConfig.PairOutcome.Ok -> Unit
            RemoteConfig.PairOutcome.NoCode ->
                return pairFail(ip, "E_NO_CODE", "设备上没有待使用的配对码，请在手机上重新生成")
            RemoteConfig.PairOutcome.Expired ->
                return pairFail(ip, "E_CODE_EXPIRED", "配对码已过期，请重新生成")
            RemoteConfig.PairOutcome.TooManyAttempts ->
                return pairFail(ip, "E_CODE_LOCKED", "配对码错误次数过多已作废，请重新生成")
            RemoteConfig.PairOutcome.Mismatch ->
                return pairFail(ip, "E_CODE_MISMATCH", "配对码不正确")
        }

        val client = RemoteConfig.addClient(context, name)
        audit(client.name, ip, "PAIR", "新设备配对成功")

        val body = JSONObject().apply {
            put("ok", true)
            put("v", RemoteProtocol.VERSION)
            put("device", deviceLabel)
            put("keyId", client.keyId)
            put("secret", client.secretB64)
            put("note", "secret 只返回这一次，请妥善保存；丢失后需重新配对")
        }
        return MiniHttpServer.Response.json(200, body.toString())
    }

    private fun pairFail(ip: String, code: String, message: String): MiniHttpServer.Response {
        limiter.recordFailure(ip)
        audit("", ip, "PAIR_FAIL", code)
        return plain(401, code, message)
    }

    // ── 已鉴权的规则接口 ────────────────────────────────────────────

    private fun handleRules(
        req: MiniHttpServer.Request,
        ip: String,
        seg: List<String>
    ): MiniHttpServer.Response {
        val auth = authenticate(req, ip)
            ?: return plain(401, "E_AUTH", "签名校验失败")

        val api = runCatching { RemoteBindings.api(context) }.getOrNull()
            ?: return enc(auth, RemoteProtocol.errorJson(500, "E_DB", "本机数据库不可用"), 500)

        // /api/v1/rules
        if (seg.size == 3) {
            if (req.method != "GET") return methodNotAllowed(auth)
            return enc(auth, JSONObject().put("ok", true).put("rules", api.list()))
        }

        val id = seg[3].toLongOrNull()
            ?: return enc(auth, RemoteProtocol.errorJson(400, "E_ID", "规则 id 必须是数字"), 400)

        // /api/v1/rules/{id}
        if (seg.size == 4) {
            return when (req.method) {
                "GET" -> {
                    val view = api.viewOf(id)
                        ?: return enc(auth, RemoteProtocol.errorJson(404, "E_RULE_NOT_FOUND", "规则 $id 不存在"), 404)
                    enc(auth, JSONObject().put("ok", true).put("rule", view))
                }
                "PATCH" -> {
                    val body = decodeBody(auth, req)
                        ?: return enc(auth, RemoteProtocol.errorJson(400, "E_BODY", "报文解密失败"), 400)
                    finishWrite(auth, ip, "PATCH", id, api.patch(id, body))
                }
                else -> methodNotAllowed(auth)
            }
        }

        // /api/v1/rules/{id}/calendar?days=14
        if (seg.size == 5 && seg[4] == "calendar" && req.method == "GET") {
            val days = req.queryInt("days") ?: 14
            val list = api.calendar(id, days)
                ?: return enc(auth, RemoteProtocol.errorJson(404, "E_RULE_NOT_FOUND", "规则 $id 不存在"), 404)
            val arr = org.json.JSONArray()
            list.forEach {
                arr.put(JSONObject().apply {
                    put("date", it.date)
                    put("willRun", it.willRun)
                    put("reason", it.reason)
                    put("note", it.note)
                })
            }
            return enc(auth, JSONObject().put("ok", true).put("days", arr))
        }

        // /api/v1/rules/{id}/next?at=<ms>
        if (seg.size == 5 && seg[4] == "next" && req.method == "GET") {
            val at = req.queryLong("at") ?: System.currentTimeMillis()
            val next = api.dryRun(id, at)
                ?: return enc(auth, RemoteProtocol.errorJson(404, "E_NO_NEXT", "该规则没有下一次定时触发"), 404)
            val body = JSONObject().put("ok", true).put("nextTriggerAt", next)
                .put("nextTriggerText", RemoteRuleApi.humanize(next, at))
            return enc(auth, body)
        }

        // /api/v1/rules/{id}/exceptions
        if (seg.size == 5 && seg[4] == "exceptions") {
            when (req.method) {
                "POST" -> {
                    val body = decodeBody(auth, req)
                        ?: return enc(auth, RemoteProtocol.errorJson(400, "E_BODY", "报文解密失败"), 400)
                    val date = body.optString("date")
                    val reason = body.optString("reason")
                    return finishWrite(auth, ip, "SKIP", id, api.addException(id, date, reason))
                }
                "GET" -> {
                    val view = api.viewOf(id)
                        ?: return enc(auth, RemoteProtocol.errorJson(404, "E_RULE_NOT_FOUND", "规则 $id 不存在"), 404)
                    return enc(auth, JSONObject().put("ok", true).put("exceptions", view.optJSONArray("exceptions")))
                }
                else -> return methodNotAllowed(auth)
            }
        }

        // /api/v1/rules/{id}/exceptions/{date}
        if (seg.size == 6 && seg[4] == "exceptions" && req.method == "DELETE") {
            return finishWrite(auth, ip, "RESTORE", id, api.removeException(id, seg[5]))
        }

        return plain(404, "E_NOT_FOUND", "未知路径 ${req.path}")
    }

    /** 写操作统一收尾：审计 + 加密返回。 */
    private fun finishWrite(
        auth: Auth,
        ip: String,
        action: String,
        id: Long,
        result: RemoteRuleApi.WriteResult
    ): MiniHttpServer.Response = when (result) {
        is RemoteRuleApi.WriteResult.Err -> {
            audit(auth.client.name, ip, "${action}_FAIL", "rule=$id ${result.error}")
            enc(auth, RemoteProtocol.errorJson(result.code, result.error, result.detail), result.code)
        }
        is RemoteRuleApi.WriteResult.Ok -> {
            val detail = buildString {
                append("rule=").append(id)
                append(" next=").append(result.nextTriggerAt?.let { RemoteRuleApi.humanize(it, System.currentTimeMillis()) } ?: "-")
                if (action == "SKIP") append(" exceptions=").append(result.exceptions.size)
            }
            audit(auth.client.name, ip, action, detail)
            notifyRulesChanged()

            val view = runCatching {
                RemoteBindings.api(context).viewOf(id)
            }.getOrNull()

            val body = JSONObject().apply {
                put("ok", true)
                put("ruleId", id)
                put("nextTriggerAt", result.nextTriggerAt ?: JSONObject.NULL)
                put(
                    "nextTriggerText",
                    result.nextTriggerAt?.let { RemoteRuleApi.humanize(it, System.currentTimeMillis()) }
                        ?: "无（已停用）"
                )
                put("exceptions", org.json.JSONArray().apply { result.exceptions.sorted().forEach { put(it) } })
                if (view != null) put("rule", view)
            }
            enc(auth, body)
        }
    }

    // ── 鉴权 ────────────────────────────────────────────────────────

    private class Auth(val client: RemoteConfig.Client, val encKey: ByteArray)

    /** 只缓存「最近一次的 keyId → secret」，省掉每个请求解析一次 JSON 客户端列表。 */
    @Volatile
    private var secretCache: Pair<String, ByteArray>? = null

    private fun authenticate(req: MiniHttpServer.Request, ip: String): Auth? {
        val keyId = req.header(RemoteAuth.HEADER_KEY) ?: return null
        val secret = secretOf(keyId) ?: run {
            // keyId 不存在也计一次失败：否则可以无限枚举 keyId
            limiter.recordFailure(ip)
            return null
        }
        if (!verifyWith(req, secret, ip)) return null

        val client = clientOf(keyId) ?: return null
        // 每次请求都写 SP 会把闪存写坏，5 分钟一次足够表达「最近使用」
        if (System.currentTimeMillis() - client.lastUsedAt > 300_000L) {
            runCatching { RemoteConfig.touchClient(context, keyId) }
        }
        return Auth(client, RemoteAuth.encKey(secret))
    }

    private fun secretOf(keyId: String): ByteArray? {
        secretCache?.let { if (it.first == keyId) return it.second }
        val client = RemoteConfig.findClient(context, keyId) ?: return null
        val secret = client.secret() ?: return null
        secretCache = keyId to secret
        return secret
    }

    private fun clientOf(keyId: String): RemoteConfig.Client? = RemoteConfig.findClient(context, keyId)

    /**
     * 校验签名 + nonce 一次性。
     *
     * 注意这里签的是**传输的原始字节**（密文 base64 文本），不是明文 ——
     * 加密层与鉴权层因此互不依赖：即使换掉加密算法，签名口径也不用动。
     */
    private fun verifyWith(req: MiniHttpServer.Request, secret: ByteArray, ip: String): Boolean {
        val nonce = req.header(RemoteAuth.HEADER_NONCE)
        val ok = RemoteAuth.verify(
            secret = secret,
            method = req.method,
            path = req.path,
            tsHeader = req.header(RemoteAuth.HEADER_TS),
            nonce = nonce,
            signHeader = req.header(RemoteAuth.HEADER_SIGN),
            body = req.body
        )
        if (!ok) {
            limiter.recordFailure(ip)
            return false
        }
        // 签名有效才消费 nonce：否则攻击者可以用无效签名提前占掉某个 nonce
        if (nonce == null || !nonces.useOnce(nonce)) {
            limiter.recordFailure(ip)
            return false
        }
        limiter.recordSuccess(ip)
        return true
    }

    /** 请求体：base64 文本 → 二进制信封 → AES-GCM 解密 → JSON。任何一步失败返回 null。 */
    private fun decodeBody(auth: Auth, req: MiniHttpServer.Request): JSONObject? {
        if (req.body.isEmpty()) return JSONObject()
        val envelope = B64.decode(String(req.body, Charsets.US_ASCII)) ?: return null
        val plain = RemoteAuth.decrypt(auth.encKey, envelope) ?: return null
        return runCatching { JSONObject(String(plain, Charsets.UTF_8)) }.getOrNull()
    }

    // ── 响应 ────────────────────────────────────────────────────────

    /** 鉴权成功后的响应一律加密：报文内容（规则名、包名）不该在 WiFi 上明文可见。 */
    private fun enc(auth: Auth, json: JSONObject, status: Int = 200): MiniHttpServer.Response {
        val envelope = RemoteAuth.encrypt(auth.encKey, json.toString().toByteArray(Charsets.UTF_8))
        return MiniHttpServer.Response(
            status = status,
            body = B64.encodeToString(envelope).toByteArray(Charsets.US_ASCII),
            contentType = "application/x-ql-enc"
        )
    }

    /** 鉴权前的错误只能明文返回，内容里不含任何业务数据。 */
    private fun plain(status: Int, error: String, detail: String): MiniHttpServer.Response =
        MiniHttpServer.Response.json(status, RemoteProtocol.errorJson(status, error, detail).toString())

    private fun methodNotAllowed(auth: Auth) =
        enc(auth, RemoteProtocol.errorJson(405, "E_METHOD", "该路径不支持此方法"), 405)

    private fun audit(client: String, ip: String, action: String, detail: String) {
        runCatching { RemoteConfig.audit(context, client, ip, action, detail) }
    }

    /** 规则已变：让正开着的列表页立刻刷新，用户不用手动切 Tab 才看到新时间。 */
    private fun notifyRulesChanged() {
        runCatching {
            context.sendBroadcast(
                android.content.Intent(RemoteEvents.ACTION_RULES_CHANGED)
                    .setPackage(context.packageName)
            )
        }
    }

    private fun segments(path: String): List<String> =
        path.trim('/').split('/').filter { it.isNotEmpty() }

    companion object {
        /** 供日志/UI 用的设备标识：机型 + 端口，便于在多台设备间区分。 */
        fun deviceLabel(context: Context): String {
            val model = runCatching { android.os.Build.MODEL }.getOrDefault("Android")
            return String.format(Locale.US, "QuickLaunch@%s", model)
        }
    }
}
