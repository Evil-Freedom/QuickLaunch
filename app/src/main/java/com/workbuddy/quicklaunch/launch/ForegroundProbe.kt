package com.workbuddy.quicklaunch.launch

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.PowerManager
import android.view.Display
import com.workbuddy.quicklaunch.util.RootUtils

/** 一次前台探测的完整快照，同时保留原始数据便于排查。 */
data class ProbeSnapshot(
    val verdict: ForegroundJudge.Verdict,
    val source: String,
    val topByDisplay: Map<Int, String> = emptyMap(),
    val litDisplays: Set<Int> = emptySet(),
    val at: Long = System.currentTimeMillis(),
    val note: String = ""
)

/**
 * 前台探测的统一入口。**阻塞调用**，必须在后台线程执行。
 *
 * 生产实现有三层（按可靠性排序），[CompositeProbe] 负责串起来：
 * 1. [RootProbe]      —— `dumpsys activity activities`，唯一能区分 display 的数据源（450ms/次）
 * 2. [UsageStatsProbe] —— 官方 API，需要用户授予「使用情况访问权限」，有 1~2s 延迟且不区分屏幕
 * 3. 都没有时返回 [ForegroundJudge.Verdict.UNKNOWN]
 *
 * 三层都不可用时**不判失败**：见 [ForegroundJudge.Verdict.UNKNOWN] 的说明。
 */
interface ForegroundProbe {
    fun probe(targetPkg: String): ProbeSnapshot
    /** 该实现当前是否真的能给出结论，供界面展示「验证能力」状态。 */
    fun available(): Boolean
}

/**
 * Root 探针：`su -c dumpsys activity activities`。
 *
 * ## 为什么这是首选
 *
 * 2026-11 在目标机实测：450ms/次，返回 `Display #N` 分段 + 每段 `topResumedActivity`
 * 与 `mLastPausedActivity` + `isSleeping`，是唯一能回答「目标 App 到底在哪块屏上、
 * 是否被锁屏挡住」的数据源。折叠屏与锁屏两个场景都依赖它。
 *
 * ## 失败熔断
 *
 * 真机观察到：锁屏状态下 `su` 可能不被 Magisk 放行，于是每次探测都要等满超时。
 * 一次任务里有 7~20 次探测，这会变成几十秒的空转。所以连续失败后进入
 * [ROOT_BACKOFF_MS] 的冷却期，期间直接走下一层，不再 fork su。
 *
 * ## 安全与健壮性约束
 *
 * - 命令是**常量字面量**，不拼接任何外部输入（连包名都不进命令）。
 * - [RootUtils.capture] 带超时，并同时排空 stdout/stderr，不会因 su 弹窗挂死线程。
 * - 退出码非 0 视为「读不到」，而不是「没有前台」。
 */
class RootProbe(
    private val litDisplayIds: () -> Set<Int> = { emptySet() },
    private val capture: (String) -> String? = { RootUtils.capture(CMD, ROOT_TIMEOUT_MS) }
) : ForegroundProbe {

    @Volatile
    private var backoffUntil = 0L

    private fun inBackoff(now: Long = System.currentTimeMillis()): Boolean = now < backoffUntil

    override fun available(): Boolean {
        if (inBackoff()) return false
        val ok = runCatching { RootUtils.hasRoot() }.getOrDefault(false)
        if (!ok) backoffUntil = System.currentTimeMillis() + ROOT_BACKOFF_MS
        return ok
    }

    override fun probe(targetPkg: String): ProbeSnapshot {
        if (inBackoff()) {
            return ProbeSnapshot(
                verdict = ForegroundJudge.Verdict.UNKNOWN,
                source = SOURCE,
                note = "root 冷却中（上次读取失败）"
            )
        }
        val dump = runCatching { capture(CMD) }.getOrNull()
        if (dump == null) {
            backoffUntil = System.currentTimeMillis() + ROOT_BACKOFF_MS
            return ProbeSnapshot(
                verdict = ForegroundJudge.Verdict.UNKNOWN,
                source = SOURCE,
                note = "root 读取失败或超时"
            )
        }
        val state = runCatching { DumpsysParser.parseState(dump) }
            .getOrDefault(DumpsysParser.DumpState())
        val lit = runCatching { litDisplayIds() }.getOrDefault(emptySet())
        val sleepingWith = runCatching { state.sleepingWith(targetPkg, lit) }.getOrDefault(false)
        val deviceSleeping = runCatching { state.deviceSleeping(lit) }.getOrDefault(false)
        return ProbeSnapshot(
            verdict = ForegroundJudge.judge(
                targetPkg = targetPkg,
                topByDisplay = state.topByDisplay,
                litDisplays = lit,
                deviceSleeping = deviceSleeping,
                sleepingWithTarget = sleepingWith
            ),
            source = SOURCE,
            topByDisplay = state.topByDisplay,
            litDisplays = lit,
            note = buildString {
                if (deviceSleeping) append("设备睡眠中; ")
                if (state.topByDisplay.isEmpty() && !state.anySleeping) append("未解析到 topResumedActivity; ")
                if (sleepingWith) append("目标已在睡眠屏上被启动; ")
            }.trim().trimEnd(';').trim()
        )
    }

    companion object {
        const val SOURCE = "ROOT_DUMPSYS"
        /** dumpsys 实测 450ms，给到 6s 是为了容忍 su 首次授权弹窗与高负载。 */
        const val ROOT_TIMEOUT_MS = 6_000L
        const val CMD = "dumpsys activity activities"

        /** 失败冷却：这段时间内不再 fork su，直接用下一层探针。 */
        const val ROOT_BACKOFF_MS = 120_000L
    }
}

/**
 * UsageStats 探针：官方 API，不需要 root。
 *
 * ## 已知局限（真机与文档均已确认）
 *
 * 1. 需要用户在系统设置里手动授予「使用情况访问权限」，否则 `queryEvents` 永远返回空；
 * 2. 事件入库有 **1~2 秒延迟**，探测立刻读很可能读不到刚发生的前台切换；
 * 3. **不区分 display** —— 只给「最近被 resume 的包」，折叠屏合盖时无法判断在哪块屏；
 * 4. **看不到锁屏**：锁屏时它仍会报告「最后前台是目标 App」，
 *    这在 §5.1 的锁屏场景里是**假阳性**（App 在 Keyguard 后面，用户看不到）。
 *    因此它的 VISIBLE 结论只在 Root 层不可用时采信，且 note 里标明来源。
 */
class UsageStatsProbe(private val context: Context) : ForegroundProbe {

    override fun available(): Boolean = runCatching {
        val usm = service() ?: return false
        // 没有权限时 queryEvents 返回空数组，用一次近 1 小时的查询判断
        val now = System.currentTimeMillis()
        usm.queryEvents(now - 3_600_000L, now).hasNextEvent()
    }.getOrDefault(false)

    override fun probe(targetPkg: String): ProbeSnapshot {
        val usm = runCatching { service() }.getOrNull()
            ?: return ProbeSnapshot(
                ForegroundJudge.Verdict.UNKNOWN, SOURCE, note = "UsageStats 服务不可用"
            )
        return runCatching {
            val now = System.currentTimeMillis()
            // 只看最近 60s：UsageStats 保留长期历史，扫全量既慢又会把昨天的记录当现在
            val events = usm.queryEvents(now - WINDOW_MS, now)
            val event = UsageEvents.Event()
            var lastForeground: String? = null
            var lastForegroundAt = 0L
            var leftAt = 0L
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED, UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                        if (event.timeStamp >= lastForegroundAt) {
                            lastForeground = event.packageName
                            lastForegroundAt = event.timeStamp
                        }
                    }
                    UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                        if (event.packageName == lastForeground) leftAt = event.timeStamp
                    }
                }
            }
            val visible = lastForeground == targetPkg && lastForegroundAt >= leftAt
            ProbeSnapshot(
                verdict = if (visible) ForegroundJudge.Verdict.VISIBLE else ForegroundJudge.Verdict.NOT_VISIBLE,
                source = SOURCE,
                topByDisplay = lastForeground?.let { mapOf(Display.DEFAULT_DISPLAY to it) } ?: emptyMap(),
                note = if (visible) "usage-stats 最近前台=$lastForeground（不区分屏幕、看不到锁屏）" else ""
            )
        }.getOrElse {
            ProbeSnapshot(ForegroundJudge.Verdict.UNKNOWN, SOURCE, note = "查询异常：${it.message}")
        }
    }

    private fun service(): UsageStatsManager? =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager

    companion object {
        const val SOURCE = "USAGE_STATS"
        private const val WINDOW_MS = 60_000L
    }
}

/**
 * 按可靠性串起来的探针：Root → UsageStats → 放弃。
 *
 * 一旦某个数据源给出**确定结论**（VISIBLE / NOT_VISIBLE / BLOCKED_BY_KEYGUARD）就停止；
 * UNKNOWN 才继续往下一层试。
 *
 * `available()` 的结果缓存 [AVAILABILITY_TTL_MS]：它内部要 fork su，
 * 而 `begin()` 在广播线程里同步调用它 —— 每次触发都等 8 秒授权超时是不可接受的。
 */
class CompositeProbe(
    private val probes: List<ForegroundProbe>,
    private val litDisplayIds: () -> Set<Int> = { emptySet() }
) : ForegroundProbe {

    @Volatile
    private var availableCachedAt = 0L

    @Volatile
    private var availableCached = false

    override fun available(): Boolean {
        val now = System.currentTimeMillis()
        if (now - availableCachedAt < AVAILABILITY_TTL_MS) return availableCached
        val result = probes.any { runCatching { it.available() }.getOrDefault(false) }
        availableCached = result
        availableCachedAt = now
        return result
    }

    override fun probe(targetPkg: String): ProbeSnapshot {
        var last: ProbeSnapshot? = null
        for (p in probes) {
            val snap = runCatching { p.probe(targetPkg) }.getOrElse {
                ProbeSnapshot(
                    ForegroundJudge.Verdict.UNKNOWN, "ERROR",
                    note = "${it.javaClass.simpleName}: ${it.message}"
                )
            }
            if (snap.verdict != ForegroundJudge.Verdict.UNKNOWN) return snap
            last = snap
        }
        return last ?: ProbeSnapshot(
            verdict = ForegroundJudge.Verdict.UNKNOWN,
            source = "NONE",
            note = "没有可用的前台探测数据源"
        )
    }

    companion object {
        /** 可用性缓存时长。锁屏/无 root 时避免每次触发都等一次 su 超时。 */
        const val AVAILABILITY_TTL_MS = 60_000L

        fun create(context: Context): CompositeProbe {
            val lit: () -> Set<Int> = { litDisplayIds(context) }
            return CompositeProbe(
                probes = listOf(
                    RootProbe(litDisplayIds = lit),
                    UsageStatsProbe(context.applicationContext)
                ),
                litDisplayIds = lit
            )
        }

        /**
         * 当前亮着的 display。
         *
         * 两级判据：
         * 1. `DisplayManager` 里 state == STATE_ON 的屏；
         * 2. 若一个都读不到但 `PowerManager.isInteractive()` 为真，退化为「主屏亮着」。
         *
         * 第 2 条是锁屏场景的兜底：真机锁屏亮屏时 `Display.state` 不一定报 ON，
         * 而 lit 为空会让 [ForegroundJudge] 走「任一屏命中即成功」的降级分支 ——
         * 那条分支在锁屏下会误判。先给出一个可信的默认值比让它为空更安全。
         *
         * 外屏在合盖态可能不被 getDisplays() 枚举，需补查 display 1。
         */
        fun litDisplayIds(context: Context): Set<Int> = runCatching {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val ids = HashSet<Int>()
            dm?.displays?.forEach { if (it.state == Display.STATE_ON) ids += it.displayId }
            dm?.getDisplay(EXTERNAL_DISPLAY_ID)?.let {
                if (it.state == Display.STATE_ON) ids += it.displayId
            }
            if (ids.isEmpty()) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                if (pm?.isInteractive == true) ids += Display.DEFAULT_DISPLAY
            }
            ids
        }.getOrDefault(emptySet())
    }
}

/** razr 外屏的固定 displayId。DisplayPicker 与探针共用同一个常量，避免两处写成不同的数。 */
internal const val EXTERNAL_DISPLAY_ID = 1
