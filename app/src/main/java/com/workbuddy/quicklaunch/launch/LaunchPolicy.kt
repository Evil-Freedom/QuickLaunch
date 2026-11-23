package com.workbuddy.quicklaunch.launch

/**
 * 一次启动任务的重试与验证参数。
 *
 * 全部字段都有「越界即夹紧」的解析入口（见 [sanitized]）：这些值可以来自
 * SharedPreferences（用户手改）、远程下发的配置，任何一处写坏都不能让
 * 重试链路变成「无限重试」或「退化为一次都不验证」。
 *
 * 默认值取向（可由用户逐项修改）：
 * - 最多 3 次尝试（首次 + 2 次重试）。第 3 次重试默认关闭 —— 一次失败就反复
 *   唤醒手机比「这次没启动成功」更烦人。
 * - 首次验证延迟 2s：实测 am start 返回后 0.5s 前台就已是目标 App，
 *   但冷启动的 App 可能先显示启动图再切主 Activity，2s 是稳妥的观察起点。
 * - 单次验证窗口 6s：覆盖绝大多数冷启动；窗口内每 800ms 探测一次，
 *   单次探测（su + dumpsys + 解析）实测约 450ms，这个间隔不至于把线程压满。
 * - 重试间隔 5s / 15s：第一次快速补一次（多是偶发的系统拦截），
 *   第二次给足时间让系统从「后台启动限制」的冷却中恢复。
 * - 总时限 90s：超过这个时间仍在重试，说明问题不是「再试一次能好」，
 *   而是规则/权限/包本身有问题，必须停下来告警。
 */
data class LaunchPolicy(
    /** 是否启用自动重试。关闭时退化为「启动一次 + 验证一次」，行为接近升级前。 */
    val enabled: Boolean = true,
    /** 最多尝试次数（含首次）。1 表示不重试。 */
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    /** 发出启动命令后，等待多久开始第一次验证。 */
    val firstVerifyDelayMs: Long = DEFAULT_FIRST_VERIFY_DELAY_MS,
    /** 单次尝试的验证窗口长度。 */
    val verifyWindowMs: Long = DEFAULT_VERIFY_WINDOW_MS,
    /** 验证窗口内的探测间隔。 */
    val verifyIntervalMs: Long = DEFAULT_VERIFY_INTERVAL_MS,
    /** 连续命中次数达到该值才判定成功。2 = 能识别「启动后立刻闪退」。 */
    val requiredStableHits: Int = DEFAULT_REQUIRED_STABLE_HITS,
    /** 第 1 次、第 2 次…重试前的等待。长度不足时用最后一个值补齐。 */
    val retryDelaysMs: List<Long> = DEFAULT_RETRY_DELAYS_MS,
    /** 整个任务的绝对时限，超过即停止重试并告警。 */
    val totalTimeoutMs: Long = DEFAULT_TOTAL_TIMEOUT_MS,
    /** 最终失败是否发通知。关掉后只写本地记录。 */
    val notifyOnFailure: Boolean = true,
    /** 失败通知里是否给「再次启动」按钮。 */
    val allowManualRetry: Boolean = true
) {

    /**
     * 把任意（可能被写坏的）输入夹紧到可执行范围。
     *
     * 为什么不是「校验失败就抛」：这些值在触发链路上读取，抛异常会让整条
     * 启动任务直接不执行 —— 用户宁可接受「参数被修正成默认值」，也不能接受
     * 「规则明明到点了却什么都没发生」。
     */
    fun sanitized(): LaunchPolicy = copy(
        // 关闭自动重试 = 只尝试一次。让「开关」直接决定次数，避免出现
        // 「重试已关闭但 maxAttempts 还是 3」这种自相矛盾的配置。
        maxAttempts = if (!enabled) 1 else maxAttempts.coerceIn(MIN_ATTEMPTS, MAX_ATTEMPTS),
        firstVerifyDelayMs = firstVerifyDelayMs.coerceIn(MIN_FIRST_VERIFY_DELAY_MS, MAX_FIRST_VERIFY_DELAY_MS),
        verifyWindowMs = verifyWindowMs.coerceIn(MIN_VERIFY_WINDOW_MS, MAX_VERIFY_WINDOW_MS),
        verifyIntervalMs = verifyIntervalMs.coerceIn(MIN_VERIFY_INTERVAL_MS, MAX_VERIFY_INTERVAL_MS),
        requiredStableHits = requiredStableHits.coerceIn(1, MAX_STABLE_HITS),
        retryDelaysMs = retryDelaysMs
            .map { it.coerceIn(MIN_RETRY_DELAY_MS, MAX_RETRY_DELAY_MS) }
            .ifEmpty { DEFAULT_RETRY_DELAYS_MS },
        totalTimeoutMs = totalTimeoutMs.coerceIn(MIN_TOTAL_TIMEOUT_MS, MAX_TOTAL_TIMEOUT_MS)
    )

    /** 第 [attemptIndex]（从 1 开始）次尝试失败后，等待多久再试。越界一律取最后一个能用的值。 */
    fun delayAfterAttempt(attemptIndex: Int): Long {
        val list = retryDelaysMs.ifEmpty { DEFAULT_RETRY_DELAYS_MS }
        val idx = (attemptIndex - 1).coerceAtLeast(0)
        return if (idx < list.size) list[idx] else list.last()
    }

    /** 还有没有下一次尝试。attemptIndex 是**刚失败的那一次**的序号。 */
    fun hasNextAttempt(attemptIndex: Int): Boolean = attemptIndex < maxAttempts

    companion object {
        const val MIN_ATTEMPTS = 1
        const val MAX_ATTEMPTS = 5
        const val DEFAULT_MAX_ATTEMPTS = 3

        const val MIN_FIRST_VERIFY_DELAY_MS = 500L
        const val MAX_FIRST_VERIFY_DELAY_MS = 10_000L
        const val DEFAULT_FIRST_VERIFY_DELAY_MS = 2_000L

        const val MIN_VERIFY_WINDOW_MS = 2_000L
        const val MAX_VERIFY_WINDOW_MS = 30_000L
        const val DEFAULT_VERIFY_WINDOW_MS = 6_000L

        const val MIN_VERIFY_INTERVAL_MS = 400L
        const val MAX_VERIFY_INTERVAL_MS = 5_000L
        const val DEFAULT_VERIFY_INTERVAL_MS = 800L

        const val MAX_STABLE_HITS = 3
        const val DEFAULT_REQUIRED_STABLE_HITS = 2

        const val MIN_RETRY_DELAY_MS = 1_000L
        const val MAX_RETRY_DELAY_MS = 120_000L
        val DEFAULT_RETRY_DELAYS_MS = listOf(5_000L, 15_000L)

        const val MIN_TOTAL_TIMEOUT_MS = 10_000L
        const val MAX_TOTAL_TIMEOUT_MS = 10 * 60_000L
        const val DEFAULT_TOTAL_TIMEOUT_MS = 90_000L
    }
}
