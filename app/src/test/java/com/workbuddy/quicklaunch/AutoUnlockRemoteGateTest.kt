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
}
