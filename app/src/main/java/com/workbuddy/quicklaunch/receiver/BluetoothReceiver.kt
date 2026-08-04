package com.workbuddy.quicklaunch.receiver

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.workbuddy.quicklaunch.data.AppDatabase
import com.workbuddy.quicklaunch.data.TriggerType
import com.workbuddy.quicklaunch.service.LaunchService

/**
 * 蓝牙触发：当有蓝牙设备连上时拉起目标 App；
 * 若规则里填了设备名，则仅匹配该名称的设备。
 */
class BluetoothReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        }
        // 未授予 BLUETOOTH_CONNECT 时读 name 会抛 SecurityException，取不到就当作「任意设备」
        val name = runCatching { device?.name }.getOrNull().orEmpty()

        val list = AppDatabase.get(context).automationDao().getEnabledByType(TriggerType.BLUETOOTH)
        list.forEach {
            if (it.bluetoothName.isEmpty() || it.bluetoothName.equals(name, ignoreCase = true)) {
                LaunchService.start(context, it.targetPackage, it.targetAppName)
            }
        }
    }
}
