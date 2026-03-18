#!/usr/bin/env node
import os from 'node:os';
import process from 'node:process';

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

const args = parseArgs(process.argv);
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

if (token && !args.expiresMinutes) {
  console.error('When --token is provided, also pass --expiresMinutes for an explicit short-lived pairing window.');
  process.exit(1);
}

const payload = {
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

const json = JSON.stringify(payload);
const link = `acpair://v1/${base64url(json)}`;

console.log(JSON.stringify({ payload, link }, null, 2));
console.log('\nPAIRING_LINK=' + link);
