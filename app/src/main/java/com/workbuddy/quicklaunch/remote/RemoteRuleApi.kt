package com.workbuddy.quicklaunch.remote

import com.workbuddy.quicklaunch.data.Automation
import com.workbuddy.quicklaunch.data.RepeatMode
import com.workbuddy.quicklaunch.data.RuleException
import com.workbuddy.quicklaunch.data.TriggerType
import com.workbuddy.quicklaunch.util.HolidayChecker
import com.workbuddy.quicklaunch.util.Scheduler
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/**
 * 远程的业务层：查看 / 改期 / 临时请假 / 恢复。
 *
 * ## 最重要的一条设计约束
 *
 * 远程**不是第二条写入通道**，而是本地编辑的另一个入口。改一条规则时执行的动作
 * 与 [com.workbuddy.quicklaunch.util.AutomationFormController.save] 的编辑分支逐项对齐：
 *
 * ```
 * 留档旧值 → 合并（沿用 createdAt）→ 写库 → 取消旧闹钟 → 按新值重排
 * ```
 *
 * 少任何一步都有具体后果：
 * - 少了「留档」：用户在本地点「撤销」会还原到一个不存在的中途状态
 * - 少了「沿用 createdAt」：规则会因列表 `ORDER BY createdAt DESC` 跳到最顶部
 * - 少了「取消旧闹钟」：`requestCode` 只认 id，改完会留下一个按旧时刻响的幽灵闹钟
 *
 * ## 单日豁免
 *
 * 用户说「明天请假，别启动」，语义是**只取消这一天**，而不是取消所有周几。
 * 所以这里操作的是 `rule_exceptions` 表，而不是去改 `repeatDays` 位图 ——
 * 改位图等于永久改掉「每周三」，用户下周三就再也不会被启动了。
 */
class RemoteRuleApi(
    private val store: RuleStore,
    private val hook: ScheduleHook,
    private val backup: (Automation) -> Unit,
    private val holidays: () -> HolidayChecker,
    private val now: () -> Long = { System.currentTimeMillis() }
) {

    sealed class WriteResult {
        data class Ok(val automation: Automation, val nextTriggerAt: Long?, val exceptions: Set<String>) : WriteResult()
        data class Err(val code: Int, val error: String, val detail: String) : WriteResult()
    }

    /** 逐日判定结果，用于「未来 N 天到底哪天会启动」的预览。 */
    data class DayStatus(
        val date: String,
        val willRun: Boolean,
        val reason: String,
        val note: String
    )

    // ── 查询 ────────────────────────────────────────────────────────

    fun list(): JSONArray {
        val arr = JSONArray()
        store.getAll().forEach { arr.put(view(it)) }
        return arr
    }

    fun viewOf(id: Long): JSONObject? = store.getById(id)?.let { view(it) }

    private fun view(a: Automation): JSONObject {
        val ex = exceptionSet(a.id)
        val next = nextAt(a, ex)
        return JSONObject().apply {
            put("id", a.id)
            put("name", a.name)
            put("targetAppName", a.targetAppName)
            put("enabled", a.enabled)
            put("triggerType", a.triggerType)
            put("isTimeRule", a.triggerType == TriggerType.TIME)
            put("schedule", JSONObject().apply {
                put("timeHour", a.timeHour)
                put("timeMinute", a.timeMinute)
                put("timeText", String.format(Locale.US, "%02d:%02d", a.timeHour, a.timeMinute))
                put("repeatMode", a.repeatMode)
                put("repeatDays", a.repeatDays)
                put("repeatDaysText", repeatDaysText(a.repeatDays))
                put("skipHolidays", a.skipHolidays)
                put("randomWindow", a.randomWindow)
                put("windowStartMin", a.windowStartMin)
                put("windowEndMin", a.windowEndMin)
            })
            put("exceptions", JSONArray().apply {
                store.exceptionsOf(a.id).sortedBy { it.date }.forEach {
                    put(JSONObject().apply {
                        put("date", it.date)
                        put("reason", it.reason)
                    })
                }
            })
            put("nextTriggerAt", next ?: JSONObject.NULL)
            put("nextTriggerText", next?.let { humanize(it, now()) } ?: "无（已停用或非定时规则）")
        }
    }

    /**
     * 未来 [days] 天的逐日判定。
     *
     * 「是否会启动」直接调用生产谓词（[Scheduler.buildSkipPredicate] /
     * [Scheduler.buildForceRunPredicate]），不在接口层另写一套判断 ——
     * 预览与实际排程必须同源，否则用户会看到「页面说明天不启动，实际明天启动了」。
     */
    fun calendar(id: Long, days: Int): List<DayStatus>? {
        val a = store.getById(id) ?: return null
        val ex = exceptionSet(id)
        val checker = holidays()
        val skip = Scheduler.buildSkipPredicate(a, checker, ex)
        val force = Scheduler.buildForceRunPredicate(checker, ex)

        val start = Calendar.getInstance().apply {
            timeInMillis = now()
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val span = days.coerceIn(1, 60)

        return (0 until span).map { offset ->
            val cal = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, offset) }
            val date = HolidayChecker.dateKey(cal)
            val excepted = ex.contains(date)
            val isMakeup = checker.isMakeupWorkday(cal)
            val willSkip = runCatching { skip(cal) }.getOrDefault(false)
            val forced = runCatching { force(cal) }.getOrDefault(false)
            val matched = matchesRepeat(a, cal)

            val reason: String
            val note: String
            when {
                !a.enabled -> {
                    reason = "DISABLED"; note = "规则已停用"
                }
                a.triggerType != TriggerType.TIME -> {
                    reason = "NOT_TIME"; note = "非定时规则（${a.triggerType}）"
                }
                excepted -> {
                    reason = "EXCEPTED"; note = "已设置当天不启动"
                }
                forced -> {
                    reason = "MAKEUP_WORKDAY"; note = "调休上班日，强制启动"
                }
                willSkip -> {
                    reason = "HOLIDAY_SKIPPED"; note = "法定休息日，已跳过"
                }
                matched -> {
                    reason = "SCHEDULED"; note = "按勾选日期启动"
                }
                else -> {
                    reason = "FILTERED"; note = "不在勾选的星期内"
                }
            }

            val willRun = a.enabled &&
                a.triggerType == TriggerType.TIME &&
                !willSkip && (forced || matched)

            DayStatus(date, willRun, reason, note)
        }
    }

    /** 干跑：按指定时刻算下一次触发，用于「改之前先看结果」。 */
    fun dryRun(id: Long, atMs: Long): Long? {
        val a = store.getById(id) ?: return null
        if (a.triggerType != TriggerType.TIME) return null
        val ex = exceptionSet(id)
        val checker = holidays()
        return runCatching {
            val cal = Calendar.getInstance().apply { timeInMillis = atMs }
            Scheduler.nextTriggerTimeFrom(
                a,
                startFrom = cal,
                shouldSkip = Scheduler.buildSkipPredicate(a, checker, ex),
                forceRun = Scheduler.buildForceRunPredicate(checker, ex),
                exceptions = ex
            )
        }.getOrNull()
    }

    // ── 写入 ────────────────────────────────────────────────────────

    /** 改排程字段（时刻 / 星期 / 跳过节假日 / 随机窗口 / 启停）。 */
    fun patch(id: Long, body: JSONObject): WriteResult {
        val old = store.getById(id) ?: return notFound(id)
        val merged = when (val r = RemoteProtocol.mergePatch(old, body)) {
            is RemoteProtocol.Result.Err -> return WriteResult.Err(r.code, r.error, r.detail)
            is RemoteProtocol.Result.Ok -> r.automation
        }
        // createdAt 必须沿用：否则规则会跳到列表最顶部（列表按 createdAt DESC 排序）
        val updated = merged.copy(id = old.id, createdAt = old.createdAt)

        backup(old)
        store.update(updated)
        hook.cancel(old)
        val ex = exceptionSet(id)
        if (updated.enabled && updated.triggerType == TriggerType.TIME) {
            hook.schedule(updated, ex)
        }
        return WriteResult.Ok(updated, nextAt(updated, ex), ex)
    }

    /**
     * 临时请假：只为这一天加豁免，不改动星期位图。
     *
     * 加完必须重排 —— 下一次触发可能本来就是这天，不重排的话
     * 系统里那个已经排好的闹钟仍会按时响起，用户会认为「远程改期没生效」。
     */
    fun addException(id: Long, dateRaw: String?, reason: String?): WriteResult {
        val a = store.getById(id) ?: return notFound(id)
        val date = RemoteProtocol.parseDate(dateRaw)
            ?: return WriteResult.Err(
                RemoteProtocol.E_BAD_REQUEST, "E_DATE",
                "date 必须是真实存在的 yyyy-MM-dd 日期"
            )
        if (a.triggerType != TriggerType.TIME) {
            return WriteResult.Err(
                RemoteProtocol.E_CONFLICT, "E_NOT_TIME",
                "只有「定时」规则才有启动日期可取消"
            )
        }

        store.addException(
            RuleException(
                ruleId = id,
                date = date,
                reason = reason?.trim()?.take(40).orEmpty(),
                createdAt = now()
            )
        )
        return reschedule(a, id)
    }

    /** 恢复某一天：删掉豁免，行为回到「按星期位图 + 节假日规则」的正常节奏。 */
    fun removeException(id: Long, dateRaw: String?): WriteResult {
        val a = store.getById(id) ?: return notFound(id)
        val date = RemoteProtocol.parseDate(dateRaw)
            ?: return WriteResult.Err(
                RemoteProtocol.E_BAD_REQUEST, "E_DATE",
                "date 必须是真实存在的 yyyy-MM-dd 日期"
            )
        val existed = store.exceptionsOf(id).any { it.date == date }
        if (!existed) {
            return WriteResult.Err(
                RemoteProtocol.E_NOT_FOUND, "E_NO_EXCEPTION",
                "$date 没有设置过豁免，无需恢复"
            )
        }
        store.removeException(id, date)
        return reschedule(a, id)
    }

    /** 豁免变更后的统一收尾：取消旧闹钟 → 用最新豁免重排 → 返回新的下次触发。 */
    private fun reschedule(a: Automation, id: Long): WriteResult {
        hook.cancel(a)
        val ex = exceptionSet(id)
        if (a.enabled && a.triggerType == TriggerType.TIME) {
            hook.schedule(a, ex)
        }
        return WriteResult.Ok(a, nextAt(a, ex), ex)
    }

    // ── 内部 ────────────────────────────────────────────────────────

    private fun exceptionSet(id: Long): Set<String> =
        runCatching { store.exceptionsOf(id).map { it.date }.toSet() }.getOrDefault(emptySet())

    private fun nextAt(a: Automation, ex: Set<String>): Long? {
        if (a.triggerType != TriggerType.TIME) return null
        return runCatching {
            val checker = holidays()
            val cal = Calendar.getInstance().apply { timeInMillis = now() }
            Scheduler.nextTriggerTimeFrom(
                a,
                startFrom = cal,
                shouldSkip = Scheduler.buildSkipPredicate(a, checker, ex),
                forceRun = Scheduler.buildForceRunPredicate(checker, ex),
                exceptions = ex
            )
        }.getOrNull()
    }

    private fun notFound(id: Long) =
        WriteResult.Err(RemoteProtocol.E_NOT_FOUND, "E_RULE_NOT_FOUND", "规则 $id 不存在")

    companion object {

        /** 与 Scheduler.advanceToValidDay 的 repeatMode 过滤口径保持一致（位图为 0 时视为不限制）。 */
        internal fun matchesRepeat(a: Automation, cal: Calendar): Boolean {
            val dow = cal.get(Calendar.DAY_OF_WEEK)
            val weekend = dow == Calendar.SATURDAY || dow == Calendar.SUNDAY
            return when (a.repeatMode) {
                RepeatMode.WEEKDAYS -> !weekend
                RepeatMode.WEEKEND -> weekend
                RepeatMode.CUSTOM -> {
                    val mask = a.repeatDays and 0x7F
                    if (mask == 0) true else ((mask shr (dow - 1)) and 1) == 1
                }
                else -> true
            }
        }

        /** 把位图翻成「周二、周四、周六」，让接口返回的东西人能看懂。 */
        fun repeatDaysText(mask: Int): String {
            val cleaned = mask and 0x7F
            if (cleaned == 0) return ""
            val names = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
            return (0..6).filter { (cleaned shr it) and 1 == 1 }.joinToString("、") { names[it] }
        }

        /** 相对时间描述。跨天只说到日期，不编造「后天」这类容易算错的词。 */
        fun humanize(ms: Long, nowMs: Long): String {
            val target = Calendar.getInstance().apply { timeInMillis = ms }
            val today = Calendar.getInstance().apply {
                timeInMillis = nowMs
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val dayDiff = ((target.timeInMillis.let {
                Calendar.getInstance().apply {
                    timeInMillis = it
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }.timeInMillis
            } - today.timeInMillis) / 86_400_000L).toInt()

            val hm = String.format(
                Locale.US, "%02d:%02d",
                target.get(Calendar.HOUR_OF_DAY), target.get(Calendar.MINUTE)
            )
            return when (dayDiff) {
                0 -> "今天 $hm"
                1 -> "明天 $hm"
                else -> String.format(
                    Locale.US, "%02d-%02d %s",
                    target.get(Calendar.MONTH) + 1,
                    target.get(Calendar.DAY_OF_MONTH),
                    hm
                )
            }
        }
    }
}
