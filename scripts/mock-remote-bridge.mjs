#!/usr/bin/env node
import http from 'node:http';
import { randomUUID } from 'node:crypto';

const port = Number(process.env.PORT || 8787);
const token = process.env.ANDROID_COMPANION_TOKEN || '';
const devices = new Map();
const queues = new Map();
const results = [];
const commandHistory = [];

function send(res, code, body) {
  const text = JSON.stringify(body, null, 2);
  res.writeHead(code, { 'content-type': 'application/json; charset=utf-8' });
  res.end(text);
}

function unauthorized(res) {
  send(res, 401, { ok: false, error: 'unauthorized' });
}

function checkAuth(req, res) {
  if (!token) return true;
  const header = req.headers.authorization || '';
  if (header === `Bearer ${token}`) return true;
  unauthorized(res);
  return false;
}

function readJson(req) {
  return new Promise((resolve, reject) => {
    let data = '';
    req.on('data', chunk => { data += chunk; });
    req.on('end', () => {
      try {
        resolve(data ? JSON.parse(data) : {});
      } catch (error) {
        reject(error);
      }
    });
    req.on('error', reject);
  });
}

const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url, `http://localhost:${port}`);

    if (req.method === 'GET' && url.pathname === '/') {
      return send(res, 200, {
        ok: true,
        service: 'android-companion-mock-remote-bridge',
        devices: Array.from(devices.keys()),
        queuedCommands: Array.from(queues.entries()).map(([deviceId, items]) => ({ deviceId, count: items.length })),
        resultCount: results.length,
        commandHistoryCount: commandHistory.length,
      });
    }

    if (!checkAuth(req, res)) return;

    if (req.method === 'POST' && url.pathname === '/api/v1/register') {
      const body = await readJson(req);
      devices.set(body.device_id, { ...body.device, registered_at: new Date().toISOString() });
      if (!queues.has(body.device_id)) queues.set(body.device_id, []);
      return send(res, 200, { ok: true, registered: true, device_id: body.device_id });
    }

    if (req.method === 'POST' && url.pathname === '/api/v1/heartbeat') {
      const body = await readJson(req);
      const existing = devices.get(body.device_id) || {};
      devices.set(body.device_id, { ...existing, heartbeat: body.summary, last_seen_at: new Date().toISOString() });
      return send(res, 200, { ok: true });
    }

    if (req.method === 'GET' && url.pathname === '/api/v1/commands/next') {
      const deviceId = url.searchParams.get('device_id');
      const queue = queues.get(deviceId) || [];
      const command = queue.shift() || null;
      queues.set(deviceId, queue);
      if (command) {
        commandHistory.unshift({
          event: 'delivered',
          device_id: deviceId,
          command_id: command.id,
          request_id: command.request_id,
          action: command.action,
          at: new Date().toISOString(),
        });
      }
      return send(res, 200, { ok: true, command });
    }

    if (req.method === 'POST' && url.pathname === '/api/v1/commands/enqueue') {
      const body = await readJson(req);
      const deviceId = body.device_id;
      const queue = queues.get(deviceId) || [];
      const command = {
        id: randomUUID(),
        request_id: body.request_id || randomUUID(),
        action: body.action,
        params: body.params || {},
        queued_at: new Date().toISOString(),
      };
      queue.push(command);
      queues.set(deviceId, queue);
      commandHistory.unshift({
        event: 'enqueued',
        device_id: deviceId,
        command,
        at: new Date().toISOString(),
      });
      return send(res, 200, { ok: true, command });
    }

    const resultMatch = url.pathname.match(/^\/api\/v1\/commands\/([^/]+)\/result$/);
    if (req.method === 'POST' && resultMatch) {
      const body = await readJson(req);
      const item = {
        command_id: resultMatch[1],
        device_id: body.device_id,
        result: body.result,
        received_at: new Date().toISOString(),
      };
      results.unshift(item);
      commandHistory.unshift({
        event: 'result',
        device_id: body.device_id,
        command_id: resultMatch[1],
        request_id: body.result?.request_id || null,
        action: body.result?.action || null,
        ok: body.result?.ok ?? null,
        at: item.received_at,
      });
      return send(res, 200, { ok: true, stored: true });
    }

    if (req.method === 'GET' && url.pathname === '/api/v1/results') {
      return send(res, 200, { ok: true, results });
    }

    if (req.method === 'GET' && url.pathname === '/api/v1/history') {
      return send(res, 200, { ok: true, history: commandHistory });
    }

    if (req.method === 'GET' && url.pathname === '/api/v1/devices') {
      return send(res, 200, { ok: true, devices: Object.fromEntries(devices.entries()) });
    }

    send(res, 404, { ok: false, error: 'not_found' });
  } catch (error) {
    send(res, 500, { ok: false, error: error.message || String(error) });
  }
});

server.listen(port, () => {
  console.log(`android-companion mock remote bridge listening on http://0.0.0.0:${port}`);
  console.log(`auth token ${token ? 'enabled' : 'disabled'}; set ANDROID_COMPANION_TOKEN to require Bearer auth`);
});
