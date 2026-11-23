package com.workbuddy.quicklaunch.remote.vps

import android.content.Context
import com.workbuddy.quicklaunch.util.AutoUnlock
import org.json.JSONObject

/**
 * 自动解锁的**状态快照**（不含任何 PIN 内容）。
 *
 * 这是「远程看板能看到的全部」：是否启用、有没有设过密码（布尔）、存储方式枚举、
 * 失败计数、上次结果。密码本体、密码长度、密码哈希都不在这里 ——
 * 看板需要回答的问题是「解锁还正常吗」，不是「密码是什么」。
 */
object AutoUnlockStatus {

    fun snapshot(context: Context): JSONObject = JSONObject().apply {
        val app = context.applicationContext
        put("enabled", AutoUnlock.isEnabled(app))
        put("hasPin", AutoUnlock.hasPin(app))
        // 存储方式让看板能显示「降级」徽标：KEYSTORE / OBFUSCATED / NONE
        put("storage", AutoUnlock.pinStorageKind(app).name)
        put("chargingOnly", AutoUnlock.isChargingOnly(app))
        put("failures", AutoUnlock.failures(app))
        put("maxFailures", AutoUnlock.MAX_FAILURES)
        put("securityLocked", AutoUnlock.isSecurityLocked(app))
        put("lastAttemptAt", AutoUnlock.lastAttemptAt(app))
        put("lastResult", AutoUnlock.lastResult(app))
    }
}
