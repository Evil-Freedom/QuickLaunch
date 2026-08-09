package com.workbuddy.quicklaunch.receiver

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import com.workbuddy.quicklaunch.BuildConfig
import com.workbuddy.quicklaunch.data.AppDatabase
import com.workbuddy.quicklaunch.data.TriggerType
import com.workbuddy.quicklaunch.service.LaunchService
import com.workbuddy.quicklaunch.util.WifiNetworks

/**
 * WiFi 触发：设备连上 WLAN 时拉起目标 App。
 *
 * 注意：`android.net.wifi.STATE_CHANGE` 自 Android 8.0 起属于隐式广播黑名单，清单静态注册收不到。
 * 这里改用系统原生的 ConnectivityManager.registerNetworkCallback(request, PendingIntent)：
 * 语义正好是「网络变为可用时发一次」，且进程被杀后系统仍会唤起，无需常驻服务。
 */
class WifiReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        ReceiverWorker.run(this, "WifiReceiver") {
            val list = runCatching {
                AppDatabase.get(app).automationDao().getEnabledByType(TriggerType.WIFI)
            }.getOrDefault(emptyList())

            // 获取当前连接的 SSID，用于与规则的 wifiName 做匹配
            val currentSsid = WifiNetworks.getCurrentSsid(app)

            // 单条启动失败不影响其余规则
            list.forEach { rule ->
                val wifiName = rule.wifiName
                // wifiName 为空 = 任意 WiFi 触发；非空 = 仅匹配指定 SSID
                val shouldTrigger = when {
                    wifiName.isEmpty() -> true  // 任意 WiFi 连接都触发
                    currentSsid == null -> {
                        // 无法获取当前 SSID（权限未授予 / 未真正连接），跳过 SSID 过滤
                        if (BuildConfig.DEBUG) Log.w("QL-WifiReceiver", "无法获取当前 SSID，跳过规则: ${rule.name}")
                        false
                    }
                    else -> {
                        val match = wifiName == currentSsid
                        if (BuildConfig.DEBUG) {
                            Log.i("QL-WifiReceiver", "规则「${rule.name}」SSID 匹配: 期望=$wifiName, 当前=$currentSsid, 结果=$match")
                        }
                        match
                    }
                }
                if (shouldTrigger) {
                    runCatching { LaunchService.start(app, rule.targetPackage, rule.targetAppName) }
                }
            }
        }
    }

    companion object {
        /** 重复调用安全：相同 PendingIntent 会覆盖旧注册。开机后与应用启动时各调一次即可。 */
        fun register(context: Context) {
            // registerNetworkCallback(request, PendingIntent) 需要 API 26+
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            // 系统要往 Intent 里塞 EXTRA_NETWORK，必须是 mutable（API 31 以下默认即可变）
            val mutableFlag =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(
                context, 0,
                Intent(context, WifiReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag
            )
            runCatching {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.registerNetworkCallback(request, pi)
            }
        }
    }
}
