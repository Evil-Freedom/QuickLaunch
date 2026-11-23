package com.workbuddy.quicklaunch.remote.vps

import android.app.AlarmManager
import android.os.Build
import com.workbuddy.quicklaunch.BuildConfig
import com.workbuddy.quicklaunch.util.AutoUnlock
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.workbuddy.quicklaunch.data.Automation
import com.workbuddy.quicklaunch.launch.LaunchPolicy
import com.workbuddy.quicklaunch.launch.LaunchSettings
import com.workbuddy.quicklaunch.receiver.VpsSyncReceiver
import com.workbuddy.quicklaunch.remote.RemoteBindings
import com.workbuddy.quicklaunch.remote.RemoteAuth
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
        return try {
            pullAndApply(app, settings)
        } finally {
            // 本地改了规则也要让看板看到。放在 finally：即使这轮「拉」失败，
            // 手机上的改动也应该照常「推」上去 —— 两者互不依赖。
            runCatching { reportRulesIfChanged(app, settings) }
        }
    }

    private fun pullAndApply(app: Context, settings: VpsSettings.Data): Outcome {
        val since = settings.appliedVersion
        val appliedHash = VpsSettings.appliedHash(app)

        var lastFail: FetchOutcome.Fail? = null
        for (url in candidateUrls(app, settings)) {
            // nonce 必须每个地址都新生成：主地址那次请求可能已经在服务端记过
            // nonce（比如 502 是网关返回的，请求其实到过 api），复用会被判重放。
            val nonce = VpsClient.newNonce()
            when (val fetched = VpsClient.fetch(settings, since, nonce, appliedHash, url)) {
                is FetchOutcome.NotModified -> {
                    VpsSettings.setLastGoodUrl(app, url)
                    VpsSettings.setLastSync(app, System.currentTimeMillis(), "无更新")
                    return Outcome.UpToDate(settings.appliedVersion)
                }

                is FetchOutcome.Fail -> {
                    lastFail = fetched
                    // 只有「网络/网关」层的失败才值得换地址。
                    // 401 是签名或设备问题、400 是参数问题 —— 换个域名一模一样。
                    if (!VpsUrls.retryable(fetched.code)) {
                        VpsSettings.setLastSync(
                            app, System.currentTimeMillis(), "拉取失败：${fetched.reason}"
                        )
                        return Outcome.Failed(fetched.reason)
                    }
                    Log.i(TAG, "地址不可用，尝试下一个：$url (${fetched.reason})")
                }

                is FetchOutcome.Ok -> {
                    VpsSettings.setLastGoodUrl(app, url)
                    val secret = settings.secret()
                        ?: return Outcome.Failed("设备密钥不可用")
                    return when (val parsed = VpsEnvelope.parse(
                        fetched.body, secret, nonce, settings.appliedVersion
                    )) {
                        is VpsEnvelope.Result.NoChange -> {
                            VpsSettings.setLastSync(
                                app, System.currentTimeMillis(), "无更新（v${parsed.version}）"
                            )
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

        // 所有地址都不通：本地状态一律不动，等下一次窗口再试。
        val reason = lastFail?.reason ?: "没有可用地址"
        VpsSettings.setLastSync(app, System.currentTimeMillis(), "拉取失败：$reason")
        return Outcome.Failed(reason)
    }

    /** 顺序见 [VpsUrls.candidates]：上次成功的 → 主 → 备。 */
    private fun candidateUrls(app: Context, settings: VpsSettings.Data): List<String> =
        VpsUrls.candidates(
            lastGood = VpsSettings.lastGoodUrl(app),
            baseUrl = settings.baseUrl,
            backupUrl = settings.backupUrl
        )

    private fun apply(
        app: Context,
        settings: VpsSettings.Data,
        parsed: VpsEnvelope.Result.Ok
    ): Outcome {
        val api = RemoteBindings.api(app)
        var changed = 0
        var failed = 0
        val failures = mutableListOf<String>()

        // 先执行删除，再应用修改：同一版里既删又改同一条规则时，
        // 如果顺序反了，刚 patch 好的规则会被紧随其后的 delete 抹掉。
        parsed.deleted.forEach { id ->
            when (val d = api.delete(id)) {
                is RemoteRuleApi.WriteResult.Ok -> changed++
                is RemoteRuleApi.WriteResult.Err -> {
                    failed++
                    failures += "#$id 删除失败 ${d.error}"
                }
            }
        }

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

        // 自动解锁策略：payload 里没有 unlock 字段时**什么都不做** ——
        // 解锁是安全敏感功能，不能被「下发了不含 unlock 的配置」意外关闭。
        var unlockNote = ""
        parsed.unlock?.let { u ->
            val policy = AutoUnlock.RemotePolicy(
                enabled = u.optBoolean("enabled", AutoUnlock.isEnabled(app)),
                chargingOnly = u.optBoolean("chargingOnly", AutoUnlock.isChargingOnly(app))
            )
            when (val r = AutoUnlock.applyRemotePolicy(app, policy)) {
                is AutoUnlock.ApplyResult.Applied ->
                    unlockNote = "解锁策略已应用（enabled=${r.enabled}, chargingOnly=${r.chargingOnly}）"
                is AutoUnlock.ApplyResult.Refused -> {
                    failures += "unlock: ${r.reason}"
                    unlockNote = "解锁策略被拒绝：${r.reason}（${r.detail}）"
                }
            }
        }

        VpsSettings.setAppliedVersion(app, parsed.version)
        VpsSettings.setAppliedHash(app, parsed.hash)
        val detail = buildString {
            append("v").append(parsed.version)
            append(" 应用 ").append(changed).append(" 条")
            if (failed > 0) append("，失败 ").append(failed).append(" 条：").append(failures.joinToString(","))
            if (unlockNote.isNotEmpty()) append("；").append(unlockNote)
        }
        VpsSettings.setLastSync(app, System.currentTimeMillis(), detail)
        Log.i(TAG, detail)

        // 回报失败不影响已生效的配置；回报里附带设备信息与解锁状态，供看板展示
        runCatching {
            VpsClient.report(
                settings, parsed.version, failed == 0, detail,
                deviceInfo = deviceInfo(app),
                unlockStatus = AutoUnlockStatus.snapshot(app),
                // 必须和这一轮真正连通的地址一致：否则会出现
                // 「配置拉下来了、回执却发不出去」—— 主地址被网络拦时就是这种局面。
                baseUrl = VpsSettings.lastGoodUrl(app).ifBlank { settings.baseUrl }
            )
        }

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

    /**
     * 设备身份信息。让看板能区分「哪台手机」「跑的哪个版本」，
     * 也方便排查「新策略在旧 APK 上不支持 unlock 字段」这类兼容性问题。
     */
    /**
     * 当前规则清单快照。
     *
     * 手机是权威方 —— 下发的规则 id 若在本地不存在，`RemoteRuleApi.patch`
     * 会直接返回 notFound。所以服务端必须先知道手机上有哪些规则，
     * 否则网页上根本无从编辑（新部署时规则表是空的）。
     *
     * 字段与 Android 规则实体对齐（含蓝牙/WiFi 名称与班表锚点），
     * 这样看板才能像 App 一样把规则讲清楚。**不含任何凭据、不含 PIN。**
     */
    internal fun rulesSnapshot(context: Context): JSONArray = JSONArray().apply {
        runCatching {
            com.workbuddy.quicklaunch.data.AppDatabase.get(context)
                .automationDao().getAll()
                .forEach { a ->
                    put(org.json.JSONObject().apply {
                        put("id", a.id)
                        put("name", a.name)
                        put("targetPackage", a.targetPackage)
                        put("targetAppName", a.targetAppName)
                        put("triggerType", a.triggerType)
                        put("timeHour", a.timeHour)
                        put("timeMinute", a.timeMinute)
                        put("repeatMode", a.repeatMode)
                        put("repeatDays", a.repeatDays)
                        put("skipHolidays", a.skipHolidays)
                        put("enabled", a.enabled)
                        put("randomWindow", a.randomWindow)
                        put("windowStartMin", a.windowStartMin)
                        put("windowEndMin", a.windowEndMin)
                        put("bluetoothName", a.bluetoothName)
                        put("wifiName", a.wifiName)
                        put("shiftAnchor", a.shiftAnchor)
                        put("shiftOffsetMin", a.shiftOffsetMin)
                    })
                }
        }
    }

    internal fun deviceInfo(context: Context): org.json.JSONObject = org.json.JSONObject().apply {
        put("model", android.os.Build.MODEL)
        put("manufacturer", android.os.Build.MANUFACTURER)
        put("androidVersion", android.os.Build.VERSION.RELEASE)
        put("sdkInt", android.os.Build.VERSION.SDK_INT)
        put("appVersion", com.workbuddy.quicklaunch.BuildConfig.VERSION_NAME)
        put("appVersionCode", com.workbuddy.quicklaunch.BuildConfig.VERSION_CODE)
        put("rules", rulesSnapshot(context))
    }

    /**
     * 本地规则变了就上报给看板。
     *
     * 没有这一步，「在手机上改规则」在看板上永远看不到 —— 原来的上报只挂在
     * 「应用了服务端新版本」之后，手机自己的修改根本没有触发点。
     *
     * 用指纹判断是否真的变了：不变就一个请求都不发，避免每分钟往服务器推
     * 一份完全相同的清单。
     */
    private fun reportRulesIfChanged(app: Context, settings: VpsSettings.Data) {
        val snapshot = rulesSnapshot(app)
        val fp = RemoteAuth.sha256Hex(snapshot.toString().toByteArray(Charsets.UTF_8))
        if (fp == VpsSettings.ruleFingerprint(app)) return

        val sent = VpsClient.report(
            settings,
            version = settings.appliedVersion,
            ok = true,
            detail = "本地规则已同步到看板（${snapshot.length()} 条）",
            deviceInfo = deviceInfo(app),
            unlockStatus = AutoUnlockStatus.snapshot(app),
            baseUrl = VpsSettings.lastGoodUrl(app).ifBlank { settings.baseUrl }
        )
        if (sent) {
            VpsSettings.setRuleFingerprint(app, fp)
            Log.i(TAG, "规则清单已上报（${snapshot.length()} 条）")
        }
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
