package com.workbuddy.quicklaunch.remote

import org.json.JSONArray
import org.json.JSONObject
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * 公网中继（备用通道）：设备主动出站拉取指令。
 *
 * ## 为什么需要它
 *
 * 设备是服务端（监听 8765），但它和公司电脑通常都在各自 NAT 后面，跨网时
 * 谁也没有公网地址 → 直连在数学上不可能。组网（Tailscale）能解决，但手机上的
 * `VpnService` 同一时刻只允许一个 VPN 应用，用户手机上已有代理时直接冲突。
 *
 * 所以备用通道改「方向」：两边都主动**出站**连一个第三点（出站连接天然穿透
 * 所有 NAT 与公司防火墙），由它转交密文。
 *
 * ## 中继不需要被信任（这是整个设计的关键）
 *
 * 本条通道**不引入任何新的密码学**，完全复用局域网那套：
 *
 * - 报文是 `AES-256-GCM` 信封，密钥由配对时下发的 `secret` 派生；
 * - 签名是 `HMAC-SHA256(macKey, method\Npath\Nts\Nnonce\Nsha256(信封))`；
 * - nonce 一次性 + 时间戳 5 分钟窗口，重放会被拒。
 *
 * 于是中继看到的是：谁在拉、密文、时间戳、密文长度。它**解不开、也伪造不了**。
 * 换句话说，中继可以是一台被入侵的机器，攻击者最多只能丢弃或延迟指令
 * （可检测的可用性问题），无法读到规则内容，也无法伪造「把周三的启动取消掉」。
 *
 * 这也意味着：**不需要给中继任何凭据**。`X-Relay-Token` 只是降低队列噪声，
 * 不承担安全职责 —— 真正的门是签名。
 *
 * ## 与局域网通道的关系
 *
 * 指令用 `(method, path, 信封)` 三元组表达，与局域网请求完全同构，
 * 因此 PATCH 的 canonical 在两条通道上**逐字节相同**：
 * 控制端同一份签名代码既能签 LAN 请求，也能签中继指令。
 *
 * ## 明确不支持的东西
 *
 * 中继通道**拒绝** `PAIR` 与 `PING`（见 [RelayCommandExecutor.ALLOWED_METHODS]）。
 * 理由：配对码只有 8 位，局域网下靠「必须同一 WiFi」兜底；一旦放到公网可达，
 * 攻击者可以用大量 IP 绕过单 IP 限流去猜码。所以配对只在局域网内做，
 * 中继只承载已配对设备的写请求。
 */

/** 中继通道的配置。 */
data class RelaySettings(
    val enabled: Boolean,
    val url: String,
    val token: String,
    val intervalSec: Int
)

/**
 * HTTP 传输抽象。
 *
 * 存在的唯一理由：让「拉取 → 验签 → 执行 → 回传」整条链路能在纯 JVM 单测里
 * 用假传输跑通，不需要真连网络，也不需要 Robolectric。
 */
fun interface RelayTransport {
    fun post(url: String, json: String, token: String): RelayHttp
}

data class RelayHttp(val ok: Boolean, val body: String?)

/** 一条待执行的指令。字段与局域网请求头一一对应。 */
data class RelayCommand(
    val id: String,
    val method: String,
    val path: String,
    val ts: Long,
    val nonce: String,
    val sign: String,
    /** Base64 的 AES-GCM 信封；`LIST` 这类无参指令为空串。 */
    val body: String
) {
    fun bodyBytes(): ByteArray = body.toByteArray(Charsets.US_ASCII)

    companion object {

        fun from(o: JSONObject): RelayCommand? {
            val method = o.optString("method").trim()
            val path = o.optString("path").trim()
            val nonce = o.optString("nonce").trim()
            val sign = o.optString("sign").trim()
            if (method.isBlank() || path.isBlank() || nonce.isBlank() || sign.isBlank()) return null
            val ts = o.optLong("ts", 0L)
            if (ts <= 0L) return null
            return RelayCommand(
                id = o.optString("id").ifBlank { nonce },
                method = method,
                path = path,
                ts = ts,
                nonce = nonce,
                sign = sign,
                body = o.optString("body")
            )
        }
    }
}

/**
 * 执行中继指令。分派到 [RemoteRuleApi]，与局域网接口走同一段业务代码。
 *
 * 类本身不 import 任何 Android 类型，因此可以被纯 JVM 单测直接驱动。
 */
class RelayCommandExecutor(
    private val api: () -> RemoteRuleApi?,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val nonces: RemoteAuth.NonceCache = RemoteAuth.NonceCache()
) {

    sealed class Outcome {
        data class Ok(val payload: JSONObject) : Outcome()
        data class Err(val code: Int, val error: String, val detail: String) : Outcome()
    }

    /**
     * 校验顺序与 [RemoteServer] 保持一致，但**先过方法白名单**：
     * 白名单之外的指令（尤其是 `PAIR`）连验签都不给做，避免任何绕过机会。
     */
    fun execute(secret: ByteArray?, cmd: RelayCommand): Outcome {
        if (secret == null) return Outcome.Err(401, "E_AUTH", "客户端密钥不可用")
        if (cmd.method.uppercase(Locale.US) !in ALLOWED_METHODS) {
            return Outcome.Err(403, "E_METHOD_BLOCKED", "备用通道不允许执行 ${cmd.method}")
        }

        val method = cmd.method.uppercase(Locale.US)
        val ok = RemoteAuth.verify(
            secret = secret,
            method = method,
            path = cmd.path,
            tsHeader = cmd.ts.toString(),
            nonce = cmd.nonce,
            signHeader = cmd.sign,
            body = cmd.bodyBytes(),
            now = now()
        )
        if (!ok) return Outcome.Err(401, "E_AUTH", "签名校验失败")

        // 签名有效才消费 nonce：否则可以用无效签名提前占掉某个 nonce，
        // 让控制端随后发出的合法指令被误判为重放。
        if (!nonces.useOnce(cmd.nonce)) {
            return Outcome.Err(401, "E_REPLAY", "该指令已被执行过（nonce 重复）")
        }

        val api = api() ?: return Outcome.Err(500, "E_DB", "本机数据库不可用")
        val encKey = RemoteAuth.encKey(secret)

        val plain = if (cmd.body.isBlank()) {
            ByteArray(0)
        } else {
            val envelope = B64.decode(cmd.body)
                ?: return Outcome.Err(400, "E_BODY", "报文不是合法的 Base64 信封")
            RemoteAuth.decrypt(encKey, envelope)
                ?: return Outcome.Err(400, "E_BODY", "报文解密失败")
        }
        val json = if (plain.isEmpty()) {
            JSONObject()
        } else {
            runCatching { JSONObject(String(plain, Charsets.UTF_8)) }.getOrNull()
                ?: return Outcome.Err(400, "E_BAD_JSON", "报文不是合法 JSON")
        }

        return dispatch(api, method, cmd.path, json)
    }

    private fun dispatch(
        api: RemoteRuleApi,
        method: String,
        path: String,
        json: JSONObject
    ): Outcome {
        if (method == "LIST") {
            if (trimPath(path) != "/api/v1/rules") {
                return Outcome.Err(400, "E_PATH", "LIST 的路径必须是 /api/v1/rules")
            }
            return Outcome.Ok(JSONObject().put("ok", true).put("rules", api.list()))
        }

        val id = ruleIdOf(path)
            ?: return Outcome.Err(400, "E_ID", "路径里没有可解析的规则 id")

        return when (method) {
            "PATCH" -> wrap(api.patch(id, json))
            "SKIP" -> wrap(
                api.addException(
                    id,
                    json.optString("date").takeIf { it.isNotBlank() },
                    json.optString("reason")
                )
            )
            "RESTORE" -> wrap(
                api.removeException(id, json.optString("date").takeIf { it.isNotBlank() })
            )
            "CALENDAR" -> {
                val list = api.calendar(id, json.optInt("days", 14))
                    ?: return Outcome.Err(404, "E_RULE_NOT_FOUND", "规则 $id 不存在")
                val arr = JSONArray()
                list.forEach {
                    arr.put(JSONObject().apply {
                        put("date", it.date)
                        put("willRun", it.willRun)
                        put("reason", it.reason)
                        put("note", it.note)
                    })
                }
                Outcome.Ok(JSONObject().put("ok", true).put("days", arr))
            }
            "NEXT" -> {
                val at = json.optLong("at", 0L).takeIf { it > 0L } ?: now()
                val next = api.dryRun(id, at)
                    ?: return Outcome.Err(404, "E_NO_NEXT", "该规则没有下一次定时触发")
                Outcome.Ok(
                    JSONObject()
                        .put("ok", true)
                        .put("nextTriggerAt", next)
                        .put("nextTriggerText", RemoteRuleApi.humanize(next, at))
                )
            }
            else -> Outcome.Err(403, "E_METHOD_BLOCKED", "备用通道不允许执行 $method")
        }
    }

    private fun wrap(r: RemoteRuleApi.WriteResult): Outcome = when (r) {
        is RemoteRuleApi.WriteResult.Err -> Outcome.Err(r.code, r.error, r.detail)
        is RemoteRuleApi.WriteResult.Ok -> {
            val next = r.nextTriggerAt
            Outcome.Ok(JSONObject().apply {
                put("ok", true)
                put("ruleId", r.automation.id)
                put("nextTriggerAt", next ?: JSONObject.NULL)
                put(
                    "nextTriggerText",
                    next?.let { RemoteRuleApi.humanize(it, now()) } ?: "无（已停用）"
                )
                put("exceptions", JSONArray().apply { r.exceptions.sorted().forEach { put(it) } })
            })
        }
    }

    companion object {

        /**
         * 方法白名单。
         *
         * 注意这里**故意没有** `PAIR` / `PING`：配对码在公网暴露面下靠不住（8 位、
         * 只有单 IP 限流），所以配对只在局域网内完成，中继只承载已配对设备的写请求。
         */
        val ALLOWED_METHODS = setOf("PATCH", "SKIP", "RESTORE", "LIST", "CALENDAR", "NEXT")

        private fun trimPath(path: String): String {
            val p = path.trim()
            return if (p.length > 1 && p.endsWith('/')) p.dropLast(1) else p
        }

        /** 从 `/api/v1/rules/{id}` 取 id；取不到返回 null。 */
        fun ruleIdOf(path: String): Long? {
            val seg = trimPath(path).trim('/').split('/')
            if (seg.size != 4) return null
            if (seg[0] != "api" || seg[1] != "v1" || seg[2] != "rules") return null
            return seg[3].toLongOrNull()
        }
    }
}

/**
 * 轮询器：拉取 → 逐条执行 → 回传结果。
 *
 * 依赖全部通过构造注入（配置、客户端列表、传输、执行器、审计），
 * 这样 [pollOnce] 的完整流程可以用假传输在纯 JVM 里断言 ——
 * 与项目里 `RuleStore` / `ScheduleHook` 的注入风格一致。
 */
class RelayPoller(
    private val settings: () -> RelaySettings?,
    private val clients: () -> List<RemoteConfig.Client>,
    private val transport: RelayTransport,
    private val executor: () -> RelayCommandExecutor?,
    private val audit: (String, String) -> Unit = { _, _ -> },
    private val now: () -> Long = { System.currentTimeMillis() },
    private val maxClientsPerRound: Int = 8
) {

    data class Stats(val pulled: Int = 0, val executed: Int = 0, val failed: Int = 0) {
        val handled: Int get() = executed + failed
    }

    fun pollOnce(): Stats {
        val s = settings() ?: return Stats()
        if (!s.enabled) return Stats()
        val base = normalize(s.url) ?: return Stats()
        val ex = executor() ?: return Stats()

        val targets = clients().take(maxClientsPerRound)
        if (targets.isEmpty()) return Stats()

        var pulled = 0
        var executed = 0
        var failed = 0

        targets.forEach { client ->
            val secret = client.secret() ?: return@forEach

            val pull = transport.post(
                "$base/ql/pull",
                JSONObject().put("device", client.keyId).toString(),
                s.token
            )
            if (!pull.ok) {
                audit(client.name, "RELAY_PULL_FAIL")
                return@forEach
            }

            val (cmds, expired) = parsePull(pull.body)
            if (expired > 0) {
                // 中继按自己的时钟丢弃了超过签名窗口的旧指令（设备离线太久）。
                // 记下来，免得用户以为是「点了没反应」。
                audit(client.name, "RELAY_EXPIRED $expired")
            }
            if (cmds.isEmpty()) return@forEach
            pulled += cmds.size

            val results = JSONArray()
            cmds.forEach { cmd ->
                when (val out = ex.execute(secret, cmd)) {
                    is RelayCommandExecutor.Outcome.Ok -> {
                        executed++
                        audit(client.name, "RELAY_${cmd.method} ${describe(cmd)}")
                        results.put(resultItem(cmd.id, 200, "OK", seal(secret, out.payload)))
                    }
                    is RelayCommandExecutor.Outcome.Err -> {
                        failed++
                        audit(client.name, "RELAY_${cmd.method}_FAIL ${out.error} ${describe(cmd)}")
                        val payload = JSONObject()
                            .put("ok", false)
                            .put("error", out.error)
                            .put("detail", out.detail)
                        results.put(resultItem(cmd.id, out.code, out.error, seal(secret, payload)))
                    }
                }
            }

            transport.post(
                "$base/ql/result",
                JSONObject().put("device", client.keyId).put("results", results).toString(),
                s.token
            )
        }

        return Stats(pulled, executed, failed)
    }

    private fun resultItem(id: String, status: Int, error: String, body: String) =
        JSONObject().apply {
            put("id", id)
            put("status", status)
            put("error", error)
            put("body", body)
        }

    /** 用该客户端的 encKey 加密结果。加密失败返回空串，控制端会看到「无结果」。 */
    private fun seal(secret: ByteArray, payload: JSONObject): String = runCatching {
        val env = RemoteAuth.encrypt(
            RemoteAuth.encKey(secret),
            payload.toString().toByteArray(Charsets.UTF_8)
        )
        B64.encodeToString(env)
    }.getOrDefault("")

    private fun describe(cmd: RelayCommand): String =
        "path=${cmd.path} id=${cmd.id}"

    /**
     * 解析 pull 响应：`{"cmds":[...], "expired":N}`。
     *
     * `expired` 是中继按自己的时钟丢掉的过期指令数 —— 中继不验签，它只按 `ts`
     * 判断「这条已经超出 5 分钟签名窗口，设备拿到也一定会被拒」，于是直接丢弃。
     * 这样不会出现「设备收到一条注定 401 的指令」，用户看到的是明确的「已过期」。
     */
    private fun parsePull(body: String?): Pair<List<RelayCommand>, Int> {
        if (body.isNullOrBlank()) return emptyList<RelayCommand>() to 0
        val obj = runCatching { JSONObject(body) }.getOrNull()
            ?: return emptyList<RelayCommand>() to 0
        val arr = obj.optJSONArray("cmds") ?: JSONArray()
        val expired = obj.optInt("expired", 0)
        val cmds = (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { RelayCommand.from(it) }
        }
        return cmds to expired
    }

    companion object {

        /** 规范化中继地址：去空白与尾部斜杠，必须是 http(s) 绝对地址。 */
        fun normalize(raw: String?): String? {
            val u = raw?.trim().orEmpty()
            if (u.isEmpty()) return null
            val lower = u.lowercase(Locale.US)
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null
            return u.trimEnd('/')
        }
    }
}

/**
 * 生产传输：`HttpsURLConnection`。
 *
 * 这是本项目**唯一一次真正用到 TLS** —— 但方向与之前拒绝 TLS 的情形不同：
 * 这里我们是**客户端**，连的是 Cloudflare 签发的正常证书，走系统信任链即可，
 * 不需要 BouncyCastle、不需要自签证书，零依赖。
 */
object RelayHttpTransport : RelayTransport {

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 12_000

    override fun post(url: String, json: String, token: String): RelayHttp = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Cache-Control", "no-store")
            if (token.isNotBlank()) setRequestProperty("X-Relay-Token", token)
        }
        val bytes = json.toByteArray(Charsets.UTF_8)
        conn.setFixedLengthStreamingMode(bytes.size)
        DataOutputStream(conn.outputStream).use { it.write(bytes) }

        val code = conn.responseCode
        val text = runCatching {
            (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        }.getOrNull()
        conn.disconnect()
        RelayHttp(code in 200..299, text)
    }.getOrDefault(RelayHttp(false, null))
}
