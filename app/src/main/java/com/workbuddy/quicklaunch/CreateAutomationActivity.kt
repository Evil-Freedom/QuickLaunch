package com.workbuddy.quicklaunch

import android.app.TimePickerDialog
import android.os.Bundle
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
        binding.btnTime.setOnClickListener { showTimePicker() }
        binding.btnSave.setOnClickListener { save() }

        updateTriggerUi(binding.spinnerTrigger.selectedItemPosition)
    }

    private fun updateTriggerUi(pos: Int) {
        val isTime = pos == 0
        binding.layoutTime.visibility = if (isTime) android.view.View.VISIBLE else android.view.View.GONE
        binding.layoutBt.visibility =
            if (pos == 3) android.view.View.VISIBLE else android.view.View.GONE
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

    private fun showTimePicker() {
        TimePickerDialog(this, { _, h, m ->
            hour = h
            minute = m
            updateTimeLabel()
        }, hour, minute, true).show()
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
            else -> "daily"
        }

        val a = Automation(
            name = name,
            targetPackage = pkg,
            targetAppName = selectedAppName ?: "",
            triggerType = currentTriggerType(),
            timeHour = hour,
            timeMinute = minute,
            repeatMode = repeatKey,
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
