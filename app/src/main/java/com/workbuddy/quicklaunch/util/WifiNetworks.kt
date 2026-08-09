package com.workbuddy.quicklaunch.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * WiFi 网络工具：获取已保存的 WiFi 网络名称列表。
 *
 * 注意：Android 11+ 需要 ACCESS_FINE_LOCATION 权限，Android 12+ 需要 NEARBY_WIFI_DEVICES 权限，
 * 否则 getConfiguredNetworks() 返回空列表或被限制。
 */
object WifiNetworks {

    /** 获取已保存的 WiFi 网络名称列表（去重、排序）。权限不足时回退到当前 SSID。 */
    fun getSavedNetworkNames(context: Context): List<String> {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return emptyList()

        if (!wifiManager.isWifiEnabled) return emptyList()

        // Android 12+ 运行时权限检查
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                // 权限不足：回退到当前连接的 SSID
                return getCurrentSsid(context)?.let { listOf(it) } ?: emptyList()
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10-12 需要 ACCESS_FINE_LOCATION
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                // 权限不足：回退到当前连接的 SSID
                return getCurrentSsid(context)?.let { listOf(it) } ?: emptyList()
            }
        }

        return runCatching {
            wifiManager.configuredNetworks
                ?.mapNotNull { config ->
                    var ssid = config.SSID
                    if (ssid != null && ssid.length >= 2 && ssid.startsWith("\"") && ssid.endsWith("\"")) {
                        ssid = ssid.substring(1, ssid.length - 1)
                    }
                    ssid?.takeIf { it.isNotBlank() }
                }
                ?.distinct()
                ?.sorted()
                ?: emptyList()
        }.getOrDefault(emptyList())
    }

    /** 获取当前连接的 WiFi 名称（未连接返回 null）。 */
    fun getCurrentSsid(context: Context): String? {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return null

        val info = runCatching { wifiManager.connectionInfo }.getOrNull() ?: return null
        var ssid = info.ssid ?: return null
        if (ssid.length >= 2 && ssid.startsWith("\"") && ssid.endsWith("\"")) {
            ssid = ssid.substring(1, ssid.length - 1)
        }
        return ssid.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
    }
}
