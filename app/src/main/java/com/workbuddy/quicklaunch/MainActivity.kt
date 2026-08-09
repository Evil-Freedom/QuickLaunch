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
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.workbuddy.quicklaunch.data.AppDatabase
import com.workbuddy.quicklaunch.databinding.ActivityMainBinding
import com.workbuddy.quicklaunch.receiver.WifiReceiver
import com.workbuddy.quicklaunch.service.KeepAliveService
import com.workbuddy.quicklaunch.util.AntiSleep
import com.workbuddy.quicklaunch.util.QuickLaunchExecutors
import com.workbuddy.quicklaunch.util.ScreenOnOverlay

/**
 * 主入口：底部双 Tab 导航，彻底取消二级页跳转。
 * - Tab 1 快捷启动：规则创建表单 + 规则列表平铺在同一页。
 * - Tab 2 同步源：法定节假日同步、数据源选择、手动管理、自定义源管理。
 *
 * 性能优化：使用 FragmentStateAdapter 实现 View 懒加载，
 * 避免启动时一次性 inflate 两个 Tab 的完整 View 树。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var db: AppDatabase

    // ── 底部悬浮导航 ──
    private lateinit var bottomNavLaunch: View
    private lateinit var bottomNavSync: View
    private lateinit var ivNavLaunch: ImageView
    private lateinit var ivNavSync: ImageView
    private lateinit var tvNavLaunch: TextView
    private lateinit var tvNavSync: TextView

    // Fragment 引用由 FragmentStateAdapter 管理，此处不需要持有强引用

    // ── onResume 防抖 ──
    private var lastResumeRefresh = 0L
    private val RESUME_DEBOUNCE_MS = 500L

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

        setupViewPager()
        setupFloatingBottomBar()

        KeepAliveService.start(this)
        checkPermissionsOnce()

        // 首次启动（本地无节假日数据）自动同步一次，便于「跳过节假日」立即生效。
        runIo {
            val empty = runCatching { db.holidayDao().count() == 0 }.getOrDefault(false)
            if (empty) postUi { findSyncFragment()?.onSyncPageSelected() }
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
    // 底部悬浮毛玻璃底栏 + ViewPager2（FragmentStateAdapter 懒加载）
    // ═══════════════════════════════════════════════════════════════════

    private fun setupViewPager() {
        binding.viewPager.adapter = MainPagerAdapter(this)
        // 关键：只保留相邻 1 页在内存，其余销毁，实现真正的懒加载
        binding.viewPager.offscreenPageLimit = 1
        setupFloatingBottomBar()
    }

    private class MainPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = 2

        override fun createFragment(position: Int): Fragment = when (position) {
            TAB_LAUNCH -> LaunchFragment()
            TAB_SYNC -> SyncFragment()
            else -> throw IllegalArgumentException("Unknown tab: $position")
        }
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
                    findSyncFragment()?.onSyncPageSelected()
                } else {
                    findLaunchFragment()?.refreshRules()
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
    // 生命周期 / 权限
    // ═══════════════════════════════════════════════════════════════════

    override fun onResume() {
        super.onResume()
        // 防抖：高频切换（设置 → 返回）时跳过重复刷新
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastResumeRefresh < RESUME_DEBOUNCE_MS) return
        lastResumeRefresh = now

        // 刷新当前可见页
        findLaunchFragment()?.refreshRules()
        findSyncFragment()?.onSyncPageSelected()
        if (ScreenOnOverlay.canDraw(this) && !AntiSleep.isDisabled(this)) {
            KeepAliveService.start(this)
        }
    }

    /** ViewPager2 FragmentStateAdapter 的 fragment tag 前缀 */
    private fun fragmentTag(position: Int): String = "f$position"

    /** 获取当前附加的 LaunchFragment（如已创建） */
    private fun findLaunchFragment(): LaunchFragment? =
        supportFragmentManager.findFragmentByTag(fragmentTag(TAB_LAUNCH)) as? LaunchFragment

    /** 获取当前附加的 SyncFragment（如已创建） */
    private fun findSyncFragment(): SyncFragment? =
        supportFragmentManager.findFragmentByTag(fragmentTag(TAB_SYNC)) as? SyncFragment

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
            androidx.appcompat.app.AlertDialog.Builder(this)
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
