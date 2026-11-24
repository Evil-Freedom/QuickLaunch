# ql-mailer — QuickLaunch 邮件通知服务（腾讯 Agent Mail）

在阿里云 Debian VPS 上**独立**运行：Android 上报事件 → 本服务入队 → 用官方
`agently-cli` 发信。邮件链路与启动链路完全解耦：邮件挂了，启动与规则同步照常工作。

```
Android ──HTTPS /notify/v1/events──> Caddy ──> ql-mailer(172.18.0.1:8780, systemd)
                                                  │  SQLite outbox（/var/lib/qlmail/notifications.db）
                                                  └─ subprocess: agently-cli message +send
                                                       └─ 主邮箱 <PRIMARY_RECIPIENT>
                                                            └─ 明确失败 -> 备用 <FALLBACK_RECIPIENT>
```

## 1. 部署组件

| 组件 | 位置 | 说明 |
|---|---|---|
| CLI | `/usr/local/bin/agently-cli` | 1.0.18，官方 npm 包 `@tencent-qqmail/agently-cli-linux-x64` 解包的静态 Go 二进制，安装时逐字节核对过 `dist.integrity` |
| 服务用户 | `qlmail` (uid 999) | 非 root、非容器用户 |
| OAuth 凭据 | `/var/lib/qlmail/config`（700） | `agently-cli auth login` 的 device flow 结果，`auth status` 显示 `encrypted file keychain` |
| 队列 | `/var/lib/qlmail/notifications.db` | SQLite（WAL），唯一真源 |
| 日志 | `/var/log/qlmail/mailer.log` + journal | 不含正文与令牌 |
| 配置 | `/etc/qlmail/mailer.env`（600 root） | 含上报令牌，绝不入库 |
| 服务 | `ql-mailer.service` + `qlmail-net.service` | `Restart=always`；后者以 root 把 docker 网桥网关写进 `/run/qlmail/bind` |

服务只监听 docker 网桥网关（`172.18.0.1:8780`），公网无法直连；外部只能经 Caddy 的
`/notify/*` 路由访问，未新增公网端口、未改安全组。

## 2. 首次部署

```bash
# 1) CLI（官方渠道 + 完整性校验）
curl -fsSL -o /tmp/cli.tgz https://registry.npmjs.org/@tencent-qqmail/agently-cli-linux-x64/-/agently-cli-linux-x64-1.0.18.tgz
openssl dgst -sha512 -binary /tmp/cli.tgz | base64 -w0   # 必须等于 npm dist.integrity
tar xzf /tmp/cli.tgz && install -m 755 package/bin/agently-cli /usr/local/bin/

# 2) 服务用户与目录
useradd -r -m -d /var/lib/qlmail -s /bin/bash qlmail
install -d -m 700 -o qlmail -g qlmail /var/lib/qlmail/config
install -d -m 750 -o qlmail -g qlmail /var/log/qlmail

# 3) 独立授权（device flow；不要复制 WorkBuddy 的凭据）
sudo -u qlmail env HOME=/var/lib/qlmail AGENTLY_CLI_CONFIG_DIR=/var/lib/qlmail/config \
  /usr/local/bin/agently-cli auth login
sudo -u qlmail env HOME=/var/lib/qlmail AGENTLY_CLI_CONFIG_DIR=/var/lib/qlmail/config \
  /usr/local/bin/agently-cli +me          # 必须返回 <SENDER_MAILBOX>

# 4) 配置 + 服务
install -d -m 750 /etc/qlmail
sed "s|^QLMAIL_INGEST_TOKEN=$|QLMAIL_INGEST_TOKEN=$(openssl rand -hex 32)|" \
  deploy/mailer.env.example > /etc/qlmail/mailer.env && chmod 600 /etc/qlmail/mailer.env
cp -r . /opt/ql-mailer            # /opt/ql-mailer/mailer/*.py
install -m 644 deploy/ql-mailer.service deploy/qlmail-net.service /etc/systemd/system/
install -m 755 deploy/resolve-net.sh /usr/local/lib/qlmail/resolve-net.sh
systemctl daemon-reload && systemctl enable --now qlmail-net ql-mailer

# 5) Caddy 路由（热加载，不重启容器）
# handle_path /notify/* { reverse_proxy 172.18.0.1:8780 }
docker exec $(docker ps -qf name=caddy) caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile
```

## 3. 接口

```http
POST /notify/v1/events           Authorization: Bearer <QLMAIL_INGEST_TOKEN>
{
  "type": "launch_success | launch_failed | unlock_alert | checkin_success | checkin_unknown",
  "device_id": "dev_7cca07466cc0bbbb",
  "event_id": "uuid（可选，重复上报不会重发）",
  "data": { ... }                 # 只渲染白名单字段，收件人字段一律忽略
}
→ 202 {"ok":true,"event_id":"...","status":"PENDING","duplicate":false}
```

`GET /notify/v1/events/{event_id}` 查状态；`GET /notify/v1/healthz` 看队列。
成功通知可用 `QLMAIL_SUCCESS_MODE=off` 关闭；失败与告警不受影响。

## 4. 状态机与错误处理

`PENDING → PRIMARY_SENDING → PRIMARY_SENT`
`PRIMARY_FAILED → FALLBACK_SENDING → FALLBACK_SENT`
`UNKNOWN`（结果未知，先回查已发送箱，绝不直接重发）
`FAILED` / `EXPIRED` / `AUTH_REQUIRED`

| 退出码 | 处理 |
|---|---|
| 0 | 标记 SENT，记 message_id |
| 1 | 服务端/网络异常：退避 30s、120s，最多 2 次重试；若 stderr 是「地址非法」则归为收件人问题 → 切备用 |
| 2 | 参数错误：FAILED，等人改配置 |
| 3 | 授权失效：AUTH_REQUIRED，调度器暂停；重新授权后重启服务自动恢复 |
| 4 | 本地网络错误：同 1 |
| 6 | 业务永久拒绝：收件人相关 → 切备用；否则 FAILED |
| 7 | 限频：按 Retry-After（默认 60s）延后 |
| 8 | 缺确认令牌：UNKNOWN（我们始终用 `--confirmed`，出现即异常） |
| 超时/被杀 | UNKNOWN；`recover_stale` 在重启后把中断的发送判为 UNKNOWN |

**没有投递保证**：CLI 的 `+send` 返回 `queued:true` 时仅代表服务器受理，
异步退信（域名不存在、邮箱满、被对方丢进垃圾箱）本服务看不到。实测
`nobody@no-such-domain.invalid` 同样返回成功——所以「打卡成功」这类通知必须有
独立证据（考勤记录），不能拿「邮件发出去了」当送达证明。

## 5. 幂等与配额

- `event_id` 是主键：重复上报只返回 `duplicate:true`，不会产生第二封；
- 发送前先落 `*_SENDING`，进程被杀后由 `recover_stale` 判 UNKNOWN 而不是重发；
- 配额：50/天、200/小时、10/分钟（与 `agently-cli +me` 一致），达限只是延后，不丢队列。

## 6. 运维

```bash
systemctl status ql-mailer
journalctl -u ql-mailer -n 50 --no-pager
sqlite3 /var/lib/qlmail/notifications.db 'select status,count(*) from notifications group by status'
# 重新授权后恢复被暂停的队列
systemctl restart ql-mailer
```

## 7. 回滚

```bash
systemctl disable --now ql-mailer qlmail-net
rm -f /etc/systemd/system/ql-mailer.service /etc/systemd/system/qlmail-net.service
# 恢复 Caddy（去掉 /notify 路由后热加载）
cp /opt/quicklaunch-vps/Caddyfile.bak-* /opt/quicklaunch-vps/Caddyfile
docker exec $(docker ps -qf name=caddy) caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile
```

回滚不影响 QuickLaunch 主服务：`api`、`caddy` 容器与规则同步从未被修改。
`/var/lib/qlmail` 可保留作为历史记录，也可整体删除（OAuth 凭据随之失效）。

## 8. 安全边界

- OAuth 凭据只存在于 `/var/lib/qlmail/config`（700，`qlmail` 用户），不入 Git、不进 APK、不进镜像；
- 收件人白名单写死在配置里，请求体里的任何收件人字段都被忽略；
- 上报需 Bearer 令牌，且限制 `device_id`；
- 正文只由服务端模板渲染并对字段做清洗（去控制字符、截断），邮件内容不会被当作指令执行；
- 服务只监听 docker 网桥，无公网管理入口；
- 邮件失败只影响 `/var/lib/qlmail/notifications.db` 的行状态，`/api/v1/report` 与启动流程不受影响。

## 9. 考勤核验与清理闭环（v1.1）

```
飞书启动 → launch_success / checkin_unknown
        → Windows 用 lark-cli 查真实打卡记录（只读）
        → POST /notify/v1/attendance/verify（核验端令牌）
        → attendance 落库 + 生成 FORCE_STOP_FEISHU 动作（30 分钟有效、同任务唯一）
        → 手机 GET /notify/v1/device/actions/pending
        → begin(带 nonce) → am force-stop --user 0 com.ss.android.lark → 验证进程清零
        → POST .../result → cleanup_success 邮件
```

新增接口：

| 方法 | 路径 | 令牌 | 用途 |
|---|---|---|---|
| GET | `/notify/v1/attendance/pending` | 核验端 | 近 20h 内、目标为飞书、尚无证据的任务 |
| POST | `/notify/v1/attendance/verify` | 核验端 | 提交真实打卡证据；缺字段 400，重复幂等 |
| GET | `/notify/v1/device/actions/pending` | 设备端 | 领取待执行动作（只有 `FORCE_STOP_FEISHU`） |
| POST | `/notify/v1/device/actions/{id}/begin` | 设备端 | 校验设备/nonce/有效期后进入 CLEANUP_RUNNING |
| POST | `/notify/v1/device/actions/{id}/result` | 设备端 | 回报 CLEANUP_SUCCESS / CLEANUP_FAILED / REJECTED |

Windows 核验端：`python tools/attendance_verifier.py --once`（或 `--loop 600` 常驻）。
判据：当天有 records、`check_in_record_id` 非空、`check_in_result` 不是 Todo/Absent、
打卡时间不早于任务时间 -10 分钟。任一条不满足就不上报成功（保持待确认）。

Android 两个开关（设置页 → 远程同步 → 飞书进程清理）：
`cleanup_before_launch`（启动前清理遗留进程）与 `cleanup_after_checkin`（考勤成功后彻底关闭）。
后者是**执行权限的最终裁决**：关闭时收到动作只会回 REJECTED。
`awaiting_checkin_cleanup` 置位期间禁止启动前清理，避免把还要用来打卡的飞书杀掉。

实测（2026-11-24）：08:55 真实打卡 → 09:5x 核验 → 动作下发 → 手机 force-stop
（清理前 3 个进程 → 0，245ms）→ cleanup_success 邮件；随后规则重新触发，飞书正常回到前台。
