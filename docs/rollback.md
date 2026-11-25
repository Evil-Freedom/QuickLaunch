# 回滚：看板迁回 Windows / 撤销统一考勤

> 前提：`refresh_token` 一次性，任何时刻只允许一个生产实例在跑。

## A. 只回滚「统一考勤」，保留 VPS 看板

适用：看板本身没问题，只是考勤分发有新缺陷。

```bash
# 1) 停用内部数据源（ql-mailer 立刻不再向看板取证据）
sed -i 's/^QL_BOARD_URL=.*/QL_BOARD_URL=/' /etc/qlmail/mailer.env
systemctl restart ql-mailer

# 2) 想让手机侧临时能核验，再短暂打开旧的直查通道（需要 lark-cli 凭据）
#    注意：只能在确认看板不采集的时间窗内开，否则两边竞争 refresh_token
sed -i 's/^QLMAIL_LARK=0/QLMAIL_LARK=1/' /etc/qlmail/mailer.env
sed -i 's/^QL_BOARD_FALLBACK_LARK=0/QL_BOARD_FALLBACK_LARK=1/' /etc/qlmail/mailer.env
systemctl restart ql-mailer

# 3) 看板侧停止晨间统采
sed -i 's/^/ /' /dev/null   # 占位，实际用下面这条
python3 - <<'PY'
import json
p="/etc/team-desk-board/config.json"
c=json.load(open(p)); c["morning_target"]="23:59"; json.dump(c,open(p,"w"),ensure_ascii=False,indent=2)
PY
systemctl restart team-desk-board
```

数据库无需改动：新表是纯增量，旧代码不读它们。

## B. 整体回滚：看板迁回 Windows

```bash
# ── 1. 先停 VPS，确认不再刷新令牌 ───────────────────────────
systemctl stop team-desk-board
systemctl disable team-desk-board
systemctl is-active team-desk-board     # 必须是 inactive
# 确认 8900 已无监听
ss -tlnp | grep 8900 || echo "已停止"

# ── 2. 取回最近的生产数据 ───────────────────────────────────
python3 -c "import sqlite3;s=sqlite3.connect('/var/lib/team-desk-board/board.db');\
d=sqlite3.connect('/tmp/board-rollback.db');s.backup(d)"
scp root@xbyyb.duckdns.org:/tmp/board-rollback.db ./
scp root@xbyyb.duckdns.org:/etc/team-desk-board/config.json ./
```

Windows 侧：

```powershell
# 3) 回到迁移前的代码版本（保留新代码在单独分支上）
cd C:\Users\Administrator\WorkBuddy\2026-10-29-16-23-19\team-desk-board
git checkout 1292172      # 迁移前最后一个提交

# 4) 用回滚下来的数据库覆盖，校验完整性
python -c "import sqlite3;print(sqlite3.connect('board.db').execute('PRAGMA integrity_check').fetchone())"

# 5) 恢复计划任务
schtasks /change /tn "StatusBoard" /enable
schtasks /run  /tn "StatusBoard"
curl http://127.0.0.1:8900/api/health
```

**顺序不能反**：先停 VPS 再启 Windows。反过来会出现两个实例同时刷新
同一组 `refresh_token`，后启动的那个会把前一个踢下线。

## C. 撤销数据库结构（可选）

新表与旧表完全解耦，旧代码不会读它们，所以**可以不做**。真要清掉：

```sql
DROP TABLE IF EXISTS attendance_records;
DROP TABLE IF EXISTS attendance_sync_log;
DROP TABLE IF EXISTS morning_runs;
DROP TABLE IF EXISTS attendance_identity;
```

执行前先备份 `board.db`。不要动 `members` / `absences` / `todos` / `users` /
`sync_log`——那五张表的数据结构一次都没改。

## D. 回滚后的验收

| 检查 | 期望 |
|---|---|
| `systemctl is-active team-desk-board` | `inactive`（VPS） |
| Windows `StatusBoard` | `Running`，`/api/health` 返回 `ok:true` |
| 群日报 | 次日 9:00 恢复固定推送（旧代码行为） |
| 手机核验 | `QLMAIL_LARK=1` 时回到「VPS 直查飞书」 |
| 数据 | `members` 11 人、`absences` 322 条、`todos` 19 条 |

## E. 备份位置

| 内容 | 位置 |
|---|---|
| 迁移前 Windows 生产库 | `work/backups/board-pre-vps-20261125/board.db` |
| 迁移快照（含新表） | `work/backups/board-migrate-20261125.db` |
| VPS 代码包 | `work/backups/board-app-20261125.tgz` |
| ql-mailer 旧版 | VPS `/root/ql-mailer-backup-20261125-2126.tgz` |
| ql-mailer 环境 | VPS `/root/mailer.env.bak-20261125-2126` |
