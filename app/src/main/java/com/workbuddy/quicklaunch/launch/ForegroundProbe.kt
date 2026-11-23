package com.workbuddy.quicklaunch.launch

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.hardware.display.DisplayManager
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
 * 2026-11 在目标机实测：450ms/次，返回 `Display #N` 分段 + 每段 `topResumedActivity`，
 * 是唯一能回答「目标 App 到底在哪块屏上」的数据源。折叠屏场景下这一点是必需的。
 *
 * ## 安全与健壮性约束
 *
 * - 命令是**常量字面量**，不拼接任何外部输入（连包名都不进命令）。
 * - [RootUtils.capture] 带 6s 超时，并同时排空 stdout/stderr，不会因 su 弹窗挂死线程。
 * - 退出码非 0 视为「读不到」，而不是「没有前台」。
 */
class RootProbe(
    private val litDisplayIds: () -> Set<Int> = { emptySet() },
    private val capture: (String) -> String? = { RootUtils.capture(CMD, ROOT_TIMEOUT_MS) }
) : ForegroundProbe {

    override fun available(): Boolean = runCatching { RootUtils.hasRoot() }.getOrDefault(false)

    override fun probe(targetPkg: String): ProbeSnapshot {
        val dump = runCatching { capture(CMD) }.getOrNull()
            ?: return ProbeSnapshot(
                verdict = ForegroundJudge.Verdict.UNKNOWN,
                source = SOURCE,
                note = "root 读取失败或超时"
            )
        val top = runCatching { DumpsysParser.parseTopByDisplay(dump) }.getOrDefault(emptyMap())
        val lit = runCatching { litDisplayIds() }.getOrDefault(emptySet())
        return ProbeSnapshot(
            verdict = ForegroundJudge.judge(targetPkg, top, lit),
            source = SOURCE,
            topByDisplay = top,
            litDisplays = lit,
            note = if (top.isEmpty()) "dump 中未解析到 topResumedActivity" else ""
        )
    }

    companion object {
        const val SOURCE = "ROOT_DUMPSYS"
        /** dumpsys 实测 450ms，给到 6s 是为了容忍 su 首次授权弹窗与高负载。 */
        const val ROOT_TIMEOUT_MS = 6_000L
        const val CMD = "dumpsys activity activities"
    }
}

/**
 * UsageStats 探针：官方 API，不需要 root。
 *
 * ## 已知局限（真机与文档均已确认）
 *
 * 1. 需要用户在系统设置里手动授予「使用情况访问权限」，否则 `queryEvents` 永远返回空；
 * 2. 事件入库有 **1~2 秒延迟**，探测立刻读很可能读不到刚发生的前台切换；
 * 3. **不区分 display** —— 只给「最近被 resume 的包」，折叠屏合盖时无法判断在哪块屏。
 *
 * 因此它只作为 Root 不可用时的降级，且判定为 VISIBLE 时把 confidence 写在 note 里。
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
                note = if (visible) "usage-stats 最近前台=${lastForeground}（不区分屏幕）" else ""
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
 * 一旦某个数据源给出**确定结论**（VISIBLE / NOT_VISIBLE）就停止；
 * UNKNOWN 才继续往下一层试。这样既有 root 又开了使用情况权限时不至于多跑一次。
 */
class CompositeProbe(
    private val probes: List<ForegroundProbe>,
    private val litDisplayIds: () -> Set<Int> = { emptySet() }
) : ForegroundProbe {

    override fun available(): Boolean = probes.any { runCatching { it.available() }.getOrDefault(false) }

    override fun probe(targetPkg: String): ProbeSnapshot {
        var last: ProbeSnapshot? = null
        for (p in probes) {
            val snap = runCatching { p.probe(targetPkg) }.getOrElse {
                ProbeSnapshot(ForegroundJudge.Verdict.UNKNOWN, "ERROR", note = "${it.javaClass.simpleName}: ${it.message}")
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
        /**
         * 生产装配。Root 优先；`Build.VERSION` 判断不需要 —— UsageStatsManager
         * 从 API 21 开始就有，minSdk 24 覆盖。
         */
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

        /** 当前亮着的 display。外屏在合盖态可能不被 getDisplays() 枚举，需补查 display 1。 */
        fun litDisplayIds(context: Context): Set<Int> = runCatching {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
                ?: return@runCatching emptySet<Int>()
            val ids = HashSet<Int>()
            dm.displays?.forEach { if (it.state == Display.STATE_ON) ids += it.displayId }
            // razr 外屏是 display 1 且可能不出现在枚举结果里
            dm.getDisplay(EXTERNAL_DISPLAY_ID)?.let {
                if (it.state == Display.STATE_ON) ids += it.displayId
            }
            ids
        }.getOrDefault(emptySet())
    }
}

/** razr 外屏的固定 displayId。与 [com.workbuddy.quicklaunch.util.DisplayPicker] 的假设一致。 */
private const val EXTERNAL_DISPLAY_ID = 1
