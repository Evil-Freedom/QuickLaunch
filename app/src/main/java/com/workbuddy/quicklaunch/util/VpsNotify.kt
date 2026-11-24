package com.workbuddy.quicklaunch.util

import android.content.Context
import android.util.Log
import com.workbuddy.quicklaunch.BuildConfig
import com.workbuddy.quicklaunch.remote.vps.VpsSettings
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

/**
 * 把「任务结果」旁路上报给 VPS 的邮件通知服务。
 *
 * ## 为什么是旁路
 *
 * 启动状态机已经被真机验证过，任何「为了发邮件」的改动都不该进入它的判定路径。
 * 所以这里只做一件事：拿到最终结果后，丢进单线程队列异步 POST 一次。
 * - 上报失败只写一行 log，绝不抛异常、绝不重试、绝不影响启动；
 * - 网络超时 4s，且在执行器线程里，主流程零等待；
 * - 收件人由服务端固定，这里连收件人字段都不传。
 *
 * ## 配置
 *
 * `QL_NOTIFY_BASE` / `QL_NOTIFY_TOKEN` 在编译期从 local.properties 注入（不入库）。
 * 两者为空时整个模块静默失效 —— 没有配置就不该有网络行为。
 */
object VpsNotify {

    private const val TAG = "QL-Notify"

    /** 目标包名命中这些值时，「已启动」不等于「打卡成功」，只上报待确认。 */
    private val CHECKIN_PACKAGES = setOf("com.ss.android.lark")

    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ql-notify").apply { isDaemon = true }
    }

    private fun ts(ms: Long): String =
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(ms))

    fun onLaunchFinished(
        ctx: Context,
        pkg: String,
        appName: String,
        success: Boolean,
        reason: String,
        tries: Int,
        displayId: Int?,
        startedAt: Long,
        finishedAt: Long,
        taskId: String
    ) {
        val data = JSONObject()
            .put("task_id", taskId)
            .put("device_name", android.os.Build.MODEL)
            .put("target_app", appName)
            .put("attempts", tries)
            .put("planned_time", ts(startedAt))
            .put("actual_time", ts(finishedAt))
            .put("display", displayId?.let { "Display #$it" } ?: "-")
            .put("final_state", if (success) "已进入前台" else reason.ifBlank { "未确认进入前台" })
        if (!success) data.put("last_error", reason.ifBlank { "未确认进入前台" })

        send(ctx, if (success) "launch_success" else "launch_failed", data)

        // 飞书这类打卡 App：启动成功 ≠ 打卡成功，缺可信考勤证据时只报「待确认」
        if (success && pkg in CHECKIN_PACKAGES) {
            send(
                ctx, "checkin_unknown",
                JSONObject()
                    .put("device_name", android.os.Build.MODEL)
                    .put("target_app", appName)
                    .put("actual_time", ts(finishedAt))
                    .put("reason", "目标应用已启动，但本机无法读取考勤记录，未取得可信打卡证据")
            )
        }
    }

    /** 自动解锁进入安全停用（连续失败达上限）。绝不带上任何口令相关信息。 */
    fun onUnlockSecurity(ctx: Context, reason: String) {
        send(
            ctx, "unlock_alert",
            JSONObject()
                .put("device_name", android.os.Build.MODEL)
                .put("checked_at", ts(System.currentTimeMillis()))
                .put("security_state", "已自动停用")
                .put("auto_disabled", "是")
                .put("reason", reason)
        )
    }

    /**
     * 上报地址：优先跟随 VPS 同步已配置的主/备入口。
     *
     * 原因是真机实测：家里的路由器会拦 `*.duckdns.org`，写死主域名必然 SocketException；
     * 而 VpsSettings 里的备用入口（sslip.io）本来就是为这种情况准备的。
     */
    private fun candidates(ctx: Context): List<String> {
        val out = LinkedHashSet<String>()
        val settings = runCatching { VpsSettings.load(ctx) }.getOrNull()
        listOfNotNull(settings?.baseUrl, settings?.backupUrl).forEach { raw ->
            runCatching {
                val uri = java.net.URI(raw)
                val host = uri.host ?: return@runCatching
                val port = if (uri.port > 0) ":${uri.port}" else ""
                out += "${uri.scheme}://$host$port/notify"
            }
        }
        BuildConfig.QL_NOTIFY_BASE.trim().trimEnd('/').takeIf { it.isNotEmpty() }?.let { out += it }
        return out.toList()
    }

    private fun send(ctx: Context, type: String, data: JSONObject) {
        val token = BuildConfig.QL_NOTIFY_TOKEN.trim()
        if (token.isEmpty()) return
        val bases = candidates(ctx)
        if (bases.isEmpty()) {
            Log.i(TAG, "未配置通知入口，跳过 $type")
            return
        }
        val basesSnapshot = bases

        val deviceId = runCatching { VpsSettings.load(ctx).deviceId }.getOrNull().orEmpty()
        if (deviceId.isBlank()) {
            Log.i(TAG, "未配对 VPS，跳过 $type")
            return
        }

        val body = JSONObject()
            .put("type", type)
            .put("device_id", deviceId)
            .put("event_id", UUID.randomUUID().toString())
            .put("data", data)
            .toString()

        exec.execute {
            for (base in basesSnapshot) {
                val ok = runCatching {
                    val conn = URL("$base/v1/events").openConnection() as HttpURLConnection
                    conn.connectTimeout = 4000
                    conn.readTimeout = 4000
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    conn.setRequestProperty("Authorization", "Bearer $token")
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    val code = conn.responseCode
                    conn.disconnect()
                    if (code / 100 != 2) Log.w(TAG, "上报 $type $base http=$code")
                    code / 100 == 2
                }.getOrElse {
                    Log.w(TAG, "上报 $type $base 异常 ${it.javaClass.simpleName}")
                    false
                }
                if (ok) return@execute
            }
        }
    }
}
