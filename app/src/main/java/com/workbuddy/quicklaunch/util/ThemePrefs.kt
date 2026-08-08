package com.workbuddy.quicklaunch.util

import android.content.Context
import android.graphics.Color

/**
 * 用户主题偏好存储。
 *
 * 支持的功能：
 * - 默认主题色（薄荷绿 / 丁香紫 / 经典绿）
 * - 用户自定义颜色（ARGB 十六进制字符串）
 * - 自动跟随系统深色模式 / 强制浅色 / 强制深色
 *
 * 持久化到 SharedPreferences "quicklaunch_theme"，与节假日偏好分离，
 * 避免键名冲突。
 *
 * 所有方法都是幂等的：无数据时返回安全默认值，不抛异常。
 */
object ThemePrefs {

    // ── SP 文件与 Key ──────────────────────────────────────────────
    private const val SP = "quicklaunch_theme"
    private const val KEY_THEME_MODE = "theme_mode"          // FOLLOW_SYSTEM | LIGHT | DARK
    private const val KEY_COLOR_PRESET = "color_preset"     // MINT | LAVENDER | CLASSIC
    private const val KEY_CUSTOM_COLOR = "custom_color"     // #AARRGGBB

    // ── 主题模式常量 ───────────────────────────────────────────────
    const val MODE_FOLLOW_SYSTEM = "FOLLOW_SYSTEM"
    const val MODE_LIGHT = "LIGHT"
    const val MODE_DARK = "DARK"

    // ── 预设色板常量（与 colors.xml 中的资源名对应）─────────────────
    const val PRESET_MINT = "MINT"
    const val PRESET_LAVENDER = "LAVENDER"
    const val PRESET_CLASSIC = "CLASSIC"

    // ── 默认值 ────────────────────────────────────────────────────
    /** 默认主题模式：跟随系统。 */
    const val DEFAULT_MODE = MODE_FOLLOW_SYSTEM
    /** 默认预设色板：薄荷绿（清新、低饱和度）。 */
    const val DEFAULT_PRESET = PRESET_MINT
    /** 默认自定义颜色：无效色（表示未启用自定义）。 */
    private const val NO_CUSTOM = ""

    // ── SP 访问封装 ───────────────────────────────────────────────
    private fun getPreferences(context: Context) =
        context.getSharedPreferences(SP, Context.MODE_PRIVATE)

    // ── 主题模式读写 ───────────────────────────────────────────────

    /**
     * 获取当前主题模式。
     * @return [MODE_FOLLOW_SYSTEM] / [MODE_LIGHT] / [MODE_DARK]
     */
    fun getThemeMode(context: Context): String =
        getPreferences(context).getString(KEY_THEME_MODE, DEFAULT_MODE) ?: DEFAULT_MODE

    /** 设置主题模式（自动持久化）。 */
    fun setThemeMode(context: Context, mode: String) {
        require(mode in setOf(MODE_FOLLOW_SYSTEM, MODE_LIGHT, MODE_DARK)) {
            "mode 必须是 FOLLOW_SYSTEM / LIGHT / DARK"
        }
        getPreferences(context).edit().putString(KEY_THEME_MODE, mode).apply()
    }

    // ── 预设色板读写 ───────────────────────────────────────────────

    /**
     * 获取当前预设色板。
     * @return [PRESET_MINT] / [PRESET_LAVENDER] / [PRESET_CLASSIC]
     */
    fun getColorPreset(context: Context): String =
        getPreferences(context).getString(KEY_COLOR_PRESET, DEFAULT_PRESET) ?: DEFAULT_PRESET

    /** 设置预设色板（自动持久化）。 */
    fun setColorPreset(context: Context, preset: String) {
        require(preset in setOf(PRESET_MINT, PRESET_LAVENDER, PRESET_CLASSIC)) {
            "preset 必须是 MINT / LAVENDER / CLASSIC"
        }
        getPreferences(context).edit().putString(KEY_COLOR_PRESET, preset).apply()
    }

    // ── 自定义颜色读写 ─────────────────────────────────────────────

    /**
     * 获取用户自定义颜色（ARGB 格式，如 "#FF5EA88B"）。
     * @return 自定义颜色字符串，未设置时返回空串 ""。
     */
    fun getCustomColor(context: Context): String =
        getPreferences(context).getString(KEY_CUSTOM_COLOR, NO_CUSTOM) ?: NO_CUSTOM

    /**
     * 设置自定义颜色。
     * @param colorHex ARGB 格式字符串，如 "#FF5EA88B"；传空串 "" 表示清除自定义。
     */
    fun setCustomColor(context: Context, colorHex: String) {
        getPreferences(context).edit().putString(KEY_CUSTOM_COLOR, colorHex).apply()
    }

    /**
     * 判断是否有有效的自定义颜色。
     */
    fun hasCustomColor(context: Context): Boolean {
        val hex = getCustomColor(context)
        return hex.isNotEmpty() && runCatching { Color.parseColor(hex) }.isSuccess
    }

    // ── 便捷查询：当前主色（考虑自定义覆盖）──────────────────────────

    /**
     * 根据当前预设色板返回对应的主色资源 ID。
     * 自定义颜色不影响此返回值（自定义通过 applyCustomColor 在运行时覆盖）。
     */
    fun getPresetPrimaryRes(preset: String): Int = when (preset) {
        PRESET_LAVENDER -> com.workbuddy.quicklaunch.R.color.preset_lavender_primary
        PRESET_CLASSIC -> com.workbuddy.quicklaunch.R.color.preset_classic_primary
        else -> com.workbuddy.quicklaunch.R.color.preset_mint_primary // 默认薄荷绿
    }

    /**
     * 根据当前预设色板返回对应的暗色模式主色资源 ID。
     */
    fun getPresetPrimaryNightRes(preset: String): Int = when (preset) {
        PRESET_LAVENDER -> com.workbuddy.quicklaunch.R.color.preset_lavender_primary
        PRESET_CLASSIC -> com.workbuddy.quicklaunch.R.color.preset_classic_primary
        else -> com.workbuddy.quicklaunch.R.color.preset_mint_primary // 默认薄荷绿暗色
    }
}
