package com.workbuddy.quicklaunch.remote

/**
 * 远程写入后通知本机界面刷新的广播。
 *
 * 为什么用包内广播而不是 LiveData / Flow：
 * 项目现有数据流是「同步 DAO + 手动 refreshRules()」，为了一个刷新点引入
 * 响应式架构属于改造整个界面层。包内广播的语义刚好是「数据变了，正在看的人刷新一下」，
 * 且能跨 Activity / Fragment 生命周期，不需要持有任何引用。
 *
 * 注册方必须用 `ContextCompat.RECEIVER_NOT_EXPORTED`：Android 14+ 动态注册
 * 非系统广播时不声明导出属性会直接抛 SecurityException。
 */
object RemoteEvents {
    const val ACTION_RULES_CHANGED = "com.workbuddy.quicklaunch.action.RULES_CHANGED"
}
