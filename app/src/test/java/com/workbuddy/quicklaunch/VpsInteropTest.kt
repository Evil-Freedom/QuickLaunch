package com.workbuddy.quicklaunch

import com.workbuddy.quicklaunch.remote.vps.VpsEnvelope
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 跨语言签名互操作守卫。
 *
 * Android 端（Kotlin）和 VPS 端（Python）各自实现同一套 HMAC 拼接规则。
 * 两处任何一处改动（字段顺序、分隔符、大小写、是否含换行）都会让线上表现为
 * **「手机一直 401」**，而单侧测试永远是绿的 —— 因为自己跟自己比总是对得上。
 *
 * 这里把 Python 侧算出的值**硬编码**进断言。一旦某一侧改了拼接方式，
 * 这个用例立刻变红，而不是等到真机连服务器时才发现。
 *
 * 基准值由 `vps/` 下的 Python 代码（`app/auth.py` 的同一套拼接）生成：
 * secret = bytes 0..31，deviceId = dev_test_001，nonce = a1b2c3d4e5f60718，
 * ts = 1763970000000，since = 6，version = 7，内容指纹 = ab12cd34ef56ab12。
 *
 * v1.4.1 起 canonical 末尾多了内容指纹（hash）字段：
 * 请求为 `...\\n since \\n hash`，响应为 `...\\n payloadB64 \\n hash`。
 * 指纹进签名是因为它决定客户端「要不要重新应用」，可被伪造的话攻击者能让设备跳过更新。
 */
class VpsInteropTest {

    private val secret = ByteArray(32) { it.toByte() }
    private val deviceId = "dev_test_001"
    private val nonce = "a1b2c3d4e5f60718"
    private val ts = 1_763_970_000_000L
    private val since = 6L
    private val version = 7L
    private val contentHash = "ab12cd34ef56ab12"

    @Test
    fun `请求签名与 Python 侧完全一致`() {
        val path = "/api/v1/config?device=$deviceId&since=$since&hash=$contentHash"
        val sig = VpsEnvelope.requestSignature(
            secret, path, ts, nonce, deviceId, since, contentHash
        )
        assertEquals(
            "5d1bab43ffea9e3661d6afe68906658fb7bf1481d461eb6b96ecbeddd404403d",
            sig
        )
    }

    @Test
    fun `响应签名与 Python 侧完全一致`() {
        val payloadB64 = "eyJydWxlcyI6W3siaWQiOjIsInRpbWVIb3VyIjo4LCJ0aW1lTWludXRlIjozMH1d" +
            "LCJyZXRyeSI6eyJtYXhBdHRlbXB0cyI6M319"
        val sig = VpsEnvelope.signature(
            secret, VpsEnvelope.ALG, version, ts, nonce, payloadB64, "35dde09173db7b03"
        )
        assertEquals(
            "00a78206d2519c7616d3d6e04a764cd5b99d1af48934b559f8d50ed5f62c1dc5",
            sig
        )
    }

    @Test
    fun `回报签名与 Python 侧完全一致`() {
        val sig = VpsEnvelope.reportSignature(
            secret, deviceId, version, ts, ok = true, detail = "v7 应用 1 条"
        )
        assertEquals(
            "cf5552f054e78ac3c9bc5564634a61037cf97e025efe3c0970f3779ce6c55a61",
            sig
        )
    }
}
