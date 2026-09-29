import test from 'node:test';
import assert from 'node:assert/strict';
import { monitorProbe, parseOptions, selectProbes } from '../../tools/browser-check.mjs';

const PROBE = 'voice-stage.browser.mjs';
const OTHER = 'turn-lifecycle.browser.mjs';
const result = (name, ok = true, detail = '') => ({ probe: PROBE, name, ok, detail });
const payload = (results, done = false, probe = PROBE) => ({ probe, results, done });

function monitored(t, options = {}) {
  t.mock.timers.enable({ apis: ['setTimeout', 'Date'], now: 0 });
  const progress = [];
  const monitor = monitorProbe(PROBE, {
    timeoutMs: 180000, overallDeadline: 360000, onResults: (rows) => progress.push(...rows), ...options,
  });
  t.after(() => monitor.fail(new Error('test cleanup')));
  return { ...monitor, progress };
}

test('default browser selection preserves every probe; --probe selects one exact name', () => {
  const probes = [OTHER, PROBE];
  const options = parseOptions(['--required']);
  assert.deepEqual(selectProbes(probes, options.probe), probes);
  assert.deepEqual(selectProbes(probes, parseOptions(['--probe', PROBE, '--required']).probe), [PROBE]);
  assert.throws(() => selectProbes(probes, '*.browser.mjs'), /unknown browser probe/);
  assert.throws(() => selectProbes(probes, 'voice-stage'), /unknown browser probe/);
  assert.throws(() => selectProbes([], PROBE), /unknown browser probe/);
});

test('missing, duplicate, and unsupported CLI options fail rather than running a different suite', () => {
  for (const args of [
    ['--probe'], ['--probe', '--required'], ['--probe', PROBE, '--probe', OTHER], ['--skip-policy'],
  ]) assert.throws(() => parseOptions(args));
  assert.equal(parseOptions(['--help']).help, true);
});

test('new assertions keep a progressing probe alive beyond 180 seconds', async (t) => {
  const monitor = monitored(t);
  let settled = false;
  monitor.promise.then(() => { settled = true; });
  t.mock.timers.tick(170000);
  monitor.accept(payload([result('first')]));
  t.mock.timers.tick(170000);
  await Promise.resolve();
  assert.equal(settled, false);
  monitor.accept(payload([result('first'), result('second')], true));
  const outcome = await monitor.promise;
  assert.equal(outcome.error, null);
  assert.equal(outcome.elapsed, 340000);
  assert.deepEqual(monitor.progress, [result('first'), result('second')]);
});

test('a stalled probe retains and publishes failure details before its no-progress timeout', async (t) => {
  const monitor = monitored(t);
  const failure = result('Pause released all tracks', false, 'one audio track remained live');
  monitor.accept(payload([failure]));
  assert.deepEqual(monitor.progress, [failure], 'diagnostics are available without a final report');
  t.mock.timers.tick(179999);
  monitor.accept(payload([failure]));
  t.mock.timers.tick(1);
  const outcome = await monitor.promise;
  assert.match(outcome.error.message, /no new assertion for 180000ms/);
  assert.match(outcome.error.message, /1 checks received; last: Pause released all tracks/);
  assert.deepEqual(outcome.results, [failure]);
});

test('steady progress cannot extend the finite overall deadline', async (t) => {
  const monitor = monitored(t);
  const rows = [];
  for (let i = 0; i < 3; i++) {
    t.mock.timers.tick(100000);
    rows.push(result(`check ${i}`));
    monitor.accept(payload(rows.slice()));
  }
  t.mock.timers.tick(60000);
  const outcome = await monitor.promise;
  assert.match(outcome.error.message, /overall browser-run deadline/);
  assert.equal(outcome.results.length, 3);
});

test('late results from a different probe cannot reset the current watchdog', async (t) => {
  const monitor = monitored(t);
  t.mock.timers.tick(179999);
  assert.equal(monitor.accept(payload([result('late')], true, OTHER)), false);
  t.mock.timers.tick(1);
  assert.match((await monitor.promise).error.message, /last: \(none\)/);
  assert.deepEqual(monitor.progress, []);
});

test('empty, malformed, and rewritten reports fail closed', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout', 'Date'], now: 0 });
  for (const report of [
    payload([], true),
    payload([{ probe: PROBE, name: 'missing boolean', detail: '' }], true),
    { probe: PROBE, done: true, results: null },
  ]) {
    const monitor = monitorProbe(PROBE, { timeoutMs: 100, overallDeadline: 1000 });
    monitor.accept(report);
    assert.ok((await monitor.promise).error);
  }
  const monitor = monitorProbe(PROBE, { timeoutMs: 100, overallDeadline: 1000 });
  monitor.accept(payload([result('first', false, 'original failure')]));
  assert.equal(monitor.accept(payload([result('first', true)], true)), false);
  const outcome = await monitor.promise;
  assert.match(outcome.error.message, /rewritten/);
  assert.equal(outcome.results[0].detail, 'original failure');
});

test('completion cancels both timers and never contaminates the next probe', async (t) => {
  const first = monitored(t);
  first.accept(payload([result('done')], true));
  assert.equal((await first.promise).error, null);
  t.mock.timers.tick(170000);
  const next = monitorProbe(OTHER, { timeoutMs: 180000, overallDeadline: 600000 });
  t.mock.timers.tick(170000);
  assert.equal(first.accept(payload([result('late failure', false)], true)), false);
  next.accept({ probe: OTHER, done: true,
    results: [{ probe: OTHER, name: 'done', ok: true, detail: '' }] });
  assert.equal((await next.promise).error, null);
  t.mock.timers.tick(1000000);
  assert.deepEqual(first.progress, [result('done')]);
});
