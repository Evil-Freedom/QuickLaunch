"""网页端触发文案的单测（stdlib unittest，不引入额外依赖）。

跑法：`python -m unittest discover -s vps/tests -p "test_*.py"`

重点钉住随机窗口：`randomWindow=true` 时 `timeHour/timeMinute` 不参与调度，
旧实现把被忽略的 10:23 印成「定时 10:23」，与手机端一样会误导用户。
"""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.ruleview import clock_of, trigger_label  # noqa: E402


class TriggerLabelTest(unittest.TestCase):
    def test_random_window_replaces_fixed_time(self):
        # 真机 #7 的原样数据：randomWindow=1, 08:45–08:55, 周一至周四, 跳假
        rule = {
            "triggerType": "TIME",
            "timeHour": 10,
            "timeMinute": 23,
            "repeatMode": "custom",
            "repeatDays": 0b0011110,
            "skipHolidays": True,
            "randomWindow": True,
            "windowStartMin": 525,
            "windowEndMin": 535,
        }
        self.assertEqual("随机 08:45–08:55（自定义(一二三四)）·跳假", trigger_label(rule))

    def test_fixed_time_unchanged(self):
        rule = {
            "triggerType": "TIME",
            "timeHour": 10,
            "timeMinute": 23,
            "repeatMode": "custom",
            "repeatDays": 0b0011110,
            "skipHolidays": True,
            "randomWindow": False,
        }
        self.assertEqual("定时 10:23（自定义(一二三四)）·跳假", trigger_label(rule))

    def test_daily_label(self):
        rule = {"triggerType": "TIME", "timeHour": 9, "timeMinute": 0, "repeatMode": "daily"}
        self.assertEqual("定时 09:00（每天）", trigger_label(rule))

    def test_clock_of_clamps_out_of_range(self):
        self.assertEqual("00:00", clock_of(0))
        self.assertEqual("23:59", clock_of(1439))
        self.assertEqual("00:00", clock_of(-5))
        self.assertEqual("23:59", clock_of(2000))


if __name__ == "__main__":
    unittest.main()
