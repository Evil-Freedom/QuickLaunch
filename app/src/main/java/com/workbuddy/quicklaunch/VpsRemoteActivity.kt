package com.workbuddy.quicklaunch

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.workbuddy.quicklaunch.databinding.ActivityVpsRemoteBinding
import com.workbuddy.quicklaunch.launch.LaunchAttemptState
import com.workbuddy.quicklaunch.launch.LaunchPolicy
import com.workbuddy.quicklaunch.launch.LaunchSettings
import com.workbuddy.quicklaunch.remote.vps.VpsSettings
import com.workbuddy.quicklaunch.remote.vps.VpsSync
import com.workbuddy.quicklaunch.remote.vps.VpsSyncScheduler
import com.workbuddy.quicklaunch.util.QuickLaunchExecutors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 国内服务器（VPS）远程规则设置页。
 *
 * 与 [RemoteAccessActivity] 的分工：
 * - 那边是「局域网直连 + 公网中继」，手机当服务端，PC 连进来
 * - 这边是「手机主动出站拉取」，手机当客户端，不需要开放任何端口
 *
 * 两条通道彼此独立：一条关掉不影响另一条，配置也存在不同的 SP 里。
 */
class VpsRemoteActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVpsRemoteBinding
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVpsRemoteBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnVpsSave.setOnClickListener { saveAndSync() }
        binding.btnRetrySave.setOnClickListener { saveRetryPolicy() }
        binding.swVpsEnabled.setOnCheckedChangeListener { _, checked ->
            // 只切开关就立即持久化：用户切完直接退出页面也不该丢
            VpsSettings.setEnabled(this, checked)
            VpsSyncScheduler.schedule(this)
            renderStatus()
        }

        fillInputs()
        renderStatus()
        renderHistory()
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
        renderHistory()
    }

    private fun fillInputs() {
        val s = VpsSettings.load(this)
        binding.swVpsEnabled.isChecked = s.enabled
        binding.etVpsUrl.setText(s.baseUrl)
        binding.etVpsDevice.setText(s.deviceId)
        // 密钥不回显：用户重新填才会覆盖，避免截图/录屏泄露
        binding.etVpsSecret.setText("")
        binding.etVpsSecret.hint = if (s.secretB64.isNotBlank()) "已保存（留空则不修改）" else "只显示一次，填完即保存"
        binding.etVpsInterval.setText(s.intervalSec.toString())

        val p = LaunchSettings.loadPolicy(this)
        binding.etRetryMax.setText(p.maxAttempts.toString())
        binding.etRetryWindow.setText(p.verifyWindowMs.toString())
        binding.etRetryDelays.setText(p.retryDelaysMs.joinToString(","))
        binding.swRetryNotify.isChecked = p.notifyOnFailure
    }

    private fun saveAndSync() {
        val urlRaw = binding.etVpsUrl.text?.toString().orEmpty()
        val urlError = VpsSettings.validateUrl(urlRaw)
        if (urlError != null) {
            toast(urlError)
            return
        }
        val deviceId = binding.etVpsDevice.text?.toString().orEmpty().trim()
        if (deviceId.isBlank()) {
            toast("请填写设备 ID")
            return
        }
        val secretInput = binding.etVpsSecret.text?.toString().orEmpty().trim()
        val existing = VpsSettings.load(this)
        if (secretInput.isBlank() && existing.secretB64.isBlank()) {
            toast("请填写设备密钥")
            return
        }
        val interval = binding.etVpsInterval.text?.toString()?.toIntOrNull()
            ?: VpsSettings.DEFAULT_INTERVAL_SEC

        VpsSettings.setBaseUrl(this, urlRaw)
        VpsSettings.setDeviceId(this, deviceId)
        if (secretInput.isNotBlank()) VpsSettings.setSecret(this, secretInput)
        VpsSettings.setInterval(this, interval)
        VpsSettings.setEnabled(this, true)
        binding.swVpsEnabled.isChecked = true
        // 换了地址/身份之后，旧的已应用版本没有意义了：清掉让下一次拉取拿全量
        if (existing.deviceId != deviceId || existing.baseUrl != urlRaw.trim().trimEnd('/')) {
            VpsSettings.setAppliedVersion(this, 0L)
        }
        binding.etVpsSecret.setText("")

        syncNow()
    }

    private fun syncNow() {
        binding.tvVpsStatus.text = "正在同步…"
        QuickLaunchExecutors.io.execute {
            val outcome = runCatching { VpsSync.runOnce(this) }
                .getOrElse { VpsSync.Outcome.Failed("${it.javaClass.simpleName}: ${it.message}") }
            VpsSyncScheduler.schedule(this)
            runOnUiThread {
                val msg = when (outcome) {
                    is VpsSync.Outcome.NotConfigured -> "配置不完整，请检查地址 / 设备 ID / 密钥"
                    is VpsSync.Outcome.Applied -> "已应用 v${outcome.version}：${outcome.detail}"
                    is VpsSync.Outcome.UpToDate -> "已是最新（v${outcome.version}）"
                    is VpsSync.Outcome.Failed -> "同步失败：${outcome.reason}"
                }
                toast(msg)
                renderStatus()
            }
        }
    }

    private fun saveRetryPolicy() {
        val cur = LaunchSettings.loadPolicy(this)
        val maxAttempts = binding.etRetryMax.text?.toString()?.toIntOrNull() ?: cur.maxAttempts
        val window = binding.etRetryWindow.text?.toString()?.toLongOrNull() ?: cur.verifyWindowMs
        val delays = binding.etRetryDelays.text?.toString().orEmpty()
            .split(',')
            .mapNotNull { it.trim().toLongOrNull() }
            .filter { it > 0 }
            .ifEmpty { LaunchPolicy.DEFAULT_RETRY_DELAYS_MS }

        val policy = cur.copy(
            maxAttempts = maxAttempts,
            verifyWindowMs = window,
            retryDelaysMs = delays,
            notifyOnFailure = binding.swRetryNotify.isChecked
        )
        LaunchSettings.savePolicy(this, policy)
        val saved = LaunchSettings.loadPolicy(this)
        // 回填夹紧后的真实值：用户输入 99 次会被存成 5 次，界面上必须看到 5
        binding.etRetryMax.setText(saved.maxAttempts.toString())
        binding.etRetryWindow.setText(saved.verifyWindowMs.toString())
        binding.etRetryDelays.setText(saved.retryDelaysMs.joinToString(","))
        toast("已保存（最多 ${saved.maxAttempts} 次，窗口 ${saved.verifyWindowMs}ms）")
    }

    private fun renderStatus() {
        val s = VpsSettings.load(this)
        val (at, note) = VpsSettings.lastSync(this)
        val nextIn = if (s.ready()) "每 ${s.intervalSec} 秒检查一次" else "未就绪"
        val last = if (at == 0L) "从未同步" else "${timeFmt.format(Date(at))}"
        val applied = if (s.appliedVersion > 0) "v${s.appliedVersion}" else "未应用过任何版本"
        binding.tvVpsStatus.text = buildString {
            append("状态：").append(if (!s.enabled) "未启用" else if (s.ready()) "已就绪" else "配置不完整")
            append("\n已应用版本：").append(applied)
            append("\n上次同步：").append(last)
            if (note.isNotBlank()) append("\n结果：").append(note)
            append("\n").append(nextIn)
        }
    }

    private fun renderHistory() {
        val list = LaunchSettings.history(this).take(8)
        if (list.isEmpty()) {
            binding.tvLaunchHistory.text = "暂无记录"
            return
        }
        binding.tvLaunchHistory.text = list.joinToString("\n") { r ->
            val t = timeFmt.format(Date(r.finishedAt.takeIf { it > 0 } ?: r.startedAt))
            val flag = when (r.state) {
                LaunchAttemptState.SUCCESS.name -> "成功"
                LaunchAttemptState.CANCELLED.name -> "已取消"
                else -> "失败"
            }
            "$t $flag ${r.appName} ×${r.tries} ${r.reason}"
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
