package com.workbuddy.quicklaunch.util

import android.content.Context
import com.workbuddy.quicklaunch.data.AppDatabase
import com.workbuddy.quicklaunch.data.DailyShift

/**
 * 内存里的班表快照，供排程一次性查多天使用。
 *
 * 存在的理由是**避免 N+1 与主线程查库**：
 * - 排程一条规则可能要逐日扫描最多 60 天，逐天查 Room 会变成 60 次查询；
 * - 界面上的「下次触发」在主线程计算，绝不能在主线程碰 Room。
 *
 * 所以约定：**查库只发生在调用链的起点**（[fromDb]），
 * 之后一路传对象。查库失败返回 [EMPTY]，让规则退化为固定时刻而不是整体失效。
 */
class ShiftTable private constructor(private val byDate: Map<String, DailyShift>) {

    /** 查某天班次；返回 null = 那天没排班 = 休息。 */
    fun of(dateKey: String): DailyShift? = byDate[dateKey]

    fun isEmpty(): Boolean = byDate.isEmpty()

    fun size(): Int = byDate.size

    /** 班表覆盖到的最晚一天，用于界面提示「同步到哪天」。 */
    fun latestDate(): String? = byDate.keys.maxOrNull()

    companion object {

        val EMPTY = ShiftTable(emptyMap())

        fun of(shifts: List<DailyShift>): ShiftTable =
            if (shifts.isEmpty()) EMPTY else ShiftTable(shifts.associateBy { it.date })

        /**
         * 读库。排程只需要未来，所以默认从 [fromDateKey] 起读 ——
         * 历史班表对「下次什么时候触发」没有意义，读进来只是浪费内存。
         *
         * @param fromDateKey null 表示全量读（供界面展示/导出用）
         */
        fun fromDb(context: Context, fromDateKey: String? = null): ShiftTable = runCatching {
            val dao = AppDatabase.get(context).dailyShiftDao()
            val list = if (fromDateKey == null) dao.getAll() else dao.getFrom(fromDateKey)
            of(list)
        }.getOrDefault(EMPTY)
    }
}
