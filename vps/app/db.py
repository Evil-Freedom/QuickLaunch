"""
SQLite 访问层。

刻意不用 ORM：本服务只有 5 张表、单进程、2 核 2GB 的机器，
SQLAlchemy 带来的抽象收益抵不过「多一层依赖 + 多一层版本冲突」。

并发模型：uvicorn 单 worker + WAL 模式。写操作全部包在 `with conn` 里，
SQLite 的写锁天然把并发写串行化 —— 对「一个人用手机改改规则」这个量级完全够。
真到了多 worker 的场景再换 Postgres，而不是现在就预防性引入。
"""

from __future__ import annotations

import json
import os
import secrets
import sqlite3
import time
from contextlib import contextmanager
from typing import Any, Iterator

DB_PATH = os.environ.get("QL_DB_PATH", "/data/quicklaunch.db")

SCHEMA = """
CREATE TABLE IF NOT EXISTS devices (
    id          TEXT PRIMARY KEY,
    name        TEXT NOT NULL DEFAULT '',
    secret      TEXT NOT NULL,
    created_at  INTEGER NOT NULL,
    revoked     INTEGER NOT NULL DEFAULT 0,
    last_seen_at INTEGER NOT NULL DEFAULT 0,
    last_version INTEGER NOT NULL DEFAULT 0,
    last_note   TEXT NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS rules (
    id          INTEGER PRIMARY KEY,
    payload     TEXT NOT NULL,
    updated_at  INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS config_versions (
    version     INTEGER PRIMARY KEY,
    payload     TEXT NOT NULL,
    created_at  INTEGER NOT NULL,
    note        TEXT NOT NULL DEFAULT '',
    actor       TEXT NOT NULL DEFAULT 'admin'
);

CREATE TABLE IF NOT EXISTS reports (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id   TEXT NOT NULL,
    version     INTEGER NOT NULL,
    ok          INTEGER NOT NULL,
    detail      TEXT NOT NULL DEFAULT '',
    at          INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS audit (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    at          INTEGER NOT NULL,
    actor       TEXT NOT NULL DEFAULT '',
    action      TEXT NOT NULL,
    detail      TEXT NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS meta (
    key         TEXT PRIMARY KEY,
    value       TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_reports_device ON reports(device_id, at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_at ON audit(at DESC);
"""

# 重试策略的默认值，与 Android 侧 LaunchPolicy 的默认值逐项对齐。
# 两处不一致的后果是「管理界面显示 3 次、手机实际重试 2 次」，非常难查。
DEFAULT_RETRY = {
    "enabled": True,
    "maxAttempts": 3,
    "firstVerifyDelayMs": 2000,
    "verifyWindowMs": 6000,
    "retryDelaysMs": [5000, 15000],
    "totalTimeoutMs": 90000,
    "notifyOnFailure": True,
    "allowManualRetry": True,
}


def new_id(prefix: str = "dev") -> str:
    return f"{prefix}_{secrets.token_hex(8)}"


def new_secret() -> str:
    """32 字节随机数的 Base64。与 Android 侧 B64.decode 兼容（标准字母表 + padding）。"""
    import base64

    return base64.b64encode(secrets.token_bytes(32)).decode("ascii")


@contextmanager
def conn() -> Iterator[sqlite3.Connection]:
    os.makedirs(os.path.dirname(DB_PATH) or ".", exist_ok=True)
    c = sqlite3.connect(DB_PATH, timeout=15)
    c.row_factory = sqlite3.Row
    c.execute("PRAGMA journal_mode=WAL")
    c.execute("PRAGMA foreign_keys=ON")
    try:
        with c:
            yield c
    finally:
        c.close()


def init_db() -> None:
    with conn() as c:
        c.executescript(SCHEMA)
        # 首次启动写入默认策略，避免「配置里没有 retry 字段 → 手机用默认值」
        # 这种两边不一致的隐性状态
        row = c.execute("SELECT value FROM meta WHERE key='retry'").fetchone()
        if row is None:
            c.execute(
                "INSERT INTO meta(key, value) VALUES('retry', ?)",
                (json.dumps(DEFAULT_RETRY),),
            )
        v = c.execute("SELECT value FROM meta WHERE key='current_version'").fetchone()
        if v is None:
            c.execute("INSERT INTO meta(key, value) VALUES('current_version','0')")


# ── 配置版本 ────────────────────────────────────────────────────────


def current_version() -> int:
    with conn() as c:
        row = c.execute("SELECT value FROM meta WHERE key='current_version'").fetchone()
        return int(row["value"]) if row else 0


def retry_policy() -> dict[str, Any]:
    with conn() as c:
        row = c.execute("SELECT value FROM meta WHERE key='retry'").fetchone()
    if not row:
        return dict(DEFAULT_RETRY)
    try:
        merged = dict(DEFAULT_RETRY)
        merged.update(json.loads(row["value"]))
        return merged
    except Exception:
        return dict(DEFAULT_RETRY)


def set_retry_policy(policy: dict[str, Any], actor: str = "admin") -> None:
    with conn() as c:
        c.execute(
            "UPDATE meta SET value=? WHERE key='retry'", (json.dumps(policy, ensure_ascii=False),)
        )
    log_audit(actor, "retry.update", json.dumps(policy, ensure_ascii=False))


def build_payload() -> dict[str, Any]:
    """把当前所有规则与重试策略装配成下发给手机的 payload。"""
    with conn() as c:
        rows = c.execute("SELECT id, payload FROM rules ORDER BY id").fetchall()
    rules = []
    for r in rows:
        try:
            body = json.loads(r["payload"])
        except Exception:
            continue
        body["id"] = r["id"]
        rules.append(body)
    return {"rules": rules, "retry": retry_policy()}


def publish(note: str, actor: str = "admin") -> int:
    """
    生成一个新版本并持久化。

    **每次都生成新版本号**（而不是原地改当前版本）：版本号单调是客户端
    「拒绝回滚」判据的唯一依据，原地改会让已同步的手机永远收不到这次修改。
    """
    payload = build_payload()
    with conn() as c:
        row = c.execute("SELECT value FROM meta WHERE key='current_version'").fetchone()
        nxt = (int(row["value"]) if row else 0) + 1
        c.execute(
            "INSERT INTO config_versions(version, payload, created_at, note, actor) VALUES(?,?,?,?,?)",
            (nxt, json.dumps(payload, ensure_ascii=False), int(time.time() * 1000), note, actor),
        )
        c.execute("UPDATE meta SET value=? WHERE key='current_version'", (str(nxt),))
    log_audit(actor, "config.publish", f"v{nxt} {note}")
    return nxt


def current_payload() -> dict[str, Any]:
    v = current_version()
    if v == 0:
        return build_payload()
    with conn() as c:
        row = c.execute("SELECT payload FROM config_versions WHERE version=?", (v,)).fetchone()
    if not row:
        return build_payload()
    try:
        return json.loads(row["payload"])
    except Exception:
        return build_payload()


def versions(limit: int = 50) -> list[dict[str, Any]]:
    with conn() as c:
        rows = c.execute(
            "SELECT version, created_at, note, actor FROM config_versions "
            "ORDER BY version DESC LIMIT ?",
            (limit,),
        ).fetchall()
    return [dict(r) for r in rows]


def rollback_to(version: int, actor: str = "admin") -> int:
    """
    回滚：把历史版本的内容**作为新版本**发布，而不是把版本号改回去。

    这一点很关键 —— 客户端的版本判据是「新版本必须严格大于本地版本」，
    直接改版本号会让回滚在手机上完全不生效。
    """
    with conn() as c:
        row = c.execute("SELECT payload FROM config_versions WHERE version=?", (version,)).fetchone()
        if not row:
            raise ValueError(f"版本 {version} 不存在")
        payload = json.loads(row["payload"])
        cur = c.execute("SELECT value FROM meta WHERE key='current_version'").fetchone()
        nxt = (int(cur["value"]) if cur else 0) + 1
        c.execute(
            "INSERT INTO config_versions(version, payload, created_at, note, actor) VALUES(?,?,?,?,?)",
            (nxt, json.dumps(payload, ensure_ascii=False), int(time.time() * 1000),
             f"回滚自 v{version}", actor),
        )
        c.execute("UPDATE meta SET value=? WHERE key='current_version'", (str(nxt),))
    log_audit(actor, "config.rollback", f"v{version} -> v{nxt}")
    return nxt


# ── 规则 ────────────────────────────────────────────────────────────


def list_rules() -> list[dict[str, Any]]:
    with conn() as c:
        rows = c.execute("SELECT id, payload, updated_at FROM rules ORDER BY id").fetchall()
    out = []
    for r in rows:
        try:
            body = json.loads(r["payload"])
        except Exception:
            body = {}
        body["id"] = r["id"]
        body["_updated_at"] = r["updated_at"]
        out.append(body)
    return out


def get_rule(rule_id: int) -> dict[str, Any] | None:
    with conn() as c:
        row = c.execute("SELECT id, payload FROM rules WHERE id=?", (rule_id,)).fetchone()
    if not row:
        return None
    body = json.loads(row["payload"])
    body["id"] = row["id"]
    return body


def upsert_rule(rule_id: int, body: dict[str, Any], actor: str = "admin") -> None:
    body = {k: v for k, v in body.items() if k != "id"}
    with conn() as c:
        c.execute(
            "INSERT INTO rules(id, payload, updated_at) VALUES(?,?,?) "
            "ON CONFLICT(id) DO UPDATE SET payload=excluded.payload, updated_at=excluded.updated_at",
            (rule_id, json.dumps(body, ensure_ascii=False), int(time.time() * 1000)),
        )
    log_audit(actor, "rule.update", f"#{rule_id} {json.dumps(body, ensure_ascii=False)}")


def delete_rule(rule_id: int, actor: str = "admin") -> None:
    with conn() as c:
        c.execute("DELETE FROM rules WHERE id=?", (rule_id,))
    log_audit(actor, "rule.delete", f"#{rule_id}")


def import_rules(items: list[dict[str, Any]], actor: str = "admin") -> int:
    """
    从手机端导出的规则批量导入（首次建库用）。

    保留 `id`：客户端按 id 定位规则，id 对不上就会把修改应用到不存在的规则上，
    而 mergePatch 找不到规则时只会记一条失败，用户看到的是「同步成功但没变化」。
    """
    n = 0
    for item in items:
        rid = item.get("id")
        if not isinstance(rid, int) or rid <= 0:
            continue
        upsert_rule(rid, item, actor)
        n += 1
    log_audit(actor, "rule.import", f"{n} 条")
    return n


# ── 设备 ────────────────────────────────────────────────────────────


def list_devices() -> list[dict[str, Any]]:
    with conn() as c:
        rows = c.execute(
            "SELECT id, name, created_at, revoked, last_seen_at, last_version, last_note "
            "FROM devices ORDER BY created_at DESC"
        ).fetchall()
    return [dict(r) for r in rows]


def create_device(name: str, actor: str = "admin") -> dict[str, Any]:
    did = new_id()
    sec = new_secret()
    with conn() as c:
        c.execute(
            "INSERT INTO devices(id, name, secret, created_at) VALUES(?,?,?,?)",
            (did, name.strip()[:40] or "未命名设备", sec, int(time.time() * 1000)),
        )
    log_audit(actor, "device.create", f"{did} {name}")
    return {"id": did, "secret": sec}


def get_device(device_id: str) -> dict[str, Any] | None:
    with conn() as c:
        row = c.execute("SELECT * FROM devices WHERE id=?", (device_id,)).fetchone()
    return dict(row) if row else None


def revoke_device(device_id: str, revoked: bool, actor: str = "admin") -> None:
    with conn() as c:
        c.execute("UPDATE devices SET revoked=? WHERE id=?", (1 if revoked else 0, device_id))
    log_audit(actor, "device.revoke" if revoked else "device.restore", device_id)


def delete_device(device_id: str, actor: str = "admin") -> None:
    with conn() as c:
        c.execute("DELETE FROM devices WHERE id=?", (device_id,))
    log_audit(actor, "device.delete", device_id)


def touch_device(device_id: str, note: str = "") -> None:
    with conn() as c:
        c.execute(
            "UPDATE devices SET last_seen_at=? WHERE id=?",
            (int(time.time() * 1000), device_id),
        )


# ── 回报与审计 ──────────────────────────────────────────────────────


def add_report(device_id: str, version: int, ok: bool, detail: str) -> None:
    with conn() as c:
        c.execute(
            "INSERT INTO reports(device_id, version, ok, detail, at) VALUES(?,?,?,?,?)",
            (device_id, version, 1 if ok else 0, detail[:500], int(time.time() * 1000)),
        )
        c.execute(
            "UPDATE devices SET last_version=?, last_note=?, last_seen_at=? WHERE id=?",
            (version, detail[:300], int(time.time() * 1000), device_id),
        )


def recent_reports(limit: int = 100) -> list[dict[str, Any]]:
    with conn() as c:
        rows = c.execute(
            "SELECT device_id, version, ok, detail, at FROM reports ORDER BY at DESC LIMIT ?",
            (limit,),
        ).fetchall()
    return [dict(r) for r in rows]


def log_audit(actor: str, action: str, detail: str = "") -> None:
    with conn() as c:
        c.execute(
            "INSERT INTO audit(at, actor, action, detail) VALUES(?,?,?,?)",
            (int(time.time() * 1000), actor[:60], action[:60], detail[:1000]),
        )


def recent_audit(limit: int = 200) -> list[dict[str, Any]]:
    with conn() as c:
        rows = c.execute(
            "SELECT at, actor, action, detail FROM audit ORDER BY at DESC LIMIT ?", (limit,)
        ).fetchall()
    return [dict(r) for r in rows]
