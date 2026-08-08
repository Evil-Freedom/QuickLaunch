package com.workbuddy.quicklaunch.util

import com.workbuddy.quicklaunch.data.Holiday
import org.json.JSONObject

/**
 * 节假日数据源管理
 *
 * 核心思路：
 * 每个数据接口的网址里都有一个叫「年份」的标记，
 * 程序运行时，会根据当前真实的年份（比如 2026）把这个标记替换掉，
 * 这样就能拿到那一年的节假日数据。
 *
 * 工作流程：
 *   1. 准备好网址模板（里面写着「年份」作为标记）
 *   2. 把「年份」替换成真实的年份 → 得到最终网址
 *   3. 用最终网址去请求数据 → 拿到原始 JSON
 *   4. 把 JSON 解析成节假日列表
 *
 * 多个数据源的设计：
 *   优先使用国内访问快的 timor.tech，
 *   如果连不上，就自动切换到 GitHub 或 jsDelivr 上的镜像数据，
 *   用户也可以自己添加其他数据源。
 */

/** 解析器类型：决定用哪种方式解析某个数据源返回的 JSON 数据。 */
enum class ParserType { TIMOR, NATE_SCARLET }

/**
 * 节假日数据源。
 *
 * 每个数据源包含：
 * - id：数据源的唯一标识（比如 "timor"）
 * - label：给用户看的名字（比如 "timor.tech"）
 * - urlTemplate：网址模板，里面用「年份」作为占位标记
 * - urlForYear：把「年份」替换成真实年份后得到的最终网址
 * - parse：解析 JSON 数据的方法
 * - builtIn：是否是内置数据源（用户自己添加的为 false）
 */
data class HolidaySource(
    val id: String,
    val label: String,
    val urlTemplate: String,        // 网址模板，里面包含「年份」标记
    val urlForYear: (Int) -> String, // 把「年份」替换成真实年份后的网址
    val parse: (String) -> List<Holiday>,
    val builtIn: Boolean = true
)

/**
 * 把网址模板里的「年份」标记替换成真实的年份。
 *
 * 注意：
 * - 只替换「年份」这个标记，不会碰其他标记
 * - 如果网址模板里没有「年份」标记，说明是固定网址，直接返回原样
 * - 年份必须是正数（大于 0），否则返回空字符串表示无效
 *
 * @param template 网址模板，比如 "https://timor.tech/api/holiday/year/【年份】"
 * @param targetYear 真实的年份，比如 2026
 * @return 替换后的最终网址，如果年份无效则返回空字符串
 */
fun resolveUrl(template: String, targetYear: Int): String {
    if (targetYear <= 0) return ""
    return template.replace("【年份】", targetYear.toString())
}

/**
 * 用户自己添加的数据源（会被保存到手机里）。
 *
 * @param id 数据源唯一标识
 * @param label 显示名称
 * @param urlTemplate 网址模板，里面包含「年份」标记
 * @param parser 用哪种解析器解析这个数据源的数据
 */
data class CustomSource(
    val id: String,
    val label: String,
    val urlTemplate: String, // 网址模板，里面包含「年份」标记
    val parser: ParserType
)

/**
 * 把用户自定义的数据源转成程序内部使用的 HolidaySource 对象。
 *
 * 转换时，会自动把网址模板里的「年份」标记绑定到替换逻辑上。
 */
fun CustomSource.toHolidaySource(): HolidaySource {
    val template = urlTemplate
    return HolidaySource(
        id = id,
        label = label,
        urlTemplate = template,
        urlForYear = { targetYear -> resolveUrl(template, targetYear) },
        parse = if (parser == ParserType.TIMOR) ::parseTimor else ::parseNateScarlet,
        builtIn = false
    )
}

object HolidaySources {

    // ═══════════════════════════════════════════════════════════════
    // 内置数据源的网址模板
    // 每个模板里的「年份」标记会在请求时被替换成真实的年份
    // ═══════════════════════════════════════════════════════════════

    private const val TIMOR_TEMPLATE =
        "https://timor.tech/api/holiday/year/【年份】"

    private const val NATESCARLET_RAW_TEMPLATE =
        "https://raw.githubusercontent.com/NateScarlet/holiday-cn/master/【年份】.json"

    private const val NATESCARLET_CDN_TEMPLATE =
        "https://cdn.jsdelivr.net/gh/NateScarlet/holiday-cn@master/【年份】.json"

    private val TIMOR = HolidaySource(
        id = "timor",
        label = "timor.tech",
        urlTemplate = TIMOR_TEMPLATE,
        urlForYear = { targetYear -> resolveUrl(TIMOR_TEMPLATE, targetYear) },
        parse = ::parseTimor
    )

    private val NATESCARLET_RAW = HolidaySource(
        id = "natescarlet_raw",
        label = "holiday-cn (GitHub)",
        urlTemplate = NATESCARLET_RAW_TEMPLATE,
        urlForYear = { targetYear -> resolveUrl(NATESCARLET_RAW_TEMPLATE, targetYear) },
        parse = ::parseNateScarlet
    )

    private val NATESCARLET_CDN = HolidaySource(
        id = "natescarlet_cdn",
        label = "holiday-cn (jsDelivr)",
        urlTemplate = NATESCARLET_CDN_TEMPLATE,
        urlForYear = { targetYear -> resolveUrl(NATESCARLET_CDN_TEMPLATE, targetYear) },
        parse = ::parseNateScarlet
    )

    /** 所有内置数据源（按默认顺序尝试）。 */
    val ALL: List<HolidaySource> = listOf(TIMOR, NATESCARLET_RAW, NATESCARLET_CDN)

    /**
     * 计算实际尝试顺序。
     *
     * 规则：
     * - 用户手动指定的数据源优先尝试
     * - 上次成功拉取过的数据源也优先尝试
     * - 剩下的按默认顺序排队
     * - 如果用户指定的和上次成功的是同一个，不会重复请求
     *
     * @param pref 用户偏好的数据源 id（null 表示没特别偏好）
     * @param lastGood 上次成功拉取的数据源 id（null 表示没有记录）
     * @param custom 用户自己添加的数据源列表
     * @return 按优先级排好序的数据源列表
     */
    fun ordered(
        pref: String?,
        lastGood: String?,
        custom: List<HolidaySource> = emptyList()
    ): List<HolidaySource> {
        // 用户添加的数据源可能和内置的 id 重复，先按 id 去重，避免同一个源请求两次
        val allSources = (ALL + custom).distinctBy { it.id }
        val preferredSource = allSources.firstOrNull { it.id == pref }
        val lastGoodSource = allSources.firstOrNull { it.id == lastGood }
        val remainingSources = allSources.filter { it !== preferredSource && it !== lastGoodSource }
        // 当用户偏好和上次成功的是同一个源时，listOfNotNull 会放两次，用 distinct 去掉重复的
        return (listOfNotNull(preferredSource, lastGoodSource) + remainingSources).distinctBy { it.id }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// 下面这些是 JSON 解析器的实现，和网址替换逻辑无关，保持不变
// ═══════════════════════════════════════════════════════════════════════

/** 严格校验日期格式 yyyy-MM-dd（包括月份和日期的合理范围），防止脏数据写入数据库。 */
private val DATE_RE = Regex("""^\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])$""")

private fun validDateOrNull(s: String?): String? {
    val v = s?.trim() ?: return null
    return if (DATE_RE.matches(v)) v else null
}

/**
 * 解析 timor.tech 返回的 JSON 数据。
 *
 * 判断规则：当 code 字段等于 0 时，holiday 对象里 holiday 等于 true 的项就是休息日。
 *
 * 注意：真实接口里的键通常是 "01-01"（月-日），完整日期在值对象的 date 字段里；
 * 少数镜像会把键写成完整的 yyyy-MM-dd，所以两种格式都兼容。
 *
 * 接口返回示例：
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
        // 优先使用对象内的 date 字段（完整日期），否则用键的值（兼容两种格式）
        val date = validDateOrNull(obj.optString("date", "")) ?: validDateOrNull(k) ?: continue
        out.add(Holiday(date = date, name = obj.optString("name", "")))
    }
    return out
}

/**
 * 解析 NateScarlet/holiday-cn 返回的 JSON 数据。
 *
 * 判断规则：days 数组里 isOffDay 等于 true 的日期就是休息日。
 *
 * 接口返回示例（JSON 对象，里面有个 days 数组）：
 * {
 *   "days": [
 *     { "date": "2026-01-01", "name": "元旦", "isOffDay": true },
 *     { "date": "2026-01-02", "name": "", "isOffDay": false }
 *   ]
 * }
 */
private fun parseNateScarlet(json: String): List<Holiday> {
    val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
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
