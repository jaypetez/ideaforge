// Scenarios and cases for tools/vectors.mjs. See that file for what the vectors are for.
//
// Inputs come from two places. Scripted interviews (the README walkthrough plus a handful of
// awkward ones) give realistic sessions at every turn; hand-written cases cover what an
// interview never produces on its own — hostile model output, impossible coverage claims,
// odd speech. Each recorded call is serialised through JSON, so `undefined` disappears
// (Kotlin reads a missing key as null) and only plain data crosses the boundary.

import {
  createSession, askQuestion, answerQuestion, skipQuestion, setDraftText, applyCoverage,
  addFacts, migrate, normalizeSessionName, normalizeTags, sessionDisplayTitle, waiveDimension,
  deferDimension, resumeDimension, copySession, reopen, isLowConfidence,
} from '../../src/core/session.js';
import {
  contentWords, classifyAnswer, lastAnswerClass, selectNextDimension, isReadyToWrap,
  shouldOfferWrap, wrapAdvisory, legalMoves, buildTurnPromptParts, buildTurnPrompt,
  pickBankQuestion, promptHash, parseTurnResult, questionTripwire,
} from '../../src/core/engine.js';
import { utf8Length, buildTranscriptBlock, serializeFacts } from '../../src/core/digest.js';
import {
  buildSynthesisPromptParts, parseSynthesisResult,
} from '../../src/core/synthesis.js';
import {
  endsWithTrigger, stripTrigger, parseSpeech, matchAffirmation, normalizeTrigger,
  triggerWarning, spokenExamples,
} from '../../src/core/driving.js';
import {
  sessionsEqual, backupFilename, buildBackup, parseBackup,
} from '../../src/core/backup.js';
import {
  sessionLibraryStatus, sessionSearchText, libraryCard, availableTags, filterSessions,
} from '../../src/core/library.js';
import {
  coveragePercent, slug, exportFilename, buildExport, renderBlocks, forSpeech, speechChunks,
} from '../../src/core/markdown.js';
import { seedTurn, submitAnswer, runTurn, resumeTurn } from '../../src/runtime/turn.js';
import { runSynthesis } from '../../src/runtime/synthesize.js';
import { levelSnapshot, isZeroGain } from '../../src/runtime/gain.js';
import { createDriveLoop } from '../../src/runtime/drive.js';
import { extractJson } from '../../src/providers/json.js';
import { codeForStatus, retryAfterMs, isRetryable, ProviderError } from '../../src/providers/errors.js';
import { applyAuth, isLoopback, AUTH_BEARER, AUTH_NONE } from '../../src/providers/http.js';
import { resolveTiers, presetModels, createProvider, PROVIDER_CHOICES } from '../../src/providers/index.js';
import { parseModelList, OPENAI_COMPAT_PRESETS } from '../../src/providers/openaiCompat.js';
import { ANTHROPIC_AUTH, ANTHROPIC_TIERS } from '../../src/providers/anthropic.js';
import { ANSWERS, TURNS, SYNTHESIS } from './walkthrough.mjs';

// ─────────────────────────────────────────────────────────────── recording

const plain = (v) => (v === undefined ? null : JSON.parse(JSON.stringify(v)));

/** Record one synchronous call. Args are captured before the call, results after. */
function rec(name, fn, ...args) {
  const captured = plain(args);
  try {
    return { name, fn: fn.name, args: captured, expect: { value: plain(fn(...args)) } };
  } catch (err) {
    return { name, fn: fn.name, args: captured, expect: { throws: String(err && err.message) } };
  }
}

const errorOf = (err) => (err ? { code: err.code || null, message: String(err.message || err) } : null);

// ─────────────────────────────────────────────────────────────── scripted interviews

const T0 = 1_760_000_000_000;              // a fixed epoch; every clock below counts from it
const tick = (n) => T0 + n * 1000;

/**
 * A provider whose replies are scripted. Each entry is `{json}`, `{error: code}` or a
 * function of the prompt parts. Records every request it receives.
 */
function scriptedProvider(script) {
  const requests = [];
  let i = 0;
  return {
    requests,
    async sampleJson(parts, opts = {}) {
      requests.push({ parts: { ...parts }, modelTier: opts.modelTier || null });
      const step = script[Math.min(i, script.length - 1)];
      i += 1;
      const r = typeof step === 'function' ? step(parts) : step;
      if (r.error) throw Object.assign(new Error(`scripted ${r.error}`), { code: r.error });
      return { json: r.json, text: JSON.stringify(r.json), modelTierApplied: r.tier || null };
    },
  };
}

const turnNumberOf = (parts) => Number((/Turn number: (\d+)/.exec(parts.tail) || [])[1]);

/** The README walkthrough, driven through the real runtime. */
async function walkthrough() {
  const snapshots = [];
  const provider = scriptedProvider([(parts) => {
    const reply = TURNS[turnNumberOf(parts)];
    return reply ? { json: reply } : { error: 'bad_response' };
  }]);
  let s = seedTurn(createSession({ id: 's_walkthrough', now: tick(0), device: 'phone' }), { now: tick(0) });
  snapshots.push(['seeded', s]);
  let n = 1;
  for (const answer of ANSWERS) {
    s = submitAnswer(s, { text: answer, now: tick(n * 10) });
    snapshots.push([`answered-${n}`, s]);
    if (n === ANSWERS.length) break;
    const out = await runTurn(s, { provider, now: tick(n * 10 + 5) });
    s = out.session;
    snapshots.push([`asked-${n + 1}`, s]);
    n += 1;
  }
  const synthProvider = scriptedProvider([{ json: SYNTHESIS, tier: 'complex-model' }]);
  const synth = await runSynthesis(s, { provider: synthProvider, now: tick(999) });
  snapshots.push(['synthesized', synth.session]);
  return { snapshots, final: synth.session };
}

/** Sessions that exercise branches the walkthrough never reaches. */
function oddSessions() {
  const base = (id) => createSession({ id, now: tick(0) });
  const out = [];

  out.push(['fresh', base('s_fresh')]);

  // Voice, chip and draft answers: provenance caps and the dodge exemption.
  let v = seedTurn(base('s_voice'), { now: tick(1) });
  v = submitAnswer(v, { text: 'so um basically a thing that reminds me to water the plants on the balcony when it has not rained for a while and the soil is dry', source: 'voice', now: tick(2) });
  v = askQuestion(v, { question: 'Which plants die first?', dimension: 'substance', chips: ['the basil', 'the tomatoes'], now: tick(3) });
  v = submitAnswer(v, { text: 'the basil', source: 'chip', now: tick(4) });
  v = askQuestion(v, { question: 'How would you know it worked?', dimension: 'bar', chips: [], now: tick(5) });
  v = setDraftText(v, 'The basil survives August.', tick(6));
  v = answerQuestion(v, { text: 'The basil survives August.', source: 'draft', classification: 'substantive', now: tick(7) });
  out.push(['voice-chip-draft', v]);

  // Skips, refusals, a waived and a deferred dimension.
  let k = seedTurn(base('s_skips'), { now: tick(1) });
  k = submitAnswer(k, { text: 'A tool for splitting restaurant bills fairly among friends', now: tick(2) });
  k = askQuestion(k, { question: 'Who pays first?', dimension: 'audience', now: tick(3) });
  k = skipQuestion(k, { now: tick(4) });
  k = askQuestion(k, { question: 'What does fair mean here?', dimension: 'bar', now: tick(5) });
  k = submitAnswer(k, { text: 'I would rather not say', now: tick(6) });
  k = waiveDimension(k, 'voice', 'not relevant', tick(7));
  k = deferDimension(k, 'references', tick(8));
  out.push(['skips-waived-deferred', k]);

  // Coverage pushed near the wrap-up threshold, with facts and a long transcript.
  let w = seedTurn(base('s_wide'), { now: tick(1) });
  w = submitAnswer(w, { text: 'A newsletter for amateur astronomers in the southern hemisphere 🌌 with dark-sky maps', now: tick(2) });
  for (let i = 0; i < 12; i++) {
    w = askQuestion(w, { question: `Question ${i}: what about the sky near the ${['horizon', 'zenith', 'pole'][i % 3]} in month ${i}?`, dimension: ['substance', 'bar', 'constraints', 'audience', 'voice', 'references', 'outcome'][i % 7], now: tick(10 + i) });
    w = submitAnswer(w, { text: `Answer ${i} — the southern sky in month ${i} shows the Magellanic Clouds clearly from rural areas, which is what my readers actually want to find on a clear night ${'and more detail '.repeat(i % 4)}`.trim(), now: tick(30 + i) });
    w = applyCoverage(w, { [['substance', 'bar', 'constraints', 'audience', 'voice', 'references', 'outcome'][i % 7]]: { level: 'covered', evidence: `quote ${i}`, gap: null } }, { now: tick(40 + i) });
  }
  w = addFacts(w, [{ dimension: 'audience', fact: 'Readers live south of the equator.' }, { dimension: 'substance', fact: 'Dark-sky maps are the core.' }], tick(60));
  out.push(['wide', w]);

  return out;
}

const DIMS = ['outcome', 'substance', 'bar', 'constraints', 'audience', 'voice', 'references'];

// ─────────────────────────────────────────────────────────────── families

async function sessionFamily() {
  const { snapshots } = await walkthrough();
  const mid = snapshots.find(([n]) => n === 'answered-3')[1];
  const fresh = createSession({ id: 's_cov', now: tick(0) });
  const partialAll = applyCoverage(fresh, Object.fromEntries(DIMS.map((d) => [d, { level: 'partial', gap: `gap ${d}` }])), { now: 1 });
  const cases = [
    rec('createSession', createSession, { id: 's_new', now: tick(0), device: 'phone' }),
    rec('createSession without id throws', createSession, {}),
    rec('ratchet: one level per turn', applyCoverage, fresh, { outcome: { level: 'covered', evidence: 'they said it', gap: null } }, { now: 2 }),
    rec('ratchet: never falls', applyCoverage, partialAll, { outcome: { level: 'thin', gap: 'worse' } }, { now: 2 }),
    rec('ratchet: at most two dimensions rise', applyCoverage, fresh, Object.fromEntries(DIMS.map((d) => [d, { level: 'partial' }])), { now: 2 }),
    rec('covered without evidence is partial', applyCoverage, partialAll, { bar: { level: 'covered', gap: null } }, { now: 2 }),
    rec('covered with blank evidence is partial', applyCoverage, partialAll, { bar: { level: 'covered', evidence: '   ' } }, { now: 2 }),
    rec('covered with evidence', applyCoverage, partialAll, { bar: { level: 'covered', evidence: 'three seconds, one thumb', gap: 'ignored' } }, { now: 2 }),
    rec('low-confidence dimension capped at partial', applyCoverage, partialAll, { bar: { level: 'covered', evidence: 'q' } }, { lowConfidenceDimension: 'bar', now: 2 }),
    rec('capped dimension stays partial', applyCoverage, partialAll, { audience: { level: 'covered', evidence: 'q' } }, { cappedDimensions: ['audience'], now: 2 }),
    rec('waived dimension ignores claims', applyCoverage, waiveDimension(partialAll, 'voice', 'n/a', 1), { voice: { level: 'covered', evidence: 'q' } }, { now: 2 }),
    rec('garbage claims ignored', applyCoverage, fresh, { outcome: { level: 'excellent' }, nope: { level: 'covered' }, bar: null }, { now: 2 }),
    rec('null claims', applyCoverage, fresh, null, { now: 2 }),
    rec('gap kept when new gap blank', applyCoverage, partialAll, { outcome: { level: 'partial', gap: '' } }, { now: 2 }),
    rec('addFacts', addFacts, mid, [{ dimension: 'bar', fact: 'Three seconds.' }, { dimension: 'nope', fact: 'x' }, { fact: '' }, null], 5),
    rec('skipQuestion on a closed turn throws', skipQuestion, mid, { now: 3 }),
    rec('answerQuestion with draft source unedited', answerQuestion, setDraftText(askQuestion(fresh, { question: 'Q?', dimension: 'bar', now: 1 }), 'draft words', 2), { text: 'draft words', source: 'draft', now: 3 }),
    rec('answerQuestion with draft source edited', answerQuestion, setDraftText(askQuestion(fresh, { question: 'Q?', dimension: 'bar', now: 1 }), 'draft words', 2), { text: 'draft words, edited', source: 'draft', now: 3 }),
    rec('askQuestion without question throws', askQuestion, fresh, { dimension: 'bar' }),
    rec('isLowConfidence chip', isLowConfidence, { answerSource: 'chip' }),
    rec('isLowConfidence typed', isLowConfidence, { answerSource: 'typed' }),
    rec('resumeDimension', resumeDimension, deferDimension(fresh, 'bar', 1), 'bar', 2),
    rec('copySession', copySession, mid, { id: 's_copy', now: tick(5) }),
    rec('reopen', reopen, { ...mid, status: 'done' }, tick(6)),
    rec('normalizeSessionName', normalizeSessionName, `  A  name\twith   spaces ${'x'.repeat(130)}  `),
    rec('normalizeSessionName non-string', normalizeSessionName, 42),
    rec('normalizeTags', normalizeTags, ['Work', ' work ', '', 'a'.repeat(40), 'Ideas', 7, 'ideas', 't1', 't2', 't3', 't4', 't5', 't6', 't7', 't8', 't9']),
    rec('sessionDisplayTitle name wins', sessionDisplayTitle, { ...mid, name: 'Mine', title: 'Model' }),
    rec('sessionDisplayTitle falls back', sessionDisplayTitle, { ...fresh }),
    rec('migrate future schema', migrate, { ...mid, schema: 99 }),
    rec('migrate garbage', migrate, 'not a session'),
    rec('migrate array', migrate, []),
    rec('migrate schema 1 with junk', migrate, { schema: 1, id: 's_old', createdAt: 5, turns: [{ question: 'q', chips: ['a', 3, null] }, 'bad', null], facts: [{ fact: 'f' }, 2], moveHistory: ['concretize', 9], tags: ['A', 'a'], archivedAt: -1, name: '  n  ' }),
    rec('migrate current', migrate, mid),
    rec('migrate missing id', migrate, { schema: 2 }),
  ];
  return { source: 'src/core/session.js', cases };
}

async function engineFamily() {
  const { snapshots } = await walkthrough();
  const all = [...snapshots, ...oddSessions()];
  const cases = [];
  for (const [label, s] of all) {
    const target = selectNextDimension(s);
    cases.push(rec(`selectNextDimension ${label}`, selectNextDimension, s));
    cases.push(rec(`lastAnswerClass ${label}`, lastAnswerClass, s));
    cases.push(rec(`isReadyToWrap ${label}`, isReadyToWrap, s));
    cases.push(rec(`shouldOfferWrap ${label}`, shouldOfferWrap, s));
    cases.push(rec(`pickBankQuestion ${label}`, pickBankQuestion, s, target));
    if (target) {
      for (const cls of ['substantive', 'terse', 'dodge', 'idk', 'refusal']) {
        cases.push(rec(`legalMoves ${label} ${cls}`, legalMoves, s, target, cls));
      }
    }
  }
  for (const reason of ['coverage', 'soft_ceiling', 'hard_ceiling', 'exhausted', null, 'nonsense']) {
    cases.push(rec(`wrapAdvisory ${reason}`, wrapAdvisory, snapshots[snapshots.length - 1][1], reason));
  }
  const q = 'What would make you stop using it in the first week?';
  const answers = [
    ['', 'typed'], ['   ', 'typed'], ['skip this', 'typed'], ["I'd rather not say", 'typed'],
    ["I don't know", 'typed'], ['no idea honestly', 'typed'], ['yes', 'typed'], ['maybe later on', 'typed'],
    ['the basil', 'chip', ['the basil', 'tomatoes']], ['THE BASIL', 'typed', ['the basil']],
    ['If it made me type while someone was still talking to me I would stop using it in the first week for sure', 'typed'],
    ['Honestly the weather has been strange and my cat knocked over a plant yesterday which was annoying for everyone involved', 'typed'],
    ['Honestly the weather has been strange and my cat knocked over a plant yesterday which was annoying for everyone involved', 'voice'],
    ['ok', 'voice'], ['four words only here', 'typed'], ['four words only here', 'voice'],
  ];
  for (const [i, [text, source, chips]] of answers.entries()) {
    cases.push(rec(`classifyAnswer ${i} ${source}`, classifyAnswer, text, q, { source, chips: chips || [] }));
  }
  cases.push(rec('classifyAnswer defaults', classifyAnswer, 'a substantive answer with enough words'));
  for (const text of ['', "It's the user's DREAM — 100% real, isn't it?", 'and the of to a in is it', null]) {
    cases.push(rec(`contentWords ${JSON.stringify(text)}`, contentWords, text));
  }
  const mid = snapshots.find(([n]) => n === 'answered-4')[1];
  const tripwires = [
    'What is the target audience for this?', 'Who is it for? And why now?',
    'What is it and why does it matter?', 'How many names did you actually lose at the last conference?',
    'At the last conference, how many names did you actually lose?',
    `Tell me about ${'the very long winding '.repeat(4)}thing and what else`, 'Why?', '', null,
    'Which value prop matters most', 'what WHO when, or where',
  ];
  for (const [i, t] of tripwires.entries()) cases.push(rec(`questionTripwire ${i}`, questionTripwire, t, mid));

  const moves = ['concretize', 'contrast', 'stress_test'];
  const hostile = [
    null, 'string', [], 42, {},
    { question: '  Who reads it?  ', bridge: 'Great point!', move: 'concretize', dimension: 'audience' },
    { question: 'Q?', bridge: 'One two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen', move: 'bogus' },
    { question: 'Q?', bridge: 'Thirty names, gone overnight.', move: 'contrast', dimension: 'nope', chips: ['a', '', 3, 'b', 'c', 'd', 'e', 'f'], new_facts: [{ dimension: 'bar', fact: ' f ' }, { fact: 'no dim' }, 'x'], coverage: { bar: { level: 'covered', evidence: ' "quote" ', gap: 7 }, nope: { level: 'covered' }, outcome: 'covered' }, suggest_wrap: 'true', wrap_reason: ' done ' },
    { question: 'Q?', coverage: { 1: { level: 'partial', gap: 'unkeyed claim' } } },
    { question: 'Q?', coverage: [{ level: 'partial' }] },
    { question: 'Q?', suggest_wrap: true, wrap_reason: 'enough', chips: 'not an array', new_facts: 'nope' },
  ];
  for (const [i, raw] of hostile.entries()) {
    cases.push(rec(`parseTurnResult ${i}`, parseTurnResult, raw, { moves, target: 'bar' }));
  }
  cases.push(rec('parseTurnResult defaults', parseTurnResult, { question: 'Q?', move: 'concretize' }));
  return { source: 'src/core/engine.js', cases };
}

async function turnPromptFamily() {
  const { snapshots } = await walkthrough();
  const all = [...snapshots, ...oddSessions()];
  const cases = [];
  for (const [label, s] of all) {
    const target = selectNextDimension(s);
    if (!target) continue;
    const moves = legalMoves(s, target, lastAnswerClass(s));
    for (const [suffix, opts] of [
      ['', { target, moves }],
      [' injected', { target, moves, injected: 'Your previous question was compound. Ask ONE thing, in fewer than 20 words.' }],
      [' tiny budget', { target, moves, budget: 900 }],
    ]) {
      cases.push(rec(`buildTurnPromptParts ${label}${suffix}`, buildTurnPromptParts, s, opts));
      // The prompt itself is already pinned by the parts above; this pins the one join that
      // defines "the prompt" (system, prefix and tail joined by newlines) and its hash.
      const prompt = buildTurnPrompt(s, opts);
      cases.push({ name: `turnPromptHash ${label}${suffix}`, fn: 'turnPromptHash', args: plain([s, opts]), expect: { value: promptHash(prompt), length: prompt.length } });
    }
    for (const budget of [400, 2000, 48 * 1024]) {
      cases.push(rec(`buildTranscriptBlock ${label} ${budget}`, buildTranscriptBlock, s, budget));
    }
    cases.push(rec(`serializeFacts ${label}`, serializeFacts, s.facts));
  }
  for (const str of ['', 'a', 'é', '€', '🌌', 'mixed 🌌 é € text', '\u0000￿', 'x'.repeat(1000)]) {
    cases.push(rec(`promptHash ${JSON.stringify(str).slice(0, 24)}`, promptHash, str));
    cases.push(rec(`utf8Length ${JSON.stringify(str).slice(0, 24)}`, utf8Length, str));
  }
  return { source: 'src/core/engine.js#buildTurnPromptParts, src/core/digest.js', cases };
}

/**
 * run-turn cases: `{name, fn: 'runTurn'|'resumeTurn', args: [session, {now, modelTier?}],
 * script: [...], expect: {value: {session, turn, degraded, error, wrap, aborted, warnings,
 * calls}, requests: [{parts, modelTier}]}}`. `script` entries are `{json}` or `{error: code}`,
 * consumed one per provider call, the last repeating; `null` means no provider at all.
 */
async function runTurnFamily() {
  const { snapshots } = await walkthrough();
  const at = (n) => snapshots.find(([l]) => l === n)[1];
  const cases = [];
  const run = async (name, session, script, deps = {}, fn = runTurn) => {
    const provider = script ? scriptedProvider(script) : null;
    const out = await fn(session, { ...deps, provider });
    cases.push({
      name, fn: fn.name, args: plain([session, deps]), script: plain(script),
      expect: { value: plain({ ...out, error: errorOf(out.error) }), requests: plain(provider ? provider.requests : []) },
    });
  };
  const good = (n) => ({ json: TURNS[n] });
  await run('walkthrough turn 2', at('answered-1'), [good(2)], { now: tick(100) });
  await run('walkthrough turn 5', at('answered-4'), [good(5)], { now: tick(100) });
  await run('no provider: bank question', at('answered-1'), null, { now: tick(100) });
  await run('provider error: bank fallback keeps zeroGainStreak', { ...at('answered-4'), zeroGainStreak: 1 }, [{ error: 'network' }], { now: tick(100) });
  await run('aborted', at('answered-4'), [{ error: 'aborted' }], { now: tick(100) });
  await run('bad json then good', at('answered-1'), [{ json: { nope: true } }, good(2)], { now: tick(100) });
  await run('bad json twice: bank', at('answered-1'), [{ json: 'garbage' }], { now: tick(100) });
  await run('compound twice: unfixable', at('answered-1'), [{ json: { ...TURNS[2], question: 'Who is it for and why now?' } }], { now: tick(100) });
  await run('generic twice: accepted', at('answered-1'), [{ json: { ...TURNS[2], question: 'What is the target audience?' } }], { now: tick(100) });
  await run('repeat then fixed', at('answered-2'), [{ json: { ...TURNS[3], question: TURNS[2].question } }, good(3)], { now: tick(100) });
  // The answer just read was a chip: the cap belongs to THAT answer's dimension.
  // Both the chip answer's dimension and the next target start at partial, and both are
  // claimed covered with evidence. The correct port caps only the answered dimension (it
  // stays partial) and lets the target reach covered. Capping the target instead, or not
  // capping at all, changes the output, which is what makes this case worth having.
  const chipped = (() => {
    let s = at('asked-4');
    s = submitAnswer(s, { text: s.turns[s.turns.length - 1].chips[0] || 'a chip', source: 'chip', now: tick(50) });
    const answered = s.turns[s.turns.length - 1].dimension;
    const coverage = { ...s.coverage, [answered]: { ...s.coverage[answered], level: 'partial' } };
    let target = selectNextDimension({ ...s, coverage });
    if (target === answered) target = DIMS.find((d) => d !== answered && coverage[d].status === 'probing');
    coverage[target] = { ...coverage[target], level: 'partial' };
    return { session: { ...s, coverage }, answered, target };
  })();
  if (chipped.answered === chipped.target) throw new Error('chip-cap fixture needs two distinct dimensions');
  await run('chip answer caps the previous dimension, not the target', chipped.session, [{ json: { ...TURNS[5], coverage: {
    [chipped.answered]: { level: 'covered', evidence: 'they picked a chip' },
    [chipped.target]: { level: 'covered', evidence: 'a verbatim quote' },
  } } }], { now: tick(100) });
  // Zero gain after the ratchet: the model claims covered but nothing can rise.
  // Claims "covered" with no evidence on two partial dimensions: the ratchet clamps both
  // back to partial, so nothing rose and the streak must go up. A port that measured gain
  // from the claims would reset it to zero instead.
  const a4 = at('answered-4');
  const partials = DIMS.filter((d) => a4.coverage[d].level === 'partial').slice(0, 2);
  if (partials.length < 2) throw new Error('zero-gain fixture needs two partial dimensions');
  const claimed = { ...TURNS[5], coverage: Object.fromEntries(partials.map((d) => [d, { level: 'covered', gap: null }])) };
  await run('zero gain increments the streak', { ...at('answered-4'), zeroGainStreak: 1 }, [{ json: claimed }], { now: tick(100) });
  await run('gain resets the streak', { ...at('answered-4'), zeroGainStreak: 1 }, [good(5)], { now: tick(100) });
  const ceiling = (() => {
    let s = createSession({ id: 's_ceiling', now: tick(0) });
    for (let i = 0; i < 25; i++) {
      s = askQuestion(s, { question: `q${i}`, dimension: 'bar', now: tick(i) });
      s = answerQuestion(s, { text: `a${i}`, now: tick(i) });
    }
    return s;
  })();
  await run('hard ceiling', ceiling, [good(2)], { now: tick(100) });
  await run('model tier passed through', at('answered-1'), [good(2)], { now: tick(100), modelTier: 'quick' });
  await run('tiny budget', at('answered-4'), [good(5)], { now: tick(100), budget: 900 });
  // resumeTurn: pending matches; pending for a turn that already exists; prompt changed.
  const a1 = at('answered-1');
  const t1 = selectNextDimension(a1);
  const matching = promptHash(buildTurnPrompt(a1, { target: t1, moves: legalMoves(a1, t1, lastAnswerClass(a1)) }));
  await run('resume with the same prompt: no warning', { ...a1, pending: { kind: 'turn', turnId: 't2', promptHash: matching, startedAt: 1 } }, [good(2)], { now: tick(100) }, resumeTurn);
  const pendingFresh = { ...a1, pending: { kind: 'turn', turnId: 't2', promptHash: 'x', startedAt: 1 } };
  await run('resume with changed prompt', pendingFresh, [good(2)], { now: tick(100) }, resumeTurn);
  const pendingDone = { ...at('asked-2'), pending: { kind: 'turn', turnId: 't2', promptHash: 'x', startedAt: 1 } };
  await run('resume a completed turn', pendingDone, [good(3)], { now: tick(100) }, resumeTurn);
  await run('resume with nothing pending', at('answered-1'), [good(2)], { now: tick(100) }, resumeTurn);

  // seedTurn and submitAnswer are synchronous but belong with the runtime.
  const fresh = createSession({ id: 's_rt', now: tick(0) });
  cases.push(rec('seedTurn', seedTurn, fresh, { now: tick(1) }));
  cases.push(rec('submitAnswer voice', submitAnswer, seedTurn(fresh, { now: 1 }), { text: 'ok', source: 'voice', now: 2 }));
  cases.push(rec('submitAnswer reopens a done session', submitAnswer, { ...askQuestion(fresh, { question: 'Q?', dimension: 'bar', now: 1 }), status: 'done' }, { text: 'more', now: 3 }));
  cases.push(rec('submitAnswer with nothing open throws', submitAnswer, fresh, { text: 'x', now: 2 }));
  cases.push(rec('levelSnapshot', levelSnapshot, at('answered-4')));
  cases.push(rec('isZeroGain same', isZeroGain, levelSnapshot(at('answered-4')), levelSnapshot(at('answered-4'))));
  cases.push(rec('isZeroGain risen', isZeroGain, levelSnapshot(at('answered-1')), levelSnapshot(at('answered-4'))));
  return { source: 'src/runtime/turn.js, src/runtime/gain.js', cases };
}

async function synthesisFamily() {
  const { snapshots, final } = await walkthrough();
  const last = snapshots.find(([l]) => l === `answered-${ANSWERS.length}`)[1];
  const cases = [
    rec('buildSynthesisPromptParts walkthrough', buildSynthesisPromptParts, last),
    rec('buildSynthesisPromptParts tiny budget', buildSynthesisPromptParts, last, { budget: 900 }),
    rec('buildSynthesisPromptParts fresh', buildSynthesisPromptParts, createSession({ id: 's_x', now: 0 })),
  ];
  const hostile = [
    null, [], 'text', {}, SYNTHESIS,
    { title: ' One two three four five six seven eight nine ten ', prompt: '  body  ', assumptions: ['a', '', 3, 'b', 'c', 'd', 'e', 'f', 'g'], open_questions: 'nope' },
    { prompt: '', title: 'x' },
    { prompt: 'p', assumptions: 'x', open_questions: Array.from({ length: 12 }, (_, i) => `q${i}`) },
  ];
  for (const [i, raw] of hostile.entries()) cases.push(rec(`parseSynthesisResult ${i}`, parseSynthesisResult, raw));
  const run = async (name, session, script, deps) => {
    const provider = script ? scriptedProvider(script) : null;
    const out = await runSynthesis(session, { ...deps, provider });
    cases.push({
      name, fn: 'runSynthesis', args: plain([session, deps]), script: plain(script),
      expect: { value: plain({ ...out, error: errorOf(out.error) }), requests: plain(provider ? provider.requests : []) },
    });
  };
  await run('runSynthesis ok', last, [{ json: SYNTHESIS, tier: 'complex-model' }], { now: tick(999) });
  await run('runSynthesis no provider', last, null, { now: tick(999) });
  await run('runSynthesis error', last, [{ error: 'network' }], { now: tick(999) });
  await run('runSynthesis aborted', last, [{ error: 'aborted' }], { now: tick(999) });
  await run('runSynthesis unusable', last, [{ json: { title: 'x' } }], { now: tick(999) });
  await run('runSynthesis resumed from synthesizing', { ...last, status: 'synthesizing' }, [{ error: 'network' }], { now: tick(999) });
  cases.push(rec('final walkthrough export', buildExport, final));
  return { source: 'src/core/synthesis.js, src/runtime/synthesize.js', cases };
}

function speechFamily() {
  const cases = [];
  const texts = [
    'we went over budget', 'we went over budget over', 'we went over budget, over.', 'Over.', 'over',
    'skip this', 'skip this over', "I'd skip this one if I could", "I'd skip this one if I could, over",
    'repeat', 'repeat that please', 'repeat customers are the point over', 'scratch that', 'scratch that, over',
    'start over', 'wrap up', "let's wrap it up", 'pause', 'stop', 'exit', 'go back', 'next question',
    'OVER AND OUT', 'over the years we grew', '  over  ', '', 'ending with overture', 'done', "I'm done",
  ];
  for (const t of texts) {
    cases.push(rec(`parseSpeech ${JSON.stringify(t)}`, parseSpeech, t));
    cases.push(rec(`endsWithTrigger ${JSON.stringify(t)}`, endsWithTrigger, t));
    cases.push(rec(`stripTrigger ${JSON.stringify(t)}`, stripTrigger, t));
  }
  for (const t of ['all set, done', 'all set done', 'done', 'finished?', 'we are finished']) {
    cases.push(rec(`parseSpeech trigger=done ${JSON.stringify(t)}`, parseSpeech, t, { trigger: 'done' }));
    cases.push(rec(`endsWithTrigger done ${JSON.stringify(t)}`, endsWithTrigger, t, 'done'));
  }
  for (const t of ['yes', 'Yeah, sure.', 'yep', 'no', 'nope, not yet', 'no thanks', 'maybe', 'yes and no', '', 'go ahead', 'not yet', 'sure thing', 'ok']) {
    cases.push(rec(`matchAffirmation ${JSON.stringify(t)}`, matchAffirmation, t));
  }
  for (const t of [' Over ', 'OVER.', 'two words', '', '  ', 'done!', 'Ünïcödé', 'a', 'x'.repeat(40), null, 7]) {
    cases.push(rec(`normalizeTrigger ${JSON.stringify(t)}`, normalizeTrigger, t));
  }
  for (const t of ['over', 'done', 'the', 'a', 'and', 'skip', 'repeat', 'finished', 'yes', 'stop', 'thanks', '']) {
    cases.push(rec(`triggerWarning ${JSON.stringify(t)}`, triggerWarning, t));
  }
  const chipSets = [
    [], ['one'], ['one', 'two'], ['one', 'two', 'three'],
    ['maybe thirty names over three days', 'honestly I stopped counting', 'only a handful, but the ones that mattered', 'I never learned them in the first place'],
    ['a chip that is extremely long and goes on and on for many words well past any sensible budget for a spoken example sentence'],
    ['  trailing punctuation.  ', 'semicolon;', '', 7, null, 'ok,'],
  ];
  for (const [i, chips] of chipSets.entries()) {
    cases.push(rec(`spokenExamples ${i}`, spokenExamples, chips));
    cases.push(rec(`spokenExamples ${i} maxWords 6`, spokenExamples, chips, { maxWords: 6 }));
  }
  cases.push(rec('spokenExamples not an array', spokenExamples, 'nope'));
  return { source: 'src/core/driving.js', cases };
}

/**
 * drive-loop cases: a scripted io (the same contract as test/drive-loop.test.mjs's
 * scriptedIo). `input: {outcomes, turns, chips, offerAt, config, hasDraft}`; each outcome is
 * `{text}` or `{kind: 'throw', code, message}`, consumed one per listen with the last
 * repeating, and `stopAfter: n` ends the run after the nth listen. `expect: {result, log}`
 * where log entries are [effect, arg?] in order, including `state` changes.
 */
async function driveLoopFamily() {
  const scenarios = [
    ['answers six questions', [{ text: 'first answer over' }, { text: 'second answer over' }], { turns: 2 }],
    ['a capture without the trigger is still the answer', [{ text: 'we went over budget' }], { turns: 1 }],
    ['silence then answer', [{ text: '' }, { text: 'here it is over' }], { turns: 1 }],
    ['three silences re-read, then skip blind', [{ text: '' }, { text: '' }, { text: '' }, { text: 'finally over' }], { turns: 1 }],
    ['permanent silence skips blind then stops', [{ text: '' }], { turns: 3 }],
    ['skip command', [{ text: 'skip this over' }, { text: 'answer two over' }], { turns: 2 }],
    ['repeat command', [{ text: 'repeat' }, { text: 'an answer over' }], { turns: 1 }],
    ['skip phrase inside an answer is an answer', [{ text: "I'd skip this one if I could over" }], { turns: 1 }],
    ['wrap refused before four answers', [{ text: 'wrap up' }, { text: 'yes' }], { turns: 3 }],
    ["I'm done refused before four answers", [{ text: "I'm done" }, { text: 'more to say over' }], { turns: 1 }],
    ['wrap after four answers', [{ text: 'one over' }, { text: 'two over' }, { text: 'three over' }, { text: 'four over' }, { text: 'wrap it up' }], { turns: 6 }],
    ['pause command', [{ text: 'pause voice' }], { turns: 3 }],
    ['exit command', [{ text: 'exit voice' }], { turns: 3 }],
    ['the bare word exit is an answer', [{ text: 'exit' }], { turns: 1 }],
    ['scratch that', [{ text: 'scratch that' }, { text: 'clean answer over' }], { turns: 1, hasDraft: true }],
    ['chips are spoken separately', [{ text: 'an answer over' }], { turns: 1, chips: ['the basil', 'the tomatoes', 'both of them'] }],
    ['offer accepted', [{ text: 'answer over' }, { text: 'yes' }], { turns: 3, offerAt: 1 }],
    ['offer declined', [{ text: 'answer over' }, { text: 'not yet' }, { text: 'next answer over' }], { turns: 2, offerAt: 1 }],
    ['offer unclear', [{ text: 'answer over' }, { text: 'purple' }, { text: 'no' }, { text: 'next over' }], { turns: 2, offerAt: 1 }],
    ['recoverable listen error', [{ kind: 'throw', code: 'network', message: 'blip' }, { text: 'ok then over' }], { turns: 1 }],
    // drive.js judges fatality from the message (RE_FATAL) or explicit flags, never the code;
    // these use the message the voice layer actually throws.
    ['denied microphone stops', [{ kind: 'throw', code: 'denied', message: 'Microphone access was denied.' }], { turns: 2 }],
    ['an explicitly fatal error stops', [{ kind: 'throw', code: 'x', message: 'engine gone', fatal: true }], { turns: 2 }],
    ['recoverable: false stops', [{ kind: 'throw', code: 'x', message: 'engine gone', recoverable: false }], { turns: 2 }],
    ['an unmatched message recovers', [{ kind: 'throw', code: 'denied', message: 'no mic' }, { text: 'fine over' }], { turns: 1 }],
    ['denied during the wrap confirmation stops', [{ text: 'answer over' }, { kind: 'throw', code: 'denied', message: 'Microphone access was denied.' }], { turns: 3, offerAt: 1 }],
    ['custom trigger', [{ text: 'my answer done' }], { turns: 1, config: { trigger: 'done' } }],
    ['stop after first listen', [{ text: 'half an answer', stopAfter: 1 }], { turns: 2 }],
  ];
  const cases = [];
  for (const [name, outcomes, opts] of scenarios) {
    const log = [];
    const turns = opts.turns;
    let i = 0;
    let answered = 0;
    let live = true;
    let listens = 0;
    let runaway = false;
    const io = {
      speak: async (t) => { log.push(['speak', t]); },
      listen: async () => {
        const o = outcomes[Math.min(i, outcomes.length - 1)];
        i += 1; listens += 1;
        log.push(['listen']);
        // A scenario that never ends is a broken script, not behaviour worth freezing.
        if (listens > 60) { runaway = true; live = false; return ''; }
        if (o.stopAfter && i >= o.stopAfter) live = false;
        if (o.kind === 'throw') {
          const err = Object.assign(new Error(o.message), { code: o.code });
          if ('fatal' in o) err.fatal = o.fatal;
          if ('recoverable' in o) err.recoverable = o.recoverable;
          throw err;
        }
        return o.text;
      },
      openTurn: () => (answered < turns
        ? { id: `t${answered}`, question: `Question ${answered}?`, bridge: answered ? `Bridge ${answered}.` : null, chips: opts.chips || [] }
        : null),
      submit: async (t) => { log.push(['submit', t]); answered += 1; },
      skip: async () => { log.push(['skip']); answered += 1; },
      wrap: async () => { log.push(['wrap']); live = false; },
      offerWrap: () => (opts.offerAt != null && answered >= opts.offerAt ? 'coverage' : null),
      advisory: (reason) => `Advisory for ${reason}.`,
      notify: (m) => log.push(['notify', m]),
      running: () => live,
      answeredCount: () => answered,
      hasDraft: () => !!opts.hasDraft,
      onState: (phase) => log.push(['state', phase]),
      pause: async () => { log.push(['pause']); },
      exit: async () => { log.push(['exit']); },
      scratch: async () => { log.push(['scratch']); },
      config: opts.config || {},
    };
    let result;
    try { result = await createDriveLoop(io).run(); } catch (err) { result = { throws: String(err.message) }; }
    if (runaway) throw new Error(`drive-loop scenario "${name}" does not terminate`);
    cases.push({ name, fn: 'driveLoop', input: plain({ outcomes, ...opts }), expect: { result: plain(result), log: plain(log) } });
  }
  return { source: 'src/runtime/drive.js', cases };
}

async function backupFamily() {
  const { final, snapshots } = await walkthrough();
  const mid = snapshots.find(([l]) => l === 'answered-3')[1];
  const reordered = JSON.parse(JSON.stringify(final));
  const keysReversed = Object.fromEntries(Object.entries(reordered).reverse());
  const backup = buildBackup([final, mid], { now: tick(5) });
  const cases = [
    rec('sessionsEqual same', sessionsEqual, final, final),
    rec('sessionsEqual key order ignored', sessionsEqual, final, keysReversed),
    rec('sessionsEqual differs', sessionsEqual, final, mid),
    rec('backupFilename', backupFilename, tick(5)),
    rec('backupFilename zero', backupFilename, 0),
    rec('buildBackup', buildBackup, [final, mid], { now: tick(5) }),
    rec('buildBackup empty', buildBackup, [], { now: 0 }),
    rec('parseBackup round trip', parseBackup, backup),
    rec('parseBackup empty', parseBackup, '   '),
    rec('parseBackup not json', parseBackup, '{nope'),
    rec('parseBackup wrong format', parseBackup, JSON.stringify({ format: 'other', version: 1, sessions: [] })),
    rec('parseBackup future version', parseBackup, JSON.stringify({ format: 'ideaforge-backup', version: 2, sessions: [] })),
    rec('parseBackup sessions not array', parseBackup, JSON.stringify({ format: 'ideaforge-backup', version: 1, sessions: {} })),
    rec('parseBackup too many sessions', parseBackup, JSON.stringify({ format: 'ideaforge-backup', version: 1, sessions: [{}, {}, {}] }), { maxSessions: 2 }),
    rec('parseBackup too large', parseBackup, backup, { maxBytes: 100 }),
    rec('parseBackup junk sessions', parseBackup, JSON.stringify({ format: 'ideaforge-backup', version: 1, exportedAt: 1, sessions: [null, 'x', { schema: 99, id: 'future' }, { schema: 2, id: 's_ok' }] })),
    rec('parseBackup non-string', parseBackup, 42),
  ];
  return { source: 'src/core/backup.js', cases };
}

async function markdownFamily() {
  const { snapshots, final } = await walkthrough();
  const odd = oddSessions();
  const cases = [];
  for (const [label, s] of [...snapshots.filter((_, i) => i % 3 === 0), ['final', final], ...odd]) {
    cases.push(rec(`coveragePercent ${label}`, coveragePercent, s));
    cases.push(rec(`exportFilename ${label}`, exportFilename, s));
    cases.push(rec(`buildExport ${label}`, buildExport, s));
    cases.push(rec(`libraryCard ${label}`, libraryCard, s));
    cases.push(rec(`sessionLibraryStatus ${label}`, sessionLibraryStatus, s));
    cases.push(rec(`sessionSearchText ${label}`, sessionSearchText, s));
  }
  cases.push(rec('buildExport generic mode with note', buildExport, final, { mode: 'generic', note: 'Written offline.' }));
  cases.push(rec('buildExport named', buildExport, { ...final, name: 'My own name' }));
  for (const t of ['', 'Hello, World!', '  Ünïcödé — idea  ', '🌌🌌', 'x'.repeat(100), null]) {
    cases.push(rec(`slug ${JSON.stringify(t)}`, slug, t));
  }
  cases.push(rec('slug fallback', slug, '!!!', 'untitled'));
  const exported = buildExport(final);
  const docs = [
    exported,
    '# Title\n\nPara one\nstill para one.\n\n- a\n- b\n\n1. one\n2. two\n\n> quote\n\n```\ncode **not bold**\n```\n\n**bold** and `code` and [link](https://x.y)\n\n---\n\n| a | b |\n|---|---|\n| 1 | 2 |\n',
    '', '   \n\n  ', '## Only a heading', '_emphasis_ *star* ***both***',
  ];
  for (const [i, md] of docs.entries()) {
    cases.push(rec(`renderBlocks ${i}`, renderBlocks, md));
    cases.push(rec(`forSpeech ${i}`, forSpeech, md));
    cases.push(rec(`forSpeech ${i} short`, forSpeech, md, { maxChars: 120 }));
  }
  const speech = forSpeech(exported);
  for (const [i, t] of [speech, 'One. Two! Three? Four.', 'a'.repeat(900), 'Short.', '', `${'word '.repeat(200)}end.`].entries()) {
    cases.push(rec(`speechChunks ${i}`, speechChunks, t));
    cases.push(rec(`speechChunks ${i} 80`, speechChunks, t, { maxChars: 80 }));
  }
  const lib = [
    // availableTags sorts with localeCompare, which follows the machine's locale. These tags
    // order identically under ASCII and en collation, so every CI leg agrees.
    { ...final, tags: ['Work', 'Apps'], updatedAt: 5 },
    { ...snapshots[2][1], id: 's_b', name: 'Basil', tags: ['Garden', 'work'], updatedAt: 9 },
    { ...odd[1][1], archivedAt: 7, tags: ['Garden', 'Work'], updatedAt: 7 },
    { ...odd[0][1], status: 'done', updatedAt: 3 },
  ];
  cases.push(rec('availableTags', availableTags, lib));
  for (const [i, f] of [{}, { status: 'all' }, { status: 'archived' }, { status: 'done' }, { tag: 'work' }, { tag: 'garden', status: 'all' }, { query: 'BASIL' }, { query: 'conference', status: 'all' }, { query: 'nothing matches' }].entries()) {
    cases.push(rec(`filterSessions ${i}`, filterSessions, lib, f));
  }
  return { source: 'src/core/markdown.js, src/core/library.js', cases };
}

/**
 * provider-request cases: `{name, fn: 'request', input: {config, call, modelTier}, expect:
 * {request: {url, method, headers, body}}}` — the exact HTTP request each adapter makes for
 * one call, captured from a stubbed fetch. Keys are fixed fake strings, never real ones.
 */
async function providersFamily() {
  const cases = [];
  const tierMaps = [ANTHROPIC_TIERS, OPENAI_COMPAT_PRESETS.openai.tiers, null, { quick: 'q', default: 'd' }];
  const choices = [{}, { model: 'm' }, { wrapModel: 'w' }, { model: 'm', wrapModel: 'w' }, { model: '', wrapModel: '' }, null];
  for (const [i, t] of tierMaps.entries()) {
    for (const [j, c] of choices.entries()) cases.push(rec(`resolveTiers ${i}.${j}`, resolveTiers, t, c));
  }
  for (const c of PROVIDER_CHOICES) cases.push(rec(`presetModels ${c.id}`, presetModels, c));
  cases.push(rec('presetModels null', presetModels, null));
  for (const u of [
    'http://localhost:11434', 'http://127.0.0.1:1234/v1', 'http://[::1]:11434', 'http://localhost.evil.com',
    'https://api.openai.com/v1', 'http://127.0.0.2', 'http://0.0.0.0:8080', 'not a url', '', 'http://LOCALHOST',
    'http://127.1', 'http://localhost:11434@evil.com', 'file:///etc/passwd',
  ]) cases.push(rec(`isLoopback ${JSON.stringify(u)}`, isLoopback, u));
  for (const [i, auth] of [AUTH_NONE, AUTH_BEARER, ANTHROPIC_AUTH, { scheme: 'header', name: 'x-api-key' }, { scheme: 'query', name: 'key' }].entries()) {
    cases.push(rec(`applyAuth ${i}`, applyAuth, 'https://example.test/v1/chat?x=1', { auth, apiKey: 'sk-test-key', headers: { 'content-type': 'application/json' } }));
    cases.push(rec(`applyAuth ${i} no key`, applyAuth, 'https://example.test/v1', { auth, apiKey: '' }));
  }
  for (const s of [400, 401, 403, 404, 408, 413, 429, 500, 502, 503, 529, 200, 0]) cases.push(rec(`codeForStatus ${s}`, codeForStatus, s));
  for (const code of ['rate_limit', 'overloaded', 'network', 'auth', 'bad_response', 'aborted']) {
    // Built inside the call: isRetryable checks `instanceof ProviderError`, which a recorded
    // plain object can never satisfy. args record the code; the Kotlin side constructs its own.
    let value;
    try { value = { value: isRetryable(new ProviderError(code, 'x')) }; } catch (err) { value = { throws: String(err.message) }; }
    cases.push({ name: `isRetryable ProviderError ${code}`, fn: 'isRetryableProviderError', args: [code], expect: value });
  }
  cases.push(rec('isRetryable plain object is never retryable', isRetryable, { code: 'network' }));
  for (const [i, h] of [{ 'retry-after': '3' }, { 'retry-after': '0' }, { 'retry-after': 'soon' }, {}, { 'retry-after': '120' }, { 'retry-after': '-4' }].entries()) {
    const headers = { get: (k) => (k.toLowerCase() in h ? h[k.toLowerCase()] : null) };
    let value;
    try { value = { value: plain(retryAfterMs(headers)) }; } catch (err) { value = { throws: String(err.message) }; }
    cases.push({ name: `retryAfterMs ${i}`, fn: 'retryAfterMs', args: [h], expect: value });
  }
  for (const [i, body] of [
    { data: [{ id: 'b' }, { id: 'a' }, { id: 'a' }, { nope: 1 }] }, { models: [{ name: 'llama' }, { model: 'qwen' }] },
    [], null, 'x', { data: 'nope' },
  ].entries()) cases.push(rec(`parseModelList ${i}`, parseModelList, body));
  for (const [i, t] of [
    '{"a":1}', '```json\n{"a":1}\n```', 'Sure! Here you go: {"a": {"b": [1,2]}} hope that helps', '', 'no json here',
    '{"a":1} {"b":2}', '[1,2,3]', '{"unterminated": ', '{"s":"a } brace"}', null, '  {"x":"\\u00e9"}  ',
  ].entries()) cases.push(rec(`extractJson ${i}`, extractJson, t));

  // Exact requests, via a stubbed fetch.
  const realFetch = globalThis.fetch;
  const parts = { system: 'SYSTEM TEXT', prefix: 'PREFIX TEXT', tail: 'TAIL TEXT' };
  // Anthropic marks the prefix cacheable only from 16 KiB of UTF-8; one case crosses it.
  const bigParts = { system: 'SYSTEM TEXT', prefix: `${'é'.repeat(8192)}x`, tail: 'TAIL TEXT' };
  const configs = [
    { kind: 'anthropic', apiKey: 'sk-ant-test' },
    { kind: 'anthropic', apiKey: 'sk-ant-test', model: 'claude-x', wrapModel: 'claude-y' },
    { kind: 'openai', apiKey: 'sk-test' },
    { kind: 'openai', apiKey: 'sk-test', model: 'gpt-x' },
    { kind: 'groq', apiKey: 'gsk-test' },
    { kind: 'openrouter', apiKey: 'or-test' },
    { kind: 'ollama', baseUrl: 'http://127.0.0.1:11434/v1', model: 'qwen2.5:7b-instruct' },
    { kind: 'lmstudio', baseUrl: 'http://localhost:1234/v1', model: 'local-model' },
    { kind: 'custom', baseUrl: 'http://127.0.0.1:8080/v1', model: 'my-model' },
    { kind: 'custom', baseUrl: 'http://127.0.0.1:8080/v1' },
    { kind: 'custom', baseUrl: 'https://example.com/v1', model: 'x' },
    { kind: 'nope' },
  ];
  try {
    for (const [i, config] of configs.entries()) {
      for (const modelTier of ['quick', 'default', 'complex']) {
        let captured = null;
        globalThis.fetch = async (url, init = {}) => {
          captured = { url: String(url), method: init.method || 'GET', headers: { ...(init.headers || {}) }, body: init.body ? JSON.parse(init.body) : null };
          const body = config.kind === 'anthropic'
            ? { content: [{ type: 'text', text: '{"ok":true}' }], stop_reason: 'end_turn' }
            : { choices: [{ message: { content: '{"ok":true}' }, finish_reason: 'stop' }], model: 'served-model' };
          return { ok: true, status: 200, headers: { get: () => null }, json: async () => body };
        };
        let expect;
        try {
          const provider = await createProvider({ ...config });
          const out = await provider.sampleJson(parts, { modelTier });
          expect = { request: captured, result: plain({ json: out.json, modelTierApplied: out.modelTierApplied }) };
        } catch (err) {
          expect = { throws: errorOf(err), request: captured };
        }
        cases.push({ name: `request ${i} ${config.kind} ${modelTier}`, fn: 'request', input: { config, parts, modelTier }, expect });
      }
    }
    for (const [label, p] of [['just under', { ...bigParts, prefix: 'é'.repeat(8191) + 'x' }], ['at the threshold', bigParts]]) {
      let captured = null;
      globalThis.fetch = async (url, init = {}) => {
        captured = { url: String(url), method: init.method || 'GET', headers: { ...(init.headers || {}) }, body: init.body ? JSON.parse(init.body) : null };
        return { ok: true, status: 200, headers: { get: () => null }, json: async () => ({ content: [{ type: 'text', text: '{}' }], stop_reason: 'end_turn' }) };
      };
      const provider = await createProvider({ kind: 'anthropic', apiKey: 'sk-ant-test' });
      await provider.sampleJson(p, { modelTier: 'default' });
      cases.push({ name: `request anthropic cache marker ${label}`, fn: 'request', input: { config: { kind: 'anthropic', apiKey: 'sk-ant-test' }, parts: p, modelTier: 'default' }, expect: { request: captured } });
    }
  } finally {
    globalThis.fetch = realFetch;
  }
  return { source: 'src/providers/*.js', cases };
}

export const FAMILIES = {
  session: sessionFamily,
  engine: engineFamily,
  'turn-prompt': turnPromptFamily,
  'run-turn': runTurnFamily,
  synthesis: synthesisFamily,
  speech: speechFamily,
  'drive-loop': driveLoopFamily,
  backup: backupFamily,
  markdown: markdownFamily,
  providers: providersFamily,
};
