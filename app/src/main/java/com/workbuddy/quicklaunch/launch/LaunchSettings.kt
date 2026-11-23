package com.workbuddy.quicklaunch.launch

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 启动重试策略与执行记录的持久化。
 *
 * ## 为什么不用 Room
 *
 * 策略是十来个标量，执行记录是「最近 20 条」的环形列表。为它们加一张表
 * 就要再写一次 Migration —— 而迁移是这个项目历史上最大的数据风险来源
 * （见 `docs/AUDIT.md` §3.3）。规则本体仍然只在 `automations` 表里，
 * 这里不引入第二份真源。
 *
 * ## 与远程配置的关系
 *
 * 远程下发的策略最终也落到这里（见 `remote/pull/`）：远程只是「另一个写入入口」，
 * 与本地界面改设置走的是同一个 [savePolicy]，不存在两套生效路径。
 */
object LaunchSettings {

    private const val SP = "quicklaunch_launch"
    private const val MAX_HISTORY = 20

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(SP, Context.MODE_PRIVATE)

    // ── 策略 ────────────────────────────────────────────────────────

    fun loadPolicy(context: Context): LaunchPolicy {
        val p = prefs(context)
        return runCatching {
            LaunchPolicy(
                enabled = p.getBoolean(K_ENABLED, true),
                maxAttempts = p.getInt(K_MAX_ATTEMPTS, LaunchPolicy.DEFAULT_MAX_ATTEMPTS),
                firstVerifyDelayMs = p.getLong(
                    K_FIRST_DELAY, LaunchPolicy.DEFAULT_FIRST_VERIFY_DELAY_MS
                ),
                verifyWindowMs = p.getLong(K_VERIFY_WINDOW, LaunchPolicy.DEFAULT_VERIFY_WINDOW_MS),
                verifyIntervalMs = p.getLong(K_VERIFY_INTERVAL, LaunchPolicy.DEFAULT_VERIFY_INTERVAL_MS),
                requiredStableHits = p.getInt(K_STABLE_HITS, LaunchPolicy.DEFAULT_REQUIRED_STABLE_HITS),
                retryDelaysMs = readDelays(p.getString(K_RETRY_DELAYS, null)),
                totalTimeoutMs = p.getLong(K_TOTAL_TIMEOUT, LaunchPolicy.DEFAULT_TOTAL_TIMEOUT_MS),
                notifyOnFailure = p.getBoolean(K_NOTIFY, true),
                allowManualRetry = p.getBoolean(K_MANUAL_RETRY, true)
            )
        }.getOrDefault(LaunchPolicy()).sanitized()
    }

    fun savePolicy(context: Context, policy: LaunchPolicy) {
        val p = policy.sanitized()
        prefs(context).edit()
            .putBoolean(K_ENABLED, p.enabled)
            .putInt(K_MAX_ATTEMPTS, p.maxAttempts)
            .putLong(K_FIRST_DELAY, p.firstVerifyDelayMs)
            .putLong(K_VERIFY_WINDOW, p.verifyWindowMs)
            .putLong(K_VERIFY_INTERVAL, p.verifyIntervalMs)
            .putInt(K_STABLE_HITS, p.requiredStableHits)
            .putString(K_RETRY_DELAYS, p.retryDelaysMs.joinToString(","))
            .putLong(K_TOTAL_TIMEOUT, p.totalTimeoutMs)
            .putBoolean(K_NOTIFY, p.notifyOnFailure)
            .putBoolean(K_MANUAL_RETRY, p.allowManualRetry)
            .apply()
    }

    private fun readDelays(raw: String?): List<Long> {
        if (raw.isNullOrBlank()) return LaunchPolicy.DEFAULT_RETRY_DELAYS_MS
        val parsed = raw.split(',').mapNotNull { it.trim().toLongOrNull() }
        return parsed.ifEmpty { LaunchPolicy.DEFAULT_RETRY_DELAYS_MS }
    }

    // ── 未完成任务的快照（进程被杀后恢复用）────────────────────────

    data class ActiveAttempt(
        val attemptId: String,
        val ruleId: Long,
        val pkg: String,
        val appName: String,
        val startedAt: Long,
        val attemptIndex: Int,
        val lastState: String
    )

    fun saveActive(context: Context, snapshot: ActiveAttempt) {
        runCatching {
            val json = JSONObject().apply {
                put("attemptId", snapshot.attemptId)
                put("ruleId", snapshot.ruleId)
                put("pkg", snapshot.pkg)
                put("appName", snapshot.appName)
                put("startedAt", snapshot.startedAt)
                put("attemptIndex", snapshot.attemptIndex)
                put("lastState", snapshot.lastState)
            }
            prefs(context).edit().putString(K_ACTIVE, json.toString()).apply()
        }
    }

    fun loadActive(context: Context): ActiveAttempt? = runCatching {
        val raw = prefs(context).getString(K_ACTIVE, null) ?: return null
        val o = JSONObject(raw)
        val id = o.optString("attemptId")
        val pkg = o.optString("pkg")
        if (id.isBlank() || pkg.isBlank()) return null
        ActiveAttempt(
            attemptId = id,
            ruleId = o.optLong("ruleId", -1),
            pkg = pkg,
            appName = o.optString("appName"),
            startedAt = o.optLong("startedAt"),
            attemptIndex = o.optInt("attemptIndex", 1),
            lastState = o.optString("lastState")
        )
    }.getOrNull()

    fun clearActive(context: Context) {
        prefs(context).edit().remove(K_ACTIVE).apply()
    }

    // ── 执行记录 ────────────────────────────────────────────────────

    /** 一条执行结论。远程接口与失败通知都从这里取数据。 */
    data class Record(
        val attemptId: String,
        val ruleId: Long,
        val pkg: String,
        val appName: String,
        val state: String,
        val reason: String,
        val startedAt: Long,
        val finishedAt: Long,
        val tries: Int,
        val detail: String = ""
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("attemptId", attemptId)
            put("ruleId", ruleId)
            put("pkg", pkg)
            put("appName", appName)
            put("state", state)
            put("reason", reason)
            put("startedAt", startedAt)
            put("finishedAt", finishedAt)
            put("tries", tries)
            put("detail", detail)
        }
    }

    fun record(context: Context, record: Record) {
        runCatching {
            val p = prefs(context)
            val arr = runCatching { JSONArray(p.getString(K_HISTORY, "[]")) }.getOrDefault(JSONArray())
            arr.put(record.toJson())
            val kept = JSONArray()
            val from = (arr.length() - MAX_HISTORY).coerceAtLeast(0)
            for (i in from until arr.length()) kept.put(arr.get(i))
            p.edit().putString(K_HISTORY, kept.toString()).apply()
        }
    }

    /** 最新在前。 */
    fun history(context: Context): List<Record> = runCatching {
        val raw = prefs(context).getString(K_HISTORY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Record(
                attemptId = o.optString("attemptId"),
                ruleId = o.optLong("ruleId", -1),
                pkg = o.optString("pkg"),
                appName = o.optString("appName"),
                state = o.optString("state"),
                reason = o.optString("reason"),
                startedAt = o.optLong("startedAt"),
                finishedAt = o.optLong("finishedAt"),
                tries = o.optInt("tries"),
                detail = o.optString("detail")
            )
        }.reversed()
    }.getOrDefault(emptyList())

    fun clearHistory(context: Context) {
        prefs(context).edit().remove(K_HISTORY).apply()
    }

    // ── key ─────────────────────────────────────────────────────────

    private const val K_ENABLED = "retry_enabled"
    private const val K_MAX_ATTEMPTS = "retry_max_attempts"
    private const val K_FIRST_DELAY = "verify_first_delay_ms"
    private const val K_VERIFY_WINDOW = "verify_window_ms"
    private const val K_VERIFY_INTERVAL = "verify_interval_ms"
    private const val K_STABLE_HITS = "verify_stable_hits"
    private const val K_RETRY_DELAYS = "retry_delays_ms"
    private const val K_TOTAL_TIMEOUT = "retry_total_timeout_ms"
    private const val K_NOTIFY = "notify_on_failure"
    private const val K_MANUAL_RETRY = "allow_manual_retry"
    private const val K_ACTIVE = "active_attempt"
    private const val K_HISTORY = "history"
}
