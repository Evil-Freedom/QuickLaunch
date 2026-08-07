package com.workbuddy.quicklaunch.receiver

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
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
        // Android 12+ 读 BluetoothDevice.name 需要 BLUETOOTH_CONNECT 运行时权限，
        // 未授权时会抛 SecurityException。这里先显式判权限：
        // 拿不到名字就只能匹配「任意设备」规则，指定了设备名的规则一律不触发（fail-closed），
        // 避免把「连上任意耳机」误判成「连上指定耳机」。
        val name = readDeviceName(context, device)
        if (name.isEmpty()) {
            Log.w(TAG, "取不到蓝牙设备名（缺 BLUETOOTH_CONNECT 权限或系统未提供），仅匹配任意设备规则")
        }

        val app = context.applicationContext
        ReceiverWorker.run(this, TAG) {
            val list = runCatching {
                AppDatabase.get(app).automationDao().getEnabledByType(TriggerType.BLUETOOTH)
            }.getOrDefault(emptyList())
            list.forEach {
                if (it.bluetoothName.isEmpty() || it.bluetoothName.equals(name, ignoreCase = true)) {
                    runCatching { LaunchService.start(app, it.targetPackage, it.targetAppName) }
                }
            }
        }
    }

    /** 先判权限再读名字；SuppressLint 是在显式判权限之后给的，不是掩盖问题。 */
    @SuppressLint("MissingPermission")
    private fun readDeviceName(context: Context, device: BluetoothDevice?): String {
        if (device == null) return ""
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return ""
        return runCatching { device.name }.getOrNull().orEmpty()
    }

    private companion object {
        const val TAG = "BluetoothReceiver"
    }
}
