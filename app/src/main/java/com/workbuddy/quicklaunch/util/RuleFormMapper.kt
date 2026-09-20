package com.workbuddy.quicklaunch.util

import com.workbuddy.quicklaunch.data.RepeatMode
import com.workbuddy.quicklaunch.data.TriggerType
import java.util.Calendar

/**
 * 表单「胶囊下标」与「数据层字符串枚举」之间的双向映射。
 *
 * 单独抽出来是因为它最容易被写错、且写错后不会报错：
 * 编辑页读回一条规则时要把 `repeatMode="weekdays"` 变成下标 1，把
 * `repeatDays` 位图变成 7 个胶囊的选中态；保存时再反向转回去。
 * 任何一处对不上，用户就会遇到「打开编辑页看到的是错的选项」或
 * 「改完保存后配置被悄悄改掉」这类静默故障。
 *
 * ⚠️ 字符串比较一律用 [equals] 的忽略大小写版本。
 * 历史教训：`TriggerType.TIME` 是全大写 `"TIME"`，而 `RepeatMode.WEEKDAYS`
 * 是全小写 `"weekdays"`，两套风格相反；早期手工插入的测试数据写成全大写后，
 * `when (a.repeatMode)` 匹配不到任何分支、静默不做过滤，排查了很久。
 */
object RuleFormMapper {

    // ── 触发条件（下标对应表单四宫格顺序）────────────────────────────
    fun triggerTypeOf(index: Int): String = when (index) {
        1 -> TriggerType.CHARGING
        2 -> TriggerType.WIFI
        3 -> TriggerType.BLUETOOTH
        else -> TriggerType.TIME
    }

    /** 反解下标。未知值回退到「定时」——编辑页宁可显示默认项，也不能崩。 */
    fun triggerIndexOf(type: String?): Int = when {
        type.equals(TriggerType.CHARGING, ignoreCase = true) -> 1
        type.equals(TriggerType.WIFI, ignoreCase = true) -> 2
        type.equals(TriggerType.BLUETOOTH, ignoreCase = true) -> 3
        else -> 0
    }

    // ── 重复模式（下标对应表单五个胶囊顺序）──────────────────────────
    fun repeatKeyOf(index: Int): String = when (index) {
        1 -> RepeatMode.WEEKDAYS
        2 -> RepeatMode.WEEKEND
        3 -> RepeatMode.CUSTOM
        4 -> RepeatMode.ONCE
        else -> RepeatMode.DAILY
    }

    /** 反解下标。未知值回退到「每天」——否则用户一进编辑页就会被重置成别的模式。 */
    fun repeatIndexOf(mode: String?): Int = when {
        mode.equals(RepeatMode.WEEKDAYS, ignoreCase = true) -> 1
        mode.equals(RepeatMode.WEEKEND, ignoreCase = true) -> 2
        mode.equals(RepeatMode.CUSTOM, ignoreCase = true) -> 3
        mode.equals(RepeatMode.ONCE, ignoreCase = true) -> 4
        else -> 0
    }

    // ── 自定义星期位图 ───────────────────────────────────────────────
    /**
     * 位图下标语义与 [Calendar.DAY_OF_WEEK] 对齐：bit 0 = 周日 … bit 6 = 周六。
     * 与 `Scheduler` 里 `(mask shr (DAY_OF_WEEK - 1)) and 1` 的读法必须一致。
     */
    fun daysMaskOf(days: BooleanArray): Int {
        var mask = 0
        for (i in 0..6) if (i < days.size && days[i]) mask = mask or (1 shl i)
        return mask
    }

    /** 位图 → 7 个布尔。超出 bit 0..6 的高位一律忽略。 */
    fun daysOf(mask: Int): BooleanArray =
        BooleanArray(7) { i -> (mask shr i) and 1 == 1 }
}
