package com.workbuddy.quicklaunch.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Automation::class, Holiday::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun automationDao(): AutomationDao
    abstract fun holidayDao(): HolidayDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // 升级到 version 2：给 automations 表新增随机窗口三列，保留旧规则不丢
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE automations ADD COLUMN randomWindow INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE automations ADD COLUMN windowStartMin INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE automations ADD COLUMN windowEndMin INTEGER NOT NULL DEFAULT 0")
            }
        }

        // 升级到 version 3：新增自定义星期位图列
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE automations ADD COLUMN repeatDays INTEGER NOT NULL DEFAULT 0")
            }
        }

        // 升级到 version 4：新增 holidays 表缓存中国法定节假日
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS holidays (" +
                        "date TEXT NOT NULL PRIMARY KEY, " +
                        "name TEXT NOT NULL)"
                )
            }
        }

        fun get(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "quicklaunch.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    // 装回旧版本 APK 时若不允许降级重建，Room 会直接抛 IllegalStateException，
                    // 表现为「一打开就闪退」且用户无法自救。宁可丢缓存也不要崩溃循环。
                    .fallbackToDestructiveMigrationOnDowngrade(true)
                    .build().also { INSTANCE = it }
            }
        }
    }
}
