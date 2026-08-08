package com.workbuddy.quicklaunch.`data`

import androidx.room.InvalidationTracker
import androidx.room.RoomOpenDelegate
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.room.util.TableInfo
import androidx.room.util.TableInfo.Companion.read
import androidx.room.util.dropFtsSyncTriggers
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import javax.`annotation`.processing.Generated
import kotlin.Lazy
import kotlin.String
import kotlin.Suppress
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.collections.MutableList
import kotlin.collections.MutableMap
import kotlin.collections.MutableSet
import kotlin.collections.Set
import kotlin.collections.mutableListOf
import kotlin.collections.mutableMapOf
import kotlin.collections.mutableSetOf
import kotlin.reflect.KClass

@Generated(value = ["androidx.room.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL"])
public class AppDatabase_Impl : AppDatabase() {
  private val _automationDao: Lazy<AutomationDao> = lazy {
    AutomationDao_Impl(this)
  }

  private val _holidayDao: Lazy<HolidayDao> = lazy {
    HolidayDao_Impl(this)
  }

  protected override fun createOpenDelegate(): RoomOpenDelegate {
    val _openDelegate: RoomOpenDelegate = object : RoomOpenDelegate(4,
        "b9e8211da3ee5e84bc1a2cf6f014e5a2", "e39427a661ab8b8add2ae77dcae0bea0") {
      public override fun createAllTables(connection: SQLiteConnection) {
        connection.execSQL("CREATE TABLE IF NOT EXISTS `automations` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `targetPackage` TEXT NOT NULL, `targetAppName` TEXT NOT NULL, `triggerType` TEXT NOT NULL, `timeHour` INTEGER NOT NULL, `timeMinute` INTEGER NOT NULL, `repeatMode` TEXT NOT NULL, `repeatDays` INTEGER NOT NULL, `skipHolidays` INTEGER NOT NULL, `bluetoothName` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `randomWindow` INTEGER NOT NULL, `windowStartMin` INTEGER NOT NULL, `windowEndMin` INTEGER NOT NULL)")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `holidays` (`date` TEXT NOT NULL, `name` TEXT NOT NULL, PRIMARY KEY(`date`))")
        connection.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        connection.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'b9e8211da3ee5e84bc1a2cf6f014e5a2')")
      }

      public override fun dropAllTables(connection: SQLiteConnection) {
        connection.execSQL("DROP TABLE IF EXISTS `automations`")
        connection.execSQL("DROP TABLE IF EXISTS `holidays`")
      }

      public override fun onCreate(connection: SQLiteConnection) {
      }

      public override fun onOpen(connection: SQLiteConnection) {
        internalInitInvalidationTracker(connection)
      }

      public override fun onPreMigrate(connection: SQLiteConnection) {
        dropFtsSyncTriggers(connection)
      }

      public override fun onPostMigrate(connection: SQLiteConnection) {
      }

      public override fun onValidateSchema(connection: SQLiteConnection):
          RoomOpenDelegate.ValidationResult {
        val _columnsAutomations: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsAutomations.put("id", TableInfo.Column("id", "INTEGER", true, 1, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsAutomations.put("name", TableInfo.Column("name", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsAutomations.put("targetPackage", TableInfo.Column("targetPackage", "TEXT", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAutomations.put("targetAppName", TableInfo.Column("targetAppName", "TEXT", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAutomations.put("triggerType", TableInfo.Column("triggerType", "TEXT", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAutomations.put("timeHour", TableInfo.Column("timeHour", "INTEGER", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsAutomations.put("timeMinute", TableInfo.Column("timeMinute", "INTEGER", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAutomations.put("repeatMode", TableInfo.Column("repeatMode", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsAutomations.put("repeatDays", TableInfo.Column("repeatDays", "INTEGER", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAutomations.put("skipHolidays", TableInfo.Column("skipHolidays", "INTEGER", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAutomations.put("bluetoothName", TableInfo.Column("bluetoothName", "TEXT", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAutomations.put("enabled", TableInfo.Column("enabled", "INTEGER", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsAutomations.put("createdAt", TableInfo.Column("createdAt", "INTEGER", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsAutomations.put("randomWindow", TableInfo.Column("randomWindow", "INTEGER", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAutomations.put("windowStartMin", TableInfo.Column("windowStartMin", "INTEGER",
            true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsAutomations.put("windowEndMin", TableInfo.Column("windowEndMin", "INTEGER", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysAutomations: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesAutomations: MutableSet<TableInfo.Index> = mutableSetOf()
        val _infoAutomations: TableInfo = TableInfo("automations", _columnsAutomations,
            _foreignKeysAutomations, _indicesAutomations)
        val _existingAutomations: TableInfo = read(connection, "automations")
        if (!_infoAutomations.equals(_existingAutomations)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |automations(com.workbuddy.quicklaunch.data.Automation).
              | Expected:
              |""".trimMargin() + _infoAutomations + """
              |
              | Found:
              |""".trimMargin() + _existingAutomations)
        }
        val _columnsHolidays: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsHolidays.put("date", TableInfo.Column("date", "TEXT", true, 1, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsHolidays.put("name", TableInfo.Column("name", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysHolidays: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesHolidays: MutableSet<TableInfo.Index> = mutableSetOf()
        val _infoHolidays: TableInfo = TableInfo("holidays", _columnsHolidays, _foreignKeysHolidays,
            _indicesHolidays)
        val _existingHolidays: TableInfo = read(connection, "holidays")
        if (!_infoHolidays.equals(_existingHolidays)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |holidays(com.workbuddy.quicklaunch.data.Holiday).
              | Expected:
              |""".trimMargin() + _infoHolidays + """
              |
              | Found:
              |""".trimMargin() + _existingHolidays)
        }
        return RoomOpenDelegate.ValidationResult(true, null)
      }
    }
    return _openDelegate
  }

  protected override fun createInvalidationTracker(): InvalidationTracker {
    val _shadowTablesMap: MutableMap<String, String> = mutableMapOf()
    val _viewTables: MutableMap<String, Set<String>> = mutableMapOf()
    return InvalidationTracker(this, _shadowTablesMap, _viewTables, "automations", "holidays")
  }

  public override fun clearAllTables() {
    super.performClear(false, "automations", "holidays")
  }

  protected override fun getRequiredTypeConverterClasses(): Map<KClass<*>, List<KClass<*>>> {
    val _typeConvertersMap: MutableMap<KClass<*>, List<KClass<*>>> = mutableMapOf()
    _typeConvertersMap.put(AutomationDao::class, AutomationDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(HolidayDao::class, HolidayDao_Impl.getRequiredConverters())
    return _typeConvertersMap
  }

  public override fun getRequiredAutoMigrationSpecClasses(): Set<KClass<out AutoMigrationSpec>> {
    val _autoMigrationSpecsSet: MutableSet<KClass<out AutoMigrationSpec>> = mutableSetOf()
    return _autoMigrationSpecsSet
  }

  public override
      fun createAutoMigrations(autoMigrationSpecs: Map<KClass<out AutoMigrationSpec>, AutoMigrationSpec>):
      List<Migration> {
    val _autoMigrations: MutableList<Migration> = mutableListOf()
    return _autoMigrations
  }

  public override fun automationDao(): AutomationDao = _automationDao.value

  public override fun holidayDao(): HolidayDao = _holidayDao.value
}
