import fs from 'node:fs';
import path from 'node:path';

const root = process.cwd();
const catalogDir = path.join(root, 'catalog', 'apps');

function walk(dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(full, out);
    else out.push(full);
  }
  return out;
}

function fail(message) {
  console.error(`CATALOG VALIDATION FAILED: ${message}`);
  process.exitCode = 1;
}

if (!fs.existsSync(catalogDir)) {
  fail('catalog/apps directory not found');
} else {
  const files = walk(catalogDir).filter((p) => p.endsWith('.yml') || p.endsWith('.yaml'));
  if (files.length === 0) fail('no catalog yaml files found');

  const capabilityFiles = files.filter((p) => p.includes(`${path.sep}capabilities${path.sep}`));
  const seen = new Set();
  for (const file of capabilityFiles) {
    const key = path.basename(file);
    if (seen.has(key)) {
      fail(`duplicate capability filename detected: ${key}`);
    }
    seen.add(key);
  }
}

if (!process.exitCode) {
  console.log('Catalog scaffold validation OK');
}
