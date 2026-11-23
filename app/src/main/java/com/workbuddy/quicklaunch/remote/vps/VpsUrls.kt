package com.workbuddy.quicklaunch.remote.vps

/**
 * 同步地址的选择与失败重试判定。**刻意不引用任何 Android API**，
 * 这样这些边界能在 JVM 单测里钉死。
 *
 * 背景：真实网络里「服务本身好的、但这个域名不通」是常态而不是异常 ——
 * 公司/园区网络会按域名拦截（`*.duckdns.org` 返回 403 或直接 TLS reset）。
 * 因此客户端支持一个主地址 + 一个备用地址，按顺序尝试。
 */
object VpsUrls {

    /**
     * 地址尝试顺序：**上次成功的 → 主地址 → 备用地址**。
     *
     * 「上次成功的」放最前，是为了不在已知不通的地址上白等 10 秒连接超时。
     * 去重，并跳过空值与不合法的地址（非法值留在这里只会在运行时抛异常）。
     */
    fun candidates(lastGood: String, baseUrl: String, backupUrl: String): List<String> {
        val out = LinkedHashSet<String>()
        listOf(lastGood, baseUrl, backupUrl).forEach { raw ->
            val url = raw.trim().trimEnd('/')
            if (url.isNotEmpty() && VpsSettings.validateUrl(url) == null) out += url
        }
        return out.toList()
    }

    /**
     * 这个失败值得换下一个地址吗？
     *
     * 只认「网络/网关」层的问题：
     * - `code <= 0`：连接超时、DNS 失败、TLS 被 reset（客户端统一记 -1）
     * - `502/503/504`：网关级错误（例如 Caddy 在、api 没起）
     *
     * **401/400/404 不换**：那是签名、设备或参数问题，换个域名一模一样，
     * 换过去只会把真正的原因埋掉。
     */
    fun retryable(code: Int): Boolean =
        code <= 0 || code == 502 || code == 503 || code == 504
}
