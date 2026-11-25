# 运营部看板：VPS 部署与验收

> 2026-11-25 完成迁移。Windows 本地实例已停止，生产实例唯一在 VPS。

## 1. 目标形态

| 项 | 值 |
|---|---|
| 主机 | 阿里云上海 VPS，Debian 12.10，x86_64 |
| 连接 | `xbyyb.duckdns.org`（仅作 SSH 地址，**不是**看板访问域名） |
| 服务 | `team-desk-board.service`（systemd，User=statusboard） |
| 监听 | `172.18.0.1:8900`（docker 网桥网关，仅宿主与容器可达，不暴露公网） |
| 代码 | `/opt/team-desk-board`（root:statusboard，只读） |
| 数据 | `/var/lib/team-desk-board/board.db`（600，可写） |
| 配置 | `/etc/team-desk-board/config.json`（root:statusboard 640） |
| 运行环境 | `/etc/team-desk-board/board.env`（640） |
| 日志 | `/var/log/team-desk-board/server.log` + journald |
| 依赖 | Python 3.11 标准库，零第三方包，零外部 CLI |

与既有服务的关系：`ql-mailer.service` / `qlmail-net.service` / Caddy 容器
**均未改动监听端口**，只把 `QLMAIL_LARK` 改为 0（考勤改由看板提供）。

## 2. 首次部署

### 2.1 本地打包上传

```powershell
# 在 QuickLaunch 仓库根目录
.\scripts\deploy-board.ps1 -BoardDir "C:\Users\Administrator\WorkBuddy\2026-10-29-16-23-19\team-desk-board"
```

脚本做的事：跑单测 → 打包（排除 `.git` / `board.db` / `config.json` / `logs`）
→ scp 到 `/tmp/board-app.tgz`。**不含任何凭据**。

### 2.2 VPS 侧

```bash
sudo bash scripts/deploy-board.sh /tmp/board-app.tgz
```

幂等：重复执行只会覆盖代码，不动数据库与配置；`board.env` 只在不存在时生成。

### 2.3 首次数据迁移（只做一次）

```bash
# 1) 停 Windows 侧的 StatusBoard 计划任务，确认 8900 端口释放
# 2) 用 sqlite3 backup API 生成一致性快照（不要直接拷正在写的库）
python -c "import sqlite3;s=sqlite3.connect('board.db');d=sqlite3.connect('backup.db');s.backup(d)"
# 3) 上传后落位
install -o statusboard -g statusboard -m 600 backup.db /var/lib/team-desk-board/board.db
install -o root -g statusboard -m 640 config.json /etc/team-desk-board/config.json
```

**单实例铁律**：`refresh_token` 是一次性的。Windows 与 VPS 同时跑，先刷新的那个
会把另一个的令牌作废，表现为「另一个实例的授权莫名失效」。所以必须
「停旧的 → 迁数据 → 启新的」，不能并行观察。

## 3. 验收清单（2026-11-25 实测）

| 项目 | 命令 | 结果 |
|---|---|---|
| 服务状态 | `systemctl is-active team-desk-board` | `active` |
| 开机自启 | `systemctl is-enabled team-desk-board` | `enabled` |
| 崩溃重启 | `systemctl kill -s SIGKILL` → 5s 后 | 自动拉起 `active` |
| 监听范围 | `ss -tlnp \| grep 8900` | `127.0.0.1:8900` |
| 健康检查 | `curl 127.0.0.1:8900/api/health` | `ok:true`，contacts/approvals 均 `ok:1` |
| 未登录访问 | `curl -o /dev/null -w %{http_code} /api/board` | `401` |
| 数据库不可下载 | `curl /board.db` | `302`（跳登录，不返回内容） |
| 配置不可下载 | `curl /config.json` | `302` |
| 数据持久化 | 重启后 `attendance_records` / `morning_runs` | 记录仍在 |
| 通讯录同步 | health.contacts | `同步 11 人（群成员）` |
| 审批同步 | health.approvals | `新增 0 条，跳过 232 条` |
| 考勤采集 | `attendance.sync()` | `team 取到 1 条`，真实 `record_id` |
| 晨间统采 | `morning_runs` | `attempt=1`，`attendance_sync_status=ok` |
| 日报不重复 | `digest_status` | `skipped`（迁移日旧机制已推送） |
| 服务间签名 | `BoardClient.self_evidence()` | 返回真实记录；错 token → 401 |

## 4. HTTPS 入口（已完成）

```caddyfile
xbyyb.duckdns.org {
	encode zstd gzip
	reverse_proxy 172.18.0.1:8900
	header {
		X-Content-Type-Options nosniff
		X-Frame-Options DENY
		Referrer-Policy no-referrer
		-Server
	}
}
```

- 域名：`xbyyb.duckdns.org`（与 SSH 共用同一域名，不同端口，互不影响）
- 证书：Let's Encrypt 自动签发，`ssl_verify_result=0`
- 与 `qlhub.duckdns.org` 完全独立：不同站点块、不同后端、不同权限体系
- Caddyfile 备份：`/opt/quicklaunch-vps/Caddyfile.bak-20261125-2204`

验收：

```
xbyyb.duckdns.org/api/health   → 200，证书校验通过
xbyyb.duckdns.org/             → 302 跳飞书登录
qlhub.duckdns.org/devices      → 302，原有功能未受影响
ssh xbyyb.duckdns.org          → 正常
```

## 4.1 为什么要听网桥地址

看板必须让 Caddy 容器访问到，而 `127.0.0.1` 在容器内指向容器自己。
改用 `172.18.0.1`（docker 网桥网关）后：宿主机与 docker 网络可达，
公网不可达——与 `ql-mailer` 绑 `172.18.0.1:8780` 是同一套做法，不新增公网端口。

ql-mailer 的 `QL_BOARD_URL` 同步改为 `http://172.18.0.1:8900`。

## 4.2 权限隔离验收（真实角色实测）

| 请求 | 管理员 | 普通成员 | 未登录 |
|---|---|---|---|
| `/api/board` | 200 | 200 | 401 |
| `/api/users` | 200 | **403** | 401 |
| `/api/config` | 200 | **403** | 401 |
| `/api/absences` | 200 | **403** | 401 |
| `/api/todos?open_id=他人` | 200 | **403** | 401 |
| `/board.db` `/config.json` | 302 | 302 | 302 |

## 5. 回滚

见 `docs/rollback.md`。
