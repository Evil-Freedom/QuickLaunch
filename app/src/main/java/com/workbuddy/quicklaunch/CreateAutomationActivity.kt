package com.workbuddy.quicklaunch

import android.app.TimePickerDialog
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.workbuddy.quicklaunch.data.AppDatabase
import com.workbuddy.quicklaunch.data.Automation
import com.workbuddy.quicklaunch.data.TriggerType
import com.workbuddy.quicklaunch.databinding.ActivityCreateBinding
import com.workbuddy.quicklaunch.util.AppListLoader
import com.workbuddy.quicklaunch.util.Scheduler
import java.util.Calendar

class CreateAutomationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCreateBinding
    private lateinit var db: AppDatabase

    private var selectedPackage: String? = null
    private var selectedAppName: String? = null
    private var hour = 8
    private var minute = 0
    private var randomWindow = false
    private var skipHolidays = false
    private var winStartHour = 8
    private var winStartMinute = 30
    private var winEndHour = 8
    private var winEndMinute = 50

    /** 自定义星期选中状态：index = Calendar.DAY_OF_WEEK - 1（0=日 … 6=六） */
    private val selectedDays = BooleanArray(7)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityCreateBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // targetSdk 35+ 强制边到边，不消费 insets 表单顶部会被状态栏压住
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        db = AppDatabase.get(this)

        val now = Calendar.getInstance()
        hour = now.get(Calendar.HOUR_OF_DAY)
        minute = now.get(Calendar.MINUTE)
        updateTimeLabel()

        binding.btnPickApp.setOnClickListener { pickApp() }
        binding.spinnerTrigger.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>, v: android.view.View?, pos: Int, id: Long) {
                    updateTriggerUi(pos)
                }
                override fun onNothingSelected(p: android.widget.AdapterView<*>) {}
            }
        binding.spinnerRepeat.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>, v: android.view.View?, pos: Int, id: Long) {
                    syncCustomDaysVisibility()
                }
                override fun onNothingSelected(p: AdapterView<*>) {}
            }
        // 星期多选开关：index 与位图对齐（0=日 … 6=六）
        listOf(
            binding.tbDay0, binding.tbDay1, binding.tbDay2, binding.tbDay3,
            binding.tbDay4, binding.tbDay5, binding.tbDay6
        ).forEachIndexed { idx, tb ->
            tb.setOnCheckedChangeListener { _, checked -> selectedDays[idx] = checked }
        }
        binding.btnTime.setOnClickListener { showTimePicker() }
        binding.btnSave.setOnClickListener { save() }

        // 随机窗口：勾选后显示起止时间，并隐藏固定时间按钮（二者互斥）
        binding.cbRandom.setOnCheckedChangeListener { _, checked ->
            randomWindow = checked
            binding.layoutRandom.visibility = if (checked) android.view.View.VISIBLE else android.view.View.GONE
            binding.btnTime.visibility = if (checked) android.view.View.GONE else android.view.View.VISIBLE
        }
        binding.btnWinStart.setOnClickListener { showWindowPicker(true) }
        binding.btnWinEnd.setOnClickListener { showWindowPicker(false) }
        binding.cbSkipHolidays.setOnCheckedChangeListener { _, checked -> skipHolidays = checked }
        updateWindowLabels()

        updateTriggerUi(binding.spinnerTrigger.selectedItemPosition)
    }

    private fun updateTriggerUi(pos: Int) {
        val isTime = pos == 0
        binding.layoutTime.visibility = if (isTime) android.view.View.VISIBLE else android.view.View.GONE
        binding.cbRandom.visibility = if (isTime) android.view.View.VISIBLE else android.view.View.GONE
        binding.cbSkipHolidays.visibility = if (isTime) android.view.View.VISIBLE else android.view.View.GONE
        if (!isTime) {
            binding.layoutRandom.visibility = android.view.View.GONE
            binding.btnTime.visibility = android.view.View.VISIBLE
            binding.cbRandom.isChecked = false
            binding.cbSkipHolidays.isChecked = false
            binding.layoutCustomDays.visibility = android.view.View.GONE
            randomWindow = false
            skipHolidays = false
        } else {
            syncCustomDaysVisibility()
        }
        binding.layoutBt.visibility =
            if (pos == 3) android.view.View.VISIBLE else android.view.View.GONE
    }

    /** 仅当选中「自定义」重复模式时显示星期多选，否则隐藏。 */
    private fun syncCustomDaysVisibility() {
        val isCustom = binding.spinnerRepeat.selectedItem?.toString() == "自定义"
        binding.layoutCustomDays.visibility = if (isCustom) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun currentTriggerType(): String = when (binding.spinnerTrigger.selectedItemPosition) {
        0 -> TriggerType.TIME
        1 -> TriggerType.CHARGING
        2 -> TriggerType.WIFI
        else -> TriggerType.BLUETOOTH
    }

    private fun updateTimeLabel() {
        binding.btnTime.text = String.format("%02d:%02d", hour, minute)
    }

    private fun updateWindowLabels() {
        binding.btnWinStart.text = String.format("%02d:%02d", winStartHour, winStartMinute)
        binding.btnWinEnd.text = String.format("%02d:%02d", winEndHour, winEndMinute)
    }

    private fun showTimePicker() {
        TimePickerDialog(this, { _, h, m ->
            hour = h
            minute = m
            updateTimeLabel()
        }, hour, minute, true).show()
    }

    private fun showWindowPicker(isStart: Boolean) {
        val (h, m) = if (isStart) winStartHour to winStartMinute else winEndHour to winEndMinute
        TimePickerDialog(this, { _, pickedH, pickedM ->
            if (isStart) {
                winStartHour = pickedH
                winStartMinute = pickedM
            } else {
                winEndHour = pickedH
                winEndMinute = pickedM
            }
            updateWindowLabels()
        }, h, m, true).show()
    }

    private fun pickApp() {
        val apps = AppListLoader.load(this)
        if (apps.isEmpty()) {
            toast("未找到可启动的应用")
            return
        }
        val names = apps.map { it.appName }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择应用")
            .setItems(names) { _, i ->
                selectedPackage = apps[i].packageName
                selectedAppName = apps[i].appName
                binding.btnPickApp.text = "已选择：$selectedAppName"
            }
            .show()
    }

    private fun save() {
        val pkg = selectedPackage
        if (pkg == null) {
            toast("请先选择要启动的应用")
            return
        }
        val name = binding.etName.text.toString().ifBlank { selectedAppName ?: "自动化" }
        val repeatKey = when (binding.spinnerRepeat.selectedItem?.toString()) {
            "工作日" -> "weekdays"
            "周末" -> "weekend"
            "一次性" -> "once"
            "自定义" -> "custom"
            else -> "daily"
        }
        val repeatDaysMask = if (repeatKey == "custom") {
            var mask = 0
            for (i in 0..6) if (selectedDays[i]) mask = mask or (1 shl i)
            mask
        } else 0
        if (repeatKey == "custom" && repeatDaysMask == 0) {
            toast("请至少选择一天")
            return
        }

        val rawStart = winStartHour * 60 + winStartMinute
        val rawEnd = winEndHour * 60 + winEndMinute
        val (wsMin, weMin) = if (randomWindow) {
            Math.min(rawStart, rawEnd) to Math.max(rawStart, rawEnd)
        } else (0 to 0)

        val a = Automation(
            name = name,
            targetPackage = pkg,
            targetAppName = selectedAppName ?: "",
            triggerType = currentTriggerType(),
            timeHour = hour,
            timeMinute = minute,
            repeatMode = repeatKey,
            repeatDays = repeatDaysMask,
            skipHolidays = skipHolidays,
            randomWindow = randomWindow,
            windowStartMin = wsMin,
            windowEndMin = weMin,
            bluetoothName = binding.etBtName.text.toString().trim()
        )

        val id = db.automationDao().insert(a)
        if (a.triggerType == TriggerType.TIME) {
            Scheduler.schedule(this, a.copy(id = id))
        }
        toast("已保存")
        finish()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
