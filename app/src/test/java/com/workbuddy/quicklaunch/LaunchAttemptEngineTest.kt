package com.workbuddy.quicklaunch

import com.workbuddy.quicklaunch.launch.DumpsysParser
import com.workbuddy.quicklaunch.launch.LaunchAttemptEngine
import com.workbuddy.quicklaunch.launch.LaunchAttemptState
import com.workbuddy.quicklaunch.launch.LaunchPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 启动状态机的行为测试。
 *
 * 全部用虚拟时钟：`T0` 起算，每一步显式给出「现在几点」。
 * 这样「等 15 秒重试」不用真的等，也不会因为机器慢而失败。
 *
 * 每个用例对应需求 §7.1 里的一条场景，用例名直接标注。
 */
class LaunchAttemptEngineTest {

    private val t0 = 1_700_000_000_000L

    private fun engine(policy: LaunchPolicy = LaunchPolicy()) = LaunchAttemptEngine(
        attemptId = "test-attempt",
        ruleId = 7L,
        pkg = "com.example.target",
        appName = "目标应用",
        policy = policy,
        startedAt = t0
    )

    /** 走完「发出启动命令」这一步，返回引擎。 */
    private fun LaunchAttemptEngine.dispatch(at: Long): LaunchAttemptEngine {
        val d = start(at, alreadyForeground = false)
        assertTrue("首次必须是 DispatchLaunch，实际 $d", d is LaunchAttemptEngine.Decision.DispatchLaunch)
        onLaunchDispatched(at)
        return this
    }

    // ── 场景 1：第一次启动就成功 ────────────────────────────────────

    @Test
    fun `首次启动并在两次命中后判定成功`() {
        val e = engine().dispatch(t0)
        assertEquals(LaunchAttemptState.LAUNCHING, e.state)
        assertEquals(1, e.attemptIndex)

        // 首次验证时刻 = t0 + firstVerifyDelay
        val firstVerify = t0 + e.policy.firstVerifyDelayMs
        assertEquals(
            LaunchAttemptEngine.Decision.ProbeNow,
            e.onTick(firstVerify)
        )

        // 第一次命中：还不够，要连续 requiredStableHits 次
        val d1 = e.onProbeResult(firstVerify, hit = true, source = "ROOT")
        assertTrue(d1 is LaunchAttemptEngine.Decision.WaitUntil)
        assertEquals(LaunchAttemptState.VERIFYING, e.state)

        // 第二次命中：判定成功
        val second = firstVerify + e.policy.verifyIntervalMs
        val d2 = e.onProbeResult(second, hit = true, source = "ROOT")
        assertTrue(d2 is LaunchAttemptEngine.Decision.Finished)
        assertEquals(LaunchAttemptState.SUCCESS, e.state)
        assertEquals(1, e.attempts.size)
        assertEquals(LaunchAttemptEngine.OUTCOME_SUCCESS, e.attempts[0].outcome)
        assertEquals("ROOT", e.attempts[0].probeSource)
    }

    // ── 场景 5：目标 App 已经在前台 ──────────────────────────────────

    @Test
    fun `目标已在前台时不再发启动命令`() {
        val e = engine()
        val d = e.start(t0, alreadyForeground = true)
        assertTrue(d is LaunchAttemptEngine.Decision.Finished)
        assertEquals(LaunchAttemptState.SUCCESS, e.state)
        assertEquals(LaunchAttemptEngine.REASON_ALREADY_FOREGROUND, e.finishedReason)
        assertEquals("", e.lastError)
        // 没有真正发出过启动命令
        assertTrue(e.attempts.isEmpty())
        assertEquals(0, e.attemptIndex)
    }

    // ── 场景 2：第一次失败、第二次成功 ──────────────────────────────

    @Test
    fun `第一次验证超时后按配置间隔重试并成功`() {
        val e = engine().dispatch(t0)
        val verifyStart = t0 + e.policy.firstVerifyDelayMs
        val verifyEnd = verifyStart + e.policy.verifyWindowMs

        // 窗口内始终未命中
        var t = verifyStart
        while (t < verifyEnd) {
            e.onTick(t)
            e.onProbeResult(t, hit = false)
            t += e.policy.verifyIntervalMs
        }

        // 窗口到期：进入重试等待，attemptIndex 递增到 2
        val dFail = e.onTick(verifyEnd)
        assertTrue("窗口到期应进入 WaitUntil，实际 $dFail", dFail is LaunchAttemptEngine.Decision.WaitUntil)
        assertEquals(LaunchAttemptState.RETRY_WAIT, e.state)
        assertEquals(2, e.attemptIndex)
        assertEquals(LaunchAttemptEngine.OUTCOME_VERIFY_TIMEOUT, e.attempts[0].outcome)

        // 重试时刻到：再次发出启动命令
        val retryAt = verifyEnd + e.policy.delayAfterAttempt(1)
        val dRetry = e.onTick(retryAt)
        assertTrue(dRetry is LaunchAttemptEngine.Decision.DispatchLaunch)
        assertEquals(2, (dRetry as LaunchAttemptEngine.Decision.DispatchLaunch).attemptIndex)

        // 第二次尝试成功
        e.onLaunchDispatched(retryAt)
        val v2 = retryAt + e.policy.firstVerifyDelayMs
        e.onProbeResult(v2, hit = true, source = "ROOT")
        val d2 = e.onProbeResult(v2 + e.policy.verifyIntervalMs, hit = true, source = "ROOT")
        assertTrue(d2 is LaunchAttemptEngine.Decision.Finished)
        assertEquals(LaunchAttemptState.SUCCESS, e.state)
        assertEquals(2, e.attempts.size)
        assertEquals(LaunchAttemptEngine.OUTCOME_SUCCESS, e.attempts[1].outcome)
    }

    // ── 场景 3：前两次失败、第三次成功 ──────────────────────────────

    @Test
    fun `前两次失败第三次成功`() {
        val e = engine().dispatch(t0)
        var now = t0

        // 第 1 次失败
        now = exhaustVerifyWindow(e, now)
        assertEquals(LaunchAttemptState.RETRY_WAIT, e.state)
        assertEquals(2, e.attemptIndex)

        // 第 2 次
        now += e.policy.delayAfterAttempt(1)
        assertTrue(e.onTick(now) is LaunchAttemptEngine.Decision.DispatchLaunch)
        e.onLaunchDispatched(now)
        now = exhaustVerifyWindow(e, now)
        assertEquals(LaunchAttemptState.RETRY_WAIT, e.state)
        assertEquals(3, e.attemptIndex)

        // 第 3 次：成功
        now += e.policy.delayAfterAttempt(2)
        assertTrue(e.onTick(now) is LaunchAttemptEngine.Decision.DispatchLaunch)
        e.onLaunchDispatched(now)
        val v = now + e.policy.firstVerifyDelayMs
        e.onProbeResult(v, hit = true, source = "ROOT")
        e.onProbeResult(v + e.policy.verifyIntervalMs, hit = true, source = "ROOT")

        assertEquals(LaunchAttemptState.SUCCESS, e.state)
        assertEquals(
            "trace: attemptIndex=${e.attemptIndex} state=${e.state} records=${e.attempts.map { "${it.index}:${it.outcome}" }}",
            3,
            e.attempts.size
        )
        assertEquals(1, e.attempts.count { it.outcome == LaunchAttemptEngine.OUTCOME_SUCCESS })
        assertEquals(
            2,
            e.attempts.count { it.outcome == LaunchAttemptEngine.OUTCOME_VERIFY_TIMEOUT }
        )
    }

    // ── 场景 4：所有尝试都失败 ──────────────────────────────────────

    @Test
    fun `三次全部失败后停在 MANUAL_REQUIRED 而不是无限重试`() {
        val e = engine().dispatch(t0)
        var now = t0
        repeat(3) { i ->
            now = exhaustVerifyWindow(e, now)
            if (i < 2) {
                now += e.policy.delayAfterAttempt(i + 1)
                assertTrue(e.onTick(now) is LaunchAttemptEngine.Decision.DispatchLaunch)
                e.onLaunchDispatched(now)
            }
        }

        assertEquals(LaunchAttemptState.MANUAL_REQUIRED, e.state)
        assertEquals(LaunchAttemptEngine.REASON_VERIFY_TIMEOUT, e.lastError)
        assertEquals(3, e.attempts.size)
        assertTrue("终态之后不应该再派发启动", e.onTick(now + 100_000) is LaunchAttemptEngine.Decision.Idle)
    }

    // ── 场景 6：目标 App 启动后立即退出（闪退）──────────────────────

    @Test
    fun `启动后立即退出不应被判成功`() {
        val e = engine().dispatch(t0)
        val v = t0 + e.policy.firstVerifyDelayMs

        // 第一下看到在前台（App 刚起来）
        e.onProbeResult(v, hit = true)
        assertEquals(1, e.stableHits)

        // 800ms 后已经退回桌面：命中被清零，本次尝试最终失败
        val v2 = v + e.policy.verifyIntervalMs
        e.onProbeResult(v2, hit = false)
        assertEquals(0, e.stableHits)

        val end = v + e.policy.verifyWindowMs
        e.onTick(end)
        assertEquals(LaunchAttemptState.RETRY_WAIT, e.state)
        assertEquals(LaunchAttemptEngine.OUTCOME_VERIFY_TIMEOUT, e.attempts[0].outcome)
    }

    @Test
    fun `只命中一次不足以判定成功`() {
        val e = engine(LaunchPolicy(requiredStableHits = 3)).dispatch(t0)
        val v = t0 + e.policy.firstVerifyDelayMs
        e.onProbeResult(v, hit = true)
        e.onProbeResult(v + 800, hit = true)
        assertEquals(LaunchAttemptState.VERIFYING, e.state)
        e.onProbeResult(v + 1600, hit = true)
        assertEquals(LaunchAttemptState.SUCCESS, e.state)
    }

    // ── 场景 7：启动过程中规则被取消 ────────────────────────────────

    @Test
    fun `取消后任何事件都不再生效`() {
        val e = engine().dispatch(t0)
        val d = e.cancel(t0 + 1000)
        assertTrue(d is LaunchAttemptEngine.Decision.Finished)
        assertEquals(LaunchAttemptState.CANCELLED, e.state)

        // 迟到的探测、tick、再次取消都不改变状态
        assertTrue(e.onProbeResult(t0 + 2000, hit = true) is LaunchAttemptEngine.Decision.Idle)
        assertTrue(e.onTick(t0 + 3000) is LaunchAttemptEngine.Decision.Idle)
        assertEquals(LaunchAttemptState.CANCELLED, e.state)
        assertEquals(1, e.attempts.count { it.outcome == LaunchAttemptEngine.OUTCOME_CANCELLED })
    }

    // ── 场景 8：重试期间重复 Alarm ──────────────────────────────────

    @Test
    fun `已进入重试等待时新的 start 不会开第二套循环`() {
        val e = engine().dispatch(t0)
        val end = t0 + e.policy.firstVerifyDelayMs + e.policy.verifyWindowMs
        e.onTick(end)
        assertEquals(LaunchAttemptState.RETRY_WAIT, e.state)

        // 重复触发：状态机层面不允许把 attemptIndex 归 1 重来
        val d = e.start(end + 100, alreadyForeground = false)
        assertEquals(LaunchAttemptEngine.Decision.Idle, d)
        assertEquals(2, e.attemptIndex)
        assertEquals(LaunchAttemptState.RETRY_WAIT, e.state)
    }

    // ── 场景 9 / 10：进程被杀、手机重启 ────────────────────────────

    @Test
    fun `总时限内的遗留任务允许恢复`() {
        val e = engine().dispatch(t0)
        assertTrue(e.canResume(t0 + 10_000))
    }

    @Test
    fun `超过总时限的遗留任务不再恢复`() {
        val e = engine().dispatch(t0)
        assertFalse(e.canResume(t0 + e.policy.totalTimeoutMs + 1))
    }

    @Test
    fun `终态任务永不恢复`() {
        val e = engine().dispatch(t0)
        e.cancel(t0 + 100)
        assertFalse(e.canResume(t0 + 200))
    }

    @Test
    fun `超过总时限的 tick 会强制收手`() {
        val e = engine().dispatch(t0)
        val beyond = t0 + e.policy.totalTimeoutMs + 1
        val d = e.onTick(beyond)
        assertTrue(d is LaunchAttemptEngine.Decision.Finished)
        assertEquals(LaunchAttemptState.MANUAL_REQUIRED, e.state)
        assertEquals(LaunchAttemptEngine.REASON_TOTAL_TIMEOUT, e.lastError)
    }

    @Test
    fun `重试时刻会越过总时限时直接停下`() {
        // 总时限只够第一次尝试，重试候选时刻超出，应当收手而不是排出去了再校验
        val policy = LaunchPolicy(
            maxAttempts = 3,
            verifyWindowMs = 4_000,
            firstVerifyDelayMs = 2_000,
            retryDelaysMs = listOf(50_000L, 50_000L),
            totalTimeoutMs = 20_000
        )
        val e = engine(policy).dispatch(t0)
        val end = t0 + 2_000 + 4_000
        val d = e.onTick(end)
        assertTrue(d is LaunchAttemptEngine.Decision.Finished)
        assertEquals(LaunchAttemptEngine.REASON_TOTAL_TIMEOUT, e.lastError)
    }

    // ── 场景 11 / 12：目标被卸载、启动权限不足 ─────────────────────

    @Test
    fun `启动命令被系统拒绝仍继续验证而不是立刻放弃`() {
        val e = engine()
        e.start(t0, alreadyForeground = false)
        // startActivity 抛异常 / 返回 false：命令没发出去，但仍进入验证窗口，
        // 因为系统可能在上一轮已经把它拉起来了，直接判失败会误报。
        e.onLaunchDispatched(t0, dispatchAccepted = false)
        assertEquals(LaunchAttemptEngine.OUTCOME_DISPATCH_REJECTED, e.attempts[0].outcome)
        assertEquals(LaunchAttemptState.LAUNCHING, e.state)

        // 窗口内确实没起来 → 走重试
        val end = t0 + e.policy.firstVerifyDelayMs + e.policy.verifyWindowMs
        e.onTick(end)
        assertEquals(LaunchAttemptState.RETRY_WAIT, e.state)
    }

    // ── 场景 13：Root 不可用（探测源退化）──────────────────────────

    @Test
    fun `探测源不可用时按未命中处理并最终告警`() {
        val e = engine(LaunchPolicy(maxAttempts = 1)).dispatch(t0)
        val end = t0 + e.policy.firstVerifyDelayMs + e.policy.verifyWindowMs
        e.onTick(end)
        assertEquals(LaunchAttemptState.MANUAL_REQUIRED, e.state)
        // 失败原因要能说清是「验证超时」，而不是含糊的「失败」
        assertEquals(LaunchAttemptEngine.REASON_VERIFY_TIMEOUT, e.lastError)
        assertTrue(e.summary().contains("MANUAL_REQUIRED"))
    }

    // ── 策略本身的边界 ─────────────────────────────────────────────

    @Test
    fun `策略参数越界会被夹紧到可执行范围`() {
        val p = LaunchPolicy(
            maxAttempts = 99,
            verifyWindowMs = 1L,
            verifyIntervalMs = 0L,
            requiredStableHits = 0,
            retryDelaysMs = emptyList(),
            totalTimeoutMs = 1L
        ).sanitized()

        assertEquals(LaunchPolicy.MAX_ATTEMPTS, p.maxAttempts)
        assertEquals(LaunchPolicy.MIN_VERIFY_WINDOW_MS, p.verifyWindowMs)
        assertEquals(LaunchPolicy.MIN_VERIFY_INTERVAL_MS, p.verifyIntervalMs)
        assertEquals(1, p.requiredStableHits)
        assertEquals(LaunchPolicy.DEFAULT_RETRY_DELAYS_MS, p.retryDelaysMs)
        assertEquals(LaunchPolicy.MIN_TOTAL_TIMEOUT_MS, p.totalTimeoutMs)
    }

    @Test
    fun `重试间隔不足时取最后一个可用值`() {
        val p = LaunchPolicy(retryDelaysMs = listOf(5_000L, 15_000L))
        assertEquals(5_000L, p.delayAfterAttempt(1))
        assertEquals(15_000L, p.delayAfterAttempt(2))
        assertEquals(15_000L, p.delayAfterAttempt(3))
        assertEquals(15_000L, p.delayAfterAttempt(9))
    }

    @Test
    fun `关闭自动重试时第二次失败即收手`() {
        val e = engine(LaunchPolicy(enabled = false, maxAttempts = 1)).dispatch(t0)
        val end = t0 + e.policy.firstVerifyDelayMs + e.policy.verifyWindowMs
        e.onTick(end)
        assertEquals(LaunchAttemptState.MANUAL_REQUIRED, e.state)
        assertEquals(1, e.attempts.size)
    }

    // ── 辅助 ────────────────────────────────────────────────────────

    /** 把当前这次尝试的验证窗口跑到过期，返回窗口结束时刻。 */
    private fun exhaustVerifyWindow(e: LaunchAttemptEngine, from: Long): Long {
        val verifyStart = from + e.policy.firstVerifyDelayMs
        val verifyEnd = verifyStart + e.policy.verifyWindowMs
        var t = verifyStart
        while (t < verifyEnd) {
            e.onTick(t)
            e.onProbeResult(t, hit = false)
            t += e.policy.verifyIntervalMs
        }
        e.onTick(verifyEnd)
        return verifyEnd
    }

    // ── dumpsys 解析 ────────────────────────────────────────────────

    @Test
    fun `解析多屏 dump 时按 Display 分段`() {
        // 取自目标机真实 dump 的结构（节选，保留缩进与字段顺序）
        val dump = """
            ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
            Display #0 (activities from top to bottom):
              * Task{7e8393c #1283 type=standard A=1000:com.android.settings.root U=0 visible=true visibleRequested=true mode=fullscreen translucent=false sz=1}
                topResumedActivity=ActivityRecord{246024462 u0 com.android.settings/.Settings t1283}
                * Hist  #0: ActivityRecord{246024462 u0 com.android.settings/.Settings t1283}
            Display #1 (activities from top to bottom):
              * Task{afdc331 #1219 type=undefined U=0 visible=true visibleRequested=false mode=fullscreen translucent=false sz=1}
                topResumedActivity=ActivityRecord{226945639 u0 com.motorola.launcher.secondarydisplay/.SecondaryDisplayLauncher t1221}
            Display #15 (activities from top to bottom):
        """.trimIndent()

        val map = DumpsysParser.parseTopByDisplay(dump)
        assertEquals("com.android.settings", map[0])
        assertEquals("com.motorola.launcher.secondarydisplay", map[1])
        assertFalse(map.containsKey(15))
    }

    @Test
    fun `topResumedActivity 为 null 时不误判`() {
        val dump = """
            Display #0 (activities from top to bottom):
              * Task{1 #1 type=undefined}
                topResumedActivity=null
                mLastPausedActivity: ActivityRecord{99 u0 com.foo.bar/.Main t1}
        """.trimIndent()
        assertTrue(DumpsysParser.parseTopByDisplay(dump).isEmpty())
    }

    @Test
    fun `ActivityRecord 中字段位置变化也能取到包名`() {
        // 不同 Android 版本会在 u0 与组件名之间插入额外字段
        val dump = """
            Display #0 (activities from top to bottom):
                topResumedActivity=ActivityRecord{a1b2c3 u0 visible=true com.example.some.app/com.example.some.app.MainActivity t42}
        """.trimIndent()
        assertEquals("com.example.some.app", DumpsysParser.parseTopByDisplay(dump)[0])
    }

    @Test
    fun `窗口焦点解析在 null 时返回 null`() {
        assertEquals(null, DumpsysParser.parseFocusedPackage("  mCurrentFocus=null"))
        assertEquals(
            "com.android.settings",
            DumpsysParser.parseFocusedPackage(
                "  mCurrentFocus=Window{1a2b u0 com.android.settings/com.android.settings.Settings}"
            )
        )
    }
}
