"""事件 → 邮件标题/正文（HTML）。

为什么发 HTML 而不是纯文本 / Markdown：
平台只做**投递**，不做渲染 —— 实测 `--body-format markdown` 发出去后，
收件箱里看到的是裸的 `**` 和 `-`。所以正文直接由服务端生成结构化 HTML，
列表与加粗必须自己拼。

安全：客户端字段只作为**文本**插入，一律 `html.escape`，且走白名单过滤，
PIN / 密钥这类字段根本不会被渲染。
"""

from __future__ import annotations

import html
import re
from typing import Any

MAX_FIELD = 200

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

ALLOWED_FIELDS = {
    "device_name", "rule_name", "target_app", "planned_time", "random_window",
    "actual_time", "attempts", "display", "unlock_result", "final_state", "last_error",
    "checked_at", "security_state", "auto_disabled", "reason", "suggestion",
    "evidence_source", "evidence_time", "evidence_ref", "note",
    "task_id", "action_id", "action_type", "detail",
    "checkin_result", "location", "employee_name", "attendance_date", "verified_at",
}

_CTRL = re.compile(r"[\x00-\x08\x0b-\x1f\x7f]")

# 飞书考勤结果的常见取值 → 人话
RESULT_LABELS = {
    "Normal": "正常",
    "Late": "迟到",
    "Early": "早退",
    "SeriousLate": "严重迟到",
    "Lack": "缺卡",
    "Absent": "旷工",
}


def clean(value: Any) -> str:
    if value is None:
        return "-"
    text = _CTRL.sub(" ", str(value)).strip()
    if not text:
        return "-"
    return html.escape(text[:MAX_FIELD], quote=False)


def _kv(pairs: list[tuple[str, Any]]) -> str:
    items = "".join(
        f"<li><b>{html.escape(label)}</b>：{clean(value)}</li>" for label, value in pairs
    )
    return f'<ul style="margin:8px 0 8px 16px;padding:0;line-height:1.7">{items}</ul>'


def _p(text: str) -> str:
    return f'<p style="margin:10px 0;line-height:1.7">{text}</p>'


def _foot(detail: str = "") -> str:
    tail = f"{html.escape(detail)}<br/>" if detail else ""
    return f'<p style="margin:16px 0 0;color:#888;font-size:12px">{tail}— QuickLaunch 通知服务</p>'


def render(notification_type: str, payload: dict[str, Any]) -> tuple[str, str]:
    """返回 (subject, html_body)。未知类型抛 ValueError，由调用方拒绝入队。"""
    if notification_type not in SUBJECTS:
        raise ValueError(f"unsupported notification type: {notification_type}")

    data = {k: v for k, v in payload.items() if k in ALLOWED_FIELDS}
    subject = SUBJECTS[notification_type]

    if notification_type == "launch_success":
        body = _p("<b>结论：目标应用已进入前台</b>") + _kv([
            ("设备", data.get("device_name")),
            ("规则名称", data.get("rule_name")),
            ("目标应用", data.get("target_app")),
            ("计划时间", data.get("planned_time")),
            ("随机时间窗口", data.get("random_window")),
            ("实际启动时间", data.get("actual_time")),
            ("启动尝试次数", data.get("attempts")),
            ("当前显示屏", data.get("display")),
            ("自动解锁结果", data.get("unlock_result")),
            ("任务最终状态", data.get("final_state") or "已进入前台"),
        ]) + _foot("启动成功不等于考勤成功，考勤结果由考勤记录单独确认。")

    elif notification_type == "launch_failed":
        body = _p("<b>结论：未能确认目标应用进入前台</b>") + _kv([
            ("设备名称", data.get("device_name")),
            ("目标应用", data.get("target_app")),
            ("计划时间", data.get("planned_time")),
            ("已尝试次数", data.get("attempts")),
            ("最终错误", data.get("last_error")),
            ("最后执行时间", data.get("actual_time")),
        ]) + _p("建议检查：目标 App 是否被系统限制后台；屏幕/折叠状态是否匹配；"
                "如为锁屏拦截，确认自动解锁是否已因失败过多而停用。") + _foot()

    elif notification_type == "unlock_alert":
        body = _p("<b>结论：自动解锁已停止尝试</b>") + _kv([
            ("设备名称", data.get("device_name")),
            ("解锁失败时间", data.get("checked_at")),
            ("当前安全状态", data.get("security_state")),
            ("是否自动停用", data.get("auto_disabled")),
            ("失败原因", data.get("reason")),
        ]) + _foot("本邮件只报告状态，不包含也不传输任何锁屏口令或其等价物。")

    elif notification_type == "checkin_success":
        if not data.get("evidence_source") or not data.get("evidence_time"):
            raise ValueError("checkin_success requires evidence_source and evidence_time")
        raw_result = str(data.get("checkin_result") or "")
        result_text = RESULT_LABELS.get(raw_result, raw_result or "正常")
        suffix = f"（原始值 {clean(raw_result)}）" if raw_result and raw_result not in RESULT_LABELS else ""
        body = (
            _p(f"<b>结论：已核实完成打卡（{html.escape(result_text)}）</b>")
            + _kv([
                ("日期", data.get("attendance_date") or str(data.get("evidence_time"))[:10]),
                ("打卡时间", data.get("evidence_time")),
                ("考勤结果", f"{result_text}{suffix}"),
                ("打卡地点", data.get("location")),
                ("记录 ID", data.get("evidence_ref")),
                ("数据来源", f"飞书考勤只读查询（{data.get('evidence_source')}）"),
                ("员工", data.get("employee_name")),
                ("设备", data.get("device_name")),
                ("核验时间", data.get("verified_at") or data.get("checked_at")),
            ])
            + _foot("结论来自飞书考勤记录本身，不是「App 启动成功」的推断。")
        )

    elif notification_type == "checkin_unknown":
        body = (
            _p("<b>结论：结果未知（尚未取得飞书打卡记录）</b>")
            + _kv([
                ("日期", data.get("attendance_date") or data.get("actual_time")),
                ("已启动时间", data.get("actual_time")),
                ("目标应用", data.get("target_app")),
                ("设备", data.get("device_name")),
                ("待确认原因", data.get("reason") or data.get("note")),
            ])
            + _p("下一步：核验端查到真实打卡记录后会立刻补发「打卡成功」并触发飞书清理；"
                 "30 分钟内仍查不到会再提醒一次，当天最多两条。")
            + _foot("本邮件不代表打卡成功，也不代表失败。")
        )

    elif notification_type == "cleanup_pending":
        body = _p("<b>结论：考勤已核实，等待设备执行清理</b>") + _kv([
            ("设备名称", data.get("device_name")),
            ("任务", data.get("task_id")),
            ("动作", data.get("action_type") or "FORCE_STOP_FEISHU"),
            ("创建时间", data.get("checked_at")),
        ]) + _foot()

    elif notification_type in ("cleanup_success", "cleanup_failed"):
        ok = notification_type == "cleanup_success"
        body = _p("<b>结论：飞书后台已彻底清理</b>" if ok else "<b>结论：飞书清理失败</b>") + _kv([
            ("设备名称", data.get("device_name")),
            ("任务", data.get("task_id")),
            ("动作", data.get("action_type") or "FORCE_STOP_FEISHU"),
            ("结果", data.get("detail")),
            ("完成时间", data.get("checked_at")),
        ]) + (_p("注意：飞书可能仍在后台运行，请检查 Root 授权与进程状态。") if not ok else "") + _foot()

    else:  # 兜底：不该走到这里
        body = _p("<b>结论：状态未知</b>") + _kv([("类型", notification_type)]) + _foot()

    return subject, body
