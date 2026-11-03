package com.workbuddy.quicklaunch

import com.workbuddy.quicklaunch.remote.MiniHttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * 自研 HTTP 层的真实 socket 往返测试（本机回环，不依赖 Android）。
 *
 * 这是本功能里唯一「自己写了协议解析」的地方，所以必须用**真实报文**验证，
 * 而不是只靠编译通过：
 * - 正常请求能否被解析出行/头/body
 * - 超长 body、chunked 编码是否被按设计拒绝（这两条是安全上限）
 * - 畸形报文是否会让服务线程崩溃或泄漏连接
 * - 端口冲突时是否会自动后移
 */
class MiniHttpServerTest {

    private var server: MiniHttpServer? = null

    @After
    fun tearDown() {
        server?.stop()
        server = null
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun start(
        port: Int = freePort(),
        handler: (MiniHttpServer.Request) -> MiniHttpServer.Response
    ): MiniHttpServer {
        val s = MiniHttpServer(port, handler)
        assertTrue("服务未能启动", s.start())
        server = s
        return s
    }

    /** 直接写原始报文，确保测的是解析器而不是某个客户端库的行为。 */
    private fun raw(port: Int, text: String): String {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5000
            socket.getOutputStream().apply {
                write(text.toByteArray(Charsets.US_ASCII))
                flush()
            }
            return socket.getInputStream().readAllBytes().toString(Charsets.UTF_8)
        }
    }

    private fun InputStream.readAllBytes(): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        while (true) {
            val n = read(chunk)
            if (n <= 0) break
            buf.write(chunk, 0, n)
        }
        return buf.toByteArray()
    }

    private fun statusOf(response: String): Int =
        response.substringAfter("HTTP/1.1 ").substringBefore(' ').toIntOrNull() ?: -1

    private fun bodyOf(response: String): String = response.substringAfter("\r\n\r\n")

    private fun contentLengthOf(response: String): Int =
        Regex("(?i)Content-Length:\\s*(\\d+)").find(response)?.groupValues?.get(1)?.toIntOrNull() ?: -1

    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `GET 请求能被解析并返回响应`() {
        var seenPath: String? = null
        var seenMethod: String? = null
        val s = start { req ->
            seenPath = req.path
            seenMethod = req.method
            MiniHttpServer.Response.json(200, """{"ok":true}""")
        }

        val resp = raw(s.boundPort, "GET /api/v1/ping HTTP/1.1\r\nHost: x\r\n\r\n")
        assertEquals(200, statusOf(resp))
        assertEquals("/api/v1/ping", seenPath)
        assertEquals("GET", seenMethod)
        assertEquals("""{"ok":true}""", bodyOf(resp))
    }

    @Test
    fun `POST 的固定长度 body 被完整读取`() {
        var body: String? = null
        val payload = """{"timeHour":9}"""
        val s = start { req ->
            body = String(req.body, Charsets.UTF_8)
            MiniHttpServer.Response.json(200, "{}")
        }

        raw(
            s.boundPort,
            "POST /api/v1/rules/7 HTTP/1.1\r\nContent-Length: ${payload.toByteArray().size}\r\n\r\n$payload"
        )
        assertEquals(payload, body)
    }

    @Test
    fun `查询参数被解码`() {
        var days: Int? = null
        var at: Long? = null
        val s = start { req ->
            days = req.queryInt("days")
            at = req.queryLong("at")
            MiniHttpServer.Response.json(200, "{}")
        }

        raw(s.boundPort, "GET /api/v1/rules/7/calendar?days=14&at=1789898400000 HTTP/1.1\r\n\r\n")
        assertEquals(14, days)
        assertEquals(1_789_898_400_000L, at)
    }

    @Test
    fun `响应头带 Content-Length 且长度与实际一致`() {
        val body = """{"k":"值"}"""
        val s = start { MiniHttpServer.Response.json(200, body) }
        val resp = raw(s.boundPort, "GET /x HTTP/1.1\r\n\r\n")
        val declared = contentLengthOf(resp)
        assertTrue("缺少 Content-Length", declared > 0)
        assertEquals(body.toByteArray(Charsets.UTF_8).size, declared)
        assertTrue(resp.contains("Connection: close", ignoreCase = true))
    }

    // ── 安全上限 ──────────────────────────────────────────────────────

    @Test
    fun `超过 8KB 的 body 被拒绝且不读取`() {
        var invoked = false
        val s = start { req ->
            invoked = true
            MiniHttpServer.Response.json(200, "{}")
        }

        val resp = raw(
            s.boundPort,
            "POST /x HTTP/1.1\r\nContent-Length: 9000\r\n\r\n" + "a".repeat(9000)
        )
        assertEquals(413, statusOf(resp))
        assertFalse("超大 body 仍进入了业务处理", invoked)
    }

    @Test
    fun `chunked 编码被拒绝`() {
        // 本服务只支持固定长度；声明支持 chunked 就要处理一整套分块边界，
        // 那是自研解析最容易出错的地方，索性直接拒绝。
        var invoked = false
        val s = start { _ ->
            invoked = true
            MiniHttpServer.Response.json(200, "{}")
        }

        val resp = raw(
            s.boundPort,
            "POST /x HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n0\r\n\r\n"
        )
        assertEquals(411, statusOf(resp))
        assertFalse(invoked)
    }

    @Test
    fun `语义错误的请求行返回 400`() {
        val s = start { MiniHttpServer.Response.json(200, "{}") }
        val resp = raw(s.boundPort, "GARBAGE\r\n\r\n")
        assertEquals(400, statusOf(resp))
    }

    @Test
    fun `业务异常被转成 500 而不是让连接挂死`() {
        val s = start { _ -> throw IllegalStateException("boom") }
        val resp = raw(s.boundPort, "GET /x HTTP/1.1\r\n\r\n")
        assertEquals(500, statusOf(resp))
    }

    @Test
    fun `对端直接断开不会影响后续请求`() {
        val s = start { MiniHttpServer.Response.json(200, """{"ok":true}""") }
        // 只发一半就断开
        runCatching {
            Socket("127.0.0.1", s.boundPort).use { it.getOutputStream().write("GET /x HT".toByteArray()) }
        }
        // 服务仍然可用
        val resp = raw(s.boundPort, "GET /y HTTP/1.1\r\n\r\n")
        assertEquals(200, statusOf(resp))
    }

    @Test
    fun `重复头只保留第一个值`() {
        var seen: String? = null
        val s = start { req ->
            seen = req.header("X-Test")
            MiniHttpServer.Response.json(200, "{}")
        }
        raw(s.boundPort, "GET /x HTTP/1.1\r\nX-Test: first\r\nX-Test: second\r\n\r\n")
        assertEquals("first", seen)
    }

    @Test
    fun `头名大小写不敏感`() {
        var seen: String? = null
        val s = start { req ->
            seen = req.header("x-ql-key")
            MiniHttpServer.Response.json(200, "{}")
        }
        raw(s.boundPort, "GET /x HTTP/1.1\r\nX-QL-Key: k_abc\r\n\r\n")
        assertEquals("k_abc", seen)
    }

    @Test
    fun `缺少 Content-Length 时视为无 body`() {
        var size = -1
        val s = start { req ->
            size = req.body.size
            MiniHttpServer.Response.json(200, "{}")
        }
        raw(s.boundPort, "GET /x HTTP/1.1\r\n\r\n")
        assertEquals(0, size)
    }

    // ── 生命周期 ──────────────────────────────────────────────────────

    @Test
    fun `端口被占用时自动后移`() {
        val taken = ServerSocket(0)
        try {
            val busy = taken.localPort
            val s = MiniHttpServer(busy) { MiniHttpServer.Response.json(200, "{}") }
            assertTrue("没有回退到可用端口", s.start())
            assertTrue("实际端口没有后移", s.boundPort != busy)
            assertEquals(200, statusOf(raw(s.boundPort, "GET /x HTTP/1.1\r\n\r\n")))
            s.stop()
        } finally {
            runCatching { taken.close() }
        }
    }

    @Test
    fun `stop 之后端口可被重新绑定`() {
        val port = freePort()
        val s1 = MiniHttpServer(port) { MiniHttpServer.Response.json(200, "{}") }
        assertTrue(s1.start())
        s1.stop()

        val s2 = MiniHttpServer(port) { MiniHttpServer.Response.json(200, "{}") }
        assertTrue("stop 后端口没有释放", s2.start())
        assertEquals(port, s2.boundPort)
        s2.stop()
    }

    @Test
    fun `stop 是幂等的`() {
        val s = start { MiniHttpServer.Response.json(200, "{}") }
        s.stop()
        s.stop()
        assertEquals(-1, s.boundPort)
    }

    @Test
    fun `未启动时 boundPort 为 -1`() {
        val s = MiniHttpServer(freePort()) { MiniHttpServer.Response.json(200, "{}") }
        assertEquals(-1, s.boundPort)
        assertNull(null)
    }
}
