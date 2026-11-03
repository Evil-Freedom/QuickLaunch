#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从飞书「值班表」读取排班，转成 QuickLaunch 的 daily_shifts 并下发。

与 shift_sync.py 的区别（两者是**两个不同数据源**，不要混用）：
  shift_sync.py  读飞书考勤打卡接口 user_tasks —— 权威但只能看到「明天」，
                 未来列范围取决于公司排班发布进度（实测只到 11-06）。
  sheet_shift.py 读人工维护的值班表 —— 能排到年底，但只记录「谁值班」，
                 班次时刻需外部给定（本项目固定 09:00-18:00）。

值班表结构（一张日历，按月分块，列固定为 周日..周六）：
    [row=189] 11月                                  ← 月份标题（可能出现在任意列）
    [row=190] 1,2,3,4,5,6,7                         ← 日期行
    [row=191] 顾羽翛,顾羽翛,"姜芃\n顾羽翛",...       ← 姓名行（与日期行严格配对）
一格可能是：单人姓名 / "甲\\n乙"（两人同日）/ 休 / \\（占位空）

年份不在每块里显式标注，靠顺序递增推断：跨过 12月 自动进下一年的 1月。
脚本用「日期数字 vs 列对应星期」做交叉校验 —— 推断错了会大面积不匹配，
因此这个校验是年份正确性的主要保障，不能被跳过。
"""

import argparse
import csv
import io
import json
import os
import re
import shutil
import subprocess
import sys
from datetime import date, datetime

# ── 固定参数 ────────────────────────────────────────────────────────
DEFAULT_SPREADSHEET = "BfXPsqS9ah3pY6tUxU8cYVejnKd"  # 值班表
DEFAULT_SHEET = "2025年6月起"
DEFAULT_WHO = "姜芃"
DEFAULT_START = (2025, 6)  # 表格标题「2025年6月起」

# 用户明确说明：所有班次时间固定 09:00–18:00，值班表不区分时段
SHIFT_IN_MIN = 9 * 60
SHIFT_OUT_MIN = 18 * 60

WEEK_NAMES = ["周日", "周一", "周二", "周三", "周四", "周五", "周六"]
MONTH_RE = re.compile(r"^(?:(\d{2,4})年)?(\d{1,2})月$")
DATE_RE = re.compile(r"^(\d{1,2})\s*(?:[（(]([^）)]*)[）)])?$")


# ── 定位 node 与 lark-cli ───────────────────────────────────────────
def _node_bin():
    """找到 node 可执行文件。

    Windows 下 lark-cli 是个**没有扩展名**的 sh 包装脚本，
    subprocess 直接执行会 WinError 2；必须穿透到 `node run.js`。
    """
    p = shutil.which("node") or shutil.which("node.exe")
    if p:
        return p
    base = os.path.expanduser("~/.workbuddy/binaries/node")
    for cand in (os.path.join(base, "current", "node.exe"),
                 os.path.join(base, "current", "node")):
        if os.path.exists(cand):
            return cand
    versions = os.path.join(base, "versions")
    if os.path.isdir(versions):
        for v in sorted(os.listdir(versions), reverse=True):
            for name in ("node.exe", "node"):
                c = os.path.join(versions, v, name)
                if os.path.exists(c):
                    return c
    return None


def _lark_cmd():
    """返回可执行的 lark-cli 命令前缀（['node', run.js]、['lark-cli'] 或 .cmd）。"""
    env = os.environ.get("LARK_CLI_JS")
    if env and os.path.exists(env):
        return [_node_bin() or "node", env]
    run_js = os.path.expanduser(
        "~/.workbuddy/binaries/node/cli-connector-packages/"
        "node_modules/@larksuite/cli/scripts/run.js"
    )
    if os.path.exists(run_js):
        node = _node_bin()
        if node:
            return [node, run_js]
    exe = shutil.which("lark-cli") or shutil.which("lark-cli.cmd")
    if exe:
        return [exe]
    raise SystemExit("找不到 lark-cli，也无法定位 node run.js")


# ── 读取飞书表格 ────────────────────────────────────────────────────
def fetch_csv(spreadsheet: str, sheet: str, rng: str) -> str:
    """调 lark-cli 取一段区域的 CSV（带 [row=N] 行号前缀）。"""
    cmd = _lark_cmd() + [
        "sheets", "+csv-get",
        "--spreadsheet", spreadsheet,
        "--range", f"{sheet}!{rng}",
    ]
    p = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8")
    if p.returncode != 0:
        raise SystemExit(f"lark-cli 调用失败：{p.stderr[:400]}")
    try:
        d = json.loads(p.stdout)
    except json.JSONDecodeError:
        raise SystemExit(f"lark-cli 返回不是 JSON：{p.stdout[:300]}")
    if not d.get("ok"):
        err = d.get("error") or {}
        raise SystemExit(f"读取表格失败：{err.get('code')} {err.get('message')}")
    return d["data"]["annotated_csv"]


def parse_rows(annotated_csv: str):
    """把 annotated_csv 解析成 [(rowNo, [cell...]), ...]。

    单元格内含换行（"姜芃\\n顾羽翛"），所以必须交给 csv 模块处理，
    splitlines() 会把一格切成两行，导致姓名行错位。
    """
    out = []
    reader = csv.reader(io.StringIO(annotated_csv))
    for rec in reader:
        if not rec:
            continue
        first = rec[0]
        m = re.match(r"^\[row=(\d+)\]\s?(.*)$", first, re.S)
        if not m:
            continue
        row_no = int(m.group(1))
        cells = [m.group(2)] + list(rec[1:])
        # 表格右侧常有整列为空的填充，裁掉尾部空串便于判断
        while cells and cells[-1].strip() == "":
            cells.pop()
        out.append((row_no, cells))
    return out


# ── 解析成「日期 → 值班人集合」 ──────────────────────────────────────
def is_month_header(cells):
    """判断是否月份标题行：恰有一个非空单元格且形如「7月」/「26年1月」。

    注意月份标题的列位置是随意的（表格按日历排版，标题只是浮在某一列），
    不能假设它在 A 列。
    """
    texts = [c.strip() for c in cells if c.strip()]
    if len(texts) != 1:
        return None
    m = MONTH_RE.match(texts[0])
    if not m:
        return None
    year = int(m.group(1)) if m.group(1) else None
    if year is not None and year < 100:  # 「26年」= 2026
        year += 2000
    return year, int(m.group(2))


def parse_sheet(rows, start=(2025, 6)):
    """返回 ({(y,m,d): set(names)}, {(y,m,d): note}, warnings)。

    note 是日期格里括号里的备注（在家班 / 公司班 / 休 …）。
    它不影响班次时刻，但决定**这天要不要触发** —— 「家里上班可以外勤打卡」，
    在家班不必按点启动，所以必须保留而不是丢掉。
    """
    shifts = {}
    notes = {}
    warnings = []

    year, month = start
    cur_year, cur_month = year, month
    started = False

    i = 0
    n = len(rows)
    while i < n:
        row_no, cells = rows[i]
        hdr = is_month_header(cells)

        if hdr is not None:
            exp_year, exp_month = hdr
            if not started:
                # 第一块直接用标题里的月份（年份来自表格标题或显式标注）
                cur_month = exp_month
                if exp_year is not None:
                    cur_year = exp_year
                started = True
            else:
                # 顺序递增：正常前进一个月，跨 12 月进下一年
                nxt_month = cur_month + 1
                nxt_year = cur_year + (1 if nxt_month > 12 else 0)
                if nxt_month > 12:
                    nxt_month = 1
                # 标题里若显式带年份（「26年1月」）则以它为准并对齐校验
                if exp_year is not None:
                    if (nxt_year, nxt_month) != (exp_year, exp_month):
                        # 显式标注优先，同时记录偏差（表格可能跳月）
                        warnings.append(
                            f"[row={row_no}] 月份标注 {exp_year}-{exp_month:02d} 与推断 "
                            f"{nxt_year}-{nxt_month:02d} 不一致，以标注为准"
                        )
                    cur_year, cur_month = exp_year, exp_month
                else:
                    if exp_month != nxt_month:
                        warnings.append(
                            f"[row={row_no}] 月份标题为 {exp_month}月，推断应为 {nxt_month}月，"
                            f"以推断为准"
                        )
                    cur_year, cur_month = nxt_year, nxt_month
            i += 1
            continue

        # 日期行判定：至少一个单元格是 1..31 的数字，且没有任何人名
        date_cells = []
        for col, c in enumerate(cells):
            t = c.strip()
            m = DATE_RE.match(t)
            if m and 1 <= int(m.group(1)) <= 31:
                date_cells.append((col, int(m.group(1)), (m.group(2) or "").strip()))

        looks_like_names = any("姜芃" in c or "顾羽翛" in c for c in cells)
        if not date_cells or looks_like_names:
            i += 1
            continue

        # 下一行必须是姓名行
        if i + 1 >= n:
            warnings.append(f"[row={row_no}] 日期行后面没有姓名行，跳过")
            i += 1
            continue
        name_row_no, name_cells = rows[i + 1]

        for col, day, note in date_cells:
            # 交叉校验：该日期在当月是否真的落在这一列对应的星期
            # 这是年份推断错误的唯一可靠信号（错了会大面积报警）
            try:
                dt = date(cur_year, cur_month, day)
            except ValueError:
                warnings.append(f"[row={row_no}] {cur_year}-{cur_month:02d}-{day} 不存在，跳过")
                continue
            expect_col = (dt.weekday() + 1) % 7  # Mon=0 → 周一列=1
            if expect_col != col:
                warnings.append(
                    f"[row={row_no}] {dt} 应为{WEEK_NAMES[expect_col]}（第 {expect_col} 列），"
                    f"实际在第 {col} 列，疑似月份/年份错位，已跳过"
                )
                continue

            who = (name_cells[col] if col < len(name_cells) else "").strip()
            names = set()
            for part in re.split(r"[\n、,，/]+", who):
                p = re.sub(r"[（(].*$", "", part).strip()  # 去掉「（上午半天休息）」等备注
                if p in ("", "\\", "休"):
                    continue
                names.add(p)
            if names:
                shifts[dt] = names
                if note:
                    notes[dt] = note
        i += 2

    return shifts, notes, warnings


def load_shifts(spreadsheet, sheet, start, rng="A1:J260"):
    rows = parse_rows(fetch_csv(spreadsheet, sheet, rng))
    if not rows:
        raise SystemExit("表格读取为空，检查 spreadsheet / sheet 名称")
    return parse_sheet(rows, start)


# ── 命令 ────────────────────────────────────────────────────────────
def skip_reason(d, note, who):
    """这天要不要触发规则。返回跳过原因，None 表示需要触发。

    用户口径（2026-11-03）：
    - 「周末不用启动」→ 周六、周日一律不排，即使表上有班
    - 「家里上班可以外勤打卡」→ 备注为在家班的日子不排（不用赶点打卡）
    公司班与未标注的普通工作日照常触发。

    备注里另有「休 / 公司休」（日期格写 `4（休）`）表示当天休息，
    虽然姓名格里仍有人名，同样不该触发；「年会」「班」按上班处理。
    """
    if d.weekday() >= 5:  # Mon=0 … Sat=5, Sun=6
        return WEEK_NAMES[(d.weekday() + 1) % 7]
    if note:
        if "在家" in note:
            return "在家班"
        if "休" in note:
            return "休息日"
    return None


def cmd_show(args):
    shifts, notes, warns = load_shifts(args.spreadsheet, args.sheet, _start(args))
    if not shifts:
        raise SystemExit("没解析出任何日期，检查表格结构是否变化")

    dates = sorted(shifts)
    print(f"数据源：飞书值班表 {args.spreadsheet}")
    print(f"解析范围：{dates[0]} ~ {dates[-1]}（共 {len(dates)} 天有值班记录）")

    if warns:
        print(f"\n⚠️  {len(warns)} 条校验提示（前 8 条）：")
        for w in warns[:8]:
            print("   " + w)
    else:
        print("✅ 日期与星期列完全吻合，年份推断正确")

    mine = [d for d in dates if args.who in shifts[d]]
    print(f"\n{'=' * 46}")
    print(f"{args.who} 值班：{len(mine)} 天 / 共 {len(dates)} 天")
    today = date.today()
    upcoming = [d for d in mine if d >= today]
    fire = [d for d in upcoming if skip_reason(d, notes.get(d), args.who) is None]
    print(f"今天起 {len(upcoming)} 天，其中会触发规则：{len(fire)} 天"
          f"（跳过周末 {sum(1 for d in upcoming if d.weekday() >= 5)} 天，"
          f"在家班 {sum(1 for d in upcoming if d.weekday() < 5 and '在家' in (notes.get(d) or ''))} 天）")
    print(f"{'=' * 46}")

    # 按月分组打印
    cur = None
    for d in mine:
        if (d.year, d.month) != cur:
            cur = (d.year, d.month)
            print(f"\n{cur[0]} 年 {cur[1]:02d} 月：")
        partner = shifts[d] - {args.who}
        tag = f"  与 {'、'.join(sorted(partner))} 同行" if partner else ""
        note = notes.get(d) or ""
        note_s = f"  [{note}]" if note else ""
        mark = "  ← 今天" if d == today else ""
        skip = skip_reason(d, note, args.who)
        state = f"  ✗ 不触发（{skip}）" if skip else ""
        print(f"  {d} {WEEK_NAMES[(d.weekday() + 1) % 7]}  09:00-18:00{note_s}{tag}{state}{mark}")
    return 0


def _start(args):
    if args.start:
        y, m = args.start.split("-")
        return int(y), int(m)
    return DEFAULT_START


def build_payload(shifts, notes, who, d_from, d_to):
    out = []
    for d in sorted(shifts):
        if d_from and d < d_from:
            continue
        if d_to and d > d_to:
            continue
        if who not in shifts[d]:
            continue
        if skip_reason(d, notes.get(d), who):
            continue
        out.append({
            "date": d.isoformat(),
            "inMin": SHIFT_IN_MIN,
            "outMin": SHIFT_OUT_MIN,
            "source": "sheet",
        })
    return out


def _client_path():
    return os.path.join(os.path.dirname(os.path.abspath(__file__)), "ql_client.js")


def _call_client(*args):
    """调 ql_client.js，返回解析后的 JSON。"""
    p = subprocess.run(
        [(_node_bin() or "node"), _client_path(), *args],
        capture_output=True, text=True, encoding="utf-8",
    )
    if p.returncode != 0:
        raise SystemExit(f"手机端调用失败：{p.stdout[:400]}{p.stderr[:400]}")
    try:
        return json.loads(p.stdout)
    except json.JSONDecodeError:
        raise SystemExit(f"手机端返回无法解析：{p.stdout[:400]}")


def _existing_dates(d_from, d_to):
    d = _call_client("shifts", "get", "--from", d_from.isoformat())
    out = set()
    for s in d.get("shifts", []):
        try:
            dt = date.fromisoformat(s["date"])
        except Exception:
            continue
        if d_to and dt > d_to:
            continue
        out.add(dt)
    return out


def _runs(days):
    """把日期列表压成连续区间 [(a,b)...] —— 逐天删会产生几十次请求。"""
    out = []
    for d in sorted(days):
        if out and (d - out[-1][1]).days == 1:
            out[-1] = (out[-1][0], d)
        else:
            out.append((d, d))
    return out


def cmd_push(args):
    shifts, notes, warns = load_shifts(args.spreadsheet, args.sheet, _start(args))
    d_from = date.fromisoformat(args.date_from) if args.date_from else date.today()
    d_to = date.fromisoformat(args.date_to) if args.date_to else None

    payload = build_payload(shifts, notes, args.who, d_from, d_to)
    if not payload:
        raise SystemExit("选定区间内没有可下发的值班日，检查 --who / --from / --to")

    if warns:
        print(f"⚠️  {len(warns)} 条校验提示，已跳过对应日期")

    # 说明被排除的日子：值班表上有班，但用户口径是「不启动」
    all_days = [d for d in shifts
                if args.who in shifts[d]
                and (not d_from or d >= d_from) and (not d_to or d <= d_to)]
    excl = [(d, skip_reason(d, notes.get(d), args.who)) for d in sorted(all_days)]
    excl = [(d, r) for d, r in excl if r]

    want = {date.fromisoformat(x["date"]) for x in payload}
    print(f"下发范围：{payload[0]['date']} ~ {payload[-1]['date']}（{len(payload)} 天）")
    print(f"班次时刻：{SHIFT_IN_MIN // 60:02d}:{SHIFT_IN_MIN % 60:02d}"
          f"-{SHIFT_OUT_MIN // 60:02d}:{SHIFT_OUT_MIN % 60:02d}")
    if excl:
        wk = sum(1 for _, r in excl if r in ("周六", "周日"))
        hm = sum(1 for _, r in excl if r == "在家班")
        print(f"已排除 {len(excl)} 天：周末 {wk} 天、在家班 {hm} 天（这些日子不排班，规则不启动）")
        print("  排除清单：" + "、".join(f"{d:%m-%d}{r}" for d, r in excl[:12])
              + (" …" if len(excl) > 12 else ""))

    # 严格镜像：表上没有的未来日必须从手机里摘掉。
    # 否则上一版同步（比如来自考勤接口）留下的「表上已取消的班」会一直生效，
    # 表现为「明明那天不上班，闹钟还是响了」。
    extra = []
    ok_have = False
    if args.replace:
        try:
            have = _existing_dates(d_from, d_to)
            ok_have = True
        except SystemExit:
            print("⚠️  读不到手机现有班表（远程未开启？），跳过清理步骤")
            have = set()
        extra = sorted(have - want)
    else:
        have = set()

    if extra:
        print(f"\n需清理 {len(extra)} 天（手机里有、但按规则不该触发）：")
        for a, b in _runs(extra):
            print(f"  - {a}" + ("" if a == b else f" ~ {b}"))
    elif ok_have:
        print("\n无需清理（手机班表与值班表一致）")

    if args.dry_run:
        print("\n--dry-run，未实际下发。载荷预览：")
        print(json.dumps({"shifts": payload[:5]}, ensure_ascii=False, indent=2))
        print(f"... 共 {len(payload)} 条")
        return 0

    for a, b in _runs(extra):
        _call_client("shifts", "del", "--from", a.isoformat(), "--to", b.isoformat())
    if extra:
        print(f"已清理 {len(extra)} 天")

    tmp = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tmp_shifts.json")
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump({"shifts": payload, "pruneBefore": d_from.isoformat()}, f, ensure_ascii=False)
    try:
        r = _call_client("shifts", "put", "--file", tmp)
    finally:
        if os.path.exists(tmp):
            os.remove(tmp)

    print(f"\n下发完成：upserted={r.get('upserted')} pruned={r.get('pruned')} "
          f"total={r.get('total')}")
    return 0


def cmd_check(args):
    """对照值班表与班次数据库，找出差异日。"""
    shifts, notes, _ = load_shifts(args.spreadsheet, args.sheet, _start(args))
    d = _call_client("shifts", "get")
    have = {s["date"] for s in d.get("shifts", [])}

    today = date.today()
    # 只有「该触发」的日子才应该出现在手机里：周末与在家班按口径排除
    want = {dt.isoformat() for dt in shifts
            if args.who in shifts[dt] and dt >= today
            and skip_reason(dt, notes.get(dt), args.who) is None}
    missing = sorted(want - have)
    extra = sorted(have - want)
    print(f"应触发日：{len(want)} 天 | 手机已有：{len(have)} 天")
    print(f"缺失（该有、手机没有）：{len(missing)} 天")
    for x in missing[:15]:
        print("  -", x)
    if extra:
        print(f"多余（手机有、不该触发）：{len(extra)} 天")
        for x in extra[:15]:
            print("  +", x)
    if not missing and not extra:
        print("✅ 两边完全一致")
    return 0


def main():
    p = argparse.ArgumentParser(
        description="从飞书值班表读取排班并下发到 QuickLaunch",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=(
            "示例：\n"
            "  python QuickLaunch/tools/sheet_shift.py show\n"
            "  python QuickLaunch/tools/sheet_shift.py push --dry-run\n"
            "  python QuickLaunch/tools/sheet_shift.py push\n"
            "  python QuickLaunch/tools/sheet_shift.py check\n"
        ),
    )
    p.add_argument("--spreadsheet", default=DEFAULT_SPREADSHEET)
    p.add_argument("--sheet", default=DEFAULT_SHEET)
    p.add_argument("--who", default=DEFAULT_WHO, help=f"值班表里的人名，默认 {DEFAULT_WHO}")
    p.add_argument("--start", help="表格首个月的 YYYY-MM，默认 2025-06")

    sp = p.add_subparsers(dest="cmd", required=True)

    s = sp.add_parser("show", help="打印值班日并做星期校验")
    s.set_defaults(func=cmd_show)

    s = sp.add_parser("push", help="下发到手机（手机需开着远程访问）")
    s.add_argument("--from", dest="date_from", help="起始日期，默认今天")
    s.add_argument("--to", dest="date_to", help="结束日期，默认表尾")
    s.add_argument("--dry-run", action="store_true")
    s.add_argument("--no-replace", dest="replace", action="store_false",
                   help="只增补，不摘掉手机里表上已不存在的日期")
    s.set_defaults(func=cmd_push, replace=True)

    s = sp.add_parser("check", help="对照手机已有班表，找缺失日")
    s.set_defaults(func=cmd_check)

    args = p.parse_args()
    sys.exit(args.func(args))


if __name__ == "__main__":
    main()
