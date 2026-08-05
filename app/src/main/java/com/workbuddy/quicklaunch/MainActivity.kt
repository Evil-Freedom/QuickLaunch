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
import com.workbuddy.quicklaunch.util.Scheduler
import com.google.android.material.snackbar.Snackbar
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var db: AppDatabase
    private val adapter = AutomationAdapter(emptyList(), ::onToggle, ::onDelete)

    /** root 命令会阻塞（首次还要等授权弹窗），一律丢到单线程池里跑，绝不占主线程。 */
    private val io = Executors.newSingleThreadExecutor()

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

        KeepAliveService.start(this)
        setupAntiSleep()
        checkPermissionsOnce()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        // 从悬浮窗授权页回来时权限可能刚变，重新对齐开关状态
        syncAntiSleepUi()
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

    private fun refresh() {
        val items = db.automationDao().getAll()
        adapter.submit(items)
        binding.tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun onToggle(a: Automation, checked: Boolean) {
        db.automationDao().update(a.copy(enabled = checked))
        if (a.triggerType == TriggerType.TIME) {
            if (checked) Scheduler.schedule(this, a.copy(enabled = true))
            else Scheduler.cancel(this, a)
        }
    }

    private fun onDelete(a: Automation) {
        if (a.triggerType == TriggerType.TIME) Scheduler.cancel(this, a)
        db.automationDao().delete(a)
        refresh()
    }

    // ---------- 防外屏息屏（root） ----------

    /**
     * 开关是否可用取决于悬浮窗权限（常亮悬浮窗是主力机制），不取决于 root。
     * root 只用来顺手把系统超时也顶高，属于加分项，异步探测完再更新副标题。
     */
    private fun setupAntiSleep() {
        syncAntiSleepUi()

        io.execute {
            val rooted = RootUtils.hasRoot()
            runOnUiThread {
                if (isFinishing || isDestroyed || !ScreenOnOverlay.canDraw(this)) return@runOnUiThread
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
            io.execute {
                val ok = if (checked) AntiSleep.enable(app) else AntiSleep.disable(app)
                // 悬浮窗挂在常驻服务上，Activity 退出后才好继续生效
                if (checked) KeepAliveService.start(app)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1
            )
        }

        val missing = buildList {
            if (!Settings.canDrawOverlays(this@MainActivity)) {
                add("悬浮窗权限 —— 后台自动拉起应用的必备条件，不开则只能靠点通知启动")
            }
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                add("忽略电池优化 —— 否则休眠时定时任务会被系统推迟或杀掉")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val am = getSystemService(ALARM_SERVICE) as AlarmManager
                if (!am.canScheduleExactAlarms()) add("精确闹钟 —— 否则定时触发会有几分钟误差")
            }
            add("自启动 / 后台弹出界面 —— Motorola myui 等厂商 ROM 需在「应用管理」中单独放行")
        }

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
