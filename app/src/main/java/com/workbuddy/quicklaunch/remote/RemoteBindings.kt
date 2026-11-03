package com.workbuddy.quicklaunch.remote

import android.content.Context
import com.workbuddy.quicklaunch.data.AppDatabase
import com.workbuddy.quicklaunch.data.Automation
import com.workbuddy.quicklaunch.data.RuleException
import com.workbuddy.quicklaunch.util.HolidayChecker
import com.workbuddy.quicklaunch.util.RuleBackupStore
import com.workbuddy.quicklaunch.util.Scheduler

/**
 * 生产实现：把 Room 与 Scheduler 适配成远程层依赖的两个接口。
 *
 * 单独放在这个文件里，是为了让 `remote` 包里除本文件外的所有类都不 import
 * Android 的 Context / Room / Scheduler —— 它们因而是纯 JVM 可测的。
 */
class RoomRuleStore(private val context: Context) : RuleStore {

    private val db get() = AppDatabase.get(context)

    override fun getAll(): List<Automation> = db.automationDao().getAll()

    override fun getById(id: Long): Automation? = db.automationDao().getById(id)

    override fun update(a: Automation) = db.automationDao().update(a)

    override fun exceptionsOf(ruleId: Long): List<RuleException> =
        db.ruleExceptionDao().getByRule(ruleId)

    override fun addException(e: RuleException) = db.ruleExceptionDao().upsert(e)

    override fun removeException(ruleId: Long, date: String) =
        db.ruleExceptionDao().delete(ruleId, date)
}

/**
 * 生产实现：转调既有的 [Scheduler]。
 *
 * 注意这里**没有**任何自己的排程逻辑 —— 远程与本地必须走同一份调度代码，
 * 否则「同样一条规则，本地改完和远程改完下次触发时间不同」这类 bug
 * 会以最难查的方式出现。
 */
class SchedulerHook(private val context: Context) : ScheduleHook {

    override fun cancel(a: Automation) = Scheduler.cancel(context, a)

    override fun schedule(a: Automation, exceptions: Set<String>) =
        Scheduler.schedule(context, a, holidays = null, exceptions = exceptions)
}

object RemoteBindings {

    /**
     * 每个请求构造一个薄壳即可：无状态、持有 Context 引用、开销可忽略。
     * 不缓存成单例是为了避免应用被杀后残留一个指向旧进程的实例。
     */
    fun api(context: Context): RemoteRuleApi {
        val app = context.applicationContext
        return RemoteRuleApi(
            store = RoomRuleStore(app),
            hook = SchedulerHook(app),
            // 与本地编辑一致：改之前留一份旧值，用户在本地点「撤销」能还原远程改动
            backup = { RuleBackupStore.save(app, it) },
            holidays = { HolidayChecker.fromDb(app) }
        )
    }

    /**
     * 构造中继轮询器。
     *
     * ⚠️ `RelayCommandExecutor` **必须在这里建一次并跨轮次复用**：
     * 它内部持有 nonce 缓存，若每轮新建，重放保护就等于没有
     * （上一轮执行过的指令，下一轮会被当成新指令再执行一次）。
     */
    fun relayPoller(context: Context): RelayPoller {
        val app = context.applicationContext
        val executor = RelayCommandExecutor(api = { api(app) })
        return RelayPoller(
            settings = { RemoteConfig.relaySettings(app) },
            clients = { RemoteConfig.clients(app) },
            transport = RelayHttpTransport,
            executor = { executor },
            audit = { client, detail ->
                RemoteConfig.audit(app, client, "relay", "RELAY", detail)
            }
        )
    }
}
