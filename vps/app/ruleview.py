"""
规则展示层的格式化。

**与 Android `AutomationAdapter.triggerLabel` 逐字一致** —— 用户在手机上看到的
「定时 08:00（每天）·跳假」，在网页上也必须一字不差，否则两边描述同一件事
却是两种说法，用户会怀疑是不是两条不同的规则。

放在服务端算而不是模板里拼：模板里拼字符串会把同一句话分散到 HTML 与 Python
两处，改一处忘另一处就会出现「手机写每天、网页写 daily」这种不一致。
"""

from __future__ import annotations

from typing import Any

DAYS = ["日", "一", "二", "三", "四", "五", "六"]

# 与 Android 的 ShiftAnchor 常量一致
ANCHOR_FIXED = 0
ANCHOR_CLOCK_IN = 1
ANCHOR_CLOCK_OUT = 2


def custom_days_label(mask: int) -> str:
    """星期位图 → 「自定义(一三五)」。bit0=周日，与 Android 完全一致。"""
    if not mask:
        return "自定义"
    picks = "".join(DAYS[i] for i in range(7) if (mask >> i) & 1)
    return f"自定义({picks})" if picks else "自定义"


def repeat_label(rule: dict[str, Any]) -> str:
    mode = rule.get("repeatMode", "daily")
    if mode == "daily":
        return "每天"
    if mode == "weekdays":
        return "工作日"
    if mode == "weekend":
        return "周末"
    if mode == "custom":
        return custom_days_label(int(rule.get("repeatDays", 0) or 0))
    return "一次"


def shift_label(rule: dict[str, Any]) -> str:
    """班表模式的可读串，例如「上班 · 提前 30 分钟」。"""
    anchor = int(rule.get("shiftAnchor", 0) or 0)
    off = int(rule.get("shiftOffsetMin", 0) or 0)
    base = "上班" if anchor == ANCHOR_CLOCK_IN else "下班"
    if off == 0:
        return base
    return f"{base} · {'提前' if off < 0 else '延后'} {abs(off)} 分钟"


def trigger_label(rule: dict[str, Any]) -> str:
    tt = rule.get("triggerType", "TIME")
    if tt == "TIME":
        if int(rule.get("shiftAnchor", 0) or 0) != ANCHOR_FIXED:
            return f"班表 {shift_label(rule)}"
        hour = int(rule.get("timeHour", 0) or 0)
        minute = int(rule.get("timeMinute", 0) or 0)
        label = f"定时 {hour:02d}:{minute:02d}（{repeat_label(rule)}）"
        if rule.get("skipHolidays"):
            label += "·跳假"
        if rule.get("randomWindow"):
            label += "·随机"
        return label
    if tt == "CHARGING":
        return "充电时"
    if tt == "WIFI":
        name = rule.get("wifiName") or ""
        return f"连接 WiFi:{name}" if name else "连接 WiFi"
    name = rule.get("bluetoothName") or ""
    return f"连接蓝牙:{name}" if name else "连接蓝牙"


def display_name(rule: dict[str, Any]) -> str:
    """卡片标题：优先规则名，其次目标 App 名，最后用 id 兜底。"""
    return (
        (rule.get("name") or "").strip()
        or (rule.get("targetAppName") or "").strip()
        or f"规则 #{rule.get('id', '?')}"
    )


def rule_view(rule: dict[str, Any]) -> dict[str, Any]:
    """给模板用的视图对象：原始字段 + 预先算好的展示串。"""
    v = dict(rule)
    v["label"] = trigger_label(rule)
    v["title"] = display_name(rule)
    v["app"] = (rule.get("targetAppName") or rule.get("targetPackage") or "").strip()
    v["desc"] = f"{v['label']} → {v['app']}" if v["app"] else v["label"]
    v["repeat_text"] = repeat_label(rule)
    v["mask"] = int(rule.get("repeatDays", 0) or 0)
    v["hour"] = int(rule.get("timeHour", 0) or 0)
    v["minute"] = int(rule.get("timeMinute", 0) or 0)
    v["time_text"] = f"{v['hour']:02d}:{v['minute']:02d}"
    # Jinja2 没有位运算，星期的选中状态必须在这里算好
    v["day_on"] = [bool((v["mask"] >> i) & 1) for i in range(7)]
    v["win_start_text"] = _hhmm(int(rule.get("windowStartMin", 0) or 0))
    v["win_end_text"] = _hhmm(int(rule.get("windowEndMin", 0) or 0))
    return v


def _hhmm(total_min: int) -> str:
    total_min = max(0, min(24 * 60 - 1, total_min))
    return f"{total_min // 60:02d}:{total_min % 60:02d}"
