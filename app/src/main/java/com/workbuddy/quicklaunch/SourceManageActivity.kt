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
    private val adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<RowVH>() {
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): RowVH {
            val v = layoutInflater.inflate(R.layout.item_source, parent, false)
            return RowVH(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: RowVH, position: Int) {
            val r = items[position]
            h.label.text = r.label
            h.url.text = r.url
            h.tag.text = r.tag
            h.delete.visibility = if (r.builtIn) View.GONE else View.VISIBLE
            h.delete.setOnClickListener { removeCustom(r.id) }
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
            val parserName = if (it.parser == ParserType.TIMOR) "timor.tech 格式" else "holiday-cn 格式"
            items.add(Row(it.id, it.label, it.urlTemplate, "自定义 · $parserName", false))
        }
        adapter.notifyDataSetChanged()
    }

    private fun addSource() {
        val labelInput = EditText(this).apply { hint = "名称，如 我的节假日API" }
        val urlInput = EditText(this).apply { hint = "https://example.com/【年份】.json" }
        val parserSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@SourceManageActivity,
                android.R.layout.simple_spinner_item,
                listOf("holiday-cn 格式", "timor.tech 格式")
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
            .setTitle("添加自定义数据源")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                val label = labelInput.text.toString().trim()
                val url = urlInput.text.toString().trim()
                if (label.isEmpty() || url.isEmpty()) {
                    android.widget.Toast.makeText(this, "名称和 URL 不能为空", android.widget.Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (!url.contains("【年份】")) {
                    android.widget.Toast.makeText(this, "网址里必须包含「年份」标记", android.widget.Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                // 校验协议与可解析性：非法网址曾会在同步时抛异常并中断整条回退链，
                // 现在同步侧已做容错，但在入口就拦住能给用户明确反馈。
                // 校验方法：把「年份」标记替换成 2000，看看能不能生成合法的网址格式
                val probe = url.replace("【年份】", "2000")
                val validUrl = runCatching { java.net.URL(probe).protocol.lowercase(Locale.US) }
                    .getOrNull()
                if (validUrl != "http" && validUrl != "https") {
                    android.widget.Toast.makeText(this, "URL 必须是 http:// 或 https:// 开头的合法地址", android.widget.Toast.LENGTH_SHORT).show()
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
        val tag = v.findViewById<android.widget.TextView>(R.id.tvTag)
        val delete = v.findViewById<android.widget.Button>(R.id.btnDelete)
    }
}
