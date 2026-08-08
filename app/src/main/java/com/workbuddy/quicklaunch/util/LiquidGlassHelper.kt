package com.workbuddy.quicklaunch.util

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi

/**
 * iOS 26 Liquid Glass 毛玻璃工具类
 * API 31+ 使用 RenderEffect 硬件高斯模糊
 * 低版本降级为半透明遮罩
 */
object LiquidGlassHelper {

    private const val BLUR_RADIUS = 25f

    /**
     * 为任意 View 施加液态玻璃效果
     * @param view 目标 View
     * @param blurRadius 模糊半径（默认 25f）
     * @param tileMode 填充模式（默认 MIRROR）
     */
    @JvmStatic
    fun apply(
        view: View,
        blurRadius: Float = BLUR_RADIUS,
        tileMode: Shader.TileMode = Shader.TileMode.MIRROR
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            applyBlurEffect(view, blurRadius, tileMode)
        }
        // 低版本：半透明背景已在 XML drawable 中定义，无需额外处理
    }

    /**
     * 为 Dock 栏施加更强的液态玻璃效果
     */
    @JvmStatic
    fun applyDock(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            applyBlurEffect(view, 35f, Shader.TileMode.CLAMP)
        }
    }

    /**
     * 为搜索栏施加轻微液态玻璃效果
     */
    @JvmStatic
    fun applySearch(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            applyBlurEffect(view, 20f, Shader.TileMode.MIRROR)
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun applyBlurEffect(
        view: View,
        radius: Float,
        tileMode: Shader.TileMode
    ) {
        view.setRenderEffect(
            RenderEffect.createBlurEffect(radius, radius, tileMode)
        )
    }

    /**
     * 清除毛玻璃效果
     */
    @JvmStatic
    fun clear(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.setRenderEffect(null)
        }
    }
}
