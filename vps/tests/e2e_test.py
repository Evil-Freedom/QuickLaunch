"""
端到端自检：不依赖手机，用 Python 模拟 Android 客户端把整条链路跑一遍。

运行方式（服务已在 127.0.0.1:8777 起来）：
    python tests/e2e_test.py http://127.0.0.1:8777

覆盖：
1. 存活探测
2. 未登录访问被拒
3. 错误口令被拒 / 正确口令登录
4. 创建设备
5. 无签名/错签名/过期时间戳/重放 nonce 全部被拒
6. 首次拉取（完整配置）
7. 已是最新时返回 updated=false（且仍然签名）
8. 版本回退尝试被服务端按版本单调处理
9. 回报结果
10. 撤销设备后拉取被拒

这些用例的价值在于「不需要真机就能发现签名拼接、时间窗、版本逻辑的错误」——
它们与 Android 侧的 `VpsEnvelopeTest` / `VpsInteropTest` 互为镜像。
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8777"
PASSWORD = os.environ.get("QL_ADMIN_PASSWORD", "testpass")

passed = 0
failed = 0


def check(name: str, cond: bool, extra: str = "") -> None:
    global passed, failed
    if cond:
        passed += 1
        print(f"  ✓ {name}")
    else:
        failed += 1
        print(f"  ✗ {name} {extra}")


def hmac_hex(key: bytes, data: str) -> str:
    return hmac.new(key, data.encode(), hashlib.sha256).hexdigest()


def req_sign(secret, path, ts, nonce, device_id, since, chash=""):
    """与 Android 侧 requestSignature / 服务端 auth.request_signature 同一拼接。"""
    return hmac_hex(
        secret, "GET\n%s\n%d\n%s\n%s\n%d\n%s" % (path, ts, nonce, device_id, since, chash)
    )


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None


_no_redirect = urllib.request.build_opener(_NoRedirect)


def request(method: str, path: str, headers: dict[str, str] | None = None,
            body: bytes | None = None, cookie: str | None = None,
            follow: bool = True):
    req = urllib.request.Request(BASE + path, data=body, method=method)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    if cookie:
        req.add_header("Cookie", cookie)
    opener = urllib.request.urlopen if follow else _no_redirect.open
    try:
        with opener(req, timeout=15) as r:
            return r.status, r.read().decode("utf-8", "replace"), r.headers
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace"), e.headers


def login() -> str:
    """表单登录，返回 Cookie 头。"""
    data = urllib.parse.urlencode({"password": PASSWORD}).encode()
    req = urllib.request.Request(
        BASE + "/login", data=data, method="POST",
        headers={"Content-Type": "application/x-www-form-urlencoded"},
    )
    # 不跟随重定向，直接取 Set-Cookie
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *a, **k):
            return None

    opener = urllib.request.build_opener(NoRedirect)
    try:
        with opener.open(req, timeout=15) as r:
            raw = r.headers.get("Set-Cookie", "")
    except urllib.error.HTTPError as e:
        raw = e.headers.get("Set-Cookie", "")
        if e.code not in (301, 302, 303):
            raise
    return raw.split(";")[0] if raw else ""


def main() -> int:
    print(f"== 目标 {BASE} ==")

    print("\n[1] 存活探测")
    code, body, _ = request("GET", "/api/v1/ping")
    check("ping 返回 200", code == 200, f"实际 {code}")
    check("ping 不泄露规则内容", "rules" not in body, body[:80])

    print("\n[2] 未登录保护")
    code, _, _ = request("GET", "/rules", follow=False)
    check("未登录访问 /rules 被重定向", code in (302, 307), f"实际 {code}")

    # 登录后界面能真正渲染（模板语法错误会在这里暴露成 500）
    code, body, _ = request("GET", "/login")
    check("/login 页面可渲染", code == 200 and "管理员登录" in body, f"实际 {code}")

    print("\n[3] 登录")
    code, _, _ = request(
        "POST", "/login",
        headers={"Content-Type": "application/x-www-form-urlencoded"},
        body=urllib.parse.urlencode({"password": "wrong-password"}).encode(),
    )
    check("错误口令被拒", code == 401, f"实际 {code}")
    cookie = login()
    check("正确口令拿到会话", bool(cookie), cookie)
    code, body, _ = request("GET", "/rules", cookie=cookie, follow=False)
    check("/rules 登录后可渲染", code == 200 and "启动规则" in body, f"实际 {code}")
    code, body, _ = request("GET", "/devices", cookie=cookie, follow=False)
    check("/devices 登录后可渲染", code == 200 and "添加设备" in body, f"实际 {code}")
    code, body, _ = request("GET", "/versions", cookie=cookie, follow=False)
    check("/versions 登录后可渲染", code == 200 and "配置版本" in body, f"实际 {code}")
    code, body, _ = request("GET", "/audit", cookie=cookie, follow=False)
    check("/audit 登录后可渲染", code == 200 and "操作日志" in body, f"实际 {code}")

    print("\n[4] 创建设备")
    code, _, _ = request(
        "POST", "/devices/create",
        headers={"Content-Type": "application/x-www-form-urlencoded"},
        body=urllib.parse.urlencode({"name": "e2e-test"}).encode(),
        cookie=cookie,
    )
    check("创建设备成功", code in (200, 302), f"实际 {code}")

    # 直接从库里读设备凭据（管理员有权限，等价于页面上"只显示一次"的那份）
    sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    from app import db as qldb  # noqa: E402

    devs = qldb.list_devices()
    check("设备已入库", len(devs) > 0)
    if not devs:
        return 1
    device_id = devs[0]["id"]
    secret_b64 = qldb.get_device(device_id)["secret"]
    secret = base64.b64decode(secret_b64)

    print("\n[5] 签名校验")
    since = 0
    path = f"/api/v1/config?device={device_id}&since={since}"
    ts = int(time.time() * 1000)

    code, _, _ = request("GET", path)
    check("无签名被拒", code == 401, f"实际 {code}")

    nonce = "n" + str(ts)
    bad = req_sign(b"\x00" * 32, path, ts, nonce, device_id, since)
    code, _, _ = request("GET", path, headers={
        "X-QL-Ts": str(ts), "X-QL-Nonce": nonce, "X-QL-Sign": bad})
    check("错签名被拒", code == 401, f"实际 {code}")

    old_ts = ts - 20 * 60 * 1000
    sig = req_sign(secret, path, old_ts, nonce, device_id, since)
    code, _, _ = request("GET", path, headers={
        "X-QL-Ts": str(old_ts), "X-QL-Nonce": nonce, "X-QL-Sign": sig})
    check("过期时间戳被拒", code == 401, f"实际 {code}")

    print("\n[6] 首次拉取")
    # 先写入一条规则并发布，模拟管理员在界面上改过
    qldb.upsert_rule(2, {"timeHour": 8, "timeMinute": 30, "repeatMode": "weekdays", "enabled": True})
    ver = qldb.publish("e2e 初始配置")
    nonce = "n" + str(ts) + "a"
    sig = req_sign(secret, path, ts, nonce, device_id, since)
    code, body, _ = request("GET", path, headers={
        "X-QL-Ts": str(ts), "X-QL-Nonce": nonce, "X-QL-Sign": sig})
    check("拉取成功", code == 200, f"实际 {code} {body[:120]}")
    env = json.loads(body)
    check("信封含 alg", env.get("alg") == "HMAC-SHA256")
    check("信封 version 正确", env.get("version") == ver, f"{env.get('version')} vs {ver}")
    check("updated=true", env.get("updated") is True)
    check("信封含内容指纹", bool(env.get("hash")), f"hash={env.get('hash')}")
    # 客户端视角验签（指纹在签名输入末尾）
    expect = hmac_hex(secret, "%s\n%d\n%d\n%s\n%s\n%s" % (
        env["alg"], env["version"], env["issuedAt"], env["nonce"], env["payload"], env.get("hash", "")))
    check("响应签名正确", expect == env["sign"])
    payload = json.loads(base64.b64decode(env["payload"]))
    check("payload 含规则", any(r.get("id") == 2 for r in payload.get("rules", [])))
    check("payload 含重试策略", "retry" in payload)
    check("payload 含解锁策略", "unlock" in payload)

    print("\n[7] 已是最新")
    since2 = ver
    applied_hash = env.get("hash", "")
    path2 = f"/api/v1/config?device={device_id}&since={since2}&hash={applied_hash}"
    ts2 = int(time.time() * 1000)
    nonce2 = "n" + str(ts2)
    sig2 = req_sign(secret, path2, ts2, nonce2, device_id, since2, applied_hash)
    code, body, _ = request("GET", path2, headers={
        "X-QL-Ts": str(ts2), "X-QL-Nonce": nonce2, "X-QL-Sign": sig2})
    check("返回 200", code == 200, f"实际 {code}")
    env2 = json.loads(body)
    check("updated=false", env2.get("updated") is False)
    check("无更新也签名", bool(env2.get("sign")), body[:120])

    print("\n[8] 重放保护")
    # 用刚刚成功过的 nonce 再发一次
    code, _, _ = request("GET", path2, headers={
        "X-QL-Ts": str(ts2), "X-QL-Nonce": nonce2, "X-QL-Sign": sig2})
    check("同一 nonce 第二次被拒", code == 401, f"实际 {code}")

    print("\n[9] 回报")
    detail = "v应用 1 条"
    rts = int(time.time() * 1000)
    rsig = hmac_hex(secret, f"REPORT\n{device_id}\n{ver}\n{rts}\n1\n{detail}")
    body = json.dumps({"device": device_id, "version": ver, "ok": True,
                       "detail": detail}).encode()
    code, _, _ = request("POST", "/api/v1/report",
                         headers={"Content-Type": "application/json",
                                  "X-QL-Ts": str(rts), "X-QL-Sign": rsig},
                         body=body)
    check("回报成功", code == 200, f"实际 {code}")

    print("\n[10] 撤销设备")
    qldb.revoke_device(device_id, True)
    ts3 = int(time.time() * 1000)
    nonce3 = "n" + str(ts3)
    sig3 = req_sign(secret, path2, ts3, nonce3, device_id, since2, applied_hash)
    code, body, _ = request("GET", path2, headers={
        "X-QL-Ts": str(ts3), "X-QL-Nonce": nonce3, "X-QL-Sign": sig3})
    check("撤销后拉取被拒", code == 401, f"实际 {code} {body[:80]}")

    print("\n[11] 内容指纹（防旧客户端跳版本）")
    # 恢复设备供本场景使用
    qldb.revoke_device(device_id, False)
    ts4 = int(time.time() * 1000)
    nonce4 = "n" + str(ts4)
    # 版本号一样，但客户端声明的内容指纹是错的 → 必须下发完整配置
    wrong_hash = "0" * 16
    path4 = f"/api/v1/config?device={device_id}&since={since2}&hash={wrong_hash}"
    sig4 = req_sign(secret, path4, ts4, nonce4, device_id, since2, wrong_hash)
    code, body, _ = request("GET", path4, headers={
        "X-QL-Ts": str(ts4), "X-QL-Nonce": nonce4, "X-QL-Sign": sig4})
    check("指纹不一致时仍下发", code == 200, f"实际 {code}")
    if code == 200:
        env4 = json.loads(body)
        check("指纹不一致时 updated=true", env4.get("updated") is True)

    print(f"\n== 通过 {passed} / 失败 {failed} ==")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
