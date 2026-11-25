"""VPS 侧直接读飞书考勤（lark-cli，只读）。

判据与 Windows 核验端**完全相同**，避免两个节点得出不同结论：

1. 当天有 records；
2. `check_in_record_id` 非空（服务器真的写入了记录）；
3. `check_in_result` 不是 Todo / Absent；
4. 打卡时间不早于任务时间 - 10 分钟（防止拿旧打卡当本次证据）。

任何一条不满足就返回 None —— 调用方必须按「结果未知」处理，不能当成功。
"""

from __future__ import annotations

import json
import logging
import os
import subprocess
from dataclasses import dataclass

log = logging.getLogger("qlmail.lark")

BAD_RESULTS = {"Todo", "Absent", "NoNeedCheck", ""}
CLOCK_TOLERANCE_S = 600


@dataclass
class Evidence:
    date: str
    record_id: str
    check_time: int
    result: str
    location: str
    employee_name: str

    @property
    def check_time_text(self) -> str:
        import datetime as dt
        return dt.datetime.fromtimestamp(self.check_time).strftime("%Y-%m-%d %H:%M:%S")


class LarkClient:
    """薄封装：跑一次 CLI、解析 JSON、挑出证据。"""

    def __init__(self, cmd: list[str] | None = None, timeout_s: int = 15, home: str = "/var/lib/qlmail"):
        self.cmd = cmd or ["/usr/local/bin/lark-cli"]
        self.timeout_s = timeout_s
        self.home = home

    # ── 底层 ────────────────────────────────────────────────────────

    def _run(self, day: str) -> dict | None:
        args = [
            *self.cmd, "attendance", "user_tasks", "query",
            "--params", '{"employee_type":"employee_no"}',
            "--data", json.dumps({
                "user_ids": [],
                "check_date_from": int(day.replace("-", "")),
                "check_date_to": int(day.replace("-", "")),
            }),
        ]
        try:
            env = dict(os.environ)
            env.update({"PATH": "/usr/local/bin:/usr/bin:/bin", "HOME": self.home, "LANG": "C.UTF-8"})
            proc = subprocess.run(args, capture_output=True, timeout=self.timeout_s, env=env)
        except subprocess.TimeoutExpired:
            log.warning("lark-cli 超时 day=%s", day)
            return None
        except OSError as exc:
            log.warning("lark-cli 无法执行：%s", exc)
            return None

        raw = proc.stdout.decode("utf-8", "replace")
        try:
            payload = json.JSONDecoder().raw_decode(raw)[0]
        except ValueError:
            log.warning("lark-cli 输出不是 JSON（exit=%s）：%s", proc.returncode, raw[:200])
            return None
        if not payload.get("ok"):
            log.warning("lark-cli 返回失败：%s", json.dumps(payload.get("error", {}), ensure_ascii=False)[:300])
            return None
        return payload

    # ── 对外 ────────────────────────────────────────────────────────

    def lookup(self, day: str, not_before_ts: int) -> Evidence | None:
        payload = self._run(day)
        if payload is None:
            return None
        for entry in (payload.get("data") or {}).get("user_task_results") or []:
            for rec in entry.get("records") or []:
                check = rec.get("check_in_record") or {}
                record_id = rec.get("check_in_record_id") or check.get("record_id") or ""
                result = rec.get("check_in_result") or check.get("check_result") or ""
                if not record_id or result in BAD_RESULTS:
                    continue
                try:
                    ts = int(check.get("check_time") or 0)
                except (TypeError, ValueError):
                    continue
                if ts and ts < not_before_ts - CLOCK_TOLERANCE_S:
                    continue
                return Evidence(
                    date=day,
                    record_id=record_id,
                    check_time=ts,
                    result=result,
                    location=str(check.get("location_name") or ""),
                    employee_name=str(entry.get("employee_name") or ""),
                )
        return None
