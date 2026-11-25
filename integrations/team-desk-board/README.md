# 运营部看板子项目（集成说明）

## 位置

真实源码**不**在本仓库内，而是独立仓库：

| 项 | 值 |
|---|---|
| 本地路径 | `C:\Users\Administrator\WorkBuddy\2026-10-29-16-23-19\team-desk-board` |
| Git 远程 | `git@github.com:Evil-Freedom/team-desk-board.git` |
| VPS 部署路径 | `/opt/team-desk-board`（root:statusboard，代码只读） |
| VPS 数据 | `/var/lib/team-desk-board/board.db` |
| VPS 配置 | `/etc/team-desk-board/config.json`（root:statusboard 640） |

## 为什么用「独立工作区」而不是 submodule/subtree

看板有自己的提交历史与发布节奏（`board.db` 与 `config.json` 都是运行时资产，
不适合同一个仓库管理）。subtree 会把两边历史混在一起，submodule 又要求所有
改动都先推远端才能被引用——而本项目明确「改完先本地提交，等确认再推」。

所以采用**独立工作区 + 契约式集成**：两边各自提交，靠下面这份契约对齐。

## 集成契约（改动任一侧都要同步这里）

### QuickLaunch → 看板

`POST /internal/morning/plan`

```json
{"device_id": "dev_xxx", "event_time": 1795613184, "type": "checkin_unknown"}
```

- 签名头：`X-QL-Timestamp` / `X-QL-Nonce` / `X-QL-Signature`
- 签名串：`{ts}\n{nonce}\n{METHOD}\n{完整path含query}\n{sha256(body) hex}`
- 密钥：`QL_BOARD_TOKEN`（QuickLaunch）== `QL_INTERNAL_TOKEN`（看板）
- 同日幂等：重复上报返回同一个 `run_id`，不会把统采时间推晚

### 看板 → QuickLaunch

`GET /internal/attendance/self?attendance_date=YYYY-MM-DD&device_id=dev_xxx`

```json
{"ok": true, "found": true, "attendance_date": "2026-11-25",
 "employee_no": "X0050", "record_id": "771...", "result": "Normal",
 "check_time": 1795567208, "state": "CHECKED_IN", "run_id": "mr-...", "fetched_at": 1795613337}
```

- `found=false` 表示「还没采到」，调用方必须继续等，**不能**当缺勤
- 不接受 `open_id` / `employee_no` 参数：服务端按 `device_bindings` 反查

### 设备绑定（看板侧配置）

```json
"quicklaunch": {"device_bindings": {"dev_7cca07466cc0bbbb": "ou_e3f43..."}}
```

未登记的设备一律 403。新增设备要同时改这里和 `QLMAIL_DEVICES`。

## 谁负责什么

| 事项 | 负责侧 |
|---|---|
| 飞书 API 调用、token 刷新 | 看板（唯一出口） |
| 考勤采集与去重 | 看板 `app/attendance.py` |
| 考勤判据（record_id / 时间容差） | **两边各校验一次**（互不信任） |
| 设备动作（Root Force Stop） | QuickLaunch |
| 个人邮件 | ql-mailer |
| 部门日报 | 看板 `app/digest.py` |
