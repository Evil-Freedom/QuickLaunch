"""CLI 退出码 → 处理策略。

官方 CLI 的错误码语义（用户给定 + `agently-cli` 帮助文本核对）：

| code | 含义 | 处理 |
|---|---|---|
| 0 | 成功 | 记 PRIMARY_SENT / FALLBACK_SENT |
| 1 | 服务端错误或网络异常 | 退避重试，最多 2 次 |
| 2 | 参数错误 | 停止，标记 FAILED（需要人改配置） |
| 3 | 授权失效 | 停止发送，标记 AUTH_REQUIRED，等重新授权 |
| 4 | 本地网络错误 | 退避重试，最多 2 次 |
| 6 | 业务永久拒绝 | 收件人相关 → 切换备用邮箱；否则 FAILED |
| 7 | 限频 | 按 Retry-After（无则 60s）等待 |
| 8 | 缺确认令牌 | 我们用 --confirmed，出现即视为 UNKNOWN 待核实 |

超时 / 被杀 单独归为 `unknown`：**不能**因此认定「没发出去」。
"""

from __future__ import annotations

from dataclasses import dataclass

KIND_SENT = "sent"
KIND_RETRYABLE = "retryable"
KIND_RECIPIENT = "recipient_rejected"
KIND_PERMANENT = "permanent"
KIND_RATE_LIMITED = "rate_limited"
KIND_AUTH = "auth_required"
KIND_UNKNOWN = "unknown"

MAX_TRANSIENT_RETRIES = 2

# 收件人层面的拒绝特征（命中即允许切换到备用邮箱）。
# 只认「明确指向地址」的证据，不把泛泛的 550 当成收件人问题。
RECIPIENT_HINTS = (
    "recipient address rejected",
    "user unknown",
    "no such user",
    "mailbox not found",
    "does not exist",
    "invalid recipient",
    "recipient not found",
    "address rejected",
)


@dataclass(frozen=True)
class Outcome:
    kind: str
    retry_after_s: int = 0
    reason: str = ""


def is_recipient_rejection(stderr: str) -> bool:
    low = (stderr or "").lower()
    return any(h in low for h in RECIPIENT_HINTS)


def classify(exit_code: int, stderr: str = "", timed_out: bool = False) -> Outcome:
    if timed_out:
        return Outcome(KIND_UNKNOWN, reason="send timed out")

    if exit_code == 0:
        return Outcome(KIND_SENT)

    if exit_code == 1:
        # 实测：收件人语法非法时 CLI 走的是**客户端校验**，退出码 1 且 stderr 带
        # `--to must be a valid email address`。这不是网络抖动，重试多少次都一样，
        # 必须归入收件人问题才能触发切换备用邮箱。
        if "valid email address" in (stderr or "").lower() or "invalid email" in (stderr or "").lower():
            return Outcome(KIND_RECIPIENT, reason="recipient address is malformed (exit 1)")
        return Outcome(KIND_RETRYABLE, reason="server/network error (exit 1)")

    if exit_code == 2:
        return Outcome(KIND_PERMANENT, reason="invalid arguments (exit 2)")

    if exit_code == 3:
        return Outcome(KIND_AUTH, reason="authorization expired (exit 3)")

    if exit_code == 4:
        return Outcome(KIND_RETRYABLE, reason="local network error (exit 4)")

    if exit_code == 6:
        if is_recipient_rejection(stderr):
            return Outcome(KIND_RECIPIENT, reason="recipient rejected (exit 6)")
        return Outcome(KIND_PERMANENT, reason="business rejection, not recipient-related (exit 6)")

    if exit_code == 7:
        return Outcome(KIND_RATE_LIMITED, retry_after_s=60, reason="rate limited (exit 7)")

    if exit_code == 8:
        return Outcome(KIND_UNKNOWN, reason="confirmation token missing (exit 8)")

    return Outcome(KIND_PERMANENT, reason=f"unexpected exit code {exit_code}")


def backoff_seconds(retry_count: int) -> int:
    """1/4 类错误的退避：30s、120s，之后不再自动重试。"""
    return [30, 120][min(max(retry_count, 0), 1)]
