"""发送调度：主邮箱优先、明确失败才切备用、结果未知先回查。

一次 tick 只处理一条，好处是：
* 串行发送天然满足「禁止并行发送」；
* 出问题时日志与数据库状态一一对应，便于复盘；
* 配额计数简单可靠（单线程）。
"""

from __future__ import annotations

import logging
import threading
import time
from datetime import datetime, timedelta, timezone

from . import classify as C
from . import repository as R
from .agent_mail import AgentMailClient
from .config import Config

log = logging.getLogger("qlmail.dispatcher")


class Dispatcher:
    def __init__(self, cfg: Config, repo: R.Repository, client: AgentMailClient,
                 on_sent=None):
        self.cfg = cfg
        self.repo = repo
        self.client = client
        # 邮件真正发出后的回调（用于推进「待确认提醒」时间，发信失败不推进）
        self.on_sent = on_sent
        self._stop = threading.Event()
        self._paused = threading.Event()  # 授权失效时暂停，等人工重新授权
        self._thread: threading.Thread | None = None

    # ── 生命周期 ────────────────────────────────────────────────────

    def start(self) -> None:
        recovered = self.repo.recover_stale()
        if recovered:
            log.warning("启动时发现 %d 条中断的发送，已置为 UNKNOWN 待回查：%s",
                        len(recovered), ", ".join(recovered[:5]))
        self._thread = threading.Thread(target=self._loop, name="dispatcher", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        if self._thread:
            self._thread.join(timeout=10)

    def resume_after_reauth(self) -> int:
        n = self.repo.resume_auth_required()
        self._paused.clear()
        log.info("授权恢复：%d 条 AUTH_REQUIRED 重新入队", n)
        return n

    @property
    def paused(self) -> bool:
        return self._paused.is_set()

    def _loop(self) -> None:
        while not self._stop.is_set():
            try:
                if self._paused.is_set():
                    self._stop.wait(30)
                    continue
                row = self.repo.claim_next()
                if row is None:
                    self._stop.wait(2)
                    continue
                self._handle(row)
            except Exception:  # 单条失败不能把循环打死
                log.exception("调度循环异常，2s 后继续")
                self._stop.wait(2)

    # ── 单条处理 ────────────────────────────────────────────────────

    def _handle(self, row: R.Notification) -> None:
        # 1) 上一次结果未知：先回查已发送箱，确认已发出就不再重发（否则用户会收到两封）
        if row.claimed_from == R.UNKNOWN:
            found = self.client.resolve_message_id(row.subject, row.current_recipient, row.created_at)
            if found:
                log.info("回查确认已发送 event=%s message_id=%s", row.event_id, found)
                self.repo.mark_sent(row.event_id, found)
                return

        # 2) 配额守卫：超限就延后，不硬发
        delay = self._quota_delay_s()
        if delay > 0:
            log.warning("配额不足，event=%s 延后 %ss", row.event_id, delay)
            self.repo.requeue(row.event_id, delay, None, "quota guard")
            return

        result = self.client.send(row.current_recipient, row.subject, row.body)
        outcome = C.classify(result.exit_code, result.stderr, result.timed_out)
        log.info("发送 event=%s to=%s exit=%s kind=%s tries=%d",
                 row.event_id, row.current_recipient, result.exit_code, outcome.kind, row.attempts)

        if outcome.kind == C.KIND_SENT:
            message_id = None
            try:
                message_id = self.client.resolve_message_id(
                    row.subject, row.current_recipient, row.created_at
                )
            except Exception:  # 回查失败不影响「已发送」这个结论
                log.warning("已发送但回查 message_id 失败 event=%s", row.event_id)
            self.repo.mark_sent(row.event_id, message_id)
            if self.on_sent is not None:
                try:
                    self.on_sent(row.event_id)
                except Exception:
                    log.exception("on_sent 回调失败 event=%s（不影响发送结果）", row.event_id)
            return

        if outcome.kind == C.KIND_UNKNOWN:
            # 结果未知：留 UNKNOWN，等 reconcile 再判，不切备用
            self.repo.mark_unknown(row.event_id, result.exit_code, outcome.reason)
            return

        if outcome.kind == C.KIND_RECIPIENT:
            log.warning("收件人被拒 event=%s to=%s", row.event_id, row.current_recipient)
            self.repo.mark_recipient_rejected(
                row.event_id, result.exit_code, (result.stderr or outcome.reason)
            )
            return

        if outcome.kind == C.KIND_RATE_LIMITED:
            self.repo.requeue(row.event_id, outcome.retry_after_s, result.exit_code, outcome.reason)
            return

        if outcome.kind == C.KIND_AUTH:
            self.repo.mark_auth_required(row.event_id, result.exit_code, result.stderr or outcome.reason)
            self._paused.set()
            log.error("授权失效，暂停发送（重新授权后重启服务即可恢复）")
            return

        if outcome.kind == C.KIND_RETRYABLE:
            if row.retry_count >= C.MAX_TRANSIENT_RETRIES:
                self.repo.mark_failed(row.event_id, result.exit_code, "重试耗尽：" + (result.stderr or outcome.reason))
            else:
                self.repo.requeue(row.event_id, C.backoff_seconds(row.retry_count),
                                  result.exit_code, outcome.reason)
            return

        # 其余一律终态失败，保留正文供人工补发
        self.repo.mark_failed(row.event_id, result.exit_code, result.stderr or outcome.reason)

    # ── 配额 ────────────────────────────────────────────────────────

    def _quota_delay_s(self) -> int:
        now = datetime.now(timezone.utc)
        day_start = now.replace(hour=0, minute=0, second=0, microsecond=0).isoformat()
        hour_start = (now - timedelta(hours=1)).replace(microsecond=0).isoformat()
        minute_start = (now - timedelta(minutes=1)).replace(microsecond=0).isoformat()

        if self.repo.sent_since(day_start) >= self.cfg.daily_quota:
            tomorrow = (now.replace(hour=0, minute=0, second=0, microsecond=0)
                        + timedelta(days=1, minutes=5))
            return max(60, int((tomorrow - now).total_seconds()))
        if self.repo.sent_since(hour_start) >= self.cfg.hourly_limit:
            return 300
        if self.repo.sent_since(minute_start) >= self.cfg.minute_limit:
            return 20
        return 0

    # ── 供测试直接驱动 ──────────────────────────────────────────────

    def run_once(self) -> bool:
        row = self.repo.claim_next()
        if row is None:
            return False
        self._handle(row)
        return True

    def has_pending(self) -> bool:
        """测试用：队列里是否还有到期任务（只读，不领取）。"""
        return self.repo.has_due()
