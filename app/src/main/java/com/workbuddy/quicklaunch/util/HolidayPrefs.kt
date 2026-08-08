package com.workbuddy.quicklaunch.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 节假日同步相关的偏好持久化（与 HolidaySync 共用 quicklaunch SP）。
 * 记录：当前选择的数据源、上次成功源、以及用户手动添加的自定义源列表。
 */
object HolidayPrefs {
    private const val SP = "quicklaunch"
    private const val KEY_PREF = "holiday_source_pref"
    private const val KEY_LAST = "holiday_source"
    private const val KEY_CUSTOM = "holiday_custom_sources"

    // 解析器类型名称常量（存到 JSON 里的字符串）
    private const val PARSER_TIMOR = "timor"
    private const val PARSER_NATESCARLET = "natescarlet"

    fun getSourcePref(context: Context): String =
        context.getSharedPreferences(SP, Context.MODE_PRIVATE).getString(KEY_PREF, "auto") ?: "auto"

    fun setSourcePref(context: Context, pref: String) =
        context.getSharedPreferences(SP, Context.MODE_PRIVATE).edit().putString(KEY_PREF, pref).apply()

    fun getLastGood(context: Context): String? =
        context.getSharedPreferences(SP, Context.MODE_PRIVATE).getString(KEY_LAST, null)

    fun setLastGood(context: Context, id: String) =
        context.getSharedPreferences(SP, Context.MODE_PRIVATE).edit().putString(KEY_LAST, id).apply()

    fun getCustomSources(context: Context): List<CustomSource> {
        val raw = context.getSharedPreferences(SP, Context.MODE_PRIVATE).getString(KEY_CUSTOM, null)
            ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val sourceJson = arr.optJSONObject(i) ?: return@mapNotNull null
                val parserName = sourceJson.optString("parser", PARSER_NATESCARLET)
                val parser = if (parserName == PARSER_TIMOR) ParserType.TIMOR else ParserType.NATE_SCARLET
                CustomSource(
                    id = sourceJson.optString("id"),
                    label = sourceJson.optString("label"),
                    urlTemplate = sourceJson.optString("urlTemplate"),
                    parser = parser
                )
            }
        }.getOrDefault(emptyList())
    }

    fun setCustomSources(context: Context, list: List<CustomSource>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("id", it.id)
                put("label", it.label)
                put("urlTemplate", it.urlTemplate)
                put("parser", it.parser.name.lowercase())
            })
        }
        context.getSharedPreferences(SP, Context.MODE_PRIVATE).edit().putString(KEY_CUSTOM, arr.toString()).apply()
    }
}
