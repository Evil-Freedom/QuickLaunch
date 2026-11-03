package com.workbuddy.quicklaunch.remote

import java.io.ByteArrayOutputStream

/**
 * 自带的标准 Base64（带 padding）。
 *
 * 为什么不用现成的：
 * - `java.util.Base64` 是 Java 8 API，**Android 上要 API 26+**，而本项目 minSdk = 24；
 * - `android.util.Base64` 在 JVM 单元测试里是 "not mocked" 空桩，一调就抛 RuntimeException，
 *   会让协议层与鉴权层无法被纯 JVM 测试覆盖。
 *
 * 实现只覆盖标准字母表 + `=` 补齐，不做 URL-safe 变体 —— 本功能所有 base64
 * 都只在自己的客户端与服务端之间流转，不需要兼容外部实现。
 */
internal object B64 {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encodeToString(data: ByteArray): String {
        if (data.isEmpty()) return ""
        val sb = StringBuilder(((data.size + 2) / 3) * 4)
        var i = 0
        while (i + 3 <= data.size) {
            val n = ((data[i].toInt() and 0xFF) shl 16) or
                ((data[i + 1].toInt() and 0xFF) shl 8) or
                (data[i + 2].toInt() and 0xFF)
            sb.append(ALPHABET[(n ushr 18) and 63])
            sb.append(ALPHABET[(n ushr 12) and 63])
            sb.append(ALPHABET[(n ushr 6) and 63])
            sb.append(ALPHABET[n and 63])
            i += 3
        }
        when (data.size - i) {
            1 -> {
                val n = (data[i].toInt() and 0xFF) shl 16
                sb.append(ALPHABET[(n ushr 18) and 63])
                sb.append(ALPHABET[(n ushr 12) and 63])
                sb.append("==")
            }
            2 -> {
                val n = ((data[i].toInt() and 0xFF) shl 16) or ((data[i + 1].toInt() and 0xFF) shl 8)
                sb.append(ALPHABET[(n ushr 18) and 63])
                sb.append(ALPHABET[(n ushr 12) and 63])
                sb.append(ALPHABET[(n ushr 6) and 63])
                sb.append('=')
            }
        }
        return sb.toString()
    }

    /** 任何非法输入（长度不是 4 的倍数、含非法字符）一律返回 null，由调用方转 400/401。 */
    fun decode(text: String): ByteArray? {
        val clean = text.filterNot { it.isWhitespace() }
        if (clean.isEmpty()) return ByteArray(0)
        if (clean.length % 4 != 0) return null

        val out = ByteArrayOutputStream(clean.length / 4 * 3)
        var i = 0
        while (i < clean.length) {
            val c0 = indexOf(clean[i])
            val c1 = indexOf(clean[i + 1])
            val pad2 = clean[i + 2] == '='
            val pad3 = clean[i + 3] == '='
            if (pad2 && !pad3) return null              // "xx=x" 不是合法 padding 形态
            val c2 = if (pad2) 0 else indexOf(clean[i + 2])
            val c3 = if (pad3) 0 else indexOf(clean[i + 3])
            if (c0 < 0 || c1 < 0 || c2 < 0 || c3 < 0) return null

            val n = (c0 shl 18) or (c1 shl 12) or (c2 shl 6) or c3
            out.write((n ushr 16) and 0xFF)
            if (!pad2) out.write((n ushr 8) and 0xFF)
            if (!pad3) out.write(n and 0xFF)
            i += 4
        }
        return out.toByteArray()
    }

    private fun indexOf(c: Char): Int = when (c) {
        in 'A'..'Z' -> c - 'A'
        in 'a'..'z' -> c - 'a' + 26
        in '0'..'9' -> c - '0' + 52
        '+' -> 62
        '/' -> 63
        else -> -1
    }
}
