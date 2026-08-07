package com.workbuddy.quicklaunch

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.workbuddy.quicklaunch.data.Automation
import com.workbuddy.quicklaunch.data.TriggerType
import com.workbuddy.quicklaunch.databinding.ItemAutomationBinding
import java.util.Locale

/**
 * 主界面列表适配器：展示每条自动化，支持启用开关与删除。
 */
class AutomationAdapter(
    private var items: List<Automation>,
    private val onToggle: (Automation, Boolean) -> Unit,
    private val onDelete: (Automation) -> Unit
) : RecyclerView.Adapter<AutomationAdapter.VH>() {

    inner class VH(val b: ItemAutomationBinding) : RecyclerView.ViewHolder(b.root)

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<Automation>) {
        items = list
        notifyDataSetChanged() // ponytail: 规则数量个位数，上 DiffUtil 不划算
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemAutomationBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val a = items[position]
        holder.b.tvName.text = a.name
        holder.b.tvDesc.text = "${triggerLabel(a)} → ${a.targetAppName}"
        // 必须先摘掉旧监听：复用 ViewHolder 时 isChecked 赋值会触发上一条规则的回调，导致误改数据
        holder.b.switchEnabled.setOnCheckedChangeListener(null)
        holder.b.switchEnabled.isChecked = a.enabled
        holder.b.switchEnabled.setOnCheckedChangeListener { _, checked -> onToggle(a, checked) }
        holder.b.btnDelete.setOnClickListener { onDelete(a) }
    }

    override fun getItemCount(): Int = items.size

    private fun triggerLabel(a: Automation): String = when (a.triggerType) {
        TriggerType.TIME ->
            // 显式给 Locale，避免依赖系统默认 locale 产生非预期数字形态
            String.format(
                Locale.US, "定时 %02d:%02d（%s）%s",
                a.timeHour, a.timeMinute, repeatLabel(a),
                if (a.skipHolidays) "·跳假" else ""
            )
        TriggerType.CHARGING -> "充电时"
        TriggerType.WIFI -> "连接 WiFi"
        else -> if (a.bluetoothName.isNotEmpty()) "连接蓝牙:${a.bluetoothName}" else "连接蓝牙"
    }

    private fun repeatLabel(a: Automation): String = when (a.repeatMode) {
        "daily" -> "每天"
        "weekdays" -> "工作日"
        "weekend" -> "周末"
        "custom" -> customDaysLabel(a.repeatDays)
        else -> "一次"
    }

    private val DAY_LABELS = arrayOf("日", "一", "二", "三", "四", "五", "六")

    /** 自定义星期位图 -> 可读串，例如 自定义(一三五)；mask 异常时回退「自定义」。 */
    private fun customDaysLabel(mask: Int): String {
        if (mask == 0) return "自定义"
        val picks = (0..6).filter { (mask shr it) and 1 == 1 }.joinToString("") { DAY_LABELS[it] }
        return if (picks.isEmpty()) "自定义" else "自定义($picks)"
    }
}
