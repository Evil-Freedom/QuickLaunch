package com.workbuddy.quicklaunch.receiver

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        val name = device?.name ?: ""

        val list = AppDatabase.get(context).automationDao().getEnabledByType(TriggerType.BLUETOOTH)
        list.forEach {
            if (it.bluetoothName.isEmpty() || it.bluetoothName.equals(name, ignoreCase = true)) {
                LaunchService.start(context, it.targetPackage, it.targetAppName)
            }
        }
    }
}
