#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
飞书班表 -> QuickLaunch 触发时刻 的桥接工具（零依赖，只用标准库 + 本机 lark-cli）。

## 为什么走「打卡记录」而不是「排班表」接口

飞书的排班表接口 `attendance/v1/user_daily_shifts/query` 被标为**仅自建应用**，
权限名是「导出打卡数据」，必须由企业管理员在考勤管理后台配置数据权限范围。
普通员工拿不到，所以不能作为个人工具的依赖。

但打卡记录接口 `attendance/v1/user_tasks/query` 只需要 `attendance:task:readonly`
（用户自己在授权页就能给），而它的每条记录里带：

  check_in_shift_time   // 班次「应到」时刻（epoch 秒）
  check_out_shift_time  // 班次「应走」时刻（epoch 秒）

这就是排班结果本身 —— 官方文档明确说 records 是「用户维度的排班结果」。
所以：排班权限拿不到，但排班结果能拿到。

## 两个必须知道的边界

1. **查询起点上限是「明天」**：`check_date_from` 超过明天会报
   `dateFrom ... is not in [20190701, YYYYMMDD]`。往前查历史不受限（试过 31 天）。
2. **未来能看多远取决于公司排班发布进度**，不是固定窗口。实测 2026-11-03 当天
   只排到 11-06，11-09（下周一）还没排。所以同步必须**滚动重跑**，不能一次拉一个月。

## 无排班的含义

某一天不在 `user_task_results` 里 = 那天没有班次 = 休息。这比法定节假日准得多：
轮班制的人「今天上不上班」由班表决定，不由国务院通知决定。

用法：
  python QuickLaunch/tools/shift_sync.py show                    # 打印过去 7 天 + 未来可见的班表
  python QuickLaunch/tools/shift_sync.py show --past 14 --future 14
  python QuickLaunch/tools/shift_sync.py plan --rule 1 --offset -30   # 算「班次前 30 分钟」，只打印不执行
  python QuickLaunch/tools/shift_sync.py plan --rule 1 --anchor out --offset 10
"""

import argparse
import datetime as dt
import json
import os
import subprocess
import sys

# 脚本在 QuickLaunch/tools/ 下，仓库根是它的父目录
WORKDIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
QL_CLIENT = os.path.join(WORKDIR, "QuickLaunch", "tools", "ql_client.js")
# lark-cli 在 Windows 上是 .cmd，直接 exec 需要 shell=False + 完整名
LARK = "lark-cli.cmd" if os.name == "nt" else "lark-cli"

ANCHOR_LABEL = {"in": "上班", "out": "下班"}


def run_lark(args):
    """调 lark-cli 并解析 JSON。失败抛 RuntimeError（带可读原因）。"""
    try:
        p = subprocess.run(
            [LARK] + args,
            capture_output=True,
            cwd=WORKDIR,
            shell=(os.name == "nt"),
        )
    except FileNotFoundError:
        raise RuntimeError("找不到 lark-cli，请确认飞书连接器已安装并在 PATH 里")

    out = p.stdout.decode("utf-8", "replace").strip()
    if not out:
        raise RuntimeError(f"lark-cli 无输出（exit={p.returncode}）：{p.stderr.decode('utf-8', 'replace')[:300]}")
    try:
        data = json.loads(out)
    except json.JSONDecodeError:
        raise RuntimeError(f"lark-cli 输出不是 JSON：{out[:300]}")

    if not data.get("ok"):
        err = data.get("error") or {}
        raise RuntimeError(f"飞书接口失败 {err.get('code')}: {err.get('message')}")
    return data.get("data") or {}


def fetch_range(date_from, date_to):
    """
    拉一个日期区间的班表，返回 {date: [ {in_ts, out_ts, name} ... ]}。

    user_ids 传空数组：按 skill 约定代表「查我自己」，也避免误查他人（无权限会整体报错）。
    """
    payload = json.dumps(
        {
            "user_ids": [],
            "check_date_from": int(date_from.strftime("%Y%m%d")),
            "check_date_to": int(date_to.strftime("%Y%m%d")),
        }
    )
    data = run_lark(
        [
            "attendance", "user_tasks", "query",
            "--params", '{"employee_type":"employee_no"}',
            "--data", payload,
        ]
    )

    result = {}
    for entry in data.get("user_task_results") or []:
        day = str(entry.get("day"))
        if len(day) != 8:
            continue
        key = f"{day[:4]}-{day[4:6]}-{day[6:]}"
        rows = []
        for rec in entry.get("records") or []:
            cin = rec.get("check_in_shift_time")
            cout = rec.get("check_out_shift_time")
            # 0 / None 都表示当天没有排班（例如整天请假），跳过
            if not cin or int(cin) <= 0:
                continue
            rows.append(
                {
                    "in_ts": int(cin),
                    "out_ts": int(cout) if cout and int(cout) > 0 else None,
                    "name": entry.get("employee_name") or "",
                }
            )
        if rows:
            result[key] = rows
    return result


def fetch_shifts(past_days=7, future_days=14):
    """历史 + 未来两段拉取后合并。未来段起点固定为今天（不能更晚，接口限制）。"""
    today = dt.date.today()
    shifts = {}

    # 历史段：起点不受限，但没必要拉太长
    if past_days > 0:
        shifts.update(fetch_range(today - dt.timedelta(days=past_days), today))

    # 未来段：from 必须是今天或明天，to 可以往后伸（超出已排范围的部分不会返回条目）
    shifts.update(fetch_range(today, today + dt.timedelta(days=future_days)))
    return shifts


def fmt_ts(ts):
    return dt.datetime.fromtimestamp(ts).strftime("%m-%d %H:%M")


def shift_kind(row):
    """给班次一个便于扫读的标签：跨天 / 白班 / 短班。"""
    if not row.get("out_ts"):
        return "无下班"
    span = (row["out_ts"] - row["in_ts"]) / 3600.0
    if span >= 20:
        return f"跨天{span:.0f}h"
    if span <= 0:
        return "数据异常"
    return f"{span:.0f}h"


def cmd_show(args):
    shifts = fetch_shifts(args.past, args.future)
    if not shifts:
        print("没有拉到任何班次（检查飞书授权是否含 attendance:task:readonly）")
        return 1

    today = dt.date.today()
    print(f"{'日期':<12}{'星期':<6}{'上班':<14}{'下班':<16}{'班次':<10}备注")
    print("-" * 76)
    upcoming = []
    for date in sorted(shifts):
        d = dt.date.fromisoformat(date)
        wd = "一二三四五六日"[d.weekday()]
        for row in shifts[date]:
            mark = ""
            if d == today:
                mark = "← 今天"
            elif d > today:
                mark = f"{(d - today).days} 天后"
            if d >= today:
                upcoming.append((date, d, row))
            print(
                f"{date:<12}周{wd:<5}{fmt_ts(row['in_ts']):<14}"
                f"{(fmt_ts(row['out_ts']) if row.get('out_ts') else '-'):<16}"
                f"{shift_kind(row):<10}{mark}"
            )

    # 休息日提示：区间内没有班次的日期
    if shifts:
        first, last = min(shifts), max(shifts)
        span = [
            (dt.date.fromisoformat(first) + dt.timedelta(days=i))
            for i in range((dt.date.fromisoformat(last) - dt.date.fromisoformat(first)).days + 1)
        ]
        rests = [d for d in span if d.isoformat() not in shifts and d >= today]
        if rests:
            print("\n休息（无班次）: " + "、".join(d.strftime("%m-%d") for d in rests))

    if upcoming:
        nxt = upcoming[0]
        print(f"\n下一次班次: {nxt[0]}（{ANCHOR_LABEL['in']} {fmt_ts(nxt[2]['in_ts'])}）")
    else:
        print("\n⚠ 未来没有已发布的班次 —— 公司还没排班，同步会拿不到目标时刻")
    print(f"可见范围: {min(shifts)} ~ {max(shifts)}（受公司排班发布进度限制，需滚动重跑）")
    return 0


def pick_target(shifts, anchor, offset_min, now=None):
    """
    选出「下一次班次」，并算出目标触发时刻。

    只取 now 之后的目标时刻：例如 offset=-30 而今天 09:00 的班次已经过了
    08:30 这个点，就应该顺延到下一个班次，而不是返回一个过去的时间点。
    """
    now = now or dt.datetime.now()
    key = "in_ts" if anchor == "in" else "out_ts"
    candidates = []
    for date in sorted(shifts):
        for row in shifts[date]:
            ts = row.get(key)
            if not ts:
                continue
            target = dt.datetime.fromtimestamp(ts) + dt.timedelta(minutes=offset_min)
            if target > now:
                candidates.append((target, date, row))
    candidates.sort(key=lambda x: x[0])
    return candidates[0] if candidates else None


def cmd_plan(args):
    shifts = fetch_shifts(args.past, 14)
    if not shifts:
        print("没有拉到任何班次")
        return 1

    hit = pick_target(shifts, args.anchor, args.offset)
    if not hit:
        print("未来没有可用班次，无法算目标时刻")
        return 1

    target, date, row = hit
    key = "in_ts" if args.anchor == "in" else "out_ts"
    print(f"班次日      : {date}（{ANCHOR_LABEL[args.anchor]} {fmt_ts(row[key])}）")
    print(f"偏移        : {args.offset:+d} 分钟")
    print(f"目标触发时刻: {target.strftime('%Y-%m-%d %H:%M')}")
    print()
    print("对应远程指令（需手机开启远程访问且与本机同网/中继在线）：")
    print(
        f"  node QuickLaunch/tools/ql_client.js patch --rule {args.rule} "
        f"--hour {target.hour} --minute {target.minute}"
    )
    if args.push:
        cmd = [
            "node", QL_CLIENT, "patch",
            "--rule", str(args.rule),
            "--hour", str(target.hour),
            "--minute", str(target.minute),
        ]
        print("\n>>> 执行推送 ...")
        p = subprocess.run(cmd, cwd=WORKDIR, capture_output=True)
        out = (p.stdout + p.stderr).decode("utf-8", "replace").strip()
        print(out)
        return 0 if p.returncode == 0 else 1
    return 0


def to_shift_row(date, row):
    """
    飞书时间戳 -> QuickLaunch 的班表行（当天分钟数）。

    跨天班的表达方式：outMin 直接累加 span 分钟，超过 1440 就自然表示「次日」。
    例如 05:00 上班、次日 05:00 下班 → inMin=300, outMin=1740。
    """
    in_dt = dt.datetime.fromtimestamp(row["in_ts"])
    in_min = in_dt.hour * 60 + in_dt.minute

    if row.get("out_ts"):
        out_dt = dt.datetime.fromtimestamp(row["out_ts"])
        span_min = int((out_dt - in_dt).total_seconds() // 60)
        out_min = in_min + span_min
    else:
        # 飞书没给下班时刻（例如当天还没排下班）时按 8 小时兜底，
        # 保证 outMin != inMin（接口会拒绝两者相等，那代表时长为 0）
        out_min = in_min + 480

    if out_min <= in_min:
        out_min = in_min + 480
    if out_min > 2879:
        out_min = 2879

    return {"date": date, "inMin": in_min, "outMin": out_min, "source": "feishu"}


def cmd_push(args):
    """
    把飞书班表下发到手机（App 端建了班表表之后的主路径）。

    与 [cmd_plan] 的区别：
    - push 只写班表，不改规则时刻 —— 规则由用户在 App 里设成「跟随班表」，
      之后每天几点触发都由班表自动决定，脚本断了也不会停摆；
    - plan 是旧路径（改规则的全局时刻），只适合临时用一下。
    """
    shifts = fetch_shifts(0, args.future)
    if not shifts:
        print("没有拉到任何班次，未下发")
        return 1

    rows = []
    for date in sorted(shifts):
        for row in shifts[date]:
            rows.append(to_shift_row(date, row))
    if not rows:
        print("班表为空，未下发")
        return 1

    payload = {"shifts": rows}
    if args.prune_days > 0:
        payload["pruneBefore"] = (
            dt.date.today() - dt.timedelta(days=args.prune_days)
        ).isoformat()

    tmp_file = os.path.join(WORKDIR, "tools", "tmp_shifts.json")
    with open(tmp_file, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)

    print(f"准备下发 {len(rows)} 天班次（{rows[0]['date']} ~ {rows[-1]['date']}）")
    for r in rows:
        print(
            f"  {r['date']}  {r['inMin'] // 60:02d}:{r['inMin'] % 60:02d} - "
            f"{r['outMin'] // 60:02d}:{r['outMin'] % 60:02d}"
            + ("（次日）" if r["outMin"] >= 1440 else "")
        )

    if args.dry_run:
        print(f"\n[dry-run] JSON 已写到 {tmp_file}，未下发")
        return 0

    cmd = ["node", QL_CLIENT, "shifts", "put", "--file", tmp_file]
    if args.host:
        cmd += ["--host", args.host]
    p = subprocess.run(cmd, cwd=WORKDIR, capture_output=True)
    out_text = (p.stdout + p.stderr).decode("utf-8", "replace").strip()
    print()
    print(out_text)
    if p.returncode != 0:
        print("\n下发失败。检查：手机远程访问是否开启、是否与手机同网（或中继在线）")
        return 1
    try:
        os.remove(tmp_file)
    except OSError:
        pass
    print("\n下发成功。App 里把规则的「跟随班表触发」打开即可按班次启动。")
    return 0


def main():
    ap = argparse.ArgumentParser(description="飞书班表 -> QuickLaunch 触发时刻")
    sub = ap.add_subparsers(dest="cmd", required=True)

    s = sub.add_parser("show", help="打印班表")
    s.add_argument("--past", type=int, default=7)
    s.add_argument("--future", type=int, default=14)
    s.set_defaults(func=cmd_show)

    p = sub.add_parser("plan", help="计算目标触发时刻（默认只打印）")
    p.add_argument("--rule", type=int, required=True, help="QuickLaunch 规则 id")
    p.add_argument("--anchor", choices=["in", "out"], default="in", help="锚定上班还是下班")
    p.add_argument("--offset", type=int, default=-30, help="相对班次的分钟偏移，负数=提前")
    p.add_argument("--past", type=int, default=7, help="历史天数（只影响判断，不影响目标）")
    p.add_argument("--push", action="store_true", help="真的下发给手机")
    p.set_defaults(func=cmd_plan)

    u = sub.add_parser("push", help="把班表下发给手机（App 端班表模式的主路径）")
    u.add_argument("--future", type=int, default=14, help="往后拉多少天（受公司排班进度限制）")
    u.add_argument("--prune-days", type=int, default=7, help="清理多少天前的历史班表，0=不清理")
    u.add_argument("--host", default=None, help="临时覆盖手机 IP")
    u.add_argument("--dry-run", action="store_true", help="只生成 JSON，不下发")
    u.set_defaults(func=cmd_push)

    args = ap.parse_args()
    try:
        return args.func(args)
    except RuntimeError as e:
        print(f"错误: {e}")
        return 2


if __name__ == "__main__":
    sys.exit(main())
