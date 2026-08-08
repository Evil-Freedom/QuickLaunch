package com.workbuddy.quicklaunch.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.workbuddy.quicklaunch.databinding.ItemDockAppBinding

/**
 * iOS 26 Dock 栏横向列表适配器
 * - 大尺寸 Squircle 图标
 * - 按压缩放动画
 */
class DockAdapter(
    private val apps: List<AppAdapter.AppItem>,
    private val onDockClick: (AppAdapter.AppItem) -> Unit
) : RecyclerView.Adapter<DockAdapter.VH>() {

    inner class VH(val binding: ItemDockAppBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemDockAppBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = apps[position]
        holder.binding.tvDockName.text = item.appName

        // 按压动画
        holder.binding.root.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80).start()
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                }
            }
            false
        }

        holder.binding.root.setOnClickListener {
            onDockClick(item)
        }
    }

    override fun getItemCount() = apps.size
}
