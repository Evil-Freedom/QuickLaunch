package com.workbuddy.quicklaunch.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.workbuddy.quicklaunch.data.AppDatabase
import com.workbuddy.quicklaunch.data.Holiday
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.concurrent.Executors

/**
 * 同步中国法定节假日（含调休休息日）到本地 holidays 表。
 * 多数据源：依次尝试 HolidaySources 中的源，第一个成功的即采用（见 [HolidaySources]）。
 * - pref 指定优先源；lastGood 为上次成功源（SharedPreferences 记忆），提升稳定性。
 * - 全部失败（无网络/接口异常）静默回退，已有规则照常触发，不会因同步失败而崩。
 */
object HolidaySync {

    private val executor = Executors.newSingleThreadExecutor()

    data class Result(val success: Boolean, val sourceLabel: String?, val count: Int)

    /**
     * 后台拉取当前年与下一年的节假日并写入数据库，结果在主线程回调。
     * @param pref 优先尝试的数据源 id（null = 自动：上次成功源优先）
     */
    fun sync(context: Context, pref: String? = null, onDone: (Result) -> Unit = {}) {
        executor.execute {
            val res = runCatching { doSync(context, pref) }.getOrDefault(Result(false, null, 0))
            Handler(Looper.getMainLooper()).post { onDone(res) }
        }
    }

    private fun doSync(context: Context, pref: String?): Result {
        val lastGood = HolidayPrefs.getLastGood(context)
        val custom = HolidayPrefs.getCustomSources(context).map { it.toHolidaySource() }
        val sources = HolidaySources.ordered(pref, lastGood, custom)
        val dao = AppDatabase.get(context).holidayDao()
        val year = Calendar.getInstance().get(Calendar.YEAR)

        for (src in sources) {
            val holidays = mutableListOf<Holiday>()
            for (y in year..year + 1) {
                val json = fetchText(src.urlForYear(y)) ?: continue
                val parsed = runCatching { src.parse(json) }.getOrDefault(emptyList())
                holidays.addAll(parsed)
            }
            if (holidays.isNotEmpty()) {
                dao.clear()
                dao.insertAll(holidays)
                HolidayPrefs.setLastGood(context, src.id)
                return Result(true, src.label, holidays.size)
            }
        }
        return Result(false, null, 0)
    }

    private fun fetchText(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.requestMethod = "GET"
        return try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }
}
