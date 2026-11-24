package com.workbuddy.quicklaunch.util

import android.content.Context
import android.util.Log

/**
 * 飞书进程生命周期（只做查询与 Force Stop，不碰数据）。
 *
 * 安全边界，写死不要放宽：
 * * 包名是常量，**不接受任何外部传入**（VPS 只能下发动作类型，包名由这里决定）；
 * * 只用 `am force-stop --user 0`，禁止 pkill / pm clear / 卸载 / 禁停权限；
 * * `am force-stop` 返回 0 不等于成功，必须再验证进程是否真的退出；
 * * 验证不到的情况返回 UNKNOWN（ok=false + detail 说明），绝不伪报成功。
 */
object FeishuProcessManager {

    const val PKG = "com.ss.android.lark"
    private const val TAG = "QL-FeishuProc"

    data class Report(
        val attempted: Boolean,
        val ok: Boolean,
        val exitCode: Int,
        val elapsedMs: Long,
        val remaining: List<String>,
        val detail: String
    )

    /** 当前飞书相关进程（含子进程/沙箱子进程）。 */
    fun processes(): List<String> {
        val out = RootUtils.capture("ps -A -o NAME | grep -F '$PKG' || true") ?: return emptyList()
        return out.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    }

    fun isRunning(): Boolean = processes().isNotEmpty()

    /**
     * 执行 Force Stop 并验证。
     *
     * @param waitMs 命令返回后等待进程退出的最长时间（force-stop 是异步的，
     *               真机上 pid 不会在命令返回的瞬间消失）。
     */
    fun forceStop(waitMs: Long = 3_000): Report {
        val before = processes()
        val startedAt = System.currentTimeMillis()
        val exit = rootExit("am force-stop --user 0 $PKG")
        val deadline = System.currentTimeMillis() + waitMs
        var remaining = processes()
        while (remaining.isNotEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(150)
            remaining = processes()
        }
        val elapsed = System.currentTimeMillis() - startedAt
        val ok = exit == 0 && remaining.isEmpty()
        val detail = when {
            exit != 0 -> "am force-stop 退出码 $exit（Root 不可用或被拒）"
            remaining.isNotEmpty() -> "命令成功但仍有进程：${remaining.joinToString()}"
            before.isEmpty() -> "执行前就没有进程，重复清理视为成功"
            else -> "已退出（清理前 ${before.size} 个进程）"
        }
        Log.i(TAG, "forceStop exit=$exit ok=$ok 剩余=${remaining.size} 耗时=${elapsed}ms")
        return Report(true, ok, exit, elapsed, remaining, detail)
    }

    /** 启动前清理：仅当存在进程时才动手，避免每次启动都白白 su 一次。 */
    fun cleanupIfRunning(): Report? {
        if (!isRunning()) return null
        return forceStop()
    }

    private fun rootExit(cmd: String): Int {
        val out = RootUtils.capture("$cmd; echo QL_EXIT=\$?") ?: return -1
        return out.lineSequence()
            .firstOrNull { it.startsWith("QL_EXIT=") }
            ?.removePrefix("QL_EXIT=")?.trim()?.toIntOrNull() ?: -1
    }
}
