"""考勤核验记录 + 清理动作队列。

与 [repository] 共用同一个 SQLite 文件，但独立连接与独立表：
通知队列管「发什么邮件」，这里管「哪台设备要执行什么、执行结果如何」。

不变量：
* 同一 (task_id, action_type) 只存在一条动作（唯一索引），重复核验不会产生第二次清理；
* 只有 PENDING/DELIVERED 可被设备领取；终态不可回退；
* 动作类型是服务端固定枚举，请求方只能传 task/device，不能传命令、包名或路径。
"""

from __future__ import annotations

import json
import sqlite3
import threading
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

from .repository import ACTION_SCHEMA, now_iso, parse_iso

PENDING = "PENDING"
DELIVERED = "DELIVERED"
RUNNING = "CLEANUP_RUNNING"
SUCCESS = "CLEANUP_SUCCESS"
FAILED = "CLEANUP_FAILED"
EXPIRED = "EXPIRED"
REJECTED = "REJECTED"

FINAL = {SUCCESS, FAILED, EXPIRED, REJECTED}
ALLOWED = {"FORCE_STOP_FEISHU"}

# 只有打卡类目标应用才需要考勤核验；哔哩哔哩的启动成功与考勤无关，
# 拿同一天的打卡记录去「证明」它成功是错误关联。
CHECKIN_APPS = ("飞书", "Lark", "lark")


@dataclass
class Action:
    action_id: str
    device_id: str
    task_id: str
    action_type: str
    nonce: str
    status: str
    created_at: str
    expires_at: str
    delivered_at: str | None
    result_at: str | None
    result_detail: str | None

    def as_dict(self) -> dict:
        return {
            "action_id": self.action_id,
            "device_id": self.device_id,
            "task_id": self.task_id,
            "action_type": self.action_type,
            "nonce": self.nonce,
            "status": self.status,
            "created_at": self.created_at,
            "expires_at": self.expires_at,
        }


class ActionStore:
    def __init__(self, path: str):
        self._lock = threading.RLock()
        self._conn = sqlite3.connect(path, timeout=10, isolation_level=None,
                                     check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        self._conn.execute("PRAGMA journal_mode=WAL")
        self._conn.execute("PRAGMA busy_timeout=5000")
        self._conn.executescript(ACTION_SCHEMA)

    def close(self) -> None:
        with self._lock:
            self._conn.close()

    # ── 考勤证据 ────────────────────────────────────────────────────

    def record_attendance(self, *, task_id: str, device_id: str, attendance_date: str,
                          evidence_source: str, evidence_time: str,
                          evidence_ref: str | None, result: str) -> bool:
        """返回是否首次记录。同一 task_id 重复核验保持幂等。"""
        with self._lock:
            cur = self._conn.execute(
                """INSERT OR IGNORE INTO attendance
                   (task_id, device_id, attendance_date, evidence_source, evidence_time,
                    evidence_ref, verified_at, result, created_at)
                   VALUES (?,?,?,?,?,?,?,?,?)""",
                (task_id, device_id, attendance_date, evidence_source, evidence_time,
                 evidence_ref or "", now_iso(), result, now_iso()),
            )
            return cur.rowcount == 1

    def attendance(self, task_id: str) -> dict | None:
        with self._lock:
            row = self._conn.execute(
                "SELECT * FROM attendance WHERE task_id=?", (task_id,)
            ).fetchone()
            return dict(row) if row else None

    # ── 动作 ────────────────────────────────────────────────────────

    def create_action(self, *, device_id: str, task_id: str, action_type: str,
                      ttl_s: int = 1800) -> tuple[bool, Action]:
        if action_type not in ALLOWED:
            raise ValueError(f"action_type not allowed: {action_type}")
        now = datetime.now(timezone.utc).replace(microsecond=0)
        expires = now + timedelta(seconds=ttl_s)
        action_id = "act_" + uuid.uuid4().hex[:24]
        with self._lock:
            cur = self._conn.execute(
                """INSERT OR IGNORE INTO actions
                   (action_id, device_id, task_id, action_type, nonce, status,
                    created_at, expires_at)
                   VALUES (?,?,?,?,?,?,?,?)""",
                (action_id, device_id, task_id, action_type, uuid.uuid4().hex,
                 PENDING, now.isoformat(), expires.isoformat()),
            )
            created = cur.rowcount == 1
            row = self._conn.execute(
                "SELECT * FROM actions WHERE task_id=? AND action_type=?",
                (task_id, action_type),
            ).fetchone()
        return created, self._to_model(row)

    def expire_stale(self) -> int:
        with self._lock:
            cur = self._conn.execute(
                "UPDATE actions SET status=? WHERE status IN (?, ?) AND expires_at <= ?",
                (EXPIRED, PENDING, DELIVERED, now_iso()),
            )
            return cur.rowcount

    def pending_for_device(self, device_id: str) -> list[Action]:
        self.expire_stale()
        with self._lock:
            rows = self._conn.execute(
                """SELECT * FROM actions
                   WHERE device_id=? AND status IN (?, ?) AND expires_at > ?
                   ORDER BY created_at ASC""",
                (device_id, PENDING, DELIVERED, now_iso()),
            ).fetchall()
            ids = [r["action_id"] for r in rows]
            for action_id in ids:
                self._conn.execute(
                    "UPDATE actions SET status=?, delivered_at=? WHERE action_id=? AND status=?",
                    (DELIVERED, now_iso(), action_id, PENDING),
                )
            return [self._to_model(r) for r in rows]

    def begin(self, action_id: str, device_id: str, nonce: str) -> tuple[bool, str, Action | None]:
        """设备回报「开始执行」。校验设备、nonce、有效期与状态。"""
        self.expire_stale()
        with self._lock:
            row = self._conn.execute(
                "SELECT * FROM actions WHERE action_id=?", (action_id,)
            ).fetchone()
            if row is None:
                return False, "not_found", None
            model = self._to_model(row)
            if model.device_id != device_id:
                return False, "device_mismatch", model
            if model.nonce != nonce:
                return False, "nonce_mismatch", model
            if model.status in FINAL:
                return False, f"already_{model.status.lower()}", model
            if parse_iso(model.expires_at) <= datetime.now(timezone.utc):
                self._conn.execute("UPDATE actions SET status=? WHERE action_id=?", (EXPIRED, action_id))
                return False, "expired", model
            # PENDING 也可以直接进 RUNNING（设备可能跳过 DELIVERED）
            self._conn.execute(
                "UPDATE actions SET status=?, delivered_at=COALESCE(delivered_at, ?) WHERE action_id=?",
                (RUNNING, now_iso(), action_id),
            )
            return True, "ok", self._to_model(
                self._conn.execute("SELECT * FROM actions WHERE action_id=?", (action_id,)).fetchone()
            )

    def finish(self, action_id: str, device_id: str, status: str,
               detail: str = "") -> tuple[bool, str, Action | None]:
        if status not in (SUCCESS, FAILED, REJECTED):
            return False, "invalid_status", None
        with self._lock:
            row = self._conn.execute(
                "SELECT * FROM actions WHERE action_id=?", (action_id,)
            ).fetchone()
            if row is None:
                return False, "not_found", None
            model = self._to_model(row)
            if model.device_id != device_id:
                return False, "device_mismatch", model
            if model.status in FINAL:
                # 幂等：同样的结果重复上报直接返回成功
                return model.status == status, "already_final", model
            self._conn.execute(
                "UPDATE actions SET status=?, result_at=?, result_detail=? WHERE action_id=?",
                (status, now_iso(), detail[:1000], action_id),
            )
            return True, "ok", self._to_model(
                self._conn.execute("SELECT * FROM actions WHERE action_id=?", (action_id,)).fetchone()
            )

    def get(self, action_id: str) -> Action | None:
        with self._lock:
            row = self._conn.execute(
                "SELECT * FROM actions WHERE action_id=?", (action_id,)
            ).fetchone()
            return self._to_model(row) if row else None

    def stats(self) -> dict[str, int]:
        with self._lock:
            rows = self._conn.execute(
                "SELECT status, COUNT(*) AS n FROM actions GROUP BY status"
            ).fetchall()
            return {r["status"]: r["n"] for r in rows}

    # ── 待核验任务 ──────────────────────────────────────────────────

    def pending_checkins(self, lookback_hours: int = 20) -> list[dict]:
        """近 N 小时的 launch_success 事件里，还没有考勤证据的任务。

        关联键是上报时带的 `task_id`（Android 的 attemptId）；老版本没有该字段，
       这里直接用 event_id 兜底，保证不会漏掉任务。
        """
        cutoff = (datetime.now(timezone.utc) - timedelta(hours=lookback_hours)
                  ).replace(microsecond=0).isoformat()
        with self._lock:
            rows = self._conn.execute(
                """SELECT n.event_id, n.device_id, n.payload, n.created_at,
                          COALESCE(json_extract(n.payload,'$.task_id'), n.event_id) AS task_id
                   FROM notifications n
                   WHERE n.notification_type='launch_success' AND n.created_at >= ?
                   ORDER BY n.created_at DESC""",
                (cutoff,),
            ).fetchall()
        out = []
        for r in rows:
            task_id = r["task_id"]
            if self.attendance(task_id) is not None:
                continue
            payload = json.loads(r["payload"])
            if str(payload.get("target_app", "")) not in CHECKIN_APPS:
                continue
            out.append({
                "task_id": task_id,
                "event_id": r["event_id"],
                "device_id": r["device_id"],
                "created_at": r["created_at"],
                "target_app": payload.get("target_app", ""),
                "actual_time": payload.get("actual_time", ""),
            })
        return out

    @staticmethod
    def _to_model(row: sqlite3.Row) -> Action:
        return Action(
            action_id=row["action_id"],
            device_id=row["device_id"],
            task_id=row["task_id"],
            action_type=row["action_type"],
            nonce=row["nonce"],
            status=row["status"],
            created_at=row["created_at"],
            expires_at=row["expires_at"],
            delivered_at=row["delivered_at"],
            result_at=row["result_at"],
            result_detail=row["result_detail"],
        )
