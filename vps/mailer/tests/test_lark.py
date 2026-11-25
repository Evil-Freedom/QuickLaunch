"""VPS 侧飞书考勤直查：判据必须与 Windows 核验端一致。"""

import os
import sys
import time
import unittest
from datetime import datetime

from mailer.lark import LarkClient

FAKE = os.path.join(os.path.dirname(__file__), "fake_lark.py")
TODAY = datetime.now().strftime("%Y-%m-%d")


class LarkClientTest(unittest.TestCase):
    def setUp(self):
        self._old = os.environ.get("FAKE_LARK_MODE")
        self.client = LarkClient([sys.executable, FAKE], timeout_s=2, home=".")

    def tearDown(self):
        if self._old is None:
            os.environ.pop("FAKE_LARK_MODE", None)
        else:
            os.environ["FAKE_LARK_MODE"] = self._old

    def mode(self, value):
        os.environ["FAKE_LARK_MODE"] = value

    def test_found_returns_evidence(self):
        self.mode("ok")
        ev = self.client.lookup(TODAY, int(time.time()) - 600)
        self.assertIsNotNone(ev)
        self.assertEqual("7711902438671797504", ev.record_id)
        self.assertEqual("Normal", ev.result)
        self.assertIn("晓葆科技", ev.location)
        self.assertTrue(ev.check_time_text.startswith(TODAY))

    def test_todo_record_is_not_evidence(self):
        self.mode("todo")
        self.assertIsNone(self.client.lookup(TODAY, int(time.time()) - 600))

    def test_old_punch_is_not_evidence(self):
        self.mode("old")
        self.assertIsNone(self.client.lookup(TODAY, int(time.time()) - 600))

    def test_late_still_counts_as_punched(self):
        self.mode("late")
        ev = self.client.lookup(TODAY, int(time.time()) - 600)
        self.assertIsNotNone(ev)
        self.assertEqual("Late", ev.result)

    def test_empty_day(self):
        self.mode("empty")
        self.assertIsNone(self.client.lookup(TODAY, int(time.time()) - 600))

    def test_cli_error_payload(self):
        self.mode("error")
        self.assertIsNone(self.client.lookup(TODAY, int(time.time()) - 600))

    def test_non_json_output(self):
        self.mode("garbage")
        self.assertIsNone(self.client.lookup(TODAY, int(time.time()) - 600))

    def test_timeout_is_unknown(self):
        self.mode("timeout")
        client = LarkClient([sys.executable, FAKE], timeout_s=1, home=".")
        started = time.time()
        self.assertIsNone(client.lookup(TODAY, int(time.time()) - 600))
        self.assertLess(time.time() - started, 5)

    def test_missing_cli_binary(self):
        client = LarkClient(["/nonexistent/lark-cli"], timeout_s=2, home=".")
        self.assertIsNone(client.lookup(TODAY, int(time.time()) - 600))


if __name__ == "__main__":
    unittest.main()
