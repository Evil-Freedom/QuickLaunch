package com.workbuddy.quicklaunch

import com.workbuddy.quicklaunch.launch.DumpsysParser
import com.workbuddy.quicklaunch.launch.ForegroundJudge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 折叠屏合盖（外屏 display 1 活跃、内屏 display 0 物理关闭）的判定。
 *
 * dump 片段取自 2026-11-23 真机合盖状态的原样输出。关键结构：
 *
 * ```
 * Display #1 ... isSleeping=false          ← 外屏活着，有自己的桌面
 *   topResumedActivity=...SecondaryDisplayLauncher
 * Display #0 ... isSleeping=true            ← 内屏物理关闭
 *   mLastPausedActivity: ... com.android.settings/.Settings   ← 旧的启动记录
 * ```
 *
 * 修复前的两个错误：
 * 1. `anySleeping` 为真 → note 报「设备睡眠中」（实际设备清醒，只是内屏关了）
 * 2. 内屏 lastPaused 里的目标被当成「被锁屏挡住」→ 误判 BLOCKED
 */
class FoldedScreenTest {

    private val foldedDump = """
        ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
        Display #1 (activities from top to bottom):
          * Task{afdc331 #1219 type=undefined U=0 visible=true visibleRequested=false mode=fullscreen translucent=false sz=1}
            topResumedActivity=ActivityRecord{104489325 u0 com.motorola.launcher.secondarydisplay/.SecondaryDisplayLauncher t1220}
            isSleeping=false
        Display #0 (activities from top to bottom):
          * Task{7e8393c #1283 type=standard A=1000:com.android.settings.root U=0 visible=true visibleRequested=false mode=fullscreen translucent=false sz=1}
            mLastPausedActivity: ActivityRecord{246024462 u0 com.android.settings/.Settings t1283}
            isSleeping=true
        Display #5 (activities from top to bottom):
    """.trimIndent()

    @Test
    fun `合盖 dump 能读出外屏的前台`() {
        val state = DumpsysParser.parseState(foldedDump)
        assertEquals(
            "com.motorola.launcher.secondarydisplay",
            state.topByDisplay[1]
        )
    }

    @Test
    fun `合盖时内屏睡眠不等于设备睡眠`() {
        val state = DumpsysParser.parseState(foldedDump)
        // 外屏（1）在亮着且不睡眠 → 设备是清醒的
        assertFalse(state.deviceSleeping(setOf(1)))
        // 反过来：如果只看内屏那一条记录，才会误判成睡眠
        assertTrue(state.deviceSleeping(setOf(0)))
    }

    @Test
    fun `内屏上的旧启动记录不算被锁屏挡住`() {
        val state = DumpsysParser.parseState(foldedDump)
        // lit={1}：用户看的是外屏。内屏(0)的 lastPaused 是 settings，但它不亮，不算挡住
        assertFalse(state.sleepingWith("com.android.settings", litDisplays = setOf(1)))
        // 只有把内屏当成「亮着的屏」时才会命中（这正是不加 lit 过滤的错误）
        assertTrue(state.sleepingWith("com.android.settings", litDisplays = setOf(0)))
    }

    @Test
    fun `合盖且外屏是桌面时判 NOT_VISIBLE`() {
        val state = DumpsysParser.parseState(foldedDump)
        val lit = setOf(1)
        val verdict = ForegroundJudge.judge(
            targetPkg = "com.android.settings",
            topByDisplay = state.topByDisplay,
            litDisplays = lit,
            deviceSleeping = state.deviceSleeping(lit),
            sleepingWithTarget = state.sleepingWith("com.android.settings", lit)
        )
        assertEquals(ForegroundJudge.Verdict.NOT_VISIBLE, verdict)
    }

    @Test
    fun `目标真的在外屏前台时判 VISIBLE`() {
        val dump = """
            Display #1 (activities from top to bottom):
              * Task{a1 #2 ...}
                topResumedActivity=ActivityRecord{99 u0 com.android.settings/.Settings t5}
                isSleeping=false
            Display #0 (activities from top to bottom):
              * Task{b2 #3 ...}
                isSleeping=true
        """.trimIndent()
        val state = DumpsysParser.parseState(dump)
        val lit = setOf(1)
        val verdict = ForegroundJudge.judge(
            targetPkg = "com.android.settings",
            topByDisplay = state.topByDisplay,
            litDisplays = lit,
            deviceSleeping = state.deviceSleeping(lit),
            sleepingWithTarget = state.sleepingWith("com.android.settings", lit)
        )
        assertEquals(ForegroundJudge.Verdict.VISIBLE, verdict)
    }

    @Test
    fun `不亮的内屏上有目标也不会把外屏的判定带偏`() {
        // 外屏前台是别的 App，内屏睡眠且 lastPaused 是目标 → 仍是 NOT_VISIBLE
        val state = DumpsysParser.parseState(foldedDump)
        val lit = setOf(1)
        assertFalse(state.deviceSleeping(lit))
        assertEquals(
            ForegroundJudge.Verdict.NOT_VISIBLE,
            ForegroundJudge.judge(
                "com.android.settings", state.topByDisplay, lit,
                deviceSleeping = state.deviceSleeping(lit),
                sleepingWithTarget = state.sleepingWith("com.android.settings", lit)
            )
        )
    }
}
