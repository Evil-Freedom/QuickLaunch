package com.workbuddy.quicklaunch.remote.vps

import android.content.Context
import android.util.Log
import com.workbuddy.quicklaunch.remote.B64
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.Locale

/**
 * VPS 通道的本地配置（地址 / 设备身份 / 已应用版本）。
 *
 * 与 `RemoteConfig` 分开存：那是局域网 + 中继通道的配置，两者可能同时开启，
 * 混在一个 SP 里会让「关掉 VPS 同步」意外改到局域网开关。
 */
object VpsSettings {

    private const val SP = "quicklaunch_vps"
    private const val TAG = "QL-Vps"

    const val MIN_INTERVAL_SEC = 60
    const val MAX_INTERVAL_SEC = 24 * 3600
    const val DEFAULT_INTERVAL_SEC = 600

    data class Data(
        val enabled: Boolean,
        val baseUrl: String,
        val deviceId: String,
        val secretB64: String,
        val appliedVersion: Long,
        val intervalSec: Int
    ) {
        fun secret(): ByteArray? = B64.decode(secretB64)
        /** 配置完整到可以发起同步。 */
        fun ready(): Boolean = enabled && validateUrl(baseUrl) == null &&
            deviceId.isNotBlank() && secretB64.isNotBlank()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(SP, Context.MODE_PRIVATE)

    fun load(context: Context): Data {
        val p = prefs(context)
        return Data(
            enabled = p.getBoolean("enabled", false),
            baseUrl = p.getString("base_url", "").orEmpty().trimEnd('/'),
            deviceId = p.getString("device_id", "").orEmpty(),
            secretB64 = p.getString("secret", "").orEmpty(),
            appliedVersion = p.getLong("applied_version", 0L),
            intervalSec = p.getInt("interval", DEFAULT_INTERVAL_SEC)
                .coerceIn(MIN_INTERVAL_SEC, MAX_INTERVAL_SEC)
        )
    }

    fun setEnabled(context: Context, v: Boolean) =
        prefs(context).edit().putBoolean("enabled", v).apply()

    fun setBaseUrl(context: Context, url: String) =
        prefs(context).edit().putString("base_url", url.trim().trimEnd('/')).apply()

    fun setDeviceId(context: Context, id: String) =
        prefs(context).edit().putString("device_id", id.trim()).apply()

    fun setSecret(context: Context, secretB64: String) =
        prefs(context).edit().putString("secret", secretB64.trim()).apply()

    fun setAppliedVersion(context: Context, version: Long) =
        prefs(context).edit().putLong("applied_version", version).apply()

    fun setInterval(context: Context, sec: Int) =
        prefs(context).edit().putInt("interval", sec.coerceIn(MIN_INTERVAL_SEC, MAX_INTERVAL_SEC)).apply()

    fun setLastSync(context: Context, at: Long, note: String) =
        prefs(context).edit().putLong("last_sync_at", at).putString("last_sync_note", note).apply()

    fun lastSync(context: Context): Pair<Long, String> =
        prefs(context).getLong("last_sync_at", 0L) to
            prefs(context).getString("last_sync_note", "").orEmpty()

    /** 清空设备身份（相当于「撤销这台设备」的本地侧）。 */
    fun clearIdentity(context: Context) {
        prefs(context).edit().remove("device_id").remove("secret").remove("applied_version").apply()
    }

    /**
     * 地址校验。
     *
     * 正常情况只接受 `https://`：配置里包含「几点启动哪个 App」，明文传输等于
     * 把作息表公开在链路上。
     *
     * 唯一例外是**回环地址**（`127.0.0.1` / `localhost` / `[::1]`）允许 http ——
     * 那是走 `adb reverse` 在设备内部转发的调试路径，报文不出设备，
     * 与「把明文发到公网」是两回事。线上部署永远用不到这个例外。
     */
    fun validateUrl(raw: String): String? {
        val url = raw.trim().trimEnd('/')
        if (url.isEmpty()) return "地址不能为空"
        if (url.length > 200) return "地址过长"
        if (url.contains("@")) return "地址里不能包含用户名或密码"
        val https = url.startsWith("https://", ignoreCase = true)
        if (!https && !isLoopback(url)) {
            return "地址必须以 https:// 开头（本机调试可用 http://127.0.0.1）"
        }
        return null
    }

    /** 设备内部回环地址。 */
    fun isLoopback(url: String): Boolean {
        val lower = url.lowercase()
        return lower.startsWith("http://127.0.0.1") ||
            lower.startsWith("http://localhost") ||
            lower.startsWith("http://[::1]")
    }

    internal fun log(msg: String) {
        runCatching { Log.i(TAG, msg) }
    }
}

/** 一次拉取的结果。 */
sealed class FetchOutcome {
    data class Ok(val body: String) : FetchOutcome()
    /** 服务端明确表示没有更新（HTTP 304 或 updated=false）。 */
    data object NotModified : FetchOutcome()
    data class Fail(val code: Int, val reason: String) : FetchOutcome()
}

/**
 * VPS 配置拉取的传输层。
 *
 * 用 `HttpsURLConnection` 而不是引入 OkHttp：项目刻意维持 10 条 implementation 依赖
 * （见 `docs/AUDIT.md` §1），一次 GET 请求不值得为它加一个 1MB 的库。
 *
 * ## 请求签名
 *
 * 见 [VpsEnvelope.requestSignature]。服务端必须用同一份密钥、同一种拼接方式复算，
 * 否则 401。nonce 每次随机，服务端记录近 10 分钟用过的 nonce 拒绝重复。
 */
object VpsClient {

    private const val TAG = "QL-Vps"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000
    private const val CONFIG_PATH = "/api/v1/config"

    private val random = SecureRandom()

    fun newNonce(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { String.format(Locale.US, "%02x", it) }
    }

    /**
     * 拉取配置。**阻塞调用**，必须在后台线程执行。
     *
     * @param nonce 由调用方生成并保管 —— 响应里必须原样回显它，用于挡重放。
     *        放在外面生成是为了让「发出去的 nonce」与「校验时用的 nonce」是同一个变量，
     *        不会因为两次调用 [newNonce] 而对不上。
     *
     * 任何异常都转成 [FetchOutcome.Fail]，绝不让网络问题冒泡到调用方 ——
     * 调用方是广播接收器，抛异常等于「同步静默失败且没有任何记录」。
     */
    fun fetch(settings: VpsSettings.Data, since: Long, nonce: String): FetchOutcome {
        val secret = settings.secret()
            ?: return FetchOutcome.Fail(-1, "设备密钥不是合法 Base64")
        val ts = System.currentTimeMillis()
        val query = "?device=${encode(settings.deviceId)}&since=$since"
        val path = CONFIG_PATH + query
        val sign = VpsEnvelope.requestSignature(
            secret, path, ts, nonce, settings.deviceId, since
        )

        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(settings.baseUrl + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("X-QL-Device", settings.deviceId)
                setRequestProperty("X-QL-Ts", ts.toString())
                setRequestProperty("X-QL-Nonce", nonce)
                setRequestProperty("X-QL-Sign", sign)
                setRequestProperty("Accept", "application/json")
                instanceFollowRedirects = false
            }
            val code = conn.responseCode
            when (code) {
                HttpURLConnection.HTTP_OK -> {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    FetchOutcome.Ok(body)
                }
                HttpURLConnection.HTTP_NOT_MODIFIED -> FetchOutcome.NotModified
                else -> {
                    val err = runCatching {
                        conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    }.getOrDefault("")
                    VpsSettings.log("拉取失败 code=$code body=${err.take(200)}")
                    FetchOutcome.Fail(code, err.take(300).ifBlank { "HTTP $code" })
                }
            }
        } catch (t: Throwable) {
            VpsSettings.log("拉取异常：${t.javaClass.simpleName} ${t.message}")
            FetchOutcome.Fail(-1, "${t.javaClass.simpleName}: ${t.message}")
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /**
     * 回报应用结果。失败**不影响本地已生效的配置** ——
     * 回报只是让服务端日志完整，不能因为回报失败就把刚应用的配置回滚。
     */
    fun report(
        settings: VpsSettings.Data,
        version: Long,
        ok: Boolean,
        detail: String
    ): Boolean {
        val secret = settings.secret() ?: return false
        val ts = System.currentTimeMillis()
        val sign = VpsEnvelope.reportSignature(secret, settings.deviceId, version, ts, ok, detail)
        val body = """{"device":"${escape(settings.deviceId)}","version":$version,""" +
            """"ok":$ok,"detail":"${escape(detail.take(200))}"}"""
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(settings.baseUrl + "/api/v1/report").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("X-QL-Device", settings.deviceId)
                setRequestProperty("X-QL-Ts", ts.toString())
                setRequestProperty("X-QL-Sign", sign)
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            conn.responseCode in 200..299
        } catch (t: Throwable) {
            runCatching { Log.w(TAG, "回报失败：${t.message}") }
            false
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun encode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")

    private fun escape(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", " ")
}
