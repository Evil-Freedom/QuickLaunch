package com.workbuddy.quicklaunch

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.workbuddy.quicklaunch.R
import com.workbuddy.quicklaunch.data.Automation
import com.workbuddy.quicklaunch.data.RepeatMode
import com.workbuddy.quicklaunch.data.TriggerType
import com.workbuddy.quicklaunch.databinding.ItemAutomationBinding
import com.workbuddy.quicklaunch.util.QuickLaunchExecutors
import java.util.Locale

/**
 * 主界面列表适配器：展示每条自动化，支持启用开关与删除。
 *
 * 性能优化：使用 ListAdapter + DiffUtil 做增量刷新，
 * 避免规则数增长后 notifyDataSetChanged 导致全量重绘。
 */
class AutomationAdapter(
    private val onToggle: (Automation, Boolean) -> Unit,
    private val onDelete: (Automation) -> Unit
) : ListAdapter<Automation, AutomationAdapter.VH>(DIFF_CALLBACK) {

    inner class VH(val b: ItemAutomationBinding) : RecyclerView.ViewHolder(b.root)

    // 共享图标缓存（与 AppPickerAdapter 统一）
    private val iconCache = object : LruCache<String, Drawable>(50) {}
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemAutomationBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val a = getItem(position)
        val ctx = holder.b.root.context
        holder.b.tvName.text = a.name
        holder.b.tvDesc.text = "${triggerLabel(a)} → ${a.targetAppName}"

        // Glassmorphism 重构：异步加载应用图标
        holder.b.ivAppIcon.setImageDrawable(null)
        val pm = ctx.packageManager
        val cached = iconCache.get(a.targetPackage)
        if (cached != null) {
            holder.b.ivAppIcon.setImageDrawable(cached)
        } else {
            // 后台取真实图标，回主线程安全设置
            QuickLaunchExecutors.io.execute {
                val icon = runCatching { pm.getApplicationIcon(a.targetPackage) }.getOrNull()
                    ?: return@execute
                iconCache.put(a.targetPackage, icon)
                mainHandler.post {
                    if (holder.bindingAdapterPosition == position) {
                        holder.b.ivAppIcon.setImageDrawable(icon)
                    }
                }
            }
        }

        // Glassmorphism 重构：语义化胶囊标签
        holder.b.tagTrigger.text = triggerLabel(a)

        // 状态色点 + 光晕：启用 = 薰衣草紫光晕 + 实心点，停用 = 灰色无光晕
        holder.b.vStatusGlow.setBackgroundResource(statusGlowRes(a.enabled))
        holder.b.vStatusDot.setBackgroundResource(statusDotRes(a.enabled))
        // 必须先摘掉旧监听：复用 ViewHolder 时 isChecked 赋值会触发上一条规则的回调，导致误改数据
        holder.b.switchEnabled.setOnCheckedChangeListener(null)
        holder.b.switchEnabled.isChecked = a.enabled
        holder.b.switchEnabled.setOnCheckedChangeListener { _, checked -> onToggle(a, checked) }
        holder.b.btnDelete.setOnClickListener { onDelete(a) }
    }

    /** 状态色点资源：启用薰衣草紫 / 停用灰。 */
    private fun statusDotRes(enabled: Boolean): Int =
        if (enabled) R.drawable.status_dot_on else R.drawable.status_dot_off

    /** 状态光晕资源：启用薰衣草紫柔光 / 停用灰。 */
    private fun statusGlowRes(enabled: Boolean): Int =
        if (enabled) R.drawable.status_glow_on else R.drawable.status_glow_off

    private fun triggerLabel(a: Automation): String = when (a.triggerType) {
        TriggerType.TIME ->
            // 显式给 Locale，避免依赖系统默认 locale 产生非预期数字形态
            String.format(
                Locale.US, "定时 %02d:%02d（%s）%s",
                a.timeHour, a.timeMinute, repeatLabel(a),
                if (a.skipHolidays) "·跳假" else ""
            )
        TriggerType.CHARGING -> "充电时"
        TriggerType.WIFI -> if (a.wifiName.isNotEmpty()) "连接 WiFi:${a.wifiName}" else "连接 WiFi"
        else -> if (a.bluetoothName.isNotEmpty()) "连接蓝牙:${a.bluetoothName}" else "连接蓝牙"
    }

    private fun repeatLabel(a: Automation): String = when (a.repeatMode) {
        RepeatMode.DAILY -> "每天"
        RepeatMode.WEEKDAYS -> "工作日"
        RepeatMode.WEEKEND -> "周末"
        RepeatMode.CUSTOM -> customDaysLabel(a.repeatDays)
        else -> "一次"
    }

    private val DAY_LABELS = arrayOf("日", "一", "二", "三", "四", "五", "六")

    /** 自定义星期位图 -> 可读串，例如 自定义(一三五)；mask 异常时回退「自定义」。 */
    private fun customDaysLabel(mask: Int): String {
        if (mask == 0) return "自定义"
        val picks = (0..6).filter { (mask shr it) and 1 == 1 }.joinToString("") { DAY_LABELS[it] }
        return if (picks.isEmpty()) "自定义" else "自定义($picks)"
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Automation>() {
            override fun areItemsTheSame(oldItem: Automation, newItem: Automation): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: Automation, newItem: Automation): Boolean =
                oldItem == newItem
        }
    }
}
