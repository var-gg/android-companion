#!/usr/bin/env node
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

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

const args = parseArgs(process.argv);
const baseUrl = (args.baseUrl || 'http://127.0.0.1:8787').replace(/\/$/, '');
const deviceId = args.deviceId || '';
const requestId = args.requestId || '';
const token = args.token || '';
const timeoutMs = Math.max(1000, Number(args.timeoutMs || 60000));
const intervalMs = Math.max(500, Number(args.intervalMs || 2000));

if (!deviceId) {
  console.error('Missing --deviceId');
  process.exit(1);
}

const headers = { accept: 'application/json' };
if (token) headers.authorization = `Bearer ${token}`;

const started = Date.now();
while (Date.now() - started < timeoutMs) {
  const response = await fetch(`${baseUrl}/api/v1/results`, { headers });
  const json = await response.json();
  const match = (json.results || []).find(item => item.device_id === deviceId && (!requestId || item.result?.request_id === requestId));
  if (match) {
    console.log(JSON.stringify({ ok: true, match }, null, 2));
    process.exit(0);
  }
  await sleep(intervalMs);
}

console.error(JSON.stringify({ ok: false, error: 'timeout_waiting_for_result', deviceId, requestId, timeoutMs }, null, 2));
process.exit(2);
