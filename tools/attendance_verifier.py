#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Windows 考勤核验端：用 lark-cli 读真实考勤记录，把结论交给 VPS。

职责边界（不要越界）：
* 只读飞书考勤，**不**做任何打卡、修改、补卡；
* 不接触 Android，不执行 Root 命令；
* 只有拿到「真实打卡记录」才上报 success，否则什么都不报（或报 pending）。

判据（缺一不可）：
1. 当天存在 records；
2. `check_in_record_id` 非空（服务器真的写入了记录）；
3. `check_in_result` 不是 Todo/Absent（表示已产生结果）；
4. 记录时间（`check_in_record.check_time`）晚于任务创建时间 - 10 分钟
   —— 防止把「昨天/早上的旧打卡」当成本次任务的证据。

用法：
  python tools/attendance_verifier.py --once
  python tools/attendance_verifier.py --loop 600
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

LARK = "lark-cli.cmd" if os.name == "nt" else "lark-cli"
PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LOCAL_PROPS = os.path.join(PROJECT_ROOT, "local.properties")

# 视为「已产生打卡结果」的取值；Todo=还没打，Absent=缺卡
BAD_RESULTS = {"Todo", "Absent", "NoNeedCheck", ""}


def load_props() -> dict[str, str]:
    props: dict[str, str] = {}
    if os.path.exists(LOCAL_PROPS):
        with open(LOCAL_PROPS, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                k, v = line.split("=", 1)
                props[k.strip()] = v.strip()
    return props


class CliAuthError(RuntimeError):
    """lark-cli 授权失效：必须停下来等人重新登录，不能当成「没查到记录」。"""


AUTH_HINTS = ("unauthorized", "not logged in", "login required", "token expired",
              "invalid token", "auth failed", "please login")


def run_lark(args: list[str], timeout: int = 60) -> dict:
    proc = subprocess.run([LARK, *args], capture_output=True, timeout=timeout)
    if proc.returncode != 0:
        err = proc.stderr.decode("utf-8", "replace")
        low = err.lower()
        if any(h in low for h in AUTH_HINTS):
            raise CliAuthError(f"lark-cli 授权失效：{err[:200]}")
        raise RuntimeError(f"lark-cli 退出码 {proc.returncode}: {err[:300]}")
    try:
        data = json.loads(proc.stdout.decode("utf-8", "replace"))
    except json.JSONDecodeError as exc:
        raise RuntimeError(f"lark-cli 输出不是 JSON: {exc}") from exc
    if not data.get("ok", False):
        raise RuntimeError(f"lark-cli 返回失败: {json.dumps(data, ensure_ascii=False)[:300]}")
    return data


def query_day(day: dt.date) -> list[dict]:
    """查询某天的考勤结果（只读）。"""
    payload = json.dumps({
        "user_ids": [],
        "check_date_from": int(day.strftime("%Y%m%d")),
        "check_date_to": int(day.strftime("%Y%m%d")),
    })
    data = run_lark([
        "attendance", "user_tasks", "query",
        "--params", '{"employee_type":"employee_no"}',
        "--data", payload,
    ])
    out: list[dict] = []
    for entry in (data.get("data") or {}).get("user_task_results") or []:
        for rec in entry.get("records") or []:
            check = rec.get("check_in_record") or {}
            out.append({
                "day": entry.get("day"),
                "employee_name": entry.get("employee_name", ""),
                "user_id": entry.get("user_id", ""),
                "result_id": entry.get("result_id", ""),
                "record_id": rec.get("check_in_record_id") or check.get("record_id") or "",
                "result": rec.get("check_in_result") or check.get("check_result") or "",
                "check_time": check.get("check_time") or "",
                "location": check.get("location_name", ""),
            })
    return out


def pick_evidence(records: list[dict], not_before_ts: int) -> dict | None:
    for r in records:
        if not r["record_id"]:
            continue
        if (r["result"] or "") in BAD_RESULTS:
            continue
        try:
            ts = int(r["check_time"])
        except (TypeError, ValueError):
            continue
        if ts < not_before_ts:
            continue
        return r
    return None


def http_json(url: str, token: str, payload: dict | None = None, timeout: int = 15) -> dict:
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    req = urllib.request.Request(url, data=data, method="POST" if data else "GET")
    req.add_header("Authorization", f"Bearer {token}")
    req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def resolve_base(props: dict[str, str]) -> str:
    base = props.get("ql.verify.base", "").strip().rstrip("/")
    if not base:
        raise RuntimeError("local.properties 缺少 ql.verify.base")
    return base


def verify_once(props: dict[str, str], dry_run: bool = False) -> int:
    token = props.get("ql.verify.token", "").strip()
    if not token:
        raise RuntimeError("local.properties 缺少 ql.verify.token")
    base = resolve_base(props)

    pending = http_json(f"{base}/v1/attendance/pending", token).get("tasks") or []
    if not pending:
        print("没有待核验任务")
        return 0

    handled = 0
    seen: set[str] = set()
    for task in pending:
        task_id = task["task_id"]
        if task_id in seen:      # 同一次运行里不重复提交同一个任务
            continue
        seen.add(task_id)
        created = dt.datetime.fromisoformat(task["created_at"].replace("Z", "+00:00")).astimezone()
        day = created.date()
        not_before = int(created.timestamp()) - 600
        try:
            records = query_day(day)
        except Exception as exc:  # CLI 登录失效/网络问题：不改变任何状态
            print(f"  {task_id[:12]} 查询失败：{exc}")
            continue

        evidence = pick_evidence(records, not_before)
        if evidence is None:
            print(f"  {task_id[:12]} {day} 无有效打卡记录，保持待确认")
            continue

        body = {
            "task_id": task_id,
            "device_id": task["device_id"],
            "attendance_date": day.isoformat(),
            "evidence_source": "lark-cli:attendance.user_tasks.query",
            "evidence_time": dt.datetime.fromtimestamp(int(evidence["check_time"])).strftime("%Y-%m-%d %H:%M:%S"),
            "evidence_ref": evidence["record_id"],
            "device_name": os.environ.get("COMPUTERNAME", "Windows"),
            "result": "success",
        }
        if dry_run:
            print(f"  [dry-run] {task_id[:12]} 证据 {json.dumps(body, ensure_ascii=False)}")
            handled += 1
            continue
        resp = http_json(f"{base}/v1/attendance/verify", token, body)
        print(f"  {task_id[:12]} 核验提交：verified={resp.get('verified')} "
              f"action={resp.get('action_id')} duplicate={resp.get('duplicate')}")
        handled += 1
    return handled


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description="飞书考勤核验（只读）")
    ap.add_argument("--once", action="store_true")
    ap.add_argument("--loop", type=int, metavar="SEC", help="循环间隔秒数")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args(argv)

    props = load_props()
    if args.loop:
        while True:
            try:
                verify_once(props, args.dry_run)
            except CliAuthError as exc:
                # 授权失效不是「没打卡」：明确告警并退避，等人重新登录
                print(f"【需要人工处理】{exc}", file=sys.stderr)
                time.sleep(max(args.loop, 600))
                continue
            except urllib.error.URLError as exc:
                print(f"VPS 不可达，稍后重试：{exc}", file=sys.stderr)
            except Exception as exc:
                print(f"核验循环异常：{exc}", file=sys.stderr)
            time.sleep(args.loop)
    try:
        verify_once(props, args.dry_run)
    except CliAuthError as exc:
        print(f"【需要人工处理】{exc}", file=sys.stderr)
        return 3
    except urllib.error.URLError as exc:
        print(f"无法连接 VPS：{exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
