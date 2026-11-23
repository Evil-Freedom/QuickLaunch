package com.workbuddy.quicklaunch

import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.workbuddy.quicklaunch.databinding.ActivityAutoUnlockBinding
import com.workbuddy.quicklaunch.util.AutoUnlock
import com.workbuddy.quicklaunch.util.AutoUnlockDecision
import com.workbuddy.quicklaunch.util.PinVault
import com.workbuddy.quicklaunch.util.RootUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 自动解锁的本地配置与状态页。
 *
 * ## 一条铁律
 *
 * 本页的一切操作都发生在手机上：输入的 PIN 只经过 [PinVault.seal] 落盘，
 * 不进入任何日志、审计或网络请求。看板上能看到的只有「是否启用 / 失败次数 /
 * 上次结果」这类策略与结果状态。
 */
class AutoUnlockActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAutoUnlockBinding
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAutoUnlockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 混淆态（保存时 KeyStore 不可用，或由 tools/seed_pin.py 注入）的密码，
        // 在 KeyStore 恢复可用后就地升级为加密存储，本页的「降级存储」警告因此只在真降级时出现。
        AutoUnlock.upgradePinStorageIfPossible(this)

        binding.btnSetPin.setOnClickListener { askPin() }
        binding.btnClearPin.setOnClickListener { confirmClearPin() }
        binding.swChargingOnly.setOnCheckedChangeListener { _, checked ->
            AutoUnlock.setChargingOnly(this, checked)
            render()
        }
        binding.swNotify.setOnCheckedChangeListener { _, checked ->
            AutoUnlock.setNotifyOnFailure(this, checked)
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    // ── 渲染 ────────────────────────────────────────────────────────

    private fun render() {
        val enabled = AutoUnlock.isEnabled(this)
        val hasPin = AutoUnlock.hasPin(this)
        val locked = AutoUnlock.isSecurityLocked(this)
        val failures = AutoUnlock.failures(this)
        val kind = AutoUnlock.pinStorageKind(this)

        binding.tvUnlockState.text = buildString {
            append("功能：")
            append(
                when {
                    locked -> "已因连续失败自动停用"
                    enabled && hasPin -> "已启用"
                    enabled && !hasPin -> "已开启但未设置密码（不会动作）"
                    else -> "已关闭"
                }
            )
            append("\n密码：")
            append(
                when (kind) {
                    PinVault.StorageKind.NONE -> "未设置"
                    PinVault.StorageKind.KEYSTORE -> "已设置（AndroidKeyStore 加密）"
                    PinVault.StorageKind.OBFUSCATED -> "已设置（⚠ 降级存储，非加密）"
                }
            )
            append("\n失败计数：").append(failures).append('/').append(AutoUnlock.MAX_FAILURES)
            append("\n上次尝试：")
            val at = AutoUnlock.lastAttemptAt(this@AutoUnlockActivity)
            append(if (at == 0L) "从未尝试" else timeFmt.format(Date(at)))
            append("\n上次结果：")
            append(
                when (val r = AutoUnlock.lastResult(this@AutoUnlockActivity)) {
                    AutoUnlock.RESULT_SUCCESS -> "成功"
                    AutoUnlock.RESULT_FAILED -> "失败"
                    "" -> "无记录"
                    else -> r
                }
            )
            if (locked) {
                append("\n停用原因：").append(
                    AutoUnlock.autoDisabledReason(this@AutoUnlockActivity)
                        .ifBlank { AutoUnlock.DISABLED_BY_FAILURES }
                )
                append("\n恢复方式：在本页重新设置密码并打开开关（远程无法恢复）")
            }
        }

        // 降级警告：只有「已保存的 PIN 确实是混淆态」才提示，不能拿
        // 「KeyStore 现在可用与否」下结论（可能在保存后才坏）
        if (kind == PinVault.StorageKind.OBFUSCATED) {
            binding.tvStorageWarning.visibility = View.VISIBLE
            binding.tvStorageWarning.text = "⚠ 当前密码使用 XOR 混淆存储（设备不支持 AndroidKeyStore）。" +
                "这不是加密，仅防止明文出现在配置文件里。建议不要在不可信环境下开启自动解锁，" +
                "或重新设置密码以尝试再次使用加密存储。"
        } else {
            binding.tvStorageWarning.visibility = View.GONE
        }

        binding.btnSetPin.text = if (hasPin) "修改密码" else "设置密码"
        binding.btnClearPin.isEnabled = hasPin
        binding.swChargingOnly.isChecked = AutoUnlock.isChargingOnly(this)
        binding.swChargingOnly.isEnabled = !locked
        binding.swNotify.isChecked = AutoUnlock.notifyOnFailure(this)
    }

    // ── PIN 输入 ────────────────────────────────────────────────────

    /**
     * 设置 / 修改密码。
     *
     * 「修改前明确本地确认」落在两处：对话框标题写明是替换，且要求**输入两次**
     * 新密码 —— 单次输入的误触率对「会往锁屏里注的东西」来说不可接受。
     */
    private fun askPin() {
        val hadPin = AutoUnlock.hasPin(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        val first = pinField(if (hadPin) "输入新密码" else "输入密码")
        val second = pinField("再输一遍确认")
        container.addView(first)
        container.addView(second)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (hadPin) "修改解锁密码" else "设置解锁密码")
            .setMessage(
                if (hadPin) "将替换已保存的密码。输错会导致自动解锁失败并计入失败保护。"
                else "密码只保存在这台手机上，永远不会被上传。"
            )
            .setView(container)
            .setPositiveButton("确认", null)
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val a = first.text?.toString().orEmpty()
                val b = second.text?.toString().orEmpty()
                when {
                    !AutoUnlockDecision.validPin(a) -> {
                        toast("密码必须是 4~16 位纯数字")
                    }
                    a != b -> toast("两次输入不一致")
                    else -> {
                        // 校验通过才落盘；setPin 内部会清零失败计数
                        // （用户刚确认过密码，旧的失败记录不再可信）
                        val ok = AutoUnlock.setPin(this, a)
                        if (ok) {
                            toast("密码已保存")
                            dialog.dismiss()
                            render()
                        } else {
                            toast("保存失败（加密存储不可用且混淆兜底也失败）")
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun pinField(hint: String): EditText = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        transformationMethod = PasswordTransformationMethod.getInstance()
        setHint(hint)
        filters = arrayOf(InputFilter.LengthFilter(16))
        setSingleLine()
    }

    private fun confirmClearPin() {
        MaterialAlertDialogBuilder(this)
            .setTitle("清除已保存的密码")
            .setMessage(
                "清除后自动解锁将不再动作（功能开关保持原状但没有密码可用）。\n" +
                    "远程服务器也看不到任何密码信息。确定清除？"
            )
            .setPositiveButton("清除") { _, _ ->
                AutoUnlock.setPin(this, null)
                // 密码没了，功能必然无法动作：一并关闭，避免界面显示「已启用但无密码」的歧义状态
                AutoUnlock.setEnabled(this, false)
                render()
                toast("已清除")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
