package com.workbuddy.quicklaunch.`data`

import androidx.room.EntityDeleteOrUpdateAdapter
import androidx.room.EntityInsertAdapter
import androidx.room.RoomDatabase
import androidx.room.util.getColumnIndexOrThrow
import androidx.room.util.performBlocking
import androidx.sqlite.SQLiteStatement
import javax.`annotation`.processing.Generated
import kotlin.Boolean
import kotlin.Int
import kotlin.Long
import kotlin.String
import kotlin.Suppress
import kotlin.Unit
import kotlin.collections.List
import kotlin.collections.MutableList
import kotlin.collections.mutableListOf
import kotlin.reflect.KClass

@Generated(value = ["androidx.room.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL"])
public class AutomationDao_Impl(
  __db: RoomDatabase,
) : AutomationDao {
  private val __db: RoomDatabase

  private val __insertAdapterOfAutomation: EntityInsertAdapter<Automation>

  private val __deleteAdapterOfAutomation: EntityDeleteOrUpdateAdapter<Automation>

  private val __updateAdapterOfAutomation: EntityDeleteOrUpdateAdapter<Automation>
  init {
    this.__db = __db
    this.__insertAdapterOfAutomation = object : EntityInsertAdapter<Automation>() {
      protected override fun createQuery(): String =
          "INSERT OR ABORT INTO `automations` (`id`,`name`,`targetPackage`,`targetAppName`,`triggerType`,`timeHour`,`timeMinute`,`repeatMode`,`repeatDays`,`skipHolidays`,`bluetoothName`,`enabled`,`createdAt`,`randomWindow`,`windowStartMin`,`windowEndMin`) VALUES (nullif(?, 0),?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: Automation) {
        statement.bindLong(1, entity.id)
        statement.bindText(2, entity.name)
        statement.bindText(3, entity.targetPackage)
        statement.bindText(4, entity.targetAppName)
        statement.bindText(5, entity.triggerType)
        statement.bindLong(6, entity.timeHour.toLong())
        statement.bindLong(7, entity.timeMinute.toLong())
        statement.bindText(8, entity.repeatMode)
        statement.bindLong(9, entity.repeatDays.toLong())
        val _tmp: Int = if (entity.skipHolidays) 1 else 0
        statement.bindLong(10, _tmp.toLong())
        statement.bindText(11, entity.bluetoothName)
        val _tmp_1: Int = if (entity.enabled) 1 else 0
        statement.bindLong(12, _tmp_1.toLong())
        statement.bindLong(13, entity.createdAt)
        val _tmp_2: Int = if (entity.randomWindow) 1 else 0
        statement.bindLong(14, _tmp_2.toLong())
        statement.bindLong(15, entity.windowStartMin.toLong())
        statement.bindLong(16, entity.windowEndMin.toLong())
      }
    }
    this.__deleteAdapterOfAutomation = object : EntityDeleteOrUpdateAdapter<Automation>() {
      protected override fun createQuery(): String = "DELETE FROM `automations` WHERE `id` = ?"

      protected override fun bind(statement: SQLiteStatement, entity: Automation) {
        statement.bindLong(1, entity.id)
      }
    }
    this.__updateAdapterOfAutomation = object : EntityDeleteOrUpdateAdapter<Automation>() {
      protected override fun createQuery(): String =
          "UPDATE OR ABORT `automations` SET `id` = ?,`name` = ?,`targetPackage` = ?,`targetAppName` = ?,`triggerType` = ?,`timeHour` = ?,`timeMinute` = ?,`repeatMode` = ?,`repeatDays` = ?,`skipHolidays` = ?,`bluetoothName` = ?,`enabled` = ?,`createdAt` = ?,`randomWindow` = ?,`windowStartMin` = ?,`windowEndMin` = ? WHERE `id` = ?"

      protected override fun bind(statement: SQLiteStatement, entity: Automation) {
        statement.bindLong(1, entity.id)
        statement.bindText(2, entity.name)
        statement.bindText(3, entity.targetPackage)
        statement.bindText(4, entity.targetAppName)
        statement.bindText(5, entity.triggerType)
        statement.bindLong(6, entity.timeHour.toLong())
        statement.bindLong(7, entity.timeMinute.toLong())
        statement.bindText(8, entity.repeatMode)
        statement.bindLong(9, entity.repeatDays.toLong())
        val _tmp: Int = if (entity.skipHolidays) 1 else 0
        statement.bindLong(10, _tmp.toLong())
        statement.bindText(11, entity.bluetoothName)
        val _tmp_1: Int = if (entity.enabled) 1 else 0
        statement.bindLong(12, _tmp_1.toLong())
        statement.bindLong(13, entity.createdAt)
        val _tmp_2: Int = if (entity.randomWindow) 1 else 0
        statement.bindLong(14, _tmp_2.toLong())
        statement.bindLong(15, entity.windowStartMin.toLong())
        statement.bindLong(16, entity.windowEndMin.toLong())
        statement.bindLong(17, entity.id)
      }
    }
  }

  public override fun insert(a: Automation): Long = performBlocking(__db, false, true) {
      _connection ->
    val _result: Long = __insertAdapterOfAutomation.insertAndReturnId(_connection, a)
    _result
  }

  public override fun delete(a: Automation): Unit = performBlocking(__db, false, true) {
      _connection ->
    __deleteAdapterOfAutomation.handle(_connection, a)
  }

  public override fun update(a: Automation): Unit = performBlocking(__db, false, true) {
      _connection ->
    __updateAdapterOfAutomation.handle(_connection, a)
  }

  public override fun getAll(): List<Automation> {
    val _sql: String = "SELECT * FROM automations ORDER BY createdAt DESC"
    return performBlocking(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfName: Int = getColumnIndexOrThrow(_stmt, "name")
        val _columnIndexOfTargetPackage: Int = getColumnIndexOrThrow(_stmt, "targetPackage")
        val _columnIndexOfTargetAppName: Int = getColumnIndexOrThrow(_stmt, "targetAppName")
        val _columnIndexOfTriggerType: Int = getColumnIndexOrThrow(_stmt, "triggerType")
        val _columnIndexOfTimeHour: Int = getColumnIndexOrThrow(_stmt, "timeHour")
        val _columnIndexOfTimeMinute: Int = getColumnIndexOrThrow(_stmt, "timeMinute")
        val _columnIndexOfRepeatMode: Int = getColumnIndexOrThrow(_stmt, "repeatMode")
        val _columnIndexOfRepeatDays: Int = getColumnIndexOrThrow(_stmt, "repeatDays")
        val _columnIndexOfSkipHolidays: Int = getColumnIndexOrThrow(_stmt, "skipHolidays")
        val _columnIndexOfBluetoothName: Int = getColumnIndexOrThrow(_stmt, "bluetoothName")
        val _columnIndexOfEnabled: Int = getColumnIndexOrThrow(_stmt, "enabled")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "createdAt")
        val _columnIndexOfRandomWindow: Int = getColumnIndexOrThrow(_stmt, "randomWindow")
        val _columnIndexOfWindowStartMin: Int = getColumnIndexOrThrow(_stmt, "windowStartMin")
        val _columnIndexOfWindowEndMin: Int = getColumnIndexOrThrow(_stmt, "windowEndMin")
        val _result: MutableList<Automation> = mutableListOf()
        while (_stmt.step()) {
          val _item: Automation
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpName: String
          _tmpName = _stmt.getText(_columnIndexOfName)
          val _tmpTargetPackage: String
          _tmpTargetPackage = _stmt.getText(_columnIndexOfTargetPackage)
          val _tmpTargetAppName: String
          _tmpTargetAppName = _stmt.getText(_columnIndexOfTargetAppName)
          val _tmpTriggerType: String
          _tmpTriggerType = _stmt.getText(_columnIndexOfTriggerType)
          val _tmpTimeHour: Int
          _tmpTimeHour = _stmt.getLong(_columnIndexOfTimeHour).toInt()
          val _tmpTimeMinute: Int
          _tmpTimeMinute = _stmt.getLong(_columnIndexOfTimeMinute).toInt()
          val _tmpRepeatMode: String
          _tmpRepeatMode = _stmt.getText(_columnIndexOfRepeatMode)
          val _tmpRepeatDays: Int
          _tmpRepeatDays = _stmt.getLong(_columnIndexOfRepeatDays).toInt()
          val _tmpSkipHolidays: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfSkipHolidays).toInt()
          _tmpSkipHolidays = _tmp != 0
          val _tmpBluetoothName: String
          _tmpBluetoothName = _stmt.getText(_columnIndexOfBluetoothName)
          val _tmpEnabled: Boolean
          val _tmp_1: Int
          _tmp_1 = _stmt.getLong(_columnIndexOfEnabled).toInt()
          _tmpEnabled = _tmp_1 != 0
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          val _tmpRandomWindow: Boolean
          val _tmp_2: Int
          _tmp_2 = _stmt.getLong(_columnIndexOfRandomWindow).toInt()
          _tmpRandomWindow = _tmp_2 != 0
          val _tmpWindowStartMin: Int
          _tmpWindowStartMin = _stmt.getLong(_columnIndexOfWindowStartMin).toInt()
          val _tmpWindowEndMin: Int
          _tmpWindowEndMin = _stmt.getLong(_columnIndexOfWindowEndMin).toInt()
          _item =
              Automation(_tmpId,_tmpName,_tmpTargetPackage,_tmpTargetAppName,_tmpTriggerType,_tmpTimeHour,_tmpTimeMinute,_tmpRepeatMode,_tmpRepeatDays,_tmpSkipHolidays,_tmpBluetoothName,_tmpEnabled,_tmpCreatedAt,_tmpRandomWindow,_tmpWindowStartMin,_tmpWindowEndMin)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override fun getById(id: Long): Automation? {
    val _sql: String = "SELECT * FROM automations WHERE id = ?"
    return performBlocking(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, id)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfName: Int = getColumnIndexOrThrow(_stmt, "name")
        val _columnIndexOfTargetPackage: Int = getColumnIndexOrThrow(_stmt, "targetPackage")
        val _columnIndexOfTargetAppName: Int = getColumnIndexOrThrow(_stmt, "targetAppName")
        val _columnIndexOfTriggerType: Int = getColumnIndexOrThrow(_stmt, "triggerType")
        val _columnIndexOfTimeHour: Int = getColumnIndexOrThrow(_stmt, "timeHour")
        val _columnIndexOfTimeMinute: Int = getColumnIndexOrThrow(_stmt, "timeMinute")
        val _columnIndexOfRepeatMode: Int = getColumnIndexOrThrow(_stmt, "repeatMode")
        val _columnIndexOfRepeatDays: Int = getColumnIndexOrThrow(_stmt, "repeatDays")
        val _columnIndexOfSkipHolidays: Int = getColumnIndexOrThrow(_stmt, "skipHolidays")
        val _columnIndexOfBluetoothName: Int = getColumnIndexOrThrow(_stmt, "bluetoothName")
        val _columnIndexOfEnabled: Int = getColumnIndexOrThrow(_stmt, "enabled")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "createdAt")
        val _columnIndexOfRandomWindow: Int = getColumnIndexOrThrow(_stmt, "randomWindow")
        val _columnIndexOfWindowStartMin: Int = getColumnIndexOrThrow(_stmt, "windowStartMin")
        val _columnIndexOfWindowEndMin: Int = getColumnIndexOrThrow(_stmt, "windowEndMin")
        val _result: Automation?
        if (_stmt.step()) {
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpName: String
          _tmpName = _stmt.getText(_columnIndexOfName)
          val _tmpTargetPackage: String
          _tmpTargetPackage = _stmt.getText(_columnIndexOfTargetPackage)
          val _tmpTargetAppName: String
          _tmpTargetAppName = _stmt.getText(_columnIndexOfTargetAppName)
          val _tmpTriggerType: String
          _tmpTriggerType = _stmt.getText(_columnIndexOfTriggerType)
          val _tmpTimeHour: Int
          _tmpTimeHour = _stmt.getLong(_columnIndexOfTimeHour).toInt()
          val _tmpTimeMinute: Int
          _tmpTimeMinute = _stmt.getLong(_columnIndexOfTimeMinute).toInt()
          val _tmpRepeatMode: String
          _tmpRepeatMode = _stmt.getText(_columnIndexOfRepeatMode)
          val _tmpRepeatDays: Int
          _tmpRepeatDays = _stmt.getLong(_columnIndexOfRepeatDays).toInt()
          val _tmpSkipHolidays: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfSkipHolidays).toInt()
          _tmpSkipHolidays = _tmp != 0
          val _tmpBluetoothName: String
          _tmpBluetoothName = _stmt.getText(_columnIndexOfBluetoothName)
          val _tmpEnabled: Boolean
          val _tmp_1: Int
          _tmp_1 = _stmt.getLong(_columnIndexOfEnabled).toInt()
          _tmpEnabled = _tmp_1 != 0
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          val _tmpRandomWindow: Boolean
          val _tmp_2: Int
          _tmp_2 = _stmt.getLong(_columnIndexOfRandomWindow).toInt()
          _tmpRandomWindow = _tmp_2 != 0
          val _tmpWindowStartMin: Int
          _tmpWindowStartMin = _stmt.getLong(_columnIndexOfWindowStartMin).toInt()
          val _tmpWindowEndMin: Int
          _tmpWindowEndMin = _stmt.getLong(_columnIndexOfWindowEndMin).toInt()
          _result =
              Automation(_tmpId,_tmpName,_tmpTargetPackage,_tmpTargetAppName,_tmpTriggerType,_tmpTimeHour,_tmpTimeMinute,_tmpRepeatMode,_tmpRepeatDays,_tmpSkipHolidays,_tmpBluetoothName,_tmpEnabled,_tmpCreatedAt,_tmpRandomWindow,_tmpWindowStartMin,_tmpWindowEndMin)
        } else {
          _result = null
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override fun getEnabledByType(type: String): List<Automation> {
    val _sql: String = "SELECT * FROM automations WHERE triggerType = ? AND enabled = 1"
    return performBlocking(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, type)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfName: Int = getColumnIndexOrThrow(_stmt, "name")
        val _columnIndexOfTargetPackage: Int = getColumnIndexOrThrow(_stmt, "targetPackage")
        val _columnIndexOfTargetAppName: Int = getColumnIndexOrThrow(_stmt, "targetAppName")
        val _columnIndexOfTriggerType: Int = getColumnIndexOrThrow(_stmt, "triggerType")
        val _columnIndexOfTimeHour: Int = getColumnIndexOrThrow(_stmt, "timeHour")
        val _columnIndexOfTimeMinute: Int = getColumnIndexOrThrow(_stmt, "timeMinute")
        val _columnIndexOfRepeatMode: Int = getColumnIndexOrThrow(_stmt, "repeatMode")
        val _columnIndexOfRepeatDays: Int = getColumnIndexOrThrow(_stmt, "repeatDays")
        val _columnIndexOfSkipHolidays: Int = getColumnIndexOrThrow(_stmt, "skipHolidays")
        val _columnIndexOfBluetoothName: Int = getColumnIndexOrThrow(_stmt, "bluetoothName")
        val _columnIndexOfEnabled: Int = getColumnIndexOrThrow(_stmt, "enabled")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "createdAt")
        val _columnIndexOfRandomWindow: Int = getColumnIndexOrThrow(_stmt, "randomWindow")
        val _columnIndexOfWindowStartMin: Int = getColumnIndexOrThrow(_stmt, "windowStartMin")
        val _columnIndexOfWindowEndMin: Int = getColumnIndexOrThrow(_stmt, "windowEndMin")
        val _result: MutableList<Automation> = mutableListOf()
        while (_stmt.step()) {
          val _item: Automation
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpName: String
          _tmpName = _stmt.getText(_columnIndexOfName)
          val _tmpTargetPackage: String
          _tmpTargetPackage = _stmt.getText(_columnIndexOfTargetPackage)
          val _tmpTargetAppName: String
          _tmpTargetAppName = _stmt.getText(_columnIndexOfTargetAppName)
          val _tmpTriggerType: String
          _tmpTriggerType = _stmt.getText(_columnIndexOfTriggerType)
          val _tmpTimeHour: Int
          _tmpTimeHour = _stmt.getLong(_columnIndexOfTimeHour).toInt()
          val _tmpTimeMinute: Int
          _tmpTimeMinute = _stmt.getLong(_columnIndexOfTimeMinute).toInt()
          val _tmpRepeatMode: String
          _tmpRepeatMode = _stmt.getText(_columnIndexOfRepeatMode)
          val _tmpRepeatDays: Int
          _tmpRepeatDays = _stmt.getLong(_columnIndexOfRepeatDays).toInt()
          val _tmpSkipHolidays: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfSkipHolidays).toInt()
          _tmpSkipHolidays = _tmp != 0
          val _tmpBluetoothName: String
          _tmpBluetoothName = _stmt.getText(_columnIndexOfBluetoothName)
          val _tmpEnabled: Boolean
          val _tmp_1: Int
          _tmp_1 = _stmt.getLong(_columnIndexOfEnabled).toInt()
          _tmpEnabled = _tmp_1 != 0
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          val _tmpRandomWindow: Boolean
          val _tmp_2: Int
          _tmp_2 = _stmt.getLong(_columnIndexOfRandomWindow).toInt()
          _tmpRandomWindow = _tmp_2 != 0
          val _tmpWindowStartMin: Int
          _tmpWindowStartMin = _stmt.getLong(_columnIndexOfWindowStartMin).toInt()
          val _tmpWindowEndMin: Int
          _tmpWindowEndMin = _stmt.getLong(_columnIndexOfWindowEndMin).toInt()
          _item =
              Automation(_tmpId,_tmpName,_tmpTargetPackage,_tmpTargetAppName,_tmpTriggerType,_tmpTimeHour,_tmpTimeMinute,_tmpRepeatMode,_tmpRepeatDays,_tmpSkipHolidays,_tmpBluetoothName,_tmpEnabled,_tmpCreatedAt,_tmpRandomWindow,_tmpWindowStartMin,_tmpWindowEndMin)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public companion object {
    public fun getRequiredConverters(): List<KClass<*>> = emptyList()
  }
}
