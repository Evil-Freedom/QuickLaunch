package com.workbuddy.quicklaunch.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 某一天的班次（来自飞书考勤同步）。
 *
 * 为什么单独建表而不是复用 [Automation] —— 班表是「全公司/全人共享的事实」，
 * 一条规则不应该各自存一份；同一天可能有多条规则都锚定同一个班次，
 * 存一份才能保证「班表更新一次，所有跟随的规则一起变」。
 *
 * ## 时间用「当天分钟数」而不是时间戳
 *
 * `inMin`/`outMin` 是**本地时区下的当天分钟数**（09:00 = 540）。
 * 存时间戳的话，用户改系统时区或跨时区出差时，同一个班次会漂移成不同时刻，
 * 而"9 点上班"是业务语义，不该随时区变。
 *
 * ## 跨天班
 *
 * `outMin >= 1440` 表示下班落在**次日**（例如 05:00 上班、次日 05:00 下班 → 300 / 1740）。
 * 不再单独存 crossDay 列：`outMin > 1439` 就能唯一推出跨天，多一列反而有两者不一致的风险。
 *
 * ## 无班次的日期
 *
 * 表里查不到某天 = 那天没排班 = 休息。不需要存 "isRest" 标记，
 * 缺失即休息这个约定让「公司还没排班」和「确实休息」在行为上一致（都不触发），
 * 这正是轮班用户想要的保守行为。
 */
@Entity(tableName = "daily_shifts")
data class DailyShift(
    /** yyyy-MM-dd，与 holidays.date / rule_exceptions.date 同格式 */
    @PrimaryKey val date: String,
    /** 上班时刻，当天分钟数 0..1439（09:00 = 540） */
    val inMin: Int,
    /** 下班时刻，当天分钟数，可 >= 1440 表示次日（跨天班） */
    val outMin: Int,
    /** 数据来源，如 "feishu"。仅用于展示与排查，不参与判定。 */
    val source: String = "",
    val updatedAt: Long = 0L
) {
    /** 下班是否落在次日。用函数而不是 val：避免 Room 把它当成需要映射的列。 */
    fun crossesDay(): Boolean = outMin >= 1440

    /** 班次时长（分钟）。跨天时 outMin 已经累加过 1440，直接相减即可。 */
    fun durationMinutes(): Int = outMin - inMin
}
