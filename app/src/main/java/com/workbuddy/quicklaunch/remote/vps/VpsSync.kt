package com.workbuddy.quicklaunch.remote.vps

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.workbuddy.quicklaunch.data.Automation
import com.workbuddy.quicklaunch.launch.LaunchPolicy
import com.workbuddy.quicklaunch.launch.LaunchSettings
import com.workbuddy.quicklaunch.receiver.VpsSyncReceiver
import com.workbuddy.quicklaunch.remote.RemoteBindings
import com.workbuddy.quicklaunch.remote.RemoteRuleApi
import org.json.JSONArray
import org.json.JSONObject

/**
 * VPS 配置的一次完整同步：拉取 → 校验 → 应用 → 回报。
 *
 * ## 最重要的两条不变量
 *
 * 1. **服务端不可用时本地配置照常工作。** 同步失败只写一条日志，
 *    绝不改动 Room 里的规则、也绝不取消已排好的闹钟。
 *    需求 §4.2 的「VPS 不可用时继续使用本地最后一次有效配置」就落在这里 ——
 *    实现方式是「什么都不做」，而不是「回滚到某个缓存副本」。
 * 2. **应用是逐条原子的。** 某条规则应用失败不影响其它条，也不阻断版本推进，
 *    但失败会被记进回报明细，用户能在管理界面看到「这条没生效」。
 *
 * ## 为什么复用 RemoteRuleApi.patch
 *
 * 远程写入的执行序（留档旧值 → 合并 → 取消旧闹钟 → 按新值重排）是最容易出错的地方，
 * 而它已经在 `RemoteRuleApi` 里实现并被 `RemoteRuleApiTest` 的 26 个用例钉住了。
 * VPS 通道只是「另一个调用方」，不是第二份实现。
 */
object VpsSync {

    private const val TAG = "QL-VpsSync"
    private const val PATH_CONFIG = "/api/v1/config"

    sealed class Outcome {
        data object NotConfigured : Outcome()
        data class Applied(val version: Long, val changed: Int, val failed: Int, val detail: String) : Outcome()
        data class UpToDate(val version: Long) : Outcome()
        data class Failed(val reason: String) : Outcome()
    }

    /** 阻塞调用，必须在后台线程执行。 */
    fun runOnce(context: Context): Outcome {
        val app = context.applicationContext
        val settings = VpsSettings.load(app)
        if (!settings.ready()) return Outcome.NotConfigured

        val nonce = VpsClient.newNonce()
        val since = settings.appliedVersion
        when (val fetched = VpsClient.fetch(settings, since, nonce)) {
            is FetchOutcome.NotModified -> {
                VpsSettings.setLastSync(app, System.currentTimeMillis(), "无更新")
                return Outcome.UpToDate(settings.appliedVersion)
            }
            is FetchOutcome.Fail -> {
                // 关键：这里不碰任何本地状态。规则、闹钟、重试策略全部维持原样。
                VpsSettings.setLastSync(
                    app, System.currentTimeMillis(), "拉取失败：${fetched.reason}"
                )
                return Outcome.Failed(fetched.reason)
            }
            is FetchOutcome.Ok -> {
                val secret = settings.secret()
                    ?: return Outcome.Failed("设备密钥不可用")
                return when (val parsed = VpsEnvelope.parse(
                    fetched.body, secret, nonce, settings.appliedVersion
                )) {
                    is VpsEnvelope.Result.NoChange -> {
                        VpsSettings.setLastSync(app, System.currentTimeMillis(), "无更新（v${parsed.version}）")
                        Outcome.UpToDate(parsed.version)
                    }
                    is VpsEnvelope.Result.Err -> {
                        VpsSettings.setLastSync(
                            app, System.currentTimeMillis(), "校验失败：${parsed.reason}"
                        )
                        Log.w(TAG, "配置校验失败 ${parsed.reason}: ${parsed.detail}")
                        Outcome.Failed("${parsed.reason}: ${parsed.detail}")
                    }
                    is VpsEnvelope.Result.Ok -> apply(app, settings, parsed)
                }
            }
        }
    }

    private fun apply(
        app: Context,
        settings: VpsSettings.Data,
        parsed: VpsEnvelope.Result.Ok
    ): Outcome {
        val api = RemoteBindings.api(app)
        var changed = 0
        var failed = 0
        val failures = mutableListOf<String>()

        parsed.rules.forEach { rule ->
            val id = rule.optLong("id", -1L)
            if (id <= 0) {
                failed++
                failures += "缺少合法 id"
                return@forEach
            }
            // `id` 是定位字段，不属于可改字段。mergePatch 的白名单会拒绝它，
            // 所以先摘出来再交给同一套合并逻辑。
            val body = JSONObject(rule.toString()).apply { remove("id") }
            when (val r = api.patch(id, body)) {
                is RemoteRuleApi.WriteResult.Ok -> changed++
                is RemoteRuleApi.WriteResult.Err -> {
                    failed++
                    failures += "#$id ${r.error}"
                }
            }
        }

        // 重试策略是全局的（不是每规则）：需求里「重试次数 / 间隔 / 验证超时」
        // 是设备级参数，做成每规则会让「这次为什么重试了 5 次」变得无法解释。
        parsed.retry?.let { retry ->
            runCatching { applyRetryPolicy(app, retry) }
                .onFailure { failures += "retry: ${it.message}" }
        }

        VpsSettings.setAppliedVersion(app, parsed.version)
        val detail = buildString {
            append("v").append(parsed.version)
            append(" 应用 ").append(changed).append(" 条")
            if (failed > 0) append("，失败 ").append(failed).append(" 条：").append(failures.joinToString(","))
        }
        VpsSettings.setLastSync(app, System.currentTimeMillis(), detail)
        Log.i(TAG, detail)

        // 回报失败不影响已生效的配置
        runCatching { VpsClient.report(settings, parsed.version, failed == 0, detail) }

        return Outcome.Applied(parsed.version, changed, failed, detail)
    }

    /**
     * 把远程下发的重试参数合并进本地策略。
     *
     * 逐项「有则覆盖、无则保留」而不是整体替换：服务端只下发 maxAttempts 时，
     * 其余参数应当保持用户在本地的设置，不能被默认值冲掉。
     * 所有值最终都会经过 [LaunchPolicy.sanitized] 夹紧。
     */
    internal fun applyRetryPolicy(context: Context, retry: JSONObject) {
        val cur = LaunchSettings.loadPolicy(context)
        val delays = parseDelays(retry.optJSONArray("retryDelaysMs"))
        val merged = cur.copy(
            enabled = if (retry.has("enabled")) retry.optBoolean("enabled", cur.enabled) else cur.enabled,
            maxAttempts = if (retry.has("maxAttempts")) {
                retry.optInt("maxAttempts", cur.maxAttempts)
            } else cur.maxAttempts,
            verifyWindowMs = if (retry.has("verifyWindowMs")) {
                retry.optLong("verifyWindowMs", cur.verifyWindowMs)
            } else cur.verifyWindowMs,
            firstVerifyDelayMs = if (retry.has("firstVerifyDelayMs")) {
                retry.optLong("firstVerifyDelayMs", cur.firstVerifyDelayMs)
            } else cur.firstVerifyDelayMs,
            retryDelaysMs = delays ?: cur.retryDelaysMs,
            totalTimeoutMs = if (retry.has("totalTimeoutMs")) {
                retry.optLong("totalTimeoutMs", cur.totalTimeoutMs)
            } else cur.totalTimeoutMs,
            notifyOnFailure = if (retry.has("notifyOnFailure")) {
                retry.optBoolean("notifyOnFailure", cur.notifyOnFailure)
            } else cur.notifyOnFailure,
            allowManualRetry = if (retry.has("allowManualRetry")) {
                retry.optBoolean("allowManualRetry", cur.allowManualRetry)
            } else cur.allowManualRetry
        )
        LaunchSettings.savePolicy(context, merged)
    }

    private fun parseDelays(arr: JSONArray?): List<Long>? {
        if (arr == null || arr.length() == 0) return null
        val list = ArrayList<Long>(arr.length())
        for (i in 0 until arr.length()) {
            val v = arr.optLong(i, -1)
            if (v > 0) list += v
        }
        return list.ifEmpty { null }
    }

    /** 供界面/诊断：当前同步状态的文字摘要。 */
    fun describe(context: Context): String {
        val s = VpsSettings.load(context)
        val (at, note) = VpsSettings.lastSync(context)
        if (!s.enabled) return "未启用"
        if (!s.ready()) return "配置不完整（需要 https 地址 + 设备 ID + 密钥）"
        val when_ = if (at == 0L) "从未同步" else {
            android.text.format.DateFormat.format("MM-dd HH:mm", at).toString()
        }
        return "v${s.appliedVersion} · $when_ · $note"
    }

    /** 已应用版本，供管理界面展示「手机当前在哪个版本」。 */
    fun appliedVersion(context: Context): Long = VpsSettings.load(context).appliedVersion

    /** 规则目标包（仅用于校验服务端下发的 id 在本机确实存在）。 */
    @Suppress("unused")
    private fun exists(api: RemoteRuleApi, id: Long): Automation? =
        api.viewOf(id)?.let { null }
}

/**
 * 周期性同步的排程。
 *
 * 用 `setAndAllowWhileIdle`（**非精确**闹钟）而不是 `setExactAndAllowWhileIdle`：
 * 配置同步允许几分钟的抖动，而精确闹钟在 Doze 下每个应用有配额限制，
 * 让 15 分钟一次的同步去消耗配额，会挤掉真正需要精确的「定时启动」闹钟。
 */
object VpsSyncScheduler {

    private const val REQUEST_CODE = 9001
    private const val ACTION = "com.workbuddy.quicklaunch.action.VPS_SYNC"

    fun schedule(context: Context) {
        val app = context.applicationContext
        val settings = VpsSettings.load(app)
        if (!settings.ready()) {
            cancel(app)
            return
        }
        runCatching {
            val am = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val at = System.currentTimeMillis() + settings.intervalSec * 1000L
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent(app))
            VpsSettings.log("下次同步已排程：+${settings.intervalSec}s")
        }
    }

    fun cancel(context: Context) {
        runCatching {
            val am = context.applicationContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            am?.cancel(pendingIntent(context.applicationContext))
        }
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, VpsSyncReceiver::class.java).setAction(ACTION)
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
