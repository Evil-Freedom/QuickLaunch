"""从运营部看板取「本人」考勤证据（服务间内部接口）。

为什么不再自己查飞书：
    一次采集、多处消费。看板每天统一采一批，QuickLaunch 只是其中一个消费者；
    两个服务各查一遍，既浪费配额，也会出现「看板显示已打卡、手机还在等」的
    不一致。所以这里只做一件事：带着签名去问看板要当天属于本设备用户的那条记录。

边界（与看板侧 internal_auth 对齐）：
    · 只传 device_id / attendance_date，**不传** open_id 或 employee_no——
      服务端按登记好的绑定关系反查，请求方改不了「要谁的」；
    · 签名覆盖 method + 完整 path（含 query），改一个字就验不过；
    · 时间戳超出窗口、nonce 重复都会被拒。
"""
from __future__ import annotations

import hashlib
import hmac
import json
import logging
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

from .lark import BAD_RESULTS, CLOCK_TOLERANCE_S, Evidence

log = logging.getLogger("qlmail.board")

# 与看板 app/internal_auth.py 的 message 拼法必须逐字节一致，
# 任一处不同都会变成「全部 401」，且两边日志都只看到「签名不匹配」。
def _signature(secret: str, ts: str, nonce: str, method: str, path: str, body: bytes) -> str:
    digest = hashlib.sha256(body or b"").hexdigest()
    msg = f"{ts}\n{nonce}\n{method.upper()}\n{path}\n{digest}".encode("utf-8")
    return hmac.new(secret.encode("utf-8"), msg, hashlib.sha256).hexdigest()


class BoardClient:
    """看板内部接口客户端。base_url 只允许回环/内网地址，杜绝误配到公网。"""

    def __init__(self, base_url: str, token: str, timeout_s: float = 8.0):
        self.base_url = (base_url or "").rstrip("/")
        self.token = token or ""
        self.timeout_s = timeout_s

    # ── 底层 ────────────────────────────────────────────────────────

    def _request(self, method: str, path: str, body: bytes = b"") -> dict | None:
        if not self.base_url or not self.token:
            log.warning("看板接口未配置（QL_BOARD_URL / QL_BOARD_TOKEN）")
            return None
        ts = str(int(time.time()))
        nonce = uuid.uuid4().hex
        headers = {
            "X-QL-Timestamp": ts,
            "X-QL-Nonce": nonce,
            "X-QL-Signature": _signature(self.token, ts, nonce, method, path, body),
        }
        if body:
            headers["Content-Type"] = "application/json"

        req = urllib.request.Request(self.base_url + path, data=body or None,
                                     headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=self.timeout_s) as r:
                return json.loads(r.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            detail = ""
            try:
                detail = e.read().decode("utf-8")[:160]
            except Exception:
                pass
            log.warning("看板接口 HTTP %s：%s", e.code, detail)
            return None
        except Exception as exc:
            log.warning("看板接口不可达：%s", exc)
            return None

    # ── 对外 ────────────────────────────────────────────────────────

    def self_evidence(self, day: str, not_before_ts: int, device_id: str) -> Evidence | None:
        """取本人当天的打卡证据。判据与旧的直查版本完全一致（本地再核一遍）。"""
        q = urllib.parse.urlencode({"attendance_date": day, "device_id": device_id})
        path = "/internal/attendance/self?" + q
        payload = self._request("GET", path)
        if not payload or not payload.get("ok"):
            return None
        if not payload.get("found"):
            log.info("看板尚未采到 %s 的考勤记录，保持待核验", day)
            return None

        record_id = str(payload.get("record_id") or "")
        result = str(payload.get("result") or "")
        if not record_id or result in BAD_RESULTS:
            return None
        try:
            ts = int(payload.get("check_time") or 0)
        except (TypeError, ValueError):
            return None
        if not ts or ts < not_before_ts - CLOCK_TOLERANCE_S:
            return None

        return Evidence(
            date=str(payload.get("attendance_date") or day),
            record_id=record_id,
            check_time=ts,
            result=result,
            location="",                      # 看板不落定位，这里自然为空
            employee_name="",                 # 姓名不从内部接口回传，最小暴露
        )

    def plan_morning(self, event_time: int, type_: str, device_id: str) -> dict | None:
        """告诉看板「今天第一台设备已经启动飞书」，由它决定什么时候统采。"""
        body = json.dumps({"device_id": device_id, "event_time": int(event_time),
                           "type": type_}, ensure_ascii=False).encode("utf-8")
        return self._request("POST", "/internal/morning/plan", body)
