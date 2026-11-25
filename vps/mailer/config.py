"""ql-mailer 的配置。

全部来自 systemd 的 EnvironmentFile（/etc/qlmail/mailer.env），默认值只用于本地测试。
收件人是**固定白名单**：只从环境读，绝不接受请求里传来的地址。
"""

from __future__ import annotations

import os
from dataclasses import dataclass


def _env(name: str, default: str = "") -> str:
    return os.environ.get(name, default).strip()


def _env_int(name: str, default: int) -> int:
    raw = _env(name)
    return int(raw) if raw else default


@dataclass(frozen=True)
class Config:
    db_path: str
    cli_path: str
    cli_config_dir: str
    cli_home: str
    ingest_token: str
    bind_host: str
    port: int
    primary: str
    fallback: str
    device_whitelist: tuple[str, ...]
    success_mode: str            # each | off
    send_timeout_s: int
    daily_quota: int
    hourly_limit: int
    minute_limit: int
    dry_run: bool
    log_path: str
    verifier_token: str = ""
    enabled_types: tuple[str, ...] = ()
    action_ttl_s: int = 1800

    @staticmethod
    def from_env() -> "Config":
        primary = _env("QLMAIL_PRIMARY")
        fallback = _env("QLMAIL_FALLBACK")
        if not primary or not fallback:
            raise RuntimeError("QLMAIL_PRIMARY / QLMAIL_FALLBACK 必须配置")
        if primary.lower() == fallback.lower():
            raise RuntimeError("主备收件人不能相同，否则故障切换没有意义")
        return Config(
            db_path=_env("QLMAIL_DB", "/var/lib/qlmail/notifications.db"),
            cli_path=_env("QLMAIL_CLI", "/usr/local/bin/agently-cli"),
            cli_config_dir=_env("QLMAIL_CONFIG_DIR", "/var/lib/qlmail/config"),
            cli_home=_env("QLMAIL_HOME", "/var/lib/qlmail"),
            ingest_token=_env("QLMAIL_INGEST_TOKEN"),
            verifier_token=_env("QLMAIL_VERIFIER_TOKEN"),
            enabled_types=tuple(t.strip() for t in _env("QLMAIL_TYPES").split(",") if t.strip()),
            action_ttl_s=_env_int("QLMAIL_ACTION_TTL", 1800),
            bind_host=_env("QLMAIL_BIND", "127.0.0.1"),
            port=_env_int("QLMAIL_PORT", 8780),
            primary=primary,
            fallback=fallback,
            device_whitelist=tuple(
                d.strip() for d in _env("QLMAIL_DEVICES").split(",") if d.strip()
            ),
            success_mode=_env("QLMAIL_SUCCESS_MODE", "each"),
            send_timeout_s=_env_int("QLMAIL_SEND_TIMEOUT", 45),
            daily_quota=_env_int("QLMAIL_DAILY_QUOTA", 50),
            hourly_limit=_env_int("QLMAIL_HOURLY_LIMIT", 200),
            minute_limit=_env_int("QLMAIL_MINUTE_LIMIT", 10),
            dry_run=_env("QLMAIL_DRY_RUN", "0") == "1",
            log_path=_env("QLMAIL_LOG", "/var/log/qlmail/mailer.log"),
        )
