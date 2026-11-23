"""
鉴权：设备签名（HMAC-SHA256）与管理员会话。

## 两条独立的信任链

| 主体 | 凭据 | 用途 |
| --- | --- | --- |
| 设备 | 每台设备一把 32 字节 secret | 拉取配置、回报结果 |
| 管理员 | 环境变量里的口令 | 改规则、发布版本、撤销设备 |

刻意**不共用**一套密钥：设备 secret 存在手机上，手机可能丢；
管理员口令存在服务器上。两者混用会让「丢手机」升级成「丢管理权限」。

## 为什么签名里要带 path 和 since

`canonical = GET \\n <path含query> \\n <ts> \\n <nonce> \\n <deviceId> \\n <since>`

- 带 path：签名不能被挪用到别的端点
- 带 since：攻击者不能把「since=0 的完整配置响应」重放给一个已经同步到 v7 的手机
- 带 nonce：同一次请求不能被重放
- 带 ts：超过 10 分钟的请求直接拒
"""

from __future__ import annotations

import hashlib
import hmac
import os
import secrets
import time
from typing import Any

MAX_SKEW_MS = 10 * 60 * 1000

# 重放窗口内的 nonce 集合。单进程内存即可；进程重启后短暂失忆是可接受的
# （重启窗口内重放的请求仍要过时间窗与版本单调两道关）。
_seen_nonces: dict[str, int] = {}
_NONCE_TTL_MS = MAX_SKEW_MS * 2


def _purge(now: int) -> None:
    if len(_seen_nonces) < 4096:
        return
    expired = [k for k, v in _seen_nonces.items() if now - v > _NONCE_TTL_MS]
    for k in expired:
        del _seen_nonces[k]


def use_nonce(nonce: str, now: int | None = None) -> bool:
    """登记一个 nonce；已用过返回 False。"""
    now = now or int(time.time() * 1000)
    _purge(now)
    if nonce in _seen_nonces:
        return False
    _seen_nonces[nonce] = now
    return True


def hmac_hex(key: bytes, data: str) -> str:
    return hmac.new(key, data.encode("utf-8"), hashlib.sha256).hexdigest()


def request_signature(secret: bytes, path: str, ts: int, nonce: str, device_id: str, since: int) -> str:
    """与 Android 侧 `VpsEnvelope.requestSignature` 逐字节一致。"""
    return hmac_hex(secret, f"GET\n{path}\n{ts}\n{nonce}\n{device_id}\n{since}")


def report_signature(
    secret: bytes, device_id: str, version: int, ts: int, ok: bool, detail: str
) -> str:
    """与 Android 侧 `VpsEnvelope.reportSignature` 逐字节一致。"""
    flag = 1 if ok else 0
    return hmac_hex(secret, f"REPORT\n{device_id}\n{version}\n{ts}\n{flag}\n{detail}")


def constant_time_equals(a: str, b: str) -> bool:
    return hmac.compare_digest(a, b)


def verify_device(
    device: dict[str, Any] | None,
    path: str,
    ts_raw: str | None,
    nonce: str | None,
    sign: str | None,
    since_raw: str | None,
    now: int | None = None,
) -> tuple[bool, str]:
    """
    校验设备请求。返回 (是否通过, 失败原因)。

    失败原因要足够具体：设备端会把 HTTP 错误码写进同步日志，
    而用户排查时看到的只有这一行。
    """
    now = now or int(time.time() * 1000)
    if device is None:
        return False, "设备未注册"
    if device.get("revoked"):
        return False, "设备已被撤销"
    if not ts_raw or not nonce or not sign:
        return False, "缺少签名头"
    try:
        ts = int(ts_raw)
    except ValueError:
        return False, "X-QL-Ts 不是整数"
    if abs(now - ts) > MAX_SKEW_MS:
        return False, f"请求时间偏差过大（{now - ts}ms）"
    if not use_nonce(nonce, now):
        return False, "nonce 已使用（重放）"

    try:
        since = int(since_raw or "0")
    except ValueError:
        return False, "since 不是整数"

    secret = device["secret"]
    if isinstance(secret, str):
        import base64

        try:
            secret_bytes = base64.b64decode(secret)
        except Exception:
            return False, "设备密钥格式非法"
    else:
        secret_bytes = secret

    expected = request_signature(secret_bytes, path, ts, nonce, device["id"], since)
    if not constant_time_equals(expected, sign):
        return False, "签名不匹配"
    return True, ""


def verify_report(
    device: dict[str, Any] | None,
    ts_raw: str | None,
    sign: str | None,
    version: int,
    ok: bool,
    detail: str,
    now: int | None = None,
) -> tuple[bool, str]:
    now = now or int(time.time() * 1000)
    if device is None:
        return False, "设备未注册"
    if device.get("revoked"):
        return False, "设备已被撤销"
    if not ts_raw or not sign:
        return False, "缺少签名头"
    try:
        ts = int(ts_raw)
    except ValueError:
        return False, "X-QL-Ts 不是整数"
    if abs(now - ts) > MAX_SKEW_MS:
        return False, "请求时间偏差过大"

    import base64

    try:
        secret_bytes = base64.b64decode(device["secret"])
    except Exception:
        return False, "设备密钥格式非法"
    expected = report_signature(secret_bytes, device["id"], version, ts, ok, detail)
    if not constant_time_equals(expected, sign):
        return False, "签名不匹配"
    return True, ""


# ── 管理员会话 ──────────────────────────────────────────────────────

SESSION_COOKIE = "ql_admin"
_SESSION_TTL_S = 12 * 3600


def _session_secret() -> bytes:
    raw = os.environ.get("QL_SESSION_SECRET", "")
    if not raw:
        # 没配就用进程级随机（重启后所有会话失效，可接受且更安全）
        raw = secrets.token_hex(32)
        os.environ["QL_SESSION_SECRET"] = raw
    return raw.encode("utf-8")


def make_session(user: str) -> str:
    ts = int(time.time())
    payload = f"{user}.{ts}"
    sig = hmac_hex(_session_secret(), payload)
    return f"{payload}.{sig}"


def check_session(token: str | None) -> str | None:
    """校验会话 cookie，成功返回用户名。"""
    if not token:
        return None
    parts = token.rsplit(".", 2)
    if len(parts) != 3:
        return None
    user, ts_raw, sig = parts
    try:
        ts = int(ts_raw)
    except ValueError:
        return None
    if time.time() - ts > _SESSION_TTL_S:
        return None
    expected = hmac_hex(_session_secret(), f"{user}.{ts}")
    if not constant_time_equals(expected, sig):
        return None
    return user


def check_password(candidate: str) -> bool:
    """
    口令比对。

    支持两种配置：
    - `QL_ADMIN_PASSWORD`：明文（简单，适合个人服务器的 .env）
    - `QL_ADMIN_PASSWORD_SHA256`：十六进制摘要（更好，配置文件泄露不等于口令泄露）

    两者都支持是因为用户可能想图省事；但文档里会推荐后者。
    """
    plain = os.environ.get("QL_ADMIN_PASSWORD", "")
    digest = os.environ.get("QL_ADMIN_PASSWORD_SHA256", "").lower()
    if digest:
        got = hashlib.sha256(candidate.encode("utf-8")).hexdigest()
        return hmac.compare_digest(got, digest)
    if plain:
        return hmac.compare_digest(candidate, plain)
    return False


def admin_configured() -> bool:
    return bool(
        os.environ.get("QL_ADMIN_PASSWORD") or os.environ.get("QL_ADMIN_PASSWORD_SHA256")
    )


# ── 简单的登录限流 ──────────────────────────────────────────────────

_attempts: dict[str, tuple[int, int]] = {}


def login_blocked(ip: str, now: float | None = None) -> bool:
    now = now or time.time()
    cnt, until = _attempts.get(ip, (0, 0))
    return until > now


def record_login_failure(ip: str, now: float | None = None) -> None:
    import time as _t

    now = now or _t.time()
    cnt, _ = _attempts.get(ip, (0, 0))
    cnt += 1
    # 连错 5 次锁 10 分钟。个人服务器不需要更复杂的策略，
    # 但也绝不能让公网上的爆破脚本无限尝试。
    until = now + 600 if cnt >= 5 else 0
    _attempts[ip] = (cnt if until == 0 else 0, until)


def record_login_success(ip: str) -> None:
    _attempts.pop(ip, None)
