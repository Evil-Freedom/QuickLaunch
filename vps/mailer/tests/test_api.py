"""HTTP 接入层测试：鉴权、白名单、幂等、成功通知开关。"""

import http.client
import json
import os
import sys
import tempfile
import threading
import unittest

from mailer import repository as R
from mailer.agent_mail import AgentMailClient
from mailer.actions import ActionStore
from mailer.api import build_server
from mailer.config import Config
from mailer.dispatcher import Dispatcher

FAKE_CLI = os.path.join(os.path.dirname(__file__), "fake_cli.py")


class ApiTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        os.environ["FAKE_STATE"] = os.path.join(self.tmp, "fake.json")
        self.cfg = Config(
            db_path=os.path.join(self.tmp, "n.db"),
            cli_path=FAKE_CLI,
            cli_config_dir=self.tmp,
            cli_home=self.tmp,
            ingest_token="secret-token",
            bind_host="127.0.0.1",
            port=0,
            primary="primary@example.com",
            fallback="fallback@example.com",
            device_whitelist=("dev_ok",),
            success_mode="each",
            send_timeout_s=5,
            daily_quota=50,
            hourly_limit=200,
            minute_limit=10,
            dry_run=False,
            log_path=os.path.join(self.tmp, "mailer.log"),
            verifier_token="verify-token",
            action_ttl_s=1800,
        )
        self.repo = R.Repository(self.cfg.db_path)
        self.client = AgentMailClient([sys.executable, FAKE_CLI], self.tmp, self.tmp)
        self.dispatcher = Dispatcher(self.cfg, self.repo, self.client)
        self.store = ActionStore(self.cfg.db_path)
        self.server = build_server(self.cfg, self.repo, self.store, self.dispatcher)
        self.port = self.server.server_address[1]
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def tearDown(self):
        self.server.shutdown()
        self.repo.close()
        self.store.close()

    def post(self, body, token="secret-token"):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=5)
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        conn.request("POST", "/v1/events", json.dumps(body), headers)
        resp = conn.getresponse()
        data = json.loads(resp.read() or b"{}")
        conn.close()
        return resp.status, data

    def event(self, **kw):
        base = {
            "type": "launch_success",
            "device_id": "dev_ok",
            "event_id": "evt-http-1",
            "data": {"device_name": "Razr", "rule_name": "飞书"},
        }
        base.update(kw)
        return base

    def test_requires_token(self):
        status, _ = self.post(self.event(), token="")
        self.assertEqual(401, status)

    def test_accepts_and_is_idempotent(self):
        status, data = self.post(self.event())
        self.assertEqual(202, status)
        self.assertFalse(data["duplicate"])
        status, data = self.post(self.event())
        self.assertEqual(202, status)
        self.assertTrue(data["duplicate"])
        self.assertEqual(1, len(self.repo.stats().items()))
        self.assertEqual({"PENDING": 1}, self.repo.stats())

    def test_rejects_unknown_device(self):
        status, data = self.post(self.event(device_id="dev_other"))
        self.assertEqual(403, status)
        self.assertEqual("device_not_allowed", data["error"])

    def test_rejects_unknown_type(self):
        status, _ = self.post(self.event(type="evil"))
        self.assertEqual(400, status)

    def test_recipients_cannot_be_injected(self):
        # 请求里塞收件人字段必须被忽略，实际收件人只能来自配置
        status, data = self.post(self.event(data={"device_name": "R", "to": "attacker@evil.com"}))
        self.assertEqual(202, status)
        row = self.repo.get(data["event_id"])
        self.assertEqual("primary@example.com", row.primary_recipient)
        self.assertNotIn("attacker@evil.com", row.body)

    def test_checkin_success_without_evidence_rejected(self):
        status, _ = self.post(self.event(type="checkin_success", data={"device_name": "R"}))
        self.assertEqual(400, status)

    def test_status_endpoint(self):
        _, data = self.post(self.event())
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=5)
        conn.request("GET", f"/v1/events/{data['event_id']}")
        resp = conn.getresponse()
        payload = json.loads(resp.read())
        conn.close()
        self.assertEqual("PENDING", payload["status"])

    def verify(self, body, token="verify-token"):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=5)
        conn.request("POST", "/v1/attendance/verify", json.dumps(body),
                     {"Content-Type": "application/json", "Authorization": f"Bearer {token}"})
        resp = conn.getresponse()
        data = json.loads(resp.read() or b"{}")
        conn.close()
        return resp.status, data

    def _evidence(self, **kw):
        base = {
            "task_id": "t-1", "device_id": "dev_ok", "attendance_date": "2026-11-24",
            "evidence_source": "lark-cli", "evidence_time": "2026-11-24 08:55:12",
            "evidence_ref": "r-1", "result": "success",
        }
        base.update(kw)
        return base

    def test_verify_requires_evidence(self):
        status, data = self.verify({"task_id": "t-1"})
        self.assertEqual(400, status)
        self.assertEqual("missing_evidence", data["error"])

    def test_verify_requires_verifier_token(self):
        status, _ = self.verify(self._evidence(), token="wrong")
        self.assertEqual(401, status)

    def test_pending_result_does_not_create_action(self):
        status, data = self.verify(self._evidence(result="pending"))
        self.assertEqual(202, status)
        self.assertFalse(data["verified"])
        self.assertEqual({}, self.store.stats())

    def test_success_creates_one_action_and_is_idempotent(self):
        status, first = self.verify(self._evidence())
        self.assertEqual(202, status)
        self.assertTrue(first["verified"])
        status, second = self.verify(self._evidence())
        self.assertEqual(202, status)
        self.assertEqual(first["action_id"], second["action_id"])
        self.assertEqual({"PENDING": 1}, self.store.stats())
        # 只产生一封考勤成功邮件与一封清理待执行邮件（重复核验不再新增）
        self.assertEqual({"PENDING": 2}, self.repo.stats())
        types = sorted(r[0] for r in self.repo._conn.execute("select notification_type from notifications"))
        self.assertEqual(["checkin_success", "cleanup_pending"], types)

    def test_healthz(self):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=5)
        conn.request("GET", "/healthz")
        resp = conn.getresponse()
        payload = json.loads(resp.read())
        conn.close()
        self.assertTrue(payload["ok"])
        self.assertEqual("primary@example.com", payload["primary"])


if __name__ == "__main__":
    unittest.main()
