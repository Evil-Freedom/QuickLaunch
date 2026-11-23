# QuickLaunch 1.4.1 交付记录

> 基线 v1.4.0（commit `e2429ce`）→ 本版 v1.4.1 / versionCode 24
> 日期：2026-11-23
> 验收设备：Motorola Razr 40 Ultra（XT2321-2 / SN `ZY22HKL48G`）/ Android 16 / Magisk root

---

## 1. 本版新增

### 1.1 Root 自动解锁升级（核心）

| 能力 | 实现 |
| --- | --- |
| 本地 PIN 设置/修改/清除 | 新增 `AutoUnlockActivity`（两次输入确认、存储方式展示、降级警告） |
| 状态记录 | 上次尝试时间、上次结果（Keyguard 真实回读）、安全停用原因 |
| 降级可见性 | `PinVault.isKeystoreAvailable()` + `storageKindOf`；UI 黄色警告，绝不静默降级 |
| 单次失败通知 | 新增独立通知（文案不含任何 PIN 信息）；安全停用通知不受开关控制 |
| 远程策略门控 | `remoteEnableGate` 纯函数：安全停用 > 无PIN > 降级存储 > 无root |
| 看板页 | `https://qlhub.duckdns.org/unlock`：策略下发 + 设备自报状态展示 |

**没有重写解锁系统**：判定链、注入顺序（上滑→text→回车）、Keyguard 真实回读、
失败保护全部保留。详见 `docs/AUTO_UNLOCK_SECURITY.md`。

### 1.2 DuckDNS 私人控制台

- 固定域名 `https://qlhub.duckdns.org`（页面：规则 / 版本 / 设备 / **自动解锁** / 日志）
- `Caddyfile` 按该域名签发 Let's Encrypt 证书
- 服务端数据库幂等迁移：devices 表增加机型 / Android 版本 / App 版本 / 解锁状态列
- 发布状态可从回报记录推导（最新回报 vN + ok 标志），不单独维护状态列避免漂移

### 1.3 配置内容指纹（重要修复）

**发现的真实缺陷**：升级窗口期，旧 APK（不认识 payload 里的 `unlock` 字段）
拉到了 v5 并把版本推进到 5 —— 内容没生效，版本号却用掉了。此后新 APK 同步
`since=5`，服务端认为无更新，`unlock` 永远不会到达。

**修复**（If-None-Match 模式）：

- 服务端对每个版本的 payload 计算 SHA-256 前 16 位指纹
- 客户端请求带 `hash=<上次应用的指纹>`，并纳入请求签名
- 服务端判定：`版本 ≤ 已应用 且 指纹一致` 才返回无更新；指纹不一致强制下发全量
- 响应的指纹字段纳入响应签名（防伪造跳过更新）
- 跨语言基准值已更新（`VpsInteropTest` 3 例重新锁定）

### 1.4 同步通道池解耦

**发现的稳定性问题**：真机 force-stop 恢复场景下，全局 `save` 单线程池出现过
任务长时间不轮转（线程栈确认 `ql-save` 处于 Waiting），导致 VPS 同步广播入队
但不执行。该池同时承载规则触发的长任务（root 调用、全量重排）。

**修复**：`VpsSyncReceiver` 改用独立单线程池（`ql-vps-sync`，daemon）。
同步卡住只卡自己，规则触发链路不受影响。`save` 池的无超时风险已记录为
既有架构的已知问题（见 §6）。

### 1.5 其它修复

| 缺陷 | 场景 | 修复 |
| --- | --- | --- |
| 回报签名与 body 不一致 | `detail` 超过 200 字符时，签名用原文、body 用截断版 → 服务端必拒 | 截断后再签名 |
| 同步广播被投递两次 | 真机观察到 ~30ms 间隔双投递 | `VpsSyncReceiver` 3 秒去重 |

---

## 2. 测试统计

| 套件 | 数量 | 结果 |
| --- | --- | --- |
| JVM 单测 | 310 例（1.4.0 的 300 + 自动解锁门控 10） | ✅ 全绿 |
| 服务端 e2e | 32 项（1.4.0 的 28 + 指纹 4 断言） | ✅ 全绿 |
| 跨语言签名互操作 | 3 例（按新 canonical 重新锁定） | ✅ 全绿 |

## 3. 真机证据摘要（2026-11-23）

### 3.1 远程策略安全门控（最重要场景）

真机 `auto_unlock_failures = 2`（用户环境真实触发的保护状态）。
服务端下发 `unlock: {enabled: true}`：

```
15:41:54 QL-VpsSync: v7 应用 3 条；解锁策略被拒绝：SECURITY_LOCKED
                     （自动解锁因连续失败已停用，必须在手机上手动重新启用）
```

同步后 SP：`auto_unlock_enabled=false`、`auto_unlock_charging_only=false`
（原子拒绝，策略一字未改）。服务端记录 `securityLocked=true, failures=2`。

### 3.2 设备信息与解锁状态上报

服务端 `devices` 表（真机）：

```
model=XT2321-2  android_version=16  app_version=1.4.0
unlock: {"enabled":false,"hasPin":true,"storage":"KEYSTORE","chargingOnly":false,
         "failures":2,"maxFailures":2,"securityLocked":true,
         "lastAttemptAt":0,"lastResult":""}
```

### 3.3 内容指纹闭环

```
首次同步（hash 空）     → v7 下发全量 → 应用 → applied_hash=290f1260952b7e7c
第二次同步（hash 匹配） → last_sync_note=无更新（v7）
```

### 3.4 用户规则还原（测试污染清理）

服务端测试过程改动了用户规则。已按 1.3.18 升级前的数据库备份
（`ql-check.db`）**逐字段还原**并经配置通道下发：

```
(2, custom, 30, 18:00, 启用)   ← 飞书
(4, custom, 124, 9:00, 启用)   ← 哔哩哔哩
(5, custom, 60, 18:01, 启用)   ← 哔哩哔哩（skipHolidays=false 是原值）
(7, custom, 30, 10:23, 启用, 随机窗口 8:45~8:55)  ← 飞书
```

`dumpsys alarm` 真实落点全部正确：

```
2026-11-23 18:00:00  ← id=2（今天周一，repeatDays=30 含周一）
2026-11-24 08:53:00  ← id=7（随机窗口在 8:45~8:55 内取点）
2026-11-24 09:00:00  ← id=4（今天 9:00 已过 → 明天）
2026-11-24 18:01:00  ← id=5（repeatDays=60 不含周一 → 明天周二）
```

### 3.5 测试残留清理

真机的 VPS 调试配置（指向 `127.0.0.1:8779` 的 adb reverse 通道）已删除；
临时数据库副本已从 /sdcard 清除。正式部署 qlhub 后在 App 里重新配置即可。

---

## 4. DuckDNS 部署核查清单

**本机无法代验的部分**：这台 Windows 机器的 DNS 被透明代理劫持
（`qlhub.duckdns.org` 解析到 `198.18.1.116`，RFC 2544 保留网段；8.8.8.8 / 1.1.1.1
结果相同），HTTPS 直连失败。因此以下检查**必须在 VPS 上执行**：

```bash
# 1. 确认 DuckDNS 绑定的 IP 与本机公网 IP 一致
dig +short qlhub.duckdns.org @8.8.8.8
curl -s ifconfig.me; echo

# 2. 部署后验证 HTTPS 与证书
curl -v https://qlhub.duckdns.org/api/v1/ping
echo | openssl s_client -connect qlhub.duckdns.org:443 -servername qlhub.duckdns.org 2>/dev/null | openssl x509 -noout -subject -dates

# 3. 手机移动网络（非 WiFi）访问验证 —— Caddy 证书是公网可信的
```

- 若 `dig` 结果与公网 IP 一致且 IP 固定 → 不需要 DDNS 更新任务
- 若 IP 会变 → 在 VPS 加 cron（Token 保存在 `/opt/quicklaunch/.secrets/duckdns.env`，
  权限 600，禁止进任何仓库）：
  `*/5 * * * * curl -s "https://www.duckdns.org/update?domains=qlhub&token=$TOKEN&ip=" >/dev/null`

---

## 5. 交付物对照（需求 §13）

| # | 交付物 | 位置 |
| --- | --- | --- |
| 1 | Android 项目 | 本仓库 `app/`（v1.4.1 / 24） |
| 2 | VPS 后端 | `vps/` |
| 3 | Web 管理看板 | `vps/app/templates/`（规则/版本/设备/自动解锁/日志 5 页） |
| 4 | DuckDNS 部署说明 | 本文 §4 + `docs/VPS_REMOTE.md` |
| 5 | HTTPS 证书与续期 | Caddy 自动签发续期，无需手工操作 |
| 6 | Docker Compose | `vps/docker-compose.yml`（YAML 已校验；本机无 Docker，首次部署时按 `VPS_REMOTE.md` §2 执行） |
| 7 | 数据库迁移脚本 | 服务端启动时幂等执行（`db.py._migrate`，全 ADD COLUMN，零数据丢失） |
| 8 | 可靠启动引擎测试报告 | `docs/LAUNCH_ENGINE.md` |
| 9 | 自动解锁专项安全报告 | `docs/AUTO_UNLOCK_SECURITY.md` |
| 10 | 远程规则同步测试报告 | `docs/VPS_REMOTE.md` §6 |
| 11 | PIN 安全存储审计 | `docs/AUTO_UNLOCK_SECURITY.md` §2 |
| 12 | Root 真机测试证据 | 本文 §3 + `docs/LAUNCH_ENGINE.md` §3 |
| 13 | 正式版 APK | `app/build/outputs/apk/release/app-release.apk`（1.4.1 / 24） |
| 14 | sig2 APK | `app/build/outputs/apk/sig2/app-sig2.apk` |
| 15 | 回滚方案 | `docs/RELEASE_1.4.0.md` §5（机制不变）+ `backups/pre-launch-engine-*` |
| 16 | 用户操作说明 | `docs/VPS_REMOTE.md` §3 + 本文 §6 |

## 6. 已知问题与遗留

| # | 项 | 处理 |
| --- | --- | --- |
| 1 | 全局 `save` 单线程池无任务超时（既有架构） | 同步已解耦；规则触发链路的超时保护建议下版评估 |
| 2 | 自动解锁时间窗策略未实现 | 需改 `decide` 判定链，明确暂缓 |
| 3 | 真机自动解锁处于安全停用态 | 用户本人手机操作恢复（设计使然） |
| 4 | 合盖外屏 / 锁屏场景的启动验收未执行 | 流程见 `docs/RELEASE_1.4.0.md` §8 |
| 5 | DuckDNS 真实解析未验证 | 本机 DNS 被劫持；按 §4 清单在 VPS 上核查 |
| 6 | Docker Compose 未实际拉起 | 本机无 Docker；配置已做静态校验 + 服务端已以同代码裸跑验证 |

## 7. 用户操作说明（新增部分）

### 7.1 手机上管理自动解锁

启动页 → 同步页 → 「锁屏自动解锁」状态行 → 进入详情页：

- **设置/修改密码**：两次输入确认；保存后失败计数清零
- **清除密码**：确认后清除并关闭功能
- **仅充电时解锁 / 失败时通知**：开关
- **状态区**：功能状态、密码存储方式（加密/降级）、失败计数、上次尝试与结果

若显示「已因连续失败自动停用」：重新设置密码后打开开关即可本地恢复
（远程无法代劳，这是故意的）。

### 7.2 看板上管理自动解锁

`https://qlhub.duckdns.org/unlock`：

- 上方表单改「启用 / 仅充电时」→ 保存并发布 → 手机下次同步时应用
- 下方表格看每台设备自报的状态：机型、App 版本、是否安全停用、失败计数、
  上次尝试与结果、存储方式徽标

被拒绝时手机回报的原因码会出现在同步明细里，常见四种：
`SECURITY_LOCKED` / `NO_PIN` / `INSECURE_STORAGE` / `NO_ROOT`，
含义与处理方式见 `docs/AUTO_UNLOCK_SECURITY.md` §4。
