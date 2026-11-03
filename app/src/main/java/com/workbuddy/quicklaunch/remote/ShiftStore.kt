package com.workbuddy.quicklaunch.remote

import com.workbuddy.quicklaunch.data.DailyShift

/**
 * 班表读写的最小抽象，与 [RuleStore] 同样的理由存在：
 * 让 `remote` 包里的业务逻辑保持纯 JVM 可测（无 Context / 无 Room）。
 *
 * 与 [RuleStore] 分开定义而不是合并成一个「大仓储」接口，是因为两者的
 * 使用方不同 —— 规则接口在没有班表能力的旧调用链上也要能工作，
 * 合并后所有既有假实现都要跟着补空方法。
 */
interface ShiftStore {

    /** 全量读取（界面/导出用）。 */
    fun all(): List<DailyShift>

    /** 读取 date >= [dateKey] 的班次（排程只需要未来）。 */
    fun from(dateKey: String): List<DailyShift>

    /** 批量写入，同一天覆盖（幂等）。 */
    fun upsertAll(items: List<DailyShift>)

    /** 删除比 [dateKey] 更早的班次，返回删除行数。 */
    fun deleteBefore(dateKey: String): Int

    /** 删除 [fromKey]..[toKey] 闭区间，返回删除行数。 */
    fun deleteRange(fromKey: String, toKey: String): Int

    fun clear(): Int

    /** 已同步到的最晚日期，null 表示表为空。 */
    fun latest(): String?
}
