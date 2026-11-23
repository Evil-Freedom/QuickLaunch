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

### 2.2 上传并配置

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
| `QL_DOMAIN` | 你的域名，如 `ql.example.com` |
| `QL_ADMIN_PASSWORD_SHA256` | `printf '%s' '你的口令' \| sha256sum \| cut -d' ' -f1` |
| `QL_SESSION_SECRET` | `openssl rand -hex 32` |

### 2.3 启动

```bash
docker compose up -d
docker compose logs -f caddy    # 等日志出现 certificate obtained
curl https://<你的域名>/api/v1/ping
# {"ok":true,"version":0}
```

首次启动会自动建库、写入默认重试策略（与 Android 端默认值逐项对齐）。

---

## 3. 首次使用

### 3.1 生成设备凭据

1. 浏览器打开 `https://<域名>/devices`
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
