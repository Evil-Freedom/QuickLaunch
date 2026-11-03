package com.workbuddy.quicklaunch.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Automation::class, Holiday::class, RuleException::class, DailyShift::class],
    version = 7,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun automationDao(): AutomationDao
    abstract fun holidayDao(): HolidayDao
    abstract fun ruleExceptionDao(): RuleExceptionDao
    abstract fun dailyShiftDao(): DailyShiftDao

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

        // 升级到 version 5：holidays 表新增 isWorkday 列，用于区分「休息日」与「调休上班日」。
        // 存量数据全是休息日（旧版解析器丢弃了调休日），默认 0 正好正确，无需回填。
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE holidays ADD COLUMN isWorkday INTEGER NOT NULL DEFAULT 0")
            }
        }

        // 升级到 version 6：新增 rule_exceptions 表，支持「某一天临时不启动」。
        // 独立建表而不是给 automations 加列：豁免是 (规则 × 日期) 的多对多关系，
        // 塞进一个逗号分隔的字符串列会让「撤销某一天」「查未来 N 天」都要全表解析字符串。
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS rule_exceptions (" +
                        "ruleId INTEGER NOT NULL, " +
                        "date TEXT NOT NULL, " +
                        "reason TEXT NOT NULL DEFAULT '', " +
                        "createdAt INTEGER NOT NULL DEFAULT 0, " +
                        "PRIMARY KEY(ruleId, date))"
                )
            }
        }

        // 升级到 version 7：新增 daily_shifts 班表 + automations 两条锚定列。
        // 班表单独建表而不是塞进 automations：班表是「共享事实」，同一天可能有多条规则
        // 锚定同一个班次，存一份才能保证班表更新一次、所有跟随规则一起变。
        // 两条新列默认 0 = 「固定时刻」= 旧行为，存量规则无需回填。
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE automations ADD COLUMN shiftAnchor INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE automations ADD COLUMN shiftOffsetMin INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS daily_shifts (" +
                        "date TEXT NOT NULL PRIMARY KEY, " +
                        "inMin INTEGER NOT NULL, " +
                        "outMin INTEGER NOT NULL, " +
                        "source TEXT NOT NULL DEFAULT '', " +
                        "updatedAt INTEGER NOT NULL DEFAULT 0)"
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
                    .addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                        MIGRATION_6_7
                    )
                    // 装回旧版本 APK 时若不允许降级重建，Room 会直接抛 IllegalStateException，
                    // 表现为「一打开就闪退」且用户无法自救。宁可丢缓存也不要崩溃循环。
                    .fallbackToDestructiveMigrationOnDowngrade(true)
                    .build().also { INSTANCE = it }
            }
        }
    }
}
