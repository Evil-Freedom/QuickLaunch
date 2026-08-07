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

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "holiday-sync").apply { isDaemon = true }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 同步进行中标记：连点同步按钮时合并请求，避免堆积一串重复的网络任务。 */
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    data class Result(val success: Boolean, val sourceLabel: String?, val count: Int)

    /**
     * 后台拉取当前年与下一年的节假日并写入数据库，结果在主线程回调。
     * 已有同步在跑时直接忽略本次请求（回调仍会触发，避免调用方 UI 卡在 loading）。
     * @param pref 优先尝试的数据源 id（null = 自动：上次成功源优先）
     */
    fun sync(context: Context, pref: String? = null, onDone: (Result) -> Unit = {}) {
        // 用 applicationContext，防止后台任务持有 Activity 导致内存泄漏
        val app = context.applicationContext
        if (!running.compareAndSet(false, true)) {
            mainHandler.post { runCatching { onDone(Result(false, null, 0)) } }
            return
        }
        val submitted = runCatching {
            executor.execute {
                val res = runCatching { doSync(app, pref) }.getOrDefault(Result(false, null, 0))
                running.set(false)
                mainHandler.post { runCatching { onDone(res) } }
            }
        }.isSuccess
        if (!submitted) {
            running.set(false)
            mainHandler.post { runCatching { onDone(Result(false, null, 0)) } }
        }
    }

    private fun doSync(context: Context, pref: String?): Result {
        val lastGood = runCatching { HolidayPrefs.getLastGood(context) }.getOrNull()
        val custom = runCatching {
            HolidayPrefs.getCustomSources(context).map { it.toHolidaySource() }
        }.getOrDefault(emptyList())
        val sources = HolidaySources.ordered(pref, lastGood, custom)
        val dao = AppDatabase.get(context).holidayDao()
        val year = Calendar.getInstance().get(Calendar.YEAR)

        for (src in sources) {
            // 单个源的任何异常（非法 URL、解析崩溃、OOM）都只淘汰该源，
            // 绝不能中断整个回退链 —— 否则第一个坏源就会让后面所有好源失去机会。
            val holidays = runCatching {
                val acc = mutableListOf<Holiday>()
                for (y in year..year + 1) {
                    val url = runCatching { src.urlForYear(y) }.getOrNull() ?: continue
                    val json = fetchText(url) ?: continue
                    acc.addAll(runCatching { src.parse(json) }.getOrDefault(emptyList()))
                }
                acc
            }.getOrDefault(emptyList())

            // 同一天可能被两年的数据重复给出，落库前按日期去重，避免表膨胀
            val distinct = holidays.distinctBy { it.date }
            if (distinct.isNotEmpty()) {
                val written = runCatching { dao.replaceAll(distinct) }.isSuccess
                if (!written) continue          // 写库失败就试下一个源，不谎报成功
                runCatching { HolidayPrefs.setLastGood(context, src.id) }
                return Result(true, src.label, distinct.size)
            }
        }
        return Result(false, null, 0)
    }

    /**
     * 拉取文本。此前 `URL(url).openConnection()` 裸奔在 try 之外：
     * 用户自定义源填了非法 URL（如 "abc"）会抛 MalformedURLException 直接冒泡，
     * 把整个 doSync 打断，后面的可用源一个都试不到。现在全部异常本地消化。
     */
    private fun fetchText(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val u = URL(url)
            // 只允许 http/https，杜绝 file:// 之类协议被误用
            if (u.protocol !in ALLOWED_SCHEMES) return null
            conn = (u.openConnection() as? HttpURLConnection) ?: return null
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = true
            conn.useCaches = false
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Accept-Encoding", "identity")

            if (conn.responseCode !in 200..299) return null
            // 限制读取上限：防止异常/恶意源返回超大响应把内存吃爆（正常年数据 < 100KB）
            conn.inputStream.bufferedReader().use { reader ->
                val sb = StringBuilder()
                val buf = CharArray(8 * 1024)
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    if (sb.length + n > MAX_BODY_CHARS) return null
                    sb.appendRange(buf, 0, n)
                }
                sb.toString()
            }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000
    private const val MAX_BODY_CHARS = 2 * 1024 * 1024
    private val ALLOWED_SCHEMES = setOf("http", "https")
}
