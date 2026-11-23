package com.workbuddy.quicklaunch.launch

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.workbuddy.quicklaunch.service.LaunchFailureNotifier
import com.workbuddy.quicklaunch.util.AutoUnlock
import com.workbuddy.quicklaunch.util.VpsNotify
import com.workbuddy.quicklaunch.service.LaunchService
import java.util.UUID
import java.util.concurrent.Executors

/**
 * 启动任务的唯一执行者。
 *
 * ## 职责边界
 *
 * 它**不做任何策略判断**——什么时候重试、什么时候放弃全部由
 * [LaunchAttemptEngine] 决定。这里只做四件事：
 * 1. 按 [LaunchAttemptEngine.Decision] 执行（发启动命令 / 探测 / 定时回调）；
 * 2. 去重：同一个包不允许有两个任务并行推进；
 * 3. 持久化：把进行中的任务写盘，进程被杀后能判断「还该不该继续」；
 * 4. 通知：终态时按配置发失败通知并提供手动重试入口。
 *
 * ## 线程模型
 *
 * 状态机只在主线程（[handler]）推进。探测是阻塞调用，放在**独立的单线程池**
 * 执行 —— 不能复用 `QuickLaunchExecutors.io`：它同时承担数据库读写，
 * 一次慢查询就会把验证窗口拖到过期，表现为「探测永远慢一拍」。
 *
 * ## 与既有链路的关系
 *
 * 升级前的路径是 `AlarmReceiver → LaunchService → LaunchProxyActivity`。
 * 现在起点换成 [begin]，但它仍然通过 `LaunchService.start(...)` 发出命令，
 * 也就是说**绕后台启动限制的那套机制一行没改**，只是外面多了一层「事后验证」。
 */
object LaunchAttemptController {

    private const val TAG = "QL-LaunchAttempt"
    private const val DISPATCH_CALLBACK_TIMEOUT_MS = 1_500L

    private val handler = Handler(Looper.getMainLooper())

    /** 探测专用单线程池：串行化 su 调用，避免同时 fork 多个 dumpsys。 */
    private val probeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ql-launch-probe").apply { isDaemon = true }
    }

    /** 进行中的任务：key 为目标包名。同一个包同时只允许一个任务。 */
    private val inFlight = HashMap<String, Running>()

    @Volatile
    private var probe: ForegroundProbe? = null

    private class Running(val engine: LaunchAttemptEngine) {
        var cancelled = false
    }

    // ── 对外入口 ────────────────────────────────────────────────────

    /**
     * 发起一次「启动并验证」的任务。
     *
     * @param manual 用户手动触发（点通知、界面按钮）。仅用于日志与记录，
     *        不影响状态机行为 —— 手动和自动走同一套重试纪律，避免两条路径行为不一致。
     */
    fun begin(
        context: Context,
        ruleId: Long,
        pkg: String,
        appName: String,
        manual: Boolean = false
    ) {
        if (pkg.isBlank()) return
        val app = context.applicationContext
        val displayName = appName.ifBlank { pkg }

        // 去重：同一个包已有在跑的任务时直接忽略本次触发。
        // 这是「多条规则指向同一个 App」「重试期间原任务又被 Alarm 触发」的统一解法。
        val existing = inFlight[pkg]
        if (existing != null && !existing.engine.state.isTerminal) {
            Log.i(
                TAG,
                "忽略重复触发 pkg=$pkg 已有任务 ${existing.engine.attemptId} ${existing.engine.summary()}"
            )
            return
        }

        val now = System.currentTimeMillis()
        val policy = LaunchSettings.loadPolicy(app)

        // 注意：这里**不能**同步判断「有没有前台探测能力」。
        // 那个判断内部要 fork `su`，真机实测一次触发到任务创建之间因此出现了
        // 36 秒空档（广播线程被 su 授权等待占住）。判定统一挪到 [preProbe]
        // 的探测线程里做，让广播处理立即返回。
        val attemptId = UUID.randomUUID().toString()
        val engine = LaunchAttemptEngine(
            attemptId = attemptId,
            ruleId = ruleId,
            pkg = pkg,
            appName = displayName,
            policy = policy,
            startedAt = now
        )
        val runner = Running(engine)
        inFlight[pkg] = runner
        saveActive(app, engine, "PENDING")

        Log.i(TAG, "新建任务 $attemptId pkg=$pkg manual=$manual ${engine.summary()}")

        // 启动前先探一次：目标已经在前台时不再重复拉起
        // （需求 §2.4「用户手动启动与自动启动同时发生」）。
        preProbe(app, runner)
    }

    /** 规则被取消/删除时清理它关联的任务。 */
    fun cancelByRule(context: Context, ruleId: Long) {
        val app = context.applicationContext
        inFlight.values.toList()
            .filter { it.engine.ruleId == ruleId && !it.engine.state.isTerminal }
            .forEach { runner ->
                runner.cancelled = true
                Log.i(TAG, "规则 $ruleId 取消任务 ${runner.engine.attemptId}")
                handle(app, runner, runner.engine.cancel(System.currentTimeMillis()))
            }
    }

    /** 用户手动停止某个包的自动启动。 */
    fun cancelByPackage(context: Context, pkg: String) {
        val app = context.applicationContext
        inFlight[pkg]?.takeIf { !it.engine.state.isTerminal }?.let { runner ->
            runner.cancelled = true
            handle(app, runner, runner.engine.cancel(System.currentTimeMillis()))
        }
    }

    /** 进程重启 / 开机后调用：判断遗留任务还该不该继续。 */
    fun recover(context: Context) {
        val app = context.applicationContext
        val active = LaunchSettings.loadActive(app) ?: return
        val policy = LaunchSettings.loadPolicy(app)
        val age = System.currentTimeMillis() - active.startedAt

        // 刚创建的任务不是「遗留任务」。
        // 真机复现：begin() 刚写完 active 快照的 100ms 内，系统的
        // MY_PACKAGE_REPLACED 广播唤起了进程 → BootReceiver 调到这里 →
        // 误把这个进行中的任务当遗留任务清掉，导致它失去「进程被杀后可恢复」的能力。
        if (age < FRESH_TASK_WINDOW_MS && hasActiveTask()) {
            Log.i(TAG, "跳过恢复：${active.attemptId} 是 ${age}ms 前刚开始的任务")
            return
        }

        LaunchSettings.clearActive(app)

        if (age > policy.totalTimeoutMs) {
            // 需求 §2.4-6「进程重启后不能无限重复旧任务」：过期就结案，不重新发起。
            LaunchSettings.record(
                app,
                LaunchSettings.Record(
                    attemptId = active.attemptId,
                    ruleId = active.ruleId,
                    pkg = active.pkg,
                    appName = active.appName,
                    state = LaunchAttemptState.FAILED.name,
                    reason = LaunchAttemptEngine.REASON_KILLED,
                    startedAt = active.startedAt,
                    finishedAt = System.currentTimeMillis(),
                    tries = active.attemptIndex,
                    detail = "进程在任务中途退出，已超过总时限（${age}ms），不再自动重试"
                )
            )
            Log.w(TAG, "遗留任务已过期，结案：${active.attemptId}")
            if (policy.notifyOnFailure) {
                LaunchFailureNotifier.notifyFailure(
                    app, active.appName, active.pkg, LaunchAttemptEngine.REASON_KILLED,
                    active.attemptIndex, policy.allowManualRetry
                )
            }
            return
        }

        // 还来得及：重新走一次完整流程（会先探测是否已在前台，避免重复拉起）
        Log.i(TAG, "恢复遗留任务 ${active.attemptId}（${age}ms 前）")
        begin(app, active.ruleId, active.pkg, active.appName)
    }

    /**
     * 由 [com.workbuddy.quicklaunch.LaunchProxyActivity] 在真正发出启动命令后回调。
     *
     * @param accepted startActivity 是否被系统接受。**这不决定成败**，
     *        只记录到 AttemptRecord；判定仍靠后续探测。
     */
    fun onProxyLaunched(context: Context, attemptId: String?, accepted: Boolean) {
        if (attemptId.isNullOrBlank()) return   // 旧调用路径（如用户点通知直接启动），不参与验证
        val app = context.applicationContext
        val runner = inFlight.values.firstOrNull { it.engine.attemptId == attemptId } ?: return
        handle(app, runner, runner.engine.onLaunchDispatched(System.currentTimeMillis(), accepted))
    }

    /** 供界面展示：当前有没有任务在跑。 */
    fun hasActiveTask(): Boolean = inFlight.values.any { !it.engine.state.isTerminal }

    fun activeSummary(): String =
        inFlight.values.filter { !it.engine.state.isTerminal }
            .joinToString("; ") { "${it.engine.pkg}:${it.engine.summary()}" }

    // ── 内部：驱动 ──────────────────────────────────────────────────

    private fun preProbe(app: Context, runner: Running) {
        val engine = runner.engine
        probeExecutor.execute {
            // 能力判定放这里（探测线程），避免阻塞广播线程
            if (!runCatching { resolveProbe(app).available() }.getOrDefault(false)) {
                handler.post { launchUnverified(app, runner) }
                return@execute
            }
            val snap = runCatching { resolveProbe(app).probe(engine.pkg) }.getOrNull()
            val foreground = snap?.verdict == ForegroundJudge.Verdict.VISIBLE
            val blocked = snap?.verdict == ForegroundJudge.Verdict.BLOCKED_BY_KEYGUARD
            handler.post {
                val now = System.currentTimeMillis()
                if (blocked) {
                    // 启动前就发现「设备锁定且目标已在锁屏后」：
                    // 记一次尝试后直接按锁屏挡住收手，不再点亮屏幕重试
                    engine.start(now, alreadyForeground = false)
                    engine.onLaunchDispatched(now, dispatchAccepted = true)
                    handle(
                        app, runner,
                        engine.onBlockedByKeyguard(now, snap?.source, requestKeyguardRescue(app))
                    )
                } else {
                    handle(app, runner, engine.start(now, foreground))
                }
            }
        }
    }

    /**
     * 无前台探测能力时的降级：照常发一次启动命令，并如实记一条「未验证」。
     *
     * 为什么不假装成功、也不假装失败：无 root 且未授予「使用情况访问权限」的设备
     * 永远读不到前台信息，把这种情况判失败会让重试变成纯粹的骚扰；
     * 而假装成功又违背「不能把命令发出当成 App 已打开」的前提。
     * 唯一诚实的做法是照常启动 + 在记录里写明「本次未验证」。
     */
    private fun launchUnverified(app: Context, runner: Running) {
        val engine = runner.engine
        inFlight.remove(engine.pkg)
        LaunchSettings.clearActive(app)
        val now = System.currentTimeMillis()
        Log.i(TAG, "无前台探测能力，退化为单次启动 pkg=${engine.pkg}")
        LaunchService.start(app, engine.pkg, engine.appName, null)
        LaunchSettings.record(
            app,
            LaunchSettings.Record(
                attemptId = "unverified-$now",
                ruleId = engine.ruleId,
                pkg = engine.pkg,
                appName = engine.appName,
                state = LaunchAttemptState.SUCCESS.name,
                reason = LaunchAttemptEngine.REASON_NO_PROBE,
                startedAt = engine.startedAt,
                finishedAt = now,
                tries = 1,
                detail = "无前台探测能力（无 root 且未授予使用情况访问权限），仅确认命令已发出"
            )
        )
    }

    private fun handle(app: Context, runner: Running, decision: LaunchAttemptEngine.Decision) {
        if (runner.cancelled && decision !is LaunchAttemptEngine.Decision.Finished) return
        when (decision) {
            is LaunchAttemptEngine.Decision.Idle -> Unit
            is LaunchAttemptEngine.Decision.DispatchLaunch -> dispatch(app, runner)
            is LaunchAttemptEngine.Decision.ProbeNow -> launchProbe(app, runner)
            is LaunchAttemptEngine.Decision.WaitUntil -> scheduleTick(app, runner, decision.at)
            is LaunchAttemptEngine.Decision.Finished -> finish(app, runner, decision)
        }
    }

    private fun dispatch(app: Context, runner: Running) {
        val engine = runner.engine
        saveActive(app, engine, "DISPATCHING")
        Log.i(TAG, "第 ${engine.attemptIndex} 次发出启动 pkg=${engine.pkg}")

        // 通过 LaunchService 发出命令：绕后台启动限制的逻辑
        // （FGS + 悬浮窗豁免 + 透明中转页）保持不变，只是多带一个 attemptId 用于回传。
        LaunchService.start(app, engine.pkg, engine.appName, engine.attemptId)

        // 兜底：LaunchService 可能因为「后台启动前台服务被拒」而走通知兜底，
        // 此时不会有 onProxyLaunched 回调。超时后仍未收到就自己补一条，
        // 否则任务会永远停在 PENDING。
        handler.postDelayed({
            if (engine.state == LaunchAttemptState.PENDING) {
                Log.w(TAG, "未收到中转页回调，按已派发处理 pkg=${engine.pkg}")
                handle(app, runner, engine.onLaunchDispatched(System.currentTimeMillis(), dispatchAccepted = true))
            }
        }, DISPATCH_CALLBACK_TIMEOUT_MS)
    }

    private fun launchProbe(app: Context, runner: Running) {
        val engine = runner.engine
        probeExecutor.execute {
            val snap = runCatching { resolveProbe(app).probe(engine.pkg) }.getOrElse {
                ProbeSnapshot(
                    verdict = ForegroundJudge.Verdict.UNKNOWN,
                    source = "ERROR",
                    note = it.message.orEmpty()
                )
            }
            handler.post {
                val now = System.currentTimeMillis()
                Log.i(
                    TAG,
                    "探测 pkg=${engine.pkg} verdict=${snap.verdict} src=${snap.source} " +
                        "top=${snap.topByDisplay} lit=${snap.litDisplays}" +
                        (if (snap.note.isNotBlank()) " note=${snap.note}" else "")
                )
                // 三态分流：被锁屏挡住时立即收手，不按普通失败重试
                handle(
                    app, runner,
                    when (snap.verdict) {
                        ForegroundJudge.Verdict.VISIBLE ->
                            engine.onProbeResult(now, hit = true, source = snap.source)
                        ForegroundJudge.Verdict.BLOCKED_BY_KEYGUARD ->
                            engine.onBlockedByKeyguard(now, snap.source, requestKeyguardRescue(app))
                        else -> engine.onProbeResult(now, hit = false, source = snap.source)
                    }
                )
            }
        }
    }

    private fun scheduleTick(app: Context, runner: Running, at: Long) {
        val delay = (at - System.currentTimeMillis()).coerceAtLeast(0)
        handler.postDelayed({
            if (runner.engine.state.isTerminal) return@postDelayed
            handle(app, runner, runner.engine.onTick(System.currentTimeMillis()))
        }, delay)
    }

    private fun finish(app: Context, runner: Running, decision: LaunchAttemptEngine.Decision.Finished) {
        val engine = runner.engine
        inFlight.remove(engine.pkg)
        LaunchSettings.clearActive(app)
        LaunchSettings.record(
            app,
            LaunchSettings.Record(
                attemptId = engine.attemptId,
                ruleId = engine.ruleId,
                pkg = engine.pkg,
                appName = engine.appName,
                state = decision.state.name,
                reason = decision.reason,
                startedAt = engine.startedAt,
                finishedAt = engine.finishedAt,
                tries = engine.attemptIndex.coerceAtLeast(1),
                detail = engine.attempts.joinToString(" | ") {
                    "#${it.index}@${it.launchedAt}→${it.outcome}" +
                        (it.probeSource?.let { s -> "($s)" } ?: "")
                }
            )
        )
        Log.i(TAG, "任务结束 ${engine.attemptId} ${engine.summary()}")

        val shouldNotify = engine.policy.notifyOnFailure &&
            (decision.state == LaunchAttemptState.MANUAL_REQUIRED ||
                decision.state == LaunchAttemptState.FAILED)
        if (shouldNotify) {
            LaunchFailureNotifier.notifyFailure(
                app, engine.appName, engine.pkg, decision.reason, engine.attemptIndex,
                engine.policy.allowManualRetry
            )
        }

        // 旁路上报邮件通知：纯附加动作，内部单线程 + 4s 超时 + 吞异常，
        // 不参与判定、不改变状态、失败只记一行 log。
        runCatching {
            VpsNotify.onLaunchFinished(
                ctx = app,
                pkg = engine.pkg,
                appName = engine.appName,
                success = decision.state == LaunchAttemptState.SUCCESS,
                reason = decision.reason,
                tries = engine.attemptIndex.coerceAtLeast(1),
                displayId = null,
                startedAt = engine.startedAt,
                finishedAt = engine.finishedAt
            )
        }
    }

    private fun saveActive(app: Context, engine: LaunchAttemptEngine, state: String) {
        LaunchSettings.saveActive(
            app,
            LaunchSettings.ActiveAttempt(
                attemptId = engine.attemptId,
                ruleId = engine.ruleId,
                pkg = engine.pkg,
                appName = engine.appName,
                startedAt = engine.startedAt,
                attemptIndex = engine.attemptIndex,
                lastState = state
            )
        )
    }

    /** 判定「刚创建」的时间窗，见 [recover]。 */
    private const val FRESH_TASK_WINDOW_MS = 5_000L

    /**
     * 自动解锁是否真的会把锁屏打开；会的话顺手唤起一次观察窗口。
     *
     * 唤起是必要的：`SCREEN_ON` 广播可能早于本任务（例如闹钟触发时屏幕刚亮），
     * 那时 AutoUnlock 的 12 秒观察窗口可能已经走完，干等不会有人来开门。
     *
     * 任一条件不满足都返回 false —— 判定链回到「立即收手」，
     * 不因为一个不可用的解锁能力而让任务空等并反复点亮屏幕。
     */
    private fun requestKeyguardRescue(app: Context): Boolean = runCatching {
        val can = AutoUnlock.isEnabled(app) &&
            AutoUnlock.hasPin(app) &&
            !AutoUnlock.isSecurityLocked(app)
        if (can) AutoUnlock.watch(app)
        can
    }.getOrDefault(false)

    private fun resolveProbe(app: Context): ForegroundProbe =
        probe ?: CompositeProbe.create(app).also { probe = it }

    /** 供单测/诊断替换探测实现。 */
    internal fun setProbeForTest(p: ForegroundProbe?) {
        probe = p
    }

    /** 进程内重置（测试用）。 */
    internal fun resetForTest() {
        handler.removeCallbacksAndMessages(null)
        inFlight.clear()
        probe = null
    }
}
