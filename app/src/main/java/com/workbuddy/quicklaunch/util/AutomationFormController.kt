package com.workbuddy.quicklaunch.util

import android.content.Context
import android.view.View
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.workbuddy.quicklaunch.R
import com.workbuddy.quicklaunch.data.AppDatabase
import com.workbuddy.quicklaunch.data.Automation
import com.workbuddy.quicklaunch.data.ShiftAnchor
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
        val triggerIcons: List<Int>? = null,
        // ── 班表模式控件（可选）──────────────────────────────────────
        // 全部可空：MainActivity 的快速创建布局没有这几个控件，
        // 传 null 时班表相关逻辑整体跳过，旧布局零改动即可编译运行。
        val cbFollowShift: CheckBox? = null,
        val layoutShiftOffset: View? = null,
        val btnShiftAnchor: TextView? = null,
        val btnShiftOffset: TextView? = null
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

    /** 触发时刻锚定方式，见 [ShiftAnchor]。FIXED 表示用上面的 hour/minute。 */
    var shiftAnchor = ShiftAnchor.FIXED
    /** 班表模式下的偏移分钟数，负数=提前。默认上班前 30 分钟。 */
    var shiftOffsetMin = -30

    // ── 状态查询 ─────────────────────────────────────────────────
    // 映射逻辑统一委托给 RuleFormMapper（纯函数、可单测），
    // 避免「下标→字符串」和「字符串→下标」两个方向各写一遍而走偏。
    fun currentTriggerType(): String = RuleFormMapper.triggerTypeOf(selectedTriggerIndex)

    fun currentRepeatKey(): String = RuleFormMapper.repeatKeyOf(selectedRepeatIndex)

    fun repeatDaysMask(): Int = RuleFormMapper.daysMaskOf(selectedDays)

    /**
     * 当前正在编辑的规则 id；为 null 表示处于「新建」模式。
     * 保存分支、返回结果、留档都以此为准，不必额外传参。
     */
    var editingId: Long? = null
        private set

    /** 进入编辑模式：把已有规则回填到表单。必须在 [setup] 之后调用。 */
    fun loadForEdit(a: Automation) {
        editingId = a.id

        selectedPackage = a.targetPackage
        selectedAppName = a.targetAppName
        selectedTriggerIndex = RuleFormMapper.triggerIndexOf(a.triggerType)
        selectedRepeatIndex = RuleFormMapper.repeatIndexOf(a.repeatMode)

        val days = RuleFormMapper.daysOf(a.repeatDays)
        for (i in 0..6) selectedDays[i] = days[i]

        hour = a.timeHour.coerceIn(0, 23)
        minute = a.timeMinute.coerceIn(0, 59)
        winStartHour = (a.windowStartMin / 60).coerceIn(0, 23)
        winStartMinute = (a.windowStartMin % 60).coerceIn(0, 59)
        winEndHour = (a.windowEndMin / 60).coerceIn(0, 23)
        winEndMinute = (a.windowEndMin % 60).coerceIn(0, 59)
        bluetoothName = a.bluetoothName
        wifiName = a.wifiName

        // 先写字段再同步勾选态：isChecked 只在值变化时才触发监听器，
        // 光靠 setOnCheckedChangeListener 回填是不可靠的（初始就是 false 时不会触发）。
        randomWindow = a.randomWindow
        skipHolidays = a.skipHolidays
        views.cbRandom.isChecked = a.randomWindow
        views.cbSkipHolidays.isChecked = a.skipHolidays
        views.layoutRandom.visibility = if (a.randomWindow) View.VISIBLE else View.GONE
        views.btnTime.visibility = if (a.randomWindow) View.GONE else View.VISIBLE

        // 班表字段同样先写状态再改勾选态；最后统一 applyShiftModeUi 收拾联动
        shiftAnchor = if (ShiftAnchor.isValid(a.shiftAnchor)) a.shiftAnchor else ShiftAnchor.FIXED
        shiftOffsetMin = a.shiftOffsetMin
        views.cbFollowShift?.isChecked = shiftAnchor != ShiftAnchor.FIXED
        applyShiftModeUi()

        updateTimeLabel()
        updateWindowLabels()
        // 让宿主刷新「已选择：xxx」按钮文案
        callbacks.onAppPicked(a.targetAppName.ifEmpty { a.name })
        callbacks.onStateUiUpdate()
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

        // ── 班表模式（仅当布局里存在这些控件时才接管）──────────────────
        views.cbFollowShift?.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                // 刚打开时若还没设过锚点，默认「上班前 30 分钟」——
                // 这是轮班用户最常见的诉求，省掉一次点选
                if (shiftAnchor == ShiftAnchor.FIXED) shiftAnchor = ShiftAnchor.CLOCK_IN
            } else {
                shiftAnchor = ShiftAnchor.FIXED
            }
            applyShiftModeUi()
        }
        views.btnShiftAnchor?.setOnClickListener { pickShiftAnchor() }
        views.btnShiftOffset?.setOnClickListener { pickShiftOffset() }

        updateTimeLabel()
        updateWindowLabels()
        updateShiftLabels()
        callbacks.onStateUiUpdate()
    }

    // ── 班表模式 ─────────────────────────────────────────────────

    /**
     * 班表模式与「固定时刻 / 随机窗口 / 跳过节假日」互斥。
     *
     * 这里选**禁用**而不是隐藏：隐藏会让用户以为自己的设置被清空了，
     * 而禁用能让他看到「这些选项还在，只是当前由班表决定」，切回来也不会丢值。
     */
    private fun applyShiftModeUi() {
        val on = shiftAnchor != ShiftAnchor.FIXED
        views.layoutShiftOffset?.visibility = if (on) View.VISIBLE else View.GONE
        views.cbRandom.isEnabled = !on
        views.cbSkipHolidays.isEnabled = !on
        views.btnTime.isEnabled = !on
        views.btnTime.alpha = if (on) 0.4f else 1f
        if (on && randomWindow) {
            // 随机窗口和「跟随班次」都在决定时刻，同时开着只会有一个生效，
            // 与其让用户猜哪个赢，不如直接关掉随机窗口
            randomWindow = false
            views.cbRandom.isChecked = false
            views.layoutRandom.visibility = View.GONE
            views.btnTime.visibility = View.VISIBLE
        }
        updateShiftLabels()
        callbacks.onStateUiUpdate()
    }

    fun shiftAnchorLabel(): String =
        if (shiftAnchor == ShiftAnchor.CLOCK_OUT) "下班" else "上班"

    fun shiftOffsetLabel(): String = when {
        shiftOffsetMin == 0 -> "准时"
        shiftOffsetMin < 0 -> "提前 ${-shiftOffsetMin} 分钟"
        else -> "延后 $shiftOffsetMin 分钟"
    }

    fun updateShiftLabels() {
        views.btnShiftAnchor?.text = shiftAnchorLabel()
        views.btnShiftOffset?.text = shiftOffsetLabel()
    }

    private fun pickShiftAnchor() {
        val labels = arrayOf("上班时", "下班时")
        val checked = if (shiftAnchor == ShiftAnchor.CLOCK_OUT) 1 else 0
        MaterialAlertDialogBuilder(context)
            .setTitle("锚定哪个时刻")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                shiftAnchor = if (which == 1) ShiftAnchor.CLOCK_OUT else ShiftAnchor.CLOCK_IN
                // 锚点换了，原来的偏移方向大概率不再合理：上班前 30 分钟 → 下班前 30 分钟
                // 仍是合法的，所以只刷新标签，不强行改偏移
                updateShiftLabels()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun pickShiftOffset() {
        // 预设比再做一个时间轮省事，也覆盖了「提前打卡」这类真实场景
        val offsets = intArrayOf(-60, -30, -15, 0, 15, 30, 60)
        val labels = offsets.map { off ->
            when {
                off == 0 -> "准时（班次时刻）"
                off < 0 -> "提前 ${-off} 分钟"
                else -> "延后 $off 分钟"
            }
        }.toTypedArray()
        val checked = offsets.indexOf(shiftOffsetMin).let { if (it >= 0) it else 1 }
        MaterialAlertDialogBuilder(context)
            .setTitle("相对班次时刻")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                shiftOffsetMin = offsets[which]
                updateShiftLabels()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
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
        // 班表模式下星期位图不参与判定，没勾也不影响触发，这里不该拦
        if (repeatKey == "custom" && mask == 0 && shiftAnchor == ShiftAnchor.FIXED) {
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
            // 班表模式下重复模式交给班表决定，但字段仍保留：用户切回固定时刻时
            // 原来的星期选择还在，不用重设一遍
            shiftAnchor = shiftAnchor,
            shiftOffsetMin = if (shiftAnchor == ShiftAnchor.FIXED) 0 else shiftOffsetMin,
        )

        views.btnSave.isEnabled = false
        val app = context.applicationContext
        val editing = editingId
        QuickLaunchExecutors.save.execute {
            val ok = runCatching {
                if (editing == null) {
                    // ── 新建 ──
                    val id = db.automationDao().insert(automation)
                    if (automation.triggerType == TriggerType.TIME) {
                        Scheduler.schedule(app, automation.copy(id = id))
                    }
                } else {
                    // ── 修改 ──
                    val old = db.automationDao().getById(editing)
                    if (old == null) {
                        // 规则已被别处删除（列表与编辑页不同步的竞态）。
                        // 退化成新建，保住用户刚填的内容，而不是丢进黑洞。
                        val id = db.automationDao().insert(automation)
                        if (automation.triggerType == TriggerType.TIME) {
                            Scheduler.schedule(app, automation.copy(id = id))
                        }
                    } else {
                        // 修改前留档，供「撤销」把旧值原样写回
                        RuleBackupStore.save(app, old)

                        // ⚠️ enabled 与 createdAt 必须沿用旧值，不能取 Automation 的默认值：
                        //   enabled 默认 true  → 编辑一条已停用的规则会把它悄悄重新启用
                        //   createdAt 默认 now → 列表按 createdAt DESC 排序，编辑后规则会跳到最上面
                        val merged = automation.copy(
                            id = editing,
                            enabled = old.enabled,
                            createdAt = old.createdAt
                        )
                        db.automationDao().update(merged)

                        // 旧闹钟先撤再排：触发条件从「定时」改成 WiFi/蓝牙时，
                        // 原 PendingIntent 的 requestCode 只认 id，不撤会留下一个幽灵闹钟。
                        Scheduler.cancel(app, merged)
                        if (merged.enabled && merged.triggerType == TriggerType.TIME) {
                            Scheduler.schedule(app, merged)
                        }
                    }
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
        editingId = null
        selectedPackage = null
        selectedAppName = null
        selectedTriggerIndex = 0
        selectedRepeatIndex = 0
        selectedDays.fill(false)
        randomWindow = false
        skipHolidays = false
        bluetoothName = ""
        wifiName = ""
        shiftAnchor = ShiftAnchor.FIXED
        shiftOffsetMin = -30
        views.cbFollowShift?.isChecked = false
        applyShiftModeUi()
        val now = Calendar.getInstance()
        hour = now.get(Calendar.HOUR_OF_DAY)
        minute = now.get(Calendar.MINUTE)
        updateTimeLabel()
        updateWindowLabels()
        callbacks.onFormReset()
    }
}
