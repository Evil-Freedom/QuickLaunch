package com.workbuddy.quicklaunch.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface RuleExceptionDao {

    /**
     * 一次性取全部豁免，由调用方按 ruleId 分组。
     *
     * 为什么不提供「按 ruleId 单查」作为排程主路径：
     * [com.workbuddy.quicklaunch.util.Scheduler.rescheduleAll] 会遍历所有规则，
     * 逐条查库就是 N+1；全表一次读入内存再分组，规则数量级（几十条）下代价可忽略。
     * 单条排程（[com.workbuddy.quicklaunch.util.Scheduler.schedule] 默认路径）才走单查。
     */
    @Query("SELECT * FROM rule_exceptions")
    fun getAll(): List<RuleException>

    @Query("SELECT date FROM rule_exceptions WHERE ruleId = :ruleId")
    fun getDatesByRule(ruleId: Long): List<String>

    @Query("SELECT * FROM rule_exceptions WHERE ruleId = :ruleId ORDER BY date")
    fun getByRule(ruleId: Long): List<RuleException>

    /** 幂等写入：同一 (ruleId, date) 重复下发只保留最后一条。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(e: RuleException)

    @Query("DELETE FROM rule_exceptions WHERE ruleId = :ruleId AND date = :date")
    fun delete(ruleId: Long, date: String)

    @Query("DELETE FROM rule_exceptions WHERE ruleId = :ruleId")
    fun deleteByRule(ruleId: Long)

    @Query("DELETE FROM rule_exceptions WHERE date < :beforeDate")
    fun deleteBefore(beforeDate: String)

    /**
     * 清理孤儿豁免：规则已被删除，但它名下的豁免还留在表里。
     *
     * 不建外键的原因：Room 外键要求被引用列有索引，且删除规则时若未级联会直接抛
     * SQLiteConstraintException，把「删除规则」这个高频操作变成可能失败的路径。
     * 这里用一次低频清理换取删除路径的绝对安全 —— 孤儿豁免本身不参与任何排程判定
     * （排程按 ruleId 取），留着只是占几十字节。
     */
    @Query("DELETE FROM rule_exceptions WHERE ruleId NOT IN (SELECT id FROM automations)")
    fun deleteOrphans(): Int

    @Query("SELECT COUNT(*) FROM rule_exceptions")
    fun count(): Int
}
