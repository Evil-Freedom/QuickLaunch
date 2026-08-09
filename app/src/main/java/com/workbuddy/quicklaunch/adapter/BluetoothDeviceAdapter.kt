package com.workbuddy.quicklaunch.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.workbuddy.quicklaunch.R
import com.workbuddy.quicklaunch.databinding.ItemBluetoothDeviceBinding

/**
 * 蓝牙设备列表适配器：显示已配对设备，支持单选高亮。
 */
class BluetoothDeviceAdapter(
    private var devices: List<String>,
    private var selectedName: String?,
    private val onSelected: (String?) -> Unit
) : RecyclerView.Adapter<BluetoothDeviceAdapter.VH>() {

    inner class VH(val b: ItemBluetoothDeviceBinding) : RecyclerView.ViewHolder(b.root)

    fun setSelected(name: String?) {
        selectedName = name
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemBluetoothDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val name = devices[position]
        holder.b.tvDeviceName.text = name
        val isSelected = name == selectedName
        holder.b.ivCheck.visibility = if (isSelected) View.VISIBLE else View.GONE
        holder.b.tvDeviceName.setTextColor(
            holder.b.root.context.resources.getColor(
                if (isSelected) R.color.item_active_text else R.color.text_white, null
            )
        )
        holder.b.ivDeviceIcon.setColorFilter(
            holder.b.root.context.resources.getColor(
                if (isSelected) R.color.item_active_text else R.color.item_inactive_text, null
            )
        )
        holder.b.root.setBackgroundResource(
            if (isSelected) R.drawable.bg_dark_capsule_selected else R.drawable.bg_dark_capsule_unselected
        )
        holder.b.root.setOnClickListener {
            val newSel = if (selectedName == name) null else name
            onSelected(newSel)
        }
    }

    override fun getItemCount(): Int = devices.size
}
