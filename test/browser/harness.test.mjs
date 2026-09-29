import test from 'node:test';
import assert from 'node:assert/strict';
import {
  attachReporter, createReporter, monitorProbe, parseOptions, REPORT_BINDING, selectProbes,
} from '../../tools/browser-check.mjs';

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

test('worker-controlled fetch failures cannot take down reporting or hide a failed assertion', async (t) => {
  t.mock.method(globalThis, 'fetch', () => Promise.reject(new TypeError('Failed to fetch')));
  await assert.rejects(fetch('/__result'), /Failed to fetch/);
  const emitted = [];
  const reporter = createReporter(PROBE, (message) => emitted.push(JSON.parse(message)));
  reporter.record(result('behavior completed'));
  reporter.record(result('real failure', false, 'must stay visible'));
  reporter.complete();
  assert.equal(globalThis.fetch.mock.callCount(), 1, 'reporting must not use the application fetch path');
  assert.deepEqual(emitted.map((message) => message.done), [false, false, true]);
  assert.deepEqual(emitted.at(-1).results, [
    result('behavior completed'), result('real failure', false, 'must stay visible'),
  ]);
});

test('an emitter failure latches and cannot be retried into apparent completion', () => {
  const failure = new Error('report channel unavailable');
  let attempts = 0;
  const reporter = createReporter(PROBE, () => { attempts++; throw failure; });
  assert.throws(() => reporter.record(result('first')), (error) => error === failure);
  assert.throws(() => reporter.record(result('second')), (error) => error === failure);
  assert.throws(() => reporter.complete(), (error) => error === failure);
  assert.equal(attempts, 1, 'there is no automatic retry or success-shaped fallback');
});

function reportChannel() {
  const handlers = new Map();
  return {
    ws: new EventTarget(),
    commands: [],
    on(name, handler) { handlers.set(name, handler); return () => handlers.delete(name); },
    async send(method, params) { this.commands.push({ method, params }); },
    emit(message) {
      handlers.get('Runtime.bindingCalled')?.({
        name: REPORT_BINDING, payload: typeof message === 'string' ? message : JSON.stringify(message),
      });
    },
  };
}

test('CDP reporting preserves ordered assertions and waits for an explicit completion', async (t) => {
  const monitor = monitored(t);
  const channel = reportChannel();
  const dispose = await attachReporter(channel, monitor);
  assert.deepEqual(channel.commands, [
    { method: 'Runtime.enable', params: undefined },
    { method: 'Runtime.addBinding', params: { name: REPORT_BINDING } },
  ]);
  channel.emit(payload([result('first', false, 'actual probe failure')]));
  channel.emit(payload([result('first', false, 'actual probe failure'), result('second')], true));
  const outcome = await monitor.promise;
  assert.equal(outcome.error, null);
  assert.equal(outcome.results[0].ok, false);
  dispose();
  channel.ws.dispatchEvent(new Event('close'));
  assert.equal((await monitor.promise).error, null, 'normal teardown cannot invalidate completed results');
});

test('a broken CDP reporting connection fails closed with partial results', async (t) => {
  const monitor = monitored(t);
  const channel = reportChannel();
  const dispose = await attachReporter(channel, monitor);
  channel.emit(payload([result('last completed claim')]));
  channel.ws.dispatchEvent(new Event('close'));
  const outcome = await monitor.promise;
  assert.match(outcome.error.message, /report channel closed/);
  assert.deepEqual(outcome.results, [result('last completed claim')]);
  dispose();
});

test('invalid binding data fails the reporting channel rather than being ignored', async (t) => {
  const monitor = monitored(t);
  const channel = reportChannel();
  const dispose = await attachReporter(channel, monitor);
  channel.emit('{invalid JSON');
  assert.match((await monitor.promise).error.message, /invalid browser report/);
  dispose();
});
