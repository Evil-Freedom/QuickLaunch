package com.workbuddy.quicklaunch.util

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.workbuddy.quicklaunch.R

/**
 * 主题颜色管理器。
 * 支持薄荷绿（默认）、丁香紫、经典绿、珊瑚橙四套预设主题色。
 *
 * 颜色持久化到 SharedPreferences，应用启动时自动恢复。
 */
object ThemeManager {

    private const val SP = "quicklaunch_theme"
    private const val KEY_PRIMARY_COLOR = "theme_primary_color"
    private const val KEY_THEME_MODE = "theme_mode"

    /** 主题模式：跟随系统 / 浅色 / 深色 */
    const val MODE_SYSTEM = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    const val MODE_LIGHT = AppCompatDelegate.MODE_NIGHT_NO
    const val MODE_DARK = AppCompatDelegate.MODE_NIGHT_YES

    /** 预设主题色：薄荷绿（默认） */
    val MINT_GREEN = PresetColor(
        id = "mint_green",
        label = "薄荷绿",
        primary = 0xFF5EA88B.toInt(),          // 与 colors.xml 中 m3_primary 对齐
        onPrimary = 0xFFFFFFFF.toInt(),
        primaryContainer = 0xFFB8E6D4.toInt(),
        onPrimaryContainer = 0xFF00210A.toInt()
    )

    /** 预设主题色：丁香紫 */
    val LILAC = PresetColor(
        id = "lilac",
        label = "丁香紫",
        primary = 0xFF9B7EC4.toInt(),
        onPrimary = 0xFFFFFFFF.toInt(),
        primaryContainer = 0xFFD4C4ED.toInt(),
        onPrimaryContainer = 0xFF1A0A30.toInt()
    )

    /** 预设主题色：经典绿 */
    val CLASSIC_GREEN = PresetColor(
        id = "classic_green",
        label = "经典绿",
        primary = 0xFF2E7D32.toInt(),
        onPrimary = 0xFFFFFFFF.toInt(),
        primaryContainer = 0xFFA8EDA5.toInt(),
        onPrimaryContainer = 0xFF002105.toInt()
    )

    /** 预设主题色：珊瑚橙 */
    val CORAL = PresetColor(
        id = "coral",
        label = "珊瑚橙",
        primary = 0xFFFF7043.toInt(),
        onPrimary = 0xFFFFFFFF.toInt(),
        primaryContainer = 0xFFFFCCBC.toInt(),
        onPrimaryContainer = 0xFFBF360C.toInt()
    )

    /** 所有预设主题色 */
    val PRESETS: List<PresetColor> = listOf(MINT_GREEN, LILAC, CLASSIC_GREEN, CORAL)

    /**
     * 主题色描述。
     * @param id 唯一标识
     * @param label 显示名称
     * @param primary 主色
     * @param onPrimary 主色上的文字色
     * @param primaryContainer 主色容器（浅底）
     * @param onPrimaryContainer 主色容器上的文字色
     */
    data class PresetColor(
        val id: String,
        val label: String,
        val primary: Int,
        val onPrimary: Int,
        val primaryContainer: Int,
        val onPrimaryContainer: Int
    )

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)

    /** 获取当前主题模式。 */
    fun getThemeMode(ctx: Context): Int =
        prefs(ctx).getInt(KEY_THEME_MODE, MODE_SYSTEM)

    /** 设置主题模式。 */
    fun setThemeMode(ctx: Context, mode: Int) {
        prefs(ctx).edit().putInt(KEY_THEME_MODE, mode).apply()
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    /** 获取当前主色值（ARGB 整数）。 */
    fun getPrimaryColor(ctx: Context): Int {
        val presetId = prefs(ctx).getString(KEY_PRIMARY_COLOR, MINT_GREEN.id)
        return PRESETS.firstOrNull { it.id == presetId }?.primary ?: MINT_GREEN.primary
    }

    /** 获取当前完整的预设颜色配置。 */
    fun getCurrentPreset(ctx: Context): PresetColor {
        val presetId = prefs(ctx).getString(KEY_PRIMARY_COLOR, MINT_GREEN.id)
        return PRESETS.firstOrNull { it.id == presetId } ?: MINT_GREEN
    }

    /** 设置预设主题色。 */
    fun setPresetColor(ctx: Context, preset: PresetColor) {
        prefs(ctx).edit().putString(KEY_PRIMARY_COLOR, preset.id).apply()
    }

    /**
     * 应用启动时初始化主题：恢复用户上次保存的主题模式。
     * 必须在 onCreate 中尽早调用。
     */
    fun init(ctx: Context) {
        val mode = getThemeMode(ctx)
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    /**
     * 从资源 ID 获取颜色（兼容主题切换）。
     */
    fun color(ctx: Context, resId: Int): Int = ContextCompat.getColor(ctx, resId)
}
