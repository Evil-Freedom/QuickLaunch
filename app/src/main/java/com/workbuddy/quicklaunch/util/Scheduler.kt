package com.workbuddy.quicklaunch.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.workbuddy.quicklaunch.BuildConfig
import com.workbuddy.quicklaunch.data.AppDatabase
import com.workbuddy.quicklaunch.data.Automation
import com.workbuddy.quicklaunch.data.DailyShift
import com.workbuddy.quicklaunch.data.RepeatMode
import com.workbuddy.quicklaunch.data.ShiftAnchor
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

    /**
     * 班表模式的逐日扫描上限。
     *
     * 取 60 天而不是 366：公司排班通常只发布未来几天到几周，扫到 60 天还没有可用
     * 班次就说明班表已经过期，此时**退化为固定时刻**比继续往后扫更有用 ——
     * 扫到明年再触发，等于规则静默失效。
     */
    private const val MAX_SHIFT_DAY_SCAN = 60

    /** 共享 Random，避免每次排程都 new 一个（并减少同一毫秒内种子相同的概率）。 */
    private val random = java.util.Random()

    /**
     * 「恒不跳过」的共享谓词。
     * 复用同一个实例而不是每次 lambda，是因为 [skipPredicate] / [exemptPredicate]
     * 会在每条规则排程时被调用一次，规则多时能省掉一堆短命闭包。
     */
    private val NEVER: (Calendar) -> Boolean = { false }

    /**
     * 读取某条规则的单日豁免日期集合（yyyy-MM-dd）。
     * 读库失败一律返回空集：豁免只是「少启动一次」，读不出来顶多照常启动，
     * 绝不能让排程链路因为一张附加表而整体失败。
     */
    fun loadExceptionDates(context: Context, ruleId: Long): Set<String> = runCatching {
        AppDatabase.get(context).ruleExceptionDao().getDatesByRule(ruleId).toSet()
    }.getOrDefault(emptySet())

    @JvmOverloads
    fun schedule(
        context: Context,
        a: Automation,
        holidays: HolidayChecker? = null,
        exceptions: Set<String>? = null,
        shifts: ShiftTable? = null
    ) {
        if (a.triggerType != TriggerType.TIME) return
        // 任何一步异常都不应让「保存规则 / 开机重排」整体失败
        runCatching {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pi = pendingIntent(context, a)
            // null 表示调用方没预加载（单条排程场景），此时才去查一次库；
            // 批量场景（rescheduleAll）传入预加载结果，避免 N+1。
            val exempt = exceptions ?: loadExceptionDates(context, a.id)
            // 班表模式必须拿到班表才能算，这里兜底查库（只读 date >= 今天，量很小）
            val shiftsOrNull = shifts ?: if (a.shiftAnchor != ShiftAnchor.FIXED) {
                ShiftTable.fromDb(context, HolidayChecker.dateKey(Calendar.getInstance()))
            } else {
                null
            }
            val triggerAt = nextTriggerTime(
                a,
                shouldSkip = skipPredicate(context, a, holidays, exempt),
                forceRun = forceRunPredicate(context, holidays, exempt),
                exceptions = exempt,
                shifts = shiftsOrNull
            )

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
     * 构造「该天是否被用户单日豁免」的判定。
     * 豁免是一次性的、用户显式表达「这一天不要启动」，因此优先级最高 ——
     * 它必须能压过调休上班日的强制放行，否则「请假当天照常启动」就成了功能缺陷。
     */
    private fun exemptPredicate(exceptions: Set<String>): (Calendar) -> Boolean {
        if (exceptions.isEmpty()) return NEVER
        return { cal -> exceptions.contains(HolidayChecker.dateKey(cal)) }
    }

    /**
     * 「是否应跳过该天」的**纯逻辑**实现（不查库，全部依赖注入）。
     *
     * 抽成独立函数是为了让单测直接验证生产代码，而不是在测试里另写一份等价判定 ——
     * 后者只能证明「测试里的逻辑对」，证明不了线上跑的那份对。
     *
     * 判定由两部分取或：
     * 1. 单日豁免 —— **不受 skipHolidays 开关影响**，用户明确点名了这一天就跳过；
     * 2. 法定休息日 —— 仅在开启 skipHolidays 且本地有节假日数据时生效。
     */
    internal fun buildSkipPredicate(
        a: Automation,
        checker: HolidayChecker,
        exceptions: Set<String>
    ): (Calendar) -> Boolean {
        val exempt = exemptPredicate(exceptions)
        // 没勾「跳过节假日」时仍要保留豁免判定，所以不能像旧实现那样直接 return { false }
        if (!a.skipHolidays || checker.isEmpty()) return exempt
        return { cal -> exempt(cal) || checker.isHoliday(cal) }
    }

    /**
     * 「该天是否强制触发」的**纯逻辑**实现：命中调休上班日时返回 true，无视星期规则与跳过规则。
     *
     * 这是「哪怕没勾选跳过节假日，遇到调休也自动运行」的实现点：
     * 调休上班日必然落在周末，而周末常被 repeatMode=WEEKDAYS 排除、也可能被 skipHolidays 跳过。
     * 只要节假日数据里标了 isWorkday，就把这天当作正常工作日强制放行。
     *
     * ⚠️ 用户单日豁免（请假）必须压过调休：命中豁免时这里返回 false，
     * 使该日退化为「普通日」再被 skipPredicate 判掉。
     * 这样做的另一个好处是 [advanceToValidDay] 内部对 forceRun 的早退分支
     * 不需要任何改动就自动尊重豁免 —— 它本来就会在安全阀里调这个谓词。
     */
    internal fun buildForceRunPredicate(
        checker: HolidayChecker,
        exceptions: Set<String>
    ): (Calendar) -> Boolean {
        if (checker.isEmpty()) return NEVER   // 没有节假日数据时跳过 dateKey 字符串开销
        val exempt = exemptPredicate(exceptions)
        return { cal -> !exempt(cal) && checker.isMakeupWorkday(cal) }
    }

    /**
     * 批量场景（[rescheduleAll]）会传入预加载的 [preloaded]，
     * 避免每条规则都全表扫描一次 holidays（N+1）。
     */
    private fun skipPredicate(
        context: Context,
        a: Automation,
        preloaded: HolidayChecker?,
        exceptions: Set<String>
    ): (Calendar) -> Boolean {
        // 只有需要查库时才建 checker：没勾跳过且无豁免时直接走纯逻辑短路
        if (!a.skipHolidays && exceptions.isEmpty()) return NEVER
        return buildSkipPredicate(a, preloaded ?: HolidayChecker.fromDb(context), exceptions)
    }

    private fun forceRunPredicate(
        context: Context,
        preloaded: HolidayChecker?,
        exceptions: Set<String>
    ): (Calendar) -> Boolean {
        val checker = preloaded ?: HolidayChecker.fromDb(context)
        return buildForceRunPredicate(checker, exceptions)
    }

    fun cancel(context: Context, a: Automation) {
        runCatching {
            val pi = pendingIntent(context, a)
            (context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)?.cancel(pi)
        }
    }

    /**
     * 重新排程所有已启用的「定时」自动化，使最新的节假日/星期设置立即生效。
     * 节假日集合与单日豁免都只加载一次并复用，规则再多也只查两次库。
     *
     * 必须无条件加载节假日：调休自动运行不依赖 skipHolidays 开关，
     * 所以哪怕所有规则都没勾选「跳过节假日」，也要读表拿调休上班日。
     */
    fun rescheduleAll(context: Context) {
        val db = runCatching { AppDatabase.get(context) }.getOrNull() ?: return
        val list = runCatching {
            db.automationDao().getEnabledByType(TriggerType.TIME)
        }.getOrDefault(emptyList())
        if (list.isEmpty()) return

        // 低频顺手清理：规则被删除后遗留的豁免。失败无所谓，下次还会清。
        runCatching { db.ruleExceptionDao().deleteOrphans() }

        val holidays = HolidayChecker.fromDb(context)
        val exemptByRule = runCatching {
            // 一次全表读入再分组，避免「每条规则查一次」的 N+1
            db.ruleExceptionDao().getAll()
                .groupBy({ it.ruleId }, { it.date })
                .mapValues { (_, dates) -> dates.toSet() }
        }.getOrDefault(emptyMap())

        // 只有存在班表规则时才读班表：多数场景全是固定时刻规则，白读一次没意义。
        // 读出来的是「今天起」的子集，量很小；历史班表对下次触发没有意义。
        val shifts = if (list.any { it.shiftAnchor != ShiftAnchor.FIXED }) {
            ShiftTable.fromDb(context, HolidayChecker.dateKey(Calendar.getInstance()))
        } else {
            null
        }

        list.forEach { schedule(context, it, holidays, exemptByRule[it.id] ?: emptySet(), shifts) }
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
    /** 公开入口：不感知节假日与豁免（供测试与兼容使用），等价于 shouldSkip / forceRun 恒为 false。 */
    fun nextTriggerTime(
        a: Automation,
        exceptions: Set<String> = emptySet(),
        shifts: ShiftTable? = null
    ): Long = nextTriggerTime(
        a, shouldSkip = { false }, forceRun = { false }, exceptions = exceptions, shifts = shifts
    )

    /**
     * 只算「下次触发时刻」，不真正排闹钟 —— 界面展示专用。
     *
     * 存在的理由：界面上的「下次触发」必须与 AlarmManager 里的真实落点一致。
     * 若界面另写一套算法（只按固定时刻取最早），就会出现「卡片写着今天 12:19、
     * 实际因为请假被推到明天」这种自相矛盾的状态。这里复用 [schedule] 用的同一组谓词，
     * 从根上避免第二份实现漂移。
     *
     * @param holidays 预加载的节假日表。传 null 时**不查库**（只保留豁免判定）——
     *                 本函数会被界面在主线程调用，绝不能在这里触发 Room 的主线程查询。
     * @param shifts   预加载的班表。同样不能在主线程查库：班表模式下调用方必须传，
     *                 传 null 时该规则会退化为固定时刻显示（仅影响展示，不影响真实排程）。
     */
    fun peekNextTriggerAt(
        a: Automation,
        holidays: HolidayChecker?,
        exceptions: Set<String>,
        shifts: ShiftTable? = null
    ): Long {
        val shouldSkip = if (holidays != null) {
            buildSkipPredicate(a, holidays, exceptions)
        } else {
            exemptPredicate(exceptions)
        }
        val forceRun = if (holidays != null) buildForceRunPredicate(holidays, exceptions) else NEVER
        return nextTriggerTime(a, shouldSkip, forceRun, exceptions, shifts)
    }

    /**
     * 与 [nextTriggerTime] 同逻辑，但可指定「现在」的起点时刻。
     *
     * 供测试模拟「重复任务一天天重排」的过程：把起点设在调休日当天，
     * 即可直接验证当天是否被放行，不必空转几百次等它自然走到。
     */
    internal fun nextTriggerTimeFrom(
        a: Automation,
        startFrom: Calendar,
        shouldSkip: (Calendar) -> Boolean,
        forceRun: (Calendar) -> Boolean = { false },
        exceptions: Set<String> = emptySet(),
        shifts: ShiftTable? = null
    ): Long = nextTriggerTimeInternal(
        a, startFrom.timeInMillis, shouldSkip, forceRun, exceptions, shifts
    )

    /**
     * 计算下一次触发时间（毫秒）。
     * @param shouldSkip 返回 true 表示该天应被跳过（如命中法定节假日、或用户单日豁免）。
     *                   仅对重复模式生效，一次性(once)只看 [exceptions]。
     * @param forceRun  返回 true 表示该天是调休上班日，不被星期规则过滤、也不被跳过规则吃掉。
     *                  同样仅对重复模式生效，once 保持原有语义。
     * @param exceptions 该规则的单日豁免日期集合（yyyy-MM-dd）。一次性任务命中豁免时顺延，
     *                   保证「这一天不启动」对 once 与重复规则表现一致。
     */
    internal fun nextTriggerTime(
        a: Automation,
        shouldSkip: (Calendar) -> Boolean,
        forceRun: (Calendar) -> Boolean = { false },
        exceptions: Set<String> = emptySet(),
        shifts: ShiftTable? = null
    ): Long = nextTriggerTimeInternal(
        a, System.currentTimeMillis(), shouldSkip, forceRun, exceptions, shifts
    )

    private fun nextTriggerTimeInternal(
        a: Automation,
        now: Long,
        shouldSkip: (Calendar) -> Boolean,
        forceRun: (Calendar) -> Boolean,
        exceptions: Set<String> = emptySet(),
        shifts: ShiftTable? = null
    ): Long {
        // ── 班表模式分流 ───────────────────────────────────────────────
        // 只有「规则锚定班次」且「确实拿到了班表」才走新路径。
        // 班表为空（还没同步过）时**故意**落回下面的固定时刻逻辑 ——
        // 否则用户刚打开开关、班表还没下发，规则就会变成永不触发，
        // 而界面上又写着「已启用」，这是最难排查的一类故障。
        if (a.shiftAnchor != ShiftAnchor.FIXED && shifts != null && !shifts.isEmpty()) {
            nextTriggerTimeByShift(a, now, exceptions, shifts)?.let { return it }
            // 扫描窗口内没有任何可用班次（公司只排到今天且时刻已过）：
            // 退化为固定时刻，宁可多触发一次也不要静默失效。
            if (BuildConfig.DEBUG) {
                Log.w("Scheduler", "班表模式无可用班次，退化为固定时刻 id=${a.id}")
            }
        }

        // 起点日必须由 now 决定，不能用 Calendar.getInstance()（那永远是真实当前日期）。
        // 生产调用传的就是 System.currentTimeMillis()，两者等价；
        // 但 nextTriggerTimeFrom 需要把「现在」设成任意时刻，此时起点日必须跟着变，
        // 否则起点日仍是今天，测试结果会全部落到「今天之后的第一个调休日」而看不出差异。
        val cal = Calendar.getInstance().apply { timeInMillis = now }

        if (a.randomWindow) {
            // 窗口分钟数来自持久化数据，可能因旧版本/手工改库越界，先夹紧到合法区间
            val start = a.windowStartMin.coerceIn(0, 1439)
            val rawEnd = a.windowEndMin.coerceIn(0, 1439)
            val end = if (rawEnd > start) rawEnd else (start + 1).coerceAtMost(1439)

            cal.set(Calendar.HOUR_OF_DAY, start / 60)
            cal.set(Calendar.MINUTE, start % 60)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            // 先落到本周期内第一个有效日，再逐日推进保证始终是未来且符合 repeatMode / 跳过规则。
            // 传入 forceRun，使推进过程中遇到调休上班日立即停住，不被星期规则越过
            advanceToValidDay(cal, a, forceRun)
            advanceUntilValid(cal, a, now, shouldSkip, forceRun)

            val span = (end - start).coerceAtLeast(0)
            val picked = start + random.nextInt(span + 1)
            cal.set(Calendar.HOUR_OF_DAY, picked / 60)
            cal.set(Calendar.MINUTE, picked % 60)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            // 随机取到的时刻可能落在“现在”之前（今天窗口已过大半），推到下一个有效日
            if (cal.timeInMillis <= now) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
                advanceToValidDay(cal, a, forceRun)
                advanceUntilValid(cal, a, now, shouldSkip, forceRun)
            }
            return cal.timeInMillis
        }

        // 普通固定时刻（同样夹紧，防止越界数据把 Calendar 推到意外的日期）
        cal.set(Calendar.HOUR_OF_DAY, a.timeHour.coerceIn(0, 23))
        cal.set(Calendar.MINUTE, a.timeMinute.coerceIn(0, 59))
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        if (a.repeatMode == RepeatMode.ONCE) {
            if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
            // 一次性任务同样尊重「该日豁免」：用户请假那天不该被一次性任务叫醒。
            // 这里只顺延、不看节假日 —— once 不受 skipHolidays 影响的既有语义保持不变。
            val exempt = exemptPredicate(exceptions)
            var guard = 0
            while (safeSkip(exempt, cal) && guard++ < MAX_DAY_ADVANCE) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
            return cal.timeInMillis
        }

        // 重复任务：先今天对齐到有效日（以防今天就是无效日但时刻还没到），
        // 再逐日推进保证是未来且每个候选日都满足 repeatMode 约束与跳过规则
        advanceToValidDay(cal, a, forceRun)
        advanceUntilValid(cal, a, now, shouldSkip, forceRun)
        return cal.timeInMillis
    }

    /**
     * 班表模式的触发时刻：逐日扫描，取第一个「有班次 + 未被豁免 + 时刻未过」的日子。
     *
     * ## 为什么这里完全不看 repeatMode / skipHolidays / 调休
     *
     * 班表本身就是「这天到底上不上班、几点上班」的事实。再叠加星期位图会直接算错：
     * 轮班的人周六要上 05:00 的班，而 weekdays 规则恰好把周末排除掉；
     * 再叠加法定节假日也会算错：他在春节值班那天，国务院的通知说那天放假。
     * 所以班表模式只认两个输入：**班表** 和 **用户显式请假（豁免）**。
     * 豁免仍然生效 —— 「这天我自己不想启动」是用户意图，不是排班事实。
     *
     * @return null 表示扫描窗口内没有可用班次，调用方退化为固定时刻
     */
    private fun nextTriggerTimeByShift(
        a: Automation,
        now: Long,
        exceptions: Set<String>,
        shifts: ShiftTable
    ): Long? {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        var guard = 0
        while (guard++ < MAX_SHIFT_DAY_SCAN) {
            val key = HolidayChecker.dateKey(cal)
            val shift = shifts.of(key)
            if (shift != null && !exceptions.contains(key)) {
                val at = shiftTriggerAt(a, cal, shift)
                // 今天这一班的点可能已经过了（9 点上班、现在 13 点），
                // 那就继续看明天 —— 绝不能返回过去的时间点，那会让闹钟立刻响。
                if (at > now) return at
            }
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return null
    }

    /**
     * 把「某天的班次 + 锚点 + 偏移」换算成绝对时刻。
     *
     * 关键在跨天班：05:00 上班、次日 05:00 下班存成 inMin=300 / outMin=1740，
     * 锚定下班时要落在**次日** 05:00。所以先把「当天分钟数」按 1440 拆成
     * 「第几天 + 当天第几分钟」，再一起加回日期 —— 若直接 set(HOUR_OF_DAY, 1740/60)，
     * Calendar 会归一化成当天 05:00，白丢一整天。
     */
    private fun shiftTriggerAt(a: Automation, day: Calendar, shift: DailyShift): Long {
        val base = if (a.shiftAnchor == ShiftAnchor.CLOCK_OUT) shift.outMin else shift.inMin
        var target = base + a.shiftOffsetMin
        var dayOffset = 0
        while (target < 0) {
            target += 1440
            dayOffset -= 1
        }
        while (target >= 1440) {
            target -= 1440
            dayOffset += 1
        }
        return (day.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, dayOffset)
            set(Calendar.HOUR_OF_DAY, target / 60)
            set(Calendar.MINUTE, target % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    /**
     * 逐日推进到「未来 且 不被跳过」的一天，**带硬上限**。
     *
     * 关键顺序：每天先问 [forceRun]（调休上班日），命中就直接认下这一天，
     * 不再走 advanceToValidDay 的星期过滤 ——
     * 调休上班日必然落在周末，而周末正是 repeatMode=WEEKDAYS 要排除的对象，
     * 若先做星期过滤就会把它推走，永远选不到。
     *
     * 上限用尽说明输入数据病态（例如节假日表把未来两年全标成休息日），
     * 此时回退到「第一个未来的有效日、忽略 shouldSkip」——
     * 宁可多触发一次，也绝不允许线程无限空转、更不能把闹钟排到两年后等于永不触发。
     */
    private fun advanceUntilValid(
        cal: Calendar,
        a: Automation,
        now: Long,
        shouldSkip: (Calendar) -> Boolean,
        forceRun: (Calendar) -> Boolean
    ) {
        // 阶段一：落到「未来」的候选日。
        // 关键：每次只 +1 天，然后才做星期对齐 —— 不能反过来「先对齐再判是否未来」，
        // 否则对齐步骤会一次跨过多天，把中间的调休上班日（必然是周末）整个越过去。
        var i = 0
        while (cal.timeInMillis <= now && i++ < MAX_DAY_ADVANCE) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
            advanceToValidDay(cal, a, forceRun)
        }
        val firstFuture = cal.timeInMillis

        // 阶段二：跳过命中跳过规则的日子，带硬上限。调休日优先于跳过规则
        var guard = 0
        while (safeSkip(shouldSkip, cal) && !safeForceRun(forceRun, cal)) {
            if (guard++ >= MAX_DAY_ADVANCE) {
                cal.timeInMillis = firstFuture
                return
            }
            cal.add(Calendar.DAY_OF_YEAR, 1)
            advanceToValidDay(cal, a, forceRun)
        }
    }

    /** shouldSkip 由外部注入，抛异常时按「不跳过」处理，避免排程链路整体崩溃。 */
    private fun safeSkip(shouldSkip: (Calendar) -> Boolean, cal: Calendar): Boolean =
        runCatching { shouldSkip(cal) }.getOrDefault(false)

    /** forceRun 由外部注入，抛异常时按「非调休日」处理，退化为原有排程行为。 */
    private fun safeForceRun(forceRun: (Calendar) -> Boolean, cal: Calendar): Boolean =
        runCatching { forceRun(cal) }.getOrDefault(false)

    /**
     * 把日期推进到满足 repeatMode 约束的下一天（含当天）。
     * daily/once 无约束；weekdays/weekend 按周末过滤；custom 按 repeatDays 位图过滤。
     * 每个分支的推进都限定在 7 天内 —— 一周内必然能找到满足条件的日子，
     * 找不到就说明数据非法（如位图落在 1..7 之外），此时直接放行而不是死循环。
     *
     * 推进过程中一旦落在调休上班日就立即停止，不再继续按星期/位图过滤。
     * 只靠调用方在进函数前判一次是不够的：本函数的 while 循环会一次跨过多天，
     * 可能把中间的调休周六直接越过（weekdays 规则：周六→周日→周一），
     * 之后再也没有机会把它挑回来。
     */
    private fun advanceToValidDay(cal: Calendar, a: Automation, forceRun: (Calendar) -> Boolean = { false }) {
        when (a.repeatMode) {
            RepeatMode.WEEKDAYS -> {
                var i = 0
                while (isWeekend(cal) && i++ < 7) {
                    if (safeForceRun(forceRun, cal)) return
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                }
            }
            RepeatMode.WEEKEND -> {
                var i = 0
                while (!isWeekend(cal) && i++ < 7) {
                    if (safeForceRun(forceRun, cal)) return
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                }
            }
            RepeatMode.CUSTOM -> {
                // 只保留 bit0..bit6（周日~周六）；清洗后为 0 视为数据异常，退化为「任意一天」
                val mask = a.repeatDays and 0x7F
                if (mask != 0) {
                    var i = 0
                    while (!isSelectedDay(cal, mask) && i++ < 7) {
                        if (safeForceRun(forceRun, cal)) return
                        cal.add(Calendar.DAY_OF_YEAR, 1)
                    }
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
