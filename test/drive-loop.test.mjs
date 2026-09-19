import test from 'node:test';
import assert from 'node:assert/strict';

import { createDriveLoop } from '../src/runtime/drive.js';

// ───────────────────────────────────────────── the hands-free loop
// The property under test is not "does it ask questions" — it is that it never comes to
// rest waiting for a tap. Every effect is injected, so a failure mode that would need a
// car, a dead microphone or a flaky network to reproduce is three lines of script here.
//
// The old loop `break`s on an empty capture and on any listen error, and the message it
// leaves behind says "tap the mic to try again". These tests are what stops that coming
// back.

/**
 * An `io` whose captures are scripted.
 *
 * Each entry is consumed by one listen(); the last repeats for ever, which is how a
 * permanent failure is expressed. `log` is the whole interaction in order, and most
 * assertions read it rather than a return value.
 */
function scriptedIo(outcomes, opts = {}) {
  const log = [];
  const turns = opts.turns == null ? 6 : opts.turns;
  let i = 0;
  let answered = 0;
  let live = true;

  const io = {
    speak: async (t) => { log.push(['speak', t]); },
    listen: async () => {
      const o = outcomes[Math.min(i, outcomes.length - 1)];
      i += 1;
      log.push(['listen', o.kind || 'text']);
      if (o.stopAfter && i >= o.stopAfter) live = false;
      if (o.kind === 'throw') throw Object.assign(new Error(o.message), { code: o.code });
      return o.text;
    },
    // A fresh object every call, as an immutable session reducer would produce.
    openTurn: () => (answered < turns
      ? { id: `t${answered}`, question: `q${answered}`, bridge: null }
      : null),
    submit: async (t) => { log.push(['submit', t]); answered += 1; },
    skip: async () => { log.push(['skip']); answered += 1; },
    wrap: async () => { log.push(['wrap']); live = false; },
    offerWrap: () => (opts.offerAt != null && answered >= opts.offerAt ? 'coverage' : null),
    advisory: () => 'There is enough here to write it up whenever you like.',
    notify: (m) => log.push(['notify', m]),
    running: () => live,
    config: opts.config || {},
  };

  return {
    io,
    log,
    said: () => log.filter(([k]) => k === 'speak').map(([, t]) => t),
    kinds: () => log.map(([k]) => k),
    only: (kind) => log.filter(([k]) => k === kind).map(([, v]) => v),
  };
}

const ANSWER = (text) => ({ text });
const NOTHING = { text: '' };

test('an answer is spoken to and then submitted', async () => {
  const s = scriptedIo([ANSWER('a tool for remembering names over')], { turns: 1 });
  await createDriveLoop(s.io).run();
  assert.deepEqual(s.only('submit'), ['a tool for remembering names']);
  assert.ok(s.said()[0].includes('q0'), 'the question is read out before listening');
});

test('the question is read before the answer is captured, never after', async () => {
  const s = scriptedIo([ANSWER('first over'), ANSWER('second over')], { turns: 2 });
  await createDriveLoop(s.io).run();
  const order = s.kinds().filter((k) => k === 'speak' || k === 'listen' || k === 'submit');
  assert.deepEqual(order, ['speak', 'listen', 'submit', 'speak', 'listen', 'submit']);
});

// ── the invariant ────────────────────────────────────────────────────────────

test('an empty capture is answered out loud, not by asking for a tap', async () => {
  const s = scriptedIo([NOTHING, ANSWER('the real answer over')], { turns: 1 });
  await createDriveLoop(s.io).run();

  assert.deepEqual(s.only('submit'), ['the real answer'], 'it recovered and carried on');
  assert.ok(s.said().length >= 2, 'it said something after the miss');
  // The whole point. A hands-free recovery that asks for a tap is not a recovery.
  assert.ok(!/\btap\b|\btype\b|\bkeyboard\b/i.test(JSON.stringify(s.log)),
    'nothing in a hands-free recovery may ask for a hand');
});

test('a first miss re-prompts without re-reading the whole question', async () => {
  // Re-reading a long question at someone who simply paused is its own irritation.
  const s = scriptedIo([NOTHING, ANSWER('got it over')], { turns: 1 });
  await createDriveLoop(s.io).run();
  const asked = s.said().filter((t) => t.includes('q0'));
  assert.equal(asked.length, 1, 'the question was read once, not twice');
});

test('a second miss re-reads the question, in case it was never heard', async () => {
  const s = scriptedIo([NOTHING, NOTHING, ANSWER('finally over')], { turns: 1 });
  await createDriveLoop(s.io).run();
  const asked = s.said().filter((t) => t.includes('q0'));
  assert.equal(asked.length, 2, 'the question was read again');
  assert.deepEqual(s.only('submit'), ['finally']);
});

test('a third miss gives up on that question and moves to the next one', async () => {
  // The failure mode is a skipped question, not a stopped app: a driver overtaking a truck
  // loses one question, not the interview.
  const s = scriptedIo([NOTHING, NOTHING, NOTHING, ANSWER('back with you over')], { turns: 2 });
  await createDriveLoop(s.io).run();
  assert.equal(s.only('skip').length, 1, 'it skipped rather than stopping');
  assert.deepEqual(s.only('submit'), ['back with you'], 'and kept going');
});

test('a transient listen error recovers exactly like an empty capture', async () => {
  const s = scriptedIo([
    { kind: 'throw', message: 'Speech recognition failed (network).' },
    ANSWER('after the blip over'),
  ], { turns: 1 });
  await createDriveLoop(s.io).run();
  assert.deepEqual(s.only('submit'), ['after the blip']);
});

test('hearing nothing at all eventually stands down, and says so', async () => {
  // The one exit, and it must announce itself rather than going quiet.
  const s = scriptedIo([NOTHING], { turns: 20 });
  const out = await createDriveLoop(s.io).run();

  assert.equal(out, 'stopped');
  assert.ok(s.only('skip').length >= 2, 'it tried more than one question first');
  assert.ok(/hands-free|microphone/i.test(s.said().join(' ')),
    'it must say that it has stopped listening');
  assert.ok(s.log.length < 200, 'and it must not spin for ever getting there');
});

test('a denied microphone stands down at once instead of retrying', async () => {
  // Retrying a permission failure is noise: it will fail identically every time.
  const s = scriptedIo([
    { kind: 'throw', message: 'Microphone access was denied.' },
    ANSWER('never reached over'),
  ], { turns: 3 });
  const out = await createDriveLoop(s.io).run();

  assert.equal(out, 'stopped');
  assert.equal(s.only('listen').length, 1, 'it did not try again');
  assert.deepEqual(s.only('submit'), []);
});

test('switching hands-free off ends the loop wherever it is', async () => {
  const s = scriptedIo([{ text: 'one over', stopAfter: 1 }], { turns: 9 });
  const out = await createDriveLoop(s.io).run();
  assert.equal(out, 'stopped');
  assert.equal(s.only('listen').length, 1);
});

test('stopping the loop from outside is honoured', async () => {
  const s = scriptedIo([ANSWER('one over')], { turns: 9 });
  const loop = createDriveLoop(s.io);
  const done = loop.run();
  loop.stop();
  assert.equal(await done, 'stopped');
});

// ── commands ─────────────────────────────────────────────────────────────────

test('"repeat that" re-reads the question and captures nothing', async () => {
  const s = scriptedIo([ANSWER('repeat that'), ANSWER('now the answer over')], { turns: 1 });
  await createDriveLoop(s.io).run();
  assert.equal(s.said().filter((t) => t.includes('q0')).length, 2);
  assert.deepEqual(s.only('submit'), ['now the answer']);
});

test('"skip this one" skips instead of being recorded as an answer', async () => {
  // If it reached submit, RE_REFUSAL would classify it and cap the dimension's coverage.
  const s = scriptedIo([ANSWER('skip this one'), ANSWER('the next one over')], { turns: 2 });
  await createDriveLoop(s.io).run();
  assert.deepEqual(s.only('skip'), [undefined]);
  assert.deepEqual(s.only('submit'), ['the next one']);
});

test('"scratch that" re-listens without re-reading the question', async () => {
  const s = scriptedIo([ANSWER('scratch that'), ANSWER('what I meant over')], { turns: 1 });
  await createDriveLoop(s.io).run();
  assert.equal(s.said().filter((t) => t.includes('q0')).length, 1);
  assert.deepEqual(s.only('submit'), ['what I meant']);
});

test('no command ever reaches submit', async () => {
  for (const said of ['repeat that', 'skip this one', 'scratch that', 'wrap it up']) {
    const s = scriptedIo([ANSWER(said), ANSWER('an answer over')], { turns: 4 });
    await createDriveLoop(s.io).run();
    assert.ok(!s.only('submit').includes(said), `"${said}" must not be recorded as an answer`);
  }
});

test('"wrap it up" is refused before there is anything to wrap', async () => {
  // Below the floor the export would be worthless, so a misfire must not end the interview.
  const s = scriptedIo([ANSWER('wrap it up'), ANSWER('carrying on over')], { turns: 2 });
  await createDriveLoop(s.io).run();
  assert.deepEqual(s.only('wrap'), [], 'it did not wrap');
  assert.equal(s.only('submit')[0], 'carrying on', 'and the interview continued');
});

test('"wrap it up" is honoured once the interview has substance', async () => {
  const s = scriptedIo([
    ANSWER('one over'), ANSWER('two over'), ANSWER('three over'), ANSWER('four over'),
    ANSWER('wrap it up'),
  ], { turns: 9 });
  const out = await createDriveLoop(s.io).run();
  assert.equal(out, 'wrapped');
  assert.equal(s.only('wrap').length, 1);
});

// ── the wrap offer ───────────────────────────────────────────────────────────

test('reaching coverage asks, out loud, and wraps on a yes', async () => {
  const s = scriptedIo([
    ANSWER('one over'), ANSWER('two over'), ANSWER('yes'),
  ], { turns: 9, offerAt: 2 });
  const out = await createDriveLoop(s.io).run();

  assert.ok(/shall I write it up/i.test(s.said().join(' ')), 'it asked');
  assert.equal(out, 'wrapped');
  assert.equal(s.only('wrap').length, 1);
});

test('"keep going" carries on, and is not asked again', async () => {
  const s = scriptedIo([
    ANSWER('one over'), ANSWER('two over'), ANSWER('keep going'),
    ANSWER('three over'), ANSWER('four over'),
  ], { turns: 5, offerAt: 2 });
  await createDriveLoop(s.io).run();

  const asks = s.said().filter((t) => /shall I write it up/i.test(t));
  assert.equal(asks.length, 1, 'asking after every answer would be unbearable');
  assert.deepEqual(s.only('wrap'), []);
});

test('an answer that is neither yes nor no never wraps by accident', async () => {
  // Wrapping ends the interview and cannot be undone by voice, so silence beats a guess.
  const s = scriptedIo([
    ANSWER('one over'), ANSWER('two over'),
    ANSWER('well the thing is'), ANSWER('still unclear'),
    ANSWER('three over'),
  ], { turns: 5, offerAt: 2 });
  await createDriveLoop(s.io).run();
  assert.deepEqual(s.only('wrap'), []);
});

test('the advisory is spoken, not just counted', async () => {
  const s = scriptedIo([ANSWER('one over'), ANSWER('keep going'), ANSWER('two over')],
    { turns: 3, offerAt: 1 });
  await createDriveLoop(s.io).run();
  assert.ok(s.said().some((t) => /enough here/i.test(t)), s.said().join(' | '));
});

test('running out of questions ends the loop cleanly', async () => {
  const s = scriptedIo([ANSWER('the only one over')], { turns: 1 });
  assert.equal(await createDriveLoop(s.io).run(), 'done');
});
