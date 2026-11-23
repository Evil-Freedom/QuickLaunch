package com.workbuddy.quicklaunch.launch

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.workbuddy.quicklaunch.util.RootUtils

/**
 * 用 root 把目标 App 启动到**指定的 display**。
 *
 * ## 为什么必须走 root
 *
 * 2026-11-23 合盖真机实测三种路径：
 *
 * | 路径 | 结果 |
 * | --- | --- |
 * | `ActivityOptions.setLaunchDisplayId(1)` + `startActivity` | 返回 true，**目标被丢到 Display #5（虚拟屏）**，外屏看不到 |
 * | 不指定 display，靠系统决定 | 同上，落在默认/虚拟屏 |
 * | `su -c am start --display 1` | **成功**，目标出现在外屏（`topResumedActivity` 就是它） |
 *
 * 根因是 Android 12+ 对普通 App 的跨屏启动限制：`am` 是 shell（uid 2000），
 * 持有跨用户/跨显示权限；App 进程没有，请求被静默丢弃 —— 而 `startActivity`
 * 依然返回 true。这正是「命令返回成功 ≠ App 真的打开」最典型的例子，
 * 也是本次升级要加验证层的原因。
 *
 * ## 安全边界
 *
 * - 组件名与 displayId 都**先白名单校验再拼命令**：包名/类名只允许
 *   `[A-Za-z0-9_.$]`，displayId 强制是整数。任何一个不合法就直接放弃（不拼命令）。
 * - 命令通过 `RootUtils.capture` 执行：带超时、排空 stderr、退出码非 0 视为失败。
 * - **不接收任何来自网络的输入**：包名来自本机 `PackageManager`（`getLaunchIntentForPackage`），
 *   displayId 来自本机 `DisplayManager`。远程配置只能改「几点启动已有规则」，
 *   碰不到这两个值（见 `RemoteProtocol.EDITABLE_FIELDS`）。
 */
object RootDisplayLauncher {

    private const val TAG = "QL-RootLaunch"

    /** 允许出现在包名/类名里的字符。`$` 用于内部类。 */
    private val SAFE_COMPONENT = Regex("^[A-Za-z0-9_.$]+$")

    /**
     * 在 [displayId] 上启动 [pkg] 的启动 Activity。**阻塞调用**，需在后台线程执行。
     *
     * @return true 表示命令执行成功（退出码 0）。**这不代表 App 已经出现**——
     *         调用方仍必须用 [ForegroundProbe] 验证，这正是本项目的核心纪律。
     */
    fun launchOnDisplay(context: Context, pkg: String, displayId: Int): Boolean {
        if (displayId < 0) return false
        val component = resolveLauncher(context, pkg) ?: return false
        val pkgName = component.packageName
        val clsName = component.className
        if (!SAFE_COMPONENT.matches(pkgName) || !SAFE_COMPONENT.matches(clsName)) {
            Log.w(TAG, "组件名不合法，拒绝拼命令：$pkgName/$clsName")
            return false
        }
        // 只保留 ACTION_MAIN/LAUNCHER 的语义：用 -n 精确启动，不带任何用户可控参数
        val cmd = "am start --display $displayId -n $pkgName/$clsName"
        val out = RootUtils.capture(cmd, TIMEOUT_MS)
        val ok = out != null && !out.contains("Error:")
        Log.i(TAG, "root 启动 display=$displayId $pkgName/$clsName → ${if (ok) "已提交" else "失败"}")
        return ok
    }

    /** 取目标包的启动 Activity；取不到（未安装/无 launcher）返回 null。 */
    private fun resolveLauncher(context: Context, pkg: String): ComponentName? =
        runCatching {
            context.packageManager.getLaunchIntentForPackage(pkg)?.component
        }.getOrNull()

    /** 目标包是否存在（供上层区分「没装」与「装了但起不来」）。 */
    fun isInstalled(context: Context, pkg: String): Boolean = runCatching {
        context.packageManager.getLaunchIntentForPackage(pkg) != null
    }.getOrDefault(false)

    /** 是否具备 root 启动能力。带缓存，避免每次触发都 fork su。 */
    fun available(): Boolean = runCatching { RootUtils.hasRoot() }.getOrDefault(false)

    private const val TIMEOUT_MS = 5_000L
}
