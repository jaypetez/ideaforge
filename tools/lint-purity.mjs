// src/core/ must stay platform-free so the Phase-2 PWA port is a copy, not a rewrite.
// Without this check, core/ acquires a `document.` reference within a week.
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

const DIR = new URL('../src/core/', import.meta.url).pathname;
const BANNED = [
  'window', 'document', 'localStorage', 'sessionStorage',
  'claude', 'navigator', 'fetch', 'alert',
];

let failures = 0;
for (const file of readdirSync(DIR).filter((f) => f.endsWith('.js'))) {
  const src = readFileSync(join(DIR, file), 'utf8');
  src.split('\n').forEach((line, i) => {
    // The ban is on real references, so strip comments and string literals first —
    // otherwise the word "claude" inside a mode name or a label trips it.
    const code = line
      .replace(/\/\/.*$/, '')
      .replace(/\/\*[\s\S]*?\*\//g, '')
      .replace(/'(?:[^'\\]|\\.)*'/g, "''")
      .replace(/"(?:[^"\\]|\\.)*"/g, '""')
      .replace(/`(?:[^`\\]|\\.)*`/g, '``');
    for (const bad of BANNED) {
      if (new RegExp('\\b' + bad + '\\b').test(code)) {
        console.error(`${file}:${i + 1}  banned reference "${bad}"\n    ${line.trim()}`);
        failures++;
      }
    }
  });
}
if (failures) {
  console.error(`\nlint:purity FAILED — ${failures} platform reference(s) in src/core/.`);
  process.exit(1);
}
console.log('lint:purity ok — src/core/ is platform-free.');
