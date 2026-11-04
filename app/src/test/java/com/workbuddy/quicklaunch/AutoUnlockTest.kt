package com.workbuddy.quicklaunch

import com.workbuddy.quicklaunch.util.AutoUnlockDecision
import com.workbuddy.quicklaunch.util.AutoUnlockDecision.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁屏自动解锁的判定边界。
 *
 * 这些用例存在的意义：注入密码是**有副作用且危险**的动作（错误次数太多会触发系统
 * 清除数据），所以「什么时候允许动作」必须被钉死，不能靠运行时试。
 */
class AutoUnlockTest {

    private fun decide(
        enabled: Boolean = true,
        hasPin: Boolean = true,
        deviceLocked: Boolean = true,
        displayLit: Boolean = true,
        chargingOnly: Boolean = false,
        charging: Boolean = false,
        failures: Int = 0,
        sinceLast: Long = Long.MAX_VALUE
    ) = AutoUnlockDecision.decide(
        enabled, hasPin, deviceLocked, displayLit, chargingOnly, charging, failures, sinceLast
    )

    @Test
    fun `一切就绪时尝试解锁`() {
        assertEquals(Action.ATTEMPT, decide())
    }

    @Test
    fun `功能关闭不动作`() {
        assertEquals(Action.SKIP, decide(enabled = false))
    }

    @Test
    fun `没设密码不动作`() {
        assertEquals(Action.SKIP, decide(hasPin = false))
    }

    @Test
    fun `没锁屏不动作`() {
        assertEquals(Action.SKIP, decide(deviceLocked = false))
    }

    @Test
    fun `黑屏时不注入输入事件`() {
        assertEquals(Action.SKIP, decide(displayLit = false))
    }

    @Test
    fun `仅充电模式下未充电不动作`() {
        assertEquals(Action.SKIP, decide(chargingOnly = true, charging = false))
    }

    @Test
    fun `仅充电模式下正在充电则动作`() {
        assertEquals(Action.ATTEMPT, decide(chargingOnly = true, charging = true))
    }

    @Test
    fun `最小间隔内不重复注入`() {
        assertEquals(Action.SKIP, decide(sinceLast = AutoUnlockDecision.MIN_INTERVAL_MS - 1))
        assertEquals(
            Action.ATTEMPT,
            decide(sinceLast = AutoUnlockDecision.MIN_INTERVAL_MS)
        )
    }

    @Test
    fun `达到失败上限立刻放弃`() {
        assertEquals(Action.GIVE_UP, decide(failures = AutoUnlockDecision.MAX_FAILURES))
    }

    @Test
    fun `放弃判定优先于灭屏跳过`() {
        // 即使屏灭了也要保持 GIVE_UP，否则用户永远看不到「已暂停」的提示
        assertEquals(
            Action.GIVE_UP,
            decide(failures = AutoUnlockDecision.MAX_FAILURES, displayLit = false)
        )
    }

    @Test
    fun `上限前仍会再试一次`() {
        assertEquals(Action.ATTEMPT, decide(failures = AutoUnlockDecision.MAX_FAILURES - 1))
    }

    @Test
    fun `密码格式只接受4到16位数字`() {
        assertTrue(AutoUnlockDecision.validPin("941007"))
        assertTrue(AutoUnlockDecision.validPin("1234"))
        assertTrue(AutoUnlockDecision.validPin("1234567890123456"))
        assertFalse(AutoUnlockDecision.validPin("123"))
        assertFalse(AutoUnlockDecision.validPin("12345678901234567"))
        assertFalse(AutoUnlockDecision.validPin("abcd"))
        assertFalse(AutoUnlockDecision.validPin("12a4"))
        assertFalse(AutoUnlockDecision.validPin(""))
        assertFalse(AutoUnlockDecision.validPin(null))
    }

    @Test
    fun `上滑手势按屏幕尺寸居中且落在屏内`() {
        // 外屏 1056x1066
        val outer = AutoUnlockDecision.swipeFromSize(1056, 1066)
        assertEquals(528, outer[0])
        assertEquals(874, outer[1])
        assertEquals(528, outer[2])
        assertEquals(298, outer[3])

        // 内屏 1080x2640
        val inner = AutoUnlockDecision.swipeFromSize(1080, 2640)
        assertEquals(540, inner[0])
        assertEquals(2164, inner[1])
        assertEquals(739, inner[3])
    }
}
