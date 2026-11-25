# -*- coding: utf-8 -*-
"""部署验收：逐项检查，输出 PASS / FAIL 表。

用法：
    python scripts/verify-deployment.py
    python scripts/verify-deployment.py --host root@xbyyb.duckdns.org --key ~/.ssh/ql-vps.pem

设计原则：只读取事实（systemctl / ss / curl / sqlite），不看函数返回值。
「服务说自己是 active」和「端口真的在听」是两回事，两者都查。
"""
import argparse
import json
import os
import subprocess
import sys

CHECKS = []


def ssh(host, key, script, timeout=30):
    cmd = ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10",
           "-i", os.path.expanduser(key), host, script]
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        return (p.stdout or "").strip(), (p.stderr or "").strip(), p.returncode
    except subprocess.TimeoutExpired:
        return "", "timeout", 124


def check(name, ok, detail=""):
    CHECKS.append((name, bool(ok), detail))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="root@xbyyb.duckdns.org")
    ap.add_argument("--key", default="~/.ssh/ql-vps.pem")
    args = ap.parse_args()

    out, err, rc = ssh(args.host, args.key,
                      "systemctl is-active team-desk-board; "
                      "systemctl is-enabled team-desk-board; "
                      "ss -tlnp | grep -c ':8900'; "
                      "curl -s --max-time 6 http://172.18.0.1:8900/api/health")
    if rc != 0 or not out:
        print(f"无法连接或命令失败：{err or out}")
        return 2

    lines = out.splitlines()
    active = lines[0] if lines else ""
    enabled = lines[1] if len(lines) > 1 else ""
    listen = lines[2] if len(lines) > 2 else "0"
    health_raw = "\n".join(lines[3:])

    check("systemd active", active == "active", active)
    check("开机自启", enabled == "enabled", enabled)
    check("监听 8900（网桥地址，未公网暴露）", listen.strip() not in ("0", ""), listen.strip())

    try:
        health = json.loads(health_raw)
    except Exception:
        health = {}
    check("健康检查 ok", health.get("ok") is True)
    check("通讯录同步", (health.get("contacts") or {}).get("ok") == 1,
          str((health.get("contacts") or {}).get("message"))[:60])
    check("审批同步", (health.get("approvals") or {}).get("ok") == 1,
          str((health.get("approvals") or {}).get("message"))[:60])

    # 数据库与考勤
    db_out, _, _ = ssh(args.host, args.key, """python3 - <<'PY'
import sqlite3
c=sqlite3.connect("/var/lib/team-desk-board/board.db")
def one(q):
    try: return c.execute(q).fetchone()
    except Exception as e: return ("ERR", str(e))
print("integrity", one("PRAGMA integrity_check")[0])
print("members", one("SELECT COUNT(*) FROM members")[0])
print("attendance", one("SELECT COUNT(*) FROM attendance_records")[0])
r=one("SELECT business_date,attendance_sync_status,digest_status FROM morning_runs ORDER BY business_date DESC LIMIT 1")
print("morning", r)
PY""")
    db = dict(
        (l.split(" ", 1)[0], l.split(" ", 1)[1] if " " in l else "")
        for l in db_out.splitlines() if " " in l
    )
    check("数据库完整性", db.get("integrity") == "ok", db.get("integrity", ""))
    check("成员数据", db.get("members", "0") not in ("0", ""), db.get("members", ""))
    check("已采集考勤", db.get("attendance", "0") not in ("0", ""), db.get("attendance", ""))
    check("晨间统采记录", "morning" in db, db.get("morning", ""))

    # 访问控制
    ac_out, _, _ = ssh(args.host, args.key,
                       "curl -s -o /dev/null -w '%{http_code} ' --max-time 8 "
                       "https://xbyyb.duckdns.org/api/board; "
                       "curl -s -o /dev/null -w '%{http_code}' --max-time 8 "
                       "https://xbyyb.duckdns.org/board.db")
    codes = ac_out.split()
    check("未登录拒绝访问", codes and codes[0] == "401", codes[0] if codes else "")
    check("数据库不可下载", len(codes) > 1 and codes[1] in ("302", "404"),
          codes[1] if len(codes) > 1 else "")

    # HTTPS 双站点与证书
    tls_out, _, _ = ssh(args.host, args.key,
                        "curl -s -o /dev/null -w '%{http_code}/%{ssl_verify_result} ' "
                        "--max-time 15 https://xbyyb.duckdns.org/api/health; "
                        "curl -s -o /dev/null -w '%{http_code}/%{ssl_verify_result}' "
                        "--max-time 15 https://qlhub.duckdns.org/devices")
    tls = tls_out.split()
    def _ok(pair, code):
        try:
            c, v = pair.split("/")
            return c == code and v == "0"
        except Exception:
            return False
    check("看板 HTTPS + 证书有效", len(tls) > 0 and _ok(tls[0], "200"), tls[0] if tls else "")
    check("QuickLaunch HTTPS 未受影响", len(tls) > 1 and _ok(tls[1], "302"), tls[1] if len(tls) > 1 else "")

    # ql-mailer 与看板的连接
    mail_out, _, _ = ssh(args.host, args.key,
                         "grep -c '^QL_BOARD_URL=http' /etc/qlmail/mailer.env; "
                         "systemctl is-active ql-mailer")
    ml = mail_out.splitlines()
    check("ql-mailer 已接入看板", ml and ml[0] == "1", ml[0] if ml else "")
    check("ql-mailer active", len(ml) > 1 and ml[1] == "active",
          ml[1] if len(ml) > 1 else "")

    width = max(len(n) for n, _, _ in CHECKS) + 2
    print("=" * (width + 40))
    for name, ok, detail in CHECKS:
        flag = "PASS" if ok else "FAIL"
        print(f"{name:<{width}} {flag:<5} {detail}")
    print("=" * (width + 40))
    failed = [n for n, ok, _ in CHECKS if not ok]
    print(f"合计 {len(CHECKS)} 项，失败 {len(failed)} 项")
    if failed:
        print("失败项：" + "、".join(failed))
    return 0 if not failed else 1


if __name__ == "__main__":
    sys.exit(main())
