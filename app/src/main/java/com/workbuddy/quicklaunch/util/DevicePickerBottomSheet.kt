package com.workbuddy.quicklaunch.util

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.workbuddy.quicklaunch.R
import com.workbuddy.quicklaunch.adapter.DevicePickerAdapter

/**
 * 设备选择 BottomSheet — 与 App Picker 统一风格。
 *
 * 全屏暗色底 + 吸顶搜索框 + 列表项带图标 + 设备名。
 * 支持蓝牙设备 / WiFi 网络两种模式。
 *
 * 用法：
 *   DevicePickerBottomSheet.newInstance(
 *       mode = DevicePickerBottomSheet.MODE_BLUETOOTH,  // or MODE_WIFI
 *       devices = listOf("Device A", "Device B"),
 *       selectedName = currentlySelected
 *   ).setOnSelectedListener { name -> ... }
 *    .show(supportFragmentManager, "device_picker")
 */
class DevicePickerBottomSheet : BottomSheetDialogFragment() {

    private var mode: Int = MODE_BLUETOOTH
    private var devices: List<String> = emptyList()
    private var selectedName: String? = null
    private var onSelected: ((String?) -> Unit)? = null

    private lateinit var rvDevices: RecyclerView
    private lateinit var adapter: DevicePickerAdapter
    private lateinit var tvEmpty: TextView
    private lateinit var tvAnyDevice: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = arguments?.getInt(ARG_MODE) ?: MODE_BLUETOOTH
        devices = arguments?.getStringArrayList(ARG_DEVICES)?.toList().orEmpty()
        selectedName = arguments?.getString(ARG_SELECTED)
        setStyle(STYLE_NORMAL, R.style.DarkBottomSheetDialogTheme)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_device_picker_bottom_sheet, container, false)

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.let { d ->
            val sheet = d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.layoutParams?.height = ViewGroup.LayoutParams.MATCH_PARENT
            val behavior = BottomSheetBehavior.from(sheet ?: return@let)
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
            behavior.isFitToContents = false
            behavior.peekHeight = 0
            behavior.isDraggable = false
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val titleView = view.findViewById<TextView>(R.id.tvTitle)
        val subtitleHint = view.findViewById<EditText>(R.id.etSearch)
        tvEmpty = view.findViewById(R.id.tvEmpty)
        tvAnyDevice = view.findViewById(R.id.tvAnyDevice)

        if (mode == MODE_BLUETOOTH) {
            titleView.text = "选择蓝牙设备"
            subtitleHint.hint = "搜索蓝牙设备..."
            tvEmpty.text = "没有已配对的蓝牙设备"
            tvAnyDevice.text = "任意蓝牙设备（不限制）"
        } else {
            titleView.text = "选择 WiFi 网络"
            subtitleHint.hint = "搜索 WiFi 网络..."
            tvEmpty.text = "没有保存的 WiFi 网络"
            tvAnyDevice.text = "任意 WiFi 网络（不限制）"
        }

        rvDevices = view.findViewById(R.id.rvDevices)
        rvDevices.layoutManager = LinearLayoutManager(requireContext())

        adapter = DevicePickerAdapter(mode, devices, selectedName) { name ->
            onSelected?.invoke(name)
            dismiss()
        }
        rvDevices.adapter = adapter

        // 空状态
        if (devices.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            rvDevices.visibility = View.GONE
        } else {
            tvEmpty.visibility = View.GONE
            rvDevices.visibility = View.VISIBLE
        }

        // 任意设备
        tvAnyDevice.setOnClickListener {
            onSelected?.invoke(null)
            dismiss()
        }

        // 搜索过滤
        val etSearch = view.findViewById<EditText>(R.id.etSearch)
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) =
                filter(s?.toString().orEmpty())
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun filter(query: String) {
        val q = query.trim().lowercase()
        val result = if (q.isEmpty()) {
            devices
        } else {
            devices.filter { it.lowercase().contains(q) }
        }
        adapter.updateList(result)
    }

    fun setOnSelectedListener(block: (String?) -> Unit): DevicePickerBottomSheet {
        onSelected = block
        return this
    }

    companion object {
        private const val ARG_MODE = "arg_mode"
        private const val ARG_DEVICES = "arg_devices"
        private const val ARG_SELECTED = "arg_selected"

        const val MODE_BLUETOOTH = 0
        const val MODE_WIFI = 1

        fun newInstance(mode: Int, devices: List<String>, selectedName: String?): DevicePickerBottomSheet {
            val f = DevicePickerBottomSheet()
            f.arguments = Bundle().apply {
                putInt(ARG_MODE, mode)
                putStringArrayList(ARG_DEVICES, ArrayList(devices))
                putString(ARG_SELECTED, selectedName)
            }
            return f
        }
    }
}
