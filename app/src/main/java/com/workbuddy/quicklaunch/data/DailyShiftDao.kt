package com.workbuddy.quicklaunch.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * 班表读写。
 *
 * 所有写入都用 REPLACE：同一天重复下发是常态（脚本滚动同步），
 * 幂等写入可以让同步链路不必先查再改，也就不会有「查到了但写入前被删」的竞态。
 */
@Dao
interface DailyShiftDao {

    @Query("SELECT * FROM daily_shifts ORDER BY date")
    fun getAll(): List<DailyShift>

    /** 只取 date >= from 的班次。排程只需要未来，历史班表没必要进内存。 */
    @Query("SELECT * FROM daily_shifts WHERE date >= :from ORDER BY date")
    fun getFrom(from: String): List<DailyShift>

    @Query("SELECT * FROM daily_shifts WHERE date = :date LIMIT 1")
    fun get(date: String): DailyShift?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(shift: DailyShift)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(shifts: List<DailyShift>)

    @Query("DELETE FROM daily_shifts WHERE date = :date")
    fun delete(date: String): Int

    /** 低价值清理：太旧的历史班表没有意义，定期裁掉避免表无限增长。 */
    @Query("DELETE FROM daily_shifts WHERE date < :before")
    fun deleteBefore(before: String): Int

    @Query("DELETE FROM daily_shifts")
    fun clear(): Int

    @Query("SELECT COUNT(*) FROM daily_shifts")
    fun count(): Int

    /** 已同步到的最晚日期，用于界面显示「班表覆盖到哪天」。 */
    @Query("SELECT MAX(date) FROM daily_shifts")
    fun latestDate(): String?
}
