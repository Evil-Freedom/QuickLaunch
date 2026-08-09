package com.workbuddy.quicklaunch

import android.Manifest
import android.app.AlarmManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.workbuddy.quicklaunch.data.AppDatabase
import com.workbuddy.quicklaunch.data.Automation
import com.workbuddy.quicklaunch.data.TriggerType
import com.workbuddy.quicklaunch.databinding.ActivityMainBinding
import com.workbuddy.quicklaunch.databinding.ViewLaunchBinding
import com.workbuddy.quicklaunch.databinding.ViewSyncBinding
import com.workbuddy.quicklaunch.receiver.WifiReceiver
import com.workbuddy.quicklaunch.service.KeepAliveService
import com.workbuddy.quicklaunch.util.AntiSleep
import com.workbuddy.quicklaunch.util.AppListLoader
import com.workbuddy.quicklaunch.util.AppPickerBottomSheet
import com.workbuddy.quicklaunch.util.DarkWheelTimePicker
import com.workbuddy.quicklaunch.util.HolidayPrefs
import com.workbuddy.quicklaunch.util.HolidaySources
import com.workbuddy.quicklaunch.util.HolidaySync
import com.workbuddy.quicklaunch.util.QuickLaunchExecutors
import com.workbuddy.quicklaunch.util.RootUtils
import com.workbuddy.quicklaunch.util.Scheduler
import com.workbuddy.quicklaunch.util.ScreenOnOverlay
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 主入口：底部双 Tab 导航，彻底取消二级页跳转。
 * - Tab 1 快捷启动：规则创建表单 + 规则列表平铺在同一页。
 * - Tab 2 同步源：法定节假日同步、数据源选择、手动管理、自定义源管理。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var db: AppDatabase

    // ---------- Tab 容器（独立 inflate 后注入 ViewPager2，见 setupViewPager） ----------
    private lateinit var launchBinding: ViewLaunchBinding
    private lateinit var syncBinding: ViewSyncBinding

    // ---------- 底部悬浮导航 ----------
    private lateinit var bottomNavLaunch: View
    private lateinit var bottomNavSync: View
    private lateinit var ivNavLaunch: ImageView
    private lateinit var ivNavSync: ImageView
    private lateinit var tvNavLaunch: TextView
    private lateinit var tvNavSync: TextView

    // ---------- 规则列表 ----------
    private lateinit var rvRules: RecyclerView
    private lateinit var layoutRulesEmpty: View
    private val ruleAdapter = AutomationAdapter(emptyList(), ::onToggle, ::onDelete)

    // ---------- 仪表盘 ----------
    private lateinit var layoutDashboard: View
    private lateinit var tvDashboardEnabled: TextView
    private lateinit var tvDashboardNext: TextView

    // ---------- 创建表单 ----------
    private lateinit var btnPickApp: MaterialButton
    private lateinit var btnTime: TextView
    private lateinit var btnWinStart: TextView
    private lateinit var btnWinEnd: TextView
    private lateinit var cbRandom: CheckBox
    private lateinit var cbSkipHolidays: CheckBox
    private lateinit var layoutRandom: View
    private lateinit var layoutTime: View
    private lateinit var layoutBt: View
    private lateinit var layoutCustomDays: View
    private lateinit var etBtName: TextView
    private lateinit var btnSaveRule: MaterialButton

    private val triggerChips = mutableListOf<TextView>()
    private val triggerIcons = listOf(
        R.drawable.ic_trigger_time,
        R.drawable.ic_trigger_charging,
        R.drawable.ic_trigger_wifi,
        R.drawable.ic_trigger_bluetooth
    )
    private val repeatChips = mutableListOf<TextView>()
    private val dayViews = mutableListOf<TextView>()
    private var selectedTriggerIndex = 0
    private var selectedRepeatIndex = 0
    private val selectedDays = BooleanArray(7)

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

    // ---------- 同步源 ----------
    private lateinit var spinnerSource: Spinner
    private lateinit var btnSyncHolidays: MaterialButton
    private lateinit var btnManageHolidays: MaterialButton
    private lateinit var btnManageSources: MaterialButton
    private var sourceIds: List<String> = emptyList()

    // ---------- 同步源页面卡片 ----------
    private lateinit var layoutAntiSleep: View
    private lateinit var layoutHolidayCard: View

    // ---------- 防息屏 ----------
    private lateinit var tvAntiSleep: TextView
    private lateinit var swAntiSleep: SwitchCompat

    // ---------- 线程 ----------
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "main-io").apply { isDaemon = true }
    }

    private fun runIo(block: () -> Unit) {
        runCatching { io.execute { runCatching(block) } }
    }

    private fun postUi(block: () -> Unit) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            runCatching(block)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()

        db = AppDatabase.get(this)
        WifiReceiver.register(this)

        launchBinding = ViewLaunchBinding.inflate(layoutInflater)
        syncBinding = ViewSyncBinding.inflate(layoutInflater)
        setupViewPager()
        setupLaunchTab()
        setupSyncTab()
        setupAntiSleep() // 必须在 setupSyncTab 之后，因需引用 swAntiSleep / tvAntiSleep

        KeepAliveService.start(this)
        checkPermissionsOnce()

        // 首次启动（本地无节假日数据）自动同步一次，便于「跳过节假日」立即生效。
        runIo {
            val empty = runCatching { db.holidayDao().count() == 0 }.getOrDefault(false)
            if (empty) postUi { syncHolidays() }
        }
    }

    /** targetSdk 35+ 起系统强制边到边显示，不消费 insets 内容会被状态栏和导航栏压住。 */
    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootContainer) { v, insets ->
            val bars: Insets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 底部悬浮毛玻璃底栏 + ViewPager2
    // 不复用原 launchContainer / syncContainer（已从 activity_main 移除），
    // 直接 inflate 两份独立页面注入 ViewPager2；launchBinding / syncBinding
    // 上的全部表单、列表、开关逻辑零改动。
    // ═══════════════════════════════════════════════════════════════════

    private fun setupViewPager() {
        val pages = listOf(launchBinding.root, syncBinding.root)
        binding.viewPager.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            private val items = pages
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val v = items[viewType]
                v.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                return object : RecyclerView.ViewHolder(v) {}
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {}
            override fun getItemCount(): Int = items.size
            override fun getItemViewType(position: Int): Int = position
        }
        setupFloatingBottomBar()
    }

    private fun setupFloatingBottomBar() {
        bottomNavLaunch = binding.bottomNavLaunch
        bottomNavSync = binding.bottomNavSync
        ivNavLaunch = binding.ivNavLaunch
        ivNavSync = binding.ivNavSync
        tvNavLaunch = binding.tvNavLaunch
        tvNavSync = binding.tvNavSync

        bottomNavLaunch.setOnClickListener { binding.viewPager.currentItem = TAB_LAUNCH }
        bottomNavSync.setOnClickListener { binding.viewPager.currentItem = TAB_SYNC }
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                applyBottomNavStyle(position == TAB_LAUNCH)
                if (position == TAB_SYNC) {
                    setupSourceSpinner()
                    syncAntiSleepUi()
                } else {
                    refreshRules()
                }
            }
        })
        applyBottomNavStyle(true)

        // ⚠️ Round 10 修复：移除底部导航 blur — RenderEffect 会糊掉子 View 文字
        // 毛玻璃拟态仅靠背景色透明度（#1FFFFFFF 等）实现，不施加 View 级模糊
    }

    private fun applyBottomNavStyle(isLaunch: Boolean) {
        val activeText = resources.getColor(R.color.item_active_text, null)
        val inactiveText = resources.getColor(R.color.item_inactive_text, null)

        bottomNavLaunch.setBackgroundResource(
            if (isLaunch) R.drawable.bg_item_active else R.drawable.bg_item_inactive
        )
        ivNavLaunch.setColorFilter(if (isLaunch) activeText else inactiveText)
        tvNavLaunch.setTextColor(if (isLaunch) activeText else inactiveText)
        tvNavLaunch.setTypeface(null, if (isLaunch) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)

        bottomNavSync.setBackgroundResource(
            if (isLaunch) R.drawable.bg_item_inactive else R.drawable.bg_item_active
        )
        ivNavSync.setColorFilter(if (isLaunch) inactiveText else activeText)
        tvNavSync.setTextColor(if (isLaunch) inactiveText else activeText)
        tvNavSync.setTypeface(null, if (isLaunch) android.graphics.Typeface.NORMAL else android.graphics.Typeface.BOLD)
    }

    // ═══════════════════════════════════════════════════════════════════
    // Tab 1：快捷启动（创建表单 + 规则列表）
    // ═══════════════════════════════════════════════════════════════════

    private fun setupLaunchTab() {
        // 仪表盘
        layoutDashboard = launchBinding.layoutDashboard
        tvDashboardEnabled = launchBinding.tvDashboardEnabled
        tvDashboardNext = launchBinding.tvDashboardNext

        // 规则列表
        rvRules = launchBinding.rvRules
        layoutRulesEmpty = launchBinding.layoutRulesEmpty
        rvRules.layoutManager = LinearLayoutManager(this)
        rvRules.adapter = ruleAdapter
        rvRules.overScrollMode = View.OVER_SCROLL_NEVER

        // 创建表单 View
        btnPickApp = launchBinding.btnPickApp
        btnTime = launchBinding.btnTime
        btnWinStart = launchBinding.btnWinStart
        btnWinEnd = launchBinding.btnWinEnd
        cbRandom = launchBinding.cbRandom
        cbSkipHolidays = launchBinding.cbSkipHolidays
        layoutRandom = launchBinding.layoutRandom
        layoutTime = launchBinding.layoutTime
        layoutBt = launchBinding.layoutBt
        layoutCustomDays = launchBinding.layoutCustomDays
        etBtName = launchBinding.etBtName
        btnSaveRule = launchBinding.btnSaveRule

        triggerChips.clear()
        triggerChips.addAll(
            listOf(
                launchBinding.chipTrigger0,
                launchBinding.chipTrigger1,
                launchBinding.chipTrigger2,
                launchBinding.chipTrigger3
            )
        )
        repeatChips.clear()
        repeatChips.addAll(
            listOf(
                launchBinding.chipRepeat0,
                launchBinding.chipRepeat1,
                launchBinding.chipRepeat2,
                launchBinding.chipRepeat3,
                launchBinding.chipRepeat4
            )
        )
        dayViews.clear()
        dayViews.addAll(
            listOf(
                launchBinding.tbDay0,
                launchBinding.tbDay1,
                launchBinding.tbDay2,
                launchBinding.tbDay3,
                launchBinding.tbDay4,
                launchBinding.tbDay5,
                launchBinding.tbDay6
            )
        )

        // 初始化时间
        val now = Calendar.getInstance()
        hour = now.get(Calendar.HOUR_OF_DAY)
        minute = now.get(Calendar.MINUTE)
        updateTimeLabel()

        setupTriggerChips()
        setupRepeatChips()
        setupDayCapsules()

        btnPickApp.setOnClickListener { pickApp() }
        btnTime.setOnClickListener { showTimePicker() }
        btnSaveRule.setOnClickListener { saveRule() }

        cbRandom.setOnCheckedChangeListener { _, checked ->
            randomWindow = checked
            layoutRandom.visibility = if (checked) View.VISIBLE else View.GONE
            btnTime.visibility = if (checked) View.GONE else View.VISIBLE
        }
        btnWinStart.setOnClickListener { showWindowPicker(true) }
        btnWinEnd.setOnClickListener { showWindowPicker(false) }
        cbSkipHolidays.setOnCheckedChangeListener { _, checked -> skipHolidays = checked }
        updateWindowLabels()
        updateTriggerUi()
        refreshRules()

        // ⚠️ Round 10 修复：移除全部 View 级 RenderEffect 模糊
        // 毛玻璃拟态仅靠背景色透明度（#1FFFFFFF / #0FFFFFFF / #0DFFFFFF）实现
        // RenderEffect 会模糊整个 View 的子树，导致文字和图标全部变糊
    }

    private fun setupTriggerChips() {
        val iconSize = resources.getDimensionPixelSize(R.dimen.trigger_grid_icon_size)
        triggerChips.forEachIndexed { index, textView ->
            textView.setOnClickListener {
                selectedTriggerIndex = index
                updateTriggerUi()
            }
            // XML 层 drawableTint 已移除，改由代码强制 20dp 并动态着色
            val drawable = AppCompatResources.getDrawable(this, triggerIcons[index])?.mutate()
            drawable?.setBounds(0, 0, iconSize, iconSize)
            textView.setCompoundDrawables(null, drawable, null, null)
        }
        selectedTriggerIndex = 0
    }

    private fun setupRepeatChips() {
        repeatChips.forEachIndexed { index, textView ->
            textView.setOnClickListener {
                selectedRepeatIndex = index
                updateRepeatUi()
            }
        }
        selectedRepeatIndex = 0
    }

    private fun setupDayCapsules() {
        dayViews.forEachIndexed { idx, view ->
            view.setOnClickListener {
                selectedDays[idx] = !selectedDays[idx]
                refreshDayCapsule(view, selectedDays[idx])
            }
        }
    }

    private fun refreshDayCapsule(view: TextView, selected: Boolean) {
        view.setBackgroundResource(
            if (selected) R.drawable.bg_dark_capsule_selected else R.drawable.bg_dark_capsule_unselected
        )
        view.setTextColor(
            if (selected) resources.getColor(R.color.item_active_text, null)
            else resources.getColor(R.color.item_inactive_text, null)
        )
    }

    private fun refreshTriggerChip(textView: TextView, selected: Boolean) {
        if (selected) {
            textView.setBackgroundResource(R.drawable.bg_trigger_grid_item_selected)
            textView.setTextColor(resources.getColor(R.color.item_active_text, null))
            textView.setTypeface(null, android.graphics.Typeface.BOLD)
        } else {
            textView.setBackgroundResource(R.drawable.bg_trigger_grid_item)
            textView.setTextColor(resources.getColor(R.color.item_inactive_text, null))
            textView.setTypeface(null, android.graphics.Typeface.NORMAL)
        }
        // 同步刷新顶部图标颜色（代码层强制 20dp 尺寸）
        textView.compoundDrawables[1]?.setTint(
            resources.getColor(
                if (selected) R.color.item_active_text else R.color.item_inactive_text,
                null
            )
        )
    }

    private fun refreshRepeatChip(textView: TextView, selected: Boolean) {
        if (selected) {
            textView.setBackgroundResource(R.drawable.bg_dark_capsule_selected)
            textView.setTextColor(resources.getColor(R.color.item_active_text, null))
            textView.setTypeface(null, android.graphics.Typeface.BOLD)
        } else {
            textView.setBackgroundResource(R.drawable.bg_dark_capsule_unselected)
            textView.setTextColor(resources.getColor(R.color.item_inactive_text, null))
            textView.setTypeface(null, android.graphics.Typeface.NORMAL)
        }
    }

    private fun updateTriggerUi() {
        triggerChips.forEachIndexed { index, textView ->
            refreshTriggerChip(textView, index == selectedTriggerIndex)
        }
        val isTime = selectedTriggerIndex == 0
        layoutTime.visibility = if (isTime) View.VISIBLE else View.GONE
        cbRandom.visibility = if (isTime) View.VISIBLE else View.GONE
        cbSkipHolidays.visibility = if (isTime) View.VISIBLE else View.GONE
        if (!isTime) {
            layoutRandom.visibility = View.GONE
            btnTime.visibility = View.VISIBLE
            cbRandom.isChecked = false
            cbSkipHolidays.isChecked = false
            randomWindow = false
            skipHolidays = false
        }
        updateRepeatUi()
        layoutBt.visibility = if (selectedTriggerIndex == 3) View.VISIBLE else View.GONE
    }

    private fun updateRepeatUi() {
        repeatChips.forEachIndexed { index, textView ->
            refreshRepeatChip(textView, index == selectedRepeatIndex)
        }
        val isCustom = selectedRepeatIndex == 3
        layoutCustomDays.visibility = if (isCustom) View.VISIBLE else View.GONE
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

    private fun updateTimeLabel() {
        btnTime.text = String.format(Locale.US, "%02d:%02d", hour, minute)
    }

    private fun updateWindowLabels() {
        btnWinStart.text = String.format(Locale.US, "%02d:%02d", winStartHour, winStartMinute)
        btnWinEnd.text = String.format(Locale.US, "%02d:%02d", winEndHour, winEndMinute)
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

    private fun pickApp() {
        btnPickApp.isEnabled = false
        AppListLoader.loadAsync(this) { apps ->
            if (isFinishing || isDestroyed) return@loadAsync
            btnPickApp.isEnabled = true
            if (apps.isEmpty()) {
                toast(getString(R.string.main_no_apps))
                return@loadAsync
            }
            runCatching {
                AppPickerBottomSheet.newInstance(apps)
                    .setOnSelectedListener { app ->
                        selectedPackage = app.packageName
                        selectedAppName = app.appName
                        btnPickApp.text = getString(R.string.main_pick_app_done, selectedAppName)
                    }
                    .show(supportFragmentManager, "app_picker")
            }
        }
    }

    private fun saveRule() {
        val pkg = selectedPackage
        if (pkg == null) {
            toast(getString(R.string.main_pick_app_first))
            return
        }
        val name = selectedAppName ?: getString(R.string.main_default_name)
        val repeatKey = currentRepeatKey()
        val repeatDaysMask = if (repeatKey == "custom") {
            var mask = 0
            for (i in 0..6) if (selectedDays[i]) mask = mask or (1 shl i)
            mask
        } else 0
        if (repeatKey == "custom" && repeatDaysMask == 0) {
            toast(getString(R.string.main_pick_day))
            return
        }

        val rawStart = winStartHour * 60 + winStartMinute
        val rawEnd = winEndHour * 60 + winEndMinute
        val (wsMin, weMin) = if (randomWindow) {
            Math.min(rawStart, rawEnd) to Math.max(rawStart, rawEnd)
        } else 0 to 0

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
            bluetoothName = etBtName.text.toString().trim()
        )

        btnSaveRule.isEnabled = false
        val app = applicationContext
        QuickLaunchExecutors.save.execute {
            val ok = runCatching {
                val id = db.automationDao().insert(a)
                if (a.triggerType == TriggerType.TIME) {
                    Scheduler.schedule(app, a.copy(id = id))
                }
            }.isSuccess
            postUi {
                if (isFinishing || isDestroyed) return@postUi
                btnSaveRule.isEnabled = true
                if (ok) {
                    toast(getString(R.string.main_saved))
                    resetForm()
                    refreshRules()
                } else {
                    toast(getString(R.string.main_save_failed))
                }
            }
        }
    }

    private fun resetForm() {
        selectedPackage = null
        selectedAppName = null
        btnPickApp.text = getString(R.string.main_pick_app)
        selectedTriggerIndex = 0
        selectedRepeatIndex = 0
        selectedDays.fill(false)
        dayViews.forEachIndexed { i, v -> refreshDayCapsule(v, selectedDays[i]) }
        cbRandom.isChecked = false
        cbSkipHolidays.isChecked = false
        randomWindow = false
        skipHolidays = false
        etBtName.text = ""
        val now = Calendar.getInstance()
        hour = now.get(Calendar.HOUR_OF_DAY)
        minute = now.get(Calendar.MINUTE)
        updateTimeLabel()
        updateWindowLabels()
        updateTriggerUi()
    }

    private fun refreshRules() {
        runIo {
            val automations = runCatching { db.automationDao().getAll() }.getOrDefault(emptyList())
            postUi {
                ruleAdapter.submit(automations)
                layoutRulesEmpty.visibility = if (automations.isEmpty()) View.VISIBLE else View.GONE
                updateDashboard(automations)
            }
        }
    }

    private fun updateDashboard(automations: List<Automation>) {
        val enabled = automations.count { it.enabled }
        tvDashboardEnabled.text = "$enabled"
        val next = computeNextTriggerTime(automations.filter { it.enabled && it.triggerType == TriggerType.TIME })
        tvDashboardNext.text = next ?: "--:--"
    }

    private fun computeNextTriggerTime(rules: List<Automation>): String? {
        if (rules.isEmpty()) return null
        val now = Calendar.getInstance()
        val currentMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val currentDow = now.get(Calendar.DAY_OF_WEEK) - 1 // 0=周日

        fun nextMinute(rule: Automation): Int? {
            val targetMin = rule.timeHour * 60 + rule.timeMinute
            return when (rule.repeatMode) {
                "once" -> if (targetMin > currentMin) targetMin else null
                "daily" -> targetMin
                "weekdays" -> if (currentDow in 1..5) targetMin else null
                "weekend" -> if (currentDow == 0 || currentDow == 6) targetMin else null
                "custom" -> {
                    val mask = rule.repeatDays
                    if ((mask shr currentDow) and 1 == 1) targetMin else null
                }
                else -> targetMin
            }
        }

        // 找今天还没触发且最近的一条；若都已过，取时间最小的那一条作为明天/后续最早
        val todayCandidates = rules.mapNotNull { rule ->
            nextMinute(rule)?.let { m -> if (m > currentMin) rule to m else null }
        }
        if (todayCandidates.isNotEmpty()) {
            val (_, min) = todayCandidates.minByOrNull { it.second } ?: return null
            return String.format(Locale.US, "%02d:%02d", min / 60, min % 60)
        }
        // 全部已过时，显示最早一条的目标时间
        val earliest = rules.minByOrNull { it.timeHour * 60 + it.timeMinute } ?: return null
        return String.format(Locale.US, "%02d:%02d", earliest.timeHour, earliest.timeMinute)
    }

    private fun onToggle(automation: Automation, checked: Boolean) {
        val app = applicationContext
        runIo {
            runCatching { db.automationDao().update(automation.copy(enabled = checked)) }
            if (automation.triggerType == TriggerType.TIME) {
                if (checked) Scheduler.schedule(app, automation.copy(enabled = true))
                else Scheduler.cancel(app, automation)
            }
            postUi {
                refreshRules()
                Snackbar.make(
                    binding.rootContainer,
                    if (checked) getString(R.string.main_rule_enabled, automation.name) else getString(R.string.main_rule_disabled, automation.name),
                    Snackbar.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun onDelete(automation: Automation) {
        runCatching {
            AlertDialog.Builder(this)
                .setTitle(R.string.main_delete_rule)
                .setMessage(getString(R.string.main_delete_rule_msg, automation.name))
                .setPositiveButton(R.string.main_delete) { _, _ -> performDelete(automation) }
                .setNegativeButton(R.string.main_cancel, null)
                .show()
        }
    }

    private fun performDelete(automation: Automation) {
        val app = applicationContext
        runIo {
            if (automation.triggerType == TriggerType.TIME) Scheduler.cancel(app, automation)
            runCatching { db.automationDao().delete(automation) }
            val automations = runCatching { db.automationDao().getAll() }.getOrDefault(emptyList())
            postUi {
                ruleAdapter.submit(automations)
                layoutRulesEmpty.visibility = if (automations.isEmpty()) View.VISIBLE else View.GONE
                Snackbar.make(binding.rootContainer, getString(R.string.main_rule_deleted, automation.name), Snackbar.LENGTH_LONG)
                    .setAction(R.string.main_undo) { undoDelete(automation) }
                    .show()
            }
        }
    }

    private fun undoDelete(automation: Automation) {
        val app = applicationContext
        runIo {
            runCatching { db.automationDao().insert(automation) }
            if (automation.triggerType == TriggerType.TIME && automation.enabled) {
                Scheduler.schedule(app, automation)
            }
            val automations = runCatching { db.automationDao().getAll() }.getOrDefault(emptyList())
            postUi {
                ruleAdapter.submit(automations)
                layoutRulesEmpty.visibility = if (automations.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Tab 2：同步源
    // ═══════════════════════════════════════════════════════════════════

    private fun setupSyncTab() {
        spinnerSource = syncBinding.spinnerSource
        btnSyncHolidays = syncBinding.btnSyncHolidays
        btnManageHolidays = syncBinding.btnManageHolidays
        btnManageSources = syncBinding.btnManageSources
        tvAntiSleep = syncBinding.tvAntiSleep
        swAntiSleep = syncBinding.swAntiSleep
        layoutAntiSleep = syncBinding.layoutAntiSleep
        layoutHolidayCard = syncBinding.layoutHolidayCard

        btnSyncHolidays.setOnClickListener { syncHolidays() }
        btnManageHolidays.setOnClickListener {
            startActivity(Intent(this, HolidayManageActivity::class.java))
        }
        btnManageSources.setOnClickListener {
            startActivity(Intent(this, SourceManageActivity::class.java))
        }
        setupSourceSpinner()

        // ⚠️ Round 10 修复：移除同步页面 blur — RenderEffect 会糊掉子 View 文字
    }

    private fun setupSourceSpinner() {
        val ids = mutableListOf("auto")
        val labels = mutableListOf(getString(R.string.main_source_auto))
        HolidaySources.ALL.forEach {
            ids.add(it.id)
            labels.add(it.label)
        }
        runCatching { HolidayPrefs.getCustomSources(this) }.getOrDefault(emptyList()).forEach {
            ids.add(it.id)
            labels.add(getString(R.string.main_source_custom, it.label))
        }

        val pref = runCatching { HolidayPrefs.getSourcePref(this) }.getOrNull() ?: "auto"
        val target = ids.indexOf(pref).coerceAtLeast(0)

        if (ids == sourceIds && spinnerSource.adapter != null) {
            if (spinnerSource.selectedItemPosition != target) {
                spinnerSource.setSelection(target)
            }
            return
        }
        sourceIds = ids

        spinnerSource.onItemSelectedListener = null
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerSource.adapter = adapter
        spinnerSource.setSelection(target)
        spinnerSource.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>, v: android.view.View?, pos: Int, id: Long) {
                val chosen = ids.getOrNull(pos) ?: return
                runCatching { HolidayPrefs.setSourcePref(this@MainActivity, chosen) }
            }
            override fun onNothingSelected(p: AdapterView<*>) {}
        }
    }

    private fun syncHolidays() {
        if (btnSyncHolidays.isEnabled) {
            btnSyncHolidays.isEnabled = false
            btnSyncHolidays.text = getString(R.string.main_syncing)
        }
        val pref = HolidayPrefs.getSourcePref(this)
        val prefId = if (pref == "auto") null else pref
        val app = applicationContext
        HolidaySync.sync(this, prefId) { res ->
            if (isFinishing || isDestroyed) return@sync
            btnSyncHolidays.isEnabled = true
            btnSyncHolidays.text = getString(R.string.main_sync_holidays)
            val msg = if (res.success) {
                runIo { Scheduler.rescheduleAll(app) }
                getString(R.string.main_synced, res.sourceLabel, res.count)
            } else {
                getString(R.string.main_sync_failed)
            }
            runCatching { Snackbar.make(binding.rootContainer, msg, Snackbar.LENGTH_SHORT).show() }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 防外屏息屏
    // ═══════════════════════════════════════════════════════════════════

    private fun setupAntiSleep() {
        syncAntiSleepUi()
        runIo {
            val rooted = RootUtils.hasRoot()
            postUi {
                if (!ScreenOnOverlay.canDraw(this)) return@postUi
                tvAntiSleep.text =
                    if (rooted) getString(R.string.main_anti_sleep_on) else getString(R.string.main_anti_sleep_on_no_root)
            }
        }
    }

    private fun syncAntiSleepUi() {
        if (!::swAntiSleep.isInitialized) return
        val granted = ScreenOnOverlay.canDraw(this)
        tvAntiSleep.text =
            if (granted) getString(R.string.main_anti_sleep_on_no_root) else "防外屏息屏 —— 需要悬浮窗权限"
        swAntiSleep.setOnCheckedChangeListener(null)
        swAntiSleep.isChecked = AntiSleep.isEnabled(this) && granted
        swAntiSleep.isEnabled = true
        bindAntiSleepSwitch()
    }

    private fun bindAntiSleepSwitch() {
        swAntiSleep.setOnCheckedChangeListener { view, checked ->
            if (checked && !ScreenOnOverlay.canDraw(this)) {
                resetAntiSleepSwitch(false)
                Snackbar.make(binding.rootContainer, getString(R.string.main_anti_sleep_need_overlay), Snackbar.LENGTH_LONG)
                    .setAction(R.string.main_anti_sleep_go_auth) { requestOverlayPermission() }
                    .show()
                return@setOnCheckedChangeListener
            }
            view.isEnabled = false
            val app = applicationContext
            runIo {
                val ok = runCatching {
                    if (checked) AntiSleep.enable(app) else AntiSleep.disable(app)
                }.getOrDefault(false)
                if (checked) KeepAliveService.start(app)
                postUi {
                    view.isEnabled = true
                    if (ok) {
                        val tip = if (checked) getString(R.string.main_anti_sleep_turned_on) else getString(R.string.main_anti_sleep_turned_off)
                        Snackbar.make(binding.rootContainer, tip, Snackbar.LENGTH_SHORT).show()
                    } else {
                        resetAntiSleepSwitch(!checked)
                        Snackbar.make(binding.rootContainer, getString(R.string.main_anti_sleep_failed), Snackbar.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun resetAntiSleepSwitch(checked: Boolean) {
        swAntiSleep.setOnCheckedChangeListener(null)
        swAntiSleep.isChecked = checked
        swAntiSleep.isEnabled = true
        bindAntiSleepSwitch()
    }

    private fun requestOverlayPermission() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 生命周期 / 权限
    // ═══════════════════════════════════════════════════════════════════

    override fun onResume() {
        super.onResume()
        refreshRules()
        setupSourceSpinner()
        syncAntiSleepUi()
        if (ScreenOnOverlay.canDraw(this) && !AntiSleep.isDisabled(this)) {
            KeepAliveService.start(this)
        }
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private fun toast(msg: String) {
        runCatching { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun checkPermissionsOnce() {
        val sp = getSharedPreferences("quicklaunch", Context.MODE_PRIVATE)
        if (sp.getBoolean("guided", false)) return

        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }.filter {
            runCatching { ActivityCompat.checkSelfPermission(this, it) }
                .getOrDefault(PackageManager.PERMISSION_GRANTED) != PackageManager.PERMISSION_GRANTED
        }
        if (wanted.isNotEmpty()) {
            runCatching { ActivityCompat.requestPermissions(this, wanted.toTypedArray(), 1) }
        }

        val missing = buildList {
            if (runCatching { !Settings.canDrawOverlays(this@MainActivity) }.getOrDefault(false)) {
                add(getString(R.string.main_permission_overlay))
            }
            val ignoring = runCatching {
                (getSystemService(POWER_SERVICE) as? PowerManager)
                    ?.isIgnoringBatteryOptimizations(packageName) ?: true
            }.getOrDefault(true)
            if (!ignoring) add(getString(R.string.main_permission_battery))

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val exact = runCatching {
                    (getSystemService(ALARM_SERVICE) as? AlarmManager)?.canScheduleExactAlarms() ?: true
                }.getOrDefault(true)
                if (!exact) add(getString(R.string.main_permission_exact_alarm))
            }
            add(getString(R.string.main_permission_autostart))
        }

        if (isFinishing || isDestroyed) return
        runCatching {
            AlertDialog.Builder(this)
                .setTitle(R.string.main_permission_required)
                .setMessage(missing.joinToString("\n\n• ", prefix = "• "))
                .setPositiveButton(R.string.main_permission_go_settings) { _, _ ->
                    sp.edit().putBoolean("guided", true).apply()
                    openSettings()
                }
                .setNegativeButton(R.string.main_permission_later) { _, _ ->
                    sp.edit().putBoolean("guided", true).apply()
                }
                .show()
        }
    }

    private fun openSettings() {
        val uri = Uri.parse("package:$packageName")
        val targets = listOf(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        )
        for (action in targets) {
            try {
                startActivity(Intent(action, uri))
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
    }

    private companion object {
        const val TAB_LAUNCH = 0
        const val TAB_SYNC = 1
    }
}
