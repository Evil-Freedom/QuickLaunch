"""仓库层测试：幂等、状态迁移、重启恢复、配额计数。"""

import os
import tempfile
import time
import unittest

from mailer import repository as R


class RepositoryTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.repo = R.Repository(os.path.join(self.tmp, "n.db"))

    def tearDown(self):
        self.repo.close()

    def _enqueue(self, event_id="evt-1", type_="launch_success", payload=None):
        subject, body = "s", "b"
        return self.repo.enqueue(
            notification_type=type_,
            device_id="dev_1",
            payload=payload or {},
            subject=subject,
            body=body,
            primary="a@x.com",
            fallback="b@x.com",
            event_id=event_id,
        )

    def test_same_event_id_is_idempotent(self):
        created1, row1 = self._enqueue()
        created2, row2 = self._enqueue()
        self.assertTrue(created1)
        self.assertFalse(created2)
        self.assertEqual(row1.event_id, row2.event_id)
        self.assertEqual({"PENDING": 1}, self.repo.stats())

    def test_claim_moves_to_sending_and_records_origin(self):
        self._enqueue()
        row = self.repo.claim_next()
        self.assertEqual(R.PRIMARY_SENDING, row.status)
        self.assertEqual("PENDING", row.claimed_from)
        self.assertEqual(1, row.attempts)

    def test_primary_rejection_switches_recipient(self):
        self._enqueue()
        row = self.repo.claim_next()
        self.repo.mark_recipient_rejected(row.event_id, 6, "recipient rejected")
        after = self.repo.get(row.event_id)
        self.assertEqual(R.PRIMARY_FAILED, after.status)
        self.assertEqual("b@x.com", after.current_recipient)

    def test_fallback_rejection_ends_as_failed(self):
        self._enqueue()
        row = self.repo.claim_next()
        self.repo.mark_recipient_rejected(row.event_id, 6, "reject")
        row2 = self.repo.claim_next()
        self.assertEqual(R.FALLBACK_SENDING, row2.status)
        self.repo.mark_recipient_rejected(row2.event_id, 6, "reject")
        self.assertEqual(R.FAILED, self.repo.get(row.event_id).status)

    def test_sent_status_follows_actual_recipient(self):
        self._enqueue()
        row = self.repo.claim_next()
        self.repo.mark_recipient_rejected(row.event_id, 6, "reject")
        row2 = self.repo.claim_next()
        self.repo.mark_sent(row2.event_id, "msg_2")
        after = self.repo.get(row.event_id)
        self.assertEqual(R.FALLBACK_SENT, after.status)
        self.assertEqual("msg_2", after.message_id)

    def test_recover_stale_marks_unknown_not_pending(self):
        self._enqueue()
        self.repo.claim_next()
        ids = self.repo.recover_stale(stale_after_s=0)
        self.assertEqual(["evt-1"], ids)
        self.assertEqual(R.UNKNOWN, self.repo.get("evt-1").status)

    def test_unknown_row_is_claimable_but_keeps_origin(self):
        self._enqueue()
        self.repo.claim_next()
        self.repo.recover_stale(stale_after_s=0)
        again = self.repo.claim_next()
        self.assertEqual("UNKNOWN", again.claimed_from)

    def test_requeue_delays_next_attempt(self):
        self._enqueue()
        row = self.repo.claim_next()
        self.repo.requeue(row.event_id, 60, 1, "server error")
        after = self.repo.get(row.event_id)
        self.assertEqual(R.PENDING, after.status)
        self.assertEqual(1, after.retry_count)
        self.assertGreater(after.next_attempt_at, row.created_at)
        self.assertFalse(self.repo.has_due())

    def test_auth_required_pauses_row_until_resume(self):
        self._enqueue()
        row = self.repo.claim_next()
        self.repo.mark_auth_required(row.event_id, 3, "expired")
        self.assertFalse(self.repo.has_due())
        self.assertEqual(1, self.repo.resume_auth_required())
        self.assertTrue(self.repo.has_due())

    def test_quota_counting_uses_sent_at(self):
        self._enqueue("e1")
        row = self.repo.claim_next()
        self.repo.mark_sent(row.event_id, "m1")
        since = "2000-01-01T00:00:00+00:00"
        self.assertEqual(1, self.repo.sent_since(since))
        self.assertEqual(0, self.repo.sent_since("2999-01-01T00:00:00+00:00"))

    def test_priority_orders_failures_before_success(self):
        self._enqueue("ok", "launch_success")
        self._enqueue("bad", "unlock_alert")
        first = self.repo.claim_next()
        self.assertEqual("bad", first.event_id)


if __name__ == "__main__":
    unittest.main()
