package com.workbuddy.quicklaunch

import com.workbuddy.quicklaunch.util.AutoUnlockDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 外屏注入的纯逻辑测试。
 *
 * 数据全部来自 2026-11-23 合盖真机（Razr 40 Ultra / Android 16）的原始输出，
 * 不是构造的样例 —— 这类「命令行到底能不能用」的问题，只有真机数据才算证据。
 */
class OuterScreenInjectTest {

    // ── 上滑脚本 ──────────────────────────────────────────────────

    @Test
    fun `上滑脚本按多点触控协议 B 排列`() {
        val s = AutoUnlockDecision.swipeScript("/dev/input/event8", 16f, 528, 874, 298, steps = 2)
        val lines = s.split("; ")

        // 按下序列：SLOT → TRACKING_ID → X → Y → BTN_TOUCH → SYN
        assertEquals("sendevent /dev/input/event8 3 47 0", lines[0])
        assertEquals("sendevent /dev/input/event8 3 57 1", lines[1])
        assertEquals("sendevent /dev/input/event8 3 53 8448", lines[2])   // 528 * 16
        assertEquals("sendevent /dev/input/event8 3 54 13984", lines[3])  // 874 * 16
        assertEquals("sendevent /dev/input/event8 1 330 1", lines[4])
        assertEquals("sendevent /dev/input/event8 0 0 0", lines[5])

        // 抬起序列
        assertTrue(lines[lines.size - 3].endsWith("3 57 -1"))
        assertTrue(lines[lines.size - 2].endsWith("1 330 0"))
        assertTrue(lines[lines.size - 1].endsWith("0 0 0"))
    }

    @Test
    fun `上滑脚本的中间点单调下降且落在起止之间`() {
        val s = AutoUnlockDecision.swipeScript("/dev/input/event8", 16f, 528, 874, 298, steps = 4)
        // 取出所有 Y 坐标（code 54），去掉按下点后的中间点应严格递减
        val ys = Regex("""/dev/input/event8 3 54 (-?\d+)""")
            .findAll(s).map { it.groupValues[1].toInt() }.toList()
        assertTrue("至少要有起点 + 4 个中间点，实际 $ys", ys.size >= 5)
        for (i in 1 until ys.size) {
            assertTrue("Y 必须单调下降：$ys", ys[i] < ys[i - 1])
        }
        assertEquals("最后一个点应等于终点 298*16", 298 * 16, ys.last())
    }

    @Test
    fun `缩放比按浮点计算不丢精度`() {
        // 15.9 这种非整数缩放常见于非标准分辨率，取整方向错了会偏向边缘
        val s = AutoUnlockDecision.swipeScript("/dev/input/event8", 15.9f, 528, 874, 298)
        assertTrue("X 应按 15.9 缩放：$s", s.contains("3 53 8395"))   // 528*15.9 = 8395.2
        assertTrue("起点 Y：$s", s.contains("3 54 13896"))            // 874*15.9 = 13896.6 → 13896
    }

    // ── 触摸设备选择（真机 getevent 输出）────────────────────────

    private val realDump = """
add device 1: /dev/input/event16
  name:     "MotFlip_LID"
add device 2: /dev/input/event15
  name:     "goodix_ts"
                ABS_MT_POSITION_X     : value 0, min 0, max 17280, fuzz 0, flat 0, resolution 0
                ABS_MT_POSITION_Y     : value 0, min 0, max 42240, fuzz 0, flat 0, resolution 0
add device 3: /dev/input/event14
  name:     "s-double-tap"
add device 9: /dev/input/event8
  name:     "fts_ts"
                ABS_MT_POSITION_X     : value 0, min 0, max 16896, fuzz 0, flat 0, resolution 0
                ABS_MT_POSITION_Y     : value 0, min 0, max 17056, fuzz 0, flat 0, resolution 0
""".trimIndent()

    @Test
    fun `外屏选中 fts_ts 而不是内屏触摸`() {
        val picked = AutoUnlockDecision.pickTouchDevice(realDump, 1056, 1066)
        assertNotNull("外屏必须找到触摸设备", picked)
        assertEquals("/dev/input/event8", picked!!.first)
        assertEquals(16f, picked.second, 0.05f)
    }

    @Test
    fun `内屏选中 goodix_ts`() {
        val picked = AutoUnlockDecision.pickTouchDevice(realDump, 1080, 2640)
        assertNotNull("内屏必须找到触摸设备", picked)
        assertEquals("/dev/input/event15", picked!!.first)
        assertEquals(16f, picked.second, 0.05f)
    }

    @Test
    fun `没有匹配的触摸设备时返回 null`() {
        // 目标屏尺寸与两个设备都不成比例（例如外接的 3840x2160 显示器）
        assertNull(AutoUnlockDecision.pickTouchDevice(realDump, 3840, 2160))
    }

    @Test
    fun `空输出或非法尺寸不会崩`() {
        assertNull(AutoUnlockDecision.pickTouchDevice("", 1056, 1066))
        assertNull(AutoUnlockDecision.pickTouchDevice(realDump, 0, 1066))
        assertNull(AutoUnlockDecision.pickTouchDevice(realDump, 1056, -1))
    }

    @Test
    fun `只有 X 轴没有 Y 轴的设备被忽略`() {
        val partial = """
add device 1: /dev/input/event8
  name:     "half_ts"
                ABS_MT_POSITION_X     : value 0, min 0, max 16896, fuzz 0, flat 0, resolution 0
""".trimIndent()
        assertNull(AutoUnlockDecision.pickTouchDevice(partial, 1056, 1066))
    }
}
