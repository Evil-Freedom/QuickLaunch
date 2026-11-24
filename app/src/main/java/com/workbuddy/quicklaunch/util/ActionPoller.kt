package com.workbuddy.quicklaunch.util

import android.content.Context
import android.util.Log
import com.workbuddy.quicklaunch.BuildConfig
import com.workbuddy.quicklaunch.remote.vps.VpsSettings
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.Executors

/**
 * 拉取并执行 VPS 下发的清理动作。
 *
 * 一次性语义：
 * 1. 只认 `FORCE_STOP_FEISHU` 这一种动作类型（包名由 [FeishuProcessManager] 写死）；
 * 2. 本地开关关闭时**拒绝执行**并回报 REJECTED —— VPS 不能绕过本地设置；
 * 3. 先 begin（带 nonce，防重放/防过期）再执行，执行完回报结果；
 * 4. 整个过程在独立单线程里跑，网络失败只影响自己，不碰启动链路。
 */
object ActionPoller {

    private const val TAG = "QL-ActionPoll"
    private const val ACTION_FORCE_STOP = "FORCE_STOP_FEISHU"

    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ql-action-poll").apply { isDaemon = true }
    }

    fun pollOnce(ctx: Context) {
        val app = ctx.applicationContext
        exec.execute { runCatching { poll(app) }.onFailure { Log.w(TAG, "轮询异常 ${it.javaClass.simpleName}") } }
    }

    private fun candidates(ctx: Context): List<String> {
        val out = LinkedHashSet<String>()
        val s = runCatching { VpsSettings.load(ctx) }.getOrNull()
        listOfNotNull(s?.baseUrl, s?.backupUrl).forEach { raw ->
            runCatching {
                val uri = URI(raw)
                val host = uri.host ?: return@runCatching
                val port = if (uri.port > 0) ":${uri.port}" else ""
                out += "${uri.scheme}://$host$port/notify"
            }
        }
        return out.toList()
    }

    private fun poll(ctx: Context) {
        val token = BuildConfig.QL_NOTIFY_TOKEN.trim()
        if (token.isEmpty()) return
        val deviceId = runCatching { VpsSettings.load(ctx).deviceId }.getOrNull().orEmpty()
        if (deviceId.isBlank()) return

        for (base in candidates(ctx)) {
            val body = runCatching { get("$base/v1/device/actions/pending", token, deviceId) }.getOrNull() ?: continue
            val actions = body.optJSONArray("actions") ?: return
            for (i in 0 until actions.length()) {
                handle(ctx, base, token, deviceId, actions.getJSONObject(i))
            }
            return
        }
    }

    private fun handle(ctx: Context, base: String, token: String, deviceId: String, action: JSONObject) {
        val actionId = action.optString("action_id")
        val type = action.optString("action_type")
        val nonce = action.optString("nonce")
        val taskId = action.optString("task_id")
        if (actionId.isBlank()) return

        if (type != ACTION_FORCE_STOP) {
            report(base, token, deviceId, actionId, "REJECTED", "unsupported_action_type=$type")
            return
        }
        if (!CleanupSettings.afterCheckin(ctx)) {
            Log.i(TAG, "本地开关关闭，拒绝执行 $actionId")
            report(base, token, deviceId, actionId, "REJECTED", "本地已关闭「考勤成功后清理飞书」")
            return
        }

        // 有未完成清理时，启动前清理必须让路（用户可能还要用飞书）
        CleanupSettings.setAwaitingCleanup(ctx, true)

        val begin = runCatching { post("$base/v1/device/actions/$actionId/begin", token, deviceId, JSONObject().put("nonce", nonce)) }
            .getOrNull()
        if (begin == null || !begin.optBoolean("ok")) {
            Log.w(TAG, "begin 被拒 action=$actionId reason=${begin?.optString("reason")}")
            return
        }

        val r = FeishuProcessManager.forceStop()
        val detail = "task=$taskId exit=${r.exitCode} 剩余进程=${r.remaining.size} 耗时=${r.elapsedMs}ms；${r.detail}"
        report(base, token, deviceId, actionId, if (r.ok) "CLEANUP_SUCCESS" else "CLEANUP_FAILED", detail)
        if (r.ok) CleanupSettings.setAwaitingCleanup(ctx, false)
    }

    private fun report(base: String, token: String, deviceId: String, actionId: String,
                       status: String, detail: String) {
        runCatching {
            post("$base/v1/device/actions/$actionId/result", token, deviceId,
                JSONObject().put("status", status).put("detail", detail)
                    .put("device_name", android.os.Build.MODEL))
        }.onFailure { Log.w(TAG, "回报失败 $actionId ${it.javaClass.simpleName}") }
    }

    // ── HTTP ────────────────────────────────────────────────────────

    private fun get(url: String, token: String, deviceId: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 4000
        conn.readTimeout = 4000
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("X-QL-Device", deviceId)
        return try {
            JSONObject(conn.inputStream.bufferedReader().readText())
        } finally {
            conn.disconnect()
        }
    }

    private fun post(url: String, token: String, deviceId: String, body: JSONObject): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 4000
        conn.readTimeout = 6000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("X-QL-Device", deviceId)
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        return try {
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            JSONObject(stream?.bufferedReader()?.readText().orEmpty().ifBlank { "{}" })
        } finally {
            conn.disconnect()
        }
    }
}
