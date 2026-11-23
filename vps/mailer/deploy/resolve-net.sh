#!/bin/sh
# 以 root 运行：把 quicklaunch 的 docker 网桥网关写进 /run/qlmail/bind。
# ql-mailer 以 qlmair 运行且不在 docker 组里（那等于 root），所以由这里代取。
set -eu

GW=$(docker network inspect quicklaunch-vps_ql -f '{{(index .IPAM.Config 0).Gateway}}' 2>/dev/null || true)
if [ -z "${GW:-}" ]; then
    GW=127.0.0.1
fi

install -d -m 755 /run/qlmail
printf '%s\n' "$GW" > /run/qlmail/bind
chmod 644 /run/qlmail/bind
