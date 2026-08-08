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
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.workbuddy.quicklaunch.data.AppDatabase
import com.workbuddy.quicklaunch.data.Automation
import com.workbuddy.quicklaunch.data.TriggerType
import com.workbuddy.quicklaunch.databinding.ActivityMainBinding
import com.workbuddy.quicklaunch.receiver.WifiReceiver
import com.workbuddy.quicklaunch.service.KeepAliveService
import com.workbuddy.quicklaunch.util.AntiSleep
import com.workbuddy.quicklaunch.util.RootUtils
import com.workbuddy.quicklaunch.util.ScreenOnOverlay
import com.workbuddy.quicklaunch.util.HolidaySync
import com.workbuddy.quicklaunch.util.HolidaySources
import com.workbuddy.quicklaunch.util.HolidayPrefs
import com.workbuddy.quicklaunch.util.LiquidGlassHelper
import com.workbuddy.quicklaunch.util.Scheduler
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var db: AppDatabase
    private val adapter = AutomationAdapter(emptyList(), ::onToggle, ::onDelete)

    /** root 命令与数据库读写都会阻塞（root 首次还要等授权弹窗），一律丢到单线程池里跑，绝不占主线程。 */
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "main-io").apply { isDaemon = true }
    }

    /** 当前下拉里的数据源 id 顺序，用于判断是否真的需要重建 Adapter。 */
    private var sourceIds: List<String> = emptyList()

    /**
     * 安全提交后台任务：onDestroy 之后 executor 已 shutdown，
     * 再 execute 会抛「线程池拒绝执行」异常直接崩溃。
     */
    private fun runIo(block: () -> Unit) {
        runCatching { io.execute { runCatching(block) } }
    }

    /** 回到主线程执行，并自动丢弃 Activity 已销毁后的回调（防内存泄漏 / 防访问已销毁界面）。 */
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

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter
        // 去除原生 OverScroll 阴影，改用 iOS 弹性回弹
        binding.recycler.overScrollMode = View.OVER_SCROLL_NEVER
        binding.fabAdd.setOnClickListener {
            startActivity(Intent(this, CreateAutomationActivity::class.java))
        }
        binding.btnSyncHolidays.setOnClickListener { syncHolidays() }
        binding.btnManageHolidays.setOnClickListener {
            startActivity(Intent(this, HolidayManageActivity::class.java))
        }
        binding.btnManageSources.setOnClickListener {
            startActivity(Intent(this, SourceManageActivity::class.java))
        }
        setupSourceSpinner()

        KeepAliveService.start(this)
        setupAntiSleep()
        checkPermissionsOnce()

        // 初始化 iOS 26 毛玻璃效果
        setupLiquidGlass()

        // 首次启动（本地无节假日数据）自动同步一次，便于「跳过节假日」立即生效。
        // count() 是磁盘 IO，放后台查，避免拖慢冷启动首帧。
        runIo {
            val empty = runCatching { db.holidayDao().count() == 0 }.getOrDefault(false)
            if (empty) postUi { syncHolidays() }
        }
    }

    /**
     * 初始化 iOS 26 Liquid Glass 毛玻璃效果
     * 为顶部操作卡片施加硬件级高斯模糊
     */
    private fun setupLiquidGlass() {
        // 操作区卡片：轻微毛玻璃
        LiquidGlassHelper.apply(binding.cardOperations)
    }

    /**
     * 数据源下拉：自动（推荐）优先用上次成功源，也可手动指定某一内置/自定义源（失败再回退其余）。
     * onResume 每次都会调用，源列表没变就只更新选中项，
     * 不再重复 new ArrayAdapter + 重设 Adapter（会触发整段 View 重建与一次多余的选中回调）。
     */
    private fun setupSourceSpinner() {
        val ids = mutableListOf("auto")
        val labels = mutableListOf("自动（推荐）")
        HolidaySources.ALL.forEach {
            ids.add(it.id)
            labels.add(it.label)
        }
        runCatching { HolidayPrefs.getCustomSources(this) }.getOrDefault(emptyList()).forEach {
            ids.add(it.id)
            labels.add("${it.label}（自定义）")
        }

        val pref = runCatching { HolidayPrefs.getSourcePref(this) }.getOrNull() ?: "auto"
        val target = ids.indexOf(pref).coerceAtLeast(0)

        if (ids == sourceIds && binding.spinnerSource.adapter != null) {
            if (binding.spinnerSource.selectedItemPosition != target) {
                binding.spinnerSource.setSelection(target)
            }
            return
        }
        sourceIds = ids

        // 换 Adapter 期间先摘掉监听，避免系统在重建时回调一次把偏好覆盖成默认值
        binding.spinnerSource.onItemSelectedListener = null
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerSource.adapter = adapter
        binding.spinnerSource.setSelection(target)
        binding.spinnerSource.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>, v: android.view.View?, pos: Int, id: Long) {
                val chosen = ids.getOrNull(pos) ?: return
                runCatching { HolidayPrefs.setSourcePref(this@MainActivity, chosen) }
            }
            override fun onNothingSelected(p: AdapterView<*>) {}
        }
    }

    /** 后台拉取并缓存中国法定节假日；结果用 Snackbar 反馈，并显示实际采用的数据源。 */
    private fun syncHolidays() {
        // 幂等短路：按钮已禁用时说明同步已在跑，直接忽略本次点击，不重复触发
        if (binding.btnSyncHolidays.isEnabled) {
            binding.btnSyncHolidays.isEnabled = false
            binding.btnSyncHolidays.text = "同步中…"
        }
        val pref = HolidayPrefs.getSourcePref(this)
        val prefId = if (pref == "auto") null else pref
        val app = applicationContext
        HolidaySync.sync(this, prefId) { res ->
            if (isFinishing || isDestroyed) return@sync
            binding.btnSyncHolidays.isEnabled = true
            binding.btnSyncHolidays.text = "同步法定节假日"
            val msg = if (res.success) {
                // 同步后重新排程，使「跳过节假日」立即按最新数据生效。
                // rescheduleAll 会读全表并逐条排程，必须放后台，否则规则一多主线程直接卡顿。
                runIo { Scheduler.rescheduleAll(app) }
                "已同步（来源：${res.sourceLabel}，${res.count} 天）"
            } else {
                "节假日数据同步失败（已保留上次数据），定时规则不受影响，可稍后重试"
            }
            runCatching { Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show() }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        setupSourceSpinner() // 从「管理数据源」返回后刷新下拉，纳入新增的自定义源
        // 从悬浮窗授权页回来时权限可能刚变，重新对齐开关状态
        syncAntiSleepUi()
        // 后台即防息屏：授权已就绪且未手动关时，让常驻服务重新同步一次，
        // 确保悬浮窗在「不拨开关」的情况下也能自动挂上。
        if (ScreenOnOverlay.canDraw(this) && !AntiSleep.isDisabled(this)) {
            KeepAliveService.start(this)
        }
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    /** targetSdk 35+ 起系统强制边到边显示，不消费 insets 内容会被状态栏和导航栏压住。 */
    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars: Insets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    /** 列表读取是磁盘 IO，放后台执行，主线程只做 UI 提交。 */
    private fun refresh() {
        runIo {
            // db.automationDao().getAll() 是磁盘 IO，放后台执行
            val automations = runCatching { db.automationDao().getAll() }.getOrDefault(emptyList())
            postUi {
                adapter.submit(automations)
                binding.tvEmpty.visibility = if (automations.isEmpty()) View.VISIBLE else View.GONE
            }
        }
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
                Snackbar.make(
                    binding.root,
                    if (checked) "已开启「${automation.name}」" else "已关闭「${automation.name}」",
                    Snackbar.LENGTH_SHORT
                ).show()
            }
        }
    }

    /** 删除入口：先弹二次确认，用户确认后才真正删库。 */
    private fun onDelete(automation: Automation) {
        runCatching {
            MaterialAlertDialogBuilder(this)
                .setTitle("删除自动化？")
                .setMessage("「${automation.name}」将被删除，其定时任务会一并取消。")
                .setPositiveButton("删除") { _, _ -> performDelete(automation) }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    /** 真正删除 + Snackbar 撤销。undoDelete 用原 Automation（含原 id）重插，保证 AlarmManager 的请求码不变。 */
    private fun performDelete(automation: Automation) {
        val app = applicationContext
        runIo {
            if (automation.triggerType == TriggerType.TIME) Scheduler.cancel(app, automation)
            runCatching { db.automationDao().delete(automation) }
            val automations = runCatching { db.automationDao().getAll() }.getOrDefault(emptyList())
            postUi {
                adapter.submit(automations)
                binding.tvEmpty.visibility = if (automations.isEmpty()) View.VISIBLE else View.GONE
                Snackbar.make(binding.root, "已删除「${automation.name}」", Snackbar.LENGTH_LONG)
                    .setAction("撤销") { undoDelete(automation) } // 闭包持有原 Automation（含原 id）
                    .show()
            }
        }
    }

    /**
     * 撤销删除 = 用原 id 重插 + 若为已启用的定时规则则重新排程。
     * 关键不变量：Room @PrimaryKey(autoGenerate=true) 显式带 id 会保留该 id，
     * Scheduler 用来区分不同闹钟的请求码（automation.id.toInt()）因此与删除前一致，
     * AlarmManager 不会出现重复闹钟/漏闹钟。
     */
    private fun undoDelete(automation: Automation) {
        val app = applicationContext
        runIo {
            runCatching { db.automationDao().insert(automation) } // 显式带原 id，requestCode 不变
            if (automation.triggerType == TriggerType.TIME && automation.enabled) Scheduler.schedule(app, automation)
            val automations = runCatching { db.automationDao().getAll() }.getOrDefault(emptyList())
            postUi {
                adapter.submit(automations)
                binding.tvEmpty.visibility = if (automations.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    // ---------- 防外屏息屏（root） ----------

    /**
     * 开关是否可用取决于悬浮窗权限（常亮悬浮窗是主力机制），不取决于 root。
     * root 只用来顺手把系统超时也顶高，属于加分项，异步探测完再更新副标题。
     */
    private fun setupAntiSleep() {
        syncAntiSleepUi()

        runIo {
            val rooted = RootUtils.hasRoot()
            postUi {
                if (!ScreenOnOverlay.canDraw(this)) return@postUi
                binding.tvAntiSleep.text =
                    if (rooted) "防外屏息屏（屏幕常亮，已叠加 root 增强）" else "防外屏息屏（屏幕常亮）"
            }
        }
    }

    /** 按当前权限与保存的开关状态刷新这一行 UI（不触发开关回调）。 */
    private fun syncAntiSleepUi() {
        if (!::binding.isInitialized) return
        val granted = ScreenOnOverlay.canDraw(this)
        binding.tvAntiSleep.text =
            if (granted) "防外屏息屏（屏幕常亮）" else "防外屏息屏 —— 需要悬浮窗权限"
        binding.swAntiSleep.setOnCheckedChangeListener(null)
        binding.swAntiSleep.isChecked = AntiSleep.isEnabled(this) && granted
        binding.swAntiSleep.isEnabled = true
        bindAntiSleepSwitch()
    }

    private fun bindAntiSleepSwitch() {
        binding.swAntiSleep.setOnCheckedChangeListener { view, checked ->
            if (checked && !ScreenOnOverlay.canDraw(this)) {
                // 没权限直接开不了，回滚开关并把用户送到授权页
                resetAntiSleepSwitch(false)
                Snackbar.make(binding.root, "需要悬浮窗权限才能防止外屏息屏", Snackbar.LENGTH_LONG)
                    .setAction("去授权") { requestOverlayPermission() }
                    .show()
                return@setOnCheckedChangeListener
            }

            view.isEnabled = false
            val app = applicationContext
            runIo {
                val ok = runCatching {
                    if (checked) AntiSleep.enable(app) else AntiSleep.disable(app)
                }.getOrDefault(false)
                // 悬浮窗挂在常驻服务上，Activity 退出后才好继续生效
                if (checked) KeepAliveService.start(app)
                postUi {
                    view.isEnabled = true
                    if (ok) {
                        val tip = if (checked) "已开启：屏幕（含外屏）保持常亮" else "已关闭：恢复系统默认息屏"
                        Snackbar.make(binding.root, tip, Snackbar.LENGTH_SHORT).show()
                    } else {
                        resetAntiSleepSwitch(!checked)
                        Snackbar.make(binding.root, "开启失败，请检查悬浮窗权限是否被系统撤回", Snackbar.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    /** 改开关状态但不触发回调，避免回滚时递归。 */
    private fun resetAntiSleepSwitch(checked: Boolean) {
        binding.swAntiSleep.setOnCheckedChangeListener(null)
        binding.swAntiSleep.isChecked = checked
        binding.swAntiSleep.isEnabled = true
        bindAntiSleepSwitch()
    }

    private fun requestOverlayPermission() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
    }

    // ---------- 权限与厂商限制引导 ----------

    /**
     * 只在首次启动时集中引导一次，避免每次打开都往设置页跳。
     * 用户跳过后可在系统设置里自行开启，不再打扰。
     */
    private fun checkPermissionsOnce() {
        val sp = getSharedPreferences("quicklaunch", Context.MODE_PRIVATE)
        if (sp.getBoolean("guided", false)) return

        // 运行时权限一次性申请：
        // - POST_NOTIFICATIONS(13+)：兜底全屏通知的送达前提
        // - BLUETOOTH_CONNECT(12+)：不授权就读不到 BluetoothDevice.name，
        //   「连接指定蓝牙设备」这类规则会永远不触发（此前完全没申请过，属于静默失效）
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

        // 各项系统查询在部分定制 ROM 上会抛异常，逐项容错，别让引导弹窗把首启弄崩
        val missing = buildList {
            if (runCatching { !Settings.canDrawOverlays(this@MainActivity) }.getOrDefault(false)) {
                add("悬浮窗权限 —— 后台自动拉起应用的必备条件，不开则只能靠点通知启动")
            }
            val ignoring = runCatching {
                (getSystemService(POWER_SERVICE) as? PowerManager)
                    ?.isIgnoringBatteryOptimizations(packageName) ?: true
            }.getOrDefault(true)
            if (!ignoring) add("忽略电池优化 —— 否则休眠时定时任务会被系统推迟或杀掉")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val exact = runCatching {
                    (getSystemService(ALARM_SERVICE) as? AlarmManager)?.canScheduleExactAlarms() ?: true
                }.getOrDefault(true)
                if (!exact) add("精确闹钟 —— 否则定时触发会有几分钟误差")
            }
            add("自启动 / 后台弹出界面 —— Motorola myui 等厂商 ROM 需在「应用管理」中单独放行")
        }

        // Activity 已在销毁流程中时 show() 会抛「访问已销毁界面」异常
        if (isFinishing || isDestroyed) return
        runCatching {
            AlertDialog.Builder(this)
                .setTitle("需要开启以下权限")
                .setMessage(missing.joinToString("\n\n• ", prefix = "• "))
                .setPositiveButton("去设置") { _, _ ->
                    sp.edit().putBoolean("guided", true).apply()
                    openSettings()
                }
                .setNegativeButton("以后再说") { _, _ ->
                    sp.edit().putBoolean("guided", true).apply()
                }
                .show()
        }
    }

    /** 依次尝试跳转，跳不动就退回本应用的系统设置详情页（myui 等 ROM 常缺其中某些页面）。 */
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
                // 该机型不支持此设置页，尝试下一个
            } catch (_: SecurityException) {
            }
        }
    }
}
