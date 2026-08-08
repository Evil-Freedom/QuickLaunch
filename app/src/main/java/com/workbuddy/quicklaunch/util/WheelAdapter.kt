package com.workbuddy.quicklaunch.util

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.workbuddy.quicklaunch.R
import java.util.Locale

/**
 * 滚轮单项适配器（无限循环模式）。
 *
 * 在真实数据前后各重复 N 个周期，使 RecyclerView 可以一直向上/向下滑动。
 * 小时（0..23）与分钟（0..59）均通过 position 取模映射到真实值，
 * 选中、点击、取值全部走 [valueAt] / [positionOf] 统一换算。
 */
class WheelAdapter(private val values: List<Int>) : RecyclerView.Adapter<WheelAdapter.VH>() {

    /** 每个真实值重复展示的周期数；500 周期 * 60 分钟 = 30000 项，足够顺滑滚动。 */
    private val cycles = 1000

    class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val tv = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_wheel, parent, false) as TextView
        return VH(tv)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.tv.text = String.format(Locale.US, "%02d", valueAt(position))
        holder.itemView.setOnClickListener {
            val rv = holder.itemView.parent as? RecyclerView
            val lm = rv?.layoutManager as? LinearLayoutManager
            lm?.scrollToPositionWithOffset(position, 0)
        }
    }

    override fun getItemCount(): Int = values.size * cycles

    /** 将任意 adapter position 映射到真实数值（循环取模）。 */
    fun valueAt(position: Int): Int {
        if (values.isEmpty()) return 0
        val size = values.size
        val idx = ((position % size) + size) % size
        return values[idx]
    }

    /** 找到一个居中的 adapter position，使其展示 [value]；用于初始化滚动位置。 */
    fun positionOf(value: Int): Int {
        if (values.isEmpty()) return itemCount / 2
        val size = values.size
        val normalized = ((value - values.first()) % size + size) % size
        val center = itemCount / 2
        val base = center - (center % size)
        return (base + normalized).coerceIn(0, itemCount - 1)
    }

    @Deprecated("Use valueAt(position) instead", ReplaceWith("valueAt(pos)"))
    fun getValue(pos: Int): Int = valueAt(pos)
}
