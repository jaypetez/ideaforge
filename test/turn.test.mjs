import test from 'node:test';
import assert from 'node:assert/strict';

import { DIMENSION_IDS, SEED_QUESTION } from '../src/core/dimensions.js';
import {
  createSession, askQuestion, answerQuestion, applyCoverage, openTurn, setPending,
} from '../src/core/session.js';
import {
  buildTurnPrompt, buildTurnPromptParts, pickBankQuestion, lastAnswerClass, promptHash,
  selectNextDimension, legalMoves, HARD_TURN_CEILING, TRIPWIRE_DIRECTIVE,
} from '../src/core/engine.js';
import { levelSnapshot, isZeroGain } from '../src/runtime/gain.js';
import { seedTurn, submitAnswer, runTurn, resumeTurn, CAPPING_CLASSES } from '../src/runtime/turn.js';

// ─────────────────────────────────────────────────────────────── fixtures
// Deliberately duplicated rather than shared: node --test would load a helpers file as a
// test file, and fifteen lines of fixture is cheaper than the indirection.

/** A provider that replays a scripted queue and records exactly what it was asked. */
function fakeProvider(script) {
  const calls = [];
  const queue = script.slice();
  return {
    calls,
    sampleJson(parts, opts = {}) {
      calls.push({ parts, opts });
      const next = queue.length > 1 ? queue.shift() : queue[0];
      if (next instanceof Error) return Promise.reject(next);
      if (typeof next === 'function') return Promise.resolve(wrap(next(calls.length)));
      return Promise.resolve(wrap(next));
    },
  };
  function wrap(json) {
    return { text: JSON.stringify(json), json, modelTierApplied: 'fake', usage: null };
  }
}

const turnResult = (over = {}) => ({
  bridge: null,
  question: 'Which single section earns its place here?',
  move: 'scope_cut',
  dimension: 'substance',
  chips: [],
  new_facts: [],
  coverage: {},
  suggest_wrap: false,
  wrap_reason: null,
  ...over,
});

const providerError = (code) => Object.assign(new Error(code), { code });

/** A session with the seed turn answered, i.e. ready for a real first runTurn. */
function opened(text = 'a tool that interviews you about an idea') {
  return submitAnswer(seedTurn(createSession({ id: 's_turn', now: 1 }), { now: 1 }), { text, now: 2 });
}

// ────────────────────────────────────────────────────────── the seed turn
test('the seed turn costs nothing and cannot fail', () => {
  const s = seedTurn(createSession({ id: 's1', now: 0 }), { now: 1 });
  assert.equal(s.turns.length, 1);
  assert.equal(s.turns[0].question, SEED_QUESTION);
  assert.equal(s.turns[0].questionSource, 'seed');
  assert.equal(s.turns[0].dimension, 'outcome');
});

test('submitAnswer classifies, and the opening is captured verbatim', () => {
  const s = submitAnswer(seedTurn(createSession({ id: 's1' })), { text: 'a memo generator for my CTO' });
  assert.equal(s.opening, 'a memo generator for my CTO');
  assert.equal(s.turns[0].classification, 'substantive');
});

test('submitAnswer honours the voice exemption rather than re-deriving it', () => {
  const rambling = 'um so like the thing is I guess maybe it could be whatever really you know';
  let s = askQuestion(seedTurn(createSession({ id: 's1' })), { question: 'ignored?', dimension: 'bar' });
  s = submitAnswer(s, { text: rambling, source: 'voice' });
  assert.equal(s.turns[1].classification, 'substantive', 'dictation must not read as a dodge');
});

// ──────────────────────────────────────────────────────────── happy path
test('a good turn appends a question, applies coverage and clears pending', async () => {
  const provider = fakeProvider([turnResult({
    coverage: { substance: { level: 'partial', gap: 'no real numbers yet' } },
    new_facts: [{ dimension: 'substance', fact: 'It runs on a phone.' }],
  })]);
  const out = await runTurn(opened(), { provider, now: 10 });

  assert.equal(out.calls, 1);
  assert.equal(out.degraded, false);
  assert.equal(out.error, null);
  assert.equal(out.session.pending, null);
  assert.equal(out.turn.question, 'Which single section earns its place here?');
  assert.equal(out.turn.questionSource, 'model');
  assert.equal(out.session.coverage.substance.level, 'partial');
  assert.equal(out.session.facts.length, 1);
});

test('the provider is handed three prompt parts whose join is the hashed prompt', async () => {
  const before = opened();
  const provider = fakeProvider([turnResult()]);
  await runTurn(before, { provider, now: 10 });

  const { parts } = provider.calls[0];
  assert.deepEqual(Object.keys(parts).sort(), ['prefix', 'system', 'tail']);
  assert.ok(parts.system.startsWith('You are the interviewer inside IdeaForge'));
  assert.ok(!parts.system.includes(before.opening), 'the cached block must hold no session content');
  assert.ok(parts.prefix.includes(before.opening));
});

test('runTurn refuses to ask while a question is still open', async () => {
  await assert.rejects(
    () => runTurn(seedTurn(createSession({ id: 's1' })), { provider: fakeProvider([turnResult()]) }),
    /already open/
  );
});

// ───────────────────────────────────────────────────────────── tripwires
test('a tripwire buys exactly one regeneration, carrying the corrective directive', async () => {
  const provider = fakeProvider([
    turnResult({ question: 'Who is your target audience?' }),          // generic
    turnResult({ question: 'What did the last one cost you in hours?' }),
  ]);
  const out = await runTurn(opened(), { provider, now: 10 });

  assert.equal(out.calls, 2);
  assert.equal(out.turn.question, 'What did the last one cost you in hours?');
  assert.ok(provider.calls[1].parts.tail.includes(TRIPWIRE_DIRECTIVE.generic));
  assert.ok(!provider.calls[0].parts.tail.includes('ADDITIONAL DIRECTIVE'));
});

test('a compound question that survives regeneration falls back to the bank', async () => {
  const provider = fakeProvider([turnResult({ question: 'What is it and why does it matter?' })]);
  const out = await runTurn(opened(), { provider, now: 10 });

  assert.equal(out.calls, 2, 'one regeneration, then give up on the model for this turn');
  assert.equal(out.degraded, true);
  assert.equal(out.turn.questionSource, 'bank');
  assert.ok(out.warnings.some((w) => /unfixable compound/.test(w)));
});

test('a generic question that survives regeneration is accepted, not discarded', async () => {
  const provider = fakeProvider([turnResult({ question: 'Who is your target audience?' })]);
  const out = await runTurn(opened(), { provider, now: 10 });

  assert.equal(out.turn.question, 'Who is your target audience?');
  assert.equal(out.degraded, false, 'a quality miss is not a correctness failure');
  assert.ok(out.warnings.some((w) => /generic question accepted/.test(w)));
});

test('unreadable JSON gets one repair attempt, then the bank', async () => {
  const provider = fakeProvider([turnResult({ question: '' })]);   // parseTurnResult: not ok
  const out = await runTurn(opened(), { provider, now: 10 });

  assert.equal(out.calls, 2);
  assert.equal(out.degraded, true);
  assert.equal(out.turn.questionSource, 'bank');
});

// ─────────────────────────────────────────────────── the provenance caps
test('a chip answer caps the dimension IT served, not the dimension being asked next', async () => {
  let s = opened();
  s = askQuestion(s, { question: 'What has to be in it?', dimension: 'substance' });
  s = submitAnswer(s, { text: 'honestly, just for me', source: 'chip' });
  s = applyCoverage(s, { substance: { level: 'partial', gap: 'g' } });

  const provider = fakeProvider([turnResult({
    coverage: { substance: { level: 'covered', evidence: 'honestly, just for me' } },
  })]);
  const out = await runTurn(s, { provider, now: 10 });

  assert.equal(out.session.coverage.substance.level, 'partial',
    "Claude's own words must not carry a dimension to covered");
});

test('a dodged answer caps the dimension it dodged', async () => {
  let s = opened();
  s = askQuestion(s, { question: 'What format should the output take?', dimension: 'voice' });
  s = submitAnswer(s, {
    text: 'well honestly the whole industry is broken if you really think about it for a while longer',
  });
  assert.equal(s.turns[1].classification, 'dodge');
  s = applyCoverage(s, { voice: { level: 'partial', gap: 'g' } });

  const provider = fakeProvider([turnResult({
    coverage: { voice: { level: 'covered', evidence: 'the whole industry is broken' } },
  })]);
  const out = await runTurn(s, { provider, now: 10 });

  assert.equal(out.session.coverage.voice.level, 'partial');
});

test('CAPPING_CLASSES excludes terse on purpose', () => {
  assert.ok(!CAPPING_CLASSES.has('terse'), 'a short answer can still be a specific one');
  for (const c of ['dodge', 'idk', 'refusal']) assert.ok(CAPPING_CLASSES.has(c));
});

// ───────────────────────────────────────────────────────────── zero gain
test('zero gain is measured after the ratchet, not on what the model claimed', async () => {
  // The model claims `covered` every turn with no quote to back it, so applyCoverage
  // clamps every claim straight back to the `partial` the dimension already held. The
  // claim looks like progress; the applied result is flat, and flat is what must count.
  // Wording has to vary genuinely or the repeat tripwire fires and we end up measuring
  // the bank fallback instead of the ratchet.
  const questions = [
    'Walk me through the last time you did this by hand.',
    'Which part of that would you refuse to automate?',
    'What did the worst version of this look like?',
  ];
  const stuck = (n) => turnResult({
    question: questions[(n - 1) % questions.length],
    coverage: { substance: { level: 'covered', evidence: null } },
  });
  let s = applyCoverage(opened(), { substance: { level: 'partial', gap: 'g' } });
  const provider = fakeProvider([stuck]);

  let out = await runTurn(s, { provider, now: 10 });
  assert.equal(out.session.zeroGainStreak, 1);

  s = submitAnswer(out.session, { text: 'I really could not say, sorry' });
  out = await runTurn(s, { provider, now: 11 });
  assert.equal(out.session.zeroGainStreak, 2);
  assert.equal(out.wrap, 'exhausted', 'two flat turns must offer the exit');
});

test('any genuine rise resets the streak', async () => {
  const provider = fakeProvider([turnResult({
    coverage: { substance: { level: 'partial', gap: 'still thin on numbers' } },
  })]);
  const out = await runTurn({ ...opened(), zeroGainStreak: 1 }, { provider, now: 10 });
  assert.equal(out.session.zeroGainStreak, 0);
});

test('a bank turn leaves the streak untouched in both directions', async () => {
  const start = { ...opened(), zeroGainStreak: 1 };
  const out = await runTurn(start, { provider: null, now: 10 });
  assert.equal(out.session.zeroGainStreak, 1,
    'a network blip is neither progress nor exhaustion');
});

test('isZeroGain reads a fall as no gain rather than as progress', () => {
  const before = { ...blank(), substance: 'covered' };
  assert.equal(isZeroGain(before, { ...blank(), substance: 'thin' }), true);
  assert.equal(isZeroGain(before, { ...blank(), substance: 'covered' }), true);
  assert.equal(isZeroGain(before, { ...blank(), substance: 'covered', bar: 'partial' }), false);
  function blank() {
    return Object.fromEntries(DIMENSION_IDS.map((id) => [id, 'thin']));
  }
});

test('levelSnapshot covers every dimension', () => {
  assert.deepEqual(Object.keys(levelSnapshot(opened())).sort(), [...DIMENSION_IDS].sort());
});

// ──────────────────────────────────────────────────────── degraded paths
test('with no provider at all the interview still runs, from the static bank', async () => {
  const out = await runTurn(opened(), { provider: null, now: 10 });
  assert.equal(out.calls, 0);
  assert.equal(out.degraded, true);
  assert.equal(out.error, null);
  assert.equal(out.session.pending, null, 'nothing to recover, so nothing to mark');
  assert.equal(out.turn.questionSource, 'bank');
});

test('a provider failure degrades this turn only, and surfaces the error', async () => {
  const out = await runTurn(opened(), { provider: fakeProvider([providerError('auth')]), now: 10 });
  assert.equal(out.degraded, true);
  assert.equal(out.error.code, 'auth');
  assert.equal(out.turn.questionSource, 'bank');
  assert.equal(out.session.pending, null);
});

test('the bank never repeats itself and eventually reports exhaustion', async () => {
  let s = opened();
  const seen = new Set();
  for (let i = 0; i < 40; i++) {
    const out = await runTurn(s, { provider: null, now: 100 + i });
    if (!out.turn) { assert.equal(out.wrap, 'exhausted'); return; }
    assert.ok(!seen.has(out.turn.question), `bank repeated: ${out.turn.question}`);
    seen.add(out.turn.question);
    s = submitAnswer(out.session, { text: `answer number ${i} with some substance to it` });
    if (s.turns.length >= HARD_TURN_CEILING) return;   // the ceiling can win first
  }
  assert.fail('the bank should have been exhausted or the ceiling reached');
});

test('an abort leaves no turn and no pending, and is not an error', async () => {
  const out = await runTurn(opened(), { provider: fakeProvider([providerError('aborted')]), now: 10 });
  assert.equal(out.aborted, true);
  assert.equal(out.turn, null);
  assert.equal(out.error, null);
  assert.equal(out.session.pending, null);
});

// ───────────────────────────────────────────────────────── the ceilings
test('the hard ceiling is enforced, not merely reported', async () => {
  let s = opened();
  while (s.turns.length < HARD_TURN_CEILING) {
    s = askQuestion(s, { question: `filler ${s.turns.length}?`, dimension: 'substance' });
    s = answerQuestion(s, { text: 'a filler answer with enough words in it' });
  }
  const provider = fakeProvider([turnResult()]);
  const out = await runTurn(s, { provider, now: 10 });

  assert.equal(out.calls, 0, 'the ceiling must stop us before we spend a call');
  assert.equal(out.turn, null);
  assert.equal(out.wrap, 'hard_ceiling');
});

// ────────────────────────────────────────────────────────────── recovery
test('resume after a crash between askQuestion and clearPending never duplicates the turn', async () => {
  const provider = fakeProvider([turnResult()]);
  const out = await runTurn(opened(), { provider, now: 10 });
  // Replay the exact interrupted state: the turn landed, the bookkeeping did not.
  const crashed = setPending(out.session, { kind: 'turn', turnId: out.turn.id, promptHash: 'x' }, 11);

  const resumed = await resumeTurn(crashed, { provider, now: 12 });
  assert.equal(resumed.calls, 0, 'the work was already done');
  assert.equal(resumed.session.turns.length, out.session.turns.length);
  assert.equal(resumed.session.pending, null);
});

test('resume with a matching hash re-issues the byte-identical prompt', async () => {
  const s = opened();
  const provider = fakeProvider([turnResult()]);
  // Reproduce the selection runTurn would make, so the hash genuinely matches.
  const target = selectNextDimension(s);
  const moves = legalMoves(s, target, lastAnswerClass(s));
  const hash = promptHash(buildTurnPrompt(s, { target, moves }));
  const pending = setPending(s, { kind: 'turn', turnId: 't99', promptHash: hash }, 5);

  const out = await resumeTurn(pending, { provider, now: 12 });
  assert.equal(out.calls, 1);
  assert.ok(!out.warnings.some((w) => /prompt changed/.test(w)));
});

test('resume with a stale hash starts the turn fresh and says so quietly', async () => {
  const provider = fakeProvider([turnResult()]);
  const pending = setPending(opened(), { kind: 'turn', turnId: 't99', promptHash: 'stale' }, 5);

  const out = await resumeTurn(pending, { provider, now: 12 });
  assert.equal(out.calls, 1);
  assert.ok(out.warnings.some((w) => /prompt changed/.test(w)));
  assert.equal(out.session.pending, null);
});

test('resume with nothing pending is a no-op', async () => {
  const s = opened();
  const out = await resumeTurn(s, { provider: fakeProvider([turnResult()]), now: 12 });
  assert.equal(out.calls, 0);
  assert.equal(out.session.rev, s.rev);
});

// ────────────────────────────────────────── engine additions this relies on
test('buildTurnPrompt is exactly the three parts joined', () => {
  const s = opened();
  const opts = { target: 'substance', moves: ['concretize', 'boundary'] };
  const { system, prefix, tail } = buildTurnPromptParts(s, opts);
  assert.equal(buildTurnPrompt(s, opts), [system, prefix, tail].join('\n'));
});

test('the cacheable system block is identical across unrelated sessions', () => {
  const a = buildTurnPromptParts(opened('one idea'), { target: 'substance', moves: ['concretize'] });
  const b = buildTurnPromptParts(opened('a completely different idea'), { target: 'bar', moves: ['menu'] });
  assert.equal(a.system, b.system, 'a per-session system block would never cache');
});

test('the volatile coverage map sits after the transcript, so the prefix only grows', () => {
  const { prefix, tail } = buildTurnPromptParts(opened(), { target: 'substance', moves: ['concretize'] });
  assert.ok(!prefix.includes('=== COVERAGE MAP ==='));
  assert.ok(tail.includes('=== COVERAGE MAP ==='));
  assert.ok(prefix.indexOf('=== THE IDEA') < prefix.indexOf('=== TRANSCRIPT ==='));
});

test('pickBankQuestion returns verbatim bank entries and skips what was asked', () => {
  let s = opened();
  const first = pickBankQuestion(s, 'substance');
  assert.equal(first.dimension, 'substance');
  s = askQuestion(s, { question: first.question, dimension: 'substance' });
  const second = pickBankQuestion(s, 'substance');
  assert.notEqual(second.question, first.question);
});

test('pickBankQuestion never offers a waived or deferred dimension', () => {
  let s = opened();
  for (const id of DIMENSION_IDS) {
    s = { ...s, coverage: { ...s.coverage, [id]: { ...s.coverage[id], status: 'waived' } } };
  }
  assert.equal(pickBankQuestion(s, null), null);
});

test('lastAnswerClass is what the prompt prints, so the two cannot drift', () => {
  let s = opened();
  s = askQuestion(s, { question: 'What format?', dimension: 'voice' });
  s = submitAnswer(s, { text: 'no idea, honestly' });
  const cls = lastAnswerClass(s);
  const { tail } = buildTurnPromptParts(s, { target: 'voice', moves: ['menu'] });
  assert.ok(tail.includes(`Last answer quality: ${cls}`));
});

test('a skipped turn reads as a refusal', () => {
  let s = opened();
  s = askQuestion(s, { question: 'What format?', dimension: 'voice' });
  s = { ...s, turns: s.turns.map((t, i) => (i === 1 ? { ...t, skipped: true } : t)) };
  assert.equal(lastAnswerClass(s), 'refusal');
});
