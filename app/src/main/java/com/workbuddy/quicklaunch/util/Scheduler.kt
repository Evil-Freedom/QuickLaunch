package com.workbuddy.quicklaunch.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.workbuddy.quicklaunch.data.AppDatabase
import com.workbuddy.quicklaunch.data.Automation
import com.workbuddy.quicklaunch.data.TriggerType
import com.workbuddy.quicklaunch.receiver.AlarmReceiver
import java.util.Calendar

/**
 * 负责把「定时」类自动化注册到系统的 AlarmManager。
 * 重复任务采用「到点触发后重新排程下一次」的方式，保证精确且可靠。
 */
object Scheduler {

    /**
     * 逐日推进的最大天数上限。正常场景最多跨 7 天（custom 位图）或十来天（长假），
     * 这里给到两年是纯粹的安全阀：一旦节假日数据异常（例如整表都是休息日）
     * 或 repeatDays 位图损坏，原实现会在 while 里无限打转，
     * 直接把调用线程（含主线程 / BOOT_COMPLETED 广播线程）挂死并耗光 CPU。
     */
    private const val MAX_DAY_ADVANCE = 366 * 2

    /** 共享 Random，避免每次排程都 new 一个（并减少同一毫秒内种子相同的概率）。 */
    private val random = java.util.Random()

    @JvmOverloads
    fun schedule(context: Context, a: Automation, holidays: HolidayChecker? = null) {
        if (a.triggerType != TriggerType.TIME) return
        // 任何一步异常都不应让「保存规则 / 开机重排」整体失败
        runCatching {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pi = pendingIntent(context, a)
            val triggerAt = nextTriggerTime(a, skipPredicate(context, a, holidays))

            val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                runCatching { am.canScheduleExactAlarms() }.getOrDefault(false)
            if (exactAllowed) {
                // setExactAndAllowWhileIdle 在权限被回收的瞬间可能抛 SecurityException，兜底降级
                runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi) }
                    .onFailure { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi) }
            } else {
                // 未授予精确闹钟权限：退化为不精确唤醒（仍会触发，时间略有偏差）
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        }
    }

    /**
     * 构造「是否应跳过该天」的判定：仅在开启 skipHolidays 时查本地节假日表，否则恒不跳过。
     * [preloaded] 由批量场景（[rescheduleAll]）传入，避免每条规则都全表扫描一次 holidays（N+1）。
     */
    private fun skipPredicate(
        context: Context,
        a: Automation,
        preloaded: HolidayChecker?
    ): (Calendar) -> Boolean {
        if (!a.skipHolidays) return { false }
        val checker = preloaded ?: HolidayChecker.fromDb(context)
        if (checker.isEmpty()) return { false }   // 没有节假日数据时跳过 dateKey 字符串开销
        return { cal -> checker.isHoliday(cal) }
    }

    fun cancel(context: Context, a: Automation) {
        runCatching {
            val pi = pendingIntent(context, a)
            (context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)?.cancel(pi)
        }
    }

    /**
     * 重新排程所有已启用的「定时」自动化，使最新的节假日/星期设置立即生效。
     * 节假日集合只加载一次并复用，规则再多也只查一次库。
     */
    fun rescheduleAll(context: Context) {
        val list = runCatching {
            AppDatabase.get(context).automationDao().getEnabledByType(TriggerType.TIME)
        }.getOrDefault(emptyList())
        if (list.isEmpty()) return
        // 只要有一条开了跳过节假日，就加载一次；都没开则完全不查库
        val holidays = if (list.any { it.skipHolidays }) HolidayChecker.fromDb(context)
        else HolidayChecker.EMPTY
        list.forEach { schedule(context, it, holidays) }
    }

    private fun pendingIntent(context: Context, a: Automation): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("automation_id", a.id)
        }
        // requestCode 用 id 保证每条规则对应独立的 PendingIntent
        return PendingIntent.getBroadcast(
            context,
            a.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 计算下一次触发时间（毫秒）。
     * - 普通定时：用 timeHour/timeMinute。
     * - 随机窗口（randomWindow=true）：在 [windowStartMin, windowEndMin] 当天分钟区间里随机取一个时刻，
     *   重复任务每次重排都重新随机，实现「每天不固定的触发时刻」。
     */
    /** 公开入口：不感知节假日（供测试与兼容使用），等价于 shouldSkip 恒为 false。 */
    fun nextTriggerTime(a: Automation): Long = nextTriggerTime(a) { false }

    /**
     * 计算下一次触发时间（毫秒）。
     * @param shouldSkip 返回 true 表示该天应被跳过（如命中法定节假日）。仅对重复模式生效，
     *                   一次性(once)不受节假日影响。
     */
    internal fun nextTriggerTime(a: Automation, shouldSkip: (Calendar) -> Boolean): Long {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()

        if (a.randomWindow) {
            // 窗口分钟数来自持久化数据，可能因旧版本/手工改库越界，先夹紧到合法区间
            val start = a.windowStartMin.coerceIn(0, 1439)
            val rawEnd = a.windowEndMin.coerceIn(0, 1439)
            val end = if (rawEnd > start) rawEnd else (start + 1).coerceAtMost(1439)

            cal.set(Calendar.HOUR_OF_DAY, start / 60)
            cal.set(Calendar.MINUTE, start % 60)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            // 先落到本周期内第一个有效日，再逐日推进保证始终是未来且符合 repeatMode / 跳过规则
            advanceToValidDay(cal, a)
            advanceUntilValid(cal, a, now, shouldSkip)

            val span = (end - start).coerceAtLeast(0)
            val picked = start + random.nextInt(span + 1)
            cal.set(Calendar.HOUR_OF_DAY, picked / 60)
            cal.set(Calendar.MINUTE, picked % 60)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            // 随机取到的时刻可能落在“现在”之前（今天窗口已过大半），推到下一个有效日
            if (cal.timeInMillis <= now) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
                advanceToValidDay(cal, a)
                advanceUntilValid(cal, a, now, shouldSkip)
            }
            return cal.timeInMillis
        }

        // 普通固定时刻（同样夹紧，防止越界数据把 Calendar 推到意外的日期）
        cal.set(Calendar.HOUR_OF_DAY, a.timeHour.coerceIn(0, 23))
        cal.set(Calendar.MINUTE, a.timeMinute.coerceIn(0, 59))
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        if (a.repeatMode == "once") {
            if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
            return cal.timeInMillis
        }

        // 重复任务：先今天对齐到有效日（以防今天就是无效日但时刻还没到），
        // 再逐日推进保证是未来且每个候选日都满足 repeatMode 约束与跳过规则
        advanceToValidDay(cal, a)
        advanceUntilValid(cal, a, now, shouldSkip)
        return cal.timeInMillis
    }

    /**
     * 逐日推进到「未来 且 不被跳过」的一天，**带硬上限**。
     *
     * 上限用尽说明输入数据病态（例如节假日表把未来两年全标成休息日），
     * 此时回退到「第一个未来的有效日、忽略 shouldSkip」——
     * 宁可多触发一次，也绝不允许线程无限空转、更不能把闹钟排到两年后等于永不触发。
     */
    private fun advanceUntilValid(
        cal: Calendar,
        a: Automation,
        now: Long,
        shouldSkip: (Calendar) -> Boolean
    ) {
        // 阶段一：先落到未来的有效日（不考虑跳过规则），正常最多 8 天内完成
        var i = 0
        while (cal.timeInMillis <= now && i++ < MAX_DAY_ADVANCE) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
            advanceToValidDay(cal, a)
        }
        val firstFuture = cal.timeInMillis

        // 阶段二：再跳过命中跳过规则的日子，带硬上限
        var guard = 0
        while (safeSkip(shouldSkip, cal)) {
            if (guard++ >= MAX_DAY_ADVANCE) {
                cal.timeInMillis = firstFuture
                return
            }
            cal.add(Calendar.DAY_OF_YEAR, 1)
            advanceToValidDay(cal, a)
        }
    }

    /** shouldSkip 由外部注入，抛异常时按「不跳过」处理，避免排程链路整体崩溃。 */
    private fun safeSkip(shouldSkip: (Calendar) -> Boolean, cal: Calendar): Boolean =
        runCatching { shouldSkip(cal) }.getOrDefault(false)

    /**
     * 把日期推进到满足 repeatMode 约束的下一天（含当天）。
     * daily/once 无约束；weekdays/weekend 按周末过滤；custom 按 repeatDays 位图过滤。
     * 每个分支的推进都限定在 7 天内 —— 一周内必然能找到满足条件的日子，
     * 找不到就说明数据非法（如位图落在 1..7 之外），此时直接放行而不是死循环。
     */
    private fun advanceToValidDay(cal: Calendar, a: Automation) {
        when (a.repeatMode) {
            "weekdays" -> {
                var i = 0
                while (isWeekend(cal) && i++ < 7) cal.add(Calendar.DAY_OF_YEAR, 1)
            }
            "weekend" -> {
                var i = 0
                while (!isWeekend(cal) && i++ < 7) cal.add(Calendar.DAY_OF_YEAR, 1)
            }
            "custom" -> {
                // 只保留 bit0..bit6（周日~周六）；清洗后为 0 视为数据异常，退化为「任意一天」
                val mask = a.repeatDays and 0x7F
                if (mask != 0) {
                    var i = 0
                    while (!isSelectedDay(cal, mask) && i++ < 7) cal.add(Calendar.DAY_OF_YEAR, 1)
                }
            }
        }
    }

    private fun isWeekend(cal: Calendar): Boolean {
        val d = cal.get(Calendar.DAY_OF_WEEK)
        return d == Calendar.SATURDAY || d == Calendar.SUNDAY
    }

    /** repeatDays 位图：bit (Calendar.DAY_OF_WEEK - 1) 表示选中。 */
    private fun isSelectedDay(cal: Calendar, mask: Int): Boolean {
        val bit = cal.get(Calendar.DAY_OF_WEEK) - 1
        return (mask shr bit) and 1 == 1
    }
}
