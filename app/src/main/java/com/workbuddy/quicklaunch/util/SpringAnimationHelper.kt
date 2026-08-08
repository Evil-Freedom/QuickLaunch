package com.workbuddy.quicklaunch.util

import android.view.View
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

/**
 * iOS 风格弹簧动画工具类
 * 使用 AndroidX DynamicAnimation 实现物理弹性回弹
 */
object SpringAnimationHelper {

    /**
     * 为 View 创建弹性回弹动画
     * @param view 目标 View
     * @param property 动画属性
     * @param finalPosition 最终位置
     */
    @JvmStatic
    fun spring(
        view: View,
        property: SpringAnimation.ViewProperty,
        finalPosition: Float
    ) {
        SpringAnimation(view, property, finalPosition).apply {
            spring.stiffness = SpringForce.STIFFNESS_LOW
            spring.dampingRatio = SpringForce.DAMPING_RATIO_LOW_BOUNCY
            start()
        }
    }

    /**
     * 缩放回弹（iOS 风格）
     */
    @JvmStatic
    fun springScale(view: View, targetScale: Float) {
        spring(view, SpringAnimation.SCALE_X, targetScale)
        spring(view, SpringAnimation.SCALE_Y, targetScale)
    }

    /**
     * 按压回弹（先缩小再恢复）
     */
    @JvmStatic
    fun pressAndRelease(view: View) {
        // 按下：缩小到 0.95
        springScale(view, 0.95f)
        // 释放：延迟恢复
        view.postDelayed({
            springScale(view, 1f)
        }, 80)
    }
}
