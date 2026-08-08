package com.workbuddy.quicklaunch.`data`

import androidx.room.EntityInsertAdapter
import androidx.room.RoomDatabase
import androidx.room.util.getColumnIndexOrThrow
import androidx.room.util.performBlocking
import androidx.sqlite.SQLiteStatement
import javax.`annotation`.processing.Generated
import kotlin.Int
import kotlin.String
import kotlin.Suppress
import kotlin.Unit
import kotlin.collections.List
import kotlin.collections.MutableList
import kotlin.collections.mutableListOf
import kotlin.reflect.KClass

@Generated(value = ["androidx.room.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL"])
public class HolidayDao_Impl(
  __db: RoomDatabase,
) : HolidayDao {
  private val __db: RoomDatabase

  private val __insertAdapterOfHoliday: EntityInsertAdapter<Holiday>
  init {
    this.__db = __db
    this.__insertAdapterOfHoliday = object : EntityInsertAdapter<Holiday>() {
      protected override fun createQuery(): String =
          "INSERT OR REPLACE INTO `holidays` (`date`,`name`) VALUES (?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: Holiday) {
        statement.bindText(1, entity.date)
        statement.bindText(2, entity.name)
      }
    }
  }

  public override fun insertAll(list: List<Holiday>): Unit = performBlocking(__db, false, true) {
      _connection ->
    __insertAdapterOfHoliday.insert(_connection, list)
  }

  public override fun replaceAll(list: List<Holiday>): Unit = performBlocking(__db, false, true) {
      _ ->
    super@HolidayDao_Impl.replaceAll(list)
  }

  public override fun getAllDates(): List<String> {
    val _sql: String = "SELECT date FROM holidays"
    return performBlocking(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _result: MutableList<String> = mutableListOf()
        while (_stmt.step()) {
          val _item: String
          _item = _stmt.getText(0)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override fun getAll(): List<Holiday> {
    val _sql: String = "SELECT * FROM holidays ORDER BY date"
    return performBlocking(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfDate: Int = getColumnIndexOrThrow(_stmt, "date")
        val _columnIndexOfName: Int = getColumnIndexOrThrow(_stmt, "name")
        val _result: MutableList<Holiday> = mutableListOf()
        while (_stmt.step()) {
          val _item: Holiday
          val _tmpDate: String
          _tmpDate = _stmt.getText(_columnIndexOfDate)
          val _tmpName: String
          _tmpName = _stmt.getText(_columnIndexOfName)
          _item = Holiday(_tmpDate,_tmpName)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override fun count(): Int {
    val _sql: String = "SELECT COUNT(*) FROM holidays"
    return performBlocking(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _result: Int
        if (_stmt.step()) {
          _result = _stmt.getLong(0).toInt()
        } else {
          _result = 0
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override fun deleteByDate(date: String) {
    val _sql: String = "DELETE FROM holidays WHERE date = ?"
    return performBlocking(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, date)
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public override fun clear() {
    val _sql: String = "DELETE FROM holidays"
    return performBlocking(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public companion object {
    public fun getRequiredConverters(): List<KClass<*>> = emptyList()
  }
}
