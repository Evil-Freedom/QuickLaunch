package com.workbuddy.quicklaunch.util

import android.animation.ArgbEvaluator
import android.annotation.SuppressLint
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.workbuddy.quicklaunch.R

/**
 * iOS 风格 3D 滚轮时间选择器（全屏 BottomSheet）。
 *
 * - 深灰胶囊背景 + 顶部取消/完成
 * - 左右两个滚轮：时 / 分，严格只显示 5 行
 * - 中间选中行：28sp 纯白 100% + 粗体
 * - 相邻行（第 2/4 行）：20sp #A1A1AA 50%
 * - 边缘行（第 1/5 行）：16sp #71717A 20%
 * - 高亮区为上下两条 1dp 微光隔线（#333338），取代暗灰底框
 * - 用 RecyclerView + LinearSnapHelper 实现居中吸附，滚动时实时计算字号/颜色/透明度/缩放形成 3D 景深
 * - 小时 (0..23) 与分钟 (0..59) 均无限循环
 *
 * 用法：
 *   DarkWheelTimePicker.newInstance(hour, minute)
 *       .setOnConfirmListener { h, m -> ... }
 *       .show(supportFragmentManager, "tag")
 */
class DarkWheelTimePicker : BottomSheetDialogFragment() {

    private var initialHour = 8
    private var initialMinute = 0
    private var onConfirm: ((Int, Int) -> Unit)? = null

    private lateinit var rvHour: RecyclerView
    private lateinit var rvMinute: RecyclerView
    private lateinit var hourAdapter: WheelAdapter
    private lateinit var minuteAdapter: WheelAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            initialHour = it.getInt(ARG_HOUR, initialHour)
            initialMinute = it.getInt(ARG_MINUTE, initialMinute)
        }
        setStyle(STYLE_NORMAL, R.style.DarkBottomSheetDialogTheme)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_dark_wheel_time_picker, container, false)

    override fun onStart() {
        super.onStart()
        // 强制全屏展开：BottomSheetDialog 的 design_bottom_sheet 容器默认是 wrap_content，
        // 只包裹内容（约半屏高），单靠 STATE_EXPANDED + isFitToContents=false 无法撑满整屏。
        // 必须把该容器高度设为 MATCH_PARENT，配合 STATE_EXPANDED 才能铺满外屏。
        (dialog as? BottomSheetDialog)?.let { d ->
            val sheet = d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.layoutParams?.height = ViewGroup.LayoutParams.MATCH_PARENT
            val behavior = BottomSheetBehavior.from(sheet ?: return@let)
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
            behavior.isFitToContents = false
            behavior.peekHeight = 0
            // 关闭整张表的拖拽手势：否则 BottomSheetBehavior 会在滚轮上下滑动时
            // 拦截 MOVE 事件去「拖拽收起」整张表，导致分钟（甚至小时）滚轮卡死。
            // 全屏选择器已有「取消 / 完成」按钮，无需拖拽关闭。
            behavior.isDraggable = false
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rvHour = view.findViewById(R.id.rvHour)
        rvMinute = view.findViewById(R.id.rvMinute)

        hourAdapter = WheelAdapter((0..23).toList())
        minuteAdapter = WheelAdapter((0..59).toList())

        setupWheel(rvHour, hourAdapter, initialHour)
        setupWheel(rvMinute, minuteAdapter, initialMinute)

        view.findViewById<View>(R.id.btnCancel).setOnClickListener { dismiss() }
        view.findViewById<View>(R.id.btnConfirm).setOnClickListener {
            val h = valueAt(rvHour, hourAdapter)
            val m = valueAt(rvMinute, minuteAdapter)
            onConfirm?.invoke(h, m)
            dismiss()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupWheel(rv: RecyclerView, adapter: WheelAdapter, initial: Int) {
        val lm = LinearLayoutManager(requireContext(), LinearLayoutManager.VERTICAL, false)
        rv.layoutManager = lm
        rv.adapter = adapter
        LinearSnapHelper().attachToRecyclerView(rv)
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(r: RecyclerView, dx: Int, dy: Int) = updateWheelAlpha(r)
        })
        // 修复：BottomSheet / 外层 ScrollView 容易拦截滚轮垂直事件，导致分钟列「卡死」。
        // 按下/滑动时通知父容器不要拦截，让 RecyclerView 自己消费触摸。
        rv.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_MOVE -> v.parent.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> v.parent.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
        // 循环模式：初始位置映射到中间周期的对应值，保证上下都能继续滚动
        val startPos = adapter.positionOf(initial)
        rv.post {
            lm.scrollToPositionWithOffset(startPos, 0)
            updateWheelAlpha(rv)
        }
    }

    /** 距中心越远字号/透明度/缩放越小，形成 iOS 3D 景深。 */
    private fun updateWheelAlpha(rv: RecyclerView) {
        if (rv.height == 0) return
        val centerY = rv.height / 2f
        val lm = rv.layoutManager as LinearLayoutManager
        val itemHeight = rv.context.resources.getDimensionPixelSize(R.dimen.wheel_item_height).toFloat()
        if (itemHeight <= 0f) return

        val centerColor = ContextCompat.getColor(rv.context, R.color.dark_text_primary)
        val adjacentColor = ContextCompat.getColor(rv.context, R.color.dark_text_secondary)
        val edgeColor = ContextCompat.getColor(rv.context, R.color.dark_text_tertiary)
        val evaluator = ArgbEvaluator()

        for (i in 0 until lm.childCount) {
            val child = lm.getChildAt(i) ?: continue
            val tv = child as? TextView ?: continue
            val childCenter = (child.top + child.bottom) / 2f
            val ratio = kotlin.math.abs(childCenter - centerY) / itemHeight

            val textSizeSp: Float
            val alpha: Float
            val scale: Float
            val textColor: Int
            if (ratio <= 1f) {
                // 中心 → 相邻
                textSizeSp = lerp(28f, 20f, ratio)
                alpha = lerp(1f, 0.5f, ratio)
                scale = lerp(1f, 0.92f, ratio)
                textColor = evaluator.evaluate(ratio, centerColor, adjacentColor) as Int
            } else {
                // 相邻 → 边缘
                val r2 = (ratio - 1f).coerceIn(0f, 1f)
                textSizeSp = lerp(20f, 16f, r2)
                alpha = lerp(0.5f, 0.2f, r2)
                scale = lerp(0.92f, 0.84f, r2)
                textColor = evaluator.evaluate(r2, adjacentColor, edgeColor) as Int
            }

            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
            tv.alpha = alpha
            tv.setTextColor(textColor)
            // 缩放保持视觉中心不变，不改变布局高度
            child.scaleX = scale
            child.scaleY = scale
        }
    }

    private fun lerp(a: Float, b: Float, t: Float): Float =
        a + (b - a) * t.coerceIn(0f, 1f)

    /** 读取当前居中项对应的数值，比 findFirstCompletelyVisibleItemPosition 更稳。 */
    private fun valueAt(rv: RecyclerView, adapter: WheelAdapter): Int {
        val lm = rv.layoutManager as LinearLayoutManager
        if (rv.height == 0) return adapter.valueAt(initialPos(adapter))
        val centerY = rv.height / 2
        var bestPos = -1
        var bestDist = Int.MAX_VALUE
        for (i in 0 until lm.childCount) {
            val child = lm.getChildAt(i) ?: continue
            val childCenter = (child.top + child.bottom) / 2
            val dist = kotlin.math.abs(childCenter - centerY)
            if (dist < bestDist) {
                bestDist = dist
                bestPos = lm.getPosition(child)
            }
        }
        if (bestPos < 0) bestPos = lm.findFirstCompletelyVisibleItemPosition()
        if (bestPos < 0) bestPos = initialPos(adapter)
        return adapter.valueAt(bestPos)
    }

    private fun initialPos(adapter: WheelAdapter): Int =
        if (adapter === hourAdapter) adapter.positionOf(initialHour) else adapter.positionOf(initialMinute)

    fun setOnConfirmListener(block: (Int, Int) -> Unit): DarkWheelTimePicker {
        onConfirm = block
        return this
    }

    companion object {
        private const val ARG_HOUR = "arg_hour"
        private const val ARG_MINUTE = "arg_minute"

        fun newInstance(hour: Int, minute: Int): DarkWheelTimePicker {
            val f = DarkWheelTimePicker()
            f.arguments = Bundle().apply {
                putInt(ARG_HOUR, hour)
                putInt(ARG_MINUTE, minute)
            }
            return f
        }
    }
}
