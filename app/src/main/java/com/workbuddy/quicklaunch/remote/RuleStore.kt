package com.workbuddy.quicklaunch.remote

import com.workbuddy.quicklaunch.data.Automation
import com.workbuddy.quicklaunch.data.RuleException

/**
 * 规则读写的最小抽象。
 *
 * 存在的唯一理由是**可测性**：远程写入的执行序（留档 → 合并 → 取消旧闹钟 →
 * 重排新闹钟）是最容易出错的地方，而项目现有测试全是纯 JVM（无 Robolectric、
 * 无 Context）。抽掉 Room 之后，这段执行序就能用假实现精确断言调用顺序。
 *
 * 这不是「以后可能换实现」的投机抽象 —— 它现在就只有一个生产实现。
 */
interface RuleStore {
    fun getAll(): List<Automation>
    fun getById(id: Long): Automation?
    fun update(a: Automation)

    fun exceptionsOf(ruleId: Long): List<RuleException>
    fun addException(e: RuleException)
    fun removeException(ruleId: Long, date: String)
}

/**
 * 排程的最小抽象。生产实现直接转调 [com.workbuddy.quicklaunch.util.Scheduler]。
 *
 * ⚠️ 契约：`schedule` 必须使用传入的 exceptions 集合，而不是自己再去查库 ——
 * 否则「远程加了豁免 → 立刻重排」这条链路上会出现「刚写进去的豁免还没被读到」的竞态。
 */
interface ScheduleHook {
    fun cancel(a: Automation)
    fun schedule(a: Automation, exceptions: Set<String>)
}
