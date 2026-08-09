package com.workbuddy.quicklaunch.util

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.net.Uri
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.workbuddy.quicklaunch.HolidayManageActivity
import com.workbuddy.quicklaunch.R
import com.workbuddy.quicklaunch.SourceManageActivity

/**
 * 同步源 Tab + 防息屏 控制器。
 *
 * 从 MainActivity 抽离，封装：
 * - 数据源 Spinner（含自定义源）
 * - 节假日同步按钮
 * - 防息屏开关（悬浮窗权限检查）
 */
class SyncTabController(
    private val context: Context,
    private val views: SyncViews
) {
    interface SyncCallbacks {
        /** 显示 Snackbar（需传入根容器 View）。 */
        fun showSnackbar(msg: String, duration: Int)

        /** 同步源页面进入时调用（刷新 spinner、防息屏 UI）。 */
        fun onSyncPageSelected()

        /** 同步完成后调用（刷新数据源状态 UI）。 */
        fun onSyncCompleted(success: Boolean)

        /** Snackbar 根容器。 */
        val snackbarRoot: View

        /** 获取 root 检测结果回调。 */
        fun onRootCheckResult(rooted: Boolean)

        /** 请求悬浮窗权限。 */
        fun requestOverlayPermission()
    }

    data class SyncViews(
        val spinnerSource: Spinner,
        val btnSyncHolidays: MaterialButton,
        val btnManageHolidays: MaterialButton,
        val btnManageSources: MaterialButton,
        val tvAntiSleep: TextView,
        val swAntiSleep: SwitchCompat,
        val layoutAntiSleep: View,
        val layoutHolidayCard: View,
        // 数据源状态可视化
        val dotTimor: View? = null,
        val badgeTimor: TextView? = null,
        val dotHolidayCn: View? = null,
        val badgeHolidayCn: TextView? = null
    )

    private var sourceIds: List<String> = emptyList()

    // ── 初始化（类似 setupSyncTab） ──────────────────────────────
    fun setup(callbacks: SyncCallbacks) {
        views.btnSyncHolidays.setOnClickListener { syncHolidays(callbacks) }
        views.btnManageHolidays.setOnClickListener {
            context.startActivity(Intent(context, HolidayManageActivity::class.java))
        }
        views.btnManageSources.setOnClickListener {
            context.startActivity(Intent(context, SourceManageActivity::class.java))
        }
        setupSourceSpinner(callbacks)

        // 数据源状态可视化（初始）
        updateSourceStatus()

        // 防息屏
        syncAntiSleepUi(callbacks)
        QuickLaunchExecutors.io.execute {
            val rooted = RootUtils.hasRoot()
            val activity = context as? FragmentActivity
            activity?.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (ScreenOnOverlay.canDraw(context)) {
                    callbacks.onRootCheckResult(rooted)
                }
            }
        }
    }

    // ── Spinner ──────────────────────────────────────────────────
    fun setupSourceSpinner(callbacks: SyncCallbacks? = null) {
        val ids = mutableListOf("auto")
        val labels = mutableListOf(context.getString(R.string.main_source_auto))
        HolidaySources.ALL.forEach {
            ids.add(it.id)
            labels.add(it.label)
        }
        runCatching { HolidayPrefs.getCustomSources(context) }.getOrDefault(emptyList()).forEach {
            ids.add(it.id)
            labels.add(context.getString(R.string.main_source_custom, it.label))
        }

        val pref = runCatching { HolidayPrefs.getSourcePref(context) }.getOrNull() ?: "auto"
        val target = ids.indexOf(pref).coerceAtLeast(0)

        if (ids == sourceIds && views.spinnerSource.adapter != null) {
            if (views.spinnerSource.selectedItemPosition != target) {
                views.spinnerSource.setSelection(target)
            }
            return
        }
        sourceIds = ids

        views.spinnerSource.onItemSelectedListener = null
        val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        views.spinnerSource.adapter = adapter
        views.spinnerSource.setSelection(target)
        views.spinnerSource.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>, v: View?, pos: Int, id: Long) {
                val chosen = ids.getOrNull(pos) ?: return
                runCatching { HolidayPrefs.setSourcePref(context, chosen) }
            }
            override fun onNothingSelected(p: AdapterView<*>) {}
        }
    }

    // ── 数据源状态可视化 ──────────────────────────────────────────
    fun updateSourceStatus() {
        val dotTimor = views.dotTimor ?: return
        val badgeTimor = views.badgeTimor ?: return
        val dotHolidayCn = views.dotHolidayCn ?: return
        val badgeHolidayCn = views.badgeHolidayCn ?: return

        val lastGood = runCatching { HolidayPrefs.getLastGood(context) }.getOrNull()
        val synced = !lastGood.isNullOrBlank() && lastGood != "auto"

        // timor.tech — 自动模式下首选 timor，同步过即视为成功
        if (synced) {
            dotTimor.setBackgroundResource(R.drawable.status_dot_synced)
            badgeTimor.text = context.getString(R.string.glass_sync_success)
            badgeTimor.setBackgroundResource(R.drawable.bg_badge_success)
            badgeTimor.setTextColor(context.getColor(R.color.glass_badge_success_text))
        } else {
            dotTimor.setBackgroundResource(R.drawable.status_dot_unsynced)
            badgeTimor.text = context.getString(R.string.glass_sync_unsynced)
            badgeTimor.setBackgroundResource(R.drawable.bg_badge_pending)
            badgeTimor.setTextColor(context.getColor(R.color.glass_badge_pending_text))
        }

        // holiday-cn
        if (synced && (lastGood == "natescarlet_raw" || lastGood == "natescarlet_cdn")) {
            dotHolidayCn.setBackgroundResource(R.drawable.status_dot_synced)
            badgeHolidayCn.text = context.getString(R.string.glass_sync_success)
            badgeHolidayCn.setBackgroundResource(R.drawable.bg_badge_success)
            badgeHolidayCn.setTextColor(context.getColor(R.color.glass_badge_success_text))
        } else {
            dotHolidayCn.setBackgroundResource(R.drawable.status_dot_unsynced)
            badgeHolidayCn.text = context.getString(R.string.glass_sync_unsynced)
            badgeHolidayCn.setBackgroundResource(R.drawable.bg_badge_pending)
            badgeHolidayCn.setTextColor(context.getColor(R.color.glass_badge_pending_text))
        }
    }

    // ── 同步 ─────────────────────────────────────────────────────
    fun syncHolidays(callbacks: SyncCallbacks) {
        if (views.btnSyncHolidays.isEnabled) {
            views.btnSyncHolidays.isEnabled = false
            views.btnSyncHolidays.text = context.getString(R.string.main_syncing)
        }
        val pref = HolidayPrefs.getSourcePref(context)
        val prefId = if (pref == "auto") null else pref
        val app = context.applicationContext
        HolidaySync.sync(context, prefId) { res ->
            val activity = context as? FragmentActivity
            if (activity == null || activity.isFinishing || activity.isDestroyed) return@sync
            views.btnSyncHolidays.isEnabled = true
            views.btnSyncHolidays.text = context.getString(R.string.main_sync_holidays)
            val msg = if (res.success) {
                QuickLaunchExecutors.io.execute { Scheduler.rescheduleAll(app) }
                context.getString(R.string.main_synced, res.sourceLabel, res.count)
            } else {
                context.getString(R.string.main_sync_failed)
            }
            // 刷新数据源状态可视化
            updateSourceStatus()
            callbacks.onSyncCompleted(res.success)
            callbacks.showSnackbar(msg, Snackbar.LENGTH_SHORT)
        }
    }

    // ── 防息屏 ───────────────────────────────────────────────────
    fun syncAntiSleepUi(callbacks: SyncCallbacks) {
        val granted = ScreenOnOverlay.canDraw(context)
        views.tvAntiSleep.text = if (granted) {
            context.getString(R.string.main_anti_sleep_on_no_root)
        } else {
            "防外屏息屏 —— 需要悬浮窗权限"
        }
        views.swAntiSleep.setOnCheckedChangeListener(null)
        views.swAntiSleep.isChecked = AntiSleep.isEnabled(context) && granted
        views.swAntiSleep.isEnabled = true
        bindAntiSleepSwitch(callbacks)
    }

    private val swAntiSleep get() = views.swAntiSleep

    private fun bindAntiSleepSwitch(callbacks: SyncCallbacks) {
        swAntiSleep.setOnCheckedChangeListener { view, checked ->
            if (checked && !ScreenOnOverlay.canDraw(context)) {
                resetAntiSleepSwitch(false)
                Snackbar.make(callbacks.snackbarRoot, context.getString(R.string.main_anti_sleep_need_overlay), Snackbar.LENGTH_LONG)
                    .setAction(context.getString(R.string.main_anti_sleep_go_auth)) { callbacks.requestOverlayPermission() }
                    .show()
                return@setOnCheckedChangeListener
            }
            view.isEnabled = false
            val app = context.applicationContext
            QuickLaunchExecutors.io.execute {
                val ok = runCatching {
                    if (checked) AntiSleep.enable(app) else AntiSleep.disable(app)
                }.getOrDefault(false)
                if (checked) com.workbuddy.quicklaunch.service.KeepAliveService.start(app)
                val activity = context as? FragmentActivity
                activity?.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    view.isEnabled = true
                    if (ok) {
                        val tip = if (checked) context.getString(R.string.main_anti_sleep_turned_on) else context.getString(R.string.main_anti_sleep_turned_off)
                        callbacks.showSnackbar(tip, Snackbar.LENGTH_SHORT)
                    } else {
                        resetAntiSleepSwitch(!checked)
                        callbacks.showSnackbar(context.getString(R.string.main_anti_sleep_failed), Snackbar.LENGTH_LONG)
                    }
                }
            }
        }
    }

    fun resetAntiSleepSwitch(checked: Boolean) {
        swAntiSleep.setOnCheckedChangeListener(null)
        swAntiSleep.isChecked = checked
        swAntiSleep.isEnabled = true
    }
}
