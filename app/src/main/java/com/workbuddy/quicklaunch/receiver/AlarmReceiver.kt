package com.workbuddy.quicklaunch.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.workbuddy.quicklaunch.data.AppDatabase
import com.workbuddy.quicklaunch.service.LaunchService
import com.workbuddy.quicklaunch.util.Scheduler

/**
 * 定时触发：由 AlarmManager 唤醒，拉起目标 App 并对重复任务重新排程。
 *
 * 可靠性要点：
 * - **先排下一次，再拉起 App**。拉起环节最容易失败（目标被卸载、后台启动被拦、
 *   前台服务受限）。原实现把 launch 放在 reschedule 之前且没有 try/catch，
 *   一次异常就会让这条重复规则**永久失效**（没有下一次闹钟，只能重开 App 才恢复）。
 * - 整个流程交给 [ReceiverWorker]：异常隔离 + 后台线程执行，避免崩溃与 ANR。
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("automation_id", -1)
        if (id < 0) return
        val app = context.applicationContext
        ReceiverWorker.run(this, TAG) { handle(app, id) }
    }

    private fun handle(context: Context, id: Long) {
        val dao = runCatching { AppDatabase.get(context).automationDao() }.getOrNull() ?: return
        val a = runCatching { dao.getById(id) }.getOrNull() ?: return
        if (!a.enabled) return

        if (a.repeatMode != "once") {
            // 先把下一次排上：即使随后的拉起失败，重复链路也不会断
            runCatching { Scheduler.schedule(context, a) }
                .onFailure { Log.e(TAG, "reschedule failed id=$id", it) }
        } else {
            runCatching { dao.update(a.copy(enabled = false)) }   // 一次性任务执行后自动关闭
                .onFailure { Log.e(TAG, "disable once-task failed id=$id", it) }
        }

        runCatching { LaunchService.start(context, a.targetPackage, a.targetAppName) }
            .onFailure { Log.e(TAG, "launch failed pkg=${a.targetPackage}", it) }
    }

    private companion object {
        const val TAG = "AlarmReceiver"
    }
}
