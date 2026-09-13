// The conductor: the loop that turns the pure engine into an interview.
//
// Everything it needs from the outside is injected — the provider and the clock — so the
// whole file is drivable from a test with no network and no platform. That is why it sits
// here rather than in src/providers/, and why the purity lint covers this directory too.
//
// A note for whatever persists sessions: one call to runTurn bumps `rev` five or six
// times (setPending, applyCoverage, addFacts, setZeroGainStreak, askQuestion,
// clearPending). Persist the object this returns, once. Persisting on every rev change
// means six writes per question.

import {
  askQuestion, answerQuestion, applyCoverage, addFacts, setPending, clearPending,
  setZeroGainStreak, isLowConfidence, openTurn, reopen,
} from '../core/session.js';
import {
  selectNextDimension, legalMoves, lastAnswerClass, classifyAnswer, buildTurnPromptParts,
  buildTurnPrompt, parseTurnResult, questionTripwire, pickBankQuestion, promptHash,
  TRIPWIRE_DIRECTIVE, HARD_TURN_CEILING, shouldOfferWrap,
} from '../core/engine.js';
import { SEED_QUESTION } from '../core/dimensions.js';
import { levelSnapshot, isZeroGain } from './gain.js';

/**
 * Answer classes that mean no information arrived, so the dimension that answer was meant
 * to serve cannot have improved however confidently the model scores it.
 *
 * `terse` is deliberately absent: "a 200-word memo my CTO reads in five minutes" is short
 * and highly specific. Length is not the signal; evasion is.
 */
export const CAPPING_CLASSES = new Set(['dodge', 'idk', 'refusal']);

const JSON_REPAIR_DIRECTIVE =
  'Your previous reply was not a single valid JSON object. Return only the JSON object ' +
  'described above, with no prose, no explanation and no code fence.';

/**
 * Turn 1 is hardcoded: no model call, so the first screen paints instantly and cannot
 * fail. `answerQuestion` copies the reply into `session.opening` on its own.
 */
export function seedTurn(session, { now = 0 } = {}) {
  return askQuestion(session, {
    question: SEED_QUESTION, dimension: 'outcome', source: 'seed', now,
  });
}

/**
 * Record the user's answer to the open turn. The runtime classifies, not the UI — the
 * `source` must reach `classifyAnswer` raw, because that is what exempts dictated answers
 * from the terse and dodge thresholds.
 */
export function submitAnswer(session, { text, source = 'typed', now = 0 }) {
  const open = openTurn(session);
  if (!open) throw new Error('no open turn to answer');
  const base = session.status === 'done' ? reopen(session, now) : session;
  const classification = classifyAnswer(text, open.question, { source, chips: open.chips });
  return answerQuestion(base, { text, source, classification, now });
}

/**
 * Ask the next question.
 *
 * @param {object} session
 * @param {{provider: object|null, now?: number, modelTier?: string, budget?: number,
 *          signal?: AbortSignal}} deps  provider null => checklist mode, no calls at all
 * @returns {Promise<{session: object, turn: object|null, degraded: boolean,
 *                    error: Error|null, wrap: string|null, aborted: boolean,
 *                    warnings: string[], calls: number}>}
 */
export async function runTurn(session, deps = {}) {
  const { provider = null, now = 0, modelTier = 'default', budget, signal } = deps;
  if (openTurn(session)) throw new Error('a question is already open');

  const warnings = [];
  const done = (s, extra) => ({
    session: s, turn: openTurn(s), degraded: false, error: null,
    wrap: shouldOfferWrap(s), aborted: false, warnings, calls: 0, ...extra,
  });

  // The hard ceiling is only *reported* by shouldOfferWrap; something has to enforce it.
  if (session.turns.length >= HARD_TURN_CEILING) {
    return done(session, { turn: null, wrap: 'hard_ceiling' });
  }

  const target = selectNextDimension(session);
  if (!target) return done(session, { turn: null, wrap: 'coverage' });

  const prev = session.turns.filter((t) => t.answer || t.skipped).slice(-1)[0] || null;
  const lastClass = lastAnswerClass(session);
  const moves = legalMoves(session, target, lastClass);
  const promptOpts = { target, moves, budget };

  if (!provider) {
    return bankFallback(session, target, { now, warnings, error: null, calls: 0 });
  }

  const turnId = `t${session.turns.length + 1}`;
  let working = session;
  let calls = 0;
  let retried = false;
  let injected = null;

  while (true) {
    const parts = buildTurnPromptParts(working, { ...promptOpts, injected });
    // Pending is set before the call and carries the prompt hash, so an interrupted turn
    // can prove on resume that the recomputed prompt is the same one.
    working = setPending(working, {
      kind: 'turn', turnId, promptHash: promptHash(joinParts(parts)), startedAt: now,
    }, now);

    let result;
    try {
      result = await provider.sampleJson(parts, { modelTier, signal, cache: true });
      calls++;
    } catch (err) {
      calls++;
      if (err && err.code === 'aborted') {
        return {
          session: clearPending(working, now), turn: null, degraded: false, error: null,
          wrap: null, aborted: true, warnings, calls,
        };
      }
      warnings.push(`provider ${err && err.code ? err.code : 'error'}`);
      return bankFallback(working, target, { now, warnings, error: err, calls });
    }

    const parsed = parseTurnResult(result.json, { moves, target });
    warnings.push(...parsed.warnings);

    const reason = !parsed.ok ? 'bad_json' : questionTripwire(parsed.question, working);

    if (reason && !retried) {
      // Exactly one regeneration per turn, shared between a bad parse and a tripwire.
      retried = true;
      injected = reason === 'bad_json' ? JSON_REPAIR_DIRECTIVE : TRIPWIRE_DIRECTIVE[reason];
      continue;
    }

    if (reason) {
      // Out of retries. A generic question is a quality miss, not a correctness failure,
      // and a canned bank question is not obviously better — so accept it. The others
      // waste the user's turn outright, so fall back to a question we know is legal.
      if (reason === 'generic') {
        warnings.push('generic question accepted');
      } else {
        warnings.push(`unfixable ${reason}`);
        return bankFallback(working, target, { now, warnings, error: null, calls });
      }
    }

    return applyTurn(working, parsed, { prev, lastClass, now, warnings, calls });
  }
}

/** Apply a good result. Order matters: coverage before the gain measurement. */
function applyTurn(session, parsed, { prev, lastClass, now, warnings, calls }) {
  const before = levelSnapshot(session);

  // The coverage claim is a judgement about the answer just read, so the provenance cap
  // belongs to the dimension THAT answer served — prev.dimension, not the dimension we
  // are about to ask about. Getting this backwards caps a dimension nothing has touched.
  const lowConfidenceDimension = prev && isLowConfidence(prev) ? prev.dimension : null;
  const cappedDimensions = prev && CAPPING_CLASSES.has(lastClass) ? [prev.dimension] : [];

  let s = applyCoverage(session, parsed.coverage, { lowConfidenceDimension, cappedDimensions, now });
  s = addFacts(s, parsed.facts, now);
  s = setZeroGainStreak(s, isZeroGain(before, levelSnapshot(s)) ? s.zeroGainStreak + 1 : 0, now);
  s = askQuestion(s, {
    question: parsed.question, dimension: parsed.dimension, move: parsed.move,
    chips: parsed.chips, bridge: parsed.bridge, source: 'model', now,
  });
  s = clearPending(s, now);

  return {
    session: s, turn: openTurn(s), degraded: false, error: null,
    wrap: shouldOfferWrap(s), aborted: false, warnings, calls,
  };
}

/**
 * The interview must never dead-end on a network error, so a failed turn falls back to
 * the static question bank rather than stopping.
 *
 * `zeroGainStreak` is left untouched here, in both directions. Incrementing it would let
 * two network blips offer the exit for a reason that never happened; resetting it would
 * erase a real exhaustion signal that a transient failure interrupted.
 */
function bankFallback(session, target, { now, warnings, error, calls }) {
  const picked = pickBankQuestion(session, target);
  const s0 = session.pending ? clearPending(session, now) : session;
  if (!picked) {
    return {
      session: s0, turn: null, degraded: true, error,
      wrap: 'exhausted', aborted: false, warnings, calls,
    };
  }
  const s = askQuestion(s0, {
    question: picked.question, dimension: picked.dimension, source: 'bank', now,
  });
  return {
    session: s, turn: openTurn(s), degraded: true, error,
    wrap: shouldOfferWrap(s), aborted: false, warnings, calls,
  };
}

/**
 * Resume a turn that was interrupted mid-flight.
 *
 * Two separate mechanisms, and conflating them produces a design that is only correct
 * when the cache happens to hit:
 *   - the turnId check is the CORRECTNESS guarantee — never create the same turn twice;
 *   - the prompt hash is only a cache-replay optimisation, and a miss is routine (any
 *     deploy that edits RULES changes it).
 */
export async function resumeTurn(session, deps = {}) {
  const { now = 0, budget } = deps;
  const pending = session.pending;
  if (!pending || pending.kind !== 'turn') {
    return {
      session, turn: openTurn(session), degraded: false, error: null,
      wrap: shouldOfferWrap(session), aborted: false, warnings: [], calls: 0,
    };
  }

  // The crash landed between askQuestion and clearPending: the work is done and only the
  // bookkeeping was lost. Calling the model again would duplicate the turn.
  if (session.turns.some((t) => t.id === pending.turnId)) {
    const s = clearPending(session, now);
    return {
      session: s, turn: openTurn(s), degraded: false, error: null,
      wrap: shouldOfferWrap(s), aborted: false, warnings: ['recovered a completed turn'], calls: 0,
    };
  }

  const target = selectNextDimension(session);
  const warnings = [];
  if (target) {
    const moves = legalMoves(session, target, lastAnswerClass(session));
    const hash = promptHash(buildTurnPrompt(session, { target, moves, budget }));
    if (hash !== pending.promptHash) {
      // The session moved underneath us — a reconciled write from another tab, or a
      // deploy that edited the prompt. Expected, and not worth alarming anyone about.
      warnings.push('prompt changed since the interrupted call; starting the turn fresh');
    }
  }

  const out = await runTurn(clearPending(session, now), deps);
  return { ...out, warnings: [...warnings, ...out.warnings] };
}

/** The single definition of how the three prompt parts become the bytes we hash. */
const joinParts = ({ system, prefix, tail }) => [system, prefix, tail].join('\n');
