#!/usr/bin/env python3
"""假 CLI：按预设脚本返回退出码，供单测覆盖 failover / 幂等 / UNKNOWN。

状态文件（FAKE_STATE）结构：
{
  "plan": [0, 6],                  # 每次 +send 依次消费的退出码，用完后默认 0
  "sent": [{"to":..., "subject":..., "created_at":..., "message_id":...}],
  "send_calls": 3
}
"""

import json
import os
import sys
from datetime import datetime, timezone


def load():
    path = os.environ.get("FAKE_STATE")
    if not path or not os.path.exists(path):
        return {"plan": [], "sent": [], "send_calls": 0}
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def save(state):
    path = os.environ["FAKE_STATE"]
    with open(path, "w", encoding="utf-8") as f:
        json.dump(state, f)


def arg(name, default=""):
    argv = sys.argv[1:]
    if name in argv:
        i = argv.index(name)
        if i + 1 < len(argv):
            return argv[i + 1]
    return default


def main():
    state = load()
    argv = sys.argv[1:]
    now = datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")

    if argv[:1] == ["+me"]:
        print(json.dumps({
            "ok": True,
            "data": {"aliases": [{"email": os.environ.get("FAKE_EMAIL", "test@agent.qq.com"),
                                  "is_primary": True}]},
        }))
        return 0

    if argv[:2] == ["auth", "status"]:
        print(json.dumps({"ok": True, "data": {"logged_in": True}}))
        return 0

    if argv[:2] == ["message", "+send"]:
        if os.environ.get("FAKE_HANG"):
            import time
            time.sleep(30)  # 模拟「请求发出去了但一直没有响应」
        plan = state.get("plan") or []
        code = plan.pop(0) if plan else 0
        state["plan"] = plan
        state["send_calls"] = state.get("send_calls", 0) + 1
        state.setdefault("attempts", []).append({"to": arg("--to"),
                                                  "subject": arg("--subject"),
                                                  "code": code})
        if code == 0:
            state.setdefault("sent", []).append({
                "to": [{"email": arg("--to")}],
                "subject": arg("--subject"),
                "created_at": now,
                "message_id": f"msg_fake_{len(state['sent']) + 1}",
            })
            save(state)
            print(json.dumps({"ok": True, "data": {"queued": True}}))
            return 0
        save(state)
        sys.stderr.write(os.environ.get("FAKE_STDERR", "recipient address rejected: user unknown"))
        return code

    if argv[:2] == ["message", "+list"]:
        print(json.dumps({"ok": True, "data": {"data": list(reversed(state.get("sent", [])))}}))
        return 0

    print(json.dumps({"ok": False, "error": "unsupported"}), file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main())
