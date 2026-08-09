package com.workbuddy.quicklaunch.util

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * 蓝牙设备扫描工具：获取已配对设备列表。
 */
object BluetoothDevices {

    /** 获取已配对设备名称列表（需 BLUETOOTH_CONNECT 权限，Android 12+）。 */
    fun getPairedDeviceNames(context: Context): List<String> {
        val adapter: BluetoothAdapter? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            mgr?.adapter
        } else {
            @Suppress("DEPRECATION")
            BluetoothAdapter.getDefaultAdapter()
        }
        if (adapter == null || !adapter.isEnabled) return emptyList()

        // Android 12+ 运行时权限检查
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return emptyList()
        }

        return runCatching {
            adapter.bondedDevices
                .mapNotNull { device -> device.name }
                .sorted()
        }.getOrDefault(emptyList())
    }
}
