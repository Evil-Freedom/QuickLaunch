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
import android.widget.CheckBox
import android.widget.ImageView
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
import com.workbuddy.quicklaunch.util.AutomationFormController
import com.workbuddy.quicklaunch.util.DarkWheelTimePicker
import com.workbuddy.quicklaunch.util.HolidayPrefs
import com.workbuddy.quicklaunch.util.HolidaySources
import com.workbuddy.quicklaunch.util.HolidaySync
import com.workbuddy.quicklaunch.util.QuickLaunchExecutors
import com.workbuddy.quicklaunch.util.RootUtils
import com.workbuddy.quicklaunch.util.Scheduler
import com.workbuddy.quicklaunch.util.ScreenOnOverlay
import com.workbuddy.quicklaunch.util.SyncTabController
import java.util.Calendar
import java.util.Locale

/**
 * 主入口：底部双 Tab 导航，彻底取消二级页跳转。
 * - Tab 1 快捷启动：规则创建表单 + 规则列表平铺在同一页。
 * - Tab 2 同步源：法定节假日同步、数据源选择、手动管理、自定义源管理。
 */
class MainActivity : AppCompatActivity(), AutomationFormController.FormCallbacks, SyncTabController.SyncCallbacks {

    private lateinit var binding: ActivityMainBinding
    private lateinit var db: AppDatabase

    // ---------- Tab 容器 ----------
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

    // ---------- 表单控制器 ----------
    private lateinit var formController: AutomationFormController

    // ---------- 同步源控制器 ----------
    private lateinit var syncController: SyncTabController

    // ---------- 表单 View 引用（供控制器回调刷新用） ----------
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

    // ---------- 防息屏 ----------
    private lateinit var tvAntiSleep: TextView
    private lateinit var swAntiSleep: SwitchCompat

    private fun runIo(block: () -> Unit) {
        runCatching { QuickLaunchExecutors.io.execute { runCatching(block) } }
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

        KeepAliveService.start(this)
        checkPermissionsOnce()

        // 首次启动（本地无节假日数据）自动同步一次，便于「跳过节假日」立即生效。
        runIo {
            val empty = runCatching { db.holidayDao().count() == 0 }.getOrDefault(false)
            if (empty) postUi { syncController.syncHolidays(this@MainActivity) }
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
                    onSyncPageSelected()
                } else {
                    refreshRules()
                }
            }
        })
        applyBottomNavStyle(true)
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

        // 初始化表单控制器
        formController = AutomationFormController(
            context = this,
            db = db,
            views = AutomationFormController.FormViews(
                btnPickApp = btnPickApp,
                btnTime = btnTime,
                btnWinStart = btnWinStart,
                btnWinEnd = btnWinEnd,
                cbRandom = cbRandom,
                cbSkipHolidays = cbSkipHolidays,
                layoutRandom = layoutRandom,
                layoutTime = layoutTime,
                layoutBt = layoutBt,
                layoutCustomDays = layoutCustomDays,
                etBtName = etBtName,
                btnSave = btnSaveRule,
                triggerChips = triggerChips,
                repeatChips = repeatChips,
                dayViews = dayViews,
                triggerIcons = triggerIcons
            ),
            callbacks = this
        )
        formController.setup()
        refreshRules()
    }

    // ═══════════════════════════════════════════════════════════════════
    // Tab 2：同步源 + 防息屏
    // ═══════════════════════════════════════════════════════════════════

    private fun setupSyncTab() {
        tvAntiSleep = syncBinding.tvAntiSleep
        swAntiSleep = syncBinding.swAntiSleep

        syncController = SyncTabController(
            context = this,
            views = SyncTabController.SyncViews(
                spinnerSource = syncBinding.spinnerSource,
                btnSyncHolidays = syncBinding.btnSyncHolidays,
                btnManageHolidays = syncBinding.btnManageHolidays,
                btnManageSources = syncBinding.btnManageSources,
                tvAntiSleep = syncBinding.tvAntiSleep,
                swAntiSleep = syncBinding.swAntiSleep,
                layoutAntiSleep = syncBinding.layoutAntiSleep,
                layoutHolidayCard = syncBinding.layoutHolidayCard
            )
        )
        syncController.setup(this)
    }

    // ═══════════════════════════════════════════════════════════════════
    // 规则列表 / 仪表盘
    // ═══════════════════════════════════════════════════════════════════

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
    // 生命周期 / 权限
    // ═══════════════════════════════════════════════════════════════════

    override fun onResume() {
        super.onResume()
        refreshRules()
        syncController.setupSourceSpinner(this)
        syncController.syncAntiSleepUi(this)
        if (ScreenOnOverlay.canDraw(this) && !AntiSleep.isDisabled(this)) {
            KeepAliveService.start(this)
        }
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

    // ═══════════════════════════════════════════════════════════════════
    // FormCallbacks 实现
    // ═══════════════════════════════════════════════════════════════════

    override fun onStateUiUpdate() {
        // 触发条件芯片
        triggerChips.forEachIndexed { index, textView ->
            refreshTriggerChip(textView, index == formController.selectedTriggerIndex)
        }
        val isTime = formController.selectedTriggerIndex == 0
        layoutTime.visibility = if (isTime) View.VISIBLE else View.GONE
        cbRandom.visibility = if (isTime) View.VISIBLE else View.GONE
        cbSkipHolidays.visibility = if (isTime) View.VISIBLE else View.GONE
        if (!isTime) {
            layoutRandom.visibility = View.GONE
            btnTime.visibility = View.VISIBLE
            cbRandom.isChecked = false
            cbSkipHolidays.isChecked = false
            formController.randomWindow = false
            formController.skipHolidays = false
        }
        // 重复模式芯片
        repeatChips.forEachIndexed { index, textView ->
            refreshRepeatChip(textView, index == formController.selectedRepeatIndex)
        }
        val isCustom = formController.selectedRepeatIndex == 3
        layoutCustomDays.visibility = if (isCustom) View.VISIBLE else View.GONE
        // 星期胶囊
        dayViews.forEachIndexed { idx, view ->
            refreshDayCapsule(view, formController.selectedDays[idx])
        }
        layoutBt.visibility = if (formController.selectedTriggerIndex == 3) View.VISIBLE else View.GONE
    }

    override fun onAppPicked(appName: String) {
        btnPickApp.text = getString(R.string.main_pick_app_done, appName)
    }

    override fun onAppPickEmpty() {
        toast(getString(R.string.main_no_apps))
    }

    override fun onValidationError(messageResId: Int) {
        toast(getString(messageResId))
    }

    override fun onSaveSuccess() {
        toast(getString(R.string.main_saved))
        formController.reset()
        refreshRules()
    }

    override fun onSaveFailed() {
        toast(getString(R.string.main_save_failed))
    }

    override fun onFormReset() {
        btnPickApp.text = getString(R.string.main_pick_app)
        onStateUiUpdate()
    }

    // ═══════════════════════════════════════════════════════════════════
    // SyncCallbacks 实现
    // ═══════════════════════════════════════════════════════════════════

    override fun showSnackbar(msg: String, duration: Int) {
        runCatching { Snackbar.make(snackbarRoot, msg, duration).show() }
    }

    override fun onSyncPageSelected() {
        syncController.setupSourceSpinner(this)
        syncController.syncAntiSleepUi(this)
    }

    override val snackbarRoot: View get() = binding.rootContainer

    override fun onRootCheckResult(rooted: Boolean) {
        if (!ScreenOnOverlay.canDraw(this)) return
        tvAntiSleep.text = if (rooted) getString(R.string.main_anti_sleep_on) else getString(R.string.main_anti_sleep_on_no_root)
    }

    override fun requestOverlayPermission() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 视觉刷新辅助
    // ═══════════════════════════════════════════════════════════════════

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

    private companion object {
        const val TAB_LAUNCH = 0
        const val TAB_SYNC = 1
    }
}
