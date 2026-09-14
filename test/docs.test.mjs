// The README makes claims about code, and shows images that live on disk. Both can rot
// without anything else failing.
//
// This is deliberately three small assertions rather than a prose linter. A doc test that
// tries to police wording becomes a tax on every future PR and gets deleted; one that only
// catches a broken image link and a stale constant keeps earning its place. The prose is the
// job of .claude/skills/update-readme.

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { DIMENSIONS } from '../src/core/dimensions.js';
import { HARD_TURN_CEILING, SOFT_TURN_CEILING } from '../src/core/engine.js';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const README = readFileSync(join(ROOT, 'README.md'), 'utf8');

/** Every local target the README points at: markdown links, img src, and picture srcset. */
function localTargets(md) {
  const found = new Set();
  const add = (raw) => {
    if (!raw) return;
    const path = raw.trim().split('#')[0];
    // Skip absolute URLs, anchors, and mailto.
    if (!path || /^(https?:|mailto:|#)/.test(path)) return;
    found.add(path);
  };
  for (const m of md.matchAll(/\]\(([^)\s]+)\)/g)) add(m[1]);
  for (const m of md.matchAll(/\bsrc="([^"]+)"/g)) add(m[1]);
  for (const m of md.matchAll(/\bsrcset="([^"]+)"/g)) add(m[1]);
  return [...found];
}

test('every file the README links to or shows actually exists', () => {
  const targets = localTargets(README);
  // A README that references nothing local means the regexes above stopped matching, which
  // would make this whole test pass while checking nothing.
  assert.ok(targets.length >= 10, `only found ${targets.length} local targets`);

  const missing = targets.filter((p) => !existsSync(join(ROOT, p)));
  assert.deepEqual(missing, [], `README points at files that do not exist: ${missing.join(', ')}`);
});

test('the constants the README states match the source it states them about', () => {
  // Written as "seven dimensions" and "a built-in bank of 21", so match the words rather
  // than digits for the first one.
  assert.match(README, /seven dimensions/i);
  assert.equal(DIMENSIONS.length, 7, 'DIMENSIONS changed; the README still says seven');

  const bank = DIMENSIONS.reduce((n, d) => n + d.bank.length, 0);
  assert.match(
    README,
    new RegExp(`built-in bank of ${bank}\\b`),
    `the bank holds ${bank} questions; the README says otherwise`,
  );

  assert.match(
    README,
    new RegExp(`hard ceiling at ${HARD_TURN_CEILING}\\b`),
    `HARD_TURN_CEILING is ${HARD_TURN_CEILING}; the README says otherwise`,
  );
  assert.match(
    README,
    new RegExp(`soft ceiling at ${SOFT_TURN_CEILING} questions`),
    `SOFT_TURN_CEILING is ${SOFT_TURN_CEILING}; the README says otherwise`,
  );
});

test('every source file the architecture doc points at exists', () => {
  // The one mechanical check on docs/ARCHITECTURE.md, and deliberately the only one. That
  // file cites code by `file.js` plus a symbol name rather than a line number, so a moved
  // symbol cannot rot it — but a deleted or renamed FILE can, silently, and this catches
  // exactly that. Policing its prose would be the tax this suite's header warns about.
  const doc = readFileSync(join(ROOT, 'docs', 'ARCHITECTURE.md'), 'utf8');
  const paths = new Set();
  for (const m of doc.matchAll(/`((?:src|test|tools|docs|\.github)\/[^`\s]+\.\w+)`/g)) {
    paths.add(m[1]);
  }
  assert.ok(paths.size >= 15, `only found ${paths.size} cited paths; the regex stopped matching`);

  const missing = [...paths].filter((p) => !existsSync(join(ROOT, p)));
  assert.deepEqual(missing, [], `ARCHITECTURE.md cites files that do not exist: ${missing.join(', ')}`);
});

test('the worked example quoted in the README is the one on disk', () => {
  const example = join(ROOT, 'docs', 'examples', 'remember-names.md');
  assert.ok(existsSync(example), 'docs/examples/remember-names.md is missing; run npm run screenshots');
  const generated = readFileSync(example, 'utf8');

  // The README quotes an excerpt of the generated export. If someone edits the excerpt to
  // read better, or regenerates the example and the excerpt drifts, this catches it — the
  // point of the example is that it is real output, not that it reads well.
  const quoted = [
    '_2026-09-13 · 8 questions · coverage 86% · synthesised by Claude_',
    'It fails on sight "if it made me type while',
    '"Three seconds, one thumb, and it has to work with no signal."',
  ];
  for (const line of quoted) {
    assert.ok(README.includes(line), `the README no longer quotes: ${line}`);
    assert.ok(generated.includes(line), `the generated example no longer contains: ${line}`);
  }
});
