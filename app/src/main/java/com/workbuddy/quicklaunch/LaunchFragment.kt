package com.workbuddy.quicklaunch

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.workbuddy.quicklaunch.adapter.BluetoothDeviceAdapter
import com.workbuddy.quicklaunch.data.AppDatabase
import com.workbuddy.quicklaunch.data.Automation
import com.workbuddy.quicklaunch.data.TriggerType
import com.workbuddy.quicklaunch.databinding.DialogBluetoothDevicesBinding
import com.workbuddy.quicklaunch.databinding.ViewLaunchBinding
import com.workbuddy.quicklaunch.util.AppListLoader
import com.workbuddy.quicklaunch.util.AutomationFormController
import com.workbuddy.quicklaunch.util.BluetoothDevices
import com.workbuddy.quicklaunch.util.DevicePickerBottomSheet
import com.workbuddy.quicklaunch.util.QuickLaunchExecutors
import com.workbuddy.quicklaunch.util.Scheduler
import com.workbuddy.quicklaunch.util.WifiNetworks
import java.util.Calendar
import java.util.Locale

/**
 * Tab 1 — 快捷启动页。
 *
 * 持有 ViewLaunchBinding + AutomationFormController + 规则列表。
 * 由 MainActivity 的 FragmentStateAdapter 懒加载，onCreateView 时才 inflate 布局，
 * 避免启动时一次性把两个 Tab 的 View 树全部创建。
 */
class LaunchFragment : Fragment(), AutomationFormController.FormCallbacks {

    private var _binding: ViewLaunchBinding? = null
    private val binding get() = _binding!!

    private lateinit var db: AppDatabase
    private val ruleAdapter = AutomationAdapter(::onToggle, ::onDelete)

    // ── 表单 View 引用 ──
    private lateinit var btnPickApp: MaterialButton
    private lateinit var btnTime: TextView
    private lateinit var btnWinStart: TextView
    private lateinit var btnWinEnd: TextView
    private lateinit var cbRandom: CheckBox
    private lateinit var cbSkipHolidays: CheckBox
    private lateinit var layoutRandom: View
    private lateinit var layoutTime: View
    private lateinit var btnPickBluetooth: MaterialButton
    private lateinit var btnPickWifi: MaterialButton
    private lateinit var layoutCustomDays: View
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

    // ── 蓝牙设备选择 ──
    private var selectedBluetoothName: String? = null
    private var selectedWifiName: String? = null

    // ── WiFi 运行时权限请求 ──
    private var pendingWifiPermissionCallback: ((Boolean) -> Unit)? = null

    private val wifiPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            pendingWifiPermissionCallback?.invoke(granted)
            pendingWifiPermissionCallback = null
        }

    /** 检查是否需要请求 WiFi 相关运行时权限 */
    private fun requestWifiPermissionIfNeeded(onResult: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ 需要 NEARBY_WIFI_DEVICES
            if (ContextCompat.checkSelfPermission(
                    requireContext(), Manifest.permission.NEARBY_WIFI_DEVICES
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                onResult(true)
                return
            }
            pendingWifiPermissionCallback = onResult
            wifiPermissionLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10-12 需要 ACCESS_FINE_LOCATION
            if (ContextCompat.checkSelfPermission(
                    requireContext(), Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                onResult(true)
                return
            }
            pendingWifiPermissionCallback = onResult
            wifiPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            // Android 9 及以下不需要运行时权限
            onResult(true)
        }
    }

    private lateinit var formController: AutomationFormController

    // ── 仪表盘 ──
    private lateinit var layoutDashboard: View
    private lateinit var tvDashboardEnabled: TextView
    private lateinit var tvDashboardNext: TextView

    // ── 权限提醒卡片 ──
    private lateinit var cardPermission: View
    private lateinit var btnGoAuth: View

    // ── 规则列表 ──
    private lateinit var rvRules: androidx.recyclerview.widget.RecyclerView
    private lateinit var layoutRulesEmpty: View

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ViewLaunchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        db = AppDatabase.get(requireContext())

        // 预加载应用列表：首次点击「启动应用」时直接命中缓存，零等待
        QuickLaunchExecutors.io.execute {
            AppListLoader.load(requireContext().applicationContext)
        }

        // 仪表盘
        layoutDashboard = binding.layoutDashboard
        tvDashboardEnabled = binding.tvDashboardEnabled
        tvDashboardNext = binding.tvDashboardNext

        // 权限提醒卡片
        cardPermission = binding.cardPermission
        btnGoAuth = binding.btnGoAuth
        btnGoAuth.setOnClickListener {
            val sp = requireContext().getSharedPreferences("quicklaunch", Context.MODE_PRIVATE)
            sp.edit().putBoolean("guided", true).apply()
            openSettings()
        }

        // 规则列表
        rvRules = binding.rvRules
        layoutRulesEmpty = binding.layoutRulesEmpty
        rvRules.layoutManager = LinearLayoutManager(requireContext())
        rvRules.adapter = ruleAdapter
        rvRules.overScrollMode = View.OVER_SCROLL_NEVER

        // 表单 View
        btnPickApp = binding.btnPickApp
        btnTime = binding.btnTime
        btnWinStart = binding.btnWinStart
        btnWinEnd = binding.btnWinEnd
        cbRandom = binding.cbRandom
        cbSkipHolidays = binding.cbSkipHolidays
        layoutRandom = binding.layoutRandom
        layoutTime = binding.layoutTime
        btnPickBluetooth = binding.btnPickBluetooth
        btnPickWifi = binding.btnPickWifi
        layoutCustomDays = binding.layoutCustomDays
        btnSaveRule = binding.btnSaveRule

        triggerChips.clear()
        triggerChips.addAll(
            listOf(
                binding.chipTrigger0, binding.chipTrigger1,
                binding.chipTrigger2, binding.chipTrigger3
            )
        )
        repeatChips.clear()
        repeatChips.addAll(
            listOf(
                binding.chipRepeat0, binding.chipRepeat1, binding.chipRepeat2,
                binding.chipRepeat3, binding.chipRepeat4
            )
        )
        dayViews.clear()
        dayViews.addAll(
            listOf(
                binding.tbDay0, binding.tbDay1, binding.tbDay2,
                binding.tbDay3, binding.tbDay4, binding.tbDay5, binding.tbDay6
            )
        )

        // 蓝牙设备选择
        btnPickBluetooth.setOnClickListener {
            showBluetoothDevicePicker()
        }

        // WiFi 网络选择
        btnPickWifi.setOnClickListener {
            showWifiNetworkPicker()
        }

        // 表单控制器
        formController = AutomationFormController(
            context = requireContext(),
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
                btnPickBluetooth = btnPickBluetooth,
                btnPickWifi = btnPickWifi,
                layoutCustomDays = layoutCustomDays,
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
    // 规则列表 / 仪表盘
    // ═══════════════════════════════════════════════════════════════════

    fun refreshRules() {
        if (_binding == null) return
        QuickLaunchExecutors.io.execute {
            val automations = runCatching { db.automationDao().getAll() }.getOrDefault(emptyList())
            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                ruleAdapter.submitList(automations)
                layoutRulesEmpty.visibility = if (automations.isEmpty()) View.VISIBLE else View.GONE
                updateDashboard(automations)
            }
        }
    }

    private fun updateDashboard(automations: List<Automation>) {
        val enabled = automations.count { it.enabled }
        tvDashboardEnabled.text = "$enabled"
        val next = computeNextTriggerTime(
            automations.filter { it.enabled && it.triggerType == TriggerType.TIME }
        )
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

        val todayCandidates = rules.mapNotNull { rule ->
            nextMinute(rule)?.let { m -> if (m > currentMin) rule to m else null }
        }
        if (todayCandidates.isNotEmpty()) {
            val (_, min) = todayCandidates.minByOrNull { it.second } ?: return null
            return String.format(Locale.US, "%02d:%02d", min / 60, min % 60)
        }
        val earliest = rules.minByOrNull { it.timeHour * 60 + it.timeMinute } ?: return null
        return String.format(Locale.US, "%02d:%02d", earliest.timeHour, earliest.timeMinute)
    }

    // ═══════════════════════════════════════════════════════════════════
    // 规则操作
    // ═══════════════════════════════════════════════════════════════════

    private fun onToggle(automation: Automation, checked: Boolean) {
        val app = requireContext().applicationContext
        QuickLaunchExecutors.io.execute {
            runCatching { db.automationDao().update(automation.copy(enabled = checked)) }
            if (automation.triggerType == TriggerType.TIME) {
                if (checked) Scheduler.schedule(app, automation.copy(enabled = true))
                else Scheduler.cancel(app, automation)
            }
            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                refreshRules()
                Snackbar.make(
                    binding.root,
                    if (checked) getString(R.string.main_rule_enabled, automation.name)
                    else getString(R.string.main_rule_disabled, automation.name),
                    Snackbar.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun onDelete(automation: Automation) {
        runCatching {
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.main_delete_rule)
                .setMessage(getString(R.string.main_delete_rule_msg, automation.name))
                .setPositiveButton(R.string.main_delete) { _, _ -> performDelete(automation) }
                .setNegativeButton(R.string.main_cancel, null)
                .show()
        }
    }

    private fun performDelete(automation: Automation) {
        val app = requireContext().applicationContext
        QuickLaunchExecutors.io.execute {
            if (automation.triggerType == TriggerType.TIME) Scheduler.cancel(app, automation)
            runCatching { db.automationDao().delete(automation) }
            val automations = runCatching { db.automationDao().getAll() }.getOrDefault(emptyList())
            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                ruleAdapter.submitList(automations)
                layoutRulesEmpty.visibility = if (automations.isEmpty()) View.VISIBLE else View.GONE
                Snackbar.make(
                    binding.root,
                    getString(R.string.main_rule_deleted, automation.name),
                    Snackbar.LENGTH_LONG
                ).setAction(R.string.main_undo) { undoDelete(automation) }.show()
            }
        }
    }

    private fun undoDelete(automation: Automation) {
        val app = requireContext().applicationContext
        QuickLaunchExecutors.io.execute {
            runCatching { db.automationDao().insert(automation) }
            if (automation.triggerType == TriggerType.TIME && automation.enabled) {
                Scheduler.schedule(app, automation)
            }
            val automations = runCatching { db.automationDao().getAll() }.getOrDefault(emptyList())
            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                ruleAdapter.submitList(automations)
                layoutRulesEmpty.visibility = if (automations.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun openSettings() {
        val uri = android.net.Uri.parse("package:${requireContext().packageName}")
        val targets = listOf(
            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        )
        for (action in targets) {
            try {
                startActivity(android.content.Intent(action, uri))
                return
            } catch (_: android.content.ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // FormCallbacks 实现
    // ═══════════════════════════════════════════════════════════════════

    override fun onStateUiUpdate() {
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
        repeatChips.forEachIndexed { index, textView ->
            refreshRepeatChip(textView, index == formController.selectedRepeatIndex)
        }
        val isCustom = formController.selectedRepeatIndex == 3
        layoutCustomDays.visibility = if (isCustom) View.VISIBLE else View.GONE
        dayViews.forEachIndexed { idx, view ->
            refreshDayCapsule(view, formController.selectedDays[idx])
        }
        btnPickBluetooth.visibility = if (formController.selectedTriggerIndex == 3) View.VISIBLE else View.GONE
        btnPickWifi.visibility = if (formController.selectedTriggerIndex == 2) View.VISIBLE else View.GONE
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
        selectedBluetoothName = null
        selectedWifiName = null
        updateBluetoothButtonText()
        updateWifiButtonText()
        onStateUiUpdate()
    }

    private fun toast(msg: String) {
        runCatching {
            android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 视觉刷新辅助
    // ═══════════════════════════════════════════════════════════════════

    private fun refreshDayCapsule(view: TextView, selected: Boolean) {
        view.setBackgroundResource(
            if (selected) R.drawable.bg_dark_capsule_selected
            else R.drawable.bg_dark_capsule_unselected
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

    // ═══════════════════════════════════════════════════════════════════
    // 蓝牙设备选择
    // ═══════════════════════════════════════════════════════════════════

    private fun showBluetoothDevicePicker() {
        val deviceNames = BluetoothDevices.getPairedDeviceNames(requireContext())
        DevicePickerBottomSheet.newInstance(
            mode = DevicePickerBottomSheet.MODE_BLUETOOTH,
            devices = deviceNames,
            selectedName = selectedBluetoothName
        ).setOnSelectedListener { name ->
            selectedBluetoothName = name
            formController.bluetoothName = name.orEmpty()
            updateBluetoothButtonText()
        }.show(parentFragmentManager, "bluetooth_picker")
    }

    private fun updateBluetoothButtonText() {
        btnPickBluetooth.text = if (selectedBluetoothName.isNullOrEmpty()) {
            "选择蓝牙设备（可选）"
        } else {
            "已选择：$selectedBluetoothName"
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // WiFi 网络选择
    // ═══════════════════════════════════════════════════════════════════

    private fun showWifiNetworkPicker() {
        requestWifiPermissionIfNeeded { granted ->
            if (!granted) return@requestWifiPermissionIfNeeded
            val networkNames = WifiNetworks.getSavedNetworkNames(requireContext())
            DevicePickerBottomSheet.newInstance(
                mode = DevicePickerBottomSheet.MODE_WIFI,
                devices = networkNames,
                selectedName = selectedWifiName
            ).setOnSelectedListener { name ->
                selectedWifiName = name
                formController.wifiName = name.orEmpty()
                updateWifiButtonText()
            }.show(parentFragmentManager, "wifi_picker")
        }
    }

    private fun updateWifiButtonText() {
        btnPickWifi.text = if (selectedWifiName.isNullOrEmpty()) {
            "选择 WiFi 网络（可选）"
        } else {
            "已选择：$selectedWifiName"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
