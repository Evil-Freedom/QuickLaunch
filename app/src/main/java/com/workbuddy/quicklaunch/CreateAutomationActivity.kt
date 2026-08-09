package com.workbuddy.quicklaunch

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.workbuddy.quicklaunch.data.AppDatabase
import com.workbuddy.quicklaunch.databinding.ActivityCreateBinding
import com.workbuddy.quicklaunch.util.AutomationFormController

class CreateAutomationActivity : AppCompatActivity(), AutomationFormController.FormCallbacks {

    private lateinit var binding: ActivityCreateBinding
    private lateinit var db: AppDatabase
    private lateinit var formController: AutomationFormController

    // View 引用（供控制器回调刷新用）
    private val triggerChips = mutableListOf<TextView>()
    private val repeatChips = mutableListOf<TextView>()
    private val dayViews = mutableListOf<TextView>()

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
                layoutBt = binding.layoutBt,
                layoutCustomDays = binding.layoutCustomDays,
                etBtName = binding.etBtName,
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
        binding.layoutBt.visibility = if (formController.selectedTriggerIndex == 3) View.VISIBLE else View.GONE
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
