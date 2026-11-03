package com.workbuddy.quicklaunch.data

import androidx.room.Entity

/**
 * 单日豁免：让某条规则在**某一个具体日期**不启动。
 *
 * 为什么需要它 —— 现有模型表达不了「临时请假」：
 * [Automation.repeatDays] 是「每周重复」的位图，勾掉周三等于**永久**取消所有周三；
 * skipHolidays 跳过的是法定节假日，管不到普通工作日。
 * 「11 月 4 日这天别启动，下周三照旧」只能靠这张表。
 *
 * 复合主键 (ruleId, date)：
 * 同一条规则在同一天只会有一条豁免，重复下发自然是幂等的（REPLACE 覆盖），
 * 不需要在业务层做存在性判断。
 *
 * date 固定 yyyy-MM-dd（Locale.US 格式化）。不用 Long 时间戳，原因：
 * 排程判定拿到的就是 [com.workbuddy.quicklaunch.util.HolidayChecker.dateKey] 的字符串，
 * 存成同格式可以直接集合比对，省掉每天一次时区换算，也不会因为时区变化把「4 号」变成「3 号」。
 */
@Entity(tableName = "rule_exceptions", primaryKeys = ["ruleId", "date"])
data class RuleException(
    val ruleId: Long,
    /** yyyy-MM-dd，与 holidays.date 同格式，可直接与 HolidayChecker.dateKey 比对 */
    val date: String,
    /** 豁免原因，例如「请假」。仅用于展示，不参与任何判定。 */
    val reason: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
