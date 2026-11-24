"""考勤证据与清理动作：幂等、领取、有效期、结果回报、防重放。"""

import json
import os
import tempfile
import unittest

from mailer import repository as R
from mailer.actions import (ActionStore, EXPIRED, FAILED, PENDING, SUCCESS, DELIVERED)


class ActionStoreTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.path = os.path.join(self.tmp, "n.db")
        self.repo = R.Repository(self.path)
        self.store = ActionStore(self.path)

    def tearDown(self):
        self.store.close()
        self.repo.close()

    def _launch(self, task_id="task-1", event_id="evt-1"):
        self.repo.enqueue(
            notification_type="launch_success", device_id="dev_1",
            payload={"task_id": task_id, "target_app": "飞书"},
            subject="s", body="b", primary="a@x.com", fallback="b@x.com",
            event_id=event_id,
        )

    # ── 证据 ────────────────────────────────────────────────────────

    def test_attendance_is_idempotent(self):
        args = dict(task_id="t1", device_id="dev_1", attendance_date="2026-11-24",
                    evidence_source="lark-cli", evidence_time="08:55:12", evidence_ref="r1")
        self.assertTrue(self.store.record_attendance(result="success", **args))
        self.assertFalse(self.store.record_attendance(result="success", **args))
        self.assertEqual("r1", self.store.attendance("t1")["evidence_ref"])

    def test_pending_checkins_skips_verified(self):
        self._launch("task-1", "evt-1")
        self._launch("task-2", "evt-2")
        self.assertEqual(2, len(self.store.pending_checkins()))
        self.store.record_attendance(task_id="task-1", device_id="dev_1",
                                     attendance_date="2026-11-24", evidence_source="lark-cli",
                                     evidence_time="08:55", evidence_ref="", result="success")
        pending = self.store.pending_checkins()
        self.assertEqual(["task-2"], [t["task_id"] for t in pending])

    # ── 动作 ────────────────────────────────────────────────────────

    def test_create_action_is_idempotent_per_task(self):
        created1, a1 = self.store.create_action(device_id="dev_1", task_id="t1",
                                                action_type="FORCE_STOP_FEISHU", ttl_s=60)
        created2, a2 = self.store.create_action(device_id="dev_1", task_id="t1",
                                                action_type="FORCE_STOP_FEISHU", ttl_s=60)
        self.assertTrue(created1)
        self.assertFalse(created2)
        self.assertEqual(a1.action_id, a2.action_id)

    def test_action_type_is_whitelisted(self):
        with self.assertRaises(ValueError):
            self.store.create_action(device_id="dev_1", task_id="t1",
                                     action_type="rm -rf /", ttl_s=60)

    def test_pending_is_per_device_and_marks_delivered(self):
        _, a = self.store.create_action(device_id="dev_1", task_id="t1",
                                        action_type="FORCE_STOP_FEISHU", ttl_s=60)
        self.assertEqual([], self.store.pending_for_device("dev_2"))
        got = self.store.pending_for_device("dev_1")
        self.assertEqual([a.action_id], [x.action_id for x in got])
        self.assertEqual(DELIVERED, self.store.get(a.action_id).status)
        # 再次拉取仍可见（DELIVERED 也属于待执行），不会丢
        self.assertEqual(1, len(self.store.pending_for_device("dev_1")))

    def test_expired_action_is_not_delivered(self):
        _, a = self.store.create_action(device_id="dev_1", task_id="t1",
                                        action_type="FORCE_STOP_FEISHU", ttl_s=-1)
        self.assertEqual([], self.store.pending_for_device("dev_1"))
        self.assertEqual(EXPIRED, self.store.get(a.action_id).status)

    def test_begin_checks_nonce_and_device(self):
        _, a = self.store.create_action(device_id="dev_1", task_id="t1",
                                        action_type="FORCE_STOP_FEISHU", ttl_s=60)
        ok, reason, _ = self.store.begin(a.action_id, "dev_1", "wrong")
        self.assertFalse(ok)
        self.assertEqual("nonce_mismatch", reason)
        ok, reason, _ = self.store.begin(a.action_id, "dev_2", a.nonce)
        self.assertFalse(ok)
        self.assertEqual("device_mismatch", reason)
        ok, reason, model = self.store.begin(a.action_id, "dev_1", a.nonce)
        self.assertTrue(ok)
        self.assertEqual("CLEANUP_RUNNING", model.status)

    def test_finish_is_idempotent_and_final(self):
        _, a = self.store.create_action(device_id="dev_1", task_id="t1",
                                        action_type="FORCE_STOP_FEISHU", ttl_s=60)
        self.store.begin(a.action_id, "dev_1", a.nonce)
        ok, _, model = self.store.finish(a.action_id, "dev_1", SUCCESS, "pid 全部退出")
        self.assertTrue(ok)
        self.assertEqual(SUCCESS, model.status)
        ok, reason, _ = self.store.finish(a.action_id, "dev_1", FAILED, "再报一次")
        self.assertFalse(ok)
        self.assertEqual("already_final", reason)
        self.assertEqual(SUCCESS, self.store.get(a.action_id).status)

    def test_finish_rejects_unknown_status(self):
        _, a = self.store.create_action(device_id="dev_1", task_id="t1",
                                        action_type="FORCE_STOP_FEISHU", ttl_s=60)
        ok, reason, _ = self.store.finish(a.action_id, "dev_1", "RUN_ANY_SHELL", "x")
        self.assertFalse(ok)
        self.assertEqual("invalid_status", reason)


if __name__ == "__main__":
    unittest.main()
