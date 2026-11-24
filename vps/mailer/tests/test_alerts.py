"""checkin_unknown 提醒节制：首次、30 分钟窗口、关闭、重启恢复、失败不推进。"""

import os
import tempfile
import time
import unittest

from mailer import repository as R
from mailer.alerts import AlertStore, CLOSED

DATE = "2026-11-24"


class AlertStoreTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.path = os.path.join(self.tmp, "n.db")
        self.repo = R.Repository(self.path)
        self.alerts = AlertStore(self.path)

    def tearDown(self):
        self.alerts.close_conn()
        self.repo.close()

    def _enqueue(self, event_id):
        self.repo.enqueue(notification_type="checkin_unknown", device_id="dev_1",
                          payload={}, subject="s", body="b", primary="a@x.com",
                          fallback="b@x.com", event_id=event_id)

    def _age(self, seconds):
        """把 first/last 时间往前推，模拟时间流逝。"""
        key = "dev_1:" + DATE
        self.alerts._conn.execute(
            """UPDATE checkin_alerts
               SET first_notified_at=datetime(first_notified_at, ?),
                   last_reminded_at=datetime(last_reminded_at, ?)
               WHERE alert_key=?""",
            ("-%d seconds" % seconds, "-%d seconds" % seconds, key),
        )

    def test_first_notice(self):
        decision, event_id = self.alerts.observe("dev_1", DATE, "t1")
        self.assertEqual("first", decision)
        self.assertTrue(event_id.startswith("unknown-dev_1-"))

    def test_suppressed_within_thirty_minutes(self):
        _, event_id = self.alerts.observe("dev_1", DATE, "t1")
        self._enqueue(event_id)
        self.assertTrue(self.alerts.on_sent(event_id))
        self.assertEqual("suppress", self.alerts.observe("dev_1", DATE, "t2")[0])

    def test_remind_after_thirty_minutes(self):
        _, event_id = self.alerts.observe("dev_1", DATE, "t1")
        self._enqueue(event_id)
        self.alerts.on_sent(event_id)
        self._age(31 * 60)
        self.assertEqual("remind", self.alerts.observe("dev_1", DATE, "t3",
                                                       now_ts=time.time() + 31 * 60)[0])

    def test_same_bucket_suppressed_even_before_send(self):
        _, event_id = self.alerts.observe("dev_1", DATE, "t1")
        self._enqueue(event_id)  # 模拟并发请求已入队
        self.assertEqual("suppress", self.alerts.observe("dev_1", DATE, "t2")[0])

    def test_state_survives_restart(self):
        _, event_id = self.alerts.observe("dev_1", DATE, "t1")
        self._enqueue(event_id)
        self.alerts.on_sent(event_id)
        self.alerts.close_conn()
        reopened = AlertStore(self.path)
        try:
            row = reopened.get("dev_1", DATE)
            self.assertIsNotNone(row["first_notified_at"])
            self.assertEqual("suppress", reopened.observe("dev_1", DATE, "t9")[0])
        finally:
            reopened.close_conn()
        self.alerts = AlertStore(self.path)

    def test_failed_send_does_not_advance_time(self):
        self.alerts.observe("dev_1", DATE, "t1")  # 入队但发送失败 → 不调 on_sent
        row = self.alerts.get("dev_1", DATE)
        self.assertIsNone(row["first_notified_at"])
        self.assertIsNone(row["last_reminded_at"])
        self.assertEqual(0, row["reminder_count"])

    def test_checkin_success_closes_window(self):
        self.alerts.observe("dev_1", DATE, "t1")
        self.assertTrue(self.alerts.close("dev_1", DATE, "checkin_success"))
        self.assertEqual(CLOSED, self.alerts.get("dev_1", DATE)["status"])
        self.assertEqual("closed", self.alerts.observe("dev_1", DATE, "t2")[0])

    def test_reminder_count_increments(self):
        _, e1 = self.alerts.observe("dev_1", DATE, "t1")
        self._enqueue(e1)
        self.alerts.on_sent(e1)
        self._age(31 * 60)
        _, e2 = self.alerts.observe("dev_1", DATE, "t2", now_ts=time.time() + 31 * 60)
        self._enqueue(e2)
        self.alerts.on_sent(e2)
        self.assertEqual(1, self.alerts.get("dev_1", DATE)["reminder_count"])

    def test_windows_are_per_device_and_date(self):
        self.alerts.close("dev_1", DATE, "checkin_success")
        self.assertEqual("closed", self.alerts.observe("dev_1", DATE, "t1")[0])
        self.assertEqual("first", self.alerts.observe("dev_1", "2026-11-25", "t2")[0])
        self.assertEqual("first", self.alerts.observe("dev_2", DATE, "t3")[0])

    def test_on_sent_ignores_unrelated_event(self):
        self.alerts.observe("dev_1", DATE, "t1")
        self.assertFalse(self.alerts.on_sent("launch-123"))
        self.assertFalse(self.alerts.on_sent(""))

    def test_stats(self):
        self.alerts.observe("dev_1", DATE, "t1")
        self.alerts.close("dev_1", DATE, "cleanup_success")
        self.assertEqual({CLOSED: 1}, self.alerts.stats())


if __name__ == "__main__":
    unittest.main()
