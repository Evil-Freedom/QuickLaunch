package com.workbuddy.quicklaunch

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.workbuddy.quicklaunch.databinding.ActivitySourceManageBinding
import com.workbuddy.quicklaunch.util.CustomSource
import com.workbuddy.quicklaunch.util.HolidayPrefs
import com.workbuddy.quicklaunch.util.HolidaySources
import com.workbuddy.quicklaunch.util.ParserType
import java.util.Locale
import java.util.UUID

/**
 * 管理节假日数据源：列出内置源与用户自定义源；用户可新增自定义源
 * （填名称、网址模板含「年份」标记、选择解析格式），删除自己的源。
 * 自定义源会被接入多源回退链，在主界面「同步法定节假日」时一起尝试。
 */
class SourceManageActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySourceManageBinding
    private val items = mutableListOf<Row>()

    /** 用一个普通年份测试网址格式是否合法，不关心具体是哪年。 */
    private companion object {
        const val URL_VALIDATION_YEAR = 2000
    }
    private val adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<RowVH>() {
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): RowVH {
            val v = layoutInflater.inflate(R.layout.item_source, parent, false)
            return RowVH(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: RowVH, position: Int) {
            val row = items[position]
            holder.label.text = row.label
            holder.url.text = row.url
            holder.delete.visibility = if (row.builtIn) View.GONE else View.VISIBLE
            holder.delete.setOnClickListener { removeCustom(row.id) }
            // Glassmorphism 重构：数据源状态指示灯 + Badge
            if (row.builtIn) {
                holder.statusDot.setBackgroundResource(R.drawable.status_dot_synced)
                holder.statusBadge.setBackgroundResource(R.drawable.bg_badge_success)
                holder.statusBadge.text = "已同步"
                holder.statusBadge.setTextColor(resources.getColor(R.color.glass_active_text, null))
            } else {
                holder.statusDot.setBackgroundResource(R.drawable.status_dot_unsynced)
                holder.statusBadge.setBackgroundResource(R.drawable.bg_badge_pending)
                holder.statusBadge.text = "未同步"
                holder.statusBadge.setTextColor(resources.getColor(R.color.glass_inactive_text, null))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySourceManageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter
        binding.fabAdd.setOnClickListener { addSource() }
        load()
    }

    private fun load() {
        items.clear()
        HolidaySources.ALL.forEach {
            items.add(Row(it.id, it.label, "内置源", "内置", true))
        }
        // 偏好里的 JSON 可能被外部改坏，读取失败时只展示内置源而不是白屏崩溃
        runCatching { HolidayPrefs.getCustomSources(this) }.getOrDefault(emptyList()).forEach {
            val parserName = if (it.parser == ParserType.TIMOR) getString(R.string.source_format_timor) else getString(R.string.source_format_holiday_cn)
            items.add(Row(it.id, it.label, it.urlTemplate, "自定义 · $parserName", false))
        }
        adapter.notifyDataSetChanged()
    }

    private fun addSource() {
        val labelInput = EditText(this).apply { hint = getString(R.string.source_name_hint) }
        val urlInput = EditText(this).apply { hint = getString(R.string.source_url_hint) }
        val parserSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@SourceManageActivity,
                android.R.layout.simple_spinner_item,
                listOf(getString(R.string.source_format_holiday_cn), getString(R.string.source_format_timor))
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        }
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
            addView(labelInput)
            addView(urlInput)
            addView(parserSpinner)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.source_add_custom))
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                val label = labelInput.text.toString().trim()
                val url = urlInput.text.toString().trim()
                if (label.isEmpty() || url.isEmpty()) {
                    android.widget.Toast.makeText(this, getString(R.string.source_empty_fields), android.widget.Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (!url.contains("【年份】")) {
                    android.widget.Toast.makeText(this, getString(R.string.source_url_need_year), android.widget.Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                // 校验协议与可解析性：非法网址曾会在同步时抛异常并中断整条回退链，
                // 现在同步侧已做容错，但在入口就拦住能给用户明确反馈。
                // 校验方法：把「年份」标记替换成一个普通年份（如 2000），看看能不能生成合法的网址格式
                val probe = url.replace("【年份】", URL_VALIDATION_YEAR.toString())
                val validUrl = runCatching { java.net.URL(probe).protocol.lowercase(Locale.US) }
                    .getOrNull()
                if (validUrl != "http" && validUrl != "https") {
                    android.widget.Toast.makeText(this, getString(R.string.source_url_invalid), android.widget.Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val parser = if (parserSpinner.selectedItemPosition == 1) ParserType.TIMOR else ParserType.NATE_SCARLET
                val list = HolidayPrefs.getCustomSources(this).toMutableList()
                list.add(CustomSource(UUID.randomUUID().toString(), label, url, parser))
                runCatching { HolidayPrefs.setCustomSources(this, list) }
                load()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun removeCustom(id: String) {
        runCatching {
            val list = HolidayPrefs.getCustomSources(this).filter { it.id != id }
            HolidayPrefs.setCustomSources(this, list)
        }
        load()
    }

    data class Row(val id: String, val label: String, val url: String, val tag: String, val builtIn: Boolean)

    class RowVH(v: android.view.View) :
        androidx.recyclerview.widget.RecyclerView.ViewHolder(v) {
        val label = v.findViewById<android.widget.TextView>(R.id.tvLabel)
        val url = v.findViewById<android.widget.TextView>(R.id.tvUrl)
        val delete = v.findViewById<android.widget.Button>(R.id.btnDelete)
        val statusDot = v.findViewById<android.view.View>(R.id.vStatusDot)
        val statusBadge = v.findViewById<android.widget.TextView>(R.id.tvStatusBadge)
    }
}
