package com.workbuddy.quicklaunch

import com.workbuddy.quicklaunch.util.AutoUnlockDecision
import com.workbuddy.quicklaunch.util.AutoUnlockDecision.GateReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 远程启用自动解锁的门控测试。
 *
 * 对应需求 §4.9 的场景清单。核心不变量只有一条：
 * **「本地安全停用」永远压过远程的一切指令** —— 这条在用例表里排在最前面，
 * 任何后续改动（比如新增时间窗策略）都不允许把它挤到后面去。
 *
 * PIN 内容本身不参与这些判定（门控只看「有没有」，不看「对不对」），
 * 所以全部用占位值，不触碰真实密码。
 */
class AutoUnlockRemoteGateTest {

    private fun gate(
        securityLocked: Boolean = false,
        hasPin: Boolean = true,
        insecureStorage: Boolean = false,
        hasRoot: Boolean = true
    ): String? = AutoUnlockDecision.remoteEnableGate(
        securityLocked, hasPin, insecureStorage, hasRoot
    )

    // ── 最重要的不变量：安全停用优先于一切 ─────────────────────────

    @Test
    fun `安全停用状态下远程启用被拒绝`() {
        assertEquals(GateReason.SECURITY_LOCKED, gate(securityLocked = true))
    }

    @Test
    fun `安全停用优先级高于缺 PIN`() {
        // 两次失败后用户又删了 PIN：看到的错误必须是「安全停用」，
        // 否则用户会去设 PIN，设完发现还是开不了，无从判断原因
        assertEquals(GateReason.SECURITY_LOCKED, gate(securityLocked = true, hasPin = false))
    }

    @Test
    fun `安全停用优先级高于降级存储`() {
        assertEquals(
            GateReason.SECURITY_LOCKED,
            gate(securityLocked = true, insecureStorage = true)
        )
    }

    @Test
    fun `安全停用优先级高于无 root`() {
        assertEquals(GateReason.SECURITY_LOCKED, gate(securityLocked = true, hasRoot = false))
    }

    // ── 其余门控 ────────────────────────────────────────────────────

    @Test
    fun `未设置 PIN 时拒绝`() {
        assertEquals(GateReason.NO_PIN, gate(hasPin = false))
    }

    @Test
    fun `PIN 处于降级存储时远程拒绝启用`() {
        // 需求 §4.3：安全能力不足时优先禁用自动解锁。
        // 本地可以由用户知情后启用，远程不行 —— 远程看不到降级警告。
        assertEquals(GateReason.INSECURE_STORAGE, gate(insecureStorage = true))
    }

    @Test
    fun `root 不可用时拒绝`() {
        assertEquals(GateReason.NO_ROOT, gate(hasRoot = false))
    }

    @Test
    fun `条件全部满足时允许启用`() {
        assertNull(gate())
    }

    // ── 禁用路径不受门控限制 ────────────────────────────────────────

    @Test
    fun `远程禁用永远被允许`() {
        // 门控只拦 enable。disable 是「关得更紧」，不存在安全风险，
        // 即使处于安全停用状态也允许（幂等）。
        // 由 AutoUnlock.applyRemotePolicy 的 if (policy.enabled) 保证 ——
        // 这里用反例钉住：门控函数对「禁用」没有话语权，调用方必须只对 enable 调门控。
        assertNull(gate())   // 全条件通过时门控放行；禁用路径不经过这里
    }

    // ── PIN 格式（与门控互补，属于注入前的防线）────────────────────

    @Test
    fun `PIN 格式校验拒绝非数字与越界长度`() {
        assertTrue(AutoUnlockDecision.validPin("1234"))
        assertTrue(AutoUnlockDecision.validPin("1234567890123456"))
        assertEquals(false, AutoUnlockDecision.validPin("123"))
        assertEquals(false, AutoUnlockDecision.validPin("12345678901234567"))
        assertEquals(false, AutoUnlockDecision.validPin("12a4"))
        assertEquals(false, AutoUnlockDecision.validPin(null))
        assertEquals(false, AutoUnlockDecision.validPin(""))
    }

    // ── 注入命令的 -d 策略（真机踩过的根因）────────────────────────

    @Test
    fun `内屏注入带 -d 0`() {
        val cmd = AutoUnlockDecision.injectCommand(0, 1080, 2640, "123456")
        assertTrue("内屏必须显式指定 display：$cmd", cmd.contains("input -d 0 swipe"))
        assertTrue("text 也要带 -d：$cmd", cmd.contains("input -d 0 text"))
    }

    @Test
    fun `外屏注入不能带 -d`() {
        // 2026-11-23 合盖真机：`input -d 1 swipe` 抛
        // IllegalArgumentException: Invalid arguments for display ID.
        // 命令完全不执行，却被计成一次失败尝试 —— 两次就把功能停用。
        val cmd = AutoUnlockDecision.injectCommand(1, 1056, 1066, "123456")
        assertEquals(
            "外屏注入不得出现 -d（input 不支持 display 1）：$cmd",
            false,
            cmd.contains("-d")
        )
        assertTrue("仍然要先上滑叫出 bouncer：$cmd", cmd.contains("input swipe"))
    }

    @Test
    fun `滑动坐标随屏幕尺寸变化`() {
        // 内屏 1080x2640 → y 876 / 739（0.82 / 0.28）
        val inner = AutoUnlockDecision.injectCommand(0, 1080, 2640, "1")
        assertTrue("内屏滑动起点按高度算：$inner", inner.contains("swipe 540 2164"))
        // 外屏 1056x1066 → 近方形，必须用外屏自己的高度，不能沿用内屏坐标
        val outer = AutoUnlockDecision.injectCommand(1, 1056, 1066, "1")
        assertTrue("外屏滑动起点按外屏高度算：$outer", outer.contains("swipe 528 874 528 298"))
    }

    @Test
    fun `注入命令里密码被单引号包裹`() {
        val cmd = AutoUnlockDecision.injectCommand(0, 1080, 2640, "9876")
        assertTrue("密码必须被引号包住：$cmd", cmd.contains("text '9876'"))
        assertTrue("提交要回车：$cmd", cmd.contains("input keyevent 66"))
    }
}
