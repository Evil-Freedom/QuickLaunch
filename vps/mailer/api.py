"""事件接入 + 考勤核验 + 清理动作 的 HTTP 接口。

三类调用方，三套边界：
* 手机（`QLMAIL_INGEST_TOKEN`）：上报事件、拉取/回报清理动作；
* Windows 核验端（`QLMAIL_VERIFIER_TOKEN`）：查待核验任务、提交真实考勤证据；
* 服务内部：只在后台线程发邮件，绝不在此路径里同步发信。

请求体里的收件人、包名、命令、文件路径一律不接受——动作类型只有服务端枚举。
"""

from __future__ import annotations

import json
import logging
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

from . import render
from . import repository as R
from .actions import ActionStore
from .alerts import AlertStore
from .config import Config
from .dispatcher import Dispatcher

log = logging.getLogger("qlmail.api")

MAX_BODY = 64 * 1024


def _looks_like_date(value: str) -> bool:
    try:
        import datetime as _dt
        _dt.date.fromisoformat(value)
        return True
    except ValueError:
        return False
SUCCESS_TYPES = ("launch_success", "checkin_success", "cleanup_success")
REQUIRED_EVIDENCE = ("task_id", "device_id", "attendance_date", "evidence_source", "evidence_time")


class Handler(BaseHTTPRequestHandler):
    server_version = "ql-mailer/1.1"
    cfg: Config
    repo: R.Repository
    store: ActionStore
    alerts: AlertStore
    dispatcher: Dispatcher

    # ── 基础 ────────────────────────────────────────────────────────

    def log_message(self, fmt: str, *args: Any) -> None:
        log.debug("http " + fmt, *args)

    def _send(self, code: int, payload: dict[str, Any]) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _bearer(self) -> str:
        header = self.headers.get("Authorization", "")
        return header[7:] if header.startswith("Bearer ") else ""

    def _device_ok(self) -> bool:
        token = self.cfg.ingest_token
        return bool(token) and self._bearer() == token

    def _verifier_ok(self) -> bool:
        token = self.cfg.verifier_token
        return bool(token) and self._bearer() == token

    def _json_body(self) -> dict[str, Any] | None:
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0 or length > MAX_BODY:
            return None
        try:
            data = json.loads(self.rfile.read(length))
        except (json.JSONDecodeError, UnicodeDecodeError):
            return None
        return data if isinstance(data, dict) else None

    def _notify(self, event_id: str, type_: str, device_id: str, payload: dict[str, Any]) -> None:
        subject, body = render.render(type_, payload)
        self.repo.enqueue(
            notification_type=type_, device_id=device_id, payload=payload,
            subject=subject, body=body, primary=self.cfg.primary,
            fallback=self.cfg.fallback, event_id=event_id,
        )

    # ── GET ─────────────────────────────────────────────────────────

    def do_GET(self) -> None:  # noqa: N802
        path = self.path.split("?", 1)[0]

        if path == "/healthz":
            self._send(200, {
                "ok": True,
                "paused": self.dispatcher.paused,
                "queue": self.repo.stats(),
                "actions": self.store.stats(),
                "primary": self.cfg.primary,
                "fallback": self.cfg.fallback,
            })
            return

        if path == "/v1/attendance/pending":
            if not self._verifier_ok():
                self._send(401, {"ok": False, "error": "unauthorized"})
                return
            self._send(200, {"ok": True, "tasks": self.store.pending_checkins()})
            return

        if path == "/v1/device/actions/pending":
            if not self._device_ok():
                self._send(401, {"ok": False, "error": "unauthorized"})
                return
            device_id = (self.headers.get("X-QL-Device")
                         or self.path.split("device_id=", 1)[-1].split("&")[0])
            if not device_id or (self.cfg.device_whitelist and device_id not in self.cfg.device_whitelist):
                self._send(403, {"ok": False, "error": "device_not_allowed"})
                return
            actions = self.store.pending_for_device(device_id)
            self._send(200, {"ok": True, "actions": [a.as_dict() for a in actions]})
            return

        if path.startswith("/v1/events/"):
            event_id = path.rsplit("/", 1)[-1]
            try:
                row = self.repo.get(event_id)
            except KeyError:
                self._send(404, {"ok": False, "error": "not_found"})
                return
            self._send(200, {
                "ok": True, "event_id": row.event_id, "status": row.status,
                "recipient": row.current_recipient, "message_id": row.message_id,
                "attempts": row.attempts, "error_code": row.error_code,
                "error": row.error_message,
            })
            return

        if path.startswith("/v1/actions/"):
            action_id = path.rsplit("/", 1)[-1]
            action = self.store.get(action_id)
            if action is None:
                self._send(404, {"ok": False, "error": "not_found"})
                return
            self._send(200, {"ok": True, **action.as_dict(),
                             "result_at": action.result_at,
                             "result_detail": action.result_detail})
            return

        self._send(404, {"ok": False, "error": "not_found"})

    # ── POST ────────────────────────────────────────────────────────

    def do_POST(self) -> None:  # noqa: N802
        path = self.path.split("?", 1)[0]

        if path == "/v1/attendance/verify":
            self._post_verify()
            return

        if path.startswith("/v1/device/actions/"):
            self._post_action_result(path)
            return

        if path != "/v1/events":
            self._send(404, {"ok": False, "error": "not_found"})
            return

        if not self._device_ok():
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
        if event_type == "checkin_unknown":
            # 日期必须是 yyyy-MM-dd：payload 里的 actual_time 是「MM-dd HH:mm:ss」，
            # 直接截前 10 位会得到 "11-24 12:4" 这种非法日期，提醒窗口就永远匹配不上。
            import datetime as _dt
            raw_date = str(payload.get("attendance_date") or "")
            date = raw_date if _looks_like_date(raw_date) else _dt.datetime.now().strftime("%Y-%m-%d")
            task_id = str(payload.get("task_id") or "")
            decision, dedupe_id = self.alerts.observe(device_id, date, task_id)
            log.info("待确认提醒 device=%s date=%s decision=%s", device_id, date, decision)
            if decision in ("suppress", "closed"):
                self._send(202, {"ok": True, "event_id": None, "status": "SUPPRESSED",
                                 "duplicate": True, "reason": decision})
                return
            event_id = dedupe_id

        created, row = self.repo.enqueue(
            notification_type=event_type, device_id=device_id, payload=payload,
            subject=subject, body=text, primary=self.cfg.primary,
            fallback=self.cfg.fallback, event_id=str(event_id) if event_id else None,
        )
        log.info("入队 event=%s type=%s new=%s", row.event_id, event_type, created)
        self._send(202, {"ok": True, "event_id": row.event_id,
                         "status": row.status, "duplicate": not created})

    def _post_verify(self) -> None:
        if not self._verifier_ok():
            self._send(401, {"ok": False, "error": "unauthorized"})
            return
        body = self._json_body()
        if body is None:
            self._send(400, {"ok": False, "error": "invalid_json"})
            return
        missing = [k for k in REQUIRED_EVIDENCE if not str(body.get(k) or "").strip()]
        if missing:
            self._send(400, {"ok": False, "error": "missing_evidence", "fields": missing})
            return
        if self.cfg.device_whitelist and str(body["device_id"]) not in self.cfg.device_whitelist:
            self._send(403, {"ok": False, "error": "device_not_allowed"})
            return

        task_id = str(body["task_id"])
        device_id = str(body["device_id"])

        owner = self.repo._conn.execute(
            """SELECT device_id FROM notifications
               WHERE event_id=? OR json_extract(payload,'$.task_id')=?
               LIMIT 1""",
            (task_id, task_id),
        ).fetchone()
        if owner is not None and owner["device_id"] != device_id:
            self._send(409, {"ok": False, "error": "task_device_mismatch"})
            return
        result = str(body.get("result") or "success")
        created = self.store.record_attendance(
            task_id=task_id, device_id=device_id,
            attendance_date=str(body["attendance_date"]),
            evidence_source=str(body["evidence_source"]),
            evidence_time=str(body["evidence_time"]),
            evidence_ref=str(body.get("evidence_ref") or ""),
            result=result,
        )

        if result != "success":
            self._send(202, {"ok": True, "accepted": True, "verified": False, "duplicate": not created})
            return

        # 邮件：考勤成功（event_id 由 task 派生 → 天然幂等）
        self._notify(f"checkin-{task_id}", "checkin_success", device_id, {
            "device_name": body.get("device_name") or "QuickLaunch 设备",
            "target_app": "飞书",
            "evidence_time": str(body["evidence_time"]),
            "evidence_source": str(body["evidence_source"]),
            "evidence_ref": str(body.get("evidence_ref") or ""),
            "task_id": task_id,
        })

        # 真实考勤成功 = 关闭待确认窗口，后续不再提醒
        self.alerts.close(device_id, str(body["attendance_date"]), "checkin_success")

        action_created, action = self.store.create_action(
            device_id=device_id, task_id=task_id,
            action_type="FORCE_STOP_FEISHU", ttl_s=self.cfg.action_ttl_s,
        )
        self._notify(f"cleanup-pending-{task_id}", "cleanup_pending", device_id, {
            "device_name": body.get("device_name") or "QuickLaunch 设备",
            "task_id": task_id,
            "action_type": action.action_type,
            "checked_at": action.created_at,
        }) if action_created else None

        log.info("考勤核验成功 task=%s action=%s new=%s", task_id, action.action_id, action_created)
        self._send(202, {
            "ok": True, "accepted": True, "verified": True,
            "duplicate": not created,
            "action_id": action.action_id,
            "action_status": action.status,
            "action_created": action_created,
        })

    def _post_action_result(self, path: str) -> None:
        if not self._device_ok():
            self._send(401, {"ok": False, "error": "unauthorized"})
            return
        parts = [p for p in path.split("/") if p]
        # /v1/device/actions/{id}/{begin|result}
        if len(parts) != 5:
            self._send(404, {"ok": False, "error": "not_found"})
            return
        action_id, verb = parts[3], parts[4]
        body = self._json_body() or {}
        device_id = str(body.get("device_id") or self.headers.get("X-QL-Device") or "")

        if verb == "begin":
            ok, reason, action = self.store.begin(action_id, device_id, str(body.get("nonce") or ""))
            self._send(200 if ok else 409, {"ok": ok, "reason": reason,
                                            "action": action.as_dict() if action else None})
            return

        if verb == "result":
            status = str(body.get("status") or "")
            ok, reason, action = self.store.finish(
                action_id, device_id, status, str(body.get("detail") or "")
            )
            if ok and action is not None and action.status == "CLEANUP_SUCCESS":
                date = str(body.get("attendance_date") or action.created_at[:10])
                self.alerts.close(device_id, date, "cleanup_success")
            if ok and action is not None and action.status in ("CLEANUP_SUCCESS", "CLEANUP_FAILED"):
                type_ = "cleanup_success" if action.status == "CLEANUP_SUCCESS" else "cleanup_failed"
                self._notify(f"cleanup-{action.action_id}", type_, device_id, {
                    "device_name": body.get("device_name") or "QuickLaunch 设备",
                    "task_id": action.task_id,
                    "action_type": action.action_type,
                    "detail": str(body.get("detail") or action.status),
                    "checked_at": action.result_at or "",
                })
            self._send(200 if ok else 409, {"ok": ok, "reason": reason,
                                            "status": action.status if action else None})
            return

        self._send(404, {"ok": False, "error": "not_found"})


def build_server(cfg: Config, repo: R.Repository, store: ActionStore,
                 alerts: AlertStore, dispatcher: Dispatcher) -> ThreadingHTTPServer:
    handler = type("BoundHandler", (Handler,),
                   {"cfg": cfg, "repo": repo, "store": store, "alerts": alerts,
                    "dispatcher": dispatcher})
    try:
        return ThreadingHTTPServer((cfg.bind_host, cfg.port), handler)
    except OSError as exc:
        log.error("绑定 %s:%s 失败（%s），退回 127.0.0.1", cfg.bind_host, cfg.port, exc)
        return ThreadingHTTPServer(("127.0.0.1", cfg.port), handler)
