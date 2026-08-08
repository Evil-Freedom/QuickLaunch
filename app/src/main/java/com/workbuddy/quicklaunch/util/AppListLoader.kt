package com.workbuddy.quicklaunch.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.Parcelable
import java.text.Collator
import java.util.Locale
import java.util.concurrent.Executors

/** 已安装、可启动（有桌面入口）的应用。手动实现 Parcelable，便于传给 BottomSheet 并耐受旋转重建。 */
data class AppInfo(val packageName: String, val appName: String) : Parcelable {
    constructor(parcel: Parcel) : this(
        parcel.readString() ?: "",
        parcel.readString() ?: ""
    )

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(packageName)
        dest.writeString(appName)
    }

    companion object CREATOR : Parcelable.Creator<AppInfo> {
        override fun createFromParcel(parcel: Parcel): AppInfo = AppInfo(parcel)
        override fun newArray(size: Int): Array<AppInfo?> = arrayOfNulls(size)
    }
}

object AppListLoader {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "app-list-loader").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 缓存：应用列表变化不频繁，短时间内重复打开选择弹窗直接复用，避免重复几百毫秒的 loadLabel。 */
    @Volatile
    private var cache: List<AppInfo>? = null

    @Volatile
    private var cacheAt = 0L
    private const val CACHE_TTL_MS = 60_000L

    /**
     * 读取本机所有带 LAUNCHER 入口的应用，按名称排序去重。
     *
     * 注意：需要 Manifest 里声明 <queries> 才能看到别的应用，否则 targetSdk 30+ 上返回空列表。
     * ponytail: 不加载图标——选择弹窗只显示名字，逐个 loadIcon 会在主线程卡上百毫秒。
     *
     * **阻塞方法**：loadLabel 要读每个 APK 的资源，几百个应用时耗时 0.5~2s，
     * 必须在后台线程调用，主线程请改用 [loadAsync]。
     */
    fun load(context: Context): List<AppInfo> {
        val cached = cache
        if (cached != null && System.currentTimeMillis() - cacheAt < CACHE_TTL_MS) return cached

        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, 0)
            }
        }.getOrDefault(emptyList())

        // 先按包名去重再 loadLabel：同一应用多入口时可省掉重复的资源读取
        val seen = HashSet<String>(resolved.size)
        val list = ArrayList<AppInfo>(resolved.size)
        for (ri in resolved) {
            val pkg = ri.activityInfo?.packageName ?: continue
            if (!seen.add(pkg)) continue
            val label = runCatching { ri.loadLabel(pm).toString() }.getOrNull()?.takeIf { it.isNotBlank() } ?: pkg
            list.add(AppInfo(pkg, label))
        }

        // 用 Collator 排序，中文按拼音、大小写不敏感；比 sortedBy{lowercase()} 更正确
        // 且只做一次比较器构造，不会对每个元素反复分配小写字符串
        val collator = Collator.getInstance(Locale.getDefault()).apply {
            strength = Collator.PRIMARY
        }
        list.sortWith { a, b -> collator.compare(a.appName, b.appName) }

        cache = list
        cacheAt = System.currentTimeMillis()
        return list
    }

    /** 后台加载 + 主线程回调，供 UI 使用。 */
    fun loadAsync(context: Context, onDone: (List<AppInfo>) -> Unit) {
        val app = context.applicationContext
        cache?.takeIf { System.currentTimeMillis() - cacheAt < CACHE_TTL_MS }?.let {
            onDone(it)
            return
        }
        executor.execute {
            val list = runCatching { load(app) }.getOrDefault(emptyList())
            mainHandler.post { runCatching { onDone(list) } }
        }
    }

    /** 安装/卸载后手动失效缓存。 */
    fun invalidate() {
        cache = null
        cacheAt = 0L
    }
}
