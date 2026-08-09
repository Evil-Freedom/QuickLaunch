package com.workbuddy.quicklaunch

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.workbuddy.quicklaunch.adapter.BluetoothDeviceAdapter
import com.workbuddy.quicklaunch.data.AppDatabase
import com.workbuddy.quicklaunch.databinding.ActivityCreateBinding
import com.workbuddy.quicklaunch.databinding.DialogBluetoothDevicesBinding
import com.workbuddy.quicklaunch.util.AppListLoader
import com.workbuddy.quicklaunch.util.AutomationFormController
import com.workbuddy.quicklaunch.util.BluetoothDevices
import com.workbuddy.quicklaunch.util.DevicePickerBottomSheet
import com.workbuddy.quicklaunch.util.QuickLaunchExecutors
import com.workbuddy.quicklaunch.util.WifiNetworks
import androidx.recyclerview.widget.LinearLayoutManager

class CreateAutomationActivity : AppCompatActivity(), AutomationFormController.FormCallbacks {

    private lateinit var binding: ActivityCreateBinding
    private lateinit var db: AppDatabase
    private lateinit var formController: AutomationFormController

    // View 引用（供控制器回调刷新用）
    private val triggerChips = mutableListOf<TextView>()
    private val repeatChips = mutableListOf<TextView>()
    private val dayViews = mutableListOf<TextView>()

    // ── 蓝牙/WiFi 设备选择 ──
    private var selectedBluetoothName: String? = null
    private var selectedWifiName: String? = null
    private lateinit var btnPickBluetooth: MaterialButton
    private lateinit var btnPickWifi: MaterialButton

    // ── WiFi 运行时权限请求 ──
    private var pendingWifiPermissionCallback: ((Boolean) -> Unit)? = null

    private val wifiPermissionLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            pendingWifiPermissionCallback?.invoke(granted)
            pendingWifiPermissionCallback = null
        }

    /** 检查是否需要请求 WiFi 相关运行时权限 */
    private fun requestWifiPermissionIfNeeded(onResult: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ 需要 NEARBY_WIFI_DEVICES
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.NEARBY_WIFI_DEVICES
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
                    this, Manifest.permission.ACCESS_FINE_LOCATION
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

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityCreateBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // targetSdk 35+ 强制边到边，不消费 insets 表单顶部会被状态栏压住
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        db = AppDatabase.get(this)

        // 预加载应用列表：首次点击「启动应用」时直接命中缓存，零等待
        QuickLaunchExecutors.io.execute {
            AppListLoader.load(applicationContext)
        }

        triggerChips.clear()
        triggerChips.addAll(
            listOf(
                binding.chipTrigger0,
                binding.chipTrigger1,
                binding.chipTrigger2,
                binding.chipTrigger3
            )
        )
        repeatChips.clear()
        repeatChips.addAll(
            listOf(
                binding.chipRepeat0,
                binding.chipRepeat1,
                binding.chipRepeat2,
                binding.chipRepeat3,
                binding.chipRepeat4
            )
        )
        dayViews.clear()
        dayViews.addAll(
            listOf(
                binding.tbDay0, binding.tbDay1, binding.tbDay2, binding.tbDay3,
                binding.tbDay4, binding.tbDay5, binding.tbDay6
            )
        )

        btnPickBluetooth = binding.btnPickBluetooth
        btnPickWifi = binding.btnPickWifi
        btnPickBluetooth.setOnClickListener {
            showBluetoothDevicePicker()
        }
        btnPickWifi.setOnClickListener {
            showWifiNetworkPicker()
        }

        formController = AutomationFormController(
            context = this,
            db = db,
            views = AutomationFormController.FormViews(
                btnPickApp = binding.btnPickApp,
                btnTime = binding.btnTime,
                btnWinStart = binding.btnWinStart,
                btnWinEnd = binding.btnWinEnd,
                cbRandom = binding.cbRandom,
                cbSkipHolidays = binding.cbSkipHolidays,
                layoutRandom = binding.layoutRandom,
                layoutTime = binding.layoutTime,
                btnPickBluetooth = btnPickBluetooth,
                btnPickWifi = btnPickWifi,
                layoutCustomDays = binding.layoutCustomDays,
                btnSave = binding.btnSave,
                triggerChips = triggerChips,
                repeatChips = repeatChips,
                dayViews = dayViews,
                triggerIcons = null
            ),
            callbacks = this
        )
        formController.setup()
    }

    private fun showBluetoothDevicePicker() {
        val deviceNames = BluetoothDevices.getPairedDeviceNames(this)
        DevicePickerBottomSheet.newInstance(
            mode = DevicePickerBottomSheet.MODE_BLUETOOTH,
            devices = deviceNames,
            selectedName = selectedBluetoothName
        ).setOnSelectedListener { name ->
            selectedBluetoothName = name
            formController.bluetoothName = name.orEmpty()
            updateBluetoothButtonText()
        }.show(supportFragmentManager, "bluetooth_picker")
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
            val networkNames = WifiNetworks.getSavedNetworkNames(this)
            if (networkNames.isEmpty()) {
                // Android 16+ 无法枚举已保存网络，弹出手动输入弹窗
                showWifiManualInputDialog()
            } else {
                DevicePickerBottomSheet.newInstance(
                    mode = DevicePickerBottomSheet.MODE_WIFI,
                    devices = networkNames,
                    selectedName = selectedWifiName
                ).setOnSelectedListener { name ->
                    selectedWifiName = name
                    formController.wifiName = name.orEmpty()
                    updateWifiButtonText()
                }.show(supportFragmentManager, "wifi_picker")
            }
        }
    }

    /**
     * 手动输入 WiFi 名称弹窗。Android 16+ 无法枚举已保存的 WiFi 网络时，
     * 让用户手动输入 SSID 进行匹配。
     */
    private fun showWifiManualInputDialog() {
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.wifi_manual_input_hint)
            setSingleLine()
        }

        val currentSsid = WifiNetworks.getCurrentSsid(this)
        val message = if (currentSsid != null) {
            "${getString(R.string.wifi_manual_input_message)}\n\n${getString(R.string.wifi_manual_input_current, currentSsid)}"
        } else {
            getString(R.string.wifi_manual_input_message)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.wifi_manual_input_title)
            .setMessage(message)
            .setView(input)
            .setPositiveButton(R.string.wifi_manual_input_confirm) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    selectedWifiName = name
                    formController.wifiName = name
                    updateWifiButtonText()
                }
            }
            .setNegativeButton(R.string.wifi_manual_input_use_any) { _, _ ->
                selectedWifiName = null
                formController.wifiName = ""
                updateWifiButtonText()
            }
            .setNeutralButton(R.string.main_cancel, null)
            .show()
    }

    private fun updateWifiButtonText() {
        btnPickWifi.text = if (selectedWifiName.isNullOrEmpty()) {
            "选择 WiFi 网络（可选）"
        } else {
            "已选择：$selectedWifiName"
        }
    }

    private fun toast(msg: String) {
        runCatching { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    // ═══════════════════════════════════════════════════════════════════
    // FormCallbacks 实现
    // ═══════════════════════════════════════════════════════════════════

    override fun onStateUiUpdate() {
        triggerChips.forEachIndexed { index, textView ->
            refreshChip(textView, index == formController.selectedTriggerIndex)
        }
        val isTime = formController.selectedTriggerIndex == 0
        binding.layoutTime.visibility = if (isTime) View.VISIBLE else View.GONE
        binding.cbRandom.visibility = if (isTime) View.VISIBLE else View.GONE
        binding.cbSkipHolidays.visibility = if (isTime) View.VISIBLE else View.GONE
        if (!isTime) {
            binding.layoutRandom.visibility = View.GONE
            binding.btnTime.visibility = View.VISIBLE
            binding.cbRandom.isChecked = false
            binding.cbSkipHolidays.isChecked = false
            formController.randomWindow = false
            formController.skipHolidays = false
        }
        repeatChips.forEachIndexed { index, textView ->
            refreshChip(textView, index == formController.selectedRepeatIndex)
        }
        val isCustom = formController.selectedRepeatIndex == 3
        binding.layoutCustomDays.visibility = if (isCustom) View.VISIBLE else View.GONE
        dayViews.forEachIndexed { idx, view ->
            refreshChip(view, formController.selectedDays[idx])
        }
        btnPickBluetooth.visibility = if (formController.selectedTriggerIndex == 3) View.VISIBLE else View.GONE
        btnPickWifi.visibility = if (formController.selectedTriggerIndex == 2) View.VISIBLE else View.GONE
    }

    override fun onAppPicked(appName: String) {
        binding.btnPickApp.text = "已选择：$appName"
    }

    override fun onAppPickEmpty() {
        toast(getString(R.string.create_no_apps))
    }

    override fun onValidationError(messageResId: Int) {
        toast(getString(messageResId))
    }

    override fun onSaveSuccess() {
        toast(getString(R.string.create_saved))
        finish()
    }

    override fun onSaveFailed() {
        binding.btnSave.isEnabled = true
        toast(getString(R.string.create_save_failed))
    }

    override fun onFormReset() {
        // CreateAutomationActivity 保存后直接 finish，无需重置
    }

    // ═══════════════════════════════════════════════════════════════════
    // 视觉刷新辅助
    // ═══════════════════════════════════════════════════════════════════

    private fun refreshChip(textView: TextView, selected: Boolean) {
        if (selected) {
            textView.setBackgroundResource(R.drawable.bg_dark_capsule_selected)
            textView.setTextColor(resources.getColor(R.color.dark_bg_primary, null))
            textView.setTypeface(null, android.graphics.Typeface.BOLD)
        } else {
            textView.setBackgroundResource(R.drawable.bg_dark_capsule_unselected)
            textView.setTextColor(resources.getColor(R.color.dark_text_secondary, null))
            textView.setTypeface(null, android.graphics.Typeface.NORMAL)
        }
    }
}
