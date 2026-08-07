package com.workbuddy.quicklaunch

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.workbuddy.quicklaunch.data.AppDatabase
import com.workbuddy.quicklaunch.data.Holiday
import com.workbuddy.quicklaunch.databinding.ActivityHolidayManageBinding
import com.workbuddy.quicklaunch.util.Scheduler
import java.util.Calendar

/**
 * 手动管理节假日（离线兜底）：用户自行增删休息日，无需联网。
 * 改动后重新排程所有定时自动化，使「跳过节假日」立即生效。
 */
class HolidayManageActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHolidayManageBinding
    private lateinit var db: AppDatabase
    private val items = mutableListOf<Holiday>()
    private val adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<ViewHolder>() {
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val v = layoutInflater.inflate(R.layout.item_holiday, parent, false)
            return ViewHolder(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: ViewHolder, position: Int) {
            val h0 = items[position]
            h.date.text = h0.date
            h.name.text = h0.name
            h.delete.setOnClickListener { removeHoliday(h0.date) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHolidayManageBinding.inflate(layoutInflater)
        setContentView(binding.root)
        db = AppDatabase.get(this)

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter
        binding.fabAdd.setOnClickListener { addHoliday() }
        load()
    }

    private fun load() {
        items.clear()
        items.addAll(db.holidayDao().getAll())
        adapter.notifyDataSetChanged()
        binding.tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    /** 用 DatePicker 选日期，再可选填名称，写入 holidays 表。 */
    private fun addHoliday() {
        val cal = Calendar.getInstance()
        DatePickerDialog(
            this,
            { _, y, m, d ->
                val date = "%04d-%02d-%02d".format(y, m + 1, d)
                // 已存在则直接更新名称，不重复插入
                val name = db.holidayDao().getAll().firstOrNull { it.date == date }?.name ?: ""
                askName(date, name)
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun askName(date: String, current: String) {
        val input = EditText(this).apply {
            setText(current)
            hint = "名称（可选，如 元旦）"
        }
        AlertDialog.Builder(this)
            .setTitle("添加 $date")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val name = input.text.toString().trim()
                db.holidayDao().insertAll(listOf(Holiday(date = date, name = name)))
                load()
                Scheduler.rescheduleAll(this)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun removeHoliday(date: String) {
        db.holidayDao().deleteByDate(date)
        load()
        Scheduler.rescheduleAll(this)
    }

    /** 简单的 ViewHolder 包装，避免引入额外文件。 */
    class ViewHolder(v: android.view.View) :
        androidx.recyclerview.widget.RecyclerView.ViewHolder(v) {
        val date = v.findViewById<android.widget.TextView>(R.id.tvDate)
        val name = v.findViewById<android.widget.TextView>(R.id.tvName)
        val delete = v.findViewById<android.widget.Button>(R.id.btnDelete)
    }
}
