package com.workbuddy.quicklaunch.launch

/**
 * 单次启动任务的纯逻辑状态机。
 *
 * ## 为什么要有它
 *
 * 升级前的链路是「发一个 startActivity，然后把结果当作成功」。真实情况是：
 * `startActivity` 返回 `true` 只代表**系统接受了这次请求**，后台启动限制、
 * 锁屏、折叠屏换屏、目标 App 闪退都能让「请求已发」与「界面真的在前台」
 * 之间出现差距，而这个差距用户完全看不见。
 *
 * 本类把「什么时候再探一次、什么时候放弃、失败了要不要重试」集中到一个
 * 可用虚拟时钟测试的函数里。Android 侧的 [LaunchAttemptController] 只负责
 * 执行它返回的 [Decision]，不做任何策略判断。
 *
 * ## 时钟约定
 *
 * 所有时间都是调用方传入的绝对毫秒（生产用 `System.currentTimeMillis()`，
 * 测试用任意递增序列）。类内部**不读系统时间**，因此「等 15 秒」这类场景
 * 在单测里是零耗时的，也不会因为 CI 慢而抖动。
 *
 * ## 线程约定
 *
 * 非线程安全。生产侧由 Controller 在单一线程（Handler 所在线程）驱动；
 * 探测结果通过 `Handler.post` 回投到同一条线程。
 */
class LaunchAttemptEngine(
    val attemptId: String,
    val ruleId: Long,
    val pkg: String,
    val appName: String,
    policy: LaunchPolicy,
    /** 任务创建时刻，用于总时限计算。 */
    val startedAt: Long
) {

    val policy: LaunchPolicy = policy.sanitized()

    var state: LaunchAttemptState = LaunchAttemptState.PENDING
        private set

    /** 当前是第几次尝试（从 1 开始）。 */
    var attemptIndex: Int = 0
        private set

    /** 最近一次发出启动命令的时刻。0 表示尚未发出。 */
    var lastLaunchedAt: Long = 0L
        private set

    /** 连续命中次数。任何一次未命中都清零 —— 这正是「启动后立刻退出」的识别方式。 */
    var stableHits: Int = 0
        private set

    /** 最近一次失败/异常原因，最终写进通知与本地记录。成功时为空串。 */
    var lastError: String = ""
        private set

    /** 终结原因。成功时也有值（如 ALREADY_FOREGROUND / FOREGROUND_CONFIRMED）。 */
    var finishedReason: String = ""
        private set

    var finishedAt: Long = 0L
        private set

    /** 每次尝试的结论，供失败通知与真机报告使用。 */
    val attempts: MutableList<AttemptRecord> = mutableListOf()

    private var nextProbeAt: Long = 0L
    private var verifyDeadline: Long = 0L
    private var retryAt: Long = 0L

    data class AttemptRecord(
        val index: Int,
        val launchedAt: Long,
        var outcome: String,
        var finishedAt: Long,
        var probeSource: String? = null
    )

    /** 状态机对外的唯一输出。Android 侧按类型执行，不做二次判断。 */
    sealed class Decision {
        /** 无需动作，等下一个事件。 */
        data object Idle : Decision()

        /** 立刻发出启动命令。 */
        data class DispatchLaunch(val attemptIndex: Int) : Decision()

        /** 现在做一次前台探测（异步）。 */
        data object ProbeNow : Decision()

        /** 在该时刻回来调 [onTick]。 */
        data class WaitUntil(val at: Long) : Decision()

        /** 任务终结。 */
        data class Finished(val state: LaunchAttemptState, val reason: String) : Decision()
    }

    // ── 事件入口 ────────────────────────────────────────────────────

    /**
     * 任务开始时调用一次。
     *
     * @param alreadyForeground 启动前探测的结果。为 true 时**不再发启动命令**，
     *        直接判成功 —— 需求里「目标 App 已经在前台」的场景，此时再发一次
     *        startActivity 会把用户正在看的界面重新拉一遍（部分 App 会重置页面）。
     */
    fun start(now: Long, alreadyForeground: Boolean): Decision {
        // 只接受「尚未开始」的任务。验证中 / 重试等待中再次收到触发（重复 Alarm、
        // 休眠恢复、另一条规则指向同一个 App）必须被忽略 ——
        // 否则 attemptIndex 会被重置成 1，两套重试循环开始互相覆盖。
        if (state != LaunchAttemptState.PENDING || attemptIndex != 0) return Decision.Idle
        if (alreadyForeground) {
            attemptIndex = 0
            return finish(now, LaunchAttemptState.SUCCESS, REASON_ALREADY_FOREGROUND)
        }
        attemptIndex = 1
        return Decision.DispatchLaunch(attemptIndex)
    }

    /**
     * 启动命令已发出（无论 startActivity 返回成功还是失败）。
     *
     * 注意：这里**不根据 startActivity 的返回值决定成败**。返回值只用于填
     * [AttemptRecord.outcome] 的初值，真正的判定仍靠后续探测。
     */
    fun onLaunchDispatched(now: Long, dispatchAccepted: Boolean = true): Decision {
        if (state.isTerminal) return Decision.Idle
        state = LaunchAttemptState.LAUNCHING
        lastLaunchedAt = now
        stableHits = 0
        nextProbeAt = now + policy.firstVerifyDelayMs
        verifyDeadline = nextProbeAt + policy.verifyWindowMs
        attempts += AttemptRecord(
            index = attemptIndex,
            launchedAt = now,
            outcome = if (dispatchAccepted) OUTCOME_PENDING else OUTCOME_DISPATCH_REJECTED,
            finishedAt = 0L
        )
        if (!dispatchAccepted) lastError = REASON_DISPATCH_REJECTED
        return Decision.WaitUntil(nextProbeAt)
    }

    /** 一次探测的结果。[hit] 表示目标包确实在亮着的那块屏上前台。 */
    fun onProbeResult(now: Long, hit: Boolean, source: String? = null): Decision {
        if (state.isTerminal) return Decision.Idle
        if (state != LaunchAttemptState.LAUNCHING && state != LaunchAttemptState.VERIFYING) {
            // 迟到的探测结果（任务已被取消或已进入重试等待）：丢弃，不改变状态。
            return Decision.Idle
        }
        state = LaunchAttemptState.VERIFYING
        if (source != null) attempts.lastOrNull()?.probeSource = source

        if (hit) {
            stableHits += 1
            if (stableHits >= policy.requiredStableHits) {
                attempts.lastOrNull()?.apply { outcome = OUTCOME_SUCCESS; finishedAt = now }
                return finish(now, LaunchAttemptState.SUCCESS, REASON_FOREGROUND_CONFIRMED)
            }
            // 已看到目标在前台，这一次确认探测不受 verifyDeadline 限制 ——
            // 只差最后一下确认却因为窗口到期而判失败，是最没道理的失败。
            nextProbeAt = now + policy.verifyIntervalMs
            return Decision.WaitUntil(nextProbeAt)
        }

        stableHits = 0
        if (now >= verifyDeadline) return failAttempt(now, REASON_VERIFY_TIMEOUT)
        nextProbeAt = minOf(now + policy.verifyIntervalMs, verifyDeadline)
        return Decision.WaitUntil(nextProbeAt)
    }

    /** 定时驱动。Controller 在每个 [Decision.WaitUntil] 的时刻回来调它。 */
    fun onTick(now: Long): Decision {
        if (state.isTerminal) return Decision.Idle

        // 总时限优先于一切：无论当前在验证还是等待重试，超过即收手。
        if (now - startedAt > policy.totalTimeoutMs) {
            return failAttempt(now, REASON_TOTAL_TIMEOUT, forceStop = true)
        }

        return when (state) {
            LaunchAttemptState.PENDING -> Decision.Idle

            LaunchAttemptState.LAUNCHING, LaunchAttemptState.VERIFYING -> {
                when {
                    now >= verifyDeadline -> failAttempt(now, REASON_VERIFY_TIMEOUT)
                    now >= nextProbeAt -> Decision.ProbeNow
                    else -> Decision.WaitUntil(minOf(nextProbeAt, verifyDeadline))
                }
            }

            LaunchAttemptState.RETRY_WAIT -> {
                if (now >= retryAt) Decision.DispatchLaunch(attemptIndex)
                else Decision.WaitUntil(retryAt)
            }

            else -> Decision.Idle
        }
    }

    /** 规则被取消、用户手动停止、或进程恢复时发现任务已过期。 */
    fun cancel(now: Long, reason: String = REASON_CANCELLED): Decision {
        if (state.isTerminal) return Decision.Idle
        attempts.lastOrNull()?.apply {
            if (finishedAt == 0L) { outcome = OUTCOME_CANCELLED; finishedAt = now }
        }
        lastError = reason
        return finish(now, LaunchAttemptState.CANCELLED, reason)
    }

    /**
     * 进程重启后恢复任务时调用：判断这条遗留任务还值不值得继续。
     *
     * 一律返回 false 的情形是「上次活动时间距今已超过总时限」——
     * 需求里「进程重启后不能无限重复旧任务」就落在这里。
     */
    fun canResume(now: Long): Boolean {
        if (state.isTerminal) return false
        val lastActivity = maxOf(lastLaunchedAt, finishedAt, startedAt)
        return now - lastActivity <= policy.totalTimeoutMs
    }

    /** 供日志与通知使用的一句话摘要。 */
    fun summary(): String = buildString {
        append("state=").append(state.name)
        append(" attempt=").append(attemptIndex).append('/').append(policy.maxAttempts)
        if (lastError.isNotEmpty()) append(" error=").append(lastError)
    }

    // ── 内部 ────────────────────────────────────────────────────────

    private fun failAttempt(
        now: Long,
        reason: String,
        forceStop: Boolean = false
    ): Decision {
        lastError = reason
        attempts.lastOrNull()?.apply {
            if (finishedAt == 0L) {
                outcome = when (reason) {
                    REASON_TOTAL_TIMEOUT -> OUTCOME_TOTAL_TIMEOUT
                    REASON_DISPATCH_REJECTED -> OUTCOME_DISPATCH_REJECTED
                    REASON_KILLED -> OUTCOME_INTERRUPTED
                    else -> OUTCOME_VERIFY_TIMEOUT
                }
                finishedAt = now
            }
        }

        if (forceStop || !policy.hasNextAttempt(attemptIndex)) {
            // 重试用尽：MANUAL_REQUIRED 而不是 FAILED —— 用户仍可一键再试，
            // 通知上要给出这个入口（受 allowManualRetry 控制）。
            return finish(now, LaunchAttemptState.MANUAL_REQUIRED, reason)
        }

        val delay = policy.delayAfterAttempt(attemptIndex)
        val candidate = now + delay
        if (candidate - startedAt > policy.totalTimeoutMs) {
            return finish(now, LaunchAttemptState.MANUAL_REQUIRED, REASON_TOTAL_TIMEOUT)
        }
        retryAt = candidate
        state = LaunchAttemptState.RETRY_WAIT
        attemptIndex += 1
        return Decision.WaitUntil(retryAt)
    }

    private fun finish(now: Long, target: LaunchAttemptState, reason: String): Decision {
        state = target
        finishedReason = reason
        lastError = if (target == LaunchAttemptState.SUCCESS) "" else reason
        finishedAt = now
        return Decision.Finished(target, reason)
    }

    companion object {
        const val REASON_ALREADY_FOREGROUND = "ALREADY_FOREGROUND"
        const val REASON_FOREGROUND_CONFIRMED = "FOREGROUND_CONFIRMED"
        const val REASON_VERIFY_TIMEOUT = "VERIFY_TIMEOUT"
        const val REASON_TOTAL_TIMEOUT = "TOTAL_TIMEOUT"
        const val REASON_DISPATCH_REJECTED = "DISPATCH_REJECTED"
        const val REASON_CANCELLED = "CANCELLED"
        const val REASON_KILLED = "INTERRUPTED"
        const val REASON_PACKAGE_MISSING = "PACKAGE_MISSING"
        const val REASON_NO_PROBE = "NO_FOREGROUND_PROBE"

        const val OUTCOME_PENDING = "PENDING"
        const val OUTCOME_SUCCESS = "SUCCESS"
        const val OUTCOME_VERIFY_TIMEOUT = "VERIFY_TIMEOUT"
        const val OUTCOME_TOTAL_TIMEOUT = "TOTAL_TIMEOUT"
        const val OUTCOME_DISPATCH_REJECTED = "DISPATCH_REJECTED"
        const val OUTCOME_CANCELLED = "CANCELLED"
        const val OUTCOME_INTERRUPTED = "INTERRUPTED"
    }
}
