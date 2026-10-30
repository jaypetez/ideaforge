import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

import { buildVectors, staleVectors, VECTOR_DIR } from '../tools/vectors.mjs';

// The golden vectors are the contract the Kotlin port (android/core) is tested against. If
// they drift from the JS, the Android build is checking the port against a past that no
// longer exists — so a JS change that alters behaviour must regenerate them in the same PR.

test('golden vectors on disk match the JS core', async () => {
  const problems = await staleVectors();
  assert.deepEqual(problems, [], `golden vectors are out of date (${problems.join(', ')}); run: npm run vectors`);
});

test('golden vectors are deterministic', async () => {
  const a = await buildVectors();
  const b = await buildVectors();
  assert.deepEqual([...a.keys()], [...b.keys()]);
  for (const [file, text] of a) assert.equal(text, b.get(file), `${file} differs between two builds`);
});

// A family with no cases, or cases that only ever throw, would let a Kotlin consumer pass
// while checking nothing.
test('every vector family has cases, unique names, and resolvable fixtures', () => {
  const files = readdirSync(VECTOR_DIR).filter((f) => f.endsWith('.json'));
  assert.ok(files.length >= 10, `expected at least 10 families, found ${files.length}`);
  for (const file of files) {
    const doc = JSON.parse(readFileSync(join(VECTOR_DIR, file), 'utf8'));
    assert.equal(doc.vectorsVersion, 1, file);
    assert.ok(doc.cases.length > 0, `${file} has no cases`);
    assert.equal(new Set(doc.cases.map((c) => c.name)).size, doc.cases.length, `${file} repeats a case name`);
    assert.ok(doc.cases.some((c) => !c.expect.throws), `${file} only records failures`);
    const refs = JSON.stringify(doc.cases).match(/"\$ref":"[^"]+"/g) || [];
    for (const ref of refs) {
      const key = ref.slice('"$ref":"'.length, -1);
      assert.ok(key in doc.fixtures, `${file} refers to a missing fixture ${key}`);
    }
  }
});

// The invariants the port is most likely to get subtly wrong are pinned by name, so a
// careless edit to the fixtures cannot quietly drop them.
test('the load-bearing invariants are covered by named vectors', () => {
  const names = (family) => JSON.parse(readFileSync(join(VECTOR_DIR, `${family}.json`), 'utf8'))
    .cases.map((c) => c.name);
  const must = {
    session: ['ratchet: one level per turn', 'ratchet: never falls', 'ratchet: at most two dimensions rise',
      'covered without evidence is partial', 'migrate future schema',
      'covered with fabricated evidence is partial', 'model-only text cannot supply covered evidence',
      'chip evidence cannot be reused by a later turn', 'unedited draft evidence cannot be reused by a later turn',
      'Unicode user quote is accepted verbatim', 'covered credit retains real evidence after a fabricated claim'],
    'run-turn': ['chip answer caps the previous dimension, not the target', 'zero gain increments the streak',
      'provider error: bank fallback keeps zeroGainStreak', 'compound twice: unfixable',
      'fabricated quotes offer exhaustion after two scored turns', 'real user quote resets exhaustion'],
    speech: ['parseSpeech "I\'d skip this one if I could"', 'parseSpeech "we went over budget"'],
    'drive-loop': ['skip phrase inside an answer is an answer', 'chips are spoken separately'],
    backup: ['sessionsEqual key order ignored', 'parseBackup future version',
      'buildBackup session limit round trip', 'buildBackup session limit plus one refuses export',
      'buildBackup UTF-8 byte limit round trip', 'buildBackup UTF-8 byte limit plus one refuses export'],
    providers: ['request anthropic cache marker at the threshold'],
  };
  for (const [family, required] of Object.entries(must)) {
    const have = new Set(names(family));
    for (const name of required) assert.ok(have.has(name), `${family} is missing "${name}"`);
  }
});
