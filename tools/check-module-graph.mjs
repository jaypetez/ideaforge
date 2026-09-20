// Cross-platform module-graph check for the shipped code.
//
// The browser probes cover src/ui/ by loading the real page. Everything else should remain
// importable in plain Node, which catches broken relative imports even when no test happened
// to exercise the path.

import { spawnSync } from 'node:child_process';
import { readdir } from 'node:fs/promises';
import { join, relative } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const BROWSER_ONLY = /^src\/ui\//;

const toPosix = (path) => path.replace(/\\/g, '/');

async function walk(dir) {
  const out = [];
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const file = join(dir, entry.name);
    if (entry.isDirectory()) out.push(...await walk(file));
    else out.push(file);
  }
  return out;
}

function rel(file) {
  return toPosix(relative(ROOT, file));
}

function runNode(args, label) {
  const out = spawnSync(process.execPath, args, {
    cwd: ROOT,
    encoding: 'utf8',
  });
  if (out.status === 0) return;
  const detail = [out.stdout, out.stderr].filter(Boolean).join('\n').trim();
  throw new Error(`${label} failed${detail ? `\n${detail}` : ''}`);
}

async function main() {
  const srcFiles = (await walk(join(ROOT, 'src')))
    .filter((file) => file.endsWith('.js'))
    .sort();
  const parseOnly = [join(ROOT, 'sw.js'), ...srcFiles];
  for (const file of parseOnly) runNode(['--check', file], `syntax check for ${rel(file)}`);

  const importable = srcFiles
    .map(rel)
    .filter((file) => !BROWSER_ONLY.test(file))
    .sort();

  const script = importable
    .map((file) => `await import(${JSON.stringify(pathToFileURL(join(ROOT, ...file.split('/'))).href)});`)
    .join('\n');

  runNode(['--input-type=module', '--eval', script], 'module graph import');
  console.log(`module graph OK (${parseOnly.length} parsed, ${importable.length} imported)`);
}

await main();
