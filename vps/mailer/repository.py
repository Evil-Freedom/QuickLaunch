"""SQLite outbox：通知事件的唯一真源。

设计要点（与需求 §5.3 对齐）：

* `event_id` 是主键 —— 同一个事件重复上报只会更新 payload，不会生成第二封邮件；
* 每次尝试都先落 `*_SENDING` 再发信，进程被杀后能通过 [recover_stale] 判为 UNKNOWN，
  而不是当成「没发过」重发（重发的代价是用户收到重复邮件）；
* 所有时间用 UTC ISO8601 字符串，便于人工排查与跨时区比对。
"""

from __future__ import annotations

import json
import sqlite3
import threading
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any

# 状态机（需求 §5.3 的状态 + AUTH_REQUIRED：授权失效时必须停下来等人，不能退化成普通 FAILED）
PENDING = "PENDING"
PRIMARY_SENDING = "PRIMARY_SENDING"
PRIMARY_SENT = "PRIMARY_SENT"
PRIMARY_FAILED = "PRIMARY_FAILED"
FALLBACK_SENDING = "FALLBACK_SENDING"
FALLBACK_SENT = "FALLBACK_SENT"
UNKNOWN = "UNKNOWN"
FAILED = "FAILED"
EXPIRED = "EXPIRED"
AUTH_REQUIRED = "AUTH_REQUIRED"

SENDING_STATES = (PRIMARY_SENDING, FALLBACK_SENDING)
FINAL_STATES = (PRIMARY_SENT, FALLBACK_SENT, FAILED, EXPIRED)

# 优先级：安全告警最高，成功通知最低（需求 §七）
PRIORITY = {
    "unlock_alert": 0,
    "launch_failed": 1,
    "cleanup_failed": 2,
    "checkin_unknown": 3,
    "cleanup_pending": 4,
    "checkin_success": 5,
    "launch_success": 6,
    "cleanup_success": 7,
}

SCHEMA = """
CREATE TABLE IF NOT EXISTS notifications (
    event_id            TEXT PRIMARY KEY,
    notification_type   TEXT NOT NULL,
    device_id           TEXT NOT NULL,
    payload             TEXT NOT NULL,
    subject             TEXT NOT NULL,
    body                TEXT NOT NULL,
    priority            INTEGER NOT NULL DEFAULT 5,
    primary_recipient   TEXT NOT NULL,
    fallback_recipient  TEXT NOT NULL,
    current_recipient   TEXT NOT NULL,
    status              TEXT NOT NULL,
    attempts            INTEGER NOT NULL DEFAULT 0,
    retry_count         INTEGER NOT NULL DEFAULT 0,
    message_id          TEXT,
    error_code          INTEGER,
    error_message       TEXT,
    created_at          TEXT NOT NULL,
    updated_at          TEXT NOT NULL,
    next_attempt_at     TEXT NOT NULL,
    claimed_at          TEXT,
    sent_at             TEXT
);
CREATE INDEX IF NOT EXISTS idx_notifications_due
    ON notifications(status, next_attempt_at);
CREATE INDEX IF NOT EXISTS idx_notifications_created
    ON notifications(created_at);
"""


def now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def parse_iso(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def new_event_id() -> str:
    return str(uuid.uuid4())


@dataclass
class Notification:
    event_id: str
    notification_type: str
    device_id: str
    payload: dict[str, Any]
    subject: str
    body: str
    priority: int
    primary_recipient: str
    fallback_recipient: str
    current_recipient: str
    status: str
    attempts: int
    retry_count: int
    message_id: str | None
    error_code: int | None
    error_message: str | None
    created_at: str
    updated_at: str
    next_attempt_at: str
    claimed_at: str | None
    sent_at: str | None
    claimed_from: str = ""  # 本次被领取时的原状态（UNKNOWN / PRIMARY_FAILED / PENDING）


class Repository:
    def __init__(self, path: str):
        self.path = path
        # HTTP 接入线程与发送调度线程共用同一个实例；SQLite 连接本身不跨线程共享，
        # 所以关掉 check_same_thread 并在我方加锁（见模块末尾的 _thread_safe 绑定）。
        self._lock = threading.RLock()
        self._conn = sqlite3.connect(path, timeout=10, isolation_level=None,
                                     check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        self._conn.execute("PRAGMA journal_mode=WAL")
        self._conn.execute("PRAGMA busy_timeout=5000")
        self._conn.executescript(SCHEMA)

    def close(self) -> None:
        self._conn.close()

    # ── 入队 ────────────────────────────────────────────────────────

    def enqueue(
        self,
        *,
        notification_type: str,
        device_id: str,
        payload: dict[str, Any],
        subject: str,
        body: str,
        primary: str,
        fallback: str,
        event_id: str | None = None,
        status: str = PENDING,
        current_recipient: str | None = None,
        next_attempt_at: str | None = None,
    ) -> tuple[bool, Notification]:
        """返回 (是否新建, 行)。重复 event_id 不会改动已存在的行。"""
        event_id = event_id or new_event_id()
        ts = now_iso()
        cur = self._conn.execute(
            """INSERT OR IGNORE INTO notifications
               (event_id, notification_type, device_id, payload, subject, body, priority,
                primary_recipient, fallback_recipient, current_recipient, status,
                attempts, retry_count, created_at, updated_at, next_attempt_at)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,0,0,?,?,?)""",
            (
                event_id,
                notification_type,
                device_id,
                json.dumps(payload, ensure_ascii=False),
                subject,
                body,
                PRIORITY.get(notification_type, 5),
                primary,
                fallback,
                current_recipient or primary,
                status,
                ts,
                ts,
                next_attempt_at or ts,
            ),
        )
        return cur.rowcount == 1, self.get(event_id)

    def get(self, event_id: str) -> Notification:
        row = self._conn.execute(
            "SELECT * FROM notifications WHERE event_id=?", (event_id,)
        ).fetchone()
        if row is None:
            raise KeyError(event_id)
        return self._to_model(row)

    def exists(self, event_id: str) -> bool:
        return (
            self._conn.execute(
                "SELECT 1 FROM notifications WHERE event_id=?", (event_id,)
            ).fetchone()
            is not None
        )

    # ── 取任务 ──────────────────────────────────────────────────────

    def claim_next(self, now: str | None = None) -> Notification | None:
        """原子地取一条到期任务并标记为发送中。

        用 BEGIN IMMEDIATE 拿写锁，避免两个 worker（或重启瞬间的两个进程）取到同一条。
        返回行的 `claimed_from` 保留了领取前的状态（UNKNOWN 需要先回查再决定是否重发）。
        """
        now = now or now_iso()
        self._conn.execute("BEGIN IMMEDIATE")
        try:
            row = self._conn.execute(
                """SELECT * FROM notifications
                   WHERE status IN (?, ?, ?) AND next_attempt_at <= ?
                   ORDER BY priority ASC, created_at ASC
                   LIMIT 1""",
                (PENDING, PRIMARY_FAILED, UNKNOWN, now),
            ).fetchone()
            if row is None:
                self._conn.execute("COMMIT")
                return None
            prev = row["status"]
            target = FALLBACK_SENDING if prev == PRIMARY_FAILED else PRIMARY_SENDING
            self._conn.execute(
                """UPDATE notifications
                   SET status=?, claimed_at=?, attempts=attempts+1, updated_at=?
                   WHERE event_id=?""",
                (target, now, now, row["event_id"]),
            )
            self._conn.execute("COMMIT")
        except Exception:
            self._conn.execute("ROLLBACK")
            raise
        model = self.get(row["event_id"])
        model.claimed_from = prev
        return model

    def recover_stale(self, stale_after_s: int = 300) -> list[str]:
        """把「发送中」但超过 stale_after_s 没结果的行判为 UNKNOWN（进程被杀/断电）。"""
        cutoff = (
            datetime.now(timezone.utc) - timedelta(seconds=stale_after_s)
        ).replace(microsecond=0).isoformat()
        rows = self._conn.execute(
            f"""SELECT event_id FROM notifications
                WHERE status IN ({",".join("?" * len(SENDING_STATES))}) AND claimed_at <= ?""",
            (*SENDING_STATES, cutoff),
        ).fetchall()
        ids = [r["event_id"] for r in rows]
        for event_id in ids:
            self._conn.execute(
                """UPDATE notifications
                   SET status=?, error_message=COALESCE(error_message,'interrupted while sending'),
                       claimed_at=NULL, updated_at=?
                   WHERE event_id=?""",
                (UNKNOWN, now_iso(), event_id),
            )
        return ids

    # ── 状态迁移 ────────────────────────────────────────────────────

    def mark_sent(self, event_id: str, message_id: str | None) -> None:
        row = self.get(event_id)
        # 用「当前收件人是不是备用」判定落库状态：UNKNOWN 回查成功的行不能误记成 PRIMARY_SENT
        status = (
            FALLBACK_SENT
            if row.current_recipient.lower() == row.fallback_recipient.lower()
            else PRIMARY_SENT
        )
        ts = now_iso()
        self._conn.execute(
            """UPDATE notifications
               SET status=?, message_id=?, sent_at=?, updated_at=?, claimed_at=NULL,
                   error_code=NULL, error_message=NULL
               WHERE event_id=?""",
            (status, message_id, ts, ts, event_id),
        )

    def mark_recipient_rejected(self, event_id: str, code: int, message: str) -> None:
        """收件人明确拒绝：主邮箱 → 切备用；备用也拒 → 终态 FAILED。"""
        row = self.get(event_id)
        ts = now_iso()
        on_fallback = row.current_recipient.lower() == row.fallback_recipient.lower()
        if on_fallback:
            self._conn.execute(
                """UPDATE notifications
                   SET status=?, error_code=?, error_message=?, updated_at=?, claimed_at=NULL
                   WHERE event_id=?""",
                (FAILED, code, message[:2000], ts, event_id),
            )
            return
        self._conn.execute(
            """UPDATE notifications
               SET status=?, current_recipient=?, error_code=?, error_message=?,
                   updated_at=?, claimed_at=NULL, next_attempt_at=?
               WHERE event_id=?""",
            (PRIMARY_FAILED, row.fallback_recipient, code, message[:2000], ts, ts, event_id),
        )

    def requeue(self, event_id: str, delay_s: int, code: int | None, message: str) -> None:
        row = self.get(event_id)
        on_fallback = row.current_recipient.lower() == row.fallback_recipient.lower()
        # 备用邮箱的重试也停在 PRIMARY_FAILED 上：claim_next 会据此再走备用，
        # 这样“还欠一封备用邮件”这个事实只有一个表达。
        target = PRIMARY_FAILED if on_fallback else PENDING
        next_at = (
            datetime.now(timezone.utc) + timedelta(seconds=delay_s)
        ).replace(microsecond=0).isoformat()
        self._conn.execute(
            """UPDATE notifications
               SET status=?, retry_count=retry_count+1, error_code=?, error_message=?,
                   next_attempt_at=?, updated_at=?, claimed_at=NULL
               WHERE event_id=?""",
            (target, code, message[:2000], next_at, now_iso(), event_id),
        )

    def mark_unknown(self, event_id: str, code: int | None, message: str,
                     reconcile_after_s: int = 120) -> None:
        next_at = (
            datetime.now(timezone.utc) + timedelta(seconds=reconcile_after_s)
        ).replace(microsecond=0).isoformat()
        self._conn.execute(
            """UPDATE notifications
               SET status=?, error_code=?, error_message=?, next_attempt_at=?,
                   updated_at=?, claimed_at=NULL
               WHERE event_id=?""",
            (UNKNOWN, code, message[:2000], next_at, now_iso(), event_id),
        )

    def mark_failed(self, event_id: str, code: int | None, message: str) -> None:
        self._conn.execute(
            """UPDATE notifications
               SET status=?, error_code=?, error_message=?, updated_at=?, claimed_at=NULL
               WHERE event_id=?""",
            (FAILED, code, message[:2000], now_iso(), event_id),
        )

    def mark_auth_required(self, event_id: str, code: int, message: str) -> None:
        self._conn.execute(
            """UPDATE notifications
               SET status=?, error_code=?, error_message=?, updated_at=?, claimed_at=NULL
               WHERE event_id=?""",
            (AUTH_REQUIRED, code, message[:2000], now_iso(), event_id),
        )

    def resume_auth_required(self) -> int:
        """重新授权成功后把攒下的任务放回队列。"""
        cur = self._conn.execute(
            "UPDATE notifications SET status=?, updated_at=?, next_attempt_at=? WHERE status=?",
            (PENDING, now_iso(), now_iso(), AUTH_REQUIRED),
        )
        return cur.rowcount

    # ── 统计（健康检查 / 验收用） ──────────────────────────────────

    def stats(self) -> dict[str, int]:
        rows = self._conn.execute(
            "SELECT status, COUNT(*) AS n FROM notifications GROUP BY status"
        ).fetchall()
        return {r["status"]: r["n"] for r in rows}

    def sent_since(self, since_iso: str) -> int:
        row = self._conn.execute(
            """SELECT COUNT(*) AS n FROM notifications
               WHERE status IN (?, ?) AND sent_at >= ?""",
            (PRIMARY_SENT, FALLBACK_SENT, since_iso),
        ).fetchone()
        return int(row["n"])

    def has_due(self, now: str | None = None) -> bool:
        """只读判断是否有到期任务（测试用，不改变状态）。"""
        now = now or now_iso()
        return (
            self._conn.execute(
                "SELECT 1 FROM notifications WHERE status IN (?, ?, ?) AND next_attempt_at <= ? LIMIT 1",
                (PENDING, PRIMARY_FAILED, UNKNOWN, now),
            ).fetchone()
            is not None
        )

    def recent(self, limit: int = 10) -> list[Notification]:
        rows = self._conn.execute(
            "SELECT * FROM notifications ORDER BY created_at DESC LIMIT ?", (limit,)
        ).fetchall()
        return [self._to_model(r) for r in rows]

    @staticmethod
    def _to_model(row: sqlite3.Row) -> Notification:
        return Notification(
            event_id=row["event_id"],
            notification_type=row["notification_type"],
            device_id=row["device_id"],
            payload=json.loads(row["payload"]),
            subject=row["subject"],
            body=row["body"],
            priority=row["priority"],
            primary_recipient=row["primary_recipient"],
            fallback_recipient=row["fallback_recipient"],
            current_recipient=row["current_recipient"],
            status=row["status"],
            attempts=row["attempts"],
            retry_count=row["retry_count"],
            message_id=row["message_id"],
            error_code=row["error_code"],
            error_message=row["error_message"],
            created_at=row["created_at"],
            updated_at=row["updated_at"],
            next_attempt_at=row["next_attempt_at"],
            claimed_at=row["claimed_at"],
            sent_at=row["sent_at"],
        )


# ── 线程安全绑定 ────────────────────────────────────────────────────
# HTTP 接入线程与发送调度线程共用同一个 Repository 实例，这里统一加锁，
# 避免把 with self._lock 撒进每个方法体（漏一个就是一个难复现的偶发错误）。

def _thread_safe(fn):
    def wrapper(self, *args, **kwargs):
        with self._lock:
            return fn(self, *args, **kwargs)

    wrapper.__name__ = fn.__name__
    wrapper.__doc__ = fn.__doc__
    return wrapper


for _name in (
    "enqueue", "get", "exists", "claim_next", "recover_stale",
    "mark_sent", "mark_recipient_rejected", "requeue", "mark_unknown",
    "mark_failed", "mark_auth_required", "resume_auth_required",
    "stats", "sent_since", "has_due", "recent", "close",
):
    setattr(Repository, _name, _thread_safe(getattr(Repository, _name)))


# ── 考勤核验与清理动作（第二阶段新增） ──────────────────────────────
# 与通知队列同库不同表：事件（notification）是「要发什么邮件」，
# 动作（action）是「要在这台设备上执行什么」，两者生命周期不同。

ACTION_SCHEMA = """
CREATE TABLE IF NOT EXISTS attendance (
    task_id          TEXT PRIMARY KEY,
    device_id        TEXT NOT NULL,
    attendance_date  TEXT NOT NULL,
    evidence_source  TEXT NOT NULL,
    evidence_time    TEXT NOT NULL,
    evidence_ref     TEXT,
    verified_at      TEXT NOT NULL,
    result           TEXT NOT NULL,
    created_at       TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS actions (
    action_id    TEXT PRIMARY KEY,
    device_id    TEXT NOT NULL,
    task_id      TEXT NOT NULL,
    action_type  TEXT NOT NULL,
    nonce        TEXT NOT NULL,
    status       TEXT NOT NULL,
    created_at   TEXT NOT NULL,
    expires_at   TEXT NOT NULL,
    delivered_at TEXT,
    result_at    TEXT,
    result_detail TEXT,
    UNIQUE(task_id, action_type)
);
CREATE INDEX IF NOT EXISTS idx_actions_pending ON actions(device_id, status, expires_at);
"""

ACTION_PENDING = "PENDING"
ACTION_DELIVERED = "DELIVERED"
ACTION_RUNNING = "CLEANUP_RUNNING"
ACTION_SUCCESS = "CLEANUP_SUCCESS"
ACTION_FAILED = "CLEANUP_FAILED"
ACTION_EXPIRED = "EXPIRED"
ACTION_REJECTED = "REJECTED"

ALLOWED_ACTIONS = {"FORCE_STOP_FEISHU"}
