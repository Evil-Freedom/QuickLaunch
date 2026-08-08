package com.workbuddy.quicklaunch.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.recyclerview.widget.RecyclerView
import com.workbuddy.quicklaunch.R
import com.workbuddy.quicklaunch.databinding.ItemAppBinding

/**
 * iOS 26 应用网格适配器
 * - Squircle 超椭圆图标
 * - 按压缩放动画
 * - 长按弹出毛玻璃上下文菜单
 */
class AppAdapter(
    private val apps: List<AppItem>,
    private val onAppClick: (AppItem) -> Unit,
    private val onAppLongClick: (AppItem, View) -> Unit
) : RecyclerView.Adapter<AppAdapter.VH>() {

    data class AppItem(
        val packageName: String,
        val appName: String,
        val iconRes: Int = 0
    )

    inner class VH(val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemAppBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = apps[position]
        holder.binding.tvAppName.text = item.appName

        // 图标按压动画（iOS 风格微缩放）
        holder.binding.root.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(100).start()
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                }
            }
            false
        }

        // 点击启动
        holder.binding.root.setOnClickListener {
            onAppClick(item)
        }

        // 长按弹出上下文菜单
        holder.binding.root.setOnLongClickListener { view ->
            onAppLongClick(item, view)
            true
        }
    }

    override fun getItemCount() = apps.size
}
