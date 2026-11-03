package com.workbuddy.quicklaunch.remote

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Locale

/**
 * 远程访问的配置持久化。
 *
 * 全部落在 `SharedPreferences`，**不新增 Room 表**：
 * 配置是「少量键值 + 一个短列表」，加表就得再写一次 `Migration`，
 * 而迁移本身正是这个项目历史上最大的数据风险来源。规则本体仍然只在
 * `automations` 表里，远程写入走同一条 DAO 路径，不存在第二份真源。
 *
 * ## 密钥存储的诚实边界
 *
 * 配对密钥以 Base64 存明文于应用私有 SP。能读到它的前提是**已经拿到 root 或
 * 能读应用数据目录**；那种前提下 Keystore 也拦不住（可代应用调用）。
 * 本功能的安全目标是「挡住同一 WiFi 下的其他普通设备」，这一点已由
 * 配对 + HMAC 验签 + 私网来源限制共同保证。若将来要求防 root，再换成
 * AndroidKeyStore 包裹（改动只在本类，不影响协议与业务层）。
 */
object RemoteConfig {

    private const val SP = "quicklaunch_remote"
    private const val SP_AUDIT = "quicklaunch_remote_audit"

    const val DEFAULT_PORT = 8765
    const val MIN_PORT = 1024
    const val MAX_PORT = 65535

    /** 配对码有效期。太短用户来不及输，太长等于把窗口留给猜测。 */
    const val PAIRING_TTL_MS = 10 * 60_000L

    /** 配对码连续错这么多次即作废，必须在设备上重新生成。 */
    const val PAIRING_MAX_ATTEMPTS = 3

    /** 已配对客户端上限。超出时淘汰最久未使用的一个。 */
    const val MAX_CLIENTS = 32

    private const val MAX_AUDIT = 200

    private val random = SecureRandom()

    fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(SP, Context.MODE_PRIVATE)

    // ── 开关与端口 ──────────────────────────────────────────────────

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean("enabled", false)

    fun setEnabled(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean("enabled", value).apply()

    fun getPort(context: Context): Int {
        val p = prefs(context).getInt("port", DEFAULT_PORT)
        return if (p in MIN_PORT..MAX_PORT) p else DEFAULT_PORT
    }

    fun setPort(context: Context, port: Int) =
        prefs(context).edit().putInt("port", port.coerceIn(MIN_PORT, MAX_PORT)).apply()

    /** 是否放行 Tailscale 网段（100.64/10、fd7a:115c:a1e0::/48）。默认关。 */
    fun allowTailscale(context: Context): Boolean =
        prefs(context).getBoolean("allow_tailscale", false)

    fun setAllowTailscale(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean("allow_tailscale", value).apply()

    // ── 公网中继（备用通道）──────────────────────────────────────────
    //
    // 中继本身不需要被信任：指令走 AES-GCM 信封 + HMAC 签名，中继只能转发。
    // 这里的 token 不承担安全职责，仅用于压低队列噪声。

    /** 轮询间隔下限：更密只会徒增流量与耗电，对「取消明天启动」这个场景毫无收益。 */
    const val RELAY_MIN_INTERVAL_SEC = 5
    const val RELAY_MAX_INTERVAL_SEC = 600
    const val RELAY_DEFAULT_INTERVAL_SEC = 15

    fun relaySettings(context: Context): RelaySettings = RelaySettings(
        enabled = prefs(context).getBoolean("relay_enabled", false),
        url = prefs(context).getString("relay_url", "").orEmpty(),
        token = prefs(context).getString("relay_token", "").orEmpty(),
        intervalSec = prefs(context).getInt("relay_interval", RELAY_DEFAULT_INTERVAL_SEC)
            .coerceIn(RELAY_MIN_INTERVAL_SEC, RELAY_MAX_INTERVAL_SEC)
    )

    fun setRelayEnabled(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean("relay_enabled", value).apply()

    fun setRelayUrl(context: Context, url: String) =
        prefs(context).edit().putString("relay_url", url.trim()).apply()

    fun setRelayToken(context: Context, token: String) =
        prefs(context).edit().putString("relay_token", token.trim()).apply()

    fun setRelayInterval(context: Context, sec: Int) =
        prefs(context).edit()
            .putInt("relay_interval", sec.coerceIn(RELAY_MIN_INTERVAL_SEC, RELAY_MAX_INTERVAL_SEC))
            .apply()

    /** 上次轮询时间与结果，供界面显示「备用通道到底通不通」。 */
    fun setRelayLastPoll(context: Context, at: Long, note: String) =
        prefs(context).edit()
            .putLong("relay_last_poll", at)
            .putString("relay_last_note", note)
            .apply()

    fun relayLastPoll(context: Context): Pair<Long, String> =
        prefs(context).getLong("relay_last_poll", 0L) to
            prefs(context).getString("relay_last_note", "").orEmpty()

    /** 上次成功绑定到的端口（端口冲突时可能与配置值不同），供 UI 展示。 */
    fun getBoundPort(context: Context): Int = prefs(context).getInt("bound_port", 0)

    fun setBoundPort(context: Context, port: Int) =
        prefs(context).edit().putInt("bound_port", port).apply()

    // ── 配对码 ──────────────────────────────────────────────────────

    data class PendingCode(val code: String, val expiresAt: Long, val attempts: Int)

    fun pendingCode(context: Context): PendingCode? {
        val p = prefs(context)
        val code = p.getString("pair_code", null) ?: return null
        val exp = p.getLong("pair_expire", 0L)
        if (System.currentTimeMillis() > exp) return null
        return PendingCode(code, exp, p.getInt("pair_attempts", 0))
    }

    /**
     * 生成新的 8 位配对码（首位非 0，便于阅读与输入）。
     * 每次生成都会重置失败计数并刷新有效期。
     */
    fun issuePairingCode(context: Context): String {
        val code = buildString {
            append(1 + random.nextInt(9))
            repeat(7) { append(random.nextInt(10)) }
        }
        prefs(context).edit()
            .putString("pair_code", code)
            .putLong("pair_expire", System.currentTimeMillis() + PAIRING_TTL_MS)
            .putInt("pair_attempts", 0)
            .apply()
        return code
    }

    fun clearPairingCode(context: Context) =
        prefs(context).edit()
            .remove("pair_code").remove("pair_expire").remove("pair_attempts").apply()

    sealed class PairOutcome {
        data object Ok : PairOutcome()
        data object NoCode : PairOutcome()
        data object Expired : PairOutcome()
        data object TooManyAttempts : PairOutcome()
        data object Mismatch : PairOutcome()
    }

    /**
     * 校验配对码。成功即作废（一次性），失败则累加计数，达到上限直接作废。
     * 所有分支都会把状态写回磁盘，避免「重启 App 就重置失败计数」绕过限流。
     */
    fun consumePairingCode(context: Context, input: String): PairOutcome {
        val p = prefs(context)
        val code = p.getString("pair_code", null) ?: return PairOutcome.NoCode
        val exp = p.getLong("pair_expire", 0L)
        if (System.currentTimeMillis() > exp) {
            clearPairingCode(context)
            return PairOutcome.Expired
        }
        val attempts = p.getInt("pair_attempts", 0)
        if (attempts >= PAIRING_MAX_ATTEMPTS) {
            clearPairingCode(context)
            return PairOutcome.TooManyAttempts
        }
        if (input.trim() != code) {
            val next = attempts + 1
            if (next >= PAIRING_MAX_ATTEMPTS) {
                clearPairingCode(context)
                return PairOutcome.TooManyAttempts
            }
            p.edit().putInt("pair_attempts", next).apply()
            return PairOutcome.Mismatch
        }
        clearPairingCode(context)
        return PairOutcome.Ok
    }

    // ── 已配对客户端 ────────────────────────────────────────────────

    data class Client(
        val keyId: String,
        val secretB64: String,
        val name: String,
        val createdAt: Long,
        val lastUsedAt: Long
    ) {
        fun secret(): ByteArray? = B64.decode(secretB64)
    }

    fun clients(context: Context): List<Client> {
        val raw = prefs(context).getString("clients", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val keyId = o.optString("keyId")
                val secret = o.optString("secret")
                if (keyId.isBlank() || secret.isBlank()) return@mapNotNull null
                Client(
                    keyId = keyId,
                    secretB64 = secret,
                    name = o.optString("name", "未命名设备"),
                    createdAt = o.optLong("createdAt", 0L),
                    lastUsedAt = o.optLong("lastUsedAt", 0L)
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun saveClients(context: Context, list: List<Client>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("keyId", it.keyId)
                put("secret", it.secretB64)
                put("name", it.name)
                put("createdAt", it.createdAt)
                put("lastUsedAt", it.lastUsedAt)
            })
        }
        prefs(context).edit().putString("clients", arr.toString()).apply()
    }

    /**
     * 新增客户端并返回它。超出上限时淘汰最久未使用的 ——
     * 不淘汰的话，反复配对会把 SP 撑成无限增长的列表，读配置越来越慢。
     */
    fun addClient(context: Context, name: String): Client {
        val now = System.currentTimeMillis()
        val secret = RemoteAuth.newSecret()
        val client = Client(
            keyId = "k_" + RemoteAuth.sha256Hex(RemoteAuth.newSecret()).take(12),
            secretB64 = B64.encodeToString(secret),
            name = name.trim().ifBlank { "未命名设备" }.take(40),
            createdAt = now,
            lastUsedAt = now
        )
        val existing = clients(context).toMutableList()
        existing.add(client)
        val trimmed = if (existing.size <= MAX_CLIENTS) {
            existing
        } else {
            existing.sortedByDescending { it.lastUsedAt }.take(MAX_CLIENTS)
        }
        saveClients(context, trimmed)
        return client
    }

    fun removeClient(context: Context, keyId: String) {
        saveClients(context, clients(context).filterNot { it.keyId == keyId })
    }

    fun findClient(context: Context, keyId: String): Client? =
        clients(context).firstOrNull { it.keyId == keyId }

    fun touchClient(context: Context, keyId: String) {
        val list = clients(context).map {
            if (it.keyId == keyId) it.copy(lastUsedAt = System.currentTimeMillis()) else it
        }
        saveClients(context, list)
    }

    fun clearAllClients(context: Context) = saveClients(context, emptyList())

    // ── 审计 ────────────────────────────────────────────────────────

    /**
     * 写一条审计。环形保留最近 [MAX_AUDIT] 条。
     *
     * 记录的是「谁、什么时候、改了哪条规则的哪些字段」，
     * 用途是「回家发现规则被改了但想不起是不是自己改的」这种场景。
     */
    fun audit(context: Context, clientName: String, ip: String, action: String, detail: String) {
        runCatching {
            val p = context.applicationContext.getSharedPreferences(SP_AUDIT, Context.MODE_PRIVATE)
            val arr = runCatching { JSONArray(p.getString("log", "[]")) }.getOrDefault(JSONArray())
            arr.put(JSONObject().apply {
                put("ts", System.currentTimeMillis())
                put("client", clientName)
                put("ip", ip)
                put("action", action)
                put("detail", detail)
            })
            // 只保留最后 MAX_AUDIT 条
            val kept = JSONArray()
            val from = (arr.length() - MAX_AUDIT).coerceAtLeast(0)
            for (i in from until arr.length()) kept.put(arr.get(i))
            p.edit().putString("log", kept.toString()).apply()
        }
    }

    data class AuditEntry(val ts: Long, val client: String, val ip: String, val action: String, val detail: String) {
        fun text(): String = String.format(
            Locale.US, "%s  %s  %s %s",
            android.text.format.DateFormat.format("MM-dd HH:mm", ts),
            client.ifBlank { "-" },
            action,
            detail
        )
    }

    /** 最新在前，便于 UI 直接展示。 */
    fun auditLog(context: Context): List<AuditEntry> {
        val p = context.applicationContext.getSharedPreferences(SP_AUDIT, Context.MODE_PRIVATE)
        val raw = p.getString("log", "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                AuditEntry(
                    ts = o.optLong("ts"),
                    client = o.optString("client"),
                    ip = o.optString("ip"),
                    action = o.optString("action"),
                    detail = o.optString("detail")
                )
            }.sortedByDescending { it.ts }
        }.getOrDefault(emptyList())
    }

    fun clearAudit(context: Context) {
        context.applicationContext.getSharedPreferences(SP_AUDIT, Context.MODE_PRIVATE)
            .edit().remove("log").apply()
    }
}
