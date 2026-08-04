package com.workbuddy.quicklaunch.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Automation::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun automationDao(): AutomationDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "quicklaunch.db"
                )
                    // ponytail: 规则数量只有几十条，且 BroadcastReceiver/JobService 里必须同步取数，
                    // 直接放开主线程查询。若将来规则上千条再换协程 + Flow。
                    .allowMainThreadQueries()
                    .build().also { INSTANCE = it }
            }
        }
    }
}
