package com.workbuddy.quicklaunch

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.workbuddy.quicklaunch.databinding.ActivityThemeSettingsBinding
import com.workbuddy.quicklaunch.util.ThemeManager

/**
 * 主题设置页：用户可切换主题色（薄荷绿/丁香紫/经典绿）与深浅模式。
 * 选择即时预览，需重启 Activity 使新主题完整生效（setTheme + recreate）。
 */
class ThemeSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityThemeSettingsBinding

    /** 当前选中的主题色 ID。 */
    private var selectedThemeId: String = ThemeManager.MINT_GREEN.id

    override fun onCreate(savedInstanceState: Bundle?) {
        // 必须在 super.onCreate 前注入主题，否则窗口背景仍是旧色
        ThemeManager.init(this)
        applyTheme(ThemeManager.getCurrentPreset(this).id)
        super.onCreate(savedInstanceState)
        binding = ActivityThemeSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        selectedThemeId = ThemeManager.getCurrentPreset(this).id
        bindThemeSelection()
        bindThemeMode()
    }

    /** 应用指定主题色（仅本次 Activity 实例，不持久化）。 */
    private fun applyTheme(themeId: String) {
        val styleRes = when (themeId) {
            ThemeManager.LILAC.id -> R.style.Theme_QuickLaunch_Lavender
            ThemeManager.CLASSIC_GREEN.id -> R.style.Theme_QuickLaunch_Classic
            else -> R.style.Theme_QuickLaunch_Mint
        }
        setTheme(styleRes)
    }

    /** 主题色选择：三选一，点击卡片即选中，实时预览。 */
    private fun bindThemeSelection() {
        updateRadioButtons()

        binding.cardMint.setOnClickListener {
            selectedThemeId = ThemeManager.MINT_GREEN.id
            updateRadioButtons()
            applyThemeWithRecreate()
        }
        binding.cardLavender.setOnClickListener {
            selectedThemeId = ThemeManager.LILAC.id
            updateRadioButtons()
            applyThemeWithRecreate()
        }
        binding.cardClassic.setOnClickListener {
            selectedThemeId = ThemeManager.CLASSIC_GREEN.id
            updateRadioButtons()
            applyThemeWithRecreate()
        }
    }

    private fun updateRadioButtons() {
        binding.rbMint.isChecked = selectedThemeId == ThemeManager.MINT_GREEN.id
        binding.rbLavender.isChecked = selectedThemeId == ThemeManager.LILAC.id
        binding.rbClassic.isChecked = selectedThemeId == ThemeManager.CLASSIC_GREEN.id
    }

    /** 切换主题色后重启 Activity 使新主题完整生效，并持久化选择。 */
    private fun applyThemeWithRecreate() {
        ThemeManager.setPresetColor(this, ThemeManager.PRESETS.first { it.id == selectedThemeId })
        recreate()
    }

    /** 深浅模式选择：即时生效。 */
    private fun bindThemeMode() {
        val currentMode = ThemeManager.getThemeMode(this)
        val checkedButtonId = when (currentMode) {
            ThemeManager.MODE_LIGHT -> R.id.btnModeLight
            ThemeManager.MODE_DARK -> R.id.btnModeDark
            else -> R.id.btnModeSystem
        }
        binding.toggleThemeMode.check(checkedButtonId)

        binding.toggleThemeMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                R.id.btnModeLight -> ThemeManager.MODE_LIGHT
                R.id.btnModeDark -> ThemeManager.MODE_DARK
                else -> ThemeManager.MODE_SYSTEM
            }
            if (mode != ThemeManager.getThemeMode(this)) {
                ThemeManager.setThemeMode(this, mode)
            }
        }
    }
}
