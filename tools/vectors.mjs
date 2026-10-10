// Golden vectors: the JS core's behaviour, frozen as JSON the Kotlin port must replay.
//
//   npm run vectors            rewrite test/vectors/*.json
//   node tools/vectors.mjs --check   exit 1 if any file on disk is stale
//
// The Android app is a Kotlin rewrite of src/core, src/runtime and src/providers. Reading
// both side by side is not how parity is kept: a prompt that differs by one byte silently
// never hits the provider cache, and a ratchet that is one comparison looser stops the
// interview early. So every family below records real calls into the JS — inputs taken
// from scripted interviews plus hand-written hostile cases — and the Kotlin tests replay
// the same calls and demand the same outputs (android/core/src/test).
//
// A case is `{name, fn, args, expect}` where `expect` is `{value}` or `{throws}`. Async
// families (run-turn, drive-loop, provider requests) record a scenario instead; their shape
// is documented where they are built. Everything is deterministic: ids, clocks and provider
// replies are injected, so a second run writes identical bytes (test/vectors.test.mjs).
//
// When a JS change makes this regenerate, the Android build goes red until the port follows.
// That is the point; do not "fix" it by loosening a Kotlin consumer.

import { mkdirSync, readFileSync, readdirSync, writeFileSync, rmSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { FAMILIES } from './fixtures/vectors.mjs';

export const VECTORS_VERSION = 1;
export const ROOT = fileURLToPath(new URL('..', import.meta.url));
export const VECTOR_DIR = join(ROOT, 'test', 'vectors');

/** Stable bytes: two-space indent, LF, trailing newline. */
export const serialize = (value) => `${JSON.stringify(value, null, 2)}\n`;

const isSession = (v) => v && typeof v === 'object' && !Array.isArray(v)
  && typeof v.id === 'string' && 'schema' in v && Array.isArray(v.turns) && 'coverage' in v;

/**
 * Sessions recur across hundreds of cases, so inputs refer to them instead of repeating
 * them: any session inside `args` (or a scenario's `input`) becomes `{"$ref": key}`, and the
 * session itself is stored once under `fixtures[key]`. The key is a content hash, so it is
 * stable across runs. A consumer resolves every `$ref` before calling the function.
 * Expectations are never shortened; what a call must return stays spelled out in full.
 */
function dedupe(cases) {
  const fixtures = {};
  const swap = (v) => {
    if (Array.isArray(v)) return v.map(swap);
    if (isSession(v)) {
      const text = JSON.stringify(v);
      const key = `${v.id}@${v.rev}#${createHash('sha256').update(text).digest('hex').slice(0, 10)}`;
      fixtures[key] = v;
      return { $ref: key };
    }
    if (v && typeof v === 'object') return Object.fromEntries(Object.entries(v).map(([k, x]) => [k, swap(x)]));
    return v;
  };
  const out = cases.map((c) => ({
    ...c,
    ...(c.args ? { args: swap(c.args) } : {}),
    ...(c.input ? { input: swap(c.input) } : {}),
  }));
  const sorted = Object.fromEntries(Object.keys(fixtures).sort().map((k) => [k, fixtures[k]]));
  return { fixtures: sorted, cases: out };
}

/** @returns {Promise<Map<string, string>>} file name → exact file contents */
export async function buildVectors() {
  const out = new Map();
  for (const [family, build] of Object.entries(FAMILIES)) {
    const { source, cases: raw } = await build();
    const names = new Set();
    for (const c of raw) {
      if (names.has(c.name)) throw new Error(`${family}: duplicate case name "${c.name}"`);
      names.add(c.name);
    }
    if (!raw.length) throw new Error(`${family}: no cases`);
    const { fixtures, cases } = dedupe(raw);
    out.set(`${family}.json`, serialize({ vectorsVersion: VECTORS_VERSION, family, source, fixtures, cases }));
  }
  return out;
}

function onDisk() {
  const files = new Map();
  let names = [];
  try { names = readdirSync(VECTOR_DIR).filter((f) => f.endsWith('.json')); } catch { /* none yet */ }
  for (const f of names) files.set(f, readFileSync(join(VECTOR_DIR, f), 'utf8'));
  return files;
}

/** @returns {string[]} human-readable problems; empty when disk matches a fresh build */
export async function staleVectors() {
  const fresh = await buildVectors();
  const disk = onDisk();
  const problems = [];
  for (const [f, text] of fresh) {
    if (!disk.has(f)) problems.push(`missing ${f}`);
    else if (disk.get(f) !== text) problems.push(`stale ${f}`);
  }
  for (const f of disk.keys()) if (!fresh.has(f)) problems.push(`unexpected ${f}`);
  return problems;
}

async function main() {
  if (process.argv.includes('--check')) {
    const problems = await staleVectors();
    if (problems.length) {
      console.error(`golden vectors are out of date (${problems.join(', ')}); run: npm run vectors`);
      process.exit(1);
    }
    console.log('golden vectors are current');
    return;
  }
  const fresh = await buildVectors();
  rmSync(VECTOR_DIR, { recursive: true, force: true });
  mkdirSync(VECTOR_DIR, { recursive: true });
  let cases = 0;
  for (const [f, text] of fresh) {
    writeFileSync(join(VECTOR_DIR, f), text);
    cases += JSON.parse(text).cases.length;
  }
  console.log(`wrote ${fresh.size} vector files, ${cases} cases, to test/vectors/`);
}

if (process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1])) {
  await main();
}
