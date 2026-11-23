#!/usr/bin/env bash
# QuickLaunch VPS 健康巡检
#
# 每 30 分钟跑一次，正常只写一行 OK，异常写 ERROR/WARN 并以非零码退出。
# 刻意不引入 Prometheus / Uptime Kuma：2 核 2G 上多一个常驻服务
# 换来的只是「更漂亮的图」，而这里需要的只是「出问题能查到」。
#
#    */30 * * * * /opt/quicklaunch-vps/healthcheck.sh
set -uo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
LOG="/var/log/ql-health.log"
DOMAIN="${QL_DOMAIN:-qlhub.duckdns.org}"
FAIL=0

say() { echo "[$(date '+%F %T')] $*" >>"$LOG"; }

# ── 容器状态 ───────────────────────────────────────────────────────
for c in quicklaunch-vps_api_1 quicklaunch-vps_caddy_1; do
    st="$(docker inspect -f '{{.State.Status}}' "$c" 2>/dev/null || echo missing)"
    if [ "$st" != "running" ]; then
        say "ERROR 容器 $c 状态=$st"
        FAIL=1
    fi
done

# ── HTTP 健康 ──────────────────────────────────────────────────────
code="$(curl -s -o /dev/null -m 10 -w '%{http_code}' "https://$DOMAIN/healthz" 2>/dev/null || echo 000)"
if [ "$code" != "200" ]; then
    say "ERROR /healthz 返回 $code"
    FAIL=1
fi

# ── 证书剩余天数 ──────────────────────────────────────────────────
exp="$(echo | timeout 10 openssl s_client -connect 127.0.0.1:443 -servername "$DOMAIN" 2>/dev/null \
    | openssl x509 -noout -enddate 2>/dev/null | cut -d= -f2)"
if [ -n "$exp" ]; then
    days=$(( ( $(date -d "$exp" +%s) - $(date +%s) ) / 86400 ))
    if [ "$days" -lt 14 ]; then
        say "WARN 证书剩余 ${days} 天（Caddy 会自动续期，持续如此需检查 80 端口）"
        FAIL=1
    fi
else
    say "WARN 读不到证书有效期"
    FAIL=1
fi

# ── 磁盘 ──────────────────────────────────────────────────────────
use="$(df --output=pcent / | tail -1 | tr -dc '0-9')"
if [ "${use:-0}" -gt 85 ]; then
    say "WARN 磁盘使用 ${use}%"
    FAIL=1
fi

# ── 内存 ──────────────────────────────────────────────────────────
avail="$(free -m | awk '/^Mem:/{print $7}')"
if [ "${avail:-0}" -lt 100 ]; then
    say "WARN 可用内存 ${avail}MB"
    FAIL=1
fi

if [ "$FAIL" -eq 0 ]; then
    say "OK 容器正常 / HTTPS 200 / 证书 ${days:-?} 天 / 磁盘 ${use}% / 可用内存 ${avail}MB"
fi

# 日志自限：超过 2000 行截断保留最后 1000 行
if [ -f "$LOG" ] && [ "$(wc -l <"$LOG")" -gt 2000 ]; then
    tail -1000 "$LOG" >"$LOG.tmp" && mv "$LOG.tmp" "$LOG"
fi

exit $FAIL
