#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
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
const version = args.version || '0.2.0-alpha7';
const tag = version.startsWith('v') ? version : `v${version}`;
const versionName = tag.replace(/^v/, '');
const versionCode = Number(args.versionCode || 7);
const minSupportedVersionCode = Number(args.minSupportedVersionCode || versionCode - 1);
const forceUpdate = (args.forceUpdate || 'false') === 'true';
const debug = (args.debug || 'false') === 'true';
const assetName = debug ? `android-companion-${tag}-debug.apk` : `android-companion-${tag}.apk`;
const repo = args.repo || 'var-gg/android-companion';
const notes = args.notes || 'Adds Tailscale-first pairing import, in-app QR scanning, remote connection testing, and pairing payload hardening for the Android Companion remote bootstrap flow.';
const manifest = {
  tag_name: tag,
  version_name: versionName,
  version_code: versionCode,
  min_supported_version_code: minSupportedVersionCode,
  force_update: forceUpdate,
  apk_url: `https://github.com/${repo}/releases/download/${tag}/${assetName}`,
  release_url: `https://github.com/${repo}/releases/tag/${tag}`,
  notes,
};
const outPath = path.resolve(args.out || 'update-manifest.json');
fs.writeFileSync(outPath, JSON.stringify(manifest, null, 2) + '\n', 'utf8');
console.log(JSON.stringify({ ok: true, outPath, manifest }, null, 2));
