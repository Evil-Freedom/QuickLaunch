package com.workbuddy.quicklaunch.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface AutomationDao {
    @Query("SELECT * FROM automations ORDER BY createdAt DESC")
    fun getAll(): List<Automation>

    @Query("SELECT * FROM automations WHERE id = :id")
    fun getById(id: Long): Automation?

    @Query("SELECT * FROM automations WHERE triggerType = :type AND enabled = 1")
    fun getEnabledByType(type: String): List<Automation>

    @Insert
    fun insert(a: Automation): Long

    @Update
    fun update(a: Automation)

    @Delete
    fun delete(a: Automation)
}
