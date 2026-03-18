#!/usr/bin/env node
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import process from 'node:process';
import { execSync } from 'node:child_process';

function parseArgs(argv) {
  const out = {};
  for (let i = 2; i < argv.length; i += 1) {
    const arg = argv[i];
    if (!arg.startsWith('--')) continue;
    const key = arg.slice(2);
    const next = argv[i + 1];
    if (!next || next.startsWith('--')) {
      out[key] = 'true';
      continue;
    }
    out[key] = next;
    i += 1;
  }
  return out;
}

function base64url(text) {
  return Buffer.from(text, 'utf8').toString('base64url');
}

function detectLocalIpv4() {
  const nets = os.networkInterfaces();
  for (const entries of Object.values(nets)) {
    for (const entry of entries || []) {
      if (entry.family === 'IPv4' && !entry.internal) return entry.address;
    }
  }
  return '127.0.0.1';
}

function buildPayload(args) {
  const label = args.label || os.hostname();
  const mode = args.mode || 'tailscale';
  const port = Number(args.port || 8787);
  const host = args.host || detectLocalIpv4();
  const scheme = args.scheme || 'http';
  const token = args.token || '';
  const poll = Math.max(10, Number(args.poll || 10));
  const suggestedDeviceId = args.deviceId || 'android-main-phone';
  const expiresMinutes = Math.max(1, Number(args.expiresMinutes || 30));
  const generatedAt = new Date();
  const expiresAt = new Date(generatedAt.getTime() + expiresMinutes * 60 * 1000);
  return {
    type: 'android-companion-pairing',
    version: 1,
    label,
    transport: {
      mode,
      base_url: `${scheme}://${host}:${port}`,
      token,
      poll_interval_seconds: poll,
    },
    device: {
      suggested_device_id: suggestedDeviceId,
    },
    meta: {
      generated_at: generatedAt.toISOString(),
      expires_at: expiresAt.toISOString(),
    },
  };
}

const args = parseArgs(process.argv);
const payload = buildPayload(args);
const link = `acpair://v1/${base64url(JSON.stringify(payload))}`;
const outPath = path.resolve(args.out || path.join(process.cwd(), 'tmp', 'android-companion-pairing.html'));
fs.mkdirSync(path.dirname(outPath), { recursive: true });

const html = `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>Android Companion Pairing</title>
  <style>
    body { font-family: Arial, sans-serif; background:#111827; color:#f9fafb; margin:0; padding:32px; }
    .wrap { max-width:960px; margin:0 auto; }
    .card { background:#1f2937; border-radius:16px; padding:24px; box-shadow:0 10px 30px rgba(0,0,0,.35); }
    .grid { display:grid; grid-template-columns: minmax(320px, 420px) 1fr; gap:24px; align-items:start; }
    .qr { background:white; padding:16px; border-radius:12px; min-height:320px; display:flex; align-items:center; justify-content:center; }
    pre, textarea { width:100%; background:#0b1220; color:#d1fae5; border:1px solid #374151; border-radius:12px; padding:12px; box-sizing:border-box; }
    textarea { min-height:120px; }
    button { background:#10b981; color:#052e16; border:none; border-radius:10px; padding:12px 16px; font-weight:700; cursor:pointer; }
    a { color:#93c5fd; word-break:break-all; }
    .muted { color:#9ca3af; }
  </style>
  <script src="https://cdn.jsdelivr.net/npm/qrcode/build/qrcode.min.js"></script>
</head>
<body>
  <div class="wrap">
    <div class="card">
      <h1>Android Companion Pairing</h1>
      <p class="muted">Open Android Companion, then import this acpair link by QR or paste.</p>
      <div class="grid">
        <div>
          <div id="qr" class="qr"></div>
        </div>
        <div>
          <p><strong>Label:</strong> ${payload.label}</p>
          <p><strong>Mode:</strong> ${payload.transport.mode}</p>
          <p><strong>Base URL:</strong> ${payload.transport.base_url}</p>
          <p><strong>Poll:</strong> ${payload.transport.poll_interval_seconds}s</p>
          <p><strong>Expires:</strong> ${payload.meta.expires_at}</p>
          <p><a href="${link}">${link}</a></p>
          <p><button onclick="navigator.clipboard.writeText(document.getElementById('pair').value)">Copy pairing link</button></p>
          <textarea id="pair" readonly>${link}</textarea>
          <pre>${JSON.stringify(payload, null, 2)}</pre>
        </div>
      </div>
    </div>
  </div>
  <script>
    QRCode.toCanvas(document.createElement('canvas'), document.getElementById('pair').value, { width: 360 }, function (err, canvas) {
      const target = document.getElementById('qr');
      if (err) {
        target.innerHTML = '<p style="color:#111">QR render failed. Use the link text instead.</p>';
        return;
      }
      target.innerHTML = '';
      target.appendChild(canvas);
    });
  </script>
</body>
</html>`;

fs.writeFileSync(outPath, html, 'utf8');
console.log(JSON.stringify({ ok: true, outPath, link, payload }, null, 2));

if (args.open === 'true') {
  const escaped = outPath.replace(/'/g, "''");
  execSync(`start "" "${escaped}"`, { shell: 'cmd.exe', stdio: 'ignore' });
}
