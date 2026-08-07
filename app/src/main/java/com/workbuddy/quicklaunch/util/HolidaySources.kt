package com.workbuddy.quicklaunch.util

import com.workbuddy.quicklaunch.data.Holiday
import org.json.JSONArray
import org.json.JSONObject

/** 解析器类型：决定如何解析某源返回的原始 JSON。 */
enum class ParserType { TIMOR, NATE_SCARLET }

/**
 * 节假日数据源。每个源提供「某年的原始 JSON URL」与对应的解析器，
 * 解析器只保留 isOffDay/holiday==true 的休息日（法定节假日 + 调休休息日），
 * 调休补班日一律不计入，避免把补班日也跳掉。
 *
 * 多源设计：境内优先 timor.tech；若失败回退到 NateScarlet/holiday-cn
 *（GitHub raw 与 jsDelivr CDN 两份镜像，互为兜底，提升国内可达性）。
 * 此外用户可在「管理数据源」里手动添加自己的源（见 [CustomSource]）。
 */
data class HolidaySource(
    val id: String,
    val label: String,
    val urlForYear: (Int) -> String,
    val parse: (String) -> List<Holiday>,
    val builtIn: Boolean = true
)

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
        urlForYear = { y -> tpl.replace("{year}", y.toString()) },
        parse = if (parser == ParserType.TIMOR) ::parseTimor else ::parseNateScarlet,
        builtIn = false
    )
}

object HolidaySources {

    private val TIMOR = HolidaySource(
        id = "timor",
        label = "timor.tech",
        urlForYear = { "https://timor.tech/api/holiday/year/$it" },
        parse = ::parseTimor
    )

    private val NATESCARLET_RAW = HolidaySource(
        id = "natescarlet_raw",
        label = "holiday-cn (GitHub)",
        urlForYear = { "https://raw.githubusercontent.com/NateScarlet/holiday-cn/master/$it.json" },
        parse = ::parseNateScarlet
    )

    private val NATESCARLET_CDN = HolidaySource(
        id = "natescarlet_cdn",
        label = "holiday-cn (jsDelivr)",
        urlForYear = { "https://cdn.jsdelivr.net/gh/NateScarlet/holiday-cn@master/$it.json" },
        parse = ::parseNateScarlet
    )

    /** 全部内置数据源（默认尝试顺序）。 */
    val ALL: List<HolidaySource> = listOf(TIMOR, NATESCARLET_RAW, NATESCARLET_CDN)

    /**
     * 计算实际尝试顺序：把用户偏好(pref)与上次成功源(lastGood)前置，
     * 其余按 ALL + 自定义源顺序跟随。两者为空则直接用 ALL + 自定义源。
     */
    fun ordered(pref: String?, lastGood: String?, custom: List<HolidaySource> = emptyList()): List<HolidaySource> {
        val all = ALL + custom
        val prefSrc = all.firstOrNull { it.id == pref }
        val lastSrc = all.firstOrNull { it.id == lastGood }
        val rest = all.filter { it != prefSrc && it != lastSrc }
        return listOfNotNull(prefSrc, lastSrc) + rest
    }
}

/** timor.tech：code==0 时 holiday 对象里 holiday==true 的键即为休息日日期。 */
private fun parseTimor(json: String): List<Holiday> {
    val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
    if (root.optInt("code", -1) != 0) return emptyList()
    val holiday = root.optJSONObject("holiday") ?: return emptyList()
    val out = mutableListOf<Holiday>()
    val keys = holiday.keys()
    while (keys.hasNext()) {
        val k = keys.next()
        val obj = holiday.optJSONObject(k) ?: continue
        if (obj.optBoolean("holiday", false)) {
            out.add(Holiday(date = k, name = obj.optString("name", "")))
        }
    }
    return out
}

/** NateScarlet/holiday-cn：days 数组里 isOffDay==true 的 date 为休息日。 */
private fun parseNateScarlet(json: String): List<Holiday> {
    val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
    val days = root.optJSONArray("days") ?: return emptyList()
    val out = mutableListOf<Holiday>()
    for (i in 0 until days.length()) {
        val obj = days.optJSONObject(i) ?: continue
        if (obj.optBoolean("isOffDay", false)) {
            out.add(Holiday(date = obj.optString("date", ""), name = obj.optString("name", "")))
        }
    }
    return out
}
