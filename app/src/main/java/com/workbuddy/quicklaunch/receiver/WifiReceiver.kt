package com.workbuddy.quicklaunch.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.NetworkInfo
import android.net.wifi.WifiManager
import com.workbuddy.quicklaunch.data.AppDatabase
import com.workbuddy.quicklaunch.data.TriggerType
import com.workbuddy.quicklaunch.service.LaunchService

/**
 * WiFi 触发：当设备连上 WLAN 时拉起目标 App。
 *
 * 说明：不同厂商 / 系统版本对「清单里静态注册网络广播」的支持略有差异，
 * 多数设备可用；若某机型不生效，可在 README 中查看替代方案。
 */
class WifiReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val netInfo = intent.getParcelableExtra<NetworkInfo>(WifiManager.EXTRA_NETWORK_INFO)
        if (netInfo != null && netInfo.isConnected) {
            val list = AppDatabase.get(context).automationDao().getEnabledByType(TriggerType.WIFI)
            list.forEach { LaunchService.start(context, it.targetPackage, it.targetAppName) }
        }
    }
}
