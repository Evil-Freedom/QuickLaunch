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
import com.workbuddy.quicklaunch.util.HolidayChecker
import com.workbuddy.quicklaunch.util.QuickLaunchExecutors
import com.workbuddy.quicklaunch.util.RuleBackupStore
import com.workbuddy.quicklaunch.util.Scheduler
import com.workbuddy.quicklaunch.util.WifiNetworks
import com.workbuddy.quicklaunch.util.tappableSnackbar
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
    private val ruleAdapter = AutomationAdapter(::onToggle, ::onDelete, ::onEdit, ::onExceptions)

    /**
     * 编辑页返回：保存成功时把旧值写回去（读取编辑前的留档）。
     * 留档存在 SharedPreferences，所以即使用户过了几秒才点「撤销」、甚至期间进程被回收，旧值都还在。
     */
    private val editLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) return@registerForActivityResult
        val editedId = result.data?.getLongExtra(
            CreateAutomationActivity.EXTRA_EDITED_ID, -1L
        ) ?: -1L
        if (editedId <= 0L) return@registerForActivityResult

        refreshRules()
        val name = ruleAdapter.currentList.firstOrNull { it.id == editedId }?.name.orEmpty()
        tappableSnackbar(
            binding.root,
            getString(R.string.main_rule_edited, name),
            getString(R.string.main_tap_to_undo)
        ) { undoEdit(editedId) }.show()
    }

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
            val ui = loadRuleUiData()
            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                ruleAdapter.submitRules(automations, ui.exceptions)
                layoutRulesEmpty.visibility = if (automations.isEmpty()) View.VISIBLE else View.GONE
                updateDashboard(automations, ui)
            }
        }
    }

    /**
     * 列表与仪表盘共同需要的附加数据（IO 线程调用，主线程只读）。
     *
     * 打包成一个对象是为了让「读豁免」和「读节假日」只走一次库，
     * 且每个调用点都不会漏掉其中一个 —— 漏掉的后果是仪表盘按「没有请假」算，
     * 显示的时间与实际闹钟对不上。
     */
    private class RuleUiData(
        val exceptions: Map<Long, List<String>>,
        val holidays: HolidayChecker?
    )

    private fun loadRuleUiData(): RuleUiData {
        val exceptions = runCatching {
            db.ruleExceptionDao().getAll().groupBy({ it.ruleId }, { it.date })
        }.getOrDefault(emptyMap())
        val holidays = runCatching {
            HolidayChecker.fromDb(requireContext().applicationContext)
        }.getOrNull()
        return RuleUiData(exceptions, holidays)
    }

    private fun updateDashboard(automations: List<Automation>, ui: RuleUiData) {
        val enabled = automations.count { it.enabled }
        tvDashboardEnabled.text = "$enabled"
        val next = computeNextTriggerTime(
            automations.filter { it.enabled && it.triggerType == TriggerType.TIME },
            ui
        )
        tvDashboardNext.text = next ?: "--:--"
    }

    /**
     * 下一次触发时刻。**直接复用 Scheduler 的判定**，不另写一套算法。
     *
     * 旧实现只按「今天还剩哪条规则的固定时刻」取最早，完全不看请假与节假日，
     * 于是出现了「卡片标签写着请假 11-04、仪表盘却显示今天 12:19」这种自相矛盾的状态。
     *
     * 随机窗口规则不参与精确计算：它每次重排都会重新随机，
     * 若在这里参与比较，仪表盘会随每次刷新不停跳动，比不精确更糟。
     */
    private fun computeNextTriggerTime(rules: List<Automation>, ui: RuleUiData): String? {
        if (rules.isEmpty()) return null
        var best = Long.MAX_VALUE
        for (rule in rules) {
            if (rule.randomWindow) continue
            val ex = ui.exceptions[rule.id].orEmpty().toSet()
            val fire = runCatching {
                Scheduler.peekNextTriggerAt(rule, ui.holidays, ex)
            }.getOrNull() ?: continue
            if (fire in 1 until best) best = fire
        }
        if (best == Long.MAX_VALUE) return fallbackNextTriggerTime(rules)
        return formatTriggerTime(best)
    }

    /** 把触发时刻格式化成「今天只显示 HH:mm，非今天带月日」。 */
    private fun formatTriggerTime(fireAt: Long): String {
        val now = Calendar.getInstance()
        val c = Calendar.getInstance().apply { timeInMillis = fireAt }
        val hhmm = String.format(
            Locale.US, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)
        )
        val sameDay = now.get(Calendar.YEAR) == c.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR)
        return if (sameDay) hhmm
        else String.format(
            Locale.US, "%02d-%02d %s",
            c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH), hhmm
        )
    }

    /**
     * 兜底显示：全部规则都是随机窗口（或不含固定的定时规则）时的近似值。
     * 取最早一条规则的时刻，保持与旧版展示一致，不显示「--:--」误导用户以为没有规则。
     */
    private fun fallbackNextTriggerTime(rules: List<Automation>): String? {
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

    /** 打开编辑页。带上规则 id，由 CreateAutomationActivity 自行读库回填。 */
    private fun onEdit(automation: Automation) {
        runCatching {
            editLauncher.launch(
                CreateAutomationActivity.editIntent(requireContext(), automation.id)
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 单日请假（豁免日期）
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 点请假标签 → 列出该规则所有「今天及以后」的请假日期，可逐条撤销。
     *
     * 远程通道能下发请假，如果本地既看不见也删不掉，请假就成了单向门：
     * 用户只能改 repeatDays 位图来绕开，而那是**永久**取消所有该周的规则，代价远大于预期。
     * 这个弹窗就是这条闭环的出口。
     */
    private fun onExceptions(automation: Automation) {
        if (_binding == null) return
        QuickLaunchExecutors.io.execute {
            val rows = runCatching { db.ruleExceptionDao().getByRule(automation.id) }
                .getOrDefault(emptyList())
                .filter { it.date >= HolidayChecker.dateKey(Calendar.getInstance()) }
            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                if (rows.isEmpty()) {
                    // 只有过去的请假（界面不该显示这种标签）→ 顺手刷新掉过期状态
                    Snackbar.make(
                        binding.root,
                        getString(R.string.exc_dialog_empty),
                        Snackbar.LENGTH_SHORT
                    ).show()
                    refreshRules()
                    return@runOnUiThread
                }
                showExceptionDialog(automation, rows.map { it.date to it.reason })
            }
        }
    }

    /**
     * 请假日期列表弹窗。
     *
     * ⚠️ 不要用 `setMessage` + `setItems` 组合：AppCompat 的 AlertController
     * 在 setupContent 里是二选一 —— 只要设了 message，列表控件根本不进内容区，
     * 结果就是「弹窗只有说明文字、一个日期都看不到」，撤销入口等于不存在。
     * 真机上已经踩过一次。这里用自定义视图把说明与可点日期行放在一起。
     */
    private fun showExceptionDialog(automation: Automation, rows: List<Pair<String, String>>) {
        val ctx = requireContext()
        val pad = (16 * resources.displayMetrics.density).toInt()

        val box = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        box.addView(
            TextView(ctx).apply {
                text = getString(R.string.exc_dialog_msg)
                textSize = 13f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                setPadding(0, 0, 0, pad / 2)
            }
        )

        var dialog: androidx.appcompat.app.AlertDialog? = null
        rows.forEach { (date, reason) ->
            val day = monthDay(date)
            val label = if (reason.isBlank()) day
            else day + getString(R.string.exc_reason_suffix, reason)
            box.addView(
                TextView(ctx).apply {
                    text = label
                    textSize = 16f
                    setTextColor(ContextCompat.getColor(ctx, R.color.text_white))
                    setPadding(0, pad, 0, pad)
                    isClickable = true
                    setOnClickListener {
                        dialog?.dismiss()
                        confirmRemoveException(automation, date)
                    }
                }
            )
        }

        dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.exc_dialog_title, automation.name))
            .setView(box)
            .setNegativeButton(R.string.main_cancel, null)
            .create()
        dialog?.show()
    }

    private fun confirmRemoveException(automation: Automation, date: String) {
        runCatching {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.exc_remove_confirm, monthDay(date)))
                .setMessage(R.string.exc_remove_confirm_msg)
                // 正向按钮用「取消请假」而不是「删除」：这里删的是「这天不启动」这条豁免，
                // 写成「删除」会被读成删除整条规则，语义完全相反。
                .setPositiveButton(R.string.exc_remove_ok) { _, _ -> removeException(automation, date) }
                .setNegativeButton(R.string.exc_back, null)
                .show()
        }
    }

    /**
     * 撤销某天的请假。
     *
     * ⚠️ 必须 cancel 再 schedule：只删库不重排的话，系统里已排的闹钟照旧会响 ——
     * 数据对、接口 200，但行为完全错。这和远程通道是同一条铁律。
     */
    private fun removeException(automation: Automation, date: String) {
        val app = requireContext().applicationContext
        QuickLaunchExecutors.io.execute {
            runCatching { db.ruleExceptionDao().delete(automation.id, date) }
            if (automation.triggerType == TriggerType.TIME) {
                Scheduler.cancel(app, automation)
                // schedule 默认自己从库里读豁免，此时刚删掉的那天已经不在集合里
                if (automation.enabled) Scheduler.schedule(app, automation)
            }
            val automations = runCatching { db.automationDao().getAll() }.getOrDefault(emptyList())
            val ui = loadRuleUiData()
            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                ruleAdapter.submitRules(automations, ui.exceptions)
                layoutRulesEmpty.visibility = if (automations.isEmpty()) View.VISIBLE else View.GONE
                updateDashboard(automations, ui)
                Snackbar.make(
                    binding.root,
                    getString(R.string.exc_removed, monthDay(date)),
                    Snackbar.LENGTH_SHORT
                ).show()
            }
        }
    }

    /** yyyy-MM-dd → MM-dd，只用于展示。 */
    private fun monthDay(date: String): String =
        if (date.length >= 10) date.substring(5) else date

    /**
     * 撤销上一次修改：把留档的旧值原样写回，并重排闹钟。
     *
     * 留档是「读取即消费」（take 会清掉），所以撤销只能生效一次 ——
     * 避免用户连点两次后把中间态又还原回去、越撤越乱。
     */
    private fun undoEdit(ruleId: Long) {
        val ctx = context ?: return
        val app = requireContext().applicationContext
        QuickLaunchExecutors.io.execute {
            val snapshot = RuleBackupStore.take(ctx, ruleId)
            if (snapshot == null) {
                activity?.runOnUiThread {
                    if (_binding == null) return@runOnUiThread
                    Snackbar.make(
                        binding.root,
                        getString(R.string.main_edit_undo_gone),
                        Snackbar.LENGTH_SHORT
                    ).show()
                }
                return@execute
            }
            runCatching {
                db.automationDao().update(snapshot)
                Scheduler.cancel(app, snapshot)
                if (snapshot.enabled && snapshot.triggerType == TriggerType.TIME) {
                    Scheduler.schedule(app, snapshot)
                }
            }
            val automations = runCatching { db.automationDao().getAll() }.getOrDefault(emptyList())
            val ui = loadRuleUiData()
            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                ruleAdapter.submitRules(automations, ui.exceptions)
                layoutRulesEmpty.visibility = if (automations.isEmpty()) View.VISIBLE else View.GONE
                updateDashboard(automations, ui)
                Snackbar.make(
                    binding.root,
                    getString(R.string.main_edit_undone, snapshot.name),
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
            val ui = loadRuleUiData()
            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                ruleAdapter.submitRules(automations, ui.exceptions)
                layoutRulesEmpty.visibility = if (automations.isEmpty()) View.VISIBLE else View.GONE
                tappableSnackbar(
                    binding.root,
                    getString(R.string.main_rule_deleted, automation.name),
                    getString(R.string.main_tap_to_undo)
                ) { undoDelete(automation) }.show()
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
            val ui = loadRuleUiData()
            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                ruleAdapter.submitRules(automations, ui.exceptions)
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
            // 只收起「定时」专属的界面元素，**不动**用户已选的开关值。
            // 曾经这里会把 randomWindow / skipHolidays 直接置为 false，
            // 导致用户从「定时」切到「充电」再切回来时这两个设置被静默清空。
            // 它们只在 triggerType=TIME 时被 Scheduler 读取，留着不影响其它触发方式。
            layoutRandom.visibility = View.GONE
            btnTime.visibility = View.VISIBLE
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
