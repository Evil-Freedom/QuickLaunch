package com.workbuddy.quicklaunch.util

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 锁屏密码的本地保管。
 *
 * 优先走 AndroidKeyStore 的 AES-GCM：密钥不可导出、且绑定本机，
 * 即使有人用 `adb backup` 把 SharedPreferences 整包拉走也只拿到密文。
 * KeyStore 在某些定制 ROM 上可能不可用（抛异常），此时退化为 XOR + Base64 混淆
 * —— 这一步**不是加密**，只避免密码以明文出现在 XML 里被人一眼看到。
 *
 * 两种格式用前缀区分，读取时按前缀分派，因此升级/降级都不会读到脏数据。
 */
object PinVault {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "ql-auto-unlock-v1"
    private const val PREFIX_KS = "ks1:"
    private const val PREFIX_XOR = "x1:"

    /** 混淆用的固定掩码。公开可见，仅用于「不出现明文」，不具备机密性。 */
    private const val MASK = "ql-pin-obfuscate-2026"

    /** 加密保存；返回可直接写入 SharedPreferences 的字符串，失败返回 null。 */
    fun seal(ctx: Context, pin: String): String? {
        if (pin.isEmpty()) return null
        val plain = pin.toByteArray(Charsets.UTF_8)
        keystoreEncrypt(plain)?.let { return PREFIX_KS + Base64.encodeToString(it, Base64.NO_WRAP) }
        return PREFIX_XOR + Base64.encodeToString(xor(plain), Base64.NO_WRAP)
    }

    /** 读取解密；任何异常一律返回 null（当作没设过密码）。 */
    fun open(ctx: Context, blob: String?): String? {
        if (blob.isNullOrEmpty()) return null
        return runCatching {
            when {
                blob.startsWith(PREFIX_KS) -> {
                    val raw = Base64.decode(blob.removePrefix(PREFIX_KS), Base64.NO_WRAP)
                    keystoreDecrypt(raw)?.toString(Charsets.UTF_8)
                }
                blob.startsWith(PREFIX_XOR) -> {
                    val raw = Base64.decode(blob.removePrefix(PREFIX_XOR), Base64.NO_WRAP)
                    xor(raw).toString(Charsets.UTF_8)
                }
                else -> null
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    // ── AndroidKeyStore ────────────────────────────────────────────

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // 不要求解锁才能用：解锁动作本身就在锁屏上，若绑 setUserAuthenticationRequired
                // 会在「设备已锁」状态下解不开自己的密码，属于自锁死。
                .setUserAuthenticationRequired(false)
                .build()
        )
        return gen.generateKey()
    }

    /** 输出 = IV(12B) + 密文，IV 每次随机。 */
    private fun keystoreEncrypt(plain: ByteArray): ByteArray? = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        cipher.iv + cipher.doFinal(plain)
    }.getOrNull()

    private fun keystoreDecrypt(raw: ByteArray): ByteArray? = runCatching {
        if (raw.size <= 12) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, raw, 0, 12))
        cipher.doFinal(raw, 12, raw.size - 12)
    }.getOrNull()

    // ── 混淆兜底 ───────────────────────────────────────────────────

    private fun xor(data: ByteArray): ByteArray {
        val k = MASK.toByteArray(Charsets.UTF_8)
        return ByteArray(data.size) { i -> (data[i].toInt() xor k[i % k.size].toInt()).toByte() }
    }
}
