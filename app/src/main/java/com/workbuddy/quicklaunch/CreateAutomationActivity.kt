package com.workbuddy.quicklaunch

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.workbuddy.quicklaunch.data.AppDatabase
import com.workbuddy.quicklaunch.data.Automation
import com.workbuddy.quicklaunch.data.TriggerType
import com.workbuddy.quicklaunch.databinding.ActivityCreateBinding
import com.workbuddy.quicklaunch.util.AppListLoader
import com.workbuddy.quicklaunch.util.AppPickerBottomSheet
import com.workbuddy.quicklaunch.util.DarkWheelTimePicker
import com.workbuddy.quicklaunch.util.QuickLaunchExecutors
import com.workbuddy.quicklaunch.util.Scheduler
import java.util.Calendar
import java.util.Locale

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

    /** 触发条件胶囊（平铺内嵌）：0=定时 1=充电 2=WiFi 3=蓝牙 */
    private val triggerChips = mutableListOf<TextView>()
    private var selectedTriggerIndex = 0

    /** 重复模式胶囊（平铺内嵌）：0=每天 1=工作日 2=周末 3=自定义 4=一次性 */
    private val repeatChips = mutableListOf<TextView>()
    private var selectedRepeatIndex = 0

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

        setupTriggerChips()
        setupRepeatChips()
        setupDayCapsules()

        binding.btnTime.setOnClickListener { showTimePicker() }
        binding.btnSave.setOnClickListener { save() }

        // 随机窗口：勾选后显示起止时间，并隐藏固定时间按钮（二者互斥）
        binding.cbRandom.setOnCheckedChangeListener { _, checked ->
            randomWindow = checked
            binding.layoutRandom.visibility = if (checked) View.VISIBLE else View.GONE
            binding.btnTime.visibility = if (checked) View.GONE else View.VISIBLE
        }
        binding.btnWinStart.setOnClickListener { showWindowPicker(true) }
        binding.btnWinEnd.setOnClickListener { showWindowPicker(false) }
        binding.cbSkipHolidays.setOnCheckedChangeListener { _, checked -> skipHolidays = checked }
        updateWindowLabels()

        updateTriggerUi()
    }

    /** 触发条件胶囊初始化：点击切换选中态。 */
    private fun setupTriggerChips() {
        triggerChips.clear()
        triggerChips.addAll(
            listOf(
                binding.chipTrigger0,
                binding.chipTrigger1,
                binding.chipTrigger2,
                binding.chipTrigger3
            )
        )
        triggerChips.forEachIndexed { index, textView ->
            textView.setOnClickListener {
                selectedTriggerIndex = index
                updateTriggerUi()
            }
        }
        selectedTriggerIndex = 0
    }

    /** 重复模式胶囊初始化：点击切换选中态。 */
    private fun setupRepeatChips() {
        repeatChips.clear()
        repeatChips.addAll(
            listOf(
                binding.chipRepeat0,
                binding.chipRepeat1,
                binding.chipRepeat2,
                binding.chipRepeat3,
                binding.chipRepeat4
            )
        )
        repeatChips.forEachIndexed { index, textView ->
            textView.setOnClickListener {
                selectedRepeatIndex = index
                updateRepeatUi()
            }
        }
        selectedRepeatIndex = 0
    }

    /** 星期胶囊点击切换：index 与二进制位对齐（0=日 … 6=六） */
    private fun setupDayCapsules() {
        val dayViews = listOf(
            binding.tbDay0, binding.tbDay1, binding.tbDay2, binding.tbDay3,
            binding.tbDay4, binding.tbDay5, binding.tbDay6
        )
        dayViews.forEachIndexed { idx, view ->
            view.setOnClickListener {
                selectedDays[idx] = !selectedDays[idx]
                refreshDayCapsule(view, selectedDays[idx])
            }
        }
    }

    private fun refreshDayCapsule(view: TextView, selected: Boolean) {
        view.setBackgroundResource(
            if (selected) R.drawable.bg_dark_capsule_selected
            else R.drawable.bg_dark_capsule_unselected
        )
        view.setTextColor(
            if (selected) resources.getColor(R.color.dark_bg_primary, null)
            else resources.getColor(R.color.dark_text_secondary, null)
        )
    }

    /** 统一设置胶囊选中/未选中视觉。 */
    private fun refreshChip(textView: TextView, selected: Boolean) {
        if (selected) {
            textView.setBackgroundResource(R.drawable.bg_dark_capsule_selected)
            textView.setTextColor(resources.getColor(R.color.dark_bg_primary, null))
            textView.setTypeface(null, android.graphics.Typeface.BOLD)
        } else {
            textView.setBackgroundResource(R.drawable.bg_dark_capsule_unselected)
            textView.setTextColor(resources.getColor(R.color.dark_text_secondary, null))
            textView.setTypeface(null, android.graphics.Typeface.NORMAL)
        }
    }

    /** 触发条件切换：控制时间设置、随机窗口、跳过节假日、蓝牙名称的显隐。 */
    private fun updateTriggerUi() {
        triggerChips.forEachIndexed { index, textView ->
            refreshChip(textView, index == selectedTriggerIndex)
        }

        val isTime = selectedTriggerIndex == 0
        binding.layoutTime.visibility = if (isTime) View.VISIBLE else View.GONE
        binding.cbRandom.visibility = if (isTime) View.VISIBLE else View.GONE
        binding.cbSkipHolidays.visibility = if (isTime) View.VISIBLE else View.GONE
        if (!isTime) {
            binding.layoutRandom.visibility = View.GONE
            binding.btnTime.visibility = View.VISIBLE
            binding.cbRandom.isChecked = false
            binding.cbSkipHolidays.isChecked = false
            randomWindow = false
            skipHolidays = false
        }
        updateRepeatUi()
        binding.layoutBt.visibility =
            if (selectedTriggerIndex == 3) View.VISIBLE else View.GONE
    }

    /** 重复模式切换：仅「自定义」显示星期多选。 */
    private fun updateRepeatUi() {
        repeatChips.forEachIndexed { index, textView ->
            refreshChip(textView, index == selectedRepeatIndex)
        }
        val isCustom = selectedRepeatIndex == 3
        binding.layoutCustomDays.visibility = if (isCustom) View.VISIBLE else View.GONE
    }

    private fun currentTriggerType(): String = when (selectedTriggerIndex) {
        0 -> TriggerType.TIME
        1 -> TriggerType.CHARGING
        2 -> TriggerType.WIFI
        else -> TriggerType.BLUETOOTH
    }

    private fun currentRepeatKey(): String = when (selectedRepeatIndex) {
        0 -> "daily"
        1 -> "weekdays"
        2 -> "weekend"
        3 -> "custom"
        4 -> "once"
        else -> "daily"
    }

    // 固定 Locale.US：某些语言环境下 %02d 会输出本地数字，时间标签变成乱码般的字符
    private fun updateTimeLabel() {
        binding.btnTime.text = String.format(Locale.US, "%02d:%02d", hour, minute)
    }

    private fun updateWindowLabels() {
        binding.btnWinStart.text = String.format(Locale.US, "%02d:%02d", winStartHour, winStartMinute)
        binding.btnWinEnd.text = String.format(Locale.US, "%02d:%02d", winEndHour, winEndMinute)
    }

    private fun showTimePicker() {
        DarkWheelTimePicker.newInstance(hour, minute)
            .setOnConfirmListener { h, m ->
                hour = h
                minute = m
                updateTimeLabel()
            }
            .show(supportFragmentManager, "dark_wheel_time_picker")
    }

    private fun showWindowPicker(isStart: Boolean) {
        val (h, m) = if (isStart) winStartHour to winStartMinute else winEndHour to winEndMinute
        DarkWheelTimePicker.newInstance(h, m)
            .setOnConfirmListener { pickedH, pickedM ->
                if (isStart) {
                    winStartHour = pickedH
                    winStartMinute = pickedM
                } else {
                    winEndHour = pickedH
                    winEndMinute = pickedM
                }
                updateWindowLabels()
            }
            .show(supportFragmentManager, "dark_wheel_window_picker")
    }

    /**
     * 选择应用。加载列表要对每个应用做 loadLabel（读 APK 资源），几百个应用能耗时 1~2 秒，
     * 原来直接在主线程跑会明显卡顿甚至 ANR，改为后台加载 + 回到主线程弹 BottomSheet。
     * 暗黑风格：带真实图标 + 吸顶搜索框的 BottomSheetDialog。
     */
    private fun pickApp() {
        binding.btnPickApp.isEnabled = false
        AppListLoader.loadAsync(this) { apps ->
            if (isFinishing || isDestroyed) return@loadAsync
            binding.btnPickApp.isEnabled = true
            if (apps.isEmpty()) {
                toast(getString(R.string.create_no_apps))
                return@loadAsync
            }
            runCatching {
                AppPickerBottomSheet.newInstance(apps)
                    .setOnSelectedListener { app ->
                        selectedPackage = app.packageName
                        selectedAppName = app.appName
                        binding.btnPickApp.text = "已选择：$selectedAppName"
                    }
                    .show(supportFragmentManager, "app_picker")
            }
        }
    }

    private fun save() {
        val pkg = selectedPackage
        if (pkg == null) {
            toast(getString(R.string.create_pick_app_first))
            return
        }
        val name = selectedAppName ?: getString(R.string.create_default_name)
        val repeatKey = currentRepeatKey()
        val repeatDaysMask = if (repeatKey == "custom") {
            var mask = 0
            for (i in 0..6) if (selectedDays[i]) mask = mask or (1 shl i)
            mask
        } else 0
        if (repeatKey == "custom" && repeatDaysMask == 0) {
            toast(getString(R.string.create_pick_day))
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

        // 写库 + 排程都是阻塞操作，放后台；避免重复点击保存出现重复规则
        binding.btnSave.isEnabled = false
        val app = applicationContext
        QuickLaunchExecutors.save.execute {
            val ok = runCatching {
                val id = db.automationDao().insert(a)
                if (a.triggerType == TriggerType.TIME) {
                    Scheduler.schedule(app, a.copy(id = id))
                }
            }.isSuccess
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (ok) {
                    toast(getString(R.string.create_saved))
                    finish()
                } else {
                    binding.btnSave.isEnabled = true
                    toast(getString(R.string.create_save_failed))
                }
            }
        }
    }

    private fun toast(msg: String) {
        runCatching { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    private companion object {
        // saveExecutor 迁移至 QuickLaunchExecutors.save
    }
}
