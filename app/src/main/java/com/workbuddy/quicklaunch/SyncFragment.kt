package com.workbuddy.quicklaunch

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.snackbar.Snackbar
import com.workbuddy.quicklaunch.databinding.ViewSyncBinding
import com.workbuddy.quicklaunch.util.AntiSleep
import com.workbuddy.quicklaunch.util.ScreenOnOverlay
import com.workbuddy.quicklaunch.util.SyncTabController

/**
 * Tab 2 — 同步源 + 防息屏。
 *
 * 持有 ViewSyncBinding + SyncTabController。
 * 由 MainActivity 的 FragmentStateAdapter 懒加载，避免启动时同时 inflate 两个 Tab。
 */
class SyncFragment : Fragment(), SyncTabController.SyncCallbacks {

    private var _binding: ViewSyncBinding? = null
    private val binding get() = _binding!!

    private lateinit var syncController: SyncTabController

    // ── 防息屏 View ──
    private lateinit var tvAntiSleep: android.widget.TextView
    private lateinit var swAntiSleep: SwitchCompat

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ViewSyncBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvAntiSleep = binding.tvAntiSleep
        swAntiSleep = binding.swAntiSleep

        syncController = SyncTabController(
            context = requireContext(),
            views = SyncTabController.SyncViews(
                spinnerSource = binding.spinnerSource,
                btnSyncHolidays = binding.btnSyncHolidays,
                btnManageHolidays = binding.btnManageHolidays,
                btnManageSources = binding.btnManageSources,
                tvAntiSleep = binding.tvAntiSleep,
                swAntiSleep = binding.swAntiSleep,
                layoutAntiSleep = binding.layoutAntiSleep,
                layoutHolidayCard = binding.layoutHolidayCard,
                dotTimor = binding.dotTimor,
                badgeTimor = binding.badgeTimor,
                dotHolidayCn = binding.dotHolidayCn,
                badgeHolidayCn = binding.badgeHolidayCn
            )
        )
        syncController.setup(this)
    }

    // ═══════════════════════════════════════════════════════════════════
    // SyncCallbacks 实现
    // ═══════════════════════════════════════════════════════════════════

    override fun showSnackbar(msg: String, duration: Int) {
        if (_binding == null) return
        runCatching { Snackbar.make(binding.root, msg, duration).show() }
    }

    override fun onSyncPageSelected() {
        if (_binding == null) return
        syncController.setupSourceSpinner(this)
        syncController.syncAntiSleepUi(this)
        syncController.updateSourceStatus()
    }

    override fun onSyncCompleted(success: Boolean) {
        if (_binding == null) return
        syncController.updateSourceStatus()
    }

    override val snackbarRoot: View get() = binding.root

    override fun onRootCheckResult(rooted: Boolean) {
        if (_binding == null) return
        tvAntiSleep.text = if (rooted)
            getString(R.string.main_anti_sleep_on)
        else
            getString(R.string.main_anti_sleep_on_no_root)
    }

    override fun requestOverlayPermission() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${requireContext().packageName}")
                )
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 生命周期
    // ═══════════════════════════════════════════════════════════════════

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
