#!/usr/bin/env bash
# 运营部看板：VPS 侧部署（Debian 12）
#
# 用法（root）：
#   bash deploy-board.sh /tmp/board-app.tgz
#
# 幂等：只覆盖代码，不动数据库与配置；
# board.env 仅在不存在时生成（密钥不因重部署而变化）。
set -euo pipefail

PKG="${1:-/tmp/board-app.tgz}"
APP=/opt/team-desk-board
DATA=/var/lib/team-desk-board
CONF=/etc/team-desk-board
LOGS=/var/log/team-desk-board
UNIT=/etc/systemd/system/team-desk-board.service

say() { printf '\n\033[1;36m▶ %s\033[0m\n' "$*"; }
die() { printf '\n\033[1;31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

[ -f "$PKG" ] || die "找不到安装包：$PKG"
[ "$(id -u)" = "0" ] || die "需要 root"

say "用户与目录"
id statusboard >/dev/null 2>&1 || useradd --system --create-home \
    --home-dir "$DATA" --shell /usr/sbin/nologin statusboard
mkdir -p "$APP" "$DATA" "$CONF" "$LOGS"
chown statusboard:statusboard "$DATA" "$LOGS"
chmod 750 "$DATA" "$LOGS"

say "停服务（避免半旧半新的代码在跑）"
systemctl stop team-desk-board 2>/dev/null || true

say "展开代码"
rm -rf "${APP:?}"/*
tar xzf "$PKG" -C "$APP"
chown -R root:statusboard "$APP"
find "$APP" -type d -exec chmod 755 {} \;
find "$APP" -type f -exec chmod 644 {} \;

say "运行环境（已存在则跳过，不覆盖密钥）"
if [ ! -f "$CONF/board.env" ]; then
    cat > "$CONF/board.env" <<EOF
HOST=127.0.0.1
PORT=8900
BOARD_DB=$DATA/board.db
BOARD_CONFIG=$CONF/config.json
BOARD_LOG=$LOGS/server.log
QL_INTERNAL_TOKEN=$(openssl rand -hex 32)
EOF
    chown root:statusboard "$CONF/board.env"
    chmod 640 "$CONF/board.env"
    echo "  已生成新密钥：记得把它填进 /etc/qlmail/mailer.env 的 QL_BOARD_TOKEN"
else
    echo "  已存在，保留原密钥"
fi

say "systemd 单元"
cat > "$UNIT" <<'EOF'
[Unit]
Description=Team Desk Board - 运营部状态看板（统一考勤采集 + 晨间日报）
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=0

[Service]
Type=simple
User=statusboard
Group=statusboard
WorkingDirectory=/opt/team-desk-board
EnvironmentFile=/etc/team-desk-board/board.env
Environment=PYTHONUNBUFFERED=1
ExecStart=/usr/bin/python3 /opt/team-desk-board/server.py
Restart=always
RestartSec=5
KillSignal=SIGTERM
TimeoutStopSec=20
NoNewPrivileges=yes
PrivateTmp=yes
ProtectSystem=strict
ProtectHome=yes
ReadWritePaths=/var/lib/team-desk-board /var/log/team-desk-board
LimitNOFILE=4096

[Install]
WantedBy=multi-user.target
EOF

say "启动"
systemctl daemon-reload
systemctl enable team-desk-board >/dev/null
systemctl start team-desk-board
sleep 6

systemctl is-active --quiet team-desk-board || {
    journalctl -u team-desk-board -n 30 --no-pager
    die "服务未起来"
}

say "健康检查"
curl -fsS --max-time 6 http://127.0.0.1:8900/api/health | head -c 300
echo
say "完成（监听 127.0.0.1:8900，未暴露公网）"
