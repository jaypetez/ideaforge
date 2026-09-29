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
      ? { id: `t${answered}`, question: `q${answered}`, bridge: null, chips: opts.chips || [] }
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

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

/** Hold one real scripted effect at its completion, without clocks or polling. */
function deferEffect(io, name, when = () => true) {
  const started = deferred();
  const outcome = deferred();
  const original = io[name];
  let pending;
  io[name] = (...args) => {
    if (pending || !when(...args)) return original(...args);
    pending = Promise.resolve(original(...args)).then(() => outcome.promise);
    started.resolve();
    return pending;
  };
  return { started: started.promise, resolve: outcome.resolve, reject: outcome.reject,
    get promise() { return pending; } };
}

function trackStates(s) {
  const states = [];
  s.io.onState = (phase) => { states.push(phase); s.log.push(['state', phase]); };
  return states;
}

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

// ── the suggestions, said out loud ───────────────────────────────────────────
//
// On screen the chips show what shape of answer the question wants. Hands-free is the one
// mode where the screen is what you are deliberately not looking at.

test('the suggestions are read after the question and before listening', async () => {
  const s = scriptedIo([ANSWER('first over')], {
    turns: 1, chips: ['like a business card', 'like LinkedIn'],
  });
  await createDriveLoop(s.io).run();
  const order = s.kinds().filter((k) => k === 'speak' || k === 'listen');
  assert.deepEqual(order, ['speak', 'speak', 'listen'], 'question, then examples, then listen');
  assert.ok(s.said()[0].includes('q0'), 'the question comes first');
  assert.match(s.said()[1], /For example: like a business card, or like LinkedIn\./);
});

test('a turn with no suggestions is spoken exactly once', async () => {
  // The common case: a bank question carries no chips, and "For example:" followed by
  // nothing would be worse than staying quiet.
  const s = scriptedIo([ANSWER('first over')], { turns: 1 });
  await createDriveLoop(s.io).run();
  assert.equal(s.said().length, 1, s.said().join(' | '));
});

test('"repeat that" reads the question and its suggestions again', async () => {
  const s = scriptedIo([ANSWER('repeat that'), ANSWER('an answer over')], {
    turns: 1, chips: ['like a business card'],
  });
  await createDriveLoop(s.io).run();
  const asked = s.said().filter((t) => t.includes('q0'));
  const examples = s.said().filter((t) => /For example/.test(t));
  assert.equal(asked.length, 2, 'the question is read twice');
  assert.equal(examples.length, 2, 'and so are the examples — repeating half of it is worse');
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

test('a resumed loop uses session history for immediate wrap eligibility', async () => {
  const s = scriptedIo([
    ANSWER('wrap it up'), { text: 'still waiting', stopAfter: 2 },
  ]);
  s.io.answeredCount = () => 4;
  assert.equal(await createDriveLoop(s.io).run(), 'wrapped');
  assert.equal(s.only('wrap').length, 1);
  assert.deepEqual(s.only('submit'), []);
});

for (const command of ['one more answer over', 'skip this one']) {
  test(`persisted progress includes a resumed ${command} before wrapping`, async () => {
    const s = scriptedIo([
      ANSWER(command), ANSWER('wrap it up'), { text: 'still waiting', stopAfter: 3 },
    ]);
    s.io.answeredCount = () => 3 + s.only('submit').length + s.only('skip').length;
    assert.equal(await createDriveLoop(s.io).run(), 'wrapped');
    assert.equal(s.only('wrap').length, 1);
  });
}

test('invalid persisted progress cannot bypass the wrap safety floor', async () => {
  const s = scriptedIo([ANSWER('wrap it up'), { text: '', stopAfter: 2 }]);
  s.io.answeredCount = () => NaN;
  await assert.rejects(createDriveLoop(s.io).run(), /nonnegative integer/);
  assert.deepEqual(s.only('wrap'), []);
});

test('a bare finish word submits a retained draft instead of counting as a miss', async () => {
  const s = scriptedIo([ANSWER('over')], { turns: 1 });
  s.io.hasDraft = () => true;
  await createDriveLoop(s.io).run();
  assert.deepEqual(s.only('submit'), ['']);
  assert.deepEqual(s.only('skip'), []);
});

for (const capture of [NOTHING, { kind: 'throw', message: 'temporary network failure' }]) {
  test(`repeated ${capture.kind || 'empty'} captures never skip a retained draft`, async () => {
    const s = scriptedIo([capture], { turns: 1 });
    s.io.hasDraft = () => true;
    assert.equal(await createDriveLoop(s.io).run(), 'stopped');
    assert.deepEqual(s.only('skip'), []);
    assert.deepEqual(s.only('submit'), []);
    assert.match(s.said().at(-1), /draft.*still here/i);
  });
}

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

// Lifecycle observers never own progression. The loop checks cancellation again after
// calling one, because a Pause/Exit handler can synchronously stop that very operation.

test('states follow half-duplex effects and keep question and examples separate', async () => {
  const s = scriptedIo([ANSWER('an answer over')], { turns: 1, chips: ['an example'] });
  trackStates(s);
  assert.equal(await createDriveLoop(s.io).run(), 'done');
  assert.deepEqual(s.log.filter(([kind]) => kind !== 'notify'), [
    ['state', 'speaking'], ['speak', 'q0'], ['speak', 'For example: an example.'],
    ['state', 'listening'], ['listen', 'text'],
    ['state', 'processing'], ['submit', 'an answer'], ['state', 'stopped'],
  ]);
});

test('transient recovery reports recovering and resumes without a tap', async () => {
  const s = scriptedIo([
    { kind: 'throw', message: 'Speech recognition failed (network).' }, ANSWER('back over'),
  ], { turns: 1 });
  const states = trackStates(s);
  assert.equal(await createDriveLoop(s.io).run(), 'done');
  assert.deepEqual(states, [
    'speaking', 'listening', 'recovering', 'speaking', 'listening', 'processing', 'stopped',
  ]);
  assert.deepEqual(s.only('submit'), ['back']);
  assert.doesNotMatch(s.said().join(' '), /\btap\b|\btype\b|\bkeyboard\b/i);
});

test('bounded empty-capture recovery ends in an observable stopped state', async () => {
  const s = scriptedIo([NOTHING], { turns: 20 });
  const states = trackStates(s);
  assert.equal(await createDriveLoop(s.io).run(), 'stopped');
  assert.equal(s.only('listen').length, 6);
  assert.equal(s.only('skip').length, 2);
  assert.equal(states.filter((phase) => phase === 'recovering').length, 6);
  assert.equal(states.at(-1), 'stopped');
});

for (const [text, command, phase] of [
  ['pause', 'pause', 'paused'], ['pause voice, over', 'pause', 'paused'],
  ['exit voice', 'exit', 'stopped'],
]) {
  test(`"${text}" stands down without submitting, skipping or wrapping`, async () => {
    const s = scriptedIo([ANSWER(text)], { turns: 1 });
    const states = trackStates(s);
    const loop = createDriveLoop(s.io);
    s.io[command] = () => { s.log.push([command]); loop.stop(); };

    assert.equal(await loop.run(), 'stopped');
    assert.deepEqual(states, ['speaking', 'listening', phase]);
    assert.deepEqual(s.only('submit'), []);
    assert.deepEqual(s.only('skip'), []);
    assert.deepEqual(s.only('wrap'), []);
    assert.equal(s.only(command).length, 1);
    assert.equal(s.only('listen').length, 1);
    assert.deepEqual(s.said(), ['q0'], 'standing down does not start farewell speech');

    const stoppedLog = s.log.slice();
    loop.stop();
    assert.equal(await loop.run(), 'stopped');
    assert.deepEqual(s.log, stoppedLog, 'a stopped instance never restarts');
  });
}

test('legacy IO needs no lifecycle or command callbacks', async () => {
  for (const command of ['pause voice', 'exit voice']) {
    const s = scriptedIo([ANSWER(command)], { turns: 1 });
    assert.equal(await createDriveLoop(s.io).run(), 'stopped', command);
    assert.deepEqual(s.only('submit'), [], command);
    assert.deepEqual(s.only('skip'), [], command);
  }
  const s = scriptedIo([ANSWER('scratch that'), ANSWER('legacy answer over')], { turns: 1 });
  assert.equal(await createDriveLoop(s.io).run(), 'done');
  assert.deepEqual(s.only('submit'), ['legacy answer']);
});

test('command phrases inside answers and a stop finish word still submit normally', async () => {
  const answers = ['pause voice playback during calls', 'the button should say exit voice',
    'we should stop voice playback before listening'];
  const s = scriptedIo(answers.map((text) => ANSWER(`${text} stop`)),
    { turns: answers.length, config: { trigger: 'stop' } });
  s.io.pause = s.io.exit = () => assert.fail('an answer is not a stand-down command');
  assert.equal(await createDriveLoop(s.io).run(), 'done');
  assert.deepEqual(s.only('submit'), answers);
});

test('scratch clears a retained draft before speaking and relistening', async () => {
  const s = scriptedIo([ANSWER('scratch that'), ANSWER('a replacement over')], { turns: 1 });
  let draft = 'the retained answer';
  const originalListen = s.io.listen;
  s.io.listen = async (...args) => {
    if (s.only('listen').length) assert.equal(draft, '');
    return originalListen(...args);
  };
  s.io.scratch = async () => { draft = ''; s.log.push(['scratch']); };
  const states = trackStates(s);

  assert.equal(await createDriveLoop(s.io).run(), 'done');
  assert.deepEqual(s.kinds().filter((kind) => !['state', 'notify'].includes(kind)),
    ['speak', 'listen', 'scratch', 'speak', 'listen', 'submit']);
  assert.deepEqual(states, [
    'speaking', 'listening', 'processing', 'speaking', 'listening', 'processing', 'stopped',
  ]);
  assert.deepEqual(s.only('submit'), ['a replacement']);
  assert.equal(s.said().filter((text) => text === 'q0').length, 1);
});

for (const command of ['pause', 'exit']) {
  test(`${command} during wrap confirmation stands down without accepting or declining`, async () => {
    const s = scriptedIo([ANSWER('one over'), ANSWER(`${command} voice`)],
      { turns: 3, offerAt: 1 });
    const states = trackStates(s);
    s.io[command] = () => s.log.push([command]);

    assert.equal(await createDriveLoop(s.io).run(), 'stopped');
    assert.deepEqual(s.only('submit'), ['one']);
    assert.deepEqual(s.only('skip'), []);
    assert.deepEqual(s.only('wrap'), []);
    assert.equal(s.only(command).length, 1);
    assert.equal(states.at(-1), command === 'pause' ? 'paused' : 'stopped');
    assert.doesNotMatch(s.said().join(' '), /carrying on|Sorry/);
    assert.equal(s.only('listen').length, 2);
  });
}

test('scratch during confirmation clears the draft but keeps the bounded yes/no exchange', async () => {
  const s = scriptedIo([ANSWER('one over'), ANSWER('scratch that'), ANSWER('yes')],
    { turns: 3, offerAt: 1 });
  s.io.scratch = () => s.log.push(['scratch']);
  assert.equal(await createDriveLoop(s.io).run(), 'wrapped');
  assert.equal(s.only('scratch').length, 1);
  assert.deepEqual(s.only('submit'), ['one']);
  assert.equal(s.only('wrap').length, 1);
  const scratch = s.kinds().indexOf('scratch');
  assert.deepEqual(s.log.slice(scratch).filter(([kind]) => kind !== 'notify').map(([kind]) => kind),
    ['scratch', 'speak', 'listen', 'wrap']);
});

test('a transient confirmation failure carries on but never treats failure as yes', async () => {
  const s = scriptedIo([
    ANSWER('one over'), { kind: 'throw', message: 'network' }, ANSWER('two over'),
  ], { turns: 2, offerAt: 1 });
  const states = trackStates(s);
  assert.equal(await createDriveLoop(s.io).run(), 'done');
  assert.deepEqual(s.only('submit'), ['one', 'two']);
  assert.deepEqual(s.only('wrap'), []);
  assert.ok(states.includes('recovering'));
  assert.equal(s.said().filter((text) => /Shall I write it up/.test(text)).length, 1);
});

test('a denied microphone during confirmation stands down instead of retrying the question', async () => {
  const s = scriptedIo([
    ANSWER('one over'), { kind: 'throw', message: 'Microphone access was denied.' },
    ANSWER('must not be heard over'),
  ], { turns: 2, offerAt: 1 });
  const states = trackStates(s);
  assert.equal(await createDriveLoop(s.io).run(), 'stopped');
  assert.deepEqual(s.only('submit'), ['one']);
  assert.deepEqual(s.only('wrap'), []);
  assert.equal(s.only('listen').length, 2);
  assert.equal(states.at(-1), 'stopped');
});

test('a wrap confirmation publishes processing before wrapping and then stops', async () => {
  const s = scriptedIo([ANSWER('one over'), ANSWER('yes')], { turns: 3, offerAt: 1 });
  const states = trackStates(s);
  assert.equal(await createDriveLoop(s.io).run(), 'wrapped');
  assert.deepEqual(states, [
    'speaking', 'listening', 'processing', 'speaking', 'listening', 'processing', 'stopped',
  ]);
  assert.deepEqual(s.log.slice(-3), [['state', 'processing'], ['wrap'], ['state', 'stopped']]);
});

const CONFIRM = { turns: 3, offerAt: 1 };
for (const { label, effect, outcomes, options, when, result = 'late answer over' } of [
  { label: 'question speech', effect: 'speak' },
  { label: 'example speech', effect: 'speak', options: { chips: ['an example'] },
    when: (text) => text.startsWith('For example:') },
  { label: 'answer capture', effect: 'listen' },
  { label: 'answer submission', effect: 'submit' },
  { label: 'skipping', effect: 'skip', outcomes: [ANSWER('skip this one')] },
  { label: 'recovery speech', effect: 'speak', outcomes: [NOTHING],
    when: (text) => /didn.t catch/.test(text) },
  { label: 'draft clearing', effect: 'scratch', outcomes: [ANSWER('scratch that')] },
  { label: 'confirmation speech', effect: 'speak', options: CONFIRM,
    when: (text) => /Shall I write it up/.test(text) },
  { label: 'confirmation capture', effect: 'listen', options: CONFIRM,
    outcomes: [ANSWER('one over'), ANSWER('yes')], when: (opts) => opts.confirm, result: 'yes' },
  { label: 'confirmation retry', effect: 'speak', options: CONFIRM,
    outcomes: [ANSWER('one over'), ANSWER('unclear')], when: (text) => /Sorry/.test(text) },
  { label: 'wrapping', effect: 'wrap', options: CONFIRM,
    outcomes: [ANSWER('one over'), ANSWER('yes')] },
]) {
  for (const late of ['resolve', 'reject']) {
    test(`external stop during ${label} ignores a late ${late} without waiting for it`, async () => {
      const s = scriptedIo(outcomes || [ANSWER('an answer over')], { turns: 3, ...options });
      s.io.scratch = () => s.log.push(['scratch']);
      const states = trackStates(s);
      const held = deferEffect(s.io, effect, when);
      const loop = createDriveLoop(s.io);
      const run = loop.run();
      await held.started;
      loop.stop();

      assert.equal(await run, 'stopped', 'stop must not wait for the abandoned effect');
      assert.equal(states.at(-1), 'stopped');
      const stoppedLog = s.log.slice();
      if (late === 'resolve') {
        held.resolve(result);
        await held.promise;
      } else {
        held.reject(new Error('late failure'));
        await assert.rejects(held.promise, /late failure/);
      }
      loop.stop();
      assert.equal(await loop.run(), 'stopped');
      assert.deepEqual(s.log, stoppedLog, 'no late speech, capture, command, mutation or state');
    });
  }
}

test('concurrent run calls share one half-duplex loop', async () => {
  const s = scriptedIo([ANSWER('one over')], { turns: 1 });
  const held = deferEffect(s.io, 'speak');
  const loop = createDriveLoop(s.io);
  const first = loop.run();
  const second = loop.run();
  assert.equal(first, second);
  await held.started;
  assert.deepEqual(s.kinds(), ['notify', 'speak']);
  held.resolve();
  assert.equal(await first, 'done');
  assert.equal(await second, 'done');
  assert.equal(s.only('submit').length, 1);
});

test('stopping before run is idempotent and never invokes command callbacks', async () => {
  const s = scriptedIo([ANSWER('one over')]);
  const states = trackStates(s);
  s.io.pause = s.io.exit = s.io.scratch = () => assert.fail('stop is not a spoken command');
  const loop = createDriveLoop(s.io);
  loop.stop();
  loop.stop();
  assert.equal(await loop.run(), 'stopped');
  assert.deepEqual(states, ['stopped']);
  assert.deepEqual(s.log, [['state', 'stopped']]);
});

for (const phase of ['speaking', 'listening', 'processing', 'recovering']) {
  test(`stopping in the ${phase} observer prevents the announced effect`, async () => {
    const s = scriptedIo(phase === 'recovering' ? [NOTHING] : [ANSWER('one over')]);
    const loop = createDriveLoop(s.io);
    let stoppedLog;
    s.io.onState = (next) => {
      s.log.push(['state', next]);
      if (next === phase) { loop.stop(); stoppedLog = s.log.slice(); }
    };
    assert.equal(await loop.run(), 'stopped');
    assert.deepEqual(s.log, stoppedLog);
  });
}

test('stopping from a speech notification prevents playback', async () => {
  const s = scriptedIo([ANSWER('one over')]);
  const loop = createDriveLoop(s.io);
  s.io.notify = () => loop.stop();
  assert.equal(await loop.run(), 'stopped');
  assert.deepEqual(s.log, []);
});

for (const effect of ['speak', 'listen', 'submit']) {
  test(`an AbortError from ${effect} stops without recovery or fallback speech`, async () => {
    const s = scriptedIo([ANSWER('one over')]);
    const states = trackStates(s);
    const original = s.io[effect];
    s.io[effect] = async (...args) => {
      await original(...args);
      throw Object.assign(new Error('cancelled'), { name: 'AbortError' });
    };
    assert.equal(await createDriveLoop(s.io).run(), 'stopped');
    assert.equal(states.at(-1), 'stopped');
    assert.ok(!states.includes('recovering'));
    assert.equal(s.only(effect).length, 1);
    assert.deepEqual(s.said(), ['q0']);
  });
}

for (const effect of ['speak', 'submit', 'scratch']) {
  test(`an active ${effect} failure is surfaced with an observable stand-down`, async () => {
    const s = scriptedIo([ANSWER(effect === 'scratch' ? 'scratch that' : 'one over')]);
    const states = trackStates(s);
    s.io[effect] = async () => { throw new Error('effect failed'); };
    await assert.rejects(createDriveLoop(s.io).run(), /effect failed/);
    assert.equal(states.at(-1), 'stopped');
    assert.ok(!states.includes('recovering'));
    assert.deepEqual(s.only('skip'), []);
  });
}

for (const flag of [{ fatal: true }, { recoverable: false }]) {
  test(`an explicitly unrecoverable capture is not retried: ${JSON.stringify(flag)}`, async () => {
    const s = scriptedIo([ANSWER('one over')]);
    const states = trackStates(s);
    s.io.listen = async () => {
      s.log.push(['listen']);
      throw Object.assign(new Error('Voice input is not configured.'), flag);
    };
    await assert.rejects(createDriveLoop(s.io).run(), /not configured/);
    assert.equal(s.only('listen').length, 1);
    assert.deepEqual(s.only('skip'), []);
    assert.equal(states.at(-1), 'stopped');
  });
}
