package com.workbuddy.quicklaunch.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface HolidayDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(list: List<Holiday>)

    @Query("SELECT date FROM holidays")
    fun getAllDates(): List<String>

    @Query("SELECT * FROM holidays ORDER BY date")
    fun getAll(): List<Holiday>

    @Query("SELECT COUNT(*) FROM holidays")
    fun count(): Int

    @Query("DELETE FROM holidays WHERE date = :date")
    fun deleteByDate(date: String)

    @Query("DELETE FROM holidays")
    fun clear()
}
