"""事件 → 邮件标题/正文。

规则：
* 标题固定（需求 §六 逐字给出），不掺入事件 id 之类的东西；
* 正文只由**服务端模板**生成，客户端传来的只是字段值，且经过清洗（去控制字符、截断）——
  邮件正文永远不可能变成 shell 命令或模板代码；
* 绝不渲染 PIN、哈希、KeyStore 别名等敏感字段：payload 白名单渲染，多余字段直接丢弃。
"""

from __future__ import annotations

import re
from typing import Any

MAX_FIELD = 200

# 事件类型 → 固定标题
SUBJECTS = {
    "launch_success": "[QuickLaunch] 自动启动成功",
    "launch_failed": "[QuickLaunch] 自动启动失败",
    "unlock_alert": "[QuickLaunch] 自动解锁安全告警",
    "checkin_success": "[QuickLaunch] 打卡成功",
    "checkin_unknown": "[QuickLaunch] 打卡结果待确认",
    "cleanup_pending": "[QuickLaunch] 飞书清理待执行",
    "cleanup_success": "[QuickLaunch] 飞书后台已清理",
    "cleanup_failed": "[QuickLaunch] 飞书清理失败",
}

# payload 里允许出现的字段（白名单）。任何 PIN/密钥字段都不可能被渲染。
ALLOWED_FIELDS = {
    "device_name", "rule_name", "target_app", "planned_time", "random_window",
    "actual_time", "attempts", "display", "unlock_result", "final_state", "last_error",
    "checked_at", "security_state", "auto_disabled", "reason", "suggestion",
    "evidence_source", "evidence_time", "evidence_ref", "note",
    "task_id", "action_id", "action_type", "detail", "checked_at",
}

_CTRL = re.compile(r"[\x00-\x08\x0b-\x1f\x7f]")


def clean(value: Any) -> str:
    if value is None:
        return "-"
    text = _CTRL.sub(" ", str(value)).strip()
    if not text:
        return "-"
    return text[:MAX_FIELD]


def _line(label: str, value: Any) -> str:
    return f"{label}：{clean(value)}"


def render(notification_type: str, payload: dict[str, Any]) -> tuple[str, str]:
    """返回 (subject, body)。未知类型抛 ValueError，由调用方拒绝入队。"""
    if notification_type not in SUBJECTS:
        raise ValueError(f"unsupported notification type: {notification_type}")

    data = {k: v for k, v in payload.items() if k in ALLOWED_FIELDS}
    subject = SUBJECTS[notification_type]

    if notification_type == "launch_success":
        lines = [
            _line("设备", data.get("device_name")),
            _line("规则名称", data.get("rule_name")),
            _line("目标应用", data.get("target_app")),
            _line("计划时间", data.get("planned_time")),
            _line("随机时间窗口", data.get("random_window")),
            _line("实际启动时间", data.get("actual_time")),
            _line("启动尝试次数", data.get("attempts")),
            _line("当前显示屏", data.get("display")),
            _line("自动解锁结果", data.get("unlock_result")),
            _line("任务最终状态", data.get("final_state") or "已进入前台"),
        ]
    elif notification_type == "launch_failed":
        lines = [
            _line("设备名称", data.get("device_name")),
            _line("目标应用", data.get("target_app")),
            _line("计划时间", data.get("planned_time")),
            _line("已尝试次数", data.get("attempts")),
            _line("最终错误", data.get("last_error")),
            _line("最后执行时间", data.get("actual_time")),
            "",
            "建议检查：",
            "1. 目标 App 是否仍在设备上、是否被系统限制后台；",
            "2. 屏幕/折叠状态与目标显示屏是否匹配；",
            "3. 如为锁屏拦截，确认自动解锁是否已因失败过多而停用。",
        ]
    elif notification_type == "unlock_alert":
        lines = [
            _line("设备名称", data.get("device_name")),
            _line("解锁失败时间", data.get("checked_at")),
            _line("当前安全状态", data.get("security_state")),
            _line("是否自动停用", data.get("auto_disabled")),
            _line("失败原因", data.get("reason")),
            "",
            "说明：本邮件只报告状态，不包含也不传输任何锁屏口令或其等价物。",
        ]
    elif notification_type == "checkin_success":
        # 必须有可信证据：没有来源与时间就不渲染成功邮件
        if not data.get("evidence_source") or not data.get("evidence_time"):
            raise ValueError("checkin_success requires evidence_source and evidence_time")
        lines = [
            _line("设备名称", data.get("device_name")),
            _line("目标应用", data.get("target_app")),
            _line("打卡时间", data.get("evidence_time")),
            _line("证据来源", data.get("evidence_source")),
            _line("记录标识", data.get("evidence_ref")),
            _line("任务状态", "已核验"),
        ]
    elif notification_type == "cleanup_pending":
        lines = [
            _line("设备名称", data.get("device_name")),
            _line("任务", data.get("task_id")),
            _line("动作", data.get("action_type") or "FORCE_STOP_FEISHU"),
            _line("创建时间", data.get("checked_at")),
            "",
            "说明：考勤已核实成功，等待设备拉取并执行清理指令。",
        ]
    elif notification_type in ("cleanup_success", "cleanup_failed"):
        lines = [
            _line("设备名称", data.get("device_name")),
            _line("任务", data.get("task_id")),
            _line("动作", data.get("action_type") or "FORCE_STOP_FEISHU"),
            _line("结果", data.get("detail")),
            _line("完成时间", data.get("checked_at")),
        ]
        if notification_type == "cleanup_failed":
            lines += ["", "注意：飞书可能仍在后台运行，请检查 Root 授权与进程状态。"]
    else:  # checkin_unknown
        lines = [
            _line("设备名称", data.get("device_name")),
            _line("目标应用", data.get("target_app")),
            _line("已启动时间", data.get("actual_time")),
            _line("待确认原因", data.get("reason") or data.get("note")),
            "",
            "结论：目标应用已启动，但尚未取得可信的打卡记录，请人工确认。",
            "本邮件不代表打卡成功或失败。",
        ]

    body = "\n".join(lines).strip() + "\n\n— QuickLaunch 通知服务"
    return subject, body
