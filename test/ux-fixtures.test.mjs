import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { buildUxFixtures, UX_STATES } from '../tools/fixtures/ux.mjs';
import { migrate, openTurn } from '../src/core/session.js';
import { buildExport } from '../src/core/markdown.js';
import { publishableFiles } from '../tools/assemble-site.mjs';
import { assertUxGeometry, requireUxFont } from './browser/fixtures/ux.js';
import { captureOptions, finishUxEvidence } from '../tools/ux-captures.mjs';

test('UX fixture inventory is deterministic, current and usable by both front ends', async () => {
  const expected = buildUxFixtures();
  assert.deepEqual(expected, buildUxFixtures());
  assert.equal(await readFile(new URL('./fixtures/ux.json', import.meta.url), 'utf8'),
    JSON.stringify(expected, null, 2) + '\n');
  assert.equal(expected.datasets.mixed.length, 12);
  assert.equal(expected.datasets.hundred.length, 100);
  assert.ok(expected.datasets.mixed.some((s) => s.archivedAt));
  assert.ok(expected.datasets.mixed.some((s) => s.status === 'done'));
  assert.ok(expected.datasets.mixed.some((s) => s.status === 'interviewing' && !s.archivedAt));
  assert.equal(expected.datasets.long[0].name.length, 120);
  assert.equal(expected.datasets.long[0].tags.length, 12);
  for (const rows of Object.values(expected.datasets)) {
    assert.equal(new Set(rows.map((s) => s.id)).size, rows.length);
    for (const session of rows) assert.deepEqual(migrate(session), session);
  }
  assert.equal(openTurn(expected.datasets.bank[0]).questionSource, 'bank');
  assert.equal(expected.datasets.long[0].synthesis.text, '');
  assert.match(buildExport(expected.datasets.long[1]), /\| Situation \| Recovery \|/);
  assert.match(buildExport(expected.datasets.long[1]), /```js/);
  assert.doesNotMatch(JSON.stringify(expected), /apiKey|ttsKey|sttKey|BEGIN PRIVATE KEY/);
});

test('every voice presentation phase has a labelled isolated fixture', async () => {
  const source = await readFile(new URL('../src/ui/voice-stage.js', import.meta.url), 'utf8');
  const labels = source.match(/const LABELS = \{([\s\S]*?)\n\};/)[1];
  const phases = [...labels.matchAll(/^\s+(\w+):/gm)].map((match) => match[1]).sort();
  assert.deepEqual(UX_STATES.filter((s) => s.phase && s.platform !== 'android').map((s) => s.phase).sort(), phases);
  assert.ok(UX_STATES.filter((s) => s.phase).every((s) => s.evidence === 'isolated-component'));
  assert.deepEqual(UX_STATES.find((s) => s.id === 'voice-writing'), {
    id: 'voice-writing', surface: 'voice', evidence: 'isolated-component', phase: 'writing', platform: 'android',
  });
  assert.equal(new Set(UX_STATES.map((s) => s.id)).size, UX_STATES.length);
});

test('UX tooling and serialized seeds cannot enter the assembled application', async () => {
  const published = await publishableFiles();
  assert.ok(published.length > 0);
  assert.ok(!published.some((file) => /(?:^|\/)(?:test|tools|android)\//.test(file)));
  assert.ok(!published.some((file) => /ux\.json$|ux-fixtures|ux-captures/.test(file)));
});

test('requested capture states and clipped or malformed geometry fail explicitly', () => {
  assert.throws(() => captureOptions(['--out', 'evidence', '--states', 'not-a-state']), /Unknown requested/);
  assert.throws(() => captureOptions(['--out', 'evidence', '--states', 'fresh,fresh']), /Duplicate/);
  assert.throws(() => captureOptions(['--out', 'evidence', '--states', 'voice-writing']), /native-only/);
  assert.ok(captureOptions(['--out', 'evidence']).states.every((s) => s.platform !== 'android'));
  assert.throws(() => captureOptions(['--out', 'evidence', '--text-scale', '3']), /Text scale/);
  assert.throws(() => captureOptions([]), /Supply --out/);
  const good = {
    state: 'fresh', width: 390, height: 844, pageWidth: 390,
    controls: [{ selector: '#b-start', visible: true, reachable: true, width: 120, height: 48 }],
  };
  assert.equal(assertUxGeometry(good), good);
  assert.throws(() => assertUxGeometry({ ...good, pageWidth: 420 }), /Page-wide overflow/);
  assert.throws(() => assertUxGeometry({ ...good, controls: [] }), /Missing requested/);
  assert.throws(() => assertUxGeometry({ ...good,
    controls: [{ ...good.controls[0], reachable: false }] }), /Clipped or inaccessible/);
  assert.throws(() => assertUxGeometry({ ...good,
    controls: [{ ...good.controls[0], width: NaN }] }), /Clipped or inaccessible/);
});

test('failed evidence writes still release the server and scratch tree and retain the original error', async () => {
  const original = new Error('capture failed');
  const disk = new Error('output disk is full');
  const calls = [];
  await assert.rejects(finishUxEvidence({
    primaryError: original,
    persist: async () => { calls.push('persist'); throw disk; },
    close: async () => { calls.push('close'); },
    remove: async () => { calls.push('remove'); },
  }), (error) => error instanceof AggregateError
    && error.errors[0] === original && error.errors[1] === disk);
  assert.deepEqual(calls, ['persist', 'close', 'remove']);
});

test('capture cleanup attempts every operation and does not replace a single original failure', async () => {
  const original = new Error('capture failed');
  await assert.rejects(finishUxEvidence({
    primaryError: original, persist: async () => {}, close: async () => {}, remove: async () => {},
  }), (error) => error === original);
  const failures = ['persist', 'close', 'remove'].map((label) => new Error(label));
  const calls = [];
  await assert.rejects(finishUxEvidence({
    persist: async () => { calls.push('persist'); throw failures[0]; },
    close: async () => { calls.push('close'); throw failures[1]; },
    remove: async () => { calls.push('remove'); throw failures[2]; },
  }), (error) => {
    assert.ok(error instanceof AggregateError);
    assert.deepEqual(error.errors, failures);
    return true;
  });
  assert.deepEqual(calls, ['persist', 'close', 'remove']);
});

function fontDocument(faces, pending = false) {
  const registered = pending ? [] : [{ family: 'IBM Plex Sans' }];
  const link = { rel: pending ? 'preload' : 'stylesheet' };
  return {
    registered, link,
    getElementById: (id) => id === 'font-styles' ? link : null,
    fonts: {
      ready: Promise.resolve(),
      load: async () => registered.length ? faces : [],
      [Symbol.iterator]: () => registered.values(),
    },
  };
}

test('a loaded font in the discarded document cannot validate the reloaded document', async () => {
  await requireUxFont(fontDocument([{ status: 'loaded' }]));
  await assert.rejects(requireUxFont(fontDocument([])), /refusing fallback-font evidence/);
  await assert.rejects(requireUxFont(fontDocument([{ status: 'error' }])), /refusing fallback-font evidence/);
});

test('the UX font gate waits for delayed CSS activation and face registration', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const doc = fontDocument([{ status: 'loaded' }], true);
  const load = t.mock.method(doc.fonts, 'load');
  const result = requireUxFont(doc).then(() => ({ ok: true }), (error) => ({ error }));
  await Promise.resolve();
  await Promise.resolve();
  assert.equal(load.mock.calls.length, 0, 'a settled empty FontFaceSet is not loaded CSS');
  doc.link.rel = 'stylesheet';
  t.mock.timers.tick(25);
  await Promise.resolve();
  await Promise.resolve();
  assert.equal(load.mock.calls.length, 0, 'stylesheet activation alone does not register its faces');
  doc.registered.push({ family: 'IBM Plex Sans' });
  t.mock.timers.tick(25);
  assert.deepEqual(await result, { ok: true });
  assert.equal(load.mock.calls.length, 1);
});

test('the UX font gate fails within its existing bounded wait when CSS never arrives', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  let now = 0;
  t.mock.method(performance, 'now', () => now);
  const doc = fontDocument([{ status: 'loaded' }], true);
  const result = assert.rejects(requireUxFont(doc), /UX fixture did not reach font stylesheet/);
  await Promise.resolve();
  await Promise.resolve();
  now = 8001;
  t.mock.timers.tick(25);
  await result;
});
