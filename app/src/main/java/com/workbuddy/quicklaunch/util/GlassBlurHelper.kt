package com.workbuddy.quicklaunch.util

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View

/**
 * Glassmorphism 毛玻璃模糊辅助类。
 *
 * 在 API 31+ 尝试为指定 View 绑定 RenderEffect 高斯模糊；
 * 若设备不支持或渲染异常，自动保留原有纯色背景，实现无缝降级。
 */
object GlassBlurHelper {

    /**
     * 为 View 应用毛玻璃模糊。
     *
     * @param view 目标 View
     * @param radius 模糊半径（px），默认 24f
     */
    @JvmStatic
    @JvmOverloads
    fun apply(view: View?, radius: Float = 24f) {
        view ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            val effect = RenderEffect.createBlurEffect(
                radius,
                radius,
                Shader.TileMode.MIRROR
            )
            view.setRenderEffect(effect)
        } catch (ignored: Exception) {
            // 降级：保持原有纯色背景，不阻断 UI 渲染
        }
    }

    /**
     * 批量应用相同半径的模糊。
     */
    @JvmStatic
    fun applyAll(views: List<View?>, radius: Float = 24f) {
        views.forEach { apply(it, radius) }
    }
}
