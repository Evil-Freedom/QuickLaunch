#!/usr/bin/env bash
# QuickLaunch VPS 备份（SQLite + 配置）
#
# 设计要点：
# - **用 sqlite3 .backup 而不是 cp**：数据库处于 WAL 模式，直接 cp 可能拿到
#   只写了一半的状态（主文件与 -wal 不一致）。.backup 走的是 SQLite 自己的
#   一致性快照逻辑。
# - 备份文件 chmod 600：配置包里含管理员口令摘要与会话密钥。
# - 保留 7 份日备 + 4 份周备，超出自动清理。
#
# 安装（部署时已配好 cron，见 deploy 后的 crontab）：
#   0 3 * * * /opt/quicklaunch-vps/backup.sh >> /var/log/ql-backup.log 2>&1
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
BACKUP_DIR="$DIR/backups"
STAMP="$(date +%Y%m%d-%H%M)"

mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"

# ── 1. 数据库一致性备份 ─────────────────────────────────────────────
if docker inspect quicklaunch-vps_api_1 >/dev/null 2>&1; then
    # 用 -c 而不是 `python -` 接 heredoc：脚本本身常常是被 `ssh host '<...>'`
    # 或 `bash -s` 调起来的，那种情况下 stdin 已经被占用，
    # 内层 heredoc 传不进容器 —— 表现为「备份静默失败但脚本继续往下跑」。
    docker exec quicklaunch-vps_api_1 python -c \
        'import os,sqlite3; s=sqlite3.connect(os.environ.get("QL_DB_PATH","/data/quicklaunch.db")); d=sqlite3.connect("/data/_backup_tmp.db"); s.backup(d); d.close(); s.close()'
    docker cp quicklaunch-vps_api_1:/data/_backup_tmp.db "$BACKUP_DIR/db-$STAMP.db" >/dev/null
    docker exec quicklaunch-vps_api_1 rm -f /data/_backup_tmp.db
else
    echo "[$(date '+%F %T')] 容器不存在，跳过数据库备份" >&2
fi

# ── 2. 配置备份（含 .env：口令摘要 + 会话密钥）────────────────────
tar czf "$BACKUP_DIR/conf-$STAMP.tar.gz" -C "$DIR" \
    .env Caddyfile docker-compose.yml Dockerfile requirements.txt app 2>/dev/null

chmod 600 "$BACKUP_DIR"/* 2>/dev/null || true

# ── 3. 周日额外留一份周备 ──────────────────────────────────────────
if [ "$(date +%u)" = "7" ] && [ -f "$BACKUP_DIR/db-$STAMP.db" ]; then
    cp "$BACKUP_DIR/db-$STAMP.db" "$BACKUP_DIR/weekly-db-$(date +%Y%W).db"
    chmod 600 "$BACKUP_DIR"/weekly-*.db
fi

# ── 4. 保留策略 ────────────────────────────────────────────────────
ls -1t "$BACKUP_DIR"/db-*.db 2>/dev/null | tail -n +8 | xargs -r rm -f
ls -1t "$BACKUP_DIR"/conf-*.tar.gz 2>/dev/null | tail -n +8 | xargs -r rm -f
ls -1t "$BACKUP_DIR"/weekly-*.db 2>/dev/null | tail -n +5 | xargs -r rm -f

echo "[$(date '+%F %T')] 备份完成 总量=$(du -sh "$BACKUP_DIR" | cut -f1) 文件数=$(find "$BACKUP_DIR" -type f | wc -l)"
