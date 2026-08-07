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

    fun getSourcePref(ctx: Context): String =
        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).getString(KEY_PREF, "auto") ?: "auto"

    fun setSourcePref(ctx: Context, pref: String) =
        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).edit().putString(KEY_PREF, pref).apply()

    fun getLastGood(ctx: Context): String? =
        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).getString(KEY_LAST, null)

    fun setLastGood(ctx: Context, id: String) =
        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).edit().putString(KEY_LAST, id).apply()

    fun getCustomSources(ctx: Context): List<CustomSource> {
        val raw = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).getString(KEY_CUSTOM, null)
            ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val parser = if (o.optString("parser", "natescarlet") == "timor") {
                    ParserType.TIMOR
                } else {
                    ParserType.NATE_SCARLET
                }
                CustomSource(
                    id = o.optString("id"),
                    label = o.optString("label"),
                    urlTemplate = o.optString("urlTemplate"),
                    parser = parser
                )
            }
        }.getOrDefault(emptyList())
    }

    fun setCustomSources(ctx: Context, list: List<CustomSource>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("id", it.id)
                put("label", it.label)
                put("urlTemplate", it.urlTemplate)
                put("parser", it.parser.name.lowercase())
            })
        }
        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).edit().putString(KEY_CUSTOM, arr.toString()).apply()
    }
}
