"""调度层测试：主邮箱成功 / 切换备用 / 双失败 / 超时未知 / 重启恢复。

对应需求 §八 的测试 A–E（纯逻辑层跑假 CLI，真发信在 VPS 上用真实地址另测）。
"""

import json
import os
import sys
import tempfile
import unittest

from mailer import repository as R
from mailer.agent_mail import AgentMailClient
from mailer.config import Config
from mailer.dispatcher import Dispatcher

FAKE_CLI = os.path.join(os.path.dirname(__file__), "fake_cli.py")


def make_config(tmp: str) -> Config:
    return Config(
        db_path=os.path.join(tmp, "n.db"),
        cli_path=FAKE_CLI,
        cli_config_dir=tmp,
        cli_home=tmp,
        ingest_token="t",
        bind_host="127.0.0.1",
        port=0,
        primary="primary@example.com",
        fallback="fallback@example.com",
        device_whitelist=(),
        success_mode="each",
        send_timeout_s=10,
        daily_quota=50,
        hourly_limit=200,
        minute_limit=10,
        dry_run=False,
        log_path=os.path.join(tmp, "mailer.log"),
    )


class DispatcherTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.state_path = os.path.join(self.tmp, "fake.json")
        self._old_state = os.environ.get("FAKE_STATE")
        os.environ["FAKE_STATE"] = self.state_path
        os.environ.pop("FAKE_HANG", None)
        self.cfg = make_config(self.tmp)
        self.repo = R.Repository(self.cfg.db_path)
        self.client = AgentMailClient(
            [sys.executable, FAKE_CLI], self.tmp, self.tmp, timeout_s=5
        )
        self.dispatcher = Dispatcher(self.cfg, self.repo, self.client)

    def tearDown(self):
        self.repo.close()
        if self._old_state is None:
            os.environ.pop("FAKE_STATE", None)
        else:
            os.environ["FAKE_STATE"] = self._old_state
        os.environ.pop("FAKE_HANG", None)

    # ── 工具 ────────────────────────────────────────────────────────

    def set_plan(self, plan):
        with open(self.state_path, "w", encoding="utf-8") as f:
            json.dump({"plan": list(plan), "sent": [], "send_calls": 0}, f)

    def state(self):
        with open(self.state_path, encoding="utf-8") as f:
            return json.load(f)

    def enqueue(self, event_id="e1"):
        return self.repo.enqueue(
            notification_type="launch_success",
            device_id="dev_1",
            payload={"device_name": "Razr"},
            subject="[QuickLaunch] 自动启动成功",
            body="body",
            primary=self.cfg.primary,
            fallback=self.cfg.fallback,
            event_id=event_id,
        )[1]

    def force_due(self, event_id):
        self.repo._conn.execute(
            "UPDATE notifications SET next_attempt_at='2000-01-01T00:00:00+00:00' WHERE event_id=?",
            (event_id,),
        )

    # ── A ──────────────────────────────────────────────────────────

    def test_a_primary_success_does_not_send_fallback(self):
        self.set_plan([0])
        self.enqueue()
        self.assertTrue(self.dispatcher.run_once())
        row = self.repo.get("e1")
        self.assertEqual(R.PRIMARY_SENT, row.status)
        self.assertEqual("msg_fake_1", row.message_id)
        attempts = self.state()["attempts"]
        self.assertEqual(1, len(attempts))
        self.assertEqual(self.cfg.primary, attempts[0]["to"])

    # ── B ──────────────────────────────────────────────────────────

    def test_b_primary_rejection_fails_over_to_gmail(self):
        self.set_plan([6, 0])
        self.enqueue()
        self.dispatcher.run_once()
        self.assertEqual(R.PRIMARY_FAILED, self.repo.get("e1").status)
        self.force_due("e1")
        self.dispatcher.run_once()
        row = self.repo.get("e1")
        self.assertEqual(R.FALLBACK_SENT, row.status)
        self.assertEqual(self.cfg.fallback, row.current_recipient)
        attempts = self.state()["attempts"]
        self.assertEqual([self.cfg.primary, self.cfg.fallback], [a["to"] for a in attempts])

    # ── C ──────────────────────────────────────────────────────────

    def test_c_both_rejected_ends_failed_but_keeps_body(self):
        self.set_plan([6, 6])
        self.enqueue()
        self.dispatcher.run_once()
        self.force_due("e1")
        self.dispatcher.run_once()
        row = self.repo.get("e1")
        self.assertEqual(R.FAILED, row.status)
        self.assertEqual("body", row.body)

    # ── D ──────────────────────────────────────────────────────────

    def test_d_transient_error_retries_then_fails(self):
        self.set_plan([1, 1, 1])
        self.enqueue()
        self.dispatcher.run_once()
        self.assertEqual(R.PENDING, self.repo.get("e1").status)
        for _ in range(2):
            self.force_due("e1")
            self.dispatcher.run_once()
        row = self.repo.get("e1")
        self.assertEqual(R.FAILED, row.status)
        self.assertEqual(3, self.state()["send_calls"])  # 首次 + 2 次重试

    def test_d_timeout_is_unknown_and_does_not_fail_over(self):
        self.set_plan([0])
        with open(self.state_path, "w", encoding="utf-8") as f:
            json.dump({"plan": [0], "sent": [], "send_calls": 0, "hang": True}, f)
        os.environ["FAKE_HANG"] = "1"
        self.enqueue()
        self.client.timeout_s = 1
        self.dispatcher.run_once()
        row = self.repo.get("e1")
        self.assertEqual(R.UNKNOWN, row.status)
        self.assertEqual(self.cfg.primary, row.current_recipient)  # 没有切备用

    def test_d_unknown_reconciles_without_resending(self):
        # 先造一条「发出去但结果未知」的记录：sent 里已有、库里是 UNKNOWN
        self.set_plan([0])
        self.enqueue()
        self.repo.claim_next()  # 标记为发送中
        self.repo.recover_stale(stale_after_s=0)
        # 假 CLI 的已发送箱里出现同一主题的同一条
        with open(self.state_path, "w", encoding="utf-8") as f:
            json.dump({
                "plan": [],
                "send_calls": 0,
                "sent": [{
                    "to": [{"email": self.cfg.primary}],
                    "subject": "[QuickLaunch] 自动启动成功",
                    "created_at": "2100-01-01T00:00:00Z",
                    "message_id": "msg_existing",
                }],
            }, f)
        self.dispatcher.run_once()
        row = self.repo.get("e1")
        self.assertEqual(R.PRIMARY_SENT, row.status)
        self.assertEqual("msg_existing", row.message_id)
        self.assertEqual(0, self.state()["send_calls"])  # 关键：没有重发

    # ── E ──────────────────────────────────────────────────────────

    def test_e_sent_event_is_never_resent_after_restart(self):
        self.set_plan([0])
        self.enqueue()
        self.dispatcher.run_once()
        # 模拟重启：新仓库 + 新调度器指向同一个 DB
        self.repo.close()
        self.repo = R.Repository(self.cfg.db_path)
        self.dispatcher = Dispatcher(self.cfg, self.repo, self.client)
        self.assertFalse(self.repo.has_due())
        self.assertFalse(self.dispatcher.run_once())
        self.assertEqual(1, self.state()["send_calls"])

    def test_e_duplicate_event_does_not_send_twice(self):
        self.set_plan([0])
        self.enqueue()
        self.enqueue()  # 同一个 event_id 再报一次
        self.dispatcher.run_once()
        self.assertFalse(self.repo.has_due())
        self.assertEqual(1, self.state()["send_calls"])

    # ── 其它 ───────────────────────────────────────────────────────

    def test_auth_failure_pauses_dispatcher(self):
        self.set_plan([3])
        self.enqueue()
        self.dispatcher.run_once()
        self.assertEqual(R.AUTH_REQUIRED, self.repo.get("e1").status)
        self.assertTrue(self.dispatcher.paused)


if __name__ == "__main__":
    unittest.main()
