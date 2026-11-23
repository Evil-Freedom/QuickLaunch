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
 * ts = 1763970000000，since = 6，version = 7。
 */
class VpsInteropTest {

    private val secret = ByteArray(32) { it.toByte() }
    private val deviceId = "dev_test_001"
    private val nonce = "a1b2c3d4e5f60718"
    private val ts = 1_763_970_000_000L
    private val since = 6L
    private val version = 7L

    @Test
    fun `请求签名与 Python 侧完全一致`() {
        val path = "/api/v1/config?device=$deviceId&since=$since"
        val sig = VpsEnvelope.requestSignature(secret, path, ts, nonce, deviceId, since)
        assertEquals(
            "78149690cf647851365cdc12583423f654fac64d33d7fa279c1a039e42b8188c",
            sig
        )
    }

    @Test
    fun `响应签名与 Python 侧完全一致`() {
        val payloadB64 = "eyJydWxlcyI6W3siaWQiOjIsInRpbWVIb3VyIjo4LCJ0aW1lTWludXRlIjozMH1d" +
            "LCJyZXRyeSI6eyJtYXhBdHRlbXB0cyI6M319"
        val sig = VpsEnvelope.signature(
            secret, VpsEnvelope.ALG, version, ts, nonce, payloadB64
        )
        assertEquals(
            "c76c3cefa393fe8829bbe2d58959842b8a4ca0819ab9d29af05864bd55c19444",
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
