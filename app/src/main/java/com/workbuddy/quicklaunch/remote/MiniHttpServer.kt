package com.workbuddy.quicklaunch.remote

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 极小的 HTTP/1.1 服务端：只做本功能需要的子集。
 *
 * ## 为什么不用现成框架
 *
 * - **NanoHTTPD**：BSD-3、成熟，但 Maven 上最新只有 `2.3.1`（2016-08-12 发布，定版近十年），
 *   且本项目 CI 无代理配置、引入新依赖会破坏构建可复现性。
 * - **Ktor / AndServer**：前者给 APK 增加 1.5~2.5 MB 并引入协程模型；
 *   后者是 MVC 注解框架，为 6 个 JSON 端点用它属于过度工程。
 *
 * 本类只支持：请求行 + 头 + **固定长度 body**。明确不支持（直接拒绝）：
 * chunked 编码、multipart、文件上传、keep-alive、HTTP/2。
 * 需要的解析面越小，可被畸形报文攻击的面就越小。
 *
 * ## 硬性上限（每一条都对应一次真实的耗尽风险）
 *
 * | 限制 | 值 | 防的是 |
 * |---|---|---|
 * | 请求行长度 | 8 KB | 超长 URL 打满内存 |
 * | 头部行数 | 64 | 头部洪泛 |
 * | body 长度 | 8 KB | 大报文占用线程 |
 * | 单连接读超时 | 10 s | 慢速连接（Slowloris）占死线程 |
 * | 并发线程 | 4（队列不排队） | 连接风暴 |
 */
class MiniHttpServer(
    private val requestedPort: Int,
    private val handler: (Request) -> Response
) {

    data class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: ByteArray,
        val remoteAddress: String
    ) {
        /** 头名大小写不敏感（HTTP 规范要求），统一转小写查。 */
        fun header(name: String): String? = headers[name.lowercase(Locale.US)]

        fun queryLong(key: String): Long? = query[key]?.toLongOrNull()
        fun queryInt(key: String): Int? = query[key]?.toIntOrNull()
    }

    data class Response(
        val status: Int,
        val body: ByteArray,
        val contentType: String = "application/json; charset=utf-8"
    ) {
        companion object {
            fun json(status: Int, text: String) =
                Response(status, text.toByteArray(Charsets.UTF_8))

            fun text(status: Int, text: String) =
                Response(status, text.toByteArray(Charsets.UTF_8), "text/plain; charset=utf-8")
        }
    }

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var running = false

    private var executor: ThreadPoolExecutor? = null
    private var acceptThread: Thread? = null

    /** 实际绑定到的端口；未启动或启动失败时为 -1。端口冲突时会自动后移。 */
    val boundPort: Int get() = serverSocket?.localPort ?: -1

    /**
     * 启动监听。端口被占用时依次尝试 `port+1 .. port+9`（避免用户因为
     * 「8765 被别的 App 占了」而完全用不了这个功能）。
     *
     * @return 是否成功绑定。
     */
    fun start(): Boolean {
        if (running) return true

        val exec = ThreadPoolExecutor(
            4, 4, 0L, TimeUnit.MILLISECONDS,
            SynchronousQueue(),
            { r -> Thread(r, "ql-http").apply { isDaemon = true } }
        )

        var bound: ServerSocket? = null
        var lastError: Exception? = null
        for (offset in 0..9) {
            val candidate = requestedPort + offset
            if (candidate > 65535) break
            try {
                bound = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(candidate))
                }
                break
            } catch (e: Exception) {
                lastError = e
            }
        }

        if (bound == null) {
            exec.shutdownNow()
            if (lastError != null) {
                safeLog("绑定端口失败 $requestedPort..+9: ${lastError.message}")
            }
            return false
        }

        serverSocket = bound
        executor = exec
        running = true
        acceptThread = Thread({ acceptLoop(bound, exec) }, "ql-http-accept").apply {
            isDaemon = true
            start()
        }
        return true
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { executor?.shutdownNow() }
        executor = null
        acceptThread = null
    }

    private fun acceptLoop(server: ServerSocket, exec: ThreadPoolExecutor) {
        var consecutiveErrors = 0
        while (running && !server.isClosed) {
            val client = try {
                server.accept().also { consecutiveErrors = 0 }
            } catch (e: Exception) {
                if (!running) return
                // ⚠️ 这里绝不能无间隔 continue：accept 失败常见于 EMFILE（文件句柄耗尽）
                // 这类**持续性**错误，裸重试会让这个线程 100% 占住一个 CPU 核空转，
                // 用户看到的是「手机莫名发热耗电」，而且日志里只有一堆 accept 异常。
                // 退避重试 + 连续失败到阈值就停止监听，等下次 start() 重建。
                if (++consecutiveErrors >= MAX_ACCEPT_ERRORS) {
                    safeLog("accept 连续失败 $consecutiveErrors 次，停止监听")
                    running = false
                    return
                }
                runCatching { Thread.sleep(ACCEPT_RETRY_DELAY_MS) }
                continue
            }
            try {
                exec.execute { handle(client) }
            } catch (e: RejectedExecutionException) {
                // 并发已满：直接关掉，不排队 —— 排队只会把内存耗在等待上
                runCatching { client.close() }
            }
        }
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            runCatching {
                socket.soTimeout = READ_TIMEOUT_MS
                val input = BufferedInputStream(socket.getInputStream())
                val output = socket.getOutputStream()

                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 2) {
                    write(output, Response.text(400, "bad request line"))
                    return
                }
                val method = parts[0].uppercase(Locale.US)
                val rawTarget = parts[1]

                val headers = readHeaders(input)
                if (headers == null) {
                    write(output, Response.text(431, "header too large"))
                    return
                }

                // 明确拒绝分块传输：本服务只处理固定长度，解析 chunked 会引入一整类边界 bug
                if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
                    write(output, Response.text(411, "length required"))
                    return
                }

                val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                if (contentLength < 0 || contentLength > MAX_BODY_BYTES) {
                    write(output, Response.text(413, "body too large"))
                    return
                }
                val body = if (contentLength > 0) readExactly(input, contentLength) else ByteArray(0)
                if (body == null) {
                    write(output, Response.text(400, "incomplete body"))
                    return
                }

                val path = rawTarget.substringBefore('?')
                val query = parseQuery(rawTarget.substringAfter('?', ""))

                val response = try {
                    handler(
                        Request(
                            method = method,
                            path = path,
                            query = query,
                            headers = headers,
                            body = body,
                            remoteAddress = socket.inetAddress?.hostAddress ?: ""
                        )
                    )
                } catch (e: Exception) {
                    safeLog("handler failed: ${e.message}")
                    Response.text(500, "internal error")
                }
                write(output, response)
            }.onFailure {
                // 连接被对端重置、读超时、客户端中途断开 —— 都属正常噪声，不记日志
            }
        }
    }

    /** 逐字节读一行（\r\n 结尾）。返回 null 表示对端直接关闭。 */    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (b == '\n'.code) {
                val bytes = buf.toByteArray()
                val trimmed = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) {
                    bytes.copyOfRange(0, bytes.size - 1)
                } else bytes
                return String(trimmed, Charsets.UTF_8)
            }
            if (buf.size() >= MAX_LINE_BYTES) return null
            buf.write(b)
            prev = b
        }
    }

    /** 读头部，超过行数或总长上限返回 null（调用方回 431）。 */
    private fun readHeaders(input: InputStream): Map<String, String>? {
        val map = HashMap<String, String>(8)
        var total = 0
        var count = 0
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) return map
            if (++count > MAX_HEADER_LINES) return null
            total += line.length
            if (total > MAX_LINE_BYTES) return null
            val idx = line.indexOf(':')
            if (idx <= 0) continue        // 畸形头直接忽略，不尝试"修复"
            val name = line.substring(0, idx).trim().lowercase(Locale.US)
            val value = line.substring(idx + 1).trim()
            // 同名头只保留第一个：本服务不依赖任何多值头，多值只会给走私攻击留缝
            if (!map.containsKey(name)) map[name] = value
        }
    }

    private fun readExactly(input: InputStream, size: Int): ByteArray? {
        val out = ByteArray(size)
        var read = 0
        while (read < size) {
            val n = input.read(out, read, size - read)
            if (n <= 0) return null
            read += n
        }
        return out
    }

    private fun write(output: OutputStream, response: Response) {
        val head = buildString {
            append("HTTP/1.1 ").append(response.status).append(' ').append(statusText(response.status)).append("\r\n")
            append("Content-Type: ").append(response.contentType).append("\r\n")
            append("Content-Length: ").append(response.body.size).append("\r\n")
            append("Connection: close\r\n")
            append("Cache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("\r\n")
        }
        output.write(head.toByteArray(Charsets.US_ASCII))
        output.write(response.body)
        output.flush()
    }

    private fun statusText(code: Int): String = when (code) {
        200 -> "OK"
        201 -> "Created"
        204 -> "No Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        409 -> "Conflict"
        411 -> "Length Required"
        413 -> "Payload Too Large"
        429 -> "Too Many Requests"
        431 -> "Request Header Fields Too Large"
        500 -> "Internal Server Error"
        else -> "Status"
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val map = HashMap<String, String>(4)
        raw.split('&').forEach { pair ->
            if (pair.isBlank()) return@forEach
            val k = pair.substringBefore('=')
            val v = pair.substringAfter('=', "")
            if (k.isNotBlank()) map[k] = decode(v)
        }
        return map
    }

    private fun decode(value: String): String = runCatching {
        java.net.URLDecoder.decode(value, "UTF-8")
    }.getOrDefault(value)

    /**
     * 日志只在真机上有意义。`android.util.Log` 在 JVM 单测里是 "not mocked" 桩，
     * 一调用就抛 RuntimeException；包一层 runCatching 之后，
     * 本类就能被真实的 socket 往返测试覆盖（否则整个 HTTP 解析层只能靠真机验）。
     */
    private fun safeLog(msg: String) {
        runCatching { android.util.Log.w("QL-Remote", msg) }
    }

    private companion object {
        const val READ_TIMEOUT_MS = 10_000
        const val MAX_LINE_BYTES = 8 * 1024
        const val MAX_HEADER_LINES = 64
        const val MAX_BODY_BYTES = 8 * 1024

        /** accept 连续失败达到这个次数就停止监听，避免持续性错误把线程拖成死循环。 */
        const val MAX_ACCEPT_ERRORS = 20

        /** accept 失败后的退避时间：足够让 EMFILE 之类的瞬时压力缓解，又不至于明显延迟重连。 */
        const val ACCEPT_RETRY_DELAY_MS = 50L
    }
}
