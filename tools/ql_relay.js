#!/usr/bin/env node
'use strict';

/**
 * QuickLaunch 备用通道 —— 公网中继（单文件，零第三方依赖）
 *
 * ## 它是什么
 *
 * 一个「信箱」。设备（手机）与公司电脑都主动**出站**连它，因此不需要公网 IP、
 * 不需要端口映射、也不需要手机开 VPN。中继只负责排队与转交密文。
 *
 * ## 它为什么可以不被信任
 *
 * 指令在离开公司电脑前就已经是 `AES-256-GCM` 信封，并用配对密钥做了
 * `HMAC-SHA256` 签名。中继既解不开内容，也伪造不出能通过验签的指令。
 * 所以：**中继被入侵 ≠ 规则被改**，攻击者最多能丢弃或延迟指令。
 *
 * `--token` 不承担安全职责，只是避免队列被别人塞垃圾。
 *
 * ## 用法
 *
 *   1) 在公司电脑上跑中继（默认只监听 127.0.0.1，不对局域网暴露）：
 *
 *        node ql_relay.js serve --port 8790 --token 你的口令 --state ./ql_relay_state.json
 *
 *   2) 用 Cloudflare Tunnel 把它暴露到公网（出站 443，无需公网 IP）：
 *
 *        cloudflared tunnel --url http://127.0.0.1:8790
 *
 *      记下输出的 https://xxxx.trycloudflare.com，填到手机「备用通道」里。
 *
 *   3) 从公司电脑下发一条指令：
 *
 *        node ql_relay.js push --relay https://xxxx.trycloudflare.com \
 *          --token 你的口令 --device k_xxxxxxxx --secret <配对时的secret> \
 *          --method SKIP --rule 7 --date 2026-11-05 --reason 请假
 *
 *   4) 查结果：
 *
 *        node ql_relay.js result --relay ... --token ... --device k_xxxxxxxx --id <上一步打印的 id>
 *
 *   secret 与 keyId 在手机「远程访问」页配对时显示一次，务必先存下来。
 */

const http = require('http');
const crypto = require('crypto');
const fs = require('fs');

// ── 常量 ────────────────────────────────────────────────────────────

/** 比设备端 5 分钟签名窗口略短，避免「刚好边界」的指令被设备拒掉。 */
const MAX_CMD_AGE_MS = 280_000;
/** 单设备排队上限：正常使用不会超过几条。 */
const MAX_QUEUE_PER_DEVICE = 50;
/** 结果保留上限与 TTL。 */
const MAX_RESULTS = 500;
const RESULT_TTL_MS = 15 * 60_000;
/** 请求体上限。 */
const MAX_BODY = 64 * 1024;

/**
 * 中继允许排队的方法。
 *
 * **故意不含 PAIR / PING**：配对码只有 8 位，在公网暴露面下靠「同 WiFi」
 * 这层兜底已经不成立，所以配对只在局域网完成，中继只承载已配对设备的写请求。
 */
const ALLOWED_METHODS = new Set(['PATCH', 'SKIP', 'RESTORE', 'LIST', 'CALENDAR', 'NEXT']);

// ── 工具 ────────────────────────────────────────────────────────────

const now = () => Date.now();

function log(...args) {
  const t = new Date().toISOString().replace('T', ' ').slice(0, 19);
  console.log(`[${t}]`, ...args);
}

function hmac(key, data) {
  return crypto.createHmac('sha256', key).update(data).digest();
}

function sha256hex(buf) {
  return crypto.createHash('sha256').update(buf).digest('hex');
}

/** 与 Kotlin 端 `RemoteAuth.macKey/encKey` 完全一致：同 label 派生两把子密钥。 */
function deriveKeys(secretB64) {
  const secret = Buffer.from(secretB64, 'base64');
  if (secret.length !== 32) {
    throw new Error(`secret 应为 32 字节（base64 解码后），实际 ${secret.length} 字节`);
  }
  return { secret, macKey: hmac(secret, 'ql-mac-v1'), encKey: hmac(secret, 'ql-enc-v1') };
}

/**
 * 构造指令。
 *
 * 关键：`canonical` 与局域网请求**逐字节一致** —— 签的是信封的 base64 文本
 * （不是明文），所以同一份签名代码既能签 LAN 请求也能签中继指令。
 */
function buildCommand({ secretB64, method, ruleId, payload }) {
  const m = String(method || '').toUpperCase();
  if (!ALLOWED_METHODS.has(m)) {
    throw new Error(`不允许的方法 ${m}（可选：${[...ALLOWED_METHODS].join(', ')}）`);
  }
  const { macKey, encKey } = deriveKeys(secretB64);

  const path = m === 'LIST' ? '/api/v1/rules' : `/api/v1/rules/${ruleId}`;
  if (m !== 'LIST' && !/^\d+$/.test(String(ruleId || ''))) {
    throw new Error(`方法 ${m} 需要 --rule <规则id>`);
  }

  const plain = Buffer.from(JSON.stringify(payload || {}), 'utf8');
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv('aes-256-gcm', encKey, iv);
  const ct = Buffer.concat([cipher.update(plain), cipher.final()]);
  const envelope = Buffer.concat([iv, ct, cipher.getAuthTag()]);
  const body = envelope.toString('base64');

  const ts = now();
  const nonce = crypto.randomBytes(8).toString('hex');
  const canonical = [m, path, ts, nonce, sha256hex(Buffer.from(body, 'ascii'))].join('\n');
  const sign = hmac(macKey, Buffer.from(canonical, 'utf8')).toString('base64');

  return { id: nonce, method: m, path, ts, nonce, sign, body };
}

async function postJson(url, obj, token) {
  const res = await fetch(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      ...(token ? { 'X-Relay-Token': token } : {}),
    },
    body: JSON.stringify(obj),
    signal: AbortSignal.timeout(15000),
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`HTTP ${res.status}: ${text}`);
  return JSON.parse(text);
}

// ── 状态 ────────────────────────────────────────────────────────────

function loadState(file) {
  const empty = { queues: {}, results: {} };
  if (!file) return empty;
  try {
    return { ...empty, ...JSON.parse(fs.readFileSync(file, 'utf8')) };
  } catch {
    return empty;
  }
}

function saveState(file, state) {
  if (!file) return;
  // 先写临时文件再替换：避免断电时留下半个 JSON 把队列毁掉
  const tmp = `${file}.tmp`;
  fs.writeFileSync(tmp, JSON.stringify(state));
  fs.renameSync(tmp, file);
}

// ── 服务端 ──────────────────────────────────────────────────────────

function serve(opts) {
  const state = loadState(opts.state);
  const token = opts.token || '';

  function sweep() {
    const t = now();
    for (const [key, r] of Object.entries(state.results)) {
      if (t - r.at > RESULT_TTL_MS) delete state.results[key];
    }
    const keys = Object.keys(state.results);
    if (keys.length > MAX_RESULTS) {
      keys.sort((a, b) => state.results[a].at - state.results[b].at)
        .slice(0, keys.length - MAX_RESULTS)
        .forEach((k) => delete state.results[k]);
    }
  }

  function json(res, status, obj) {
    const body = Buffer.from(JSON.stringify(obj), 'utf8');
    res.writeHead(status, {
      'Content-Type': 'application/json; charset=utf-8',
      'Content-Length': body.length,
      'Cache-Control': 'no-store',
    });
    res.end(body);
  }

  function readBody(req) {
    return new Promise((resolve, reject) => {
      const chunks = [];
      let size = 0;
      req.on('data', (c) => {
        size += c.length;
        if (size > MAX_BODY) {
          reject(new Error('body too large'));
          req.destroy();
          return;
        }
        chunks.push(c);
      });
      req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
      req.on('error', reject);
    });
  }

  const server = http.createServer(async (req, res) => {
    try {
      if (token) {
        const got = req.headers['x-relay-token'] || '';
        // 常量时间比较，避免用响应时间逐字节猜口令
        const a = Buffer.from(got);
        const b = Buffer.from(token);
        if (a.length !== b.length || !crypto.timingSafeEqual(a, b)) {
          return json(res, 401, { ok: false, error: 'E_TOKEN', detail: '中继令牌不正确' });
        }
      }

      const url = new URL(req.url, 'http://localhost');
      const route = url.pathname.replace(/\/+$/, '');

      if (route === '/ql/ping' && req.method === 'GET') {
        return json(res, 200, {
          ok: true,
          v: 1,
          time: now(),
          devices: Object.keys(state.queues).length,
        });
      }

      if (route === '/ql/push' && req.method === 'POST') {
        const body = JSON.parse(await readBody(req));
        const device = String(body.device || '').trim();
        if (!device) return json(res, 400, { ok: false, error: 'E_DEVICE', detail: '缺少 device' });

        const list = Array.isArray(body.commands) ? body.commands : [body.command];
        const queue = state.queues[device] || (state.queues[device] = []);
        let queued = 0;
        for (const cmd of list) {
          if (!cmd || typeof cmd !== 'object') continue;
          const m = String(cmd.method || '').toUpperCase();
          if (!ALLOWED_METHODS.has(m)) {
            return json(res, 400, {
              ok: false,
              error: 'E_METHOD_BLOCKED',
              detail: `中继不允许排队 ${m}`,
            });
          }
          for (const f of ['path', 'nonce', 'sign', 'body']) {
            if (!cmd[f]) {
              return json(res, 400, { ok: false, error: 'E_FIELD', detail: `缺少字段 ${f}` });
            }
          }
          queue.push({
            id: String(cmd.id || cmd.nonce),
            method: m,
            path: String(cmd.path),
            ts: Number(cmd.ts) || now(),
            nonce: String(cmd.nonce),
            sign: String(cmd.sign),
            body: String(cmd.body),
          });
          queued++;
        }
        while (queue.length > MAX_QUEUE_PER_DEVICE) queue.shift();
        saveState(opts.state, state);
        log(`push device=${device} queued=${queued} depth=${queue.length}`);
        return json(res, 200, { ok: true, queued, depth: queue.length });
      }

      if (route === '/ql/pull' && req.method === 'POST') {
        const body = JSON.parse(await readBody(req));
        const device = String(body.device || '').trim();
        if (!device) return json(res, 400, { ok: false, error: 'E_DEVICE', detail: '缺少 device' });

        const queue = state.queues[device] || [];
        const t = now();
        const fresh = [];
        let expired = 0;
        for (const cmd of queue) {
          if (t - cmd.ts > MAX_CMD_AGE_MS) {
            expired++;
            continue;
          }
          fresh.push(cmd);
        }
        // 取出即清空：同一条指令不应被执行两次（设备侧还有 nonce 兜底）
        state.queues[device] = [];
        if (expired || fresh.length) saveState(opts.state, state);
        if (fresh.length || expired) {
          log(`pull device=${device} cmds=${fresh.length} expired=${expired}`);
        }
        return json(res, 200, { ok: true, cmds: fresh, expired });
      }

      if (route === '/ql/result') {
        if (req.method === 'POST') {
          const body = JSON.parse(await readBody(req));
          const device = String(body.device || '').trim();
          if (!device) return json(res, 400, { ok: false, error: 'E_DEVICE', detail: '缺少 device' });
          const results = Array.isArray(body.results) ? body.results : [];
          for (const r of results) {
            if (!r || !r.id) continue;
            state.results[`${device}|${r.id}`] = {
              at: now(),
              status: Number(r.status) || 0,
              error: String(r.error || ''),
              body: String(r.body || ''),
            };
          }
          sweep();
          saveState(opts.state, state);
          log(`result device=${device} n=${results.length}`);
          return json(res, 200, { ok: true, saved: results.length });
        }
        if (req.method === 'GET') {
          const device = String(url.searchParams.get('device') || '').trim();
          const id = String(url.searchParams.get('id') || '').trim();
          const hit = state.results[`${device}|${id}`];
          if (!hit) return json(res, 404, { ok: false, error: 'E_NO_RESULT', detail: '还没有结果' });
          return json(res, 200, { ok: true, id, ...hit });
        }
        return json(res, 405, { ok: false, error: 'E_METHOD', detail: '只支持 GET/POST' });
      }

      return json(res, 404, { ok: false, error: 'E_NOT_FOUND', detail: '只有 /ql/* 可用' });
    } catch (e) {
      const msg = String(e && e.message || e);
      const code = msg === 'body too large' ? 413 : 400;
      return json(res, code, { ok: false, error: 'E_BAD_REQUEST', detail: msg });
    }
  });

  server.listen(opts.port, opts.host, () => {
    log(`中继已启动 http://${opts.host}:${opts.port}`);
    if (opts.host === '127.0.0.1') {
      log('只监听本机。要公网访问请用：cloudflared tunnel --url http://127.0.0.1:' + opts.port);
    }
    if (!token) log('⚠ 未设置 --token：任何能访问该端口的人都能往队列里塞垃圾（不会影响规则安全，但会刷审计）');
    if (opts.state) log(`队列落盘：${opts.state}`);
  });

  return server;
}

// ── CLI ─────────────────────────────────────────────────────────────

function parseArgs(argv) {
  const out = { _: [] };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a.startsWith('--')) {
      const key = a.slice(2);
      const next = argv[i + 1];
      if (next === undefined || next.startsWith('--')) out[key] = true;
      else {
        out[key] = next;
        i++;
      }
    } else out._.push(a);
  }
  return out;
}

async function main() {
  const argv = parseArgs(process.argv.slice(2));
  const cmd = argv._[0];

  if (cmd === 'serve') {
    serve({
      host: argv.host || '127.0.0.1',
      port: Number(argv.port) || 8790,
      token: argv.token || '',
      state: argv.state || '',
    });
    return;
  }

  if (cmd === 'push') {
    const need = ['relay', 'device', 'secret', 'method'];
    for (const k of need) {
      if (!argv[k]) throw new Error(`缺少 --${k}`);
    }
    const method = String(argv.method).toUpperCase();

    // 按方法组装 payload —— 字段名必须与手机端 RemoteRuleApi 的入参一致
    const payload = {};
    if (method === 'PATCH') {
      for (const f of ['timeHour', 'timeMinute', 'repeatMode', 'repeatDays',
        'skipHolidays', 'randomWindow', 'windowStartMin', 'windowEndMin', 'enabled']) {
        const v = argv[f];
        if (v === undefined) continue;
        if (['skipHolidays', 'randomWindow', 'enabled'].includes(f)) {
          payload[f] = v === true || v === 'true' || v === '1';
        } else {
          payload[f] = Number(v);
        }
      }
    } else if (method === 'SKIP') {
      payload.date = argv.date || '';
      payload.reason = argv.reason || '';
    } else if (method === 'RESTORE') {
      payload.date = argv.date || '';
    } else if (method === 'CALENDAR') {
      payload.days = Number(argv.days) || 14;
    } else if (method === 'NEXT') {
      if (argv.at) payload.at = Number(argv.at);
    }

    const command = buildCommand({
      secretB64: argv.secret,
      method,
      ruleId: argv.rule,
      payload,
    });

    const base = String(argv.relay).replace(/\/+$/, '');
    const r = await postJson(`${base}/ql/push`, { device: argv.device, commands: [command] }, argv.token);
    log(`已排队 id=${command.id} depth=${r.depth}`);
    console.log(`\n查结果：\n  node ql_relay.js result --relay ${base} --device ${argv.device} --id ${command.id}${argv.token ? ' --token ' + argv.token : ''}\n`);
    return;
  }

  if (cmd === 'result') {
    const base = String(argv.relay || '').replace(/\/+$/, '');
    const u = `${base}/ql/result?device=${encodeURIComponent(argv.device)}&id=${encodeURIComponent(argv.id)}`;
    const res = await fetch(u, {
      headers: argv.token ? { 'X-Relay-Token': argv.token } : {},
      signal: AbortSignal.timeout(15000),
    });
    const text = await res.text();
    if (!res.ok) {
      console.error(`HTTP ${res.status}: ${text}`);
      process.exitCode = 2;
      return;
    }
    const obj = JSON.parse(text);
    console.log(`status=${obj.status} error=${obj.error || '-'}`);
    if (obj.body) {
      // 结果是密文：中继解不开，所以这里只能打印信封，需在手机侧审计里看明文
      console.log('body(密文)=' + obj.body.slice(0, 80) + '…');
    }
    return;
  }

  if (cmd === 'ping') {
    const base = String(argv.relay || '').replace(/\/+$/, '');
    const res = await fetch(`${base}/ql/ping`, {
      headers: argv.token ? { 'X-Relay-Token': argv.token } : {},
      signal: AbortSignal.timeout(15000),
    });
    console.log(await res.text());
    return;
  }

  console.log(`QuickLaunch 中继

  node ql_relay.js serve  --port 8790 --token <口令> [--state ./state.json]
  node ql_relay.js push   --relay <url> --device <keyId> --secret <secret> --method SKIP --rule 7 --date 2026-11-05
  node ql_relay.js result --relay <url> --device <keyId> --id <id>
  node ql_relay.js ping   --relay <url>`);
}

main().catch((e) => {
  console.error('错误：', e.message || e);
  process.exitCode = 1;
});
