package com.workbuddy.quicklaunch.launch

/**
 * 一次「把目标 App 拉到前台」任务的生命周期状态。
 *
 * 之所以要一个显式状态机而不是「失败就再试一次」的递归调用：
 * 重试链路横跨 AlarmReceiver、前台服务、透明中转页、Root 探测四个组件，
 * 任何一个组件各自维护一套「还要不要重试」的判断，就会出现两个重试环互相竞争
 * （表现为同一个 App 被连续拉起三四次）。状态只在这里有一份真源。
 *
 * 转移表（→ 表示通过 [LaunchAttemptEngine] 的某个事件到达）：
 *
 * ```
 * PENDING      --onLaunchIssued-->  LAUNCHING
 * PENDING      --(启动前探测已在前台)-->  SUCCESS
 * LAUNCHING    --onTick(到首次验证时刻)-->  VERIFYING
 * VERIFYING    --onProbeResult(命中连续 N 次)-->  SUCCESS
 * VERIFYING    --onTick(验证窗口耗尽)-->  RETRY_WAIT / FAILED
 * RETRY_WAIT   --onTick(到达重试时刻)-->  PENDING（attemptIndex 已 +1）
 * 任意状态      --cancel-->  CANCELLED
 * 任意状态      --onTick(超过总时限)-->  FAILED / MANUAL_REQUIRED
 * ```
 *
 * [MANUAL_REQUIRED] 与 [FAILED] 的区别：前者表示「重试已用尽但用户仍可一键再试」，
 * 后者表示「重试都没机会发生」（如目标包不存在、规则被取消）。
 * 分开是为了通知文案与是否给「再次启动」按钮能各自决定。
 */
enum class LaunchAttemptState {
    PENDING,
    LAUNCHING,
    VERIFYING,
    RETRY_WAIT,
    SUCCESS,
    FAILED,
    CANCELLED,
    MANUAL_REQUIRED;

    /**
     * 状态机不会再自行推进的状态。
     *
     * [MANUAL_REQUIRED] 也算终态：它表示自动重试已用尽，后续只能由用户
     * 显式新建一次任务（新 attemptId），当前这条不能再被 tick 驱动。
     */
    val isTerminal: Boolean
        get() = this == SUCCESS || this == FAILED || this == CANCELLED || this == MANUAL_REQUIRED
}
