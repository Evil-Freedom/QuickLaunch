package com.workbuddy.quicklaunch

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import com.workbuddy.quicklaunch.launch.LaunchAttemptController
import com.workbuddy.quicklaunch.util.DisplayPicker

/**
 * 透明中转页：触发时先拉起它，再由它启动目标 App。多花一个 Activity，一次解决四件事：
 *
 * 1. **绕过后台启动限制** —— 从「已在前台的 Activity」启动其它应用是完全合法的，
 *    不受 Android 10+ 的后台 startActivity 管控。
 * 2. **点亮屏幕** —— turnScreenOn 是官方 API，替代早已废弃的 SCREEN_BRIGHT_WAKE_LOCK，
 *    息屏状态下的定时任务因此才真正可用。
 * 3. **盖在锁屏之上** —— showWhenLocked。
 * 4. **折叠屏外屏** —— 合盖时它自己就跑在外屏上，从它启动的目标 App 默认继承同一块屏；
 *    再配合 DisplayPicker 显式指定 displayId 双保险。
 *
 * 用的是 Activity 而非 AppCompatActivity：不加载任何主题资源与 AppCompat 委托，启动开销最小。
 */
class LaunchProxyActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // finally 保证任何异常都不会把这个透明页留在屏幕上（残留会挡住整个界面且无法关闭）。
        try {
            turnScreenOnCompat()
            launchTarget(
                intent?.getStringExtra(EXTRA_PKG).orEmpty(),
                intent?.getStringExtra(EXTRA_ATTEMPT_ID)
            )
        } finally {
            finish()
        }
    }

    /** 通过 fullScreenIntent 复用同一实例时会走这里。 */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        try {
            setIntent(intent)
            turnScreenOnCompat()
            launchTarget(
                intent?.getStringExtra(EXTRA_PKG).orEmpty(),
                intent?.getStringExtra(EXTRA_ATTEMPT_ID)
            )
        } finally {
            finish()
        }
    }

    private fun launchTarget(pkg: String, attemptId: String?) {
        if (pkg.isEmpty()) {
            reportDispatch(attemptId, false)
            return
        }
        // 目标应用可能已被卸载/停用，getLaunchIntentForPackage 在部分 ROM 上会抛而非返回 null。
        val target = runCatching { packageManager.getLaunchIntentForPackage(pkg) }.getOrNull()
        if (target == null) {
            reportDispatch(attemptId, false)
            return
        }
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

        // 显式投到当前点亮的屏幕；单屏设备 options 为 null，等价于普通 startActivity
        val ok = runCatching { startActivity(target, DisplayPicker.launchOptions(this)) }.isSuccess
        val finalOk = if (!ok) {
            // 副屏被拒时退回主屏，总比不启动强
            runCatching { startActivity(target) }.isSuccess
        } else {
            true
        }
        reportDispatch(attemptId, finalOk)
    }

    /**
     * 把「命令已发出」的事实回传给验证链路。
     *
     * 只在带 attemptId 的调用里回传：用户点通知直接打开 App 时没有 attemptId，
     * 那种场景不需要（也不应该）触发一套重试验证。
     */
    private fun reportDispatch(attemptId: String?, accepted: Boolean) {
        if (attemptId.isNullOrBlank()) return
        runCatching { LaunchAttemptController.onProxyLaunched(this, attemptId, accepted) }
    }

    private fun turnScreenOnCompat() {
        // 点亮屏幕失败不应阻断启动目标 App，因此整体吞掉异常。
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            } else {
                @Suppress("DEPRECATION")
                window.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                )
            }
        }
    }

    companion object {
        private const val EXTRA_PKG = "pkg"
        private const val EXTRA_ATTEMPT_ID = "attempt_id"

        /**
         * @param attemptId 验证任务 id。带它时本页会在发出启动命令后回传结果；
         *        不带（用户点通知手动打开）时行为与升级前完全一致。
         */
        fun intent(context: Context, pkg: String, attemptId: String? = null): Intent =
            Intent(context, LaunchProxyActivity::class.java)
                .putExtra(EXTRA_PKG, pkg)
                .apply { if (attemptId != null) putExtra(EXTRA_ATTEMPT_ID, attemptId) }
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}
