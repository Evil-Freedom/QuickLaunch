package com.workbuddy.quicklaunch.util

import android.content.Context
import android.view.View
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton
import com.workbuddy.quicklaunch.R
import com.workbuddy.quicklaunch.data.AppDatabase
import com.workbuddy.quicklaunch.data.Automation
import com.workbuddy.quicklaunch.data.TriggerType
import java.util.Calendar
import java.util.Locale

/**
 * 自动化规则表单控制器。
 *
 * 封装 MainActivity 与 CreateAutomationActivity 中重复的表单逻辑：
 * - 触发条件 / 重复模式 / 星期胶囊的状态管理
 * - 时间选择器 / 随机窗口选择器
 * - 应用选择
 * - 表单校验 + Automation 对象构建
 * - 表单重置
 *
 * 渲染由持有方通过 [FormCallbacks.onStateUiUpdate] 回调处理，
 * 以兼容两套布局（MainActivity 带图标 vs CreateAutomationActivity 纯文字胶囊）。
 */
class AutomationFormController(
    private val context: Context,
    private val db: AppDatabase,
    private val views: FormViews,
    private val callbacks: FormCallbacks,
    private val strings: FormStrings = FormStrings()
) {
    interface FormCallbacks {
        /** 表单状态变更后，Activity 刷新 chip/capsule 视觉。 */
        fun onStateUiUpdate()

        /** 应用已选择。 */
        fun onAppPicked(appName: String)

        /** 应用列表为空。 */
        fun onAppPickEmpty()

        /** 表单校验失败。 */
        fun onValidationError(messageResId: Int)

        /** 保存成功。 */
        fun onSaveSuccess()

        /** 保存失败。 */
        fun onSaveFailed()

        /** 表单已重置，Activity 刷新 UI。 */
        fun onFormReset()
    }

    data class FormStrings(
        val pickAppFirst: Int = R.string.create_pick_app_first,
        val pickDay: Int = R.string.create_pick_day,
        val defaultName: Int = R.string.create_default_name
    )

    data class FormViews(
        val btnPickApp: MaterialButton,
        val btnTime: TextView,
        val btnWinStart: TextView,
        val btnWinEnd: TextView,
        val cbRandom: CheckBox,
        val cbSkipHolidays: CheckBox,
        val layoutRandom: View,
        val layoutTime: View,
        val btnPickBluetooth: MaterialButton,
        val btnPickWifi: MaterialButton,
        val layoutCustomDays: View,
        val btnSave: MaterialButton,
        val triggerChips: List<TextView>,
        val repeatChips: List<TextView>,
        val dayViews: List<TextView>,
        val triggerIcons: List<Int>? = null
    )

    // ── 表单状态 ─────────────────────────────────────────────────
    var selectedTriggerIndex = 0
        private set
    var selectedRepeatIndex = 0
        private set
    val selectedDays = BooleanArray(7)
    var selectedPackage: String? = null
    var selectedAppName: String? = null
    var hour = 8
    var minute = 0
    var randomWindow = false
    var skipHolidays = false
    var winStartHour = 8
    var winStartMinute = 30
    var winEndHour = 8
    var winEndMinute = 50
    var bluetoothName: String = ""
    var wifiName: String = ""

    // ── 状态查询 ─────────────────────────────────────────────────
    fun currentTriggerType(): String = when (selectedTriggerIndex) {
        0 -> TriggerType.TIME
        1 -> TriggerType.CHARGING
        2 -> TriggerType.WIFI
        else -> TriggerType.BLUETOOTH
    }

    fun currentRepeatKey(): String = when (selectedRepeatIndex) {
        0 -> "daily"
        1 -> "weekdays"
        2 -> "weekend"
        3 -> "custom"
        4 -> "once"
        else -> "daily"
    }

    fun repeatDaysMask(): Int {
        var mask = 0
        for (i in 0..6) if (selectedDays[i]) mask = mask or (1 shl i)
        return mask
    }

    fun timeLabel(): String = String.format(Locale.US, "%02d:%02d", hour, minute)
    fun windowStartLabel(): String = String.format(Locale.US, "%02d:%02d", winStartHour, winStartMinute)
    fun windowEndLabel(): String = String.format(Locale.US, "%02d:%02d", winEndHour, winEndMinute)

    // ── 初始化 ───────────────────────────────────────────────────
    fun setup() {
        val now = Calendar.getInstance()
        hour = now.get(Calendar.HOUR_OF_DAY)
        minute = now.get(Calendar.MINUTE)

        views.triggerChips.forEachIndexed { index, textView ->
            textView.setOnClickListener { selectTrigger(index) }
            setupTriggerIcon(textView, index)
        }

        views.repeatChips.forEachIndexed { index, textView ->
            textView.setOnClickListener { selectRepeat(index) }
        }

        views.dayViews.forEachIndexed { idx, view ->
            view.setOnClickListener { toggleDay(idx) }
        }

        views.btnPickApp.setOnClickListener { pickApp() }
        views.btnTime.setOnClickListener { showTimePicker() }
        views.btnWinStart.setOnClickListener { showWindowPicker(true) }
        views.btnWinEnd.setOnClickListener { showWindowPicker(false) }
        views.btnSave.setOnClickListener { save() }

        views.cbRandom.setOnCheckedChangeListener { _, checked ->
            randomWindow = checked
            views.layoutRandom.visibility = if (checked) View.VISIBLE else View.GONE
            views.btnTime.visibility = if (checked) View.GONE else View.VISIBLE
        }
        views.cbSkipHolidays.setOnCheckedChangeListener { _, checked -> skipHolidays = checked }

        updateTimeLabel()
        updateWindowLabels()
        callbacks.onStateUiUpdate()
    }

    private fun setupTriggerIcon(textView: TextView, index: Int) {
        val icons = views.triggerIcons ?: return
        if (index >= icons.size) return
        val iconSize = context.resources.getDimensionPixelSize(R.dimen.trigger_grid_icon_size)
        val drawable = AppCompatResources.getDrawable(context, icons[index])?.mutate()
        drawable?.setBounds(0, 0, iconSize, iconSize)
        textView.setCompoundDrawables(null, drawable, null, null)
    }

    // ── 状态变更 ─────────────────────────────────────────────────
    fun selectTrigger(index: Int) {
        selectedTriggerIndex = index
        callbacks.onStateUiUpdate()
    }

    fun selectRepeat(index: Int) {
        selectedRepeatIndex = index
        callbacks.onStateUiUpdate()
    }

    fun toggleDay(index: Int) {
        selectedDays[index] = !selectedDays[index]
        callbacks.onStateUiUpdate()
    }

    fun setApp(packageName: String?, appName: String?) {
        selectedPackage = packageName
        selectedAppName = appName
        callbacks.onAppPicked(appName ?: "")
    }

    // ── 时间标签 ─────────────────────────────────────────────────
    fun updateTimeLabel() {
        views.btnTime.text = timeLabel()
    }

    fun updateWindowLabels() {
        views.btnWinStart.text = windowStartLabel()
        views.btnWinEnd.text = windowEndLabel()
    }

    // ── 时间选择器 ───────────────────────────────────────────────
    fun showTimePicker() {
        DarkWheelTimePicker.newInstance(hour, minute)
            .setOnConfirmListener { h, m ->
                hour = h
                minute = m
                updateTimeLabel()
            }
            .show((context as FragmentActivity).supportFragmentManager, "dark_wheel_time_picker")
    }

    fun showWindowPicker(isStart: Boolean) {
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
            .show((context as FragmentActivity).supportFragmentManager, "dark_wheel_window_picker")
    }

    // ── 应用选择 ─────────────────────────────────────────────────
    fun pickApp() {
        views.btnPickApp.isEnabled = false
        val activity = context as? FragmentActivity ?: return
        AppListLoader.loadAsync(context) { apps ->
            if (activity.isFinishing || activity.isDestroyed) return@loadAsync
            views.btnPickApp.isEnabled = true
            if (apps.isEmpty()) {
                callbacks.onAppPickEmpty()
                return@loadAsync
            }
            runCatching {
                AppPickerBottomSheet.newInstance(apps)
                    .setOnSelectedListener { app ->
                        setApp(app.packageName, app.appName)
                    }
                    .show(activity.supportFragmentManager, "app_picker")
            }
        }
    }

    // ── 保存 ─────────────────────────────────────────────────────
    fun save() {
        val pkg = selectedPackage
        if (pkg == null) {
            callbacks.onValidationError(strings.pickAppFirst)
            return
        }
        val repeatKey = currentRepeatKey()
        val mask = if (repeatKey == "custom") repeatDaysMask() else 0
        if (repeatKey == "custom" && mask == 0) {
            callbacks.onValidationError(strings.pickDay)
            return
        }

        val rawStart = winStartHour * 60 + winStartMinute
        val rawEnd = winEndHour * 60 + winEndMinute
        val (wsMin, weMin) = if (randomWindow) {
            Math.min(rawStart, rawEnd) to Math.max(rawStart, rawEnd)
        } else 0 to 0

        val automation = Automation(
            name = selectedAppName ?: context.getString(strings.defaultName),
            targetPackage = pkg,
            targetAppName = selectedAppName ?: "",
            triggerType = currentTriggerType(),
            timeHour = hour,
            timeMinute = minute,
            repeatMode = repeatKey,
            repeatDays = mask,
            skipHolidays = skipHolidays,
            randomWindow = randomWindow,
            windowStartMin = wsMin,
            windowEndMin = weMin,
            bluetoothName = bluetoothName,
            wifiName = wifiName,
        )

        views.btnSave.isEnabled = false
        val app = context.applicationContext
        QuickLaunchExecutors.save.execute {
            val ok = runCatching {
                val id = db.automationDao().insert(automation)
                if (automation.triggerType == TriggerType.TIME) {
                    Scheduler.schedule(app, automation.copy(id = id))
                }
            }.isSuccess
            val activity = context as? FragmentActivity
            activity?.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                views.btnSave.isEnabled = true
                if (ok) {
                    callbacks.onSaveSuccess()
                } else {
                    callbacks.onSaveFailed()
                }
            }
        }
    }

    // ── 重置 ─────────────────────────────────────────────────────
    fun reset() {
        selectedPackage = null
        selectedAppName = null
        selectedTriggerIndex = 0
        selectedRepeatIndex = 0
        selectedDays.fill(false)
        randomWindow = false
        skipHolidays = false
        bluetoothName = ""
        wifiName = ""
        val now = Calendar.getInstance()
        hour = now.get(Calendar.HOUR_OF_DAY)
        minute = now.get(Calendar.MINUTE)
        updateTimeLabel()
        updateWindowLabels()
        callbacks.onFormReset()
    }
}
