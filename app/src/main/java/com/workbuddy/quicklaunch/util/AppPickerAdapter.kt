package com.workbuddy.quicklaunch.util

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.workbuddy.quicklaunch.R
import java.util.concurrent.Executors

/**
 * 应用选择列表适配器。
 *
 * - 列表项 36dp 真实图标 + 15sp 应用名
 * - 图标在主线程之外（共享 2 线程池）加载，避免几百个应用逐个 getApplicationIcon 卡 UI
 * - 按 packageName 缓存 Drawable，过滤/复用时直接命中
 * - 回到主线程再设置图标，并用 bindingAdapterPosition 校验防止滚动复用串图
 */
class AppPickerAdapter(
    private val pm: PackageManager,
    private val onItemClick: (AppInfo) -> Unit
) : RecyclerView.Adapter<AppPickerAdapter.VH>() {

    private var items: List<AppInfo> = emptyList()
    private val executor = QuickLaunchExecutors.io
    private val mainHandler = Handler(Looper.getMainLooper())
    private val iconCache = mutableMapOf<String, Drawable>()

    class VH(
        val root: android.view.View,
        val ivIcon: ImageView,
        val tvAppName: TextView
    ) : RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app_select, parent, false)
        return VH(
            view,
            view.findViewById(R.id.ivIcon),
            view.findViewById(R.id.tvAppName)
        )
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val app = items[position]
        holder.tvAppName.text = app.appName
        // 先清空，避免复用旧 ViewHolder 时短暂串图
        holder.ivIcon.setImageDrawable(null)

        holder.root.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos in items.indices) onItemClick(items[pos])
        }

        val cached = iconCache[app.packageName]
        if (cached != null) {
            holder.ivIcon.setImageDrawable(cached)
            return
        }
        // 后台取真实图标，回主线程安全设置
        executor.execute {
            val icon = runCatching { pm.getApplicationIcon(app.packageName) }.getOrNull()
                ?: return@execute
            iconCache[app.packageName] = icon
            mainHandler.post {
                if (holder.bindingAdapterPosition == position) {
                    holder.ivIcon.setImageDrawable(icon)
                }
            }
        }
    }

    override fun getItemCount(): Int = items.size

    fun setItems(newItems: List<AppInfo>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun getItem(position: Int): AppInfo = items[position]

    val currentItems: List<AppInfo> get() = items
}
