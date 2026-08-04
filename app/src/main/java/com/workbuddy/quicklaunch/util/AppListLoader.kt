package com.workbuddy.quicklaunch.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/** 已安装、可启动（有桌面入口）的应用。 */
data class AppInfo(val packageName: String, val appName: String)

object AppListLoader {
    /**
     * 读取本机所有带 LAUNCHER 入口的应用，按名称排序去重。
     *
     * 注意：需要 Manifest 里声明 <queries> 才能看到别的应用，否则 targetSdk 30+ 上返回空列表。
     * ponytail: 不加载图标——选择弹窗只显示名字，逐个 loadIcon 会在主线程卡上百毫秒。
     */
    fun load(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        return resolved
            .map { AppInfo(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .distinctBy { it.packageName }
            .sortedBy { it.appName.lowercase() }
    }
}
