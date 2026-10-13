import { readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { buildUxFixtures } from './fixtures/ux.mjs';

const path = fileURLToPath(new URL('../test/fixtures/ux.json', import.meta.url));
const expected = JSON.stringify(buildUxFixtures(), null, 2) + '\n';
if (process.argv.slice(2).some((arg) => arg !== '--check')) {
  throw new Error('Usage: node tools\\ux-fixtures.mjs [--check]');
}
if (process.argv.includes('--check')) {
  if (await readFile(path, 'utf8') !== expected) throw new Error('UX fixtures are stale; run node tools\\ux-fixtures.mjs');
  console.log('UX fixtures match their reducer-built source');
} else {
  await writeFile(path, expected);
  console.log('Wrote deterministic UX fixtures');
}
