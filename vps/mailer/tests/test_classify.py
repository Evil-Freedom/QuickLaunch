"""classify / render 的纯逻辑测试。"""

import unittest

from mailer import classify as C
from mailer import render


class ClassifyTest(unittest.TestCase):
    def test_success(self):
        self.assertEqual(C.KIND_SENT, C.classify(0).kind)

    def test_server_and_local_network_are_retryable(self):
        self.assertEqual(C.KIND_RETRYABLE, C.classify(1).kind)
        self.assertEqual(C.KIND_RETRYABLE, C.classify(4).kind)

    def test_param_error_is_permanent(self):
        self.assertEqual(C.KIND_PERMANENT, C.classify(2).kind)

    def test_auth_is_not_retryable(self):
        self.assertEqual(C.KIND_AUTH, C.classify(3).kind)

    def test_rate_limit_carries_wait(self):
        out = C.classify(7)
        self.assertEqual(C.KIND_RATE_LIMITED, out.kind)
        self.assertGreater(out.retry_after_s, 0)

    def test_business_rejection_only_fails_over_when_recipient_related(self):
        self.assertEqual(C.KIND_RECIPIENT, C.classify(6, "550 recipient address rejected").kind)
        self.assertEqual(C.KIND_PERMANENT, C.classify(6, "content policy violation").kind)

    def test_malformed_recipient_is_a_recipient_problem_not_a_network_error(self):
        # 真实实测：`--to not-an-email` → exit 1 + "--to must be a valid email address"
        out = C.classify(1, "Error: --to must be a valid email address")
        self.assertEqual(C.KIND_RECIPIENT, out.kind)

    def test_timeout_is_unknown_not_failure(self):
        self.assertEqual(C.KIND_UNKNOWN, C.classify(-1, "", timed_out=True).kind)

    def test_unknown_exit_code_is_permanent(self):
        self.assertEqual(C.KIND_PERMANENT, C.classify(99).kind)

    def test_backoff_is_short_and_bounded(self):
        self.assertEqual(30, C.backoff_seconds(0))
        self.assertEqual(120, C.backoff_seconds(1))
        self.assertEqual(120, C.backoff_seconds(9))


class RenderTest(unittest.TestCase):
    def test_subjects_are_fixed(self):
        for t, subject in render.SUBJECTS.items():
            payload = {"device_name": "Razr", "evidence_source": "feishu", "evidence_time": "08:50"}
            _, body = render.render(t, payload)
            self.assertIn("Razr", body)
        self.assertEqual("[QuickLaunch] 自动启动成功", render.SUBJECTS["launch_success"])
        self.assertEqual("[QuickLaunch] 打卡结果待确认", render.SUBJECTS["checkin_unknown"])

    def test_pin_fields_are_never_rendered(self):
        subject, body = render.render("unlock_alert", {
            "device_name": "Razr",
            "pin": "941007",
            "pinHash": "deadbeef",
            "keystoreAlias": "ql-auto-unlock-v1",
            "reason": "连续失败 2 次",
        })
        self.assertNotIn("941007", body)
        self.assertNotIn("deadbeef", body)
        self.assertNotIn("ql-auto-unlock-v1", body)
        self.assertIn("连续失败 2 次", body)

    def test_checkin_success_requires_evidence(self):
        with self.assertRaises(ValueError):
            render.render("checkin_success", {"device_name": "Razr"})

    def test_control_chars_and_long_values_are_sanitized(self):
        _, body = render.render("launch_failed", {
            "device_name": "Razr\x00\x1b[31m",
            "last_error": "x" * 5000,
        })
        self.assertNotIn("\x00", body)
        self.assertLess(len(body), 1200)

    def test_unknown_type_rejected(self):
        with self.assertRaises(ValueError):
            render.render("whatever", {})


if __name__ == "__main__":
    unittest.main()
