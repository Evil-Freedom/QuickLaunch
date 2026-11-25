#!/usr/bin/env python3
"""假 lark-cli：按 FAKE_LARK_MODE 输出不同考勤结果，供单测覆盖判据分支。"""

import json
import os
import sys
import time


def record(result="Normal", record_id="7711902438671797504", minutes_ago=1, location="晓葆科技"):
    ts = int(time.time()) - minutes_ago * 60
    return {
        "user_task_results": [{
            "day": int(time.strftime("%Y%m%d")),
            "employee_name": "姜芃",
            "records": [{
                "check_in_record_id": record_id,
                "check_in_result": result,
                "check_in_record": {
                    "check_time": str(ts),
                    "location_name": location,
                    "record_id": record_id,
                },
            }],
        }]
    }


def main():
    mode = os.environ.get("FAKE_LARK_MODE", "ok")
    if mode == "error":
        print(json.dumps({"ok": False, "error": {"message": "no permission"}}))
        return 0
    if mode == "garbage":
        print("not json at all")
        return 0
    if mode == "timeout":
        time.sleep(30)
        return 0
    if mode == "empty":
        print(json.dumps({"ok": True, "data": {"user_task_results": []}}))
        return 0
    if mode == "todo":
        print(json.dumps({"ok": True, "data": record(result="Todo", record_id="")}))
        return 0
    if mode == "old":
        print(json.dumps({"ok": True, "data": record(minutes_ago=120)}))
        return 0
    if mode == "late":
        print(json.dumps({"ok": True, "data": record(result="Late")}))
        return 0
    print(json.dumps({"ok": True, "data": record()}))
    print("tip: lark-cli message +list")   # 真实 CLI 会追加提示，解析必须容忍
    return 0


if __name__ == "__main__":
    sys.exit(main())
