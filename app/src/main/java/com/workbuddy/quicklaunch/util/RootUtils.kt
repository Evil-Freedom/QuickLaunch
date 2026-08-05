package com.workbuddy.quicklaunch.util

import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Root 检测与命令执行。SukiSU / KernelSU 提供 `su` 二进制，
 * 用它执行需要 root 的命令（如 settings put 防息屏）。
 */
object RootUtils {

    /** 是否有可用 root：尝试 `su -c id` 看是否拿到 uid=0。 */
    fun hasRoot(): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val out = BufferedReader(InputStreamReader(p.inputStream)).readText()
            val code = p.waitFor()
            code == 0 && out.contains("uid=0")
        } catch (_: Exception) {
            false
        }
    }

    /** 以 root 执行命令，返回是否成功（exit 0）。 */
    fun runAsRoot(cmd: String): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            p.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }
}
