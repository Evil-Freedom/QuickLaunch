package com.workbuddy.quicklaunch.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.workbuddy.quicklaunch.R
import com.workbuddy.quicklaunch.util.DevicePickerBottomSheet

/**
 * 设备选择列表适配器 — 用于 DevicePickerBottomSheet。
 *
 * 列表项：设备图标 + 设备名称 + 选中对勾。
 * 支持蓝牙设备 / WiFi 网络两种模式（图标不同）。
 */
class DevicePickerAdapter(
    private val mode: Int,
    private var devices: List<String>,
    private var selectedName: String?,
    private val onSelected: (String?) -> Unit
) : RecyclerView.Adapter<DevicePickerAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val ivIcon: ImageView = view.findViewById(R.id.ivIcon)
        val tvName: TextView = view.findViewById(R.id.tvName)
        val ivCheck: ImageView = view.findViewById(R.id.ivCheck)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device_select, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val name = devices[position]
        holder.tvName.text = name
        holder.ivIcon.setImageResource(
            if (mode == DevicePickerBottomSheet.MODE_BLUETOOTH) R.drawable.ic_trigger_bluetooth
            else R.drawable.ic_trigger_wifi
        )
        holder.ivCheck.visibility = if (name == selectedName) View.VISIBLE else View.GONE

        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos in 0 until itemCount) {
                onSelected(devices[pos])
            }
        }
    }

    override fun getItemCount(): Int = devices.size

    fun updateList(newDevices: List<String>) {
        devices = newDevices
        notifyDataSetChanged()
    }
}
