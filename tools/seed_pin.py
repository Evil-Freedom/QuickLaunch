#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把锁屏 PIN 注入已安装 App 的 SharedPreferences（真机验收工具，不参与打包）。

为什么需要：`adb uninstall` / 换签名 / 清数据都会清掉 App 私有目录，锁屏自动解锁的
PIN 随之丢失，每次重装都要手动进设置页重填一遍才能继续验收。本脚本在安装之后
直接把 PIN 写进 prefs，做到「装完就能测」。

写入格式：`x1:`（XOR + Base64），与 `PinVault.open` 支持的降级形态逐字节一致，
因此 App 读到的是同一个密码。`ks1:`（AndroidKeyStore AES-GCM）需要 App 进程内的
私钥才能生成，脚本无法伪造 —— 已存在 `ks1:` 时默认**跳过**，不会把加密存储降级；
确需覆盖用 `--force`（覆盖后 App 界面的密码一栏会显示「降级存储」，属预期）。

安全边界：
- PIN 默认从 `local.properties` 的 `ql.debug.pin` 读取，该文件在 .gitignore 里；
  命令行 `--pin` 只是应急通道，会留在 shell 历史与进程列表里。
- 本脚本全程不回显 PIN，只打印位数。
- 需要 root（写 /data/data/<pkg> 并修 uid/SELinux 上下文）。

用法：
  python tools/seed_pin.py --status                 # 只看现状，不写
  python tools/seed_pin.py                          # 缺 PIN 才注入
  python tools/seed_pin.py --enable --clean         # 注入 + 开开关 + 清零失败计数
  python tools/seed_pin.py --force --clean          # 覆盖已有 PIN（会降级为 x1）
"""

import argparse
import base64
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

PKG = "com.workbuddy.quicklaunch"
PREFS = "/data/data/%s/shared_prefs/quicklaunch.xml" % PKG
REMOTE_TMP = "/data/local/tmp/ql_seed_prefs.xml"

# 与 PinVault.MASK 必须逐字一致，否则 App 解出来是另一个密码。
MASK = b"ql-pin-obfuscate-2026"

KEY_PIN = "auto_unlock_pin_v1"
KEY_ENABLED = "auto_unlock_enabled"
KEY_FAILURES = "auto_unlock_failures"
KEY_AUTO_DISABLED = "auto_unlock_auto_disabled"
KEY_DISABLED_REASON = "auto_unlock_auto_disabled_reason"

PREFIX_KS = "ks1:"
PREFIX_XOR = "x1:"

EMPTY_MAP = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n</map>\n"

ROOT_DIR = Path(__file__).resolve().parent.parent
LOCAL_PROPS = ROOT_DIR / "local.properties"


# ── 编解码（与 PinVault 对齐） ────────────────────────────────────


def encode_pin(pin: str) -> str:
    """明文 → `x1:` blob。"""
    raw = pin.encode("utf-8")
    mixed = bytes(b ^ MASK[i % len(MASK)] for i, b in enumerate(raw))
    return PREFIX_XOR + base64.b64encode(mixed).decode("ascii")


def decode_pin(blob: str):
    """`x1:` blob → 明文；其他形态（含 ks1）返回 None。"""
    if not blob.startswith(PREFIX_XOR):
        return None
    raw = base64.b64decode(blob[len(PREFIX_XOR):])
    return bytes(b ^ MASK[i % len(MASK)] for i, b in enumerate(raw)).decode("utf-8")


def storage_kind(blob):
    if not blob:
        return "NONE"
    if blob.startswith(PREFIX_KS):
        return "KEYSTORE"
    if blob.startswith(PREFIX_XOR):
        return "OBFUSCATED"
    return "NONE"


# ── adb 封装 ──────────────────────────────────────────────────────


class Adb:
    def __init__(self, serial):
        self.serial = serial

    def _base(self):
        return ["adb"] + (["-s", self.serial] if self.serial else [])

    def shell(self, cmd, su=False, binary=False):
        args = self._base() + ["exec-out" if binary else "shell"]
        # su 的命令必须作为整体是一个 argv：否则 adb 会用空格重拼，su 只拿到第一个词
        args.append("su -c '%s'" % cmd if su else cmd)
        proc = subprocess.run(args, capture_output=True)
        if proc.returncode != 0:
            err = proc.stderr.decode("utf-8", "replace").strip()
            raise RuntimeError("adb 失败(%d): %s\n%s" % (proc.returncode, cmd, err))
        return proc.stdout if binary else proc.stdout.decode("utf-8", "replace")

    def push(self, local, remote):
        proc = subprocess.run(self._base() + ["push", local, remote], capture_output=True)
        if proc.returncode != 0:
            raise RuntimeError("adb push 失败: %s" % proc.stderr.decode("utf-8", "replace"))

    def try_shell(self, cmd, su=True):
        try:
            return self.shell(cmd, su=su)
        except RuntimeError:
            return None


def detect_serial():
    proc = subprocess.run(["adb", "devices"], capture_output=True)
    if proc.returncode != 0:
        raise RuntimeError("adb 不可用，请确认 platform-tools 在 PATH 上")
    serials = []
    for line in proc.stdout.decode("utf-8", "replace").splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            serials.append(parts[0])
    if len(serials) == 1:
        return None  # 让 adb 自己选，省一次 -s
    if not serials:
        raise RuntimeError("没有已连接的设备")
    raise RuntimeError("检测到多台设备，请用 --serial 指定：%s" % ", ".join(serials))


def read_props():
    props = {}
    if LOCAL_PROPS.exists():
        for line in LOCAL_PROPS.read_text(encoding="utf-8", errors="replace").splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            props[k.strip()] = v.strip()
    return props


# ── prefs XML 编辑 ────────────────────────────────────────────────


def _escape(s: str) -> str:
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def _insert(xml, element):
    idx = xml.rfind("</map>")
    if idx < 0:
        raise RuntimeError("prefs 内容不是预期的 <map> 结构")
    return xml[:idx] + element + "\n" + xml[idx:]


def set_string(xml, name, value):
    pat = re.compile(r'(<string name="%s">)(.*?)(</string>)' % re.escape(name), re.S)
    if pat.search(xml):
        return pat.sub(lambda m: m.group(1) + _escape(value) + m.group(3), xml, count=1)
    return _insert(xml, '    <string name="%s">%s</string>' % (name, _escape(value)))


def set_scalar(xml, name, value, kind=None):
    """<int/> <boolean/> 这类自闭合元素。kind 为空时沿用原元素的类型。"""
    pat = re.compile(r'<(\w+) name="%s" value="[^"]*" />' % re.escape(name))
    m = pat.search(xml)
    if m:
        k = kind or m.group(1)
        return pat.sub('<%s name="%s" value="%s" />' % (k, name, value), xml, count=1)
    if not kind:
        raise RuntimeError("prefs 中找不到 %s，且未给出类型" % name)
    return _insert(xml, '    <%s name="%s" value="%s" />' % (kind, name, value))


def get_value(xml, name):
    """取元素的字符串值（自闭合取 value 属性），用于状态展示。"""
    m = re.search(r'<string name="%s">(.*?)</string>' % re.escape(name), xml, re.S)
    if m:
        return m.group(1)
    m = re.search(r'<\w+ name="%s" value="([^"]*)" />' % re.escape(name), xml)
    return m.group(1) if m else None


# ── 主流程 ────────────────────────────────────────────────────────


def app_uid(adb):
    # 数据目录的 owner 就是 app uid；`dumpsys package` 在 root 域下会被 SELinux 挡住，
    # 而且在 Android 16 上不一定带 userId= 字段，所以 stat 才是可靠来源。
    out = adb.try_shell("stat -c %%u /data/data/%s" % PKG)
    if out and out.strip().isdigit():
        return out.strip()
    out = adb.try_shell("ls -ldn /data/data/%s" % PKG)
    if out:
        m = re.search(r"^\S+\s+\d+\s+(\d+)", out)
        if m:
            return m.group(1)
    raise RuntimeError("读不到 %s 的 uid（App 是否已安装？）" % PKG)


def read_prefs(adb):
    out = adb.try_shell("cat %s" % PREFS, su=True)
    if out is None:
        return None
    return out or None


def write_prefs(adb, xml):
    fd, local = tempfile.mkstemp(prefix="ql_prefs_", suffix=".xml")
    try:
        with os.fdopen(fd, "wb") as f:
            f.write(xml.encode("utf-8"))
        adb.push(local, REMOTE_TMP)
    finally:
        try:
            os.unlink(local)
        except OSError:
            pass
    uid = app_uid(adb)
    adb.shell(
        "cp %s %s && chown %s:%s %s && chmod 660 %s && restorecon %s"
        % (REMOTE_TMP, PREFS, uid, uid, PREFS, PREFS, PREFS),
        su=True,
    )
    adb.try_shell("rm -f %s" % REMOTE_TMP)


def print_status(adb, xml):
    if xml is None:
        print("prefs: 不存在（App 从未启动或数据已清）")
        print("PIN  : 未设置")
        return
    blob = get_value(xml, KEY_PIN)
    kind = storage_kind(blob)
    label = {
        "NONE": "未设置",
        "KEYSTORE": "已设置（AndroidKeyStore 加密）",
        "OBFUSCATED": "已设置（x1 混淆，脚本注入或 KeyStore 不可用）",
    }[kind]
    print("PIN  : %s" % label)
    if kind == "OBFUSCATED":
        plain = decode_pin(blob)
        print("位数 : %d" % len(plain or ""))
    print(
        "开关 : enabled=%s 失败=%s/%s 安全停用=%s"
        % (
            get_value(xml, KEY_ENABLED),
            get_value(xml, KEY_FAILURES),
            "2",
            get_value(xml, KEY_AUTO_DISABLED),
        )
    )


def main(argv):
    ap = argparse.ArgumentParser(description="把锁屏 PIN 注入 QuickLaunch 的 prefs")
    ap.add_argument("--pin", help="PIN 明文（默认取 local.properties 的 ql.debug.pin）")
    ap.add_argument("--serial", help="adb 序列号（默认取 local.properties 或唯一在线设备）")
    ap.add_argument("--status", action="store_true", help="只展示当前状态")
    ap.add_argument("--force", action="store_true", help="覆盖已有 PIN（含 ks1 加密态）")
    ap.add_argument("--enable", action="store_true", help="同时把自动解锁开关打开")
    ap.add_argument("--clean", action="store_true", help="清零失败计数、解除安全停用")
    ap.add_argument("--no-start", action="store_true", help="写完后不拉起 App")
    args = ap.parse_args(argv)

    props = read_props()
    pin = args.pin or props.get("ql.debug.pin")
    serial = args.serial or props.get("ql.device.serial") or detect_serial()
    adb = Adb(serial)

    xml = read_prefs(adb)

    if args.status:
        print_status(adb, xml)
        return 0

    if not pin:
        print("未提供 PIN：local.properties 缺 ql.debug.pin，也没有 --pin", file=sys.stderr)
        return 2
    if not (pin.isdigit() and 4 <= len(pin) <= 16):
        print("PIN 必须是 4~16 位数字（AutoUnlockDecision.validPin 的限制）", file=sys.stderr)
        return 2

    existed = xml is not None
    xml = xml or EMPTY_MAP
    current = get_value(xml, KEY_PIN)
    kind = storage_kind(current)

    if kind == "KEYSTORE" and not args.force:
        print("已存在 keystore 加密的 PIN，跳过（--force 可覆盖，但会降级为 x1）")
    else:
        adb.try_shell("am force-stop %s" % PKG)
        xml = set_string(xml, KEY_PIN, encode_pin(pin))
        if args.enable:
            xml = set_scalar(xml, KEY_ENABLED, "true", "boolean")
        if args.clean:
            xml = set_scalar(xml, KEY_FAILURES, "0", "int")
            xml = set_scalar(xml, KEY_AUTO_DISABLED, "false", "boolean")
            xml = set_string(xml, KEY_DISABLED_REASON, "")
        write_prefs(adb, xml)

        # 自检：读回来必须能解出同一个 PIN，否则宁可不留半截状态
        back = read_prefs(adb)
        got = decode_pin(get_value(back or "", KEY_PIN) or "")
        if got != pin:
            print("自检失败：写入后读回不一致", file=sys.stderr)
            return 1
        print("已写入 PIN（%d 位，不回显）%s" % (len(pin), "" if existed else "，并新建 prefs"))

    if not args.no_start:
        # 安装/force-stop 会把 App 置于 stopped 且清掉闹钟，拉起一次恢复调度
        adb.try_shell("am start -n %s/.MainActivity" % PKG, su=True)
    print_status(adb, read_prefs(adb))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
