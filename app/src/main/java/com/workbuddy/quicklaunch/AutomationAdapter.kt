package com.workbuddy.quicklaunch

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.workbuddy.quicklaunch.data.Automation
import com.workbuddy.quicklaunch.data.TriggerType
import com.workbuddy.quicklaunch.databinding.ItemAutomationBinding

/**
 * 主界面列表适配器：展示每条自动化，支持启用开关与删除。
 */
class AutomationAdapter(
    private val items: List<Automation>,
    private val onToggle: (Automation, Boolean) -> Unit,
    private val onDelete: (Automation) -> Unit
) : RecyclerView.Adapter<AutomationAdapter.VH>() {

    inner class VH(val b: ItemAutomationBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(ItemAutomationBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val a = items[position]
        holder.b.tvName.text = a.name
        holder.b.tvDesc.text = "${triggerLabel(a)} → ${a.targetAppName}"
        holder.b.switchEnabled.isChecked = a.enabled
        holder.b.switchEnabled.setOnCheckedChangeListener { _, checked -> onToggle(a, checked) }
        holder.b.btnDelete.setOnClickListener { onDelete(a) }
    }

    override fun getItemCount(): Int = items.size

    private fun triggerLabel(a: Automation): String = when (a.triggerType) {
        TriggerType.TIME ->
            "定时 %02d:%02d（%s".format(a.timeHour, a.timeMinute, repeatLabel(a.repeatMode))
        TriggerType.CHARGING -> "充电时"
        TriggerType.WIFI -> "连接 WiFi"
        else -> if (a.bluetoothName.isNotEmpty()) "连接蓝牙:${a.bluetoothName}" else "连接蓝牙"
    }

    private fun repeatLabel(mode: String): String = when (mode) {
        "daily" -> "每天)"
        "weekdays" -> "工作日)"
        "weekend" -> "周末)"
        else -> "一次)"
    }
}
