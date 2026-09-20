package com.workbuddy.quicklaunch.util

import android.content.Context
import com.workbuddy.quicklaunch.data.Automation
import org.json.JSONObject

/**
 * [Automation] ↔ JSON 编解码。
 *
 * 用途：编辑规则前把旧版本留档，改错了能原样还原。
 *
 * 为什么不新建一张 Room 表：
 * 加表就要写 `Migration(5, 6)`，而迁移本身正是「数据丢失」的最大风险源 ——
 * 为了一个撤销功能去动全库结构不划算。规则字段全是 String/Long/Int/Boolean，
 * 塞进 SharedPreferences 足够，且完全不碰数据库。
 *
 * ⚠️ 维护要求：给 [Automation] 增删字段时**必须**同步改这里。
 * `RuleEditingTest.留档 key 集合与实体字段集合完全一致` 会兜住这个疏漏 ——
 * 少写一个字段的后果是「编辑一次后该字段被静默重置为默认值」，属于用户数据丢失。
 */
object AutomationCodec {

    /** 必填字段。缺任何一个都视为无效留档，直接返回 null 让调用方降级。 */
    private val REQUIRED = listOf("name", "targetPackage", "targetAppName", "triggerType")

    fun encode(a: Automation): String = JSONObject().apply {
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
        put("bluetoothName", a.bluetoothName)
        put("wifiName", a.wifiName)
        put("enabled", a.enabled)
        put("createdAt", a.createdAt)
        put("randomWindow", a.randomWindow)
        put("windowStartMin", a.windowStartMin)
        put("windowEndMin", a.windowEndMin)
    }.toString()

    /**
     * 解析留档。任何异常（空串、非 JSON、字段缺失、类型不符）一律返回 null。
     * 留档只是「撤销」的辅助数据，读不出来顶多是不能撤销，绝不能让编辑流程崩掉。
     */
    fun decode(json: String?): Automation? {
        if (json.isNullOrBlank() || !json.trimStart().startsWith("{")) return null
        return runCatching {
            val o = JSONObject(json)
            if (REQUIRED.any { !o.has(it) }) return null
            Automation(
                id = o.optLong("id", 0L),
                name = o.getString("name"),
                targetPackage = o.getString("targetPackage"),
                targetAppName = o.getString("targetAppName"),
                triggerType = o.getString("triggerType"),
                timeHour = o.optInt("timeHour", 0),
                timeMinute = o.optInt("timeMinute", 0),
                repeatMode = o.optString("repeatMode", "daily"),
                repeatDays = o.optInt("repeatDays", 0),
                skipHolidays = o.optBoolean("skipHolidays", false),
                bluetoothName = o.optString("bluetoothName", ""),
                wifiName = o.optString("wifiName", ""),
                enabled = o.optBoolean("enabled", true),
                createdAt = o.optLong("createdAt", 0L),
                randomWindow = o.optBoolean("randomWindow", false),
                windowStartMin = o.optInt("windowStartMin", 0),
                windowEndMin = o.optInt("windowEndMin", 0)
            )
        }.getOrNull()
    }
}

/**
 * 规则修改前的留档仓库（每个规则保留「上一次修改前」的一份快照）。
 *
 * 语义：不是历史版本库，只留一份，再次编辑时覆盖。
 * 目标是「手滑改错了能一键还原」，不是完整的版本管理（YAGNI）。
 */
object RuleBackupStore {

    private const val SP = "quicklaunch_rule_backup"

    private fun key(id: Long) = "rule_$id"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(SP, Context.MODE_PRIVATE)

    /** 记录规则修改前的快照。同一规则反复编辑只保留最新一份旧值。 */
    fun save(context: Context, a: Automation) {
        runCatching {
            prefs(context).edit().putString(key(a.id), AutomationCodec.encode(a)).apply()
        }
    }

    /** 只读留档，不删除。用于判断「这条规则是否有可还原的旧版本」。 */
    fun peek(context: Context, id: Long): Automation? = runCatching {
        AutomationCodec.decode(prefs(context).getString(key(id), null))
    }.getOrNull()

    fun has(context: Context, id: Long): Boolean = peek(context, id) != null

    /** 读取并消费留档（还原后旧值即失效，避免反复还原到同一个陈旧状态）。 */
    fun take(context: Context, id: Long): Automation? {
        val snapshot = peek(context, id) ?: return null
        clear(context, id)
        return snapshot
    }

    fun clear(context: Context, id: Long) {
        runCatching { prefs(context).edit().remove(key(id)).apply() }
    }
}
