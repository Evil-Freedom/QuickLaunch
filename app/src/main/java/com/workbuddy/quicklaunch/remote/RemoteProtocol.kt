package com.workbuddy.quicklaunch.remote

import com.workbuddy.quicklaunch.data.Automation
import com.workbuddy.quicklaunch.data.RepeatMode
import com.workbuddy.quicklaunch.data.ShiftAnchor
import org.json.JSONObject

/**
 * 远程改期的线协议：字段白名单、取值校验、与 [Automation] 的合并。
 *
 * 设计约束（每一条都有明确的失败后果）：
 *
 * 1. **只允许改排程字段**。`targetPackage` / `triggerType` / `createdAt` / `id` 一律拒绝。
 *    这样即使密钥泄露，攻击者能做的上限是「改某条规则几点启动」，
 *    而不是「让这台手机在指定时间拉起任意应用」——后者是远程执行原语的入口。
 *
 * 2. **枚举字面量必须用常量**。`repeatMode` 全小写、`triggerType` 全大写，
 *    写反时 `when` 匹配不到会**静默不做过滤**，表现为「排程算错」而代码看着没错。
 *
 * 3. **合并口径必须和本地编辑一致**：非 custom 模式 `repeatDays` 归 0，
 *    非随机窗口 `windowStartMin/windowEndMin` 归 0。
 *    否则同一条规则「本地改完」和「远程改完」在库里是两种形态，
 *    后续任何依赖这些字段的判定都会出现只在某条路径下复现的 bug。
 */
object RemoteProtocol {

    const val VERSION = "1"

    /** 协议层错误码：直接当 HTTP 状态码用，便于日志排查。 */
    const val E_BAD_REQUEST = 400
    const val E_FORBIDDEN_FIELD = 409
    const val E_NOT_FOUND = 404
    const val E_CONFLICT = 409
    const val E_BODY_TOO_LARGE = 413

    /** 允许远程修改的字段。不在此集合内的一律 409。 */
    val EDITABLE_FIELDS = setOf(
        "timeHour", "timeMinute", "repeatMode", "repeatDays",
        "skipHolidays", "randomWindow", "windowStartMin", "windowEndMin", "enabled",
        "shiftAnchor", "shiftOffsetMin"
    )

    /** `repeatMode` 的合法取值（全小写）。 */
    private val VALID_REPEAT_MODES = setOf(
        RepeatMode.DAILY, RepeatMode.WEEKDAYS, RepeatMode.WEEKEND,
        RepeatMode.CUSTOM, RepeatMode.ONCE
    )

    sealed class Result {
        data class Ok(val automation: Automation) : Result()
        data class Err(val code: Int, val error: String, val detail: String) : Result()
    }

    /**
     * 把 PATCH 报文合并进 [old]。
     *
     * 成功时返回的 [Automation] 已经过规范化（非 custom 归零位图等），
     * 但 **`enabled` 与 `createdAt` 的保留策略不在这里决定** ——
     * 那属于写入链路（见 RemoteRuleApi），因为「远程改」和「远程启用/停用」是两件事：
     * 报文里没给 enabled 就必须沿用旧值，不能取 data class 的默认值 true。
     */
    fun mergePatch(old: Automation, body: JSONObject): Result {
        // ── 第一步：字段白名单。先查再校验值，越权字段不会被"顺手校验通过" ──
        val forbidden = body.keys().asSequence().filter { it !in EDITABLE_FIELDS }.toList()
        if (forbidden.isNotEmpty()) {
            return Result.Err(
                E_FORBIDDEN_FIELD,
                "E_FORBIDDEN_FIELD",
                "以下字段不允许远程修改：${forbidden.sorted().joinToString(",")}"
            )
        }

        val hour = intOf(body, "timeHour", old.timeHour) ?: return rangeErr("timeHour", 0, 23)
        val minute = intOf(body, "timeMinute", old.timeMinute) ?: return rangeErr("timeMinute", 0, 59)
        if (hour !in 0..23) return rangeErr("timeHour", 0, 23)
        if (minute !in 0..59) return rangeErr("timeMinute", 0, 59)

        val repeatMode = if (body.has("repeatMode")) {
            val raw = body.opt("repeatMode")
            if (raw !is String || raw !in VALID_REPEAT_MODES) {
                return Result.Err(
                    E_BAD_REQUEST, "E_ENUM",
                    "repeatMode 只接受 ${VALID_REPEAT_MODES.sorted().joinToString("/")}（全小写）"
                )
            }
            raw
        } else old.repeatMode

        // ── 第二步：位图。非 custom 一律归 0，与本地保存口径对齐 ──
        val rawMask = intOf(body, "repeatDays", old.repeatDays)
            ?: return rangeErr("repeatDays", 1, 127)
        val mask = if (repeatMode == RepeatMode.CUSTOM) {
            val cleaned = rawMask and 0x7F          // 只保留 bit0..bit6（周日~周六）
            if (cleaned == 0) {
                return Result.Err(
                    E_BAD_REQUEST, "E_MASK",
                    "repeatMode=custom 时 repeatDays 至少勾选一天（1..127）"
                )
            }
            cleaned
        } else {
            0
        }

        val randomWindow = boolOf(body, "randomWindow", old.randomWindow)
            ?: return typeErr("randomWindow")
        val rawStart = intOf(body, "windowStartMin", old.windowStartMin)
            ?: return rangeErr("windowStartMin", 0, 1439)
        val rawEnd = intOf(body, "windowEndMin", old.windowEndMin)
            ?: return rangeErr("windowEndMin", 0, 1439)
        if (rawStart !in 0..1439) return rangeErr("windowStartMin", 0, 1439)
        if (rawEnd !in 0..1439) return rangeErr("windowEndMin", 0, 1439)

        // 非随机窗口时归零；随机窗口时要求区间非空且有序（乱序就交换，与本地 save 一致）
        val (start, end) = if (randomWindow) {
            val s = minOf(rawStart, rawEnd)
            val e = maxOf(rawStart, rawEnd)
            if (s == e) {
                return Result.Err(
                    E_BAD_REQUEST, "E_WINDOW",
                    "随机窗口的起止时间不能相同，否则等于固定时刻"
                )
            }
            s to e
        } else {
            0 to 0
        }

        val skipHolidays = boolOf(body, "skipHolidays", old.skipHolidays)
            ?: return typeErr("skipHolidays")
        val enabled = boolOf(body, "enabled", old.enabled)
            ?: return typeErr("enabled")

        // 班表锚定：0=固定时刻 / 1=上班 / 2=下班。
        // 偏移限制在 ±720 分钟（半天），再大就不像"提前/延后"而像换了一天。
        val shiftAnchor = intOf(body, "shiftAnchor", old.shiftAnchor)
            ?: return typeErr("shiftAnchor")
        if (!ShiftAnchor.isValid(shiftAnchor)) return rangeErr("shiftAnchor", 0, 2)
        val shiftOffsetMin = intOf(body, "shiftOffsetMin", old.shiftOffsetMin)
            ?: return typeErr("shiftOffsetMin")
        if (shiftOffsetMin !in -720..720) return rangeErr("shiftOffsetMin", -720, 720)

        return Result.Ok(
            old.copy(
                timeHour = hour,
                timeMinute = minute,
                repeatMode = repeatMode,
                repeatDays = mask,
                skipHolidays = skipHolidays,
                randomWindow = randomWindow,
                windowStartMin = start,
                windowEndMin = end,
                enabled = enabled,
                shiftAnchor = shiftAnchor,
                shiftOffsetMin = shiftOffsetMin
            )
        )
    }

    /**
     * 单日豁免的日期校验。
     *
     * 只接受 `yyyy-MM-dd` 且必须是**真实存在的日期**（拒绝 2026-02-30）。
     * 用 `Calendar` 反查而不是正则：正则能过 2 月 30 号，而那种日期在
     * `HolidayChecker.dateKey` 比对里永远匹配不上，豁免会静默失效。
     */
    fun parseDate(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        if (!Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(raw)) return null
        val (y, m, d) = raw.split("-").map { it.toIntOrNull() ?: return null }
        if (m !in 1..12 || d !in 1..31) return null
        val cal = java.util.Calendar.getInstance().apply {
            clear()
            set(y, m - 1, d)
        }
        // 溢出会被 Calendar 归一化（2 月 30 → 3 月 2），比对回来就能发现
        if (cal.get(java.util.Calendar.YEAR) != y) return null
        if (cal.get(java.util.Calendar.MONTH) + 1 != m) return null
        if (cal.get(java.util.Calendar.DAY_OF_MONTH) != d) return null
        return raw
    }

    fun errorJson(code: Int, error: String, detail: String): JSONObject = JSONObject().apply {
        put("ok", false)
        put("error", error)
        put("detail", detail)
        put("code", code)
    }

    // ── 取值辅助：类型不符一律返回 null，由调用方转成明确的错误码 ──

    private fun intOf(o: JSONObject, key: String, def: Int): Int? {
        if (!o.has(key)) return def
        val v = o.opt(key) ?: return null
        return when (v) {
            is Int -> v
            is Long -> if (v in Int.MIN_VALUE..Int.MAX_VALUE) v.toInt() else null
            is Double -> if (v == Math.floor(v) && !v.isInfinite()) v.toInt() else null
            else -> null
        }
    }

    private fun boolOf(o: JSONObject, key: String, def: Boolean): Boolean? {
        if (!o.has(key)) return def
        val v = o.opt(key) ?: return null
        return v as? Boolean
    }

    private fun rangeErr(field: String, min: Int, max: Int) =
        Result.Err(E_BAD_REQUEST, "E_RANGE", "$field 必须是 $min..$max 之间的整数")

    private fun typeErr(field: String) =
        Result.Err(E_BAD_REQUEST, "E_TYPE", "$field 类型不正确")
}
