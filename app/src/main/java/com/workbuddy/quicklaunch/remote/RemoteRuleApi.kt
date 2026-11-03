package com.workbuddy.quicklaunch.remote

import com.workbuddy.quicklaunch.data.Automation
import com.workbuddy.quicklaunch.data.DailyShift
import com.workbuddy.quicklaunch.data.RepeatMode
import com.workbuddy.quicklaunch.data.RuleException
import com.workbuddy.quicklaunch.data.ShiftAnchor
import com.workbuddy.quicklaunch.data.TriggerType
import com.workbuddy.quicklaunch.util.HolidayChecker
import com.workbuddy.quicklaunch.util.Scheduler
import com.workbuddy.quicklaunch.util.ShiftTable
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
    private val shiftStore: ShiftStore? = null,
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
                put("shiftAnchor", a.shiftAnchor)
                put("shiftOffsetMin", a.shiftOffsetMin)
                put("shiftText", shiftText(a))
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

        // 班表模式下星期位图与节假日都不参与判定，必须单独走一套，
        // 否则预览会说「不在勾选的星期内」而实际班表模式根本不看星期。
        if (a.shiftAnchor != ShiftAnchor.FIXED) {
            val shifts = shiftsFor(a, start)
            return (0 until span).map { offset ->
                val cal = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, offset) }
                val date = HolidayChecker.dateKey(cal)
                val shift = shifts?.of(date)
                when {
                    !a.enabled -> DayStatus(date, false, "DISABLED", "规则已停用")
                    ex.contains(date) -> DayStatus(date, false, "EXCEPTED", "已设置当天不启动")
                    shift == null -> DayStatus(date, false, "NO_SHIFT", "班表无这天的班次（休息或未排班）")
                    else -> DayStatus(
                        date, true, "SHIFT",
                        "按班次启动（${shiftLabel(a, shift)}）"
                    )
                }
            }
        }

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
                exceptions = ex,
                shifts = shiftsFor(a, cal)
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

    // ── 班表同步 ────────────────────────────────────────────────────

    sealed class ShiftResult {
        data class Ok(
            val upserted: Int,
            val pruned: Int,
            val total: Int,
            val latest: String?
        ) : ShiftResult()

        data class Err(val code: Int, val error: String, val detail: String) : ShiftResult()
    }

    /** 列出班表（[fromKey] 为 null 时全量）。 */
    fun listShifts(fromKey: String?, limit: Int = 400): JSONArray {
        val s = shiftStore ?: return JSONArray()
        val list = runCatching {
            if (fromKey.isNullOrBlank()) s.all() else s.from(fromKey)
        }.getOrDefault(emptyList())
        return JSONArray().apply {
            list.take(limit.coerceIn(1, MAX_SHIFT_BATCH)).forEach { put(shiftJson(it)) }
        }
    }

    fun shiftStats(): JSONObject {
        val s = shiftStore
        return JSONObject().apply {
            put("enabled", s != null)
            put("count", runCatching { s?.all()?.size ?: 0 }.getOrDefault(0))
            put("latest", runCatching { s?.latest() }.getOrNull() ?: JSONObject.NULL)
        }
    }

    /**
     * 批量写入班表（同一天覆盖，幂等）。
     *
     * 校验刻意严格：date 必须是真实日期、inMin/outMin 必须是合法分钟数。
     * 这些值直接决定闹钟落点，放进来一个 2026-02-30 或 inMin=-1，
     * 排程侧就会静默算出一个不存在的时刻，比直接报错难查得多。
     */
    fun upsertShifts(body: JSONObject): ShiftResult {
        val s = shiftStore ?: return ShiftResult.Err(
            RemoteProtocol.E_CONFLICT, "E_SHIFTS_OFF", "当前版本不支持班表同步"
        )
        val arr = body.optJSONArray("shifts")
            ?: return ShiftResult.Err(RemoteProtocol.E_BAD_REQUEST, "E_SHIFTS", "缺少 shifts 数组")
        if (arr.length() == 0) {
            return ShiftResult.Err(RemoteProtocol.E_BAD_REQUEST, "E_SHIFTS", "shifts 不能为空")
        }
        if (arr.length() > MAX_SHIFT_BATCH) {
            return ShiftResult.Err(
                RemoteProtocol.E_BODY_TOO_LARGE, "E_SHIFTS_TOO_MANY",
                "单次最多 $MAX_SHIFT_BATCH 天"
            )
        }

        val items = ArrayList<DailyShift>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
                ?: return ShiftResult.Err(RemoteProtocol.E_BAD_REQUEST, "E_SHIFTS", "第 ${i + 1} 条不是对象")
            val date = RemoteProtocol.parseDate(o.optString("date"))
                ?: return ShiftResult.Err(RemoteProtocol.E_BAD_REQUEST, "E_DATE", "第 ${i + 1} 条 date 必须是 yyyy-MM-dd")
            val inMin = o.optInt("inMin", -1)
            val outMin = o.optInt("outMin", -1)
            if (inMin !in 0..1439) {
                return ShiftResult.Err(
                    RemoteProtocol.E_BAD_REQUEST, "E_RANGE",
                    "第 ${i + 1} 条 inMin 必须是 0..1439（当天分钟数，09:00=540）"
                )
            }
            // 上限 2879 = 次日 23:59，足够表达任何跨天班；等于 inMin 说明时长为 0
            if (outMin !in 0..2879 || outMin == inMin) {
                return ShiftResult.Err(
                    RemoteProtocol.E_BAD_REQUEST, "E_RANGE",
                    "第 ${i + 1} 条 outMin 必须是 0..2879 且不等于 inMin（次日 05:00 = 1740）"
                )
            }
            items += DailyShift(
                date = date,
                inMin = inMin,
                outMin = outMin,
                source = o.optString("source").take(20),
                updatedAt = now()
            )
        }

        // pruneBefore：滚动同步时顺手裁掉过期历史，避免表无限增长。
        // 非法日期必须报错而不是静默忽略 —— 静默忽略会让用户的清理意图悄悄失效。
        var pruneBefore: String? = null
        if (body.has("pruneBefore")) {
            val raw = body.optString("pruneBefore")
            if (raw.isNotBlank()) {
                pruneBefore = RemoteProtocol.parseDate(raw) ?: return ShiftResult.Err(
                    RemoteProtocol.E_BAD_REQUEST, "E_DATE", "pruneBefore 必须是 yyyy-MM-dd"
                )
            }
        }

        runCatching { s.upsertAll(items) }.onFailure {
            return ShiftResult.Err(500, "E_DB", "写班表失败：${it.message}")
        }
        val pruned = pruneBefore?.let { runCatching { s.deleteBefore(it) }.getOrDefault(0) } ?: 0

        // 🔴 写后必须整体重排：已经排进系统的那批闹钟不会因为库里班表变了就自己变。
        // 漏掉这一步的表现是「接口 200、库里数据也对，但今天还是按旧时刻响」。
        runCatching { hook.rescheduleAll() }

        return ShiftResult.Ok(
            upserted = items.size,
            pruned = pruned,
            total = runCatching { s.all().size }.getOrDefault(0),
            latest = runCatching { s.latest() }.getOrNull()
        )
    }

    fun deleteShifts(fromKey: String?, toKey: String?, clearAll: Boolean): ShiftResult {
        val s = shiftStore
            ?: return ShiftResult.Err(RemoteProtocol.E_CONFLICT, "E_SHIFTS_OFF", "当前版本不支持班表同步")
        val removed = runCatching {
            when {
                clearAll -> s.clear()
                fromKey != null && toKey != null -> s.deleteRange(fromKey, toKey)
                fromKey != null -> s.deleteRange(fromKey, fromKey)
                else -> 0
            }
        }.getOrElse {
            return ShiftResult.Err(500, "E_DB", "删除班表失败：${it.message}")
        }
        if (removed > 0) runCatching { hook.rescheduleAll() }
        return ShiftResult.Ok(
            upserted = 0,
            pruned = removed,
            total = runCatching { s.all().size }.getOrDefault(0),
            latest = runCatching { s.latest() }.getOrNull()
        )
    }

    private fun shiftJson(s: DailyShift): JSONObject = JSONObject().apply {
        put("date", s.date)
        put("inMin", s.inMin)
        put("outMin", s.outMin)
        put("crossesDay", s.crossesDay())
        put("source", s.source)
        put("updatedAt", s.updatedAt)
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
                exceptions = ex,
                shifts = shiftsFor(a, cal)
            )
        }.getOrNull()
    }

    /**
     * 班表模式下读一次班表（远程请求线程，允许查库）。
     *
     * 固定时刻规则直接返回 null：让 Scheduler 走老路径，
     * 这样既有规则的排程结果与升级前逐字节一致。
     */
    private fun shiftsFor(a: Automation, day: Calendar): ShiftTable? {
        if (a.shiftAnchor == ShiftAnchor.FIXED) return null
        val s = shiftStore ?: return null
        return runCatching {
            ShiftTable.of(s.from(HolidayChecker.dateKey(day)))
        }.getOrNull()
    }

    private fun notFound(id: Long) =
        WriteResult.Err(RemoteProtocol.E_NOT_FOUND, "E_RULE_NOT_FOUND", "规则 $id 不存在")

    companion object {

        /** 单次最多下发多少天的班表。滚动同步一次通常几天到几周。 */
        const val MAX_SHIFT_BATCH = 366

        /** 「上班前 30 分钟」这样的人话，供界面与接口展示。 */
        fun shiftText(a: Automation): String =
            ShiftAnchor.describe(a.shiftAnchor, a.shiftOffsetMin)

        /** 某天班次按锚点换算后的具体时钟，例如「08:30」或「05:10（次日）」。 */
        fun shiftLabel(a: Automation, s: DailyShift): String {
            val base = if (a.shiftAnchor == ShiftAnchor.CLOCK_OUT) s.outMin else s.inMin
            var t = base + a.shiftOffsetMin
            var day = 0
            while (t < 0) { t += 1440; day -= 1 }
            while (t >= 1440) { t -= 1440; day += 1 }
            val hm = String.format(Locale.US, "%02d:%02d", t / 60, t % 60)
            return when (day) {
                0 -> hm
                1 -> "$hm（次日）"
                else -> "$hm（+${day}天）"
            }
        }

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
