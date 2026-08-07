package com.workbuddy.quicklaunch

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
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
            launchTarget(intent?.getStringExtra(EXTRA_PKG).orEmpty())
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
            launchTarget(intent?.getStringExtra(EXTRA_PKG).orEmpty())
        } finally {
            finish()
        }
    }

    private fun launchTarget(pkg: String) {
        if (pkg.isEmpty()) return
        // 目标应用可能已被卸载/停用，getLaunchIntentForPackage 在部分 ROM 上会抛而非返回 null。
        val target = runCatching { packageManager.getLaunchIntentForPackage(pkg) }.getOrNull() ?: return
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

        // 显式投到当前点亮的屏幕；单屏设备 options 为 null，等价于普通 startActivity
        val ok = runCatching { startActivity(target, DisplayPicker.launchOptions(this)) }.isSuccess
        if (!ok) runCatching { startActivity(target) }   // 副屏被拒时退回主屏，总比不启动强
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

        fun intent(context: Context, pkg: String): Intent =
            Intent(context, LaunchProxyActivity::class.java)
                .putExtra(EXTRA_PKG, pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}
