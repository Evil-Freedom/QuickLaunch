package com.workbuddy.quicklaunch

import com.workbuddy.quicklaunch.data.Automation
import com.workbuddy.quicklaunch.data.RepeatMode
import com.workbuddy.quicklaunch.data.TriggerType
import com.workbuddy.quicklaunch.util.AutomationCodec
import com.workbuddy.quicklaunch.util.RuleFormMapper
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 编辑/修改功能的核心纯逻辑测试。
 *
 * 覆盖两块最容易静默出错的地方：
 *
 * 1. **表单下标 ↔ 字符串枚举的双向映射。**
 *    App 的历史坑：`TriggerType.TIME = "TIME"`（全大写）而 `RepeatMode.WEEKDAYS = "weekdays"`
 *    （全小写），大小写风格相反。手工写字符串字面量极易写错，而写错的后果极其隐蔽 ——
 *    `when (a.repeatMode)` 匹配不到任何分支，不会抛异常，只是静默不做过滤。
 *    这里用「下标 → 字符串 → 下标」往返断言 + 直接对常量断言，把这个坑焊死。
 *
 * 2. **规则留档的 JSON 编解码。**
 *    少写一个字段的后果是：编辑一次后该字段被静默重置为默认值，用户数据丢失。
 *    除了「全部字段非默认值往返」之外，还额外断言 JSON 的 key 集合
 *    与 `Automation` 声明的字段集合**完全相等** —— 这样将来给实体加字段却忘了改编解码，
 *    测试会直接失败，而不是等到线上丢数据。
 */
class RuleEditingTest {

    // ══════════════════════════════════════════════════════════════════
    // 触发条件映射
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `触发条件下标与常量互相对应`() {
        assertEquals(TriggerType.TIME, RuleFormMapper.triggerTypeOf(0))
        assertEquals(TriggerType.CHARGING, RuleFormMapper.triggerTypeOf(1))
        assertEquals(TriggerType.WIFI, RuleFormMapper.triggerTypeOf(2))
        assertEquals(TriggerType.BLUETOOTH, RuleFormMapper.triggerTypeOf(3))
    }

    @Test
    fun `触发条件字符串能反解回下标`() {
        assertEquals(0, RuleFormMapper.triggerIndexOf(TriggerType.TIME))
        assertEquals(1, RuleFormMapper.triggerIndexOf(TriggerType.CHARGING))
        assertEquals(2, RuleFormMapper.triggerIndexOf(TriggerType.WIFI))
        assertEquals(3, RuleFormMapper.triggerIndexOf(TriggerType.BLUETOOTH))
    }

    @Test
    fun `触发条件下标往返完全闭合`() {
        for (i in 0..3) {
            val type = RuleFormMapper.triggerTypeOf(i)
            // 顺带钉死字面量大小写：TIME 必须全大写，其余必须全小写
            assertEquals(
                "下标 $i 的常量字面量不符合预期（大小写敏感）",
                type, listOf("TIME", "CHARGING", "WIFI", "BLUETOOTH")[i]
            )
            assertEquals("下标 $i 往返后不一致", i, RuleFormMapper.triggerIndexOf(type))
        }
    }

    @Test
    fun `未知触发条件回退到定时而不是抛异常`() {
        assertEquals(0, RuleFormMapper.triggerIndexOf("nonexistent"))
        assertEquals(0, RuleFormMapper.triggerIndexOf(""))
        // 旧版本可能写成小写，也要能兼容识别
        assertEquals(0, RuleFormMapper.triggerIndexOf("time"))
    }

    // ══════════════════════════════════════════════════════════════════
    // 重复模式映射
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `重复模式下标与常量互相对应`() {
        assertEquals(RepeatMode.DAILY, RuleFormMapper.repeatKeyOf(0))
        assertEquals(RepeatMode.WEEKDAYS, RuleFormMapper.repeatKeyOf(1))
        assertEquals(RepeatMode.WEEKEND, RuleFormMapper.repeatKeyOf(2))
        assertEquals(RepeatMode.CUSTOM, RuleFormMapper.repeatKeyOf(3))
        assertEquals(RepeatMode.ONCE, RuleFormMapper.repeatKeyOf(4))
    }

    @Test
    fun `重复模式下标往返完全闭合`() {
        for (i in 0..4) {
            val key = RuleFormMapper.repeatKeyOf(i)
            assertEquals(
                "下标 $i 的 repeatMode 字面量不符合预期（全小写）",
                key, listOf("daily", "weekdays", "weekend", "custom", "once")[i]
            )
            assertEquals("下标 $i 往返后不一致", i, RuleFormMapper.repeatIndexOf(key))
        }
    }

    @Test
    fun `未知重复模式回退到每天而不是抛异常`() {
        assertEquals(0, RuleFormMapper.repeatIndexOf("nonsense"))
        assertEquals(0, RuleFormMapper.repeatIndexOf(""))
        // 历史数据里曾出现全大写写法，编辑时必须能正确识别，否则用户一改就被重置成「每天」
        assertEquals(1, RuleFormMapper.repeatIndexOf("WEEKDAYS"))
        assertEquals(4, RuleFormMapper.repeatIndexOf("ONCE"))
    }

    // ══════════════════════════════════════════════════════════════════
    // 自定义星期位图
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `位图与布尔数组互转`() {
        // 位 i 对应 Calendar.DAY_OF_WEEK - 1，即 0=周日 1=周一 ... 6=周六
        val monWedFri = booleanArrayOf(false, true, false, true, false, true, false)
        assertEquals(0b0101010, RuleFormMapper.daysMaskOf(monWedFri))
        assertArrayEquals(monWedFri, RuleFormMapper.daysOf(0b0101010))

        val everyday = BooleanArray(7) { true }
        assertEquals(0b1111111, RuleFormMapper.daysMaskOf(everyday))
        assertArrayEquals(everyday, RuleFormMapper.daysOf(0b1111111))

        val none = BooleanArray(7) { false }
        assertEquals(0, RuleFormMapper.daysMaskOf(none))
        assertArrayEquals(none, RuleFormMapper.daysOf(0))
    }

    @Test
    fun `设备上真实规则的星期位图能正确还原`() {
        // 以下四条取自用户真机 1.3.6 备份（backups/device-pre-v138/automations.json）的原值，
        // 不是构造出来的，确保映射语义与线上数据一致。
        // id=2 飞书 18:00，repeatDays=60（0b0111100）→ 二三四五
        assertArrayEquals(
            booleanArrayOf(false, false, true, true, true, true, false),
            RuleFormMapper.daysOf(60)
        )
        // id=4 哔哩哔哩 09:00，repeatDays=124（0b1111100）→ 二三四五六
        assertArrayEquals(
            booleanArrayOf(false, false, true, true, true, true, true),
            RuleFormMapper.daysOf(124)
        )
        // id=5 哔哩哔哩 18:01，repeatDays=60 → 二三四五
        assertArrayEquals(
            booleanArrayOf(false, false, true, true, true, true, false),
            RuleFormMapper.daysOf(60)
        )
        // id=7 飞书 10:23，repeatDays=61（0b0111101）→ 日二三四五（含周日）
        assertArrayEquals(
            booleanArrayOf(true, false, true, true, true, true, false),
            RuleFormMapper.daysOf(61)
        )
        // 反向：位图转回来必须还是原值，否则编辑一次就把星期改掉了
        assertEquals(60, RuleFormMapper.daysMaskOf(RuleFormMapper.daysOf(60)))
        assertEquals(124, RuleFormMapper.daysMaskOf(RuleFormMapper.daysOf(124)))
        assertEquals(61, RuleFormMapper.daysMaskOf(RuleFormMapper.daysOf(61)))
    }

    @Test
    fun `位图往返对全部 128 种组合都闭合`() {
        for (mask in 0..127) {
            assertEquals("mask=$mask 往返不一致", mask, RuleFormMapper.daysMaskOf(RuleFormMapper.daysOf(mask)))
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 规则留档 JSON 编解码
    // ══════════════════════════════════════════════════════════════════

    /** 构造一条「每个字段都偏离默认值」的规则，任何字段漏编都会导致往返失败。 */
    private fun fullyPopulated(): Automation = Automation(
        id = 987654321L,
        name = "带全部字段的规则",
        targetPackage = "com.example.some.app",
        targetAppName = "示例应用",
        triggerType = TriggerType.BLUETOOTH,
        timeHour = 23,
        timeMinute = 47,
        repeatMode = RepeatMode.CUSTOM,
        repeatDays = 0b0101010,
        skipHolidays = true,
        bluetoothName = "WH-1000XM5",
        wifiName = "MyHomeWiFi",
        enabled = false,
        createdAt = 1_700_000_000_123L,
        randomWindow = true,
        windowStartMin = 510,
        windowEndMin = 530
    )

    @Test
    fun `留档往返后每个字段都原样保留`() {
        val original = fullyPopulated()
        val restored = AutomationCodec.decode(AutomationCodec.encode(original))

        assertNotNull("解码返回了 null", restored)
        assertEquals(original, restored)
        // 逐字段报错，便于定位漏编的字段
        assertEquals("id", original.id, restored!!.id)
        assertEquals("name", original.name, restored.name)
        assertEquals("targetPackage", original.targetPackage, restored.targetPackage)
        assertEquals("targetAppName", original.targetAppName, restored.targetAppName)
        assertEquals("triggerType", original.triggerType, restored.triggerType)
        assertEquals("timeHour", original.timeHour, restored.timeHour)
        assertEquals("timeMinute", original.timeMinute, restored.timeMinute)
        assertEquals("repeatMode", original.repeatMode, restored.repeatMode)
        assertEquals("repeatDays", original.repeatDays, restored.repeatDays)
        assertEquals("skipHolidays", original.skipHolidays, restored.skipHolidays)
        assertEquals("bluetoothName", original.bluetoothName, restored.bluetoothName)
        assertEquals("wifiName", original.wifiName, restored.wifiName)
        assertEquals("enabled", original.enabled, restored.enabled)
        assertEquals("createdAt", original.createdAt, restored.createdAt)
        assertEquals("randomWindow", original.randomWindow, restored.randomWindow)
        assertEquals("windowStartMin", original.windowStartMin, restored.windowStartMin)
        assertEquals("windowEndMin", original.windowEndMin, restored.windowEndMin)
    }

    @Test
    fun `留档 key 集合与实体字段集合完全一致`() {
        // 将来给 Automation 加字段却忘了改编解码，这里会立刻失败
        val declared = Automation::class.java.declaredFields
            .map { it.name }
            .filterNot { it.startsWith("$") }
            .toSet()
        val encoded = AutomationCodec.encode(fullyPopulated())
        val keys = org.json.JSONObject(encoded).keys().asSequence().toSet()

        val missing = declared - keys
        val extra = keys - declared
        assertTrue("以下字段没有写进留档，编辑后会丢失: $missing", missing.isEmpty())
        assertTrue("留档里有实体不存在的字段: $extra", extra.isEmpty())
    }

    @Test
    fun `默认值规则往返后依然等于自身`() {
        val plain = Automation(name = "默认", targetPackage = "a.b.c", targetAppName = "A", triggerType = TriggerType.TIME)
        assertEquals(plain, AutomationCodec.decode(AutomationCodec.encode(plain)))
    }

    @Test
    fun `留档内容损坏时返回 null 而不是抛异常`() {
        // 留档读不出来只能降级为「无法撤销」，绝不能因此让编辑流程崩掉
        assertNull(AutomationCodec.decode(""))
        assertNull(AutomationCodec.decode("这不是 JSON"))
        assertNull(AutomationCodec.decode("{不完整的"))
        assertNull(AutomationCodec.decode("null"))
        // 空对象缺少必填字段，同样应安全返回 null
        assertNull(AutomationCodec.decode("{}"))
    }

    @Test
    fun `含特殊字符的字段名与引号能安全往返`() {
        val tricky = fullyPopulated().copy(
            name = "带 \"引号\" 和 \\ 反斜杠",
            wifiName = "WiFi\n含换行",
            targetAppName = "emoji 🚀 与中文"
        )
        assertEquals(tricky, AutomationCodec.decode(AutomationCodec.encode(tricky)))
    }

    // ══════════════════════════════════════════════════════════════════
    // 与既有排程逻辑的联动：编辑后落点应遵守新配置
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `编辑出的规则能直接被排程器接受`() {
        // 从设备备份还原一条真实规则，改个时间，确认映射出的配置是排程器认识的字面量
        val fromDevice = Automation(
            id = 2L, name = "飞书", targetPackage = "com.ss.android.lark",
            targetAppName = "飞书", triggerType = TriggerType.TIME,
            timeHour = 18, timeMinute = 0, repeatMode = RepeatMode.CUSTOM,
            repeatDays = 0b0111100, enabled = true
        )
        val edited = fromDevice.copy(
            timeHour = 7, timeMinute = 30,
            repeatMode = RuleFormMapper.repeatKeyOf(1)   // 改成「工作日」
        )
        assertEquals("weekdays", edited.repeatMode)
        assertEquals(7, edited.timeHour)
        assertEquals(30, edited.timeMinute)
        // 下标回去必须还是 1，否则用户再次打开编辑页会被显示成别的选项
        assertEquals(1, RuleFormMapper.repeatIndexOf(edited.repeatMode))

        val encoded = AutomationCodec.encode(edited)
        val back = AutomationCodec.decode(encoded)
        assertEquals(edited, back)
    }

    @Test
    fun `位图 bit 与 Calendar 星期常量对齐`() {
        // daysOf 的下标语义必须与 Scheduler/表单一致：0=周日
        val sunday = RuleFormMapper.daysOf(1 shl Calendar.SUNDAY - 1)
        assertTrue("bit0 应当是周日", sunday[0])
        assertFalse(sunday[1])
        val saturday = RuleFormMapper.daysOf(1 shl Calendar.SATURDAY - 1)
        assertTrue("bit6 应当是周六", saturday[6])
    }
}
