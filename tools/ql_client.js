#!/usr/bin/env node
'use strict';

/**
 * QuickLaunch 局域网直连控制端（零依赖，Node 18+）。
 *
 * 与 ql_relay.js 的关系：
 *   - 两者共用同一套密码学（canonical / AES-GCM 信封），互通互验；
 *   - 但请求形态不同：本脚本走手机的**真实 HTTP 路由**（GET/PATCH/POST/DELETE 各司其职），
 *     中继走的是「method 字段分派」（PATCH/SKIP/RESTORE…），path 只是形式。
 *
 * 用法（首次配对后配置自动落盘，后续免参数）：
 *   node ql_client.js pair   --code 23686590 --name gongsi-pc --host 192.168.110.201
 *   node ql_client.js ping
 *   node ql_client.js list
 *   node ql_client.js get      --rule 1
 *   node ql_client.js patch    --rule 1 --hour 8 --minute 30 [--enabled false]
 *   node ql_client.js skip     --rule 1 --date 2026-11-05 --reason 请假
 *   node ql_client.js restore  --rule 1 --date 2026-11-05
 *   node ql_client.js exceptions --rule 1
 *   node ql_client.js calendar --rule 1 --days 7
 *   node ql_client.js next     --rule 1
 */

const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const CFG_FILE = path.join(__dirname, 'ql_client.json');

// ── 密码学（与 RemoteAuth.kt 逐字节对齐）─────────────────────────────

const hmac = (key, data) => crypto.createHmac('sha256', key).update(data).digest();
const sha256hex = (buf) => crypto.createHash('sha256').update(buf).digest('hex');

function deriveKeys(secretB64) {
  const secret = Buffer.from(secretB64, 'base64');
  return {
    secret,
    macKey: hmac(secret, 'ql-mac-v1'),
    encKey: hmac(secret, 'ql-enc-v1'),
  };
}

function encrypt(encKey, plainBuf) {
  const iv = crypto.randomBytes(12);
  const c = crypto.createCipheriv('aes-256-gcm', encKey, iv);
  const ct = Buffer.concat([c.update(plainBuf), c.final()]);
  return Buffer.concat([iv, ct, c.getAuthTag()]).toString('base64');
}

function decrypt(encKey, b64) {
  const buf = Buffer.from(String(b64).trim(), 'base64');
  if (buf.length < 12 + 16) throw new Error('信封长度不足');
  const iv = buf.subarray(0, 12);
  const tag = buf.subarray(buf.length - 16);
  const ct = buf.subarray(12, buf.length - 16);
  const d = crypto.createDecipheriv('aes-256-gcm', encKey, iv);
  d.setAuthTag(tag);
  return Buffer.concat([d.update(ct), d.final()]).toString('utf8');
}

// ── 配置 ────────────────────────────────────────────────────────────

function loadCfg(argv) {
  let saved = {};
  if (fs.existsSync(CFG_FILE)) {
    try {
      saved = JSON.parse(fs.readFileSync(CFG_FILE, 'utf8'));
    } catch {
      saved = {};
    }
  }
  const cfg = {
    host: argv.host || saved.host || '192.168.110.1',
    port: Number(argv.port || saved.port || 8765),
    key: argv.key || saved.key || '',
    secret: argv.secret || saved.secret || '',
  };
  return cfg;
}

function saveCfg(cfg) {
  fs.writeFileSync(CFG_FILE, JSON.stringify(cfg, null, 2), 'utf8');
}

// ── 请求 ────────────────────────────────────────────────────────────

async function signedCall(cfg, method, pathOnly, query, payload) {
  if (!cfg.key || !cfg.secret) throw new Error('缺少 key/secret，请先执行 pair');
  const { macKey, encKey } = deriveKeys(cfg.secret);

  let bodyStr = '';
  if (method !== 'GET') {
    bodyStr = encrypt(encKey, Buffer.from(JSON.stringify(payload || {}), 'utf8'));
  }

  const ts = Date.now();
  const nonce = crypto.randomBytes(8).toString('hex');
  // path 不含 query —— 与 MiniHttpServer 的 req.path 口径一致
  const canonical = [method, pathOnly, ts, nonce, sha256hex(Buffer.from(bodyStr, 'ascii'))].join('\n');
  const sign = hmac(macKey, Buffer.from(canonical, 'utf8')).toString('base64');

  const url = `http://${cfg.host}:${cfg.port}${pathOnly}${query || ''}`;
  const res = await fetch(url, {
    method,
    headers: {
      'X-QL-Key': cfg.key,
      'X-QL-Ts': String(ts),
      'X-QL-Nonce': nonce,
      'X-QL-Sign': sign,
      ...(bodyStr ? { 'Content-Type': 'text/plain; charset=utf-8' } : {}),
    },
    body: bodyStr || undefined,
    signal: AbortSignal.timeout(10000),
  });

  const text = await res.text();
  // 成功响应一定是信封；失败响应是明文 JSON
  try {
    return { status: res.status, data: JSON.parse(decrypt(encKey, text)), encrypted: true };
  } catch {
    let data = null;
    try {
      data = JSON.parse(text);
    } catch {
      /* 非 JSON，原样返回 */
    }
    return { status: res.status, data, raw: text, encrypted: false };
  }
}

// ── 子命令 ──────────────────────────────────────────────────────────

const EDITABLE = ['timeHour', 'timeMinute', 'repeatMode', 'repeatDays',
  'skipHolidays', 'randomWindow', 'windowStartMin', 'windowEndMin', 'enabled'];

function buildPatchPayload(argv) {
  const p = {};
  for (const f of EDITABLE) {
    if (argv[f] === undefined) continue;
    const v = argv[f];
    if (['skipHolidays', 'randomWindow', 'enabled'].includes(f)) {
      p[f] = v === true || v === 'true' || v === '1';
    } else {
      p[f] = Number(v);
    }
  }
  if (Object.keys(p).length === 0) throw new Error(`patch 至少需要一个字段：${EDITABLE.join(', ')}`);
  return p;
}

function needRule(argv) {
  if (!argv.rule || !/^\d+$/.test(String(argv.rule))) throw new Error('缺少 --rule <规则id>');
  return String(argv.rule);
}

async function main() {
  const [, , cmd, ...rest] = process.argv;
  const argv = {};
  for (let i = 0; i < rest.length; i++) {
    const a = rest[i];
    if (a.startsWith('--')) {
      const k = a.slice(2);
      const next = rest[i + 1];
      argv[k] = next && !next.startsWith('--') ? (i++, next) : true;
    }
  }

  if (!cmd || cmd === 'help' || cmd === '--help') {
    printHelp();
    return;
  }

  const cfg = loadCfg(argv);

  switch (cmd) {
    case 'pair': {
      const code = String(argv.code || '').replace(/\s/g, '');
      if (code.length !== 8) throw new Error('--code 需要 8 位配对码（手机上生成）');
      const url = `http://${cfg.host}:${cfg.port}/api/v1/pair`;
      const res = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json; charset=utf-8' },
        body: JSON.stringify({ code, clientName: argv.name || 'ql_client' }),
        signal: AbortSignal.timeout(15000),
      });
      const obj = await res.json();
      if (!obj.ok) {
        console.error(`配对失败 [${res.status}] ${obj.error || ''} ${obj.message || ''}`);
        process.exitCode = 1;
        return;
      }
      cfg.key = obj.keyId;
      cfg.secret = obj.secret;
      saveCfg(cfg);
      console.log(`配对成功：device=${obj.device}`);
      console.log(`  keyId  = ${obj.keyId}`);
      console.log(`  secret = ${obj.secret}`);
      console.log(`已保存到 ${CFG_FILE}（含密钥，别提交到版本库）`);
      return;
    }

    case 'ping': {
      const res = await fetch(`http://${cfg.host}:${cfg.port}/api/v1/ping`, {
        signal: AbortSignal.timeout(8000),
      });
      console.log(JSON.stringify(await res.json(), null, 2));
      return;
    }

    case 'list': {
      const r = await signedCall(cfg, 'GET', '/api/v1/rules');
      out(r);
      return;
    }

    case 'get': {
      const r = await signedCall(cfg, 'GET', `/api/v1/rules/${needRule(argv)}`);
      out(r);
      return;
    }

    case 'patch': {
      const r = await signedCall(cfg, 'PATCH', `/api/v1/rules/${needRule(argv)}`, null, buildPatchPayload(argv));
      out(r);
      return;
    }

    case 'skip': {
      const id = needRule(argv);
      if (!argv.date) throw new Error('缺少 --date YYYY-MM-DD');
      const r = await signedCall(cfg, 'POST', `/api/v1/rules/${id}/exceptions`, null, {
        date: argv.date,
        reason: argv.reason || '',
      });
      out(r);
      return;
    }

    case 'restore': {
      const id = needRule(argv);
      if (!argv.date) throw new Error('缺少 --date YYYY-MM-DD');
      const r = await signedCall(cfg, 'DELETE', `/api/v1/rules/${id}/exceptions/${argv.date}`);
      out(r);
      return;
    }

    case 'exceptions': {
      const r = await signedCall(cfg, 'GET', `/api/v1/rules/${needRule(argv)}/exceptions`);
      out(r);
      return;
    }

    case 'calendar': {
      const days = Number(argv.days || 7);
      const r = await signedCall(cfg, 'GET', `/api/v1/rules/${needRule(argv)}/calendar`, `?days=${days}`);
      out(r);
      return;
    }

    case 'next': {
      const q = argv.at ? `?at=${Number(argv.at)}` : '';
      const r = await signedCall(cfg, 'GET', `/api/v1/rules/${needRule(argv)}/next`, q);
      out(r);
      return;
    }

    default:
      printHelp();
      process.exitCode = 2;
  }
}

function out(r) {
  if (r.data && r.data.ok === false) {
    console.error(`失败 [${r.status}] ${r.data.error || ''} ${r.data.message || ''}`);
    process.exitCode = 1;
    return;
  }
  console.log(JSON.stringify(r.data !== null ? r.data : r.raw, null, 2));
}

function printHelp() {
  console.log(`QuickLaunch 局域网控制端

  node ql_client.js pair     --code <8位码> --name <客户端名> [--host IP]
  node ql_client.js ping
  node ql_client.js list
  node ql_client.js get      --rule <id>
  node ql_client.js patch    --rule <id> [--timeHour 8] [--timeMinute 30]
                             [--repeatMode daily|weekdays|weekend|custom|once]
                             [--repeatDays 127] [--skipHolidays true] [--enabled true]
  node ql_client.js skip     --rule <id> --date YYYY-MM-DD [--reason 请假]
  node ql_client.js restore  --rule <id> --date YYYY-MM-DD
  node ql_client.js exceptions --rule <id>
  node ql_client.js calendar --rule <id> [--days 7]
  node ql_client.js next     --rule <id> [--at <epochMs>]

通用参数：--host <IP> --port <端口> --key <keyId> --secret <secret>
配置默认从 ${CFG_FILE} 读取（pair 时自动写入）。`);
}

main().catch((e) => {
  console.error(`错误：${e.message}`);
  process.exitCode = 1;
});
