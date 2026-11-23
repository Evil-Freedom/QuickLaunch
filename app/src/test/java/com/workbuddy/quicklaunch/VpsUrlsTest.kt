package com.workbuddy.quicklaunch

import com.workbuddy.quicklaunch.remote.vps.VpsUrls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多地址同步的选择逻辑。
 *
 * 这些用例来自真机现场：公司网络按域名拦截 `*.duckdns.org`，
 * 同一个服务换一个域名就通。所以「什么时候换地址」必须是确定行为，
 * 而不是每次靠试。
 */
class VpsUrlsTest {

    private val primary = "https://qlhub.duckdns.org"
    private val backup = "https://47-100-162-254.sslip.io"

    @Test
    fun `顺序是 上次成功 主地址 备用地址`() {
        // 上次成功走的是备用地址：它必须排第一，否则每次同步都要先在
        // 已知不通的主地址上白等 10 秒连接超时。
        assertEquals(
            listOf(backup, primary),
            VpsUrls.candidates(lastGood = backup, baseUrl = primary, backupUrl = backup)
        )
    }

    @Test
    fun `没有上次记录时按主 备顺序`() {
        assertEquals(
            listOf(primary, backup),
            VpsUrls.candidates(lastGood = "", baseUrl = primary, backupUrl = backup)
        )
    }

    @Test
    fun `没有备用地址时只有主地址`() {
        assertEquals(
            listOf(primary),
            VpsUrls.candidates(lastGood = "", baseUrl = primary, backupUrl = "")
        )
    }

    @Test
    fun `地址去重`() {
        // 上次成功的就是主地址时不能出现两遍（会白跑一次请求）
        assertEquals(
            listOf(primary, backup),
            VpsUrls.candidates(lastGood = primary, baseUrl = primary, backupUrl = backup)
        )
    }

    @Test
    fun `尾部斜杠与空白被规范化`() {
        assertEquals(
            listOf(primary),
            VpsUrls.candidates(lastGood = "", baseUrl = "  $primary/  ", backupUrl = "")
        )
    }

    @Test
    fun `非法地址被跳过`() {
        // http:// 公网地址是明确禁止的（配置里有作息表，不能明文传）；
        // 空串也不能进候选，否则会拼出非法 URL 直接抛异常
        assertEquals(
            listOf(primary),
            VpsUrls.candidates(lastGood = "", baseUrl = primary, backupUrl = "http://example.com")
        )
        assertEquals(
            emptyList<String>(),
            VpsUrls.candidates(lastGood = "  ", baseUrl = "", backupUrl = "not-a-url")
        )
    }

    @Test
    fun `回环地址允许作为调试入口`() {
        // adb reverse 的调试路径允许 http://127.0.0.1（报文不出设备）
        assertEquals(
            listOf("http://127.0.0.1:8779"),
            VpsUrls.candidates(lastGood = "", baseUrl = "http://127.0.0.1:8779", backupUrl = "")
        )
    }

    // ── 什么时候值得换地址 ────────────────────────────────────────

    @Test
    fun `网络与网关级失败值得换地址`() {
        assertTrue(VpsUrls.retryable(-1))    // 连接超时 / TLS reset
        assertTrue(VpsUrls.retryable(0))     // 未知网络错误
        assertTrue(VpsUrls.retryable(502))   // 网关在、api 不在
        assertTrue(VpsUrls.retryable(503))
        assertTrue(VpsUrls.retryable(504))
    }

    @Test
    fun `认证与参数问题不换地址`() {
        // 换域名解决不了签名错误，只会把真实原因埋掉
        assertEquals(false, VpsUrls.retryable(401))
        assertEquals(false, VpsUrls.retryable(400))
        assertEquals(false, VpsUrls.retryable(404))
        assertEquals(false, VpsUrls.retryable(409))
        assertEquals(false, VpsUrls.retryable(200))
    }
}
