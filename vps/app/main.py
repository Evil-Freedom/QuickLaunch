"""
QuickLaunch 远程规则服务。

## 它是什么

一台只服务你一个人手机的配置服务器：你在网页上改启动规则，手机定期来拉。
**手机主动出站**，服务器不需要任何入站端口连到手机，也不保存任何能反向控制
手机的东西（没有 Shell、没有包名之外的指令）。

## 端点

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| GET | /api/v1/ping | 无 | 存活探测，只返回版本号，不返回任何配置 |
| GET | /api/v1/config | 设备签名 | 拉取配置信封 |
| POST | /api/v1/report | 设备签名 | 回报应用结果 |
| GET | /login | 无 | 管理员登录页 |
| GET | /rules /versions /devices /audit | Cookie | 管理界面 |

## 安全基线

- 所有设备请求走 HMAC 签名 + 时间窗 + nonce（见 `auth.py`）
- 管理界面走口令 + 会话 cookie + 登录限流
- 下发的规则字段是**白名单**（见 `ALLOWED_RULE_FIELDS`）：改不了 targetPackage，
  也就是说即使这台服务器被攻破，攻击者也无法让手机去启动一个别的 App
- 配置签发时间带在信封里，客户端会校验 ±10 分钟
"""

from __future__ import annotations

import base64
import hashlib
import json
import os
import time
from typing import Any

from fastapi import FastAPI, Form, HTTPException, Request, Response
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse
from fastapi.templating import Jinja2Templates

from . import auth, db

BASE_DIR = os.path.dirname(__file__)
templates = Jinja2Templates(directory=os.path.join(BASE_DIR, "templates"))

ALG = "HMAC-SHA256"

# 手机上报的解锁状态字段白名单。这些都是「状态与结果」，不存在 PIN 内容；
# 出现白名单之外的字段一律丢弃，防止未来客户端版本误传敏感数据。
ALLOWED_UNLOCK_STATUS_FIELDS = {
    "enabled", "hasPin", "storage", "chargingOnly",
    "failures", "maxFailures", "securityLocked", "lastAttemptAt", "lastResult",
}

# 与 Android 侧 `RemoteProtocol.EDITABLE_FIELDS` 保持一致。
# 注意这里**故意不包含** targetPackage / triggerType / id：
# 远程能改的只有「什么时候启动」，不能改「启动什么」。
ALLOWED_RULE_FIELDS = {
    "timeHour", "timeMinute", "repeatMode", "repeatDays",
    "skipHolidays", "randomWindow", "windowStartMin", "windowEndMin", "enabled",
    "shiftAnchor", "shiftOffsetMin",
}

VALID_REPEAT_MODES = {"daily", "weekdays", "weekend", "custom", "once"}

app = FastAPI(title="QuickLaunch Remote", docs_url=None, redoc_url=None)


@app.on_event("startup")
def _startup() -> None:
    db.init_db()


# ── 设备 API ────────────────────────────────────────────────────────


@app.get("/api/v1/ping")
def ping() -> JSONResponse:
    """存活探测。只回版本号 —— 不泄露设备数量、规则内容。"""
    return JSONResponse({"ok": True, "version": db.current_version()})


def _sign_envelope(
    secret_b64: str, version: int, issued_at: int, nonce: str,
    payload_b64: str, payload_hash: str = ""
) -> dict[str, Any]:
    """
    生成签名信封。签名输入与 Android 侧 `VpsEnvelope.signature` 逐字节一致：
    `alg \\n version \\n issuedAt \\n nonce \\n payloadB64 \\n payloadHash`

    指纹必须进签名：它决定了客户端「要不要重新应用」，可被伪造的话
    攻击者就能让设备跳过更新。
    """
    secret = base64.b64decode(secret_b64)
    sign = auth.hmac_hex(
        secret, f"{ALG}\n{version}\n{issued_at}\n{nonce}\n{payload_b64}\n{payload_hash}"
    )
    return {
        "alg": ALG,
        "version": version,
        "issuedAt": issued_at,
        "nonce": nonce,
        "hash": payload_hash,
        "sign": sign,
    }


@app.get("/api/v1/config")
async def get_config(request: Request, device: str = "", since: int = 0) -> JSONResponse:
    """
    拉取配置。

    ⚠️ 签名用的 path 必须**用请求原始串**重算，不能拿重新拼出来的 URL ——
    查询参数的编码方式（`+` vs `%20`）一旦不同，签名就会对不上，
    而症状是「同一条命令在 Postman 里能通、手机上报 401」。
    """
    raw_path = request.scope.get("raw_path", b"").decode("latin-1") or request.url.path
    raw_query = request.scope.get("query_string", b"").decode("latin-1")
    sign_path = f"{raw_path}?{raw_query}" if raw_query else raw_path

    d = db.get_device(device)
    ok, why = auth.verify_device(
        d,
        sign_path,
        request.headers.get("X-QL-Ts"),
        request.headers.get("X-QL-Nonce"),
        request.headers.get("X-QL-Sign"),
        str(since),
    )
    if not ok:
        db.log_audit(device or "-", "api.config.denied", why)
        raise HTTPException(status_code=401, detail=why)

    cur_version = db.current_version()
    now = int(time.time() * 1000)
    nonce = request.headers.get("X-QL-Nonce", "")
    db.touch_device(device)

    payload = db.current_payload()
    payload_json = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
    payload_hash = hashlib.sha256(payload_json.encode("utf-8")).hexdigest()[:16]

    # 无更新判定：版本不高于客户端已应用版本 **且** 内容指纹一致。
    # 只有版本号是不够的：旧版本 APK 可能拉到了某个版本但（因为不认识新字段）
    # 没有应用其中的内容，此时版本号已推进、内容却没生效 ——
    # 指纹让「版本到了、内容没到」的设备仍然能拿到完整配置。
    client_hash = request.query_params.get("hash", "")
    if cur_version <= since and client_hash == payload_hash:
        # 无更新也签名：客户端要求「连 NoChange 也必须能验签」，
        # 否则任何人都能伪造一个 NoChange 让手机停止同步。
        env = _sign_envelope(d["secret"], cur_version, now, nonce, "", payload_hash)
        env["updated"] = False
        env["payload"] = ""
        return JSONResponse(env)

    payload_b64 = base64.b64encode(payload_json.encode("utf-8")).decode("ascii")
    env = _sign_envelope(d["secret"], cur_version, now, nonce, payload_b64, payload_hash)
    env["updated"] = True
    env["payload"] = payload_b64
    env["hash"] = payload_hash
    return JSONResponse(env)


@app.post("/api/v1/report")
async def report(request: Request) -> JSONResponse:
    """
    接收应用结果。**签名覆盖 detail 原文**，所以必须先读原始 body 再解析。
    """
    raw = await request.body()
    try:
        body = json.loads(raw)
    except Exception:
        raise HTTPException(status_code=400, detail="body 不是合法 JSON")

    device_id = str(body.get("device", ""))
    version = int(body.get("version", 0) or 0)
    ok = bool(body.get("ok", False))
    detail = str(body.get("detail", ""))[:200]

    d = db.get_device(device_id)
    passed, why = auth.verify_report(
        d,
        request.headers.get("X-QL-Ts"),
        request.headers.get("X-QL-Sign"),
        version,
        ok,
        detail,
    )
    if not passed:
        db.log_audit(device_id or "-", "api.report.denied", why)
        raise HTTPException(status_code=401, detail=why)

    # 设备自报的快照字段：白名单过滤后再入库 —— 就算手机端将来误带了多余字段，
    # 也不会原样落库（这些数据最终会被渲染到看板上）。
    device_info = body.get("deviceInfo")
    unlock_status = body.get("unlock")
    device_info = device_info if isinstance(device_info, dict) else None
    if isinstance(unlock_status, dict):
        unlock_status = {k: unlock_status[k] for k in unlock_status
                         if k in ALLOWED_UNLOCK_STATUS_FIELDS}
    else:
        unlock_status = None
    db.add_report(device_id, version, ok, detail, device_info, unlock_status)
    return JSONResponse({"ok": True})


# ── 管理界面 ────────────────────────────────────────────────────────


def _current_user(request: Request) -> str | None:
    return auth.check_session(request.cookies.get(auth.SESSION_COOKIE))


def _require(request: Request) -> str | None:
    """未登录时返回 None，调用方据此重定向。"""
    return _current_user(request)


@app.get("/", response_class=HTMLResponse)
def index(request: Request):
    if not _current_user(request):
        return RedirectResponse("/login", status_code=302)
    return RedirectResponse("/rules", status_code=302)


@app.get("/login", response_class=HTMLResponse)
def login_page(request: Request):
    if not auth.admin_configured():
        return HTMLResponse(
            "<h3>服务未配置管理员口令</h3>"
            "<p>请在 .env 中设置 <code>QL_ADMIN_PASSWORD_SHA256</code>（推荐）"
            "或 <code>QL_ADMIN_PASSWORD</code>，然后重启容器。</p>",
            status_code=500,
        )
    return templates.TemplateResponse(request, "login.html", {"error": ""})


@app.post("/login")
def login(request: Request, password: str = Form("")):
    ip = request.client.host if request.client else "unknown"
    if auth.login_blocked(ip):
        return templates.TemplateResponse(
            request, "login.html", {"error": "尝试次数过多，请 10 分钟后再试"},
            status_code=429,
        )
    if not auth.check_password(password):
        auth.record_login_failure(ip)
        db.log_audit(ip, "admin.login.fail", "")
        return templates.TemplateResponse(
            request, "login.html", {"error": "口令不正确"}, status_code=401
        )
    auth.record_login_success(ip)
    db.log_audit(ip, "admin.login.ok", "")
    resp = RedirectResponse("/rules", status_code=302)
    resp.set_cookie(
        auth.SESSION_COOKIE,
        auth.make_session("admin"),
        httponly=True,
        samesite="lax",
        secure=True,
        max_age=12 * 3600,
    )
    return resp


@app.post("/logout")
def logout():
    resp = RedirectResponse("/login", status_code=302)
    resp.delete_cookie(auth.SESSION_COOKIE)
    return resp


@app.get("/rules", response_class=HTMLResponse)
def rules_page(request: Request):
    if not _require(request):
        return RedirectResponse("/login", status_code=302)
    return templates.TemplateResponse(
        request,
        "rules.html",
        {
            "rules": db.list_rules(),
            "retry": db.retry_policy(),
            "version": db.current_version(),
        },
    )


@app.post("/rules/{rule_id}/edit")
def edit_rule(
    request: Request,
    rule_id: int,
    time_hour: int = Form(0),
    time_minute: int = Form(0),
    repeat_mode: str = Form("daily"),
    repeat_days: int = Form(0),
    skip_holidays: int = Form(0),
    enabled: int = Form(1),
    publish: int = Form(1),
):
    if not _require(request):
        return RedirectResponse("/login", status_code=302)

    # 服务端也做一次范围校验：客户端的 mergePatch 会拒绝越界值，但那时
    # 用户已经在手机上看到「同步失败」，而根因在服务器。早失败早报错。
    body = {
        "timeHour": max(0, min(23, time_hour)),
        "timeMinute": max(0, min(59, time_minute)),
        "repeatMode": repeat_mode if repeat_mode in VALID_REPEAT_MODES else "daily",
        "repeatDays": repeat_days & 0x7F,
        "skipHolidays": bool(skip_holidays),
        "enabled": bool(enabled),
    }
    # 保留表单里没有的字段（randomWindow / windowStartMin / shiftAnchor …）。
    #
    # 直接用只有 6 个字段的 body 覆盖，会把服务端记录里的这些字段抹掉：
    # 网页上从此看不到「随机窗口」，用户再也没法确认它是否还在。
    # 手机端不受影响（mergePatch 只改报文里给出的字段），但服务端的记录必须完整。
    existing = next((r for r in db.list_rules() if r["id"] == rule_id), None)
    merged: dict = {}
    if existing:
        merged = {k: v for k, v in existing.items() if not k.startswith("_") and k != "id"}
    merged.update(body)
    db.upsert_rule(rule_id, merged)
    if publish:
        db.publish(f"改规则 #{rule_id}")
    return RedirectResponse("/rules", status_code=302)


@app.post("/retry")
def edit_retry(
    request: Request,
    enabled: int = Form(1),
    max_attempts: int = Form(3),
    first_verify_delay_ms: int = Form(2000),
    verify_window_ms: int = Form(6000),
    retry_delays: str = Form("5000,15000"),
    total_timeout_ms: int = Form(90000),
    notify_on_failure: int = Form(1),
    allow_manual_retry: int = Form(1),
):
    if not _require(request):
        return RedirectResponse("/login", status_code=302)
    delays = []
    for part in retry_delays.split(","):
        try:
            v = int(part.strip())
            if v > 0:
                delays.append(v)
        except ValueError:
            continue
    policy = {
        "enabled": bool(enabled),
        "maxAttempts": max(1, min(5, max_attempts)),
        "firstVerifyDelayMs": max(500, min(10000, first_verify_delay_ms)),
        "verifyWindowMs": max(2000, min(30000, verify_window_ms)),
        "retryDelaysMs": delays or [5000, 15000],
        "totalTimeoutMs": max(10000, min(600000, total_timeout_ms)),
        "notifyOnFailure": bool(notify_on_failure),
        "allowManualRetry": bool(allow_manual_retry),
    }
    db.set_retry_policy(policy)
    db.publish("改重试策略")
    return RedirectResponse("/rules", status_code=302)


@app.post("/publish")
def publish(request: Request, note: str = Form("手动发布")):
    if not _require(request):
        return RedirectResponse("/login", status_code=302)
    db.publish(note[:120])
    return RedirectResponse("/versions", status_code=302)


@app.get("/versions", response_class=HTMLResponse)
def versions_page(request: Request):
    if not _require(request):
        return RedirectResponse("/login", status_code=302)
    return templates.TemplateResponse(
        request,
        "versions.html",
        {"versions": db.versions(), "current": db.current_version()},
    )


@app.post("/versions/{version}/rollback")
def rollback(request: Request, version: int):
    if not _require(request):
        return RedirectResponse("/login", status_code=302)
    try:
        db.rollback_to(version)
    except ValueError:
        pass
    return RedirectResponse("/versions", status_code=302)


@app.get("/devices", response_class=HTMLResponse)
def devices_page(request: Request):
    if not _require(request):
        return RedirectResponse("/login", status_code=302)
    # secret 只在此刻展示一次给创建者；列表里一律脱敏
    new_device = getattr(devices_page, "_last_created", None)
    devices_page._last_created = None  # type: ignore[attr-defined]
    return templates.TemplateResponse(
        request,
        "devices.html",
        {
            "devices": db.list_devices(),
            "new_device": new_device,
            "reports": db.recent_reports(30),
        },
    )


@app.post("/devices/create")
def create_device(request: Request, name: str = Form("")):
    if not _require(request):
        return RedirectResponse("/login", status_code=302)
    created = db.create_device(name)
    # 用一次性属性把凭据带到下一次渲染：避免写进 URL（会被日志与浏览器历史记住）
    devices_page._last_created = created  # type: ignore[attr-defined]
    return RedirectResponse("/devices", status_code=302)


@app.post("/devices/{device_id}/revoke")
def revoke_device(request: Request, device_id: str, revoked: int = Form(1)):
    if not _require(request):
        return RedirectResponse("/login", status_code=302)
    db.revoke_device(device_id, bool(revoked))
    return RedirectResponse("/devices", status_code=302)


@app.post("/devices/{device_id}/delete")
def delete_device(request: Request, device_id: str):
    if not _require(request):
        return RedirectResponse("/login", status_code=302)
    db.delete_device(device_id)
    return RedirectResponse("/devices", status_code=302)


@app.get("/unlock", response_class=HTMLResponse)
def unlock_page(request: Request):
    if not _require(request):
        return RedirectResponse("/login", status_code=302)
    devices = db.list_devices()
    for d in devices:
        u = d.get("unlock") or {}
        at = u.get("lastAttemptAt") or 0
        # 看板显示可读时间；0（从未尝试）原样给 "-"
        u["lastAttemptText"] = (
            time.strftime("%m-%d %H:%M:%S", time.localtime(at / 1000)) if at else "-"
        )
        d["unlock"] = u
    return templates.TemplateResponse(
        request,
        "unlock.html",
        {
            "devices": devices,
            "policy": db.unlock_policy(),
            "version": db.current_version(),
        },
    )


@app.post("/unlock/policy")
def edit_unlock(
    request: Request,
    enabled: int = Form(0),
    charging_only: int = Form(0),
    publish: int = Form(1),
):
    if not _require(request):
        return RedirectResponse("/login", status_code=302)
    db.set_unlock_policy({"enabled": bool(enabled), "chargingOnly": bool(charging_only)})
    if publish:
        db.publish("改自动解锁策略")
    return RedirectResponse("/unlock", status_code=302)


@app.get("/audit", response_class=HTMLResponse)
def audit_page(request: Request):
    if not _require(request):
        return RedirectResponse("/login", status_code=302)
    return templates.TemplateResponse(
        request,
        "audit.html",
        {"entries": db.recent_audit()},
    )


@app.get("/healthz")
def healthz() -> Response:
    return Response("ok", media_type="text/plain")
