package com.workbuddy.quicklaunch.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Automation::class], version = 2)
abstract class AppDatabase : RoomDatabase() {
    abstract fun automationDao(): AutomationDao

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

        fun get(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "quicklaunch.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    // ponytail: 规则数量只有几十条，且 BroadcastReceiver/JobService 里必须同步取数，
                    // 直接放开主线程查询。若将来规则上千条再换协程 + Flow。
                    .allowMainThreadQueries()
                    .build().also { INSTANCE = it }
            }
        }
    }
}
