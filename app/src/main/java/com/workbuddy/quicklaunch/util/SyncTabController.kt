package com.workbuddy.quicklaunch.util

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.net.Uri
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
        // 锁屏自动解锁
        val swAutoUnlock: SwitchCompat,
        val tvAutoUnlockPin: TextView,
        val swAutoUnlockCharging: SwitchCompat,
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
        // 锁屏自动解锁
        syncAutoUnlockUi(callbacks)
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
                tappableSnackbar(
                    callbacks.snackbarRoot,
                    context.getString(R.string.main_anti_sleep_need_overlay),
                    context.getString(R.string.main_anti_sleep_tap_to_auth)
                ) { callbacks.requestOverlayPermission() }.show()
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

    // ── 锁屏自动解锁 ─────────────────────────────────────────────
    private val swAutoUnlock get() = views.swAutoUnlock

    /** 把持久化状态刷到 UI。每次都要先摘监听器，否则 isChecked 的回调会反过来写库。 */
    fun syncAutoUnlockUi(callbacks: SyncCallbacks) {
        val hasPin = AutoUnlock.hasPin(context)
        val enabled = AutoUnlock.isEnabled(context) && hasPin

        swAutoUnlock.setOnCheckedChangeListener(null)
        swAutoUnlock.isChecked = enabled
        swAutoUnlock.isEnabled = true

        views.tvAutoUnlockPin.text = if (hasPin) {
            val masked = "•".repeat(AutoUnlock.pin(context)?.length ?: 0)
            context.getString(R.string.auto_unlock_pin_set, masked)
        } else {
            context.getString(R.string.auto_unlock_pin_unset)
        }
        views.tvAutoUnlockPin.setOnClickListener { askPin(callbacks) { } }

        views.swAutoUnlockCharging.setOnCheckedChangeListener(null)
        views.swAutoUnlockCharging.isChecked = AutoUnlock.isChargingOnly(context)
        views.swAutoUnlockCharging.isEnabled = enabled
        views.swAutoUnlockCharging.setOnCheckedChangeListener { _, checked ->
            AutoUnlock.setChargingOnly(context, checked)
        }

        bindAutoUnlockSwitch(callbacks)
    }

    private fun resetAutoUnlockSwitch(checked: Boolean, callbacks: SyncCallbacks) {
        swAutoUnlock.setOnCheckedChangeListener(null)
        swAutoUnlock.isChecked = checked
        swAutoUnlock.isEnabled = true
        bindAutoUnlockSwitch(callbacks)
    }

    private fun bindAutoUnlockSwitch(callbacks: SyncCallbacks) {
        swAutoUnlock.setOnCheckedChangeListener { view, checked ->
            if (!checked) {
                AutoUnlock.setEnabled(context, false)
                syncAutoUnlockUi(callbacks)
                callbacks.showSnackbar(context.getString(R.string.auto_unlock_turned_off), Snackbar.LENGTH_SHORT)
                return@setOnCheckedChangeListener
            }
            // 开启前必须确认：有 root（注入输入事件）+ 有密码
            view.isEnabled = false
            QuickLaunchExecutors.io.execute {
                val rooted = runCatching { RootUtils.hasRoot() }.getOrDefault(false)
                val activity = context as? FragmentActivity
                activity?.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    view.isEnabled = true
                    if (!rooted) {
                        resetAutoUnlockSwitch(false, callbacks)
                        callbacks.showSnackbar(context.getString(R.string.auto_unlock_need_root), Snackbar.LENGTH_LONG)
                        return@runOnUiThread
                    }
                    val enable = {
                        AutoUnlock.setEnabled(context, true)
                        runCatching {
                            com.workbuddy.quicklaunch.service.KeepAliveService.start(context.applicationContext)
                        }
                        syncAutoUnlockUi(callbacks)
                        callbacks.showSnackbar(context.getString(R.string.auto_unlock_turned_on), Snackbar.LENGTH_SHORT)
                    }
                    if (AutoUnlock.hasPin(context)) {
                        enable()
                    } else {
                        askPin(callbacks) { ok -> if (ok) enable() else resetAutoUnlockSwitch(false, callbacks) }
                    }
                }
            }
        }
    }

    /**
     * 输入锁屏密码。用自定义 View 承载 EditText —— MaterialAlertDialogBuilder 的
     * setMessage 与 setItems 互斥，但 setView 可与 setMessage 共存。
     * 确定按钮手动接管点击，否则格式校验失败时对话框已经关掉了。
     */
    private fun askPin(callbacks: SyncCallbacks, onDone: (Boolean) -> Unit) {
        val ctx = context
        val input = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            // 仅靠 NUMBER_VARIATION_PASSWORD 在部分 ROM 上不生效（实测这台机器会明文回显），
            // 显式再挂一次密码变换，保证输入内容始终是圆点
            transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
            hint = ctx.getString(R.string.auto_unlock_dialog_hint)
            filters = arrayOf(InputFilter.LengthFilter(16))
            setSingleLine()
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val box = FrameLayout(ctx).apply {
            val pad = (8 * resources.displayMetrics.density).toInt()
            setPadding(pad, 0, pad, 0)
            addView(input)
        }
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.auto_unlock_dialog_title)
            .setMessage(R.string.auto_unlock_dialog_msg)
            .setView(box)
            .setPositiveButton(R.string.auto_unlock_ok, null)
            .setNeutralButton(R.string.auto_unlock_clear, null)
            .setNegativeButton(R.string.auto_unlock_dialog_cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = input.text?.toString()?.trim().orEmpty()
                if (!AutoUnlock.setPin(ctx, pin)) {
                    input.error = ctx.getString(R.string.auto_unlock_bad_pin)
                    return@setOnClickListener
                }
                dialog.dismiss()
                callbacks.showSnackbar(ctx.getString(R.string.auto_unlock_saved), Snackbar.LENGTH_SHORT)
                onDone(true)
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                AutoUnlock.setPin(ctx, null)
                AutoUnlock.setEnabled(ctx, false)
                dialog.dismiss()
                callbacks.showSnackbar(ctx.getString(R.string.auto_unlock_cleared), Snackbar.LENGTH_SHORT)
                onDone(false)
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                dialog.dismiss()
                onDone(false)
            }
        }
        dialog.show()
    }
}
