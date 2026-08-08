package com.workbuddy.quicklaunch.util

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.workbuddy.quicklaunch.R

/**
 * 暗黑应用选择 BottomSheet：
 * - 28dp 顶部圆角暗色卡片
 * - 吸顶搜索框（ic_search_ios + “搜索全部应用…”）
 * - 列表项带 36dp 真实应用图标，支持按名称/包名实时过滤
 *
 * 用法：
 *   AppPickerBottomSheet.newInstance(apps)
 *       .setOnSelectedListener { app -> ... }
 *       .show(supportFragmentManager, "tag")
 */
class AppPickerBottomSheet : BottomSheetDialogFragment() {

    private var apps: List<AppInfo> = emptyList()
    private var onSelected: ((AppInfo) -> Unit)? = null

    private lateinit var rvApps: RecyclerView
    private lateinit var adapter: AppPickerAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("UNCHECKED_CAST")
        apps = arguments?.getParcelableArrayList<AppInfo>(ARG_APPS)?.toList().orEmpty()
        setStyle(STYLE_NORMAL, R.style.DarkBottomSheetDialogTheme)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_app_picker_bottom_sheet, container, false)

    override fun onStart() {
        super.onStart()
        // 强制全屏展开：design_bottom_sheet 容器默认 wrap_content，必须显式设为 MATCH_PARENT
        // 才能真正铺满整屏，彻底消除半屏 BottomSheet 的割裂感（背景全遮罩渐变同步生效）。
        (dialog as? BottomSheetDialog)?.let { d ->
            val sheet = d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.layoutParams?.height = ViewGroup.LayoutParams.MATCH_PARENT
            val behavior = BottomSheetBehavior.from(sheet ?: return@let)
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
            behavior.isFitToContents = false
            behavior.peekHeight = 0
            // 关闭整张表的拖拽手势，避免 BottomSheet 在列表滑动时拦截 MOVE 事件。
            behavior.isDraggable = false
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rvApps = view.findViewById(R.id.rvApps)
        rvApps.layoutManager = LinearLayoutManager(requireContext())

        adapter = AppPickerAdapter(requireContext().packageManager) { app ->
            onSelected?.invoke(app)
            dismiss()
        }
        adapter.setItems(apps)
        rvApps.adapter = adapter

        val etSearch = view.findViewById<EditText>(R.id.etSearch)
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) =
                filter(s?.toString().orEmpty())

            override fun afterTextChanged(s: Editable?) {}
        })
        // 不自动弹出键盘：cover screen 高度有限，先展示列表 + 图标，用户点搜索框再输入
    }

    private fun filter(query: String) {
        val q = query.trim().lowercase()
        val result = if (q.isEmpty()) {
            apps
        } else {
            apps.filter { it.appName.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
        }
        adapter.setItems(result)
    }

    fun setOnSelectedListener(block: (AppInfo) -> Unit): AppPickerBottomSheet {
        onSelected = block
        return this
    }

    companion object {
        private const val ARG_APPS = "arg_apps"

        fun newInstance(apps: List<AppInfo>): AppPickerBottomSheet {
            val f = AppPickerBottomSheet()
            f.arguments = Bundle().apply {
                putParcelableArrayList(ARG_APPS, ArrayList(apps))
            }
            return f
        }
    }
}
