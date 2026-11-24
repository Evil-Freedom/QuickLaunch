"""checkin_unknown 的提醒节制。

问题：Android 每次拉起飞书都会上报一条 checkin_unknown，直接入队就是每启动一次
一封「待确认」邮件（实测一天 12 封），噪音大到没人看。

口径（按需求）：
* 同一台设备、同一个考勤日 = 一个「待确认窗口」，只发一次首次通知；
* 之后每满 30 分钟最多一条提醒，直到窗口被关闭；
* 窗口只在 **真实考勤成功** 或 **清理完成** 时关闭；
* 「提醒时间」只在邮件真的发出去之后才推进 —— 发信失败不能让系统误以为已提醒。

并发与幂等：通知 event_id 由 (设备, 日期, 30 分钟桶) 决定，同一个桶内的并发请求
只会插进同一行（notification 表 event_id 主键），天然去重。
"""

from __future__ import annotations

import re
import sqlite3
import threading

from .repository import now_iso

REMIND_INTERVAL_S = 1800
OPEN = "OPEN"
CLOSED = "CLOSED"

SCHEMA = """
CREATE TABLE IF NOT EXISTS checkin_alerts (
    alert_key         TEXT PRIMARY KEY,
    device_id         TEXT NOT NULL,
    attendance_date   TEXT NOT NULL,
    task_id           TEXT NOT NULL,
    status            TEXT NOT NULL,
    closed_reason     TEXT,
    first_notified_at TEXT,
    last_reminded_at  TEXT,
    reminder_count    INTEGER NOT NULL DEFAULT 0,
    created_at        TEXT NOT NULL,
    updated_at        TEXT NOT NULL
);
"""

_EVENT_RE = re.compile(r"^unknown-(.+)-(\d{4}-\d{2}-\d{2})-(\d+)$")


def key_of(device_id: str, date: str) -> str:
    return f"{device_id}:{date}"


def event_id_of(device_id: str, date: str, now_ts: float) -> str:
    return f"unknown-{device_id}-{date}-{int(now_ts // REMIND_INTERVAL_S)}"


class AlertStore:
    def __init__(self, path: str):
        self._lock = threading.RLock()
        self._conn = sqlite3.connect(path, timeout=10, isolation_level=None,
                                     check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        self._conn.execute("PRAGMA journal_mode=WAL")
        self._conn.execute("PRAGMA busy_timeout=5000")
        self._conn.executescript(SCHEMA)

    def close_conn(self) -> None:
        with self._lock:
            self._conn.close()

    # ── 决策 ────────────────────────────────────────────────────────

    def observe(self, device_id: str, date: str, task_id: str,
                now_ts: float | None = None) -> tuple[str, str]:
        """返回 (decision, event_id)。decision ∈ first / remind / suppress / closed。

        [now_ts] 只用于测试里模拟「时间已经过去」；生产调用传 None 取当前时间。
        """
        import time
        key = key_of(device_id, date)
        now_ts = time.time() if now_ts is None else now_ts
        event_id = event_id_of(device_id, date, now_ts)
        with self._lock:
            self._conn.execute(
                """INSERT OR IGNORE INTO checkin_alerts
                   (alert_key, device_id, attendance_date, task_id, status,
                    reminder_count, created_at, updated_at)
                   VALUES (?,?,?,?,?,0,?,?)""",
                (key, device_id, date, task_id, OPEN, now_iso(), now_iso()),
            )
            row = self._conn.execute(
                "SELECT * FROM checkin_alerts WHERE alert_key=?", (key,)
            ).fetchone()
            if row is None:
                return "suppress", event_id
            if row["status"] == CLOSED:
                return "closed", event_id

            last = row["last_reminded_at"] or row["first_notified_at"]
            if last is None:
                decision = "first"
            else:
                from datetime import datetime, timezone
                try:
                    last_dt = datetime.fromisoformat(last)
                    if last_dt.tzinfo is None:      # SQLite datetime() 出来的是裸时间，按 UTC 解释
                        last_dt = last_dt.replace(tzinfo=timezone.utc)
                except ValueError:
                    last_dt = None
                from datetime import datetime as _dt
                now_dt = _dt.now(timezone.utc) if now_ts is None else                     _dt.fromtimestamp(now_ts, timezone.utc)
                due = last_dt is None or (now_dt - last_dt).total_seconds() >= REMIND_INTERVAL_S
                decision = "remind" if due else "suppress"

            if decision == "suppress":
                return decision, event_id

            # 同一时间桶内已经有邮件（可能还在发送/已发送）就不再重复入队
            exists = self._conn.execute(
                "SELECT 1 FROM notifications WHERE event_id=?", (event_id,)
            ).fetchone()
            if exists is not None:
                return "suppress", event_id
            return decision, event_id

    # ── 发送成功后推进时间 ──────────────────────────────────────────

    def on_sent(self, event_id: str) -> bool:
        """由 dispatcher 在邮件真正发出后调用；返回是否匹配到待确认窗口。"""
        m = _EVENT_RE.match(event_id or "")
        if m is None:
            return False
        device_id, date = m.group(1), m.group(2)
        key = key_of(device_id, date)
        ts = now_iso()
        with self._lock:
            row = self._conn.execute(
                "SELECT * FROM checkin_alerts WHERE alert_key=?", (key,)
            ).fetchone()
            if row is None:
                return False
            if row["first_notified_at"] is None:
                self._conn.execute(
                    """UPDATE checkin_alerts
                       SET first_notified_at=?, last_reminded_at=?, updated_at=?
                       WHERE alert_key=?""",
                    (ts, ts, ts, key),
                )
            else:
                self._conn.execute(
                    """UPDATE checkin_alerts
                       SET last_reminded_at=?, reminder_count=reminder_count+1, updated_at=?
                       WHERE alert_key=?""",
                    (ts, ts, key),
                )
            return True

    # ── 关闭 ────────────────────────────────────────────────────────

    def close(self, device_id: str, date: str, reason: str) -> bool:
        """关闭窗口。窗口不存在时也要落一行 CLOSED —— 否则当天第一次就核验成功的场景
        会在随后出现的 checkin_unknown 上重新开窗发信。"""
        key = key_of(device_id, date)
        with self._lock:
            self._conn.execute(
                """INSERT OR IGNORE INTO checkin_alerts
                   (alert_key, device_id, attendance_date, task_id, status,
                    reminder_count, created_at, updated_at)
                   VALUES (?,?,?,?,?,0,?,?)""",
                (key, device_id, date, "", CLOSED, now_iso(), now_iso()),
            )
            cur = self._conn.execute(
                """UPDATE checkin_alerts SET status=?, closed_reason=?, updated_at=?
                   WHERE alert_key=? AND status=?""",
                (CLOSED, reason, now_iso(), key, OPEN),
            )
            return cur.rowcount == 1

    def get(self, device_id: str, date: str) -> dict | None:
        with self._lock:
            row = self._conn.execute(
                "SELECT * FROM checkin_alerts WHERE alert_key=?", (key_of(device_id, date),)
            ).fetchone()
            return dict(row) if row else None

    def stats(self) -> dict[str, int]:
        with self._lock:
            rows = self._conn.execute(
                "SELECT status, COUNT(*) AS n FROM checkin_alerts GROUP BY status"
            ).fetchall()
            return {r["status"]: r["n"] for r in rows}
