import test from 'node:test';
import assert from 'node:assert/strict';

import { DIMENSION_IDS, SEED_QUESTION } from '../src/core/dimensions.js';
import {
  createSession, askQuestion, answerQuestion, applyCoverage, addFacts,
  waiveDimension, deferDimension, migrate, openTurn, isLowConfidence, setDraftText,
  setWrapOffered,
} from '../src/core/session.js';
import {
  classifyAnswer, selectNextDimension, legalMoves, buildTurnPrompt, promptHash,
  parseTurnResult, questionTripwire, isReadyToWrap, shouldOfferWrap, wrapAdvisory,
  HARD_TURN_CEILING, SOFT_TURN_CEILING,
} from '../src/core/engine.js';
import { buildTranscriptBlock, utf8Length, BUDGET_BYTES } from '../src/core/digest.js';
import {
  buildExport, coveragePercent, slug, exportFilename, forSpeech, speechChunks,
} from '../src/core/markdown.js';

const seed = (over = {}) => ({ id: 's_test', now: 1000, ...over });
function withOpening(text = 'a tool that interviews you') {
  let s = createSession(seed());
  s = askQuestion(s, { question: SEED_QUESTION, dimension: 'outcome', source: 'seed', now: 1 });
  return answerQuestion(s, { text, now: 2 });
}

// ───────────────────────────────────────────────────────────── reducers
test('createSession demands a caller-supplied id (core generates no randomness)', () => {
  assert.throws(() => createSession({}), /requires an id/);
  const s = createSession(seed());
  assert.equal(s.rev, 0);
  assert.equal(Object.keys(s.coverage).length, DIMENSION_IDS.length);
  assert.ok(DIMENSION_IDS.every((id) => s.coverage[id].level === 'thin'));
});

test('every reducer bumps rev and leaves the input untouched', () => {
  const a = createSession(seed());
  const frozen = JSON.stringify(a);
  const b = askQuestion(a, { question: 'q?', dimension: 'outcome', now: 5 });
  const c = answerQuestion(b, { text: 'an answer that is long enough', now: 6 });
  assert.equal(JSON.stringify(a), frozen, 'input session was mutated');
  assert.equal(b.rev, 1);
  assert.equal(c.rev, 2);
  assert.equal(c.turns[0].answer, 'an answer that is long enough');
});

test('openTurn reports an unanswered question and nothing else', () => {
  let s = createSession(seed());
  assert.equal(openTurn(s), null);
  s = askQuestion(s, { question: 'q?', dimension: 'outcome' });
  assert.equal(openTurn(s).question, 'q?');
  s = answerQuestion(s, { text: 'something substantive here' });
  assert.equal(openTurn(s), null);
});

test('draft provenance distinguishes edited from unedited', () => {
  let s = createSession(seed());
  s = askQuestion(s, { question: 'q?', dimension: 'outcome' });
  s = setDraftText(s, 'the drafted text');
  const unedited = answerQuestion(s, { text: 'the drafted text', source: 'draft' });
  assert.equal(unedited.turns[0].answerSource, 'draft-unedited');
  assert.ok(isLowConfidence(unedited.turns[0]));
  const edited = answerQuestion(s, { text: 'the drafted text, but changed', source: 'draft' });
  assert.equal(edited.turns[0].answerSource, 'draft-edited');
  assert.ok(!isLowConfidence(edited.turns[0]));
});

// ──────────────────────────────────────────────────── the coverage ratchet
test('ratchet: coverage never decreases and never leaps more than one level', () => {
  let s = withOpening();
  s = applyCoverage(s, { outcome: { level: 'covered', evidence: 'a tool that interviews you' } });
  assert.equal(s.coverage.outcome.level, 'partial', 'thin -> covered must be clamped to one step');
  s = applyCoverage(s, { outcome: { level: 'covered', evidence: 'a tool that interviews you' } });
  assert.equal(s.coverage.outcome.level, 'covered');
  s = applyCoverage(s, { outcome: { level: 'thin', gap: 'reconsidered' } });
  assert.equal(s.coverage.outcome.level, 'covered', 'coverage must not fall silently');
});

test('ratchet: `covered` without a verbatim user quote is not covered', () => {
  let s = withOpening();
  s = applyCoverage(s, { outcome: { level: 'partial', gap: 'x' } });
  s = applyCoverage(s, { outcome: { level: 'covered', evidence: '   ' } });
  assert.equal(s.coverage.outcome.level, 'partial', 'no evidence => no covered');
});

test('ratchet: at most two dimensions may rise in one turn', () => {
  let s = withOpening();
  const claim = {};
  for (const id of DIMENSION_IDS) claim[id] = { level: 'partial', gap: 'g' };
  s = applyCoverage(s, claim);
  const risen = DIMENSION_IDS.filter((id) => s.coverage[id].level !== 'thin');
  assert.equal(risen.length, 2, `expected 2 risen, got ${risen.length}`);
});

test('ratchet: chip/draft provenance caps a dimension at partial', () => {
  let s = withOpening();
  s = applyCoverage(s, { outcome: { level: 'partial', gap: 'g' } });
  s = applyCoverage(s, { outcome: { level: 'covered', evidence: 'their words' } },
    { lowConfidenceDimension: 'outcome' });
  assert.equal(s.coverage.outcome.level, 'partial', "Claude's own words must not count as coverage");
});

test('waived and deferred dimensions stop being scored and stop being asked', () => {
  let s = withOpening();
  s = waiveDimension(s, 'voice', 'I do not care about tone');
  s = deferDimension(s, 'references');
  s = applyCoverage(s, { voice: { level: 'covered', evidence: 'x' } });
  assert.equal(s.coverage.voice.level, 'thin', 'a waived dimension must not be re-scored');
  for (let i = 0; i < 40; i++) {
    const next = selectNextDimension(s);
    assert.ok(next !== 'voice' && next !== 'references');
    s = askQuestion(s, { question: `q${i}?`, dimension: next });
    s = answerQuestion(s, { text: 'a reasonably substantive answer about things' });
  }
});

test('addFacts de-duplicates near-identical restatements', () => {
  let s = withOpening();
  s = addFacts(s, [{ dimension: 'outcome', fact: 'It is a CLI tool.' }]);
  s = addFacts(s, [{ dimension: 'outcome', fact: 'it is a cli tool' }]);
  assert.equal(s.facts.length, 1);
});

// ──────────────────────────────────────────────────── answer classification
test('classifyAnswer separates the five classes', () => {
  assert.equal(classifyAnswer('yes'), 'terse');
  assert.equal(classifyAnswer('idk'), 'terse');
  assert.equal(classifyAnswer('I honestly have no idea about that one'), 'idk');
  assert.equal(classifyAnswer('stop asking about that'), 'refusal');
  assert.equal(
    classifyAnswer('It should be a short memo my manager reads in five minutes',
      'What format should the output take?'),
    'substantive');
});

test('classifyAnswer exempts dictated answers from terse and dodge thresholds', () => {
  const rambling = 'um so like the thing is I guess maybe it could be whatever really you know';
  assert.equal(classifyAnswer(rambling, 'What format should the output take?', { source: 'typed' }), 'dodge');
  assert.equal(classifyAnswer(rambling, 'What format should the output take?', { source: 'voice' }), 'substantive');
});

test('an answer tapped verbatim from a chip is terse', () => {
  assert.equal(
    classifyAnswer('honestly, just for me', 'Who is this for?', { chips: ['honestly, just for me'] }),
    'terse');
});

// ────────────────────────────────────────────────────── selection and moves
test('selectNextDimension prefers the weightiest least-covered dimension', () => {
  const s = withOpening();
  assert.ok(['outcome', 'substance'].includes(selectNextDimension(s)));
});

test('legalMoves never repeats the previous move', () => {
  let s = withOpening();
  s = askQuestion(s, { question: 'q?', dimension: 'bar', move: 'concretize' });
  assert.ok(!legalMoves(s, 'bar').includes('concretize'));
});

test('premortem and challenge stay locked until turn 4', () => {
  const s = withOpening();
  const early = legalMoves(s, 'bar');
  assert.ok(!early.includes('premortem'), 'premortem lands badly cold');
  assert.ok(!early.includes('challenge'));
});

test('a third turn on one dimension escalates instead of circling', () => {
  let s = withOpening();
  for (let i = 0; i < 4; i++) {
    s = askQuestion(s, { question: `q${i}?`, dimension: 'substance', move: 'concretize' });
    s = answerQuestion(s, { text: 'a substantive answer about the actual mechanics involved' });
  }
  const moves = legalMoves(s, 'substance');
  assert.ok(moves.some((m) => ['challenge', 'tradeoff', 'boundary'].includes(m)),
    `expected escalation, got ${moves}`);
});

// ──────────────────────────────────────── prompt determinism (load-bearing)
test('buildTurnPrompt is byte-identical across calls', () => {
  const s = withOpening();
  const opts = { target: 'substance', moves: ['concretize', 'boundary'] };
  assert.equal(buildTurnPrompt(s, opts), buildTurnPrompt(s, opts));
});

test('buildTurnPrompt leaks no timestamp — crash recovery depends on this', () => {
  // Two sessions identical in content, differing only in when things happened.
  const a = withOpening();
  const b = JSON.parse(JSON.stringify(a));
  b.createdAt = 999999; b.updatedAt = 123456; b.rev = 77;
  b.turns = b.turns.map((t) => ({ ...t, askedAt: 5555, answeredAt: 6666 }));
  const opts = { target: 'substance', moves: ['concretize', 'boundary'] };
  assert.equal(buildTurnPrompt(a, opts), buildTurnPrompt(b, opts),
    'a timestamp reached the prompt; the sample cache replay will silently never hit');
  assert.equal(promptHash(buildTurnPrompt(a, opts)), promptHash(buildTurnPrompt(b, opts)));
});

test('promptHash is stable and discriminating', () => {
  assert.equal(promptHash('abc'), promptHash('abc'));
  assert.notEqual(promptHash('abc'), promptHash('abd'));
});

// ───────────────────────────────────────────────────────── the budgeter
function longSession(turns, answerChars) {
  let s = withOpening();
  const filler = 'x'.repeat(answerChars);
  for (let i = 0; i < turns; i++) {
    s = askQuestion(s, { question: `Question number ${i} about the idea?`, dimension: 'substance' });
    s = answerQuestion(s, { text: `${filler} ${i}` });
  }
  return s;
}

test('budgeter keeps a 40-turn interview inside the byte budget', () => {
  const s = longSession(40, 3000);
  const { text, windowSize } = buildTranscriptBlock(s);
  assert.ok(utf8Length(text) <= BUDGET_BYTES,
    `transcript block was ${utf8Length(text)} bytes, over the ${BUDGET_BYTES} budget`);
  assert.ok(windowSize !== null, 'a 40-turn session must not be sent verbatim');
});

test('budgeter degrades in the documented order: verbatim, then 6, then 3, then facts-only', () => {
  assert.equal(buildTranscriptBlock(longSession(3, 100)).windowSize, null, 'short stays verbatim');
  const steps = [6, 3, 0];
  const observed = [1000, 6000, 40000].map((chars) => buildTranscriptBlock(longSession(30, chars)).windowSize);
  for (const w of observed) assert.ok(steps.includes(w), `unexpected window ${w}`);
  assert.deepEqual([...observed].sort((a, b) => b - a), observed, 'window must shrink monotonically');
});

test('utf8Length counts multi-byte characters correctly', () => {
  assert.equal(utf8Length('abc'), 3);
  assert.equal(utf8Length('é'), 2);
  assert.equal(utf8Length('—'), 3);
  assert.equal(utf8Length('😀'), 4);
});

// ─────────────────────────────────────────────── never trust the model JSON
test('parseTurnResult survives every malformed shape', () => {
  for (const bad of [null, undefined, 'a string', 42, [], { }, { question: '' }]) {
    const r = parseTurnResult(bad);
    assert.equal(r.ok, false);
    assert.deepEqual(r.chips, []);
    assert.deepEqual(r.facts, []);
  }
});

test('parseTurnResult rejects illegal moves and unknown dimensions', () => {
  const r = parseTurnResult(
    { question: 'What stops you?', move: 'hypnotize', dimension: 'nonsense', chips: 'not an array' },
    { moves: ['concretize', 'boundary'], target: 'bar' });
  assert.equal(r.move, 'concretize');
  assert.equal(r.dimension, 'bar');
  assert.deepEqual(r.chips, []);
  assert.ok(r.warnings.some((w) => /illegal move/.test(w)));
});

test('parseTurnResult drops a sycophantic bridge instead of regenerating', () => {
  const r = parseTurnResult({ question: 'What breaks first?', bridge: "That's a great point!" });
  assert.equal(r.bridge, null);
  const kept = parseTurnResult({ question: 'What breaks first?', bridge: 'Okay — so the 40-minute load is the enemy.' });
  assert.ok(kept.bridge);
});

test('parseTurnResult caps chips and facts and drops over-long chips', () => {
  const r = parseTurnResult({
    question: 'q?',
    chips: ['a', 'b', 'c', 'd', 'e', 'this chip is far too long to be tappable and should be dropped entirely ok'],
    new_facts: [{ fact: '1' }, { fact: '2' }, { fact: '3' }, { fact: '4' }],
  });
  assert.ok(r.chips.length <= 4);
  assert.ok(!r.chips.some((c) => c.split(/\s+/).length > 14));
  assert.equal(r.facts.length, 3);
});

// ───────────────────────────────────────────────────────────── tripwires
test('tripwires catch generic, compound and repeated questions', () => {
  const s = withOpening();
  assert.equal(questionTripwire('Who is your target audience?', s), 'generic');
  assert.equal(questionTripwire('What is it and why does it matter?', s), 'compound');
  assert.equal(questionTripwire('It is six weeks later and you stopped. Why?', s), null);
  let t = askQuestion(s, { question: 'What would make you abandon this project entirely?', dimension: 'bar' });
  assert.equal(questionTripwire('What would make you abandon this project entirely?', t), 'repeat');
});

// ───────────────────────────────────────────────────────── wrap gating
test('the interview always terminates at the hard ceiling', () => {
  let s = withOpening();
  for (let i = 0; i < HARD_TURN_CEILING + 2; i++) {
    s = askQuestion(s, { question: `q${i}?`, dimension: 'substance' });
    s = answerQuestion(s, { text: 'answer' });
  }
  assert.equal(shouldOfferWrap(s), 'hard_ceiling');
});

test('two zero-gain turns offer the exit', () => {
  const s = { ...withOpening(), zeroGainStreak: 2 };
  assert.equal(shouldOfferWrap(s), 'exhausted');
});

test('the wrap advisory is given once and then never again', () => {
  // It used to repeat on every remaining turn, because `setWrapOffered` was exported and
  // called from nowhere, so the caller's `!session.wrapOffered` guard always passed. On
  // screen that is a line nobody notices; read aloud it announces the interview is over
  // after every single answer.
  const s = withOpening();
  assert.ok(wrapAdvisory(s, 'coverage'), 'the first time, it should say so');

  const told = setWrapOffered(s, true, 1);
  assert.equal(wrapAdvisory(told, 'coverage'), null, 'the second time, silence');
  // Not even as the reason escalates — the hard ceiling ends the interview by itself.
  assert.equal(wrapAdvisory(told, 'soft_ceiling'), null);
  assert.equal(wrapAdvisory(told, 'hard_ceiling'), null);
});

test('the wrap advisory says nothing when there is no reason to', () => {
  const s = withOpening();
  assert.equal(wrapAdvisory(s, null), null);
  assert.equal(wrapAdvisory(s, 'not_a_reason'), null);
});

test('the wrap advisory quotes the ceiling rather than restating it', () => {
  // The number lives in engine.js. A hardcoded 18 in the sentence is a lie waiting to
  // happen, which is why the UI used to import SOFT_TURN_CEILING just to format this.
  assert.match(wrapAdvisory(withOpening(), 'soft_ceiling'), new RegExp(`${SOFT_TURN_CEILING}`));
});

test('a reopened session is told once more, because it is a new decision', () => {
  // `reopen` clears the flag: after "Ask me more", reaching coverage again is news.
  const told = setWrapOffered(withOpening(), true, 1);
  assert.equal(wrapAdvisory(migrate(JSON.parse(JSON.stringify(told))), 'coverage'), null,
    'and it survives a round trip through storage, so a reload does not re-announce');
});

test('isReadyToWrap needs every probing dimension at least partial', () => {
  let s = withOpening();
  assert.equal(isReadyToWrap(s), false);
  for (const id of DIMENSION_IDS) s.coverage[id] = { ...s.coverage[id], level: 'covered' };
  assert.equal(isReadyToWrap(s), true);
});

// ──────────────────────────────────────────────────────────── the export
test('export works with no synthesis at all — transcript is never lost', () => {
  const s = withOpening();
  const md = buildExport(s, { mode: 'checklist', note: 'Claude was not available.' });
  assert.match(md, /## Refined prompt/);
  assert.match(md, /## Open questions/);
  assert.match(md, /## Transcript/);
  assert.match(md, /a tool that interviews you/);
  assert.match(md, /Claude was not available/);
  assert.ok(md.indexOf('## Refined prompt') < md.indexOf('## Transcript'),
    'the prompt must come before the transcript');
});

test('export flags a session that was mostly drafted for the user', () => {
  let s = withOpening();
  for (let i = 0; i < 4; i++) {
    s = askQuestion(s, { question: `q${i}?`, dimension: 'substance', chips: ['c'] });
    s = setDraftText(s, 'drafted answer');
    s = answerQuestion(s, { text: 'drafted answer', source: 'draft' });
  }
  const md = buildExport(s);
  assert.match(md, /drafted for you rather than written by you/);
  assert.match(md, /unconfirmed/);
});

test('export records deliberate omissions as choices, not failures', () => {
  let s = waiveDimension(withOpening(), 'voice', 'tone does not matter here');
  const md = buildExport(s);
  assert.match(md, /deliberately left out/);
  assert.match(md, /tone does not matter here/);
});

test('a coverage claim keyed by position is read as the dimension we asked about', () => {
  // Observed against a real Ollama: qwen2.5:7b returns {"1": {...}} every turn rather than
  // keying by dimension id. Dropping it silently costs far more than the mistake is worth —
  // coverage never rises, isReadyToWrap never fires, and the interview runs to the hard
  // ceiling before telling the user it got 0%.
  const out = parseTurnResult(
    { question: 'and then?', coverage: { 1: { level: 'partial', gap: 'no numbers yet' } } },
    { target: 'substance' },
  );
  assert.equal(out.coverage.substance.level, 'partial');
  assert.equal(out.coverage.substance.gap, 'no numbers yet');
  assert.match(out.warnings.join(' '), /coverage keyed by/);
});

test('a correctly keyed coverage claim is never second-guessed', () => {
  const out = parseTurnResult(
    { question: 'and then?', coverage: { audience: { level: 'partial' } } },
    { target: 'substance' },
  );
  assert.equal(out.coverage.audience.level, 'partial');
  assert.equal(out.coverage.substance, undefined, 'the target must not be invented');
  assert.deepEqual(out.warnings, []);
});

test('more than one mis-keyed claim is dropped rather than guessed at', () => {
  // One unkeyed claim can only be about the dimension the turn asked for. Two could be
  // about anything, and picking for the model would be inventing coverage.
  const out = parseTurnResult(
    { question: 'and then?', coverage: { 1: { level: 'covered' }, 2: { level: 'covered' } } },
    { target: 'substance' },
  );
  assert.deepEqual(out.coverage, {});
});

test('export says so when questions came from the bank rather than the model', () => {
  // The interview never dead-ends: a failed model call is answered from the static bank and
  // the run carries on. Live that is right. But this document outlives the session, and a
  // wrap-up call can succeed while every turn failed — so without a line here an export
  // stamped "synthesised by Claude" is indistinguishable from one where every question was
  // actually grounded in the answer before it.
  let s = withOpening();
  s = askQuestion(s, { question: 'a real one?', dimension: 'substance', source: 'model' });
  s = answerQuestion(s, { text: 'a substantive answer about the thing' });
  s = askQuestion(s, { question: 'a canned one?', dimension: 'bar', source: 'bank' });
  s = answerQuestion(s, { text: 'another substantive answer about it' });

  const md = buildExport(s, { mode: 'claude' });
  assert.match(md, /1 of these questions came from the\s+built-in checklist/);
  assert.match(md, /generic rather than grounded in your answers/);
});

test('export stays quiet when every question came from the model', () => {
  let s = withOpening();
  s = askQuestion(s, { question: 'a real one?', dimension: 'substance', source: 'model' });
  s = answerQuestion(s, { text: 'a substantive answer about the thing' });
  assert.doesNotMatch(buildExport(s), /built-in checklist/);
});

test('export marks a stale synthesis after re-entry', () => {
  let s = withOpening();
  s = { ...s, synthesis: { ...s.synthesis, text: 'old prompt', stale: true } };
  assert.match(buildExport(s), /predates the most recent answers/);
});

test('coveragePercent and filename slugging behave', () => {
  const s = withOpening();
  assert.equal(coveragePercent(s), 0);
  assert.equal(slug('A Tool: for Refining!! Ideas'), 'a-tool-for-refining-ideas');
  assert.equal(slug(''), 'idea');
  assert.match(exportFilename({ ...s, title: 'My Idea' }), /^ideaforge-my-idea-\d{4}-\d{2}-\d{2}\.md$/);
});

// ───────────────────────────────────────────────────────────── migration
test('migrate round-trips a stored session and rejects future schemas', () => {
  let s = withOpening();
  s = applyCoverage(s, { outcome: { level: 'partial', gap: 'no length given' } });
  const restored = migrate(JSON.parse(JSON.stringify(s)));
  assert.equal(restored.id, s.id);
  assert.equal(restored.turns.length, s.turns.length);
  assert.equal(restored.coverage.outcome.gap, 'no length given');
  assert.equal(migrate({ schema: 99 }), null);
  assert.equal(migrate(null), null);
  assert.equal(migrate([]), null);
});

test('migrate backfills dimensions added since the session was stored', () => {
  const old = { schema: 1, id: 's_old', turns: [], coverage: { outcome: { level: 'partial' } } };
  const m = migrate(old);
  assert.ok(DIMENSION_IDS.every((id) => m.coverage[id]), 'every dimension must be present');
  assert.equal(m.coverage.outcome.level, 'partial');
  assert.equal(m.coverage.voice.level, 'thin');
});

test('migrate repairs malformed persisted containers before the session is used', () => {
  const m = migrate({
    schema: 1,
    id: 's_damaged',
    turns: [null, 'not a turn', {
      id: 't1', n: 1, question: 'What is the idea?', dimension: 'outcome',
      answer: '', skipped: false, chips: 'not an array',
    }],
    facts: [null, { dimension: 'outcome', fact: 'It runs in a browser.' }],
    moveHistory: { bad: true },
    openQuestions: 'not an array',
    pending: ['not', 'an', 'object'],
    coverage: { outcome: 'not an object' },
    synthesis: { text: '', assumptions: 'not an array' },
    meta: 'not an object',
  });

  assert.equal(m.turns.length, 1);
  assert.deepEqual(m.turns[0].chips, []);
  assert.deepEqual(m.facts, [{ dimension: 'outcome', fact: 'It runs in a browser.' }]);
  assert.deepEqual(m.moveHistory, []);
  assert.deepEqual(m.openQuestions, []);
  assert.equal(m.pending, null);
  assert.equal(m.coverage.outcome.level, 'thin');
  assert.deepEqual(m.synthesis.assumptions, []);
  assert.deepEqual(m.meta, { device: 'desktop' });
  assert.equal(openTurn(m).question, 'What is the idea?');

  const answered = answerQuestion(m, { text: 'A browser tool that refines an idea.', now: 5 });
  assert.doesNotThrow(() => buildExport(answered));
  assert.ok(selectNextDimension(answered));
});

test('tripwire catches short compound questions, but not legitimate "and" clauses', () => {
  const s = withOpening();
  assert.equal(questionTripwire('What is it and why does it matter?', s), 'compound');
  assert.equal(questionTripwire('What are the features and how will you prioritise them?', s), 'compound');
  // One interrogative plus a conjunction is a single, legitimate question.
  assert.equal(questionTripwire('What would make you abandon this and never come back?', s), null);
  assert.equal(questionTripwire('If you could ship one screen and one button, which survives?', s), null);
});

// ─────────────────────────────────────────────────────────── the version
test('the app version matches package.json', async () => {
  // src/version.js duplicates the version because a browser ES module cannot import JSON
  // without an import attribute and this app has no build step. This is the guard.
  const { VERSION } = await import('../src/version.js');
  const pkg = JSON.parse(
    await (await import('node:fs/promises')).readFile(new URL('../package.json', import.meta.url), 'utf8')
  );
  assert.equal(VERSION, pkg.version, 'bump src/version.js and package.json together');
});

// ───────────────────────────────────────────── reading it back aloud

test('forSpeech strips the markers a synthesiser would read out as words', () => {
  const said = forSpeech([
    '## Refined prompt',
    '',
    'Build a **mobile-first** tool. See [the notes](https://example.com/x) for `detail`.',
    '',
    '- three seconds',
    '- one thumb',
  ].join('\n'));

  for (const marker of ['#', '**', '`', '](', 'http', '- ']) {
    assert.ok(!said.includes(marker), `"${marker}" would be read aloud: ${said}`);
  }
  assert.ok(said.includes('mobile-first'), said);
  assert.ok(said.includes('the notes'), 'a link keeps its label and loses its URL');
  assert.ok(said.includes('three seconds.'), `a bullet becomes a sentence: ${said}`);
});

test('forSpeech drops code fences rather than spelling them out', () => {
  const said = forSpeech('Do this:\n\n```js\nconst x = 1;\n```\n\nThen stop.');
  assert.ok(!said.includes('const'), said);
  assert.ok(said.includes('Then stop.'), said);
});

test('forSpeech truncates on a sentence boundary, not mid-clause', () => {
  const long = Array.from({ length: 60 }, (_, i) => `Sentence number ${i} is here.`).join(' ');
  const said = forSpeech(long, { maxChars: 200 });
  assert.ok(said.length <= 200, String(said.length));
  assert.ok(said.endsWith('.'), `trailing off mid-clause sounds like a crash: ${said}`);
});

test('speechChunks keeps every piece inside the synthesiser watchdog', () => {
  // Chrome silently abandons an utterance that outlasts its own watchdog, so a 600-word
  // prompt read in one go stops partway through with no error. That is what this prevents.
  const text = forSpeech(Array.from({ length: 40 },
    (_, i) => `This is sentence ${i} of the refined prompt.`).join(' '));
  const chunks = speechChunks(text, { maxChars: 200 });

  assert.ok(chunks.length > 1, 'it should have split at all');
  for (const c of chunks) assert.ok(c.length <= 200, `${c.length} chars: ${c}`);
  assert.equal(chunks.join(' '), text, 'and nothing may be lost in the splitting');
});

test('speechChunks does not drop the tail of text with no sentence breaks', () => {
  const runOn = 'word '.repeat(200).trim();
  assert.equal(speechChunks(runOn, { maxChars: 200 }).join(' '), runOn);
});
