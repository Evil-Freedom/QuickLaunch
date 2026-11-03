package com.workbuddy.quicklaunch

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.workbuddy.quicklaunch.databinding.ActivityRemoteAccessBinding
import com.workbuddy.quicklaunch.remote.RelayPoller
import com.workbuddy.quicklaunch.remote.RemoteConfig
import com.workbuddy.quicklaunch.service.RemoteService
import java.util.Locale

/**
 * 远程访问配置页：开关、访问地址、配对码、已配对设备、操作记录。
 *
 * 页面本身不持有任何网络逻辑 —— 监听在 [RemoteService] 里，
 * 这样即使退出本页（甚至杀掉界面进程中的 Activity），远程仍然可用。
 */
class RemoteAccessActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRemoteAccessBinding
    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            renderPairing()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRemoteAccessBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.swRemote.setOnCheckedChangeListener { _, checked -> onToggle(checked) }
        binding.btnGenerate.setOnClickListener { generateCode() }
        binding.btnClearAudit.setOnClickListener {
            RemoteConfig.clearAudit(this)
            renderAudit()
        }
        binding.swRelay.setOnCheckedChangeListener { _, checked -> onRelayToggle(checked) }
        binding.btnRelaySave.setOnClickListener { saveRelay() }
        render()
    }

    override fun onResume() {
        super.onResume()
        // 自愈：开关是开着的但服务已经没了（覆盖安装、被系统回收、前台服务超时），
        // 进这个页面就顺手拉起来。startForegroundService 对已在运行的实例是幂等的。
        if (RemoteConfig.isEnabled(this) && !RemoteService.isRunning) {
            RemoteService.start(this)
            handler.postDelayed({ render() }, 700L)
        }
        render()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ── 开关 ────────────────────────────────────────────────────────

    private fun onToggle(checked: Boolean) {
        if (checked) {
            // 没有 WiFi 时，只有备用通道已就绪才允许开启：
            // 局域网地址拿不到，但备用通道走的是出站连接，不依赖本机 IP。
            if (RemoteService.currentIp(this) == null && !isRelayReady()) {
                setSwitchSilently(false)
                toast(getString(R.string.remote_need_wifi))
                return
            }
            RemoteConfig.setEnabled(this, true)
            RemoteService.start(this)
        } else {
            RemoteConfig.setEnabled(this, false)
            // 总开关关闭 = 远程能力整体关闭。
            // 备用通道的轮询线程就跑在 RemoteService 里，若只停服务不关这个开关，
            // 界面会一直显示「已启用 · 等待首次轮询」，而实际上永远不会再轮询 ——
            // 用户看到的是「开着但没反应」，比明确关掉更难排查。
            if (RemoteConfig.relaySettings(this).enabled) {
                RemoteConfig.setRelayEnabled(this, false)
            }
            RemoteService.stop(this)
        }
        // 服务启动/停止是异步的，稍等一下再读一次真实状态
        handler.postDelayed({ render() }, 700L)
    }

    /** 备用通道是否已配好（已开启 + 地址合法）。 */
    private fun isRelayReady(): Boolean {
        val s = RemoteConfig.relaySettings(this)
        return s.enabled && RelayPoller.normalize(s.url) != null
    }

    private fun setSwitchSilently(checked: Boolean) {
        binding.swRemote.setOnCheckedChangeListener(null)
        binding.swRemote.isChecked = checked
        binding.swRemote.setOnCheckedChangeListener { _, c -> onToggle(c) }
    }

    // ── 渲染 ────────────────────────────────────────────────────────

    private fun render() {
        val enabled = RemoteConfig.isEnabled(this)
        setSwitchSilently(enabled)

        binding.tvAddress.text = if (enabled) {
            RemoteService.addressHint(this)
        } else {
            "—"
        }
        binding.tvStatus.text = when {
            !enabled -> getString(R.string.remote_status_off)
            // 开关开着不等于服务活着：这一条必须在地址判断之前，
            // 否则会出现「地址显示得好好的、其实连不上」的假象。
            !RemoteService.isRunning -> getString(R.string.remote_status_not_running)
            RemoteService.currentIp(this) == null && isRelayReady() ->
                getString(R.string.remote_status_relay_only)
            RemoteService.currentIp(this) == null -> getString(R.string.remote_status_no_wifi)
            else -> getString(R.string.remote_status_on)
        }
        renderPairing()
        renderClients()
        renderRelay()
        renderAudit()
    }

    private fun renderPairing() {
        val pending = RemoteConfig.pendingCode(this)
        if (pending == null) {
            binding.tvPairCode.text = "--------"
            binding.tvPairHint.text = getString(R.string.remote_pair_hint_idle)
            return
        }
        binding.tvPairCode.text = pending.code.chunked(4).joinToString(" ")
        val left = pending.expiresAt - System.currentTimeMillis()
        binding.tvPairHint.text = if (left <= 0) {
            getString(R.string.remote_pair_expired)
        } else {
            val min = left / 60_000
            val sec = (left % 60_000) / 1000
            String.format(Locale.US, "剩余 %d 分 %02d 秒 · 错误 %d/%d 次", min, sec, pending.attempts, RemoteConfig.PAIRING_MAX_ATTEMPTS)
        }
    }

    private fun renderClients() {
        val box = binding.containerClients
        box.removeAllViews()
        val clients = RemoteConfig.clients(this).sortedByDescending { it.lastUsedAt }
        binding.tvClientsEmpty.visibility = if (clients.isEmpty()) View.VISIBLE else View.GONE

        clients.forEach { client ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val label = TextView(this).apply {
                text = "${client.name}\n${relativeTime(client.lastUsedAt)}"
                setTextColor(getColor(R.color.dark_text_secondary))
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val remove = TextView(this).apply {
                text = getString(R.string.remote_client_remove)
                setTextColor(getColor(R.color.dark_text_tertiary))
                textSize = 13f
                setPadding(dp(12), dp(8), dp(4), dp(8))
                setOnClickListener {
                    RemoteConfig.removeClient(this@RemoteAccessActivity, client.keyId)
                    // 删掉最后一个客户端时顺手关掉服务：没有密钥的监听口留着没有意义
                    if (RemoteConfig.clients(this@RemoteAccessActivity).isEmpty()) {
                        RemoteConfig.setEnabled(this@RemoteAccessActivity, false)
                        RemoteService.stop(this@RemoteAccessActivity)
                    }
                    render()
                }
            }
            row.addView(label)
            row.addView(remove)
            box.addView(row)
        }
    }

    // ── 备用通道 ────────────────────────────────────────────────────

    private fun renderRelay() {
        val s = RemoteConfig.relaySettings(this)
        setRelaySwitchSilently(s.enabled)

        // 正在输入时不要覆写，否则光标会被顶到开头
        if (!binding.etRelayUrl.hasFocus()) binding.etRelayUrl.setText(s.url)
        if (!binding.etRelayToken.hasFocus()) binding.etRelayToken.setText(s.token)

        val (at, note) = if (RemoteService.lastRelayAt > 0L) {
            RemoteService.lastRelayAt to RemoteService.lastRelayNote
        } else {
            RemoteConfig.relayLastPoll(this)
        }

        binding.tvRelayStatus.text = when {
            !s.enabled -> getString(R.string.relay_status_off)
            at <= 0L -> getString(R.string.relay_status_never)
            else -> String.format(
                Locale.US,
                "%s · %s %s",
                getString(R.string.relay_status_polling, s.intervalSec),
                clockTime(at),
                note
            )
        }
    }

    private fun onRelayToggle(checked: Boolean) {
        if (checked) {
            if (RemoteConfig.clients(this).isEmpty()) {
                setRelaySwitchSilently(false)
                toast(getString(R.string.relay_need_clients))
                return
            }
            val url = binding.etRelayUrl.text.toString().trim()
            if (RelayPoller.normalize(url) == null) {
                setRelaySwitchSilently(false)
                toast(getString(R.string.relay_invalid_url))
                return
            }
            persistRelayInputs(url)
        } else {
            // 关闭时不再校验地址：用户就是想把这段有问题的配置停掉
            persistRelayInputs(binding.etRelayUrl.text.toString().trim())
        }

        RemoteConfig.setRelayEnabled(this, checked)
        if (checked) {
            // 备用通道靠前台服务里的轮询线程活着，所以必须确保服务在跑
            RemoteConfig.setEnabled(this, true)
            RemoteService.start(this)
        }
        handler.postDelayed({ render() }, 700L)
    }

    private fun saveRelay() {
        val url = binding.etRelayUrl.text.toString().trim()
        if (url.isNotEmpty() && RelayPoller.normalize(url) == null) {
            toast(getString(R.string.relay_invalid_url))
            return
        }
        persistRelayInputs(url)
        if (RemoteConfig.relaySettings(this).enabled) {
            // 地址改了要立刻生效：重启服务让轮询线程用新地址
            RemoteService.stop(this)
            RemoteConfig.setEnabled(this, true)
            RemoteService.start(this)
        }
        toast(getString(R.string.relay_saved))
        handler.postDelayed({ render() }, 700L)
    }

    private fun persistRelayInputs(url: String) {
        RemoteConfig.setRelayUrl(this, url)
        RemoteConfig.setRelayToken(this, binding.etRelayToken.text.toString())
    }

    private fun setRelaySwitchSilently(checked: Boolean) {
        binding.swRelay.setOnCheckedChangeListener(null)
        binding.swRelay.isChecked = checked
        binding.swRelay.setOnCheckedChangeListener { _, c -> onRelayToggle(c) }
    }

    private fun clockTime(ts: Long): String =
        android.text.format.DateFormat.format("HH:mm:ss", ts).toString()

    private fun renderAudit() {
        val log = RemoteConfig.auditLog(this).take(20)
        binding.tvAudit.text = if (log.isEmpty()) {
            getString(R.string.remote_audit_empty)
        } else {
            log.joinToString("\n") { it.text() }
        }
    }

    // ── 交互 ────────────────────────────────────────────────────────

    private fun generateCode() {
        if (!RemoteConfig.isEnabled(this)) {
            toast(getString(R.string.remote_need_enable_first))
            return
        }
        RemoteConfig.issuePairingCode(this)
        renderPairing()
        toast(getString(R.string.remote_pair_generated))
    }

    private fun relativeTime(ts: Long): String {
        if (ts <= 0) return getString(R.string.remote_never_used)
        val diff = System.currentTimeMillis() - ts
        return when {
            diff < 60_000 -> "刚刚使用"
            diff < 3_600_000 -> "${diff / 60_000} 分钟前使用"
            diff < 86_400_000 -> "${diff / 3_600_000} 小时前使用"
            else -> "${diff / 86_400_000} 天前使用"
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) {
        runCatching { android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show() }
    }
}
