# 国内 VPS 远程规则管理（v1.4.0）

> 目标：在自己的阿里云上海 VPS（2 核 2GB）上跑一个只服务自己手机的配置服务。
> 手机**主动出站拉取**，服务器不需要任何入站端口连到手机，
> 也不保存任何能反向控制手机的东西（没有 Shell、不能改要启动哪个 App）。

---

## 1. 拓扑

```
        ┌──────────────┐   ①改规则/发布版本   ┌─────────────────┐
        │ 你的电脑/手机 │ ───────────────────► │  VPS (Docker)   │
        └──────────────┘      HTTPS 管理界面   │  FastAPI+SQLite │
                                              └────────┬────────┘
                                                       │ ②返回签名配置
                                      ③手机定期主动拉取 │
                                                       ▼
                                              ┌─────────────────┐
                                              │ Razr 40 Ultra   │
                                              │ 验证签名→应用→  │
                                              │ 回报结果        │
                                              └─────────────────┘
```

服务器离线时，手机继续用本地最后一次生效的配置 —— 因为配置本来就在手机本地，
「同步失败」的实现方式是**什么都不做**，不是回滚到某个缓存副本。

---

## 2. 部署（一次性，约 10 分钟）

### 2.1 前置条件

- 域名 A 记录指向 VPS 公网 IP（Caddy 需要它来签证书）
- 阿里云安全组放行 **80 与 443**
- VPS 已装 Docker 与 Docker Compose 插件

```bash
# Debian 12
sudo apt update && sudo apt install -y docker.io docker-compose-plugin
sudo systemctl enable --now docker
```

### 2.2 DuckDNS 核查（部署前在 VPS 上执行）

```bash
dig +short qlhub.duckdns.org @8.8.8.8   # 应返回 VPS 公网 IP
curl -s ifconfig.me; echo                # 与上一行一致 → 固定 IP，无需 DDNS 更新
```

IP 若会变化，加 cron 自动更新（Token 存 `/opt/quicklaunch/.secrets/duckdns.env`，权限 600，
**不进任何仓库**）：`*/5 * * * * curl -s "https://www.duckdns.org/update?domains=qlhub&token=$TOKEN&ip=" >/dev/null`

### 2.3 上传并配置

```bash
# 在本地
scp -r QuickLaunch/vps root@<你的VPS>:/opt/quicklaunch-vps

# 在 VPS 上
cd /opt/quicklaunch-vps
cp .env.example .env
```

编辑 `.env`：

| 变量 | 填什么 |
| --- | --- |
| `QL_DOMAIN` | `qlhub.duckdns.org`（唯一固定域名，禁止更换） |
| `QL_ADMIN_PASSWORD_SHA256` | `printf '%s' '你的口令' \| sha256sum \| cut -d' ' -f1` |
| `QL_SESSION_SECRET` | `openssl rand -hex 32` |

### 2.4 启动

```bash
docker compose up -d
docker compose logs -f caddy    # 等日志出现 certificate obtained
curl https://qlhub.duckdns.org/api/v1/ping
# {"ok":true,"version":0}
```

首次启动会自动建库、写入默认重试策略（与 Android 端默认值逐项对齐）。

---

## 3. 首次使用

### 3.1 生成设备凭据

1. 浏览器打开 `https://qlhub.duckdns.org/devices`
2. 输入管理员口令登录
3. 填设备名 → 点「生成设备凭据」
4. 页面会显示 **设备 ID** 与 **密钥**（密钥只显示这一次）

### 3.2 填到手机

手机 → 同步页 → 「远程访问」→ 「国内服务器规则（VPS）」：

| 字段 | 填什么 |
| --- | --- |
| 启用 VPS 同步 | 打开 |
| 服务器地址 | `https://<你的域名>` |
| 设备 ID | 上一步的 ID |
| 设备密钥 | 上一步的密钥 |
| 同步间隔 | 600（秒） |

点「保存并立即同步」。

### 3.3 让服务器认识你的规则

服务器默认没有任何规则 —— 它只知道「手机上报过哪些规则 ID」才能下发修改。
首次使用有两种做法：

**做法 A：手动建规则（推荐，简单）**

在服务器的「规则」页没有新增入口，需要先在服务器侧写入规则定义。
用一条命令把手机当前的规则同步过去（在 VPS 上执行）：

```bash
# 1) 先在手机端确认规则 ID：App 内「启动」页的规则卡片没有显示 ID，
#    但可以从 VPS 的「设备」页看到手机回报的结果明细
# 2) 直接在容器里补一条规则（示例：id=2 每工作日 08:30）
docker compose exec api python -c "
from app import db
db.upsert_rule(2, {'timeHour': 8, 'timeMinute': 30, 'repeatMode': 'weekdays',
                   'skipHolidays': False, 'enabled': True})
print('已发布版本', db.publish('初始化规则'))
"
```

**做法 B：先让手机拉一次，再看服务器日志确认 ID**

手机的同步日志（`adb logcat -s QL-VpsSync`）会打印 `vN 应用 M 条`，
配合服务器「设备」页的明细可以看出哪些 ID 生效了。

> ⚠️ 规则 ID 必须与手机上一致。ID 对不上时，`patch` 会返回
> `E_RULE_NOT_FOUND`，手机上表现为「同步成功但某几条没变化」，
> 服务器「设备」页的明细里能看到失败原因。

---

## 4. 能远程改什么，不能改什么

| 字段 | 可远程改 | 理由 |
| --- | --- | --- |
| 时 / 分 | ✅ | |
| 重复模式 / 星期位图 | ✅ | |
| 跳过节假日 | ✅ | |
| 启用 / 停用 | ✅ | |
| 重试次数 / 间隔 / 验证窗口 / 通知策略 | ✅（全局） | |
| **目标 App（targetPackage）** | ❌ | 这是「远程执行原语」的入口。改不了它，即使服务器被攻破，攻击者也最多能让某个已在规则里的 App 早启动几分钟 |
| 规则 ID / 创建时间 | ❌ | |

白名单在 `vps/app/main.py` 的 `ALLOWED_RULE_FIELDS`，
与 Android 侧 `RemoteProtocol.EDITABLE_FIELDS` 逐项对应。

---

## 5. 安全设计

### 5.1 设备侧（每个请求）

```
X-QL-Device: <deviceId>
X-QL-Ts:     <毫秒时间戳>
X-QL-Nonce:  <16 字节随机 hex>
X-QL-Sign:   HMAC-SHA256(secret, "GET\n<path含query>\n<ts>\n<nonce>\n<deviceId>\n<since>")
```

服务器校验：

1. 设备存在且未被撤销
2. `|now - ts| ≤ 10 分钟`
3. nonce 未用过（内存集合，2×时间窗后淘汰）
4. 重算签名并**常数时间比较**

### 5.2 响应侧

```
{ "alg": "HMAC-SHA256", "version": 7, "issuedAt": ..., "nonce": <回显>,
  "updated": true, "payload": "<base64>", "sign": "<hex>" }
```

签名输入：`HMAC(secret, "HMAC-SHA256\n<version>\n<issuedAt>\n<nonce>\n<payloadB64>")`

客户端校验（`VpsEnvelope.parse`）：

| 检查 | 拒绝原因码 | 挡住什么 |
| --- | --- | --- |
| 算法声明 | `E_ALG` | 降级算法替换 |
| nonce 回显 | `E_NONCE` | 重放一份旧的合法响应 |
| 签名 | `E_SIGN` | 篡改 payload / version / issuedAt |
| 时间窗 | `E_STALE` | 旧配置被重新投递 |
| 版本单调 | `E_ROLLBACK` | 把用户已改掉的规则偷偷恢复成旧值 |

### 5.3 传输

- `base-config cleartextTrafficPermitted="false"`：公网一律要求 TLS
- 仅 `127.0.0.1 / localhost / [::1]` 允许明文（`adb reverse` 调试用，报文不出设备）
- 同一条限制也写在 `VpsSettings.validateUrl`：用户只能填 https 或回环地址

### 5.4 管理界面

- 口令摘要（推荐）+ 会话 cookie（HttpOnly / Secure / SameSite=Lax，12 小时）
- 登录限流：同一 IP 连错 5 次锁 10 分钟
- 所有写操作进审计日志（`/audit`）

### 5.5 设备撤销

「设备」页点「撤销」→ 该设备的后续请求一律 401。
手机上同步会失败，但**本地规则照常工作**（不会被清空）。

---

## 6. 验证记录（2026-11-23 真机）

用 `adb reverse tcp:8779 tcp:8778` 把手机请求转到本机测试服务，
配置与线上完全一致（同一份代码、同一套签名）：

| 步骤 | 手机日志 | 服务器日志 |
| --- | --- | --- |
| 首次拉取 v1 | `QL-VpsSync: v1 应用 2 条` | `GET /api/v1/config?...&since=0 → 200`，`POST /api/v1/report → 200` |
| 再次同步 | （无应用日志） | `since=1 → updated=false` |
| 服务端改 id=2 为 07:15 并发布 v2 | `QL-VpsSync: v2 应用 3 条` | - |
| 回滚到 v1 生成 v3 | `QL-VpsSync: v3 应用 2 条` | - |

本地数据库核对（拉取 `db + wal + shm` 后回放）：

```
v1 应用后: (2,18,0,42,1) (4,9,0,30,1) (5,18,1,30,1) (7,10,23,62,1)
v2 应用后: (2,7,15,42,1) (4,9,0,30,1) (5,18,1,30,0) (7,10,23,62,1)   ← id=5 停用生效
v3 应用后: (2,18,0,42,1) ...                                          ← 回滚生效
```

> ⚠️ 取证注意：Room 开了 WAL。只 `adb pull` 主 `.db` 文件会读到**同步前**的快照，
> 必须连 `-wal` 与 `-shm` 一起拉。这一点在联调时踩过，不是代码缺陷。

### 服务器离线

停掉服务后触发同步：

```
QL-Vps : 拉取异常：IOException unexpected end of stream
QL-Vps : 下次同步已排程：+60s
```

核对数据库与闹钟：规则 4 条一字未变，`dumpsys alarm` 里本应用的 `AlarmReceiver`
闹钟仍在。**服务器离线不影响任何本地能力。**

### 服务端自检

```
python tests/e2e_test.py http://127.0.0.1:8778
== 通过 28 / 失败 0 ==
```

覆盖：存活探测、未登录拦截、口令校验、创建设备、无签名/错签名/过期时间戳/重放
四种拒绝、首次拉取、无更新、回应签名、重放保护、结果回报、撤销设备后拒绝。

---

## 7. 跨语言签名的一致性保证

Android 端是 Kotlin，服务端是 Python，各自实现同一套 HMAC 拼接。任何一侧改了
字段顺序或分隔符，线上表现都是「手机一直 401」，而单侧测试永远是绿的。

三道防线：

1. `app/src/test/.../VpsInteropTest.kt` —— 把 Python 侧算出的签名**硬编码**进断言
2. `vps/tests/e2e_test.py` —— 用 Python 模拟客户端跑完整链路
3. 两侧的 canonical 字符串在各自源码里都写了注释指向对方

---

## 8. 备份与迁移

```bash
# 备份（配置 + 设备 + 审计全在里面）
tar czf ql-vps-$(date +%F).tar.gz -C /opt/quicklaunch-vps data .env

# 恢复
tar xzf ql-vps-2026-11-23.tar.gz -C /opt/quicklaunch-vps
docker compose restart
```

`.env` 里有会话密钥与口令摘要，**不要提交到任何仓库**（`vps/.gitignore` 已排除）。

---

## 9. 正式部署实录（2026-11-23，阿里云上海）

这一节记录**实际部署到真实服务器时踩到的坑**。上面的教程是理想路径，
以下是它在中国大陆一台 2 核 2G、与 RustDesk 共存的 Debian 12 上的真实偏差。

### 9.1 环境实况

| 项 | 值 |
| --- | --- |
| 系统 | Debian 12 (bookworm)，kernel 6.1.0-33 |
| 规格 | 2 核 / 1.6 GiB 可用内存 / 40 GB 磁盘（用了 10%） |
| 公网 IP | `47.100.162.254`，域名 A 记录一致（固定 IP，无需 DDNS 更新） |
| 既有服务 | **RustDesk**（`hbbs`/`hbbr`，占 21115-21119）；阿里云监控 agent |
| 80 / 443 | 部署前完全空闲 |
| Docker | 20.10.24（Debian 源），**无 compose 插件** |

### 9.2 六个真实坑

**① compose 插件装不上 → 用 Debian 源的 v1**

GitHub 到阿里云的速度只有 ~14 KB/s，61 MB 的 compose 二进制要下一小时。
直接用 `apt install docker-compose`（v1.29.2）。

v1 要求 compose 文件里有显式 `version` 字段，否则直接报错。
现在文件里加了 `version: "3.8"`（v2 会提示 obsolete，无害）。

**② pip 从官方源装依赖会卡住**

`uvicorn[standard]` 带 uvloop/httptools/watchfiles，PyPI 只有 97 KB/s，
四分钟没装完。Dockerfile 改为：

```dockerfile
ARG PIP_INDEX=https://mirrors.aliyun.com/pypi/simple/
RUN pip install --no-cache-dir -i ${PIP_INDEX} -r requirements.txt
```

阿里云内网 PyPI：**1.5 MB/s**，快 16 倍。构建从「数分钟未完成」变成 40 秒。

**③ Caddy 镜像拉不下来**

Docker Hub 直连超时。`daemon.json` 里已配了几个加速源，但 `docker pull caddy:2-alpine`
仍然卡住。用加速源前缀直连可行：

```bash
docker pull docker.m.daocloud.io/library/caddy:2-alpine
docker tag docker.m.daocloud.io/library/caddy:2-alpine caddy:2-alpine
```

**④ bind mount 覆盖了 Dockerfile 里的 chown**

`sqlite3.OperationalError: unable to open database file` —— 容器内 `ql`（uid 10001）
写不进宿主机 `./data`（属主 root）。镜像里的 `chown /data` 被挂载覆盖了。

```bash
mkdir -p /opt/quicklaunch-vps/data
chown -R 10001:10001 /opt/quicklaunch-vps/data
```

`deploy.sh` 已补这一步。

**⑤ 改代码必须 rebuild，`scp` 到宿主机没用**

`/app` 是构建时 `COPY` 进镜像的，不是挂载。改了 `app/*.py` 后只 `scp`
宿主机文件，容器里还是旧代码 —— 表现为「明明改了却没生效，日志里连新函数都没有」。
正确做法：`docker-compose up -d --build api`。

**⑥ `restart` 不重读 `.env`**

改了 `.env` 后 `docker-compose restart caddy`，容器内的环境变量还是旧值。
必须 `docker-compose up -d`（重建容器）。

### 9.3 本次一并修复的三个服务端缺陷

| 缺陷 | 现象 | 修复 |
| --- | --- | --- |
| 全新部署没有可下发的版本 | 手机同步永远 `E_VERSION`（客户端要求 `version > 0`），表现为「配对成功但拉不到配置」 | `init_db` 在 `current_version == 0` 时自动发布「初始配置」 |
| 服务端不知道手机有哪些规则 | 网页规则列表为空，无可编辑项（客户端对不存在的 id 返回 `notFound`，不能凭空塞） | 设备 `report` 附带规则清单，服务端 `import_device_rules` 只补不改 |
| 下发规则带越权字段 | 发布后 `N-1` 条报 `E_FORBIDDEN_FIELD`（设备上报的完整对象含 `createdAt`/`targetPackage`） | `build_payload` 按客户端白名单裁剪字段 |

还有一个**行为**问题：默认策略 `unlock: {enabled: false}` 若无条件下发，
首次部署会把用户在手机上手开的自动解锁关掉。现在加 `unlock_managed` 标志 ——
没在网页上保存过策略就不下发这个字段。

### 9.4 一个必须知道的网络限制

**家里的路由器会拦截所有 `*.duckdns.org`。**

实测（本机与手机在同一 WiFi 下都复现）：

```
Host: abc.duckdns.org    → 403  Server: Beaver   ← 任意 duckdns 域名
Host: qlhub.duckdns.org  → 403  Server: Beaver
Host: example.com        → 308  Server: Caddy    ← 其他域名正常到服务器
```

- ICMP 直通（ping 正常），TCP 80/443 被按域名接管
- HTTPS 的 ClientHello 发出后直接被 reset
- 手机访问 baidu 正常（200），所以不是全局代理

后果：手机在这个 WiFi 下**无法用主域名同步**。
服务器侧完全正常（`curl https://qlhub.duckdns.org/healthz` → 200，证书 89 天有效）。

绕过方案（已部署）：`.env` 里 `QL_DOMAIN` 支持逗号分隔，同时挂一个
`sslip.io` 别名（把 IP 嵌进域名，不走 duckdns）。手机当前用别名同步。

**根治**需要在家里路由器（`192.168.110.1`）上把 `duckdns.org` 从
URL 过滤/家长控制/安全防护里放行，或者关闭该功能。

### 9.5 监控与备份

```bash
# 每天 3:00 备份（SQLite 用 .backup 做一致性快照，不是 cp）
0 3 * * * /opt/quicklaunch-vps/backup.sh >> /var/log/ql-backup.log 2>&1
# 每 30 分钟巡检（容器 / HTTPS / 证书天数 / 磁盘 / 内存）
*/30 * * * * /opt/quicklaunch-vps/healthcheck.sh
```

- 保留 7 份日备 + 4 份周备，自动清理；备份文件 `600`（含 `.env`）
- 容器日志限制 `max-size=10m, max-file=3`（只配本项目的容器，不动全局
  `daemon.json` —— 那会重启 RustDesk）
- `/etc/logrotate.d/quicklaunch` 管理 `ql-*.log`

**刻意不装** Prometheus / Uptime Kuma：2 核 2G 上多一个常驻服务换来的只是更漂亮的图，
这里需要的只是「出问题能查到」。
