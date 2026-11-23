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
    -- 上一次「设备确认已应用」的那份值。三方合并的基线：
    -- payload == baseline 表示网页没改过，device != baseline 表示手机改过。
    baseline    TEXT NOT NULL DEFAULT '',
    updated_at  INTEGER NOT NULL
);

-- 待下发的删除指令。
--
-- 为什么不能直接 DELETE FROM rules 就完事：手机才是权威方，它下次上报规则清单时
-- 会把这条规则又带回来（服务端看到「本地没有 → 建档」），删了等于没删。
-- 所以删除必须作为一条指令下发给设备，等设备确认应用过含这条指令的版本后再清理。
CREATE TABLE IF NOT EXISTS deletions (
    rule_id     INTEGER PRIMARY KEY,
    created_at  INTEGER NOT NULL
);

-- 双端改了同一条规则时的待裁决记录。
-- 存在的意义：**不静默覆盖**。谁改得更晚并不代表谁更对，
-- 用户必须自己看一眼两边的差异再决定。
CREATE TABLE IF NOT EXISTS conflicts (
    rule_id         INTEGER PRIMARY KEY,
    server_payload  TEXT NOT NULL,
    device_payload  TEXT NOT NULL,
    baseline        TEXT NOT NULL,
    detected_at     INTEGER NOT NULL
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


# 自动解锁策略的字段白名单：只有这两个开关。
# 永远不允许出现 pin / failures / lastResult 这类字段 ——
# 前者远程不能设置，后者是设备上报的状态（下发侧写进去只会造成「看起来改成功了」）。
UNLOCK_POLICY_FIELDS = {"enabled", "chargingOnly"}

DEFAULT_UNLOCK_POLICY = {"enabled": False, "chargingOnly": False}


def _migrate(c: sqlite3.Connection) -> None:
    """幂等迁移：给已有库补新列。全部是 ADD COLUMN，存量数据零丢失。"""
    rcols = {r[1] for r in c.execute("PRAGMA table_info(rules)")}
    if "baseline" not in rcols:
        # 老库补列：已有规则视为「网页与设备一致」，即 baseline = payload。
        # 这样升级后第一次手机上报不会把所有规则都误判成冲突。
        c.execute("ALTER TABLE rules ADD COLUMN baseline TEXT NOT NULL DEFAULT ''")
        c.execute("UPDATE rules SET baseline = payload WHERE baseline = ''")

    cols = {r[1] for r in c.execute("PRAGMA table_info(devices)")}
    for name, ddl in [
        ("model", "ALTER TABLE devices ADD COLUMN model TEXT NOT NULL DEFAULT ''"),
        ("android_version", "ALTER TABLE devices ADD COLUMN android_version TEXT NOT NULL DEFAULT ''"),
        ("app_version", "ALTER TABLE devices ADD COLUMN app_version TEXT NOT NULL DEFAULT ''"),
        ("unlock_json", "ALTER TABLE devices ADD COLUMN unlock_json TEXT NOT NULL DEFAULT ''"),
    ]:
        if name not in cols:
            c.execute(ddl)
    if not c.execute("SELECT 1 FROM meta WHERE key='unlock'").fetchone():
        c.execute("INSERT INTO meta(key,value) VALUES('unlock',?)",
                  (json.dumps(DEFAULT_UNLOCK_POLICY),))
    # 「自动解锁策略是否已被本控制台托管」。
    #
    # 0 = 从未在网页上设置过 → payload 里**不带** unlock 字段，手机保持本地设置。
    # 1 = 用户点过保存 → 开始下发。
    #
    # 为什么必须有这个标志：默认策略是 enabled=false，若无条件下发，
    # 首次部署就会把用户在手机上手动开启的自动解锁关掉 ——
    # 真机实测发生过，用户只会看到「功能莫名其妙没了」。
    if not c.execute("SELECT 1 FROM meta WHERE key='unlock_managed'").fetchone():
        c.execute("INSERT INTO meta(key,value) VALUES('unlock_managed','0')")


def init_db() -> None:
    with conn() as c:
        c.executescript(SCHEMA)
        _migrate(c)
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

    # 全新部署必须有一个可下发的版本。
    #
    # 客户端 `VpsEnvelope.parse` 要求 `version > 0`（0 是「非法/未发布」的哨兵），
    # 而刚建好的库里就是 0 —— 新手机第一次同步会永远收到 E_VERSION，
    # 表现为「配对成功但拉不到配置」。这里自动做一次初始发布，
    # 让「部署完成」等价于「存在 v1 空配置」。
    if current_version() == 0:
        publish("初始配置（部署自动发布）", actor="system")


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


def unlock_policy() -> dict[str, Any]:
    with conn() as c:
        row = c.execute("SELECT value FROM meta WHERE key='unlock'").fetchone()
    if not row:
        return dict(DEFAULT_UNLOCK_POLICY)
    try:
        merged = dict(DEFAULT_UNLOCK_POLICY)
        merged.update(json.loads(row["value"]))
        # 只保留白名单字段：防御「数据库被手工塞进 pin 字段后下发出去」
        return {k: bool(v) for k, v in merged.items() if k in UNLOCK_POLICY_FIELDS}
    except Exception:
        return dict(DEFAULT_UNLOCK_POLICY)


def unlock_managed() -> bool:
    """自动解锁策略是否已被本控制台接管（用户在网页上保存过）。"""
    with conn() as c:
        row = c.execute("SELECT value FROM meta WHERE key='unlock_managed'").fetchone()
    return bool(row) and row["value"] == "1"


def set_unlock_policy(policy: dict[str, Any], actor: str = "admin") -> None:
    cleaned = {k: bool(v) for k, v in policy.items() if k in UNLOCK_POLICY_FIELDS}
    with conn() as c:
        c.execute("UPDATE meta SET value=? WHERE key='unlock'", (json.dumps(cleaned),))
        # 一旦在这里保存过，就视为「用户接管了策略」，之后每次都下发
        c.execute(
            "INSERT INTO meta(key,value) VALUES('unlock_managed','1') "
            "ON CONFLICT(key) DO UPDATE SET value='1'"
        )
    log_audit(actor, "unlock.update", json.dumps(cleaned))


# 与 Android 侧 `RemoteProtocol.EDITABLE_FIELDS` **逐字一致**。
#
# 下发的规则只允许含这些字段：客户端对越权字段直接返回 409（E_FORBIDDEN_FIELD），
# 一条规则只要带上 `createdAt` 或 `targetPackage`，整条下发就会失败。
# 设备上报的规则清单是完整对象，必须在这里裁掉多余字段 ——
# 真机踩过：导入 4 条后发布，3 条报 E_FORBIDDEN_FIELD，只有被网页改过的那条成功。
RULE_EDITABLE_FIELDS = {
    "timeHour", "timeMinute", "repeatMode", "repeatDays",
    "skipHolidays", "randomWindow", "windowStartMin", "windowEndMin", "enabled",
    "shiftAnchor", "shiftOffsetMin",
}


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
        body = {k: v for k, v in body.items() if k in RULE_EDITABLE_FIELDS}
        body["id"] = r["id"]
        rules.append(body)
    with conn() as c:
        deleted = [r["rule_id"] for r in c.execute("SELECT rule_id FROM deletions")]

    payload: dict[str, Any] = {"rules": rules, "retry": retry_policy()}
    if deleted:
        # 与 rules 同级：客户端把它当「指令」而不是规则本身。
        # 没有这条，网页上的删除在手机下次上报后会被原样复活。
        payload["deleted"] = deleted
    # 没被托管就不带这个字段：客户端对「缺 unlock」的语义是「不动本地」
    if unlock_managed():
        payload["unlock"] = unlock_policy()
    return payload


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


# 只用于「看板展示」的字段：需要存下来（否则网页上只能看到 #7 看不到「飞书」），
# 但**不参与下发**（客户端白名单里没有它们，带上就 409）。
DISPLAY_FIELDS = {"name", "targetPackage", "targetAppName"}


def _rule_for_storage(raw: dict[str, Any]) -> dict[str, Any]:
    """存库形态 = 可编辑字段 + 展示字段。"""
    keep = RULE_EDITABLE_FIELDS | DISPLAY_FIELDS
    return {k: raw[k] for k in keep if k in raw}


# 可编辑字段的默认值，与 Android `Automation` 的构造默认值一致。
# 用途见 [_normalize_rule]：缺字段必须按默认值参与比较，而不是「缺了就跳过」。
RULE_DEFAULTS: dict[str, Any] = {
    "timeHour": 0,
    "timeMinute": 0,
    "repeatMode": "daily",
    "repeatDays": 0,
    "skipHolidays": False,
    "enabled": True,
    "randomWindow": False,
    "windowStartMin": 0,
    "windowEndMin": 0,
    "shiftAnchor": 0,
    "shiftOffsetMin": 0,
}


def _normalize_rule(body: dict[str, Any]) -> dict[str, Any]:
    """
    取规则里「参与比较」的部分：可编辑字段，**缺失的按默认值补齐**。

    为什么必须补齐：老记录里可能只存过 6 个字段（早期版本），
    而 baseline 是完整 11 个字段。如果比较时「有什么取什么」，
    这两份永远不相等 —— 于是每次同步都判「网页改过」，
    手机上的真实修改全部变成假冲突。真机踩过一次，四条规则全被误判。
    """
    return {k: body.get(k, RULE_DEFAULTS.get(k)) for k in RULE_EDITABLE_FIELDS}


def sync_device_rules(
    device_rules: list[dict[str, Any]] | None,
    applied_version: int,
    actor: str = "device",
) -> dict[str, int]:
    """
    设备上报规则清单 → 与本地做**三方合并**（server / device / baseline）。

    为什么需要 baseline：只看「server 与 device 是否相同」无法区分
    「手机改了」「网页改了」「两边都改了」。加上「上次确认下发的值」这个基线，
    三种情况才可判：

    | device vs baseline | server vs baseline | 结论 |
    | --- | --- | --- |
    | 相同 | 相同 | 一致，无事 |
    | 不同 | 相同 | 只有手机改了 → 采纳手机 |
    | 相同 | 不同 | 只有网页改了 → 保留网页（等设备下次同步） |
    | 不同 | 不同 | **两边都改了 → 记冲突，不覆盖** |

    安全边界：
    - 不存在于上报里的规则**一律不删**（设备可能只是还没同步到，删了就是数据丢失）
    - 冲突时不写 payload，只记一条待裁决记录
    - 本地没有的 id 视为「手机上新建的」，直接建档

    @return {"added": n, "adopted": n, "conflicts": n, "ignored": n}
    """
    stats = {"added": 0, "adopted": 0, "conflicts": 0, "ignored": 0}
    if not device_rules:
        return stats

    now = int(time.time() * 1000)
    with conn() as c:
        # 设备还没拉到最新发布时，它上报的是旧值 —— 拿旧值做三方比较
        # 只会把「还没同步」误判成「两边都改了」。
        cur = c.execute("SELECT value FROM meta WHERE key='current_version'").fetchone()
        cur_version = int(cur["value"]) if cur else 0
        if applied_version < cur_version:
            stats["ignored"] = len(device_rules)
            return stats

        for r in device_rules:
            if not isinstance(r, dict):
                continue
            try:
                rid = int(r.get("id", 0))
            except (TypeError, ValueError):
                continue
            if rid <= 0:
                continue

            dev = _normalize_rule(r)
            row = c.execute("SELECT payload, baseline FROM rules WHERE id=?", (rid,)).fetchone()

            if row is None:
                # 服务端从未见过 → 手机上新建的规则，建档（baseline 同值，视为已对齐）
                stored = _rule_for_storage(r)
                c.execute(
                    "INSERT INTO rules(id, payload, baseline, updated_at) VALUES(?,?,?,?)",
                    (rid, json.dumps(stored, ensure_ascii=False),
                     json.dumps(dev, ensure_ascii=False), now),
                )
                stats["added"] += 1
                continue

            try:
                server = _normalize_rule(json.loads(row["payload"]))
            except Exception:
                server = {}
            try:
                base = _normalize_rule(json.loads(row["baseline"])) if row["baseline"] else None
            except Exception:
                base = None

            if dev == server:
                # 可编辑字段一致，但可能缺展示字段（早期记录只存了白名单，
                # 看板上只能看到「规则 #2」看不到「飞书」）。
                # 补上展示字段，不算变更、不影响 baseline。
                stored_now = _rule_for_storage(r)
                try:
                    existing_payload = json.loads(row["payload"])
                except Exception:
                    existing_payload = {}
                if existing_payload != stored_now:
                    missing = any(k in stored_now and k not in existing_payload
                                  for k in DISPLAY_FIELDS)
                    if missing:
                        c.execute(
                            "UPDATE rules SET payload=? WHERE id=?",
                            (json.dumps(stored_now, ensure_ascii=False), rid),
                        )
                continue

            if base is None or server == base:
                # 只有手机改了 → 采纳（存完整形态，比较用白名单形态）
                stored = _rule_for_storage(r)
                c.execute(
                    "UPDATE rules SET payload=?, baseline=?, updated_at=? WHERE id=?",
                    (json.dumps(stored, ensure_ascii=False),
                     json.dumps(dev, ensure_ascii=False), now, rid),
                )
                c.execute("DELETE FROM conflicts WHERE rule_id=?", (rid,))
                stats["adopted"] += 1
                continue

            if dev == base:
                continue  # 只有网页改了，等设备下次同步

            # 两边都改了 → 记录冲突，**不动 payload**
            c.execute(
                "INSERT INTO conflicts(rule_id, server_payload, device_payload, baseline, detected_at) "
                "VALUES(?,?,?,?,?) ON CONFLICT(rule_id) DO UPDATE SET "
                "server_payload=excluded.server_payload, device_payload=excluded.device_payload, "
                "baseline=excluded.baseline, detected_at=excluded.detected_at",
                (rid, json.dumps(server, ensure_ascii=False),
                 json.dumps(dev, ensure_ascii=False),
                 json.dumps(base or {}, ensure_ascii=False), now),
            )
            stats["conflicts"] += 1

    if stats["added"] or stats["adopted"] or stats["conflicts"]:
        log_audit(
            actor, "rule.sync",
            f"新增 {stats['added']} / 采纳设备 {stats['adopted']} / 冲突 {stats['conflicts']}"
            + (f" / 跳过 {stats['ignored']}" if stats["ignored"] else ""),
        )
    return stats


def list_conflicts() -> list[dict[str, Any]]:
    """所有待裁决的规则冲突。"""
    with conn() as c:
        rows = c.execute(
            "SELECT rule_id, server_payload, device_payload, baseline, detected_at "
            "FROM conflicts ORDER BY detected_at DESC"
        ).fetchall()
    out = []
    for r in rows:
        d = dict(r)
        for k in ("server_payload", "device_payload", "baseline"):
            try:
                d[k] = json.loads(d[k])
            except Exception:
                d[k] = {}
        out.append(d)
    return out


def resolve_conflict(rule_id: int, keep: str, actor: str = "admin") -> bool:
    """
    裁决一条冲突。keep="server" 保留网页版，"device" 保留手机版。

    两种选择都要把 baseline 对齐到最终采用的那份 —— 否则下一轮同步会
    立刻又判出同一个冲突。
    """
    with conn() as c:
        row = c.execute(
            "SELECT server_payload, device_payload FROM conflicts WHERE rule_id=?", (rule_id,)
        ).fetchone()
        if row is None:
            return False
        chosen = row["server_payload"] if keep == "server" else row["device_payload"]
        # 保留原记录的展示字段（name / targetPackage / targetAppName）：
        # 冲突记录里只存可编辑字段，直接覆盖会让看板上的「飞书」变回「规则 #2」。
        existing = c.execute("SELECT payload FROM rules WHERE id=?", (rule_id,)).fetchone()
        stored: dict[str, Any] = {}
        if existing:
            try:
                stored = json.loads(existing["payload"])
            except Exception:
                stored = {}
        try:
            chosen_body = json.loads(chosen) if isinstance(chosen, str) else chosen
        except Exception:
            chosen_body = {}
        merged = {k: v for k, v in stored.items() if k in DISPLAY_FIELDS}
        merged.update(_normalize_rule(chosen_body))
        c.execute(
            "UPDATE rules SET payload=?, baseline=?, updated_at=? WHERE id=?",
            (json.dumps(merged, ensure_ascii=False),
             json.dumps(_normalize_rule(chosen_body), ensure_ascii=False),
             int(time.time() * 1000), rule_id),
        )
        c.execute("DELETE FROM conflicts WHERE rule_id=?", (rule_id,))
    log_audit(actor, "rule.resolve", f"#{rule_id} 采用{'网页' if keep == 'server' else '手机'}版本")
    return True


def mark_baseline_applied(version: int) -> None:
    """
    设备确认应用了 [version] 后，把那一版下发的规则值记为新的基线。

    只有设备真正确认过，baseline 才代表「两边看到的同一份值」；
    在设备拉取之前就改 baseline，会让「还没同步」被误判成「一致」。
    """
    with conn() as c:
        row = c.execute("SELECT payload FROM config_versions WHERE version=?", (version,)).fetchone()
        if row is None:
            return
        try:
            rules = json.loads(row["payload"]).get("rules", [])
        except Exception:
            return
        for r in rules:
            rid = r.get("id")
            if not isinstance(rid, int) or rid <= 0:
                continue
            body = _normalize_rule(r)
            c.execute(
                "UPDATE rules SET baseline=? WHERE id=?",
                (json.dumps(body, ensure_ascii=False), rid),
            )
        # 这一版里的删除指令已经在设备上执行过了，可以从待办里清掉
        try:
            deleted = json.loads(row["payload"]).get("deleted") or []
        except Exception:
            deleted = []
        for rid in deleted:
            c.execute("DELETE FROM deletions WHERE rule_id=?", (rid,))


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
    """
    删除一条规则。

    不是简单的 DELETE：还要记一条待下发的删除指令，否则手机下次上报规则清单时
    会把它当「服务端没有的新规则」重新建档，删除形同虚设。
    """
    with conn() as c:
        c.execute("DELETE FROM rules WHERE id=?", (rule_id,))
        c.execute(
            "INSERT INTO deletions(rule_id, created_at) VALUES(?,?) "
            "ON CONFLICT(rule_id) DO NOTHING",
            (rule_id, int(time.time() * 1000)),
        )
        c.execute("DELETE FROM conflicts WHERE rule_id=?", (rule_id,))
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
            "SELECT id, name, created_at, revoked, last_seen_at, last_version, last_note, "
            "model, android_version, app_version, unlock_json "
            "FROM devices ORDER BY created_at DESC"
        ).fetchall()
    out = []
    for r in rows:
        d = dict(r)
        raw = d.pop("unlock_json", "") or ""
        try:
            d["unlock"] = json.loads(raw) if raw else {}
        except Exception:
            d["unlock"] = {}
        out.append(d)
    return out


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


def import_device_rules(rules: list[dict[str, Any]] | None, actor: str = "device") -> int:
    """
    把设备上报的规则清单补进本地表，返回新增条数。

    **只在 id 不存在时插入**。已存在的条目可能是网页上正在编辑的草稿 ——
    用设备上报的旧值覆盖它，等于把用户的修改悄悄回滚掉。
    设备新增了规则则会在下一次上报时自动补进来。

    这是「规则从哪来」的唯一正确来源：手机是权威方，
    下发的 id 若在本地不存在，客户端 `patch` 会返回 notFound。
    """
    if not rules:
        return 0
    added = 0
    with conn() as c:
        for r in rules:
            if not isinstance(r, dict):
                continue
            try:
                rid = int(r.get("id", 0))
            except (TypeError, ValueError):
                continue
            if rid <= 0:
                continue
            if c.execute("SELECT 1 FROM rules WHERE id=?", (rid,)).fetchone():
                continue
            body = {k: v for k, v in r.items() if k != "id"}
            c.execute(
                "INSERT INTO rules(id, payload, updated_at) VALUES(?,?,?)",
                (rid, json.dumps(body, ensure_ascii=False), int(time.time() * 1000)),
            )
            added += 1
    if added:
        log_audit(actor, "rule.import", f"从设备导入 {added} 条规则")
    return added


def add_report(
    device_id: str,
    version: int,
    ok: bool,
    detail: str,
    device_info: dict[str, Any] | None = None,
    unlock_status: dict[str, Any] | None = None,
) -> None:
    with conn() as c:
        c.execute(
            "INSERT INTO reports(device_id, version, ok, detail, at) VALUES(?,?,?,?,?)",
            (device_id, version, 1 if ok else 0, detail[:500], int(time.time() * 1000)),
        )
        # 设备信息与解锁状态都是设备自报的快照：远程只能看，不能改。
        # unlock_json 里存的是策略与结果（含失败计数），不存在任何 PIN 内容。
        c.execute(
            "UPDATE devices SET last_version=?, last_note=?, last_seen_at=?, "
            "model=?, android_version=?, app_version=?, unlock_json=? WHERE id=?",
            (
                version,
                detail[:300],
                int(time.time() * 1000),
                str((device_info or {}).get("model", ""))[:40],
                str((device_info or {}).get("androidVersion", ""))[:20],
                str((device_info or {}).get("appVersion", ""))[:20],
                json.dumps(unlock_status or {}, ensure_ascii=False)[:2000],
                device_id,
            ),
        )
    # 规则清单随回报一起上来。放在事务外：导入/合并失败不应该回滚回报本身。
    #
    # 顺序有讲究：先把这一版下发值记为基线，再做三方比较。
    # 反过来的话，刚应用完的那版会被自己判成「两边都改了」。
    if ok:
        mark_baseline_applied(version)
    sync_device_rules((device_info or {}).get("rules"), version)


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
