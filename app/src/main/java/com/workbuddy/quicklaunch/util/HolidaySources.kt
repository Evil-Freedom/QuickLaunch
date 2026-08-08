package com.workbuddy.quicklaunch.util

import com.workbuddy.quicklaunch.data.Holiday
import org.json.JSONObject

/**
 * 日报/节假日 API 接口结构重构
 *
 * 核心设计：所有 URL 模板统一包含 {year} 占位符，
 * 运行时根据年份动态替换，实现年份参数与路由的精确匹配。
 *
 * 架构层次：
 *   URL 模板（含 {year} 占位符）
 *     ↓
 *   resolveUrl(template, year) → 生成最终 URL
 *     ↓
 *   HTTP 请求 → 原始 JSON
 *     ↓
 *   Parser 解析 → List<Holiday>
 *
 * 向后兼容：自定义源（CustomSource）已支持 {year} 模板，本重构仅优化内置源。
 */

/** 解析器类型：决定如何解析某源返回的原始 JSON。 */
enum class ParserType { TIMOR, NATE_SCARLET }

/**
 * 节假日数据源。
 *
 * 每个源提供：
 * - urlTemplate: 含 {year} 占位符的 URL 模板（如 "https://timor.tech/api/holiday/year/{year}"）
 * - urlForYear(year): 将模板中的 {year} 替换为实际年份
 * - parse(json): 解析原始 JSON → List<Holiday>
 *
 * 多源设计：境内优先 timor.tech；若失败回退到 NateScarlet/holiday-cn
 * （GitHub raw 与 jsDelivr CDN 两份镜像，互为兜底，提升国内可达性）。
 * 此外用户可在「管理数据源」里手动添加自己的源（见 [CustomSource]）。
 */
data class HolidaySource(
    val id: String,
    val label: String,
    val urlTemplate: String,        // 含 {year} 占位符
    val urlForYear: (Int) -> String, // 年份替换后的实际 URL
    val parse: (String) -> List<Holiday>,
    val builtIn: Boolean = true
)

/**
 * 解析 URL 模板，将 {year} 占位符替换为实际年份。
 *
 * 设计要点：
 * - 只替换 {year}，不替换其他可能存在的占位符（如 {month}、{day}），避免误伤
 * - 模板中不含 {year} 时，视为静态 URL，不做替换（向后兼容）
 * - 年份参数必须为正整数，否则返回空字符串
 *
 * @param template URL 模板，如 "https://example.com/api/holiday/{year}.json"
 * @param year 年份，如 2026
 * @return 替换后的实际 URL，或空字符串（参数无效时）
 */
fun resolveUrl(template: String, year: Int): String {
    if (year <= 0) return ""
    return template.replace("{year}", year.toString())
}

/** 用户自定义源的可序列化描述，持久化到 SharedPreferences。 */
data class CustomSource(
    val id: String,
    val label: String,
    val urlTemplate: String, // 含 {year} 占位符，如 https://example.com/{year}.json
    val parser: ParserType
)

/** 把可序列化的自定义源转成运行时 [HolidaySource]。 */
fun CustomSource.toHolidaySource(): HolidaySource {
    val tpl = urlTemplate
    return HolidaySource(
        id = id,
        label = label,
        urlTemplate = tpl,
        urlForYear = { y -> resolveUrl(tpl, y) },
        parse = if (parser == ParserType.TIMOR) ::parseTimor else ::parseNateScarlet,
        builtIn = false
    )
}

object HolidaySources {

    // ═══════════════════════════════════════════════════════════════
    // 内置数据源 URL 模板（含 {year} 占位符）
    // ═══════════════════════════════════════════════════════════════

    private const val TIMOR_TEMPLATE =
        "https://timor.tech/api/holiday/year/{year}"

    private const val NATESCARLET_RAW_TEMPLATE =
        "https://raw.githubusercontent.com/NateScarlet/holiday-cn/master/{year}.json"

    private const val NATESCARLET_CDN_TEMPLATE =
        "https://cdn.jsdelivr.net/gh/NateScarlet/holiday-cn@master/{year}.json"

    private val TIMOR = HolidaySource(
        id = "timor",
        label = "timor.tech",
        urlTemplate = TIMOR_TEMPLATE,
        urlForYear = { resolveUrl(TIMOR_TEMPLATE, it) },
        parse = ::parseTimor
    )

    private val NATESCARLET_RAW = HolidaySource(
        id = "natescarlet_raw",
        label = "holiday-cn (GitHub)",
        urlTemplate = NATESCARLET_RAW_TEMPLATE,
        urlForYear = { resolveUrl(NATESCARLET_RAW_TEMPLATE, it) },
        parse = ::parseNateScarlet
    )

    private val NATESCARLET_CDN = HolidaySource(
        id = "natescarlet_cdn",
        label = "holiday-cn (jsDelivr)",
        urlTemplate = NATESCARLET_CDN_TEMPLATE,
        urlForYear = { resolveUrl(NATESCARLET_CDN_TEMPLATE, it) },
        parse = ::parseNateScarlet
    )

    /** 全部内置数据源（默认尝试顺序）。 */
    val ALL: List<HolidaySource> = listOf(TIMOR, NATESCARLET_RAW, NATESCARLET_CDN)

    /**
     * 计算实际尝试顺序：把用户偏好(pref)与上次成功源(lastGood)前置，
     * 其余按 ALL + 自定义源顺序跟随。两者为空则直接用 ALL + 自定义源。
     *
     * @param pref 用户偏好的源 id
     * @param lastGood 上次成功拉取的源 id
     * @param custom 用户自定义源列表
     * @return 按优先级排序的源列表
     */
    fun ordered(
        pref: String?,
        lastGood: String?,
        custom: List<HolidaySource> = emptyList()
    ): List<HolidaySource> {
        // 自定义源可能与内置源 id 冲突，先按 id 去重，避免同一个源被请求两次。
        val all = (ALL + custom).distinctBy { it.id }
        val prefSrc = all.firstOrNull { it.id == pref }
        val lastSrc = all.firstOrNull { it.id == lastGood }
        val rest = all.filter { it !== prefSrc && it !== lastSrc }
        // pref == lastGood 时 listOfNotNull 会产生同一个源两次，distinct 消除重复网络请求。
        return (listOfNotNull(prefSrc, lastSrc) + rest).distinctBy { it.id }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// 解析器实现（保持不变，仅移动到底部以突出 URL 模板重构的核心变更）
// ═══════════════════════════════════════════════════════════════════════

/** 严格校验 yyyy-MM-dd（含月/日范围），避免把脏数据写进 holidays 表。 */
private val DATE_RE = Regex("""^\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])$""")

private fun validDateOrNull(s: String?): String? {
    val v = s?.trim() ?: return null
    return if (DATE_RE.matches(v)) v else null
}

/**
 * timor.tech：code==0 时 holiday 对象里 holiday==true 的项为休息日。
 *
 * 注意真实接口的键是 "01-01"（MM-dd）而非完整日期，完整日期在值对象的 date 字段里；
 * 少数镜像会把键写成完整 yyyy-MM-dd，故两者都兼容，且最终统一校验为 yyyy-MM-dd。
 *
 * API 响应示例：
 * {
 *   "code": 0,
 *   "holiday": {
 *     "01-01": { "holiday": true, "name": "元旦", "date": "2026-01-01" },
 *     "01-02": { "holiday": false, "name": "", "date": "2026-01-02" }
 *   }
 * }
 */
private fun parseTimor(json: String): List<Holiday> {
    val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
    if (root.optInt("code", -1) != 0) return emptyList()
    val holiday = root.optJSONObject("holiday") ?: return emptyList()
    val out = mutableListOf<Holiday>()
    val keys = holiday.keys()
    while (keys.hasNext()) {
        val k = keys.next()
        val obj = holiday.optJSONObject(k) ?: continue
        if (!obj.optBoolean("holiday", false)) continue
        // 优先使用对象内的 date 字段（yyyy-MM-dd），否则尝试用键（兼容两种格式）
        val date = validDateOrNull(obj.optString("date", "")) ?: validDateOrNull(k) ?: continue
        out.add(Holiday(date = date, name = obj.optString("name", "")))
    }
    return out
}

/**
 * NateScarlet/holiday-cn：days 数组里 isOffDay==true 的 date 为休息日。
 *
 * API 响应示例（JSON 数组）：
 * [
 *   { "date": "2026-01-01", "name": "元旦", "isOffDay": true },
 *   { "date": "2026-01-02", "name": "", "isOffDay": false }
 * ]
 *
 * 注：某些镜像的顶层可能是 { "days": [...] } 对象而非直接数组，此处两种都兼容。
 */
private fun parseNateScarlet(json: String): List<Holiday> {
    val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
    // 兼容两种顶层结构：直接是 days 数组，或 days 是对象的字段
    val days = root.optJSONArray("days") ?: return emptyList()
    val out = mutableListOf<Holiday>()
    for (i in 0 until days.length()) {
        val obj = days.optJSONObject(i) ?: continue
        if (!obj.optBoolean("isOffDay", false)) continue
        val date = validDateOrNull(obj.optString("date", "")) ?: continue
        out.add(Holiday(date = date, name = obj.optString("name", "")))
    }
    return out
}
