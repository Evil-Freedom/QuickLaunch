#!/usr/bin/env bash
# QuickLaunch VPS 一键部署（Debian 12 / Ubuntu 22.04+）
#
# 用法（在 VPS 上，vps 目录内执行）：
#   sudo bash deploy.sh qlhub.duckdns.org
#
# 它会依次：检查 docker → 校验域名解析 → 生成 .env（口令摘要 + 会话密钥）
# → 起容器 → 等证书签发 → 做健康检查。
#
# 安全约定：
# - 不在命令行接收口令（会留在 shell history），改为交互式输入，只存 SHA-256 摘要
# - .env 权限 600，data/ 归容器用户所有
# - 脚本本身不含任何密钥，可安全提交到仓库
set -euo pipefail

DOMAIN="${1:-}"
if [ -z "$DOMAIN" ]; then
    echo "用法: sudo bash deploy.sh <你的域名>   例如: sudo bash deploy.sh qlhub.duckdns.org"
    exit 1
fi

cd "$(dirname "$0")"

say() { printf '\n\033[1;36m▶ %s\033[0m\n' "$*"; }
die() { printf '\n\033[1;31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

# ── 1. 依赖 ────────────────────────────────────────────────────────
say "检查 Docker"
if ! command -v docker >/dev/null 2>&1; then
    echo "未安装 docker，正在安装…"
    apt-get update -qq
    apt-get install -y -qq docker.io docker-compose-plugin
    systemctl enable --now docker
fi
docker compose version >/dev/null 2>&1 || die "缺少 docker compose 插件：apt install docker-compose-plugin"

# ── 2. 域名解析 ────────────────────────────────────────────────────
say "校验 $DOMAIN 的解析"
PUBLIC_IP="$(curl -fsS -m 10 https://api.ipify.org || true)"
RESOLVED="$(getent hosts "$DOMAIN" | awk '{print $1; exit}' || true)"
echo "  本机公网 IP : ${PUBLIC_IP:-读取失败}"
echo "  $DOMAIN → ${RESOLVED:-解析失败}"
if [ -z "$RESOLVED" ]; then
    echo "  ⚠ 解析为空。Caddy 无法签证书，请先把 A 记录指到本机公网 IP。"
elif [ -n "$PUBLIC_IP" ] && [ "$RESOLVED" != "$PUBLIC_IP" ]; then
    echo "  ⚠ 解析结果与本机公网 IP 不一致。若用了 CDN/代理可忽略，否则证书会签失败。"
fi
echo "  ⚠ 别忘了阿里云安全组放行 TCP 80 与 443 —— Caddy 需要 80 做 HTTP-01 校验。"

# ── 3. .env ───────────────────────────────────────────────────────
if [ -f .env ]; then
    say ".env 已存在，保留原值（如需重填请先备份再删除）"
else
    say "生成 .env"
    printf '设置管理后台口令（输入时不回显）: '
    read -rs ADMIN_PASSWORD
    echo
    [ -n "$ADMIN_PASSWORD" ] || die "口令不能为空"
    HASH="$(printf '%s' "$ADMIN_PASSWORD" | sha256sum | cut -d' ' -f1)"
    SECRET="$(openssl rand -hex 32)"
    cat > .env <<EOF
QL_DOMAIN=$DOMAIN
QL_ADMIN_PASSWORD_SHA256=$HASH
QL_SESSION_SECRET=$SECRET
QL_DB_PATH=/data/quicklaunch.db
EOF
    chmod 600 .env
    echo "  已写入 .env（权限 600）。口令只以 SHA-256 摘要保存，明文不落盘。"
fi

mkdir -p data

# ── 4. 启动 ───────────────────────────────────────────────────────
say "构建并启动容器"
docker compose up -d --build

# ── 5. 等证书与健康检查 ────────────────────────────────────────────
say "等待 HTTPS 证书签发（最多 90 秒）"
for i in $(seq 1 18); do
    sleep 5
    if curl -fsS -m 5 "https://$DOMAIN/healthz" >/dev/null 2>&1; then
        echo "  ✓ HTTPS 可用"
        break
    fi
    if [ "$i" = "18" ]; then
        echo "  ✗ 仍不可用。查看日志定位："
        echo "    docker compose logs --tail=40 caddy"
        echo "    docker compose logs --tail=40 api"
        exit 1
    fi
done

say "完成"
cat <<EOF
管理后台：https://$DOMAIN/login

下一步（必须按顺序）：
  1. 登录 → 打开 /devices → 「生成设备凭据」
     device_id 与 secret **只显示这一次**，立刻填到手机上
  2. 打开 /rules → 改规则 → 点最下面的「发布」 —— 不发布不会下发
  3. 手机：远程访问页 → 「国内服务器规则（VPS）」
     地址填 https://$DOMAIN ，再填 device_id 与 secret
  4. 回到 /devices 看 last_seen_at 是否更新，确认手机已连上

排障：
  docker compose logs -f caddy     # 证书问题
  docker compose ps                # 容器是否在跑
  curl -s https://$DOMAIN/healthz  # 健康检查
备份：整个 data/ 目录拷走即可（SQLite）。
EOF
