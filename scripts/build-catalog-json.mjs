import fs from 'node:fs';
import path from 'node:path';

const root = process.cwd();
const outDir = path.join(root, 'docs', 'catalog');
fs.mkdirSync(outDir, { recursive: true });

const readme = {
  generated: false,
  version: '0.1-scaffold',
  note: 'This is a scaffold placeholder. Generated JSON build will be implemented after real records and schema validation stabilize.'
};

fs.writeFileSync(path.join(outDir, 'index.json'), JSON.stringify(readme, null, 2));
console.log('Wrote docs/catalog/index.json scaffold');
