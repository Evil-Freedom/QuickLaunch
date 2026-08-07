package com.workbuddy.quicklaunch.util

import android.os.Looper
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Root 检测与命令执行。SukiSU / KernelSU 提供 `su` 二进制，
 * 用它执行需要 root 的命令（如 settings put 防息屏）。
 *
 * 健壮性要点（此前的实现有三个致命缺陷，均已修复）：
 * 1. **必须排空 stdout + stderr**：只读一路时另一路的管道缓冲区（通常 4~64KB）写满后
 *    子进程会永久阻塞在 write()，父进程 waitFor() 随之永久挂起。
 * 2. **必须有超时**：su 授权弹窗未确认 / 管理器未运行时，waitFor() 会无限等待，
 *    把调用线程（单线程 IO 池）彻底堵死，后续所有 root 操作与 UI 回调全部饿死。
 * 3. **必须 destroy()**：异常或超时路径不销毁进程会造成僵尸进程与 fd 泄漏，
 *    长时间运行后触发 "Too many open files"。
 *
 * 另外：exec 是阻塞的重操作，禁止在主线程调用；[runAsRootAsync] 提供后台执行入口。
 */
object RootUtils {

    private const val DEFAULT_TIMEOUT_MS = 8_000L

    /** 后台执行 root 命令的单线程池（守护线程，不阻止进程退出）。 */
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "root-exec").apply { isDaemon = true }
    }

    /**
     * 已确认存在 root 时缓存 true，避免每次都 fork 一个 su 进程。
     * 只缓存正例：没 root 的情况不缓存，用户后续授权/安装管理器后仍可被检测到。
     */
    @Volatile
    private var rootConfirmed = false

    /** 是否有可用 root：尝试 `su -c id` 看是否拿到 uid=0。阻塞调用，勿在主线程用。 */
    fun hasRoot(): Boolean {
        if (rootConfirmed) return true
        val r = exec(arrayOf("su", "-c", "id"), DEFAULT_TIMEOUT_MS)
        val ok = r != null && r.exitCode == 0 && r.stdout.contains("uid=0")
        if (ok) rootConfirmed = true
        return ok
    }

    /** 以 root 执行命令，返回是否成功（exit 0）。阻塞调用，勿在主线程用。 */
    fun runAsRoot(cmd: String): Boolean {
        val r = exec(arrayOf("su", "-c", cmd), DEFAULT_TIMEOUT_MS)
        return r != null && r.exitCode == 0
    }

    /**
     * 在后台线程执行 root 命令。主线程调用方（Service/Receiver/Activity）一律走这里，
     * 避免 su 授权弹窗期间 ANR。
     */
    fun runAsRootAsync(cmd: String, onDone: ((Boolean) -> Unit)? = null) {
        val task = Runnable {
            val ok = runCatching { runAsRoot(cmd) }.getOrDefault(false)
            onDone?.invoke(ok)
        }
        // 已在后台线程时直接执行，省一次调度；在主线程则必须切走。
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runCatching { executor.execute(task) }
        } else {
            task.run()
        }
    }

    private class ExecResult(val exitCode: Int, val stdout: String)

    /**
     * 执行外部命令：并发排空 stdout/stderr，带超时，无论成功失败都销毁进程与关闭 fd。
     * @return null 表示启动失败或超时。
     */
    private fun exec(cmd: Array<String>, timeoutMs: Long): ExecResult? {
        var process: Process? = null
        var outReader: Thread? = null
        var errReader: Thread? = null
        return try {
            val p = Runtime.getRuntime().exec(cmd)
            process = p
            // stdin 立刻关掉：su 读到 EOF 才不会傻等输入
            closeQuietly(p.outputStream)

            val out = StringBuilder()
            outReader = drain(p.inputStream, out)
            errReader = drain(p.errorStream, null)

            val code = waitFor(p, timeoutMs) ?: return null
            // 进程已退出，读线程会很快读到 EOF；给一个短上限防止极端情况下卡住
            outReader.join(500)
            errReader.join(500)
            ExecResult(code, synchronized(out) { out.toString() })
        } catch (_: Throwable) {
            // IOException（无 su）、SecurityException、OOM 等一律视为不可用
            null
        } finally {
            outReader?.interrupt()
            errReader?.interrupt()
            process?.let { p ->
                closeQuietly(p.inputStream)
                closeQuietly(p.errorStream)
                closeQuietly(p.outputStream)
                runCatching { p.destroy() }
            }
        }
    }

    /** 起一个守护线程持续读取流，[sink] 为 null 时只丢弃（仅为防止管道写满）。 */
    private fun drain(stream: java.io.InputStream, sink: StringBuilder?): Thread {
        val t = Thread {
            runCatching {
                stream.bufferedReader().use { br ->
                    val buf = CharArray(1024)
                    while (true) {
                        val n = br.read(buf)
                        if (n < 0) break
                        if (sink != null) synchronized(sink) { sink.appendRange(buf, 0, n) }
                    }
                }
            }
        }
        t.isDaemon = true
        t.name = "root-drain"
        t.start()
        return t
    }

    /**
     * 带超时的 waitFor。Process.waitFor(long, TimeUnit) 需要 API 26，
     * minSdk 24 下用 exitValue() 轮询实现，语义一致。
     * @return 退出码；超时或被中断返回 null。
     */
    private fun waitFor(p: Process, timeoutMs: Long): Int? {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (true) {
            try {
                return p.exitValue()
            } catch (_: IllegalThreadStateException) {
                // 仍在运行
            }
            if (System.nanoTime() >= deadline) return null
            try {
                Thread.sleep(30)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
    }

    private fun closeQuietly(c: Closeable?) {
        runCatching { c?.close() }
    }
}
