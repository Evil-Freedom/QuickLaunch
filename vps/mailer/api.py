"""事件接入 HTTP 接口。

边界（对应需求 §九）：
* 只接受 `Authorization: Bearer <QLMAIL_INGEST_TOKEN>`，否则 401；
* **不接受任何收件人字段** —— 地址只来自服务端配置，Android 无法指定发给谁；
* 只接受固定的事件类型与白名单字段，正文由服务端模板渲染（不会被当成指令执行）；
* 入队即返回 202，发送在后台线程做，绝不阻塞上报方。
"""

from __future__ import annotations

import json
import logging
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

from . import render
from . import repository as R
from .config import Config
from .dispatcher import Dispatcher

log = logging.getLogger("qlmail.api")

MAX_BODY = 64 * 1024
SUCCESS_TYPES = ("launch_success", "checkin_success")


class Handler(BaseHTTPRequestHandler):
    server_version = "ql-mailer/1.0"
    cfg: Config
    repo: R.Repository
    dispatcher: Dispatcher

    # ── 基础 ────────────────────────────────────────────────────────

    def log_message(self, fmt: str, *args: Any) -> None:  # 去掉默认的 stderr 噪音
        log.debug("http " + fmt, *args)

    def _send(self, code: int, payload: dict[str, Any]) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _authorized(self) -> bool:
        token = self.cfg.ingest_token
        if not token:
            return False
        header = self.headers.get("Authorization", "")
        return header == f"Bearer {token}"

    def _json_body(self) -> dict[str, Any] | None:
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0 or length > MAX_BODY:
            return None
        try:
            data = json.loads(self.rfile.read(length))
        except (json.JSONDecodeError, UnicodeDecodeError):
            return None
        return data if isinstance(data, dict) else None

    # ── 路由 ────────────────────────────────────────────────────────

    def do_GET(self) -> None:  # noqa: N802
        if self.path == "/healthz":
            self._send(200, {
                "ok": True,
                "paused": self.dispatcher.paused,
                "queue": self.repo.stats(),
                "primary": self.cfg.primary,
                "fallback": self.cfg.fallback,
            })
            return
        if self.path.startswith("/v1/events/"):
            event_id = self.path.rsplit("/", 1)[-1]
            try:
                row = self.repo.get(event_id)
            except KeyError:
                self._send(404, {"ok": False, "error": "not_found"})
                return
            self._send(200, {
                "ok": True,
                "event_id": row.event_id,
                "status": row.status,
                "recipient": row.current_recipient,
                "message_id": row.message_id,
                "attempts": row.attempts,
                "error_code": row.error_code,
                "error": row.error_message,
            })
            return
        self._send(404, {"ok": False, "error": "not_found"})

    def do_POST(self) -> None:  # noqa: N802
        if self.path != "/v1/events":
            self._send(404, {"ok": False, "error": "not_found"})
            return
        if not self._authorized():
            self._send(401, {"ok": False, "error": "unauthorized"})
            return
        body = self._json_body()
        if body is None:
            self._send(400, {"ok": False, "error": "invalid_json"})
            return

        event_type = str(body.get("type") or "")
        if event_type not in render.SUBJECTS:
            self._send(400, {"ok": False, "error": "unsupported_type"})
            return

        device_id = str(body.get("device_id") or "")
        if not device_id or len(device_id) > 64:
            self._send(400, {"ok": False, "error": "invalid_device_id"})
            return
        if self.cfg.device_whitelist and device_id not in self.cfg.device_whitelist:
            self._send(403, {"ok": False, "error": "device_not_allowed"})
            return

        payload = body.get("data") or {}
        if not isinstance(payload, dict):
            self._send(400, {"ok": False, "error": "invalid_data"})
            return

        if self.cfg.success_mode == "off" and event_type in SUCCESS_TYPES:
            self._send(200, {"ok": True, "accepted": False, "reason": "success_notifications_disabled"})
            return

        try:
            subject, text = render.render(event_type, payload)
        except ValueError as exc:
            self._send(400, {"ok": False, "error": str(exc)})
            return

        event_id = body.get("event_id")
        created, row = self.repo.enqueue(
            notification_type=event_type,
            device_id=device_id,
            payload=payload,
            subject=subject,
            body=text,
            primary=self.cfg.primary,
            fallback=self.cfg.fallback,
            event_id=str(event_id) if event_id else None,
        )
        log.info("入队 event=%s type=%s new=%s", row.event_id, event_type, created)
        self._send(202, {
            "ok": True,
            "event_id": row.event_id,
            "status": row.status,
            "duplicate": not created,
        })


def build_server(cfg: Config, repo: R.Repository, dispatcher: Dispatcher) -> ThreadingHTTPServer:
    handler = type("BoundHandler", (Handler,), {"cfg": cfg, "repo": repo, "dispatcher": dispatcher})
    try:
        return ThreadingHTTPServer((cfg.bind_host, cfg.port), handler)
    except OSError as exc:
        # docker 网桥不存在（容器未起）时退回本机回环，服务仍可用但只有本机能上报
        log.error("绑定 %s:%s 失败（%s），退回 127.0.0.1", cfg.bind_host, cfg.port, exc)
        return ThreadingHTTPServer(("127.0.0.1", cfg.port), handler)
