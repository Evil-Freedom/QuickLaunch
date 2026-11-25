# 统一考勤：数据分发与权限边界

> 2026-11-25 上线。本文件是 QuickLaunch 侧的视角；采集实现见看板仓库
> `docs/UNIFIED-ATTENDANCE.md`。

## 1. 变化点

| 之前 | 现在 |
|---|---|
| ql-mailer 在收到 `checkin_unknown` 时自己跑 `lark-cli` 查考勤 | 改成向看板内部接口要「本人」那一条 |
| Windows 核验端跑着另一套核验 | 已停用，生产实例只剩 VPS 一个 |

为什么必须只留一个数据源：同一个人同一天，两个服务各自查一次飞书，
刷新令牌、缓存、限流都会让两边读到不同快照。表现出来就是
「看板显示已打卡、手机还在等核验」——这类问题没有日志能查，只能靠架构避免。

## 2. 判据（两边都跑一遍）

```python
BAD_RESULTS = {"Todo", "Absent", "NoNeedCheck", ""}
CLOCK_TOLERANCE_S = 600
```

1. `record_id` 非空
2. `result` 不在 `BAD_RESULTS`
3. `check_time >= 任务时间 - 600s`（防止拿早上更早的打卡当本次证据）

看板侧额外确认「这条记录属于这台设备绑定的人」；ql-mailer 侧再确认一次
「记录没早于本次任务」。任何一条不满足 → `CHECKIN_PENDING`，保持飞书运行。

## 3. 状态与动作

```
checkin_unknown 上报
   │
   ├─ 看板有本人真实记录 ──► checkin_success ──► 创建 FORCE_STOP_FEISHU
   │                                              │
   │                                              ▼
   │                                      Android Root Force Stop
   │                                              │
   │                                              ▼
   │                                      CLEANUP_SUCCESS + 邮件
   │
   └─ 没有记录 ──► 保持 PENDING，30 分钟后进入 checkin_unknown 提醒窗口
```

`checkin_unknown` 只表示「待核验」，**永远不代表打卡成功**。

## 4. 环境变量（ql-mailer）

| 变量 | 说明 |
|---|---|
| `QL_BOARD_URL` | `http://127.0.0.1:8900`，留空 = 该能力关闭 |
| `QL_BOARD_TOKEN` | 与看板 `QL_INTERNAL_TOKEN` 相同 |
| `QL_BOARD_TIMEOUT` | 默认 8 秒 |
| `QL_BOARD_FALLBACK_LARK` | 默认 0。设为 1 会退回「自己查飞书」，仅在排障时短暂开启 |

`QLMAIL_LARK=0`：CLI 凭据存在系统 keyring 里，跨用户拿不走；关掉可避免
两个服务竞争同一份 refresh_token。

## 5. 排障

```bash
# 1) 看板侧最近一次采集
sudo -u statusboard python3 -c "import sys;sys.path.insert(0,'/opt/team-desk-board');\
from app import attendance;print(attendance.last_log())"

# 2) 服务间签名是否通（在 VPS 上以 root 执行）
set -a; . /etc/qlmail/mailer.env; set +a
cd /opt/ql-mailer && python3 -c "
from mailer.config import Config; from mailer.board_bridge import BoardClient
c=Config.from_env(); b=BoardClient(c.board_url,c.board_token,c.board_timeout_s)
print(b.self_evidence(__import__('datetime').date.today().isoformat(),0,c.device_whitelist[0]))"

# 3) 设备动作是否下发
curl -s -H "Authorization: Bearer $QLMAIL_INGEST_TOKEN" \
     -H "X-QL-Device: $DEV" http://127.0.0.1:8780/v1/device/actions/pending
```

常见错误：

| 现象 | 原因 |
|---|---|
| 内部接口 401 | 两边 token 不一致，或 VPS 时间漂移超过 300 秒 |
| 403 `device_not_bound` | 看板 `device_bindings` 没登记这台设备 |
| `found=false` 一直不变 | 看板没采到：查 `attendance_sync_log` 的 message |
| ql-mailer 拿到 None 但看板有记录 | 时间容差：记录早于任务时间 10 分钟以上，判据会拒绝 |

## 6. 覆盖范围（当前真实状态）

- 应用缺 `contact:user.employee_id:readonly` → 拿不到部门成员的工号
- 因此按 `per_user` 模式采集：**已授权成员各自查自己**
- 2026-11-25 实测：5 人有 token，1 人成功（姜芃），其余 4 人授权时缺 `attendance:task:readonly`

要让覆盖率上去，两条路（都不需要管理员审批）：

1. 成员重新授权：`config.oauth_scope` 已加入 `attendance:task:readonly`，重新点一次登录即可
2. 管理员补 `contact:user.employee_id:readonly`：`sync_identity()` 会自动回填映射并切到 `team` 模式

在此之前，看板对未覆盖成员显示「结果未知」，不会显示「待打卡」或「缺勤」。
