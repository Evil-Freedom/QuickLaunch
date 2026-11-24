"""ql-mailer 进程入口：一个 HTTP 接入线程 + 一个发送调度线程。

不做守护化 —— 交给 systemd（Restart=always、日志进 journal 与文件）。
收到 SIGTERM 时先停调度、再关库，保证「正在发的」不会被硬砍（它会被 recover_stale 兜住）。
"""

from __future__ import annotations

import logging
import signal
import subprocess
import sys
import threading
from dataclasses import replace

from .actions import ActionStore
from .alerts import AlertStore
from .agent_mail import AgentMailClient
from .api import build_server
from .config import Config
from .dispatcher import Dispatcher
from .repository import Repository


def _setup_logging(path: str) -> None:
    handlers: list[logging.Handler] = [logging.StreamHandler(sys.stdout)]
    try:
        handlers.append(logging.FileHandler(path, encoding="utf-8"))
    except OSError:
        pass
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
        handlers=handlers,
    )


def resolve_bind_host(host: str) -> str:
    """QLMAIL_BIND=auto 时取 quicklaunch 的 docker 网桥网关。

    绑网关而不是 0.0.0.0：Caddy 容器经网关访问得到，公网侧无法直连，
    等于不新开管理端口，也不需要改安全组。

    网关由 root 的 `qlmail-net.service` 在启动时写进 /run/qlmail/bind —— 服务本身
    以 qlmair 运行，没有 docker 组权限，也不该为了取一个 IP 去要这个权限。
    """
    if host != "auto":
        return host
    log = logging.getLogger("qlmail.main")
    try:
        with open("/run/qlmail/bind", encoding="utf-8") as f:
            cached = f.read().strip()
        if cached:
            return cached
    except OSError:
        pass
    try:
        out = subprocess.run(
            ["docker", "network", "inspect", "quicklaunch-vps_ql",
             "-f", "{{(index .IPAM.Config 0).Gateway}}"],
            capture_output=True, text=True, timeout=5,
        )
        gateway = out.stdout.strip()
        if out.returncode == 0 and gateway:
            return gateway
    except Exception:
        pass
    log.warning("取不到 docker 网桥网关，退回 127.0.0.1")
    return "127.0.0.1"


def main() -> int:
    cfg = Config.from_env()
    _setup_logging(cfg.log_path)
    log = logging.getLogger("qlmail.main")
    cfg = replace(cfg, bind_host=resolve_bind_host(cfg.bind_host))

    if not cfg.ingest_token:
        log.error("QLMAIL_INGEST_TOKEN 未配置，拒绝启动（否则任何人都能往队列里塞通知）")
        return 2

    repo = Repository(cfg.db_path)
    store = ActionStore(cfg.db_path)
    alerts = AlertStore(cfg.db_path)
    client = AgentMailClient(cfg.cli_path, cfg.cli_config_dir, cfg.cli_home, cfg.send_timeout_s)
    dispatcher = Dispatcher(cfg, repo, client, on_sent=alerts.on_sent)

    me = client.whoami()
    if me.exit_code != 0:
        log.error("启动自检失败：agently-cli +me 退出码 %s（授权可能失效）", me.exit_code)
    else:
        aliases = me.json().get("data", {}).get("aliases", [])
        email = aliases[0].get("email") if aliases else "?"
        log.info("发件身份：%s（授权正常）", email)

    server = build_server(cfg, repo, store, alerts, dispatcher)
    dispatcher.start()
    log.info("ql-mailer 监听 %s:%s，DB=%s", cfg.bind_host, cfg.port, cfg.db_path)

    stop = threading.Event()

    def _stop(signum, _frame):
        log.info("收到信号 %s，准备退出", signum)
        stop.set()

    signal.signal(signal.SIGTERM, _stop)
    signal.signal(signal.SIGINT, _stop)

    thread = threading.Thread(target=server.serve_forever, name="http", daemon=True)
    thread.start()
    stop.wait()

    server.shutdown()
    dispatcher.stop()
    repo.close()
    store.close()
    alerts.close_conn()
    log.info("已退出")
    return 0


if __name__ == "__main__":
    sys.exit(main())
