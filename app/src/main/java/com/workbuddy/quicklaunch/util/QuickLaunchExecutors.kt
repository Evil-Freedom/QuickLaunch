package com.workbuddy.quicklaunch.util

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 应用级线程池单例。
 * 避免 Activity/Fragment 中定义静态 Executor 导致生命周期泄漏。
 */
object QuickLaunchExecutors {
    /** 单线程执行器，用于规则保存等串行任务。 */
    val save: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ql-save").apply { isDaemon = true }
    }

    /** 通用 IO 线程池，用于数据库/网络/文件操作。 */
    val io: ExecutorService = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "ql-io").apply { isDaemon = true }
    }
}
