package com.workbuddy.quicklaunch

import com.workbuddy.quicklaunch.launch.DumpsysParser
import com.workbuddy.quicklaunch.launch.ForegroundJudge
import com.workbuddy.quicklaunch.launch.LaunchAttemptEngine
import com.workbuddy.quicklaunch.launch.LaunchAttemptState
import com.workbuddy.quicklaunch.launch.LaunchPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁屏场景的判定与收手策略。
 *
 * 这些用例的 dump 片段取自 2026-11-23 真机（锁屏 + 屏幕已点亮）的原样输出：
 * 整个 dump 没有任何 `topResumedActivity`，目标 App 只出现在 `mLastPausedActivity`，
 * 且 Task 带 `isSleeping=true`。
 *
 * 修复前的行为：解析器返回空 → UNKNOWN → 降级 UsageStats → 判 NOT_VISIBLE →
 * 按普通失败重试 3 次，每次都点亮一次屏幕，而锁屏状态不会因为重试而改变。
 */
class LockScreenLaunchTest {

    /** 真机锁屏 dump 的结构（节选，保留缩进与字段顺序）。 */
    private val lockedDump = """
        ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
        Display #0 (activities from top to bottom):
          * Task{7e8393c #1283 type=standard A=1000:com.android.settings.root U=0 visible=true visibleRequested=false mode=fullscreen translucent=false sz=1}
            mLastPausedActivity: ActivityRecord{246024462 u0 com.android.settings/.Settings t1283}
            mLastNonFullscreenBounds=Rect(304, 802 - 776, 1882)
            isSleeping=true
            * Hist  #0: ActivityRecord{246024462 u0 com.android.settings/.Settings t1283}
              packageName=com.android.settings processName=com.android.settings
          * Task{b82bae0 #1294 type=standard A=10388:com.workbuddy.quicklaunch U=0 visible=false visibleRequested=false mode=fullscreen translucent=true sz=1}
            mLastPausedActivity: ActivityRecord{164692391 u0 com.workbuddy.quicklaunch/.LaunchProxyActivity t-1 f}}
            isSleeping=true
    """.trimIndent()

    /** 正常（已解锁）dump：有 topResumedActivity。 */
    private val unlockedDump = """
        Display #0 (activities from top to bottom):
          * Task{7e8393c #1283 type=standard A=1000:com.android.settings.root U=0 visible=true visibleRequested=true mode=fullscreen translucent=false sz=1}
            topResumedActivity=ActivityRecord{246024462 u0 com.android.settings/.Settings t1283}
    """.trimIndent()

    // ── 解析器 ──────────────────────────────────────────────────────

    @Test
    fun `锁屏 dump 中没有任何 topResumedActivity`() {
        val state = DumpsysParser.parseState(lockedDump)
        assertTrue("锁屏时不该解析出 resumed Activity", state.topByDisplay.isEmpty())
    }

    @Test
    fun `锁屏 dump 能识别睡眠屏与最后被暂停的目标`() {
        val state = DumpsysParser.parseState(lockedDump)
        assertTrue(state.sleepingDisplays.contains(0))
        assertEquals("com.android.settings", state.lastPausedByDisplay[0])
        assertTrue(state.sleepingWith("com.android.settings"))
    }

    @Test
    fun `锁屏 dump 对其他包不误报睡眠命中`() {
        val state = DumpsysParser.parseState(lockedDump)
        // LaunchProxyActivity 也在 lastPaused 里，但它不是「目标」
        assertFalse(state.sleepingWith("tv.danmaku.bili"))
        assertFalse(state.sleepingWith(""))
    }

    @Test
    fun `已解锁 dump 不标记睡眠`() {
        val state = DumpsysParser.parseState(unlockedDump)
        assertFalse(state.anySleeping)
        assertFalse(state.sleepingWith("com.android.settings"))
        assertEquals("com.android.settings", state.topByDisplay[0])
    }

    // ── 判定 ────────────────────────────────────────────────────────

    @Test
    fun `目标在锁屏后应判 BLOCKED 而不是 NOT_VISIBLE`() {
        val state = DumpsysParser.parseState(lockedDump)
        val verdict = ForegroundJudge.judge(
            targetPkg = "com.android.settings",
            topByDisplay = state.topByDisplay,
            litDisplays = setOf(0),   // 屏幕是亮的（turnScreenOn 生效）
            sleepingWithTarget = state.sleepingWith("com.android.settings")
        )
        assertEquals(ForegroundJudge.Verdict.BLOCKED_BY_KEYGUARD, verdict)
    }

    @Test
    fun `锁屏但不是目标 App 时仍判 NOT_VISIBLE`() {
        val state = DumpsysParser.parseState(lockedDump)
        val verdict = ForegroundJudge.judge(
            targetPkg = "tv.danmaku.bili",
            topByDisplay = state.topByDisplay,
            litDisplays = setOf(0),
            deviceSleeping = state.anySleeping,
            sleepingWithTarget = state.sleepingWith("tv.danmaku.bili")
        )
        assertEquals(ForegroundJudge.Verdict.NOT_VISIBLE, verdict)
    }

    @Test
    fun `已解锁且目标在前台仍然判 VISIBLE`() {
        val state = DumpsysParser.parseState(unlockedDump)
        val verdict = ForegroundJudge.judge(
            targetPkg = "com.android.settings",
            topByDisplay = state.topByDisplay,
            litDisplays = setOf(0),
            deviceSleeping = state.anySleeping,
            sleepingWithTarget = state.sleepingWith("com.android.settings")
        )
        assertEquals(ForegroundJudge.Verdict.VISIBLE, verdict)
    }

    @Test
    fun `完全没有信息时仍判 UNKNOWN（不能当成失败）`() {
        assertEquals(
            ForegroundJudge.Verdict.UNKNOWN,
            ForegroundJudge.judge(
                "com.a.b", emptyMap(), setOf(0),
                deviceSleeping = false, sleepingWithTarget = false
            )
        )
    }

    // ── 状态机：锁屏必须立刻收手 ────────────────────────────────────

    private fun engine(policy: LaunchPolicy = LaunchPolicy()) = LaunchAttemptEngine(
        attemptId = "k1", ruleId = 1L, pkg = "com.android.settings", appName = "设置",
        policy = policy, startedAt = 1_000_000L
    )

    @Test
    fun `锁屏挡住时不重试，直接 MANUAL_REQUIRED`() {
        val e = engine()
        e.start(1_000_000L, alreadyForeground = false)
        e.onLaunchDispatched(1_010_000L)
        val d = e.onBlockedByKeyguard(1_020_000L, "ROOT_DUMPSYS")

        assertTrue(d is LaunchAttemptEngine.Decision.Finished)
        assertEquals(LaunchAttemptState.MANUAL_REQUIRED, e.state)
        assertEquals(LaunchAttemptEngine.REASON_KEYGUARD_BLOCKED, e.lastError)
        assertEquals(1, e.attemptIndex)                       // 没有排下一次
        assertEquals(LaunchAttemptEngine.OUTCOME_KEYGUARD_BLOCKED, e.attempts[0].outcome)

        // 终态后 tick 不再产生动作
        assertTrue(e.onTick(1_100_000L) is LaunchAttemptEngine.Decision.Idle)
    }

    @Test
    fun `锁屏挡住不会消耗全部重试额度`() {
        val e = engine(LaunchPolicy(maxAttempts = 3))
        e.start(0L, alreadyForeground = false)
        e.onLaunchDispatched(0L)
        e.onBlockedByKeyguard(1_000L)
        // 只有一次尝试记录，说明没有走到「重试 3 次」
        assertEquals(1, e.attempts.size)
    }

    @Test
    fun `重复上报锁屏不会重复结案`() {
        val e = engine()
        e.start(0L, alreadyForeground = false)
        e.onLaunchDispatched(0L)
        e.onBlockedByKeyguard(1_000L)
        val again = e.onBlockedByKeyguard(2_000L)
        assertEquals(LaunchAttemptEngine.Decision.Idle, again)
    }
}
