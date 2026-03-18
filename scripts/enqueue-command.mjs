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

const args = parseArgs(process.argv);
const baseUrl = (args.baseUrl || 'http://127.0.0.1:8787').replace(/\/$/, '');
const deviceId = args.deviceId || '';
const action = args.action || 'health_ping';
const token = args.token || '';
const requestId = args.requestId || `req-${Date.now()}`;
const params = args.params ? JSON.parse(args.params) : {};

if (!deviceId) {
  console.error('Missing --deviceId');
  process.exit(1);
}

const headers = { 'content-type': 'application/json', accept: 'application/json' };
if (token) headers.authorization = `Bearer ${token}`;

const response = await fetch(`${baseUrl}/api/v1/commands/enqueue`, {
  method: 'POST',
  headers,
  body: JSON.stringify({ device_id: deviceId, request_id: requestId, action, params }),
});

const text = await response.text();
console.log(text);
if (!response.ok) process.exit(1);
