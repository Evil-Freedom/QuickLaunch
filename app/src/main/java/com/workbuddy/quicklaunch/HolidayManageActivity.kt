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
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 手动管理节假日（离线兜底）：用户自行增删休息日，无需联网。
 * 改动后重新排程所有定时自动化，使「跳过节假日」立即生效。
 */
class HolidayManageActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHolidayManageBinding
    private lateinit var db: AppDatabase
    private val items = mutableListOf<Holiday>()

    /** 单线程后台队列：DB 与重排程都不能放在主线程。守护线程，Activity 销毁后不阻止进程退出。 */
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "holiday-manage-io").apply { isDaemon = true }
    }

    private val adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<ViewHolder>() {
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val v = layoutInflater.inflate(R.layout.item_holiday, parent, false)
            return ViewHolder(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: ViewHolder, position: Int) {
            val holiday = items[position]
            h.date.text = holiday.date
            h.name.text = holiday.name
            h.delete.setOnClickListener { removeHoliday(holiday.date) }
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

    /** 后台执行；executor 已关闭时静默丢弃，避免因「线程池拒绝执行」异常导致崩溃。 */
    private fun runIo(block: () -> Unit) {
        runCatching { io.execute { runCatching(block) } }
    }

    /** 回主线程执行；Activity 已销毁则丢弃，避免因访问已销毁界面导致崩溃。 */
    private fun postUi(block: () -> Unit) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            runCatching(block)
        }
    }

    private fun load() {
        runIo {
            val all = db.holidayDao().getAll()
            postUi {
                items.clear()
                items.addAll(all)
                adapter.notifyDataSetChanged()
                binding.tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    /** 用 DatePicker 选日期，再可选填名称，写入 holidays 表。 */
    private fun addHoliday() {
        val cal = Calendar.getInstance()
        DatePickerDialog(
            this,
            { _, y, m, d ->
                // 必须固定 Locale.US：阿拉伯/波斯等 locale 默认会输出非 ASCII 数字，
                // 生成的 key 与 HolidayChecker.dateKey 永远匹配不上，跳过节假日会静默失效。
                val date = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d)
                // 已存在则沿用原名称，不重复插入（date 为主键，insert 走 REPLACE）
                runIo {
                    val name = db.holidayDao().getAll().firstOrNull { it.date == date }?.name ?: ""
                    postUi { askName(date, name) }
                }
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
                val app = applicationContext
                runIo {
                    db.holidayDao().insertAll(listOf(Holiday(date = date, name = name)))
                    Scheduler.rescheduleAll(app)
                    load()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun removeHoliday(date: String) {
        val app = applicationContext
        runIo {
            db.holidayDao().deleteByDate(date)
            Scheduler.rescheduleAll(app)
            load()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()   // 释放后台线程，避免 Activity 反复进出堆积线程
    }

    /** 简单的 ViewHolder 包装，避免引入额外文件。 */
    class ViewHolder(v: android.view.View) :
        androidx.recyclerview.widget.RecyclerView.ViewHolder(v) {
        val date = v.findViewById<android.widget.TextView>(R.id.tvDate)
        val name = v.findViewById<android.widget.TextView>(R.id.tvName)
        val delete = v.findViewById<android.widget.Button>(R.id.btnDelete)
    }
}
