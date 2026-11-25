"""对 `agently-cli` 的薄封装：只做「调一次、判结果、必要时回查」。

不在这里做重试与状态机 —— 那是 [dispatcher] 的事，客户端保持无状态，
方便单测替换成假 CLI（`tests/fake_cli.py`）。
"""

from __future__ import annotations

import json
import os
import signal
import subprocess
from dataclasses import dataclass


@dataclass
class CliResult:
    exit_code: int
    stdout: str
    stderr: str
    timed_out: bool = False

    def json(self) -> dict:
        try:
            return json.loads(self.stdout or "{}")
        except json.JSONDecodeError:
            return {}


class AgentMailClient:
    def __init__(self, cli: str | list[str], config_dir: str, home: str, timeout_s: int = 45):
        # 接受命令前缀列表，单测里可以换成 [sys.executable, fake_cli.py]，不必造包装脚本
        self.cli = [cli] if isinstance(cli, str) else list(cli)
        self.config_dir = config_dir
        self.home = home
        self.timeout_s = timeout_s

    # ── 内部 ────────────────────────────────────────────────────────

    def _env(self) -> dict[str, str]:
        # 继承 systemd 注入的环境（单测靠它传 FAKE_* 给假 CLI），再覆盖必须固定的项。
        # 显式剔除 AGENTLY_CLI_DEBUG_HTTP：它会把请求头/正文打进日志，等于泄漏邮件内容。
        env = dict(os.environ)
        env.update({
            "PATH": "/usr/local/bin:/usr/bin:/bin",
            "HOME": self.home,
            "AGENTLY_CLI_CONFIG_DIR": self.config_dir,
            # 遥测与更新提示在服务里只会添乱
            "AGENTLY_DISABLE_TELEMETRY": "1",
            "AGENTLY_CLI_NO_UPDATE_NOTIFIER": "1",
            "LANG": "C.UTF-8",
        })
        env.pop("AGENTLY_CLI_DEBUG_HTTP", None)
        return env

    def _run(self, args: list[str]) -> CliResult:
        try:
            proc = subprocess.Popen(
                [*self.cli, *args],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                env=self._env(),
                start_new_session=True,  # 超时能连子进程一起杀干净
                cwd=self.home,
            )
        except FileNotFoundError as exc:
            return CliResult(2, "", f"cli not found: {exc}")

        try:
            out, err = proc.communicate(timeout=self.timeout_s)
            return CliResult(
                proc.returncode,
                out.decode("utf-8", "replace"),
                err.decode("utf-8", "replace"),
            )
        except subprocess.TimeoutExpired:
            # 超时 = 结果未知，绝不等于「没发出去」
            try:
                if hasattr(os, "killpg"):
                    os.killpg(proc.pid, signal.SIGKILL)
                else:  # Windows 单测环境没有进程组
                    proc.kill()
            except ProcessLookupError:
                pass
            out, err = proc.communicate()
            return CliResult(-1, out.decode("utf-8", "replace"),
                             err.decode("utf-8", "replace"), timed_out=True)

    # ── 对外 ────────────────────────────────────────────────────────

    def send(self, to: str, subject: str, body: str) -> CliResult:
        return self._run(
            [
                "message", "+send",
                "--to", to,
                "--subject", subject,
                "--body", body,
                "--body-format", "html",
                "--confirmed",          # 服务里没有第二个人点确认
            ]
        )

    def whoami(self) -> CliResult:
        return self._run(["+me"])

    def auth_status(self) -> CliResult:
        return self._run(["auth", "status"])

    def resolve_message_id(self, subject: str, to: str, since_iso: str) -> str | None:
        """发送结果未知时回查已发送箱。

        匹配口径：同一主题、同一收件人、且创建时间不早于 since_iso。
        找不到就返回 None（调用方应保持 UNKNOWN，而不是当作失败重发）。
        """
        res = self._run(["message", "+list", "--dir", "sent", "--limit", "20"])
        if res.exit_code != 0:
            return None
        items = res.json().get("data", {}).get("data", []) or []
        lowered = to.lower()
        since = _parse_iso(since_iso)
        for item in items:
            if (item.get("subject") or "").strip() != subject:
                continue
            recipients = {str(r.get("email", "")).lower() for r in item.get("to", []) or []}
            if lowered not in recipients:
                continue
            created = _parse_iso(str(item.get("created_at") or ""))
            if since and created and created < since:
                continue
            return item.get("message_id")
        return None


def _parse_iso(value: str):
    """解析 CLI 返回的 2026-11-23T14:50:43Z 这类时间；解析不了返回 None（不因此过滤掉）。"""
    from datetime import datetime

    if not value:
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
