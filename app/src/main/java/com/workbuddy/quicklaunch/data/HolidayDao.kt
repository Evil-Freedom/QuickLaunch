package com.workbuddy.quicklaunch.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface HolidayDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(list: List<Holiday>)

    /**
     * 整表替换。必须在同一事务里做：
     * 原来是 clear() 后再 insertAll()，若 insert 中途失败（磁盘满、约束冲突、进程被杀），
     * 节假日表会停在「已清空但没写入」的状态，跳过节假日功能直接静默失效且不可恢复。
     */
    @Transaction
    fun replaceAll(list: List<Holiday>) {
        clear()
        insertAll(list)
    }

    @Query("SELECT date FROM holidays")
    fun getAllDates(): List<String>

    /** 仅休息日（isWorkday = 0），用于「跳过节假日不触发」。 */
    @Query("SELECT date FROM holidays WHERE isWorkday = 0")
    fun getRestDates(): List<String>

    /** 仅调休上班日（isWorkday = 1），用于「遇到调休自动运行」。 */
    @Query("SELECT date FROM holidays WHERE isWorkday = 1")
    fun getWorkdayDates(): List<String>

    @Query("SELECT * FROM holidays ORDER BY date")
    fun getAll(): List<Holiday>

    @Query("SELECT * FROM holidays WHERE date = :date LIMIT 1")
    fun getByDate(date: String): Holiday?

    @Query("SELECT COUNT(*) FROM holidays")
    fun count(): Int

    @Query("DELETE FROM holidays WHERE date = :date")
    fun deleteByDate(date: String)

    @Query("DELETE FROM holidays")
    fun clear()
}
