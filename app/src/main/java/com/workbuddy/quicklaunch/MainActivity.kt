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
import com.workbuddy.quicklaunch.util.Scheduler
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
     * 再 execute 会抛 RejectedExecutionException 直接崩溃。
     */
    private fun runIo(block: () -> Unit) {
        runCatching { io.execute { runCatching(block) } }
    }

    /** 回到主线程执行，并自动丢弃 Activity 已销毁后的回调（防泄漏 / 防 BadToken）。 */
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

        // 首次启动（本地无节假日数据）自动同步一次，便于「跳过节假日」立即生效。
        // count() 是磁盘 IO，放后台查，避免拖慢冷启动首帧。
        runIo {
            val empty = runCatching { db.holidayDao().count() == 0 }.getOrDefault(false)
            if (empty) postUi { syncHolidays() }
        }
    }

    /**
     * 数据源下拉：自动（推荐）优先用上次成功源，也可手动指定某一内置/自定义源（失败再回退其余）。
     * onResume 每次都会调用，这里做**幂等短路**：源列表没变就只更新选中项，
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
        if (binding.btnSyncHolidays.isEnabled) binding.btnSyncHolidays.isEnabled = false
        val pref = HolidayPrefs.getSourcePref(this)
        val prefId = if (pref == "auto") null else pref
        val app = applicationContext
        HolidaySync.sync(this, prefId) { res ->
            if (isFinishing || isDestroyed) return@sync
            binding.btnSyncHolidays.isEnabled = true
            val msg = if (res.success) {
                // 同步后重新排程，使「跳过节假日」立即按最新数据生效。
                // rescheduleAll 会读全表并逐条排程，必须放后台，否则规则一多主线程直接卡顿。
                runIo { Scheduler.rescheduleAll(app) }
                "已同步（来源：${res.sourceLabel}，${res.count} 天）"
            } else {
                "所有数据源同步失败，请检查网络后重试"
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
            val items = runCatching { db.automationDao().getAll() }.getOrDefault(emptyList())
            postUi {
                adapter.submit(items)
                binding.tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun onToggle(a: Automation, checked: Boolean) {
        val app = applicationContext
        runIo {
            runCatching { db.automationDao().update(a.copy(enabled = checked)) }
            if (a.triggerType == TriggerType.TIME) {
                if (checked) Scheduler.schedule(app, a.copy(enabled = true))
                else Scheduler.cancel(app, a)
            }
        }
    }

    private fun onDelete(a: Automation) {
        val app = applicationContext
        runIo {
            if (a.triggerType == TriggerType.TIME) Scheduler.cancel(app, a)
            runCatching { db.automationDao().delete(a) }
            val items = runCatching { db.automationDao().getAll() }.getOrDefault(emptyList())
            postUi {
                adapter.submit(items)
                binding.tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
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

        // Activity 已在销毁流程中时 show() 会抛 BadTokenException
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
