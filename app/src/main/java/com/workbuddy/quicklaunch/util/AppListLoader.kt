package com.workbuddy.quicklaunch.util

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.content.Context
import android.content.Intent

/**
 * 已安装、可启动（有桌面入口）的应用信息。
 */
data class AppInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable
)

object AppListLoader {
    /**
     * 读取本机所有带 LAUNCHER 入口的应用，按名称排序去重。
     */
    fun load(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        return pm.queryIntentActivities(intent, 0)
            .map {
                AppInfo(
                    packageName = it.activityInfo.packageName,
                    appName = it.loadLabel(pm).toString(),
                    icon = it.loadIcon(pm)
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.appName.lowercase() }
    }
}
