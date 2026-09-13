// Interview engine: what to ask next, how to ask for it, and how to read the answer.
//
// The shape of the interview is deterministic and testable here; the model only
// ever WRITES the question. That split is deliberate — it keeps question quality
// under our control and makes the whole loop unit-testable without a model.

import {
  DIMENSIONS, DIMENSION_IDS, LEVEL_RANK, MOVES, MOVE_IDS, getDimension,
} from './dimensions.js';
import { buildTranscriptBlock, serializeFacts, BUDGET_BYTES } from './digest.js';

export const SOFT_TURN_CEILING = 18;
export const HARD_TURN_CEILING = 25;

// ───────────────────────────────────────────────── answer classification
// Deterministic, free, and it changes behaviour. Runs before the turn prompt.

const RE_IDK = /\b(i\s*don'?t\s*know|no\s+idea|not\s+sure|haven'?t\s+thought|dunno|you\s+tell\s+me|whatever\s+you\s+think)\b/i;
const RE_REFUSAL = /\b(stop\s+asking|move\s+on|next\s+question|skip\s+(this|that)|enough\s+about|don'?t\s+care\s+about|already\s+(said|told))\b/i;
const RE_TERSE_WORD = /^(yes|no|sure|maybe|idk|both|either|whatever|none|n\/a)\W*$/i;
const STOPWORDS = new Set(('the a an and or but if then of to in on for with is are was were be been it this that these those ' +
  'what how why who when where which you your my i we they them at as by from so do does did can could would should will ' +
  'about into over under out up down not no yes its it\'s there here more most some any all one two').split(' '));

export function contentWords(text) {
  return String(text || '').toLowerCase().replace(/[^a-z0-9\s']/g, ' ').split(/\s+/)
    .filter((w) => w.length > 2 && !STOPWORDS.has(w));
}

/**
 * Dictated answers are systematically longer and more meandering than typed
 * ones, so terse/dodge thresholds must not be applied to them unchanged.
 */
export function classifyAnswer(text, question = '', { source = 'typed', chips = [] } = {}) {
  const t = String(text || '').trim();
  if (!t) return 'terse';
  if (RE_REFUSAL.test(t)) return 'refusal';
  if (RE_IDK.test(t)) return 'idk';

  const words = t.split(/\s+/).filter(Boolean);
  const isChipVerbatim = chips.some((c) => c.trim().toLowerCase() === t.toLowerCase());
  if (RE_TERSE_WORD.test(t) || (words.length <= 4 && source !== 'voice') || isChipVerbatim) return 'terse';

  if (source !== 'voice' && words.length >= 15) {
    const qWords = new Set(contentWords(question));
    const overlap = contentWords(t).filter((w) => qWords.has(w)).length;
    if (qWords.size >= 3 && overlap < 2) return 'dodge';
  }
  return 'substantive';
}

// ─────────────────────────────────────────────────── dimension selection

/**
 * The classification of the most recent answered turn.
 *
 * Exported because two places need it and they must never disagree: the move policy
 * (`legalMoves`) and the "Last answer quality" line in the prompt. Computing it twice is
 * how those two silently drift apart.
 */
export function lastAnswerClass(session) {
  const last = session.turns.filter((t) => t.answer || t.skipped).slice(-1)[0];
  if (!last) return 'substantive';
  if (last.skipped) return 'refusal';
  return last.classification || 'substantive';
}

/** Lowest-covered dimension, weighted; ties broken by fewest turns, then fixed order. */
export function selectNextDimension(session) {
  const candidates = DIMENSIONS.filter((d) => session.coverage[d.id].status === 'probing');
  if (!candidates.length) return null;
  let best = null;
  let bestScore = Infinity;
  for (const d of candidates) {
    const c = session.coverage[d.id];
    // Higher weight and lower level => more urgent => lower score.
    const score = LEVEL_RANK[c.level] / d.weight;
    if (score < bestScore - 1e-9 ||
        (Math.abs(score - bestScore) < 1e-9 && c.turnsSpent < session.coverage[best.id].turnsSpent)) {
      best = d; bestScore = score;
    }
  }
  return best ? best.id : null;
}

export function isReadyToWrap(session) {
  const probing = DIMENSION_IDS.filter((id) => session.coverage[id].status === 'probing');
  if (!probing.length) return true;
  const allPartial = probing.every((id) => LEVEL_RANK[session.coverage[id].level] >= 1);
  const coveredCount = probing.filter((id) => session.coverage[id].level === 'covered').length;
  return allPartial && coveredCount >= Math.min(5, probing.length);
}

export function shouldOfferWrap(session) {
  const n = session.turns.length;
  if (n >= HARD_TURN_CEILING) return 'hard_ceiling';
  if (n >= SOFT_TURN_CEILING) return 'soft_ceiling';
  if (session.zeroGainStreak >= 2) return 'exhausted';
  if (isReadyToWrap(session)) return 'coverage';
  return null;
}

// ──────────────────────────────────────────────────────── move policy
// The engine narrows to a legal set; the model picks one and names it.
// Left to itself a model collapses onto two comfortable moves within ~4 turns.

export function legalMoves(session, targetId, lastClass = 'substantive') {
  const n = session.turns.length + 1;
  const cov = session.coverage[targetId] || { level: 'thin', turnsSpent: 0 };
  let set;

  if (n === 1) set = ['concretize', 'scope_cut', 'analogue'];
  else if (lastClass === 'terse' || lastClass === 'idk' || lastClass === 'dodge')
    set = ['menu', 'concretize', 'sample_output'];
  else if (cov.turnsSpent >= 2) set = ['challenge', 'tradeoff', 'boundary'];   // escalate, don't circle
  else switch (targetId) {
    case 'voice':       set = ['sample_output', 'analogue', 'define_term']; break;
    case 'bar':         set = ['premortem', 'anti_goal', 'concretize']; break;
    case 'constraints': set = ['scope_cut', 'tradeoff', 'boundary']; break;
    case 'audience':    set = ['concretize', 'who_else', 'boundary']; break;
    case 'references':  set = ['analogue', 'define_term']; break;
    case 'outcome':     set = cov.level === 'thin'
                          ? ['concretize', 'scope_cut', 'boundary']
                          : ['scope_cut', 'premortem', 'challenge']; break;
    default:            set = cov.level === 'thin'
                          ? ['concretize', 'scope_cut', 'boundary']
                          : ['boundary', 'tradeoff', 'challenge', 'define_term'];
  }

  const history = session.moveHistory;
  const last = history[history.length - 1];
  const counts = history.reduce((m, mv) => (m[mv] = (m[mv] || 0) + 1, m), {});
  // premortem and challenge land badly cold.
  let out = set.filter((m) => m !== last && (counts[m] || 0) < 3
    && !(n < 4 && (m === 'premortem' || m === 'challenge')));
  if (n >= 4 && !counts.premortem && !out.includes('premortem')) out.push('premortem');
  if (!out.length) out = ['concretize', 'boundary'];
  return out;
}

// ────────────────────────────────────────────────────── the turn prompt
// MUST be a pure function of session state: no timestamps, no randomness.
// An interrupted call is recovered by re-issuing the byte-identical prompt and
// letting `sample`'s 5-minute cache replay the answer for free.

function coverageBlock(session) {
  return DIMENSION_IDS.map((id) => {
    const c = session.coverage[id];
    const d = getDimension(id);
    return `${id} | ${d.label} | ${c.level} | ${c.status} | ${c.gap || '—'}`;
  }).join('\n');
}

const RULES = `You are the interviewer inside IdeaForge. Someone has a half-formed idea. Your job is
to pull the specifics out of their head — the things they know but would never think to
type unprompted — so that afterwards we can write an excellent prompt for an LLM.

You ask ONE question per turn. You are not a chatbot, an advisor or a coach. You do not
give advice, you do not evaluate their idea, you do not summarise their answer back at them.
You ask the single best question available and get out of the way.

WHAT MAKES A GOOD QUESTION
Filler asks for a CATEGORY. Good questions ask for an INSTANCE, a preference under
pressure, or a boundary. A good question is one they can only answer from their own head
and whose answer you could not have guessed. It has a concrete hook — a number, a scene,
a name, a moment in time — and is answerable in one or two sentences.

GOOD:
  "It's six weeks from now and you've stopped using it. What made you stop?"
  "You said 'clean'. What's something you've seen recently that wasn't clean?"
  "If this could only produce ONE section, which one earns its place?"
BAD — never produce these:
  "Who is your target audience?"                 (category; they'll say 'developers')
  "What's your go-to-market strategy?"           (MBA-speak)
  "What are the key features and how will you    (COMPOUND — three questions)
   prioritise them, and why?"
  "How will you measure success at scale?"       (they cannot know yet)
  "Great answer! Tell me more about your vision?" (sycophantic, and vague)

HARD CONSTRAINTS
1. ONE question. One question mark. No "and", no "also", no "(and if so, why?)".
2. NEVER ask about anything in FACTS ESTABLISHED, or anything already asked.
3. NEVER ask about a dimension whose status is waived or deferred.
4. NEVER ask for what they could not reasonably know yet — market size, conversion
   rates, what other people will think, research they haven't done.
5. No praise, no evaluation, no "interesting", no "I love that".
6. No advice. If you have an opinion, the only legal expression of it is a
   \`challenge\` move phrased as a question.
7. Use THEIR vocabulary. If they call it a "run sheet", you call it a run sheet.
8. Max 30 words. Questions over 30 words are almost always compound.
9. If their last answer was terse or "I don't know", do NOT change the subject and do
   NOT scold. Ask a smaller, more answerable version, or use the \`menu\` move.

SUGGESTION CHIPS
3–4 candidate ANSWERS the user can tap. A chip drops into their answer box as editable
text; it does not submit for them.
- First person, a sentence fragment they'd plausibly say. Max 12 words.
- MUTUALLY EXCLUSIVE and genuinely divergent. Three shades of one answer is a failure.
- At least one must be the unglamorous, uncomfortable or low-ambition answer. Real
  answers are often "honestly, just for me" or "I'd probably give up".
- It is fine if the true answer is none of them. Their job is to show the SHAPE of an
  answer, not to be picked.
- If the question has no enumerable answers, return fewer chips or none. Never filler.

THE BRIDGE (optional, one short clause before the question)
May be null. If present: max 15 words, must contain a concrete noun or a quoted fragment
of their own last answer, and must be factual acknowledgement, not evaluation.
BANNED anywhere in it: great, interesting, love, smart, excellent, awesome, perfect,
helpful, insightful, good point, makes sense, absolutely, totally.
  GOOD: "Okay — so the 40-minute load time is the real enemy."
  BAD:  "That's a really interesting point about performance!"

COVERAGE SCORING — a writing test, not a satisfaction test
  thin     I could not write this section without inventing things they never said.
  partial  I could write it, but it would be generic — equally true of a hundred other
           people's ideas. Or they gave a stance with no specifics.
  covered  I could write it right now from their own specifics, and a stranger reading
           it would make the same choices they would.
- Every dimension marked \`covered\` MUST carry \`evidence\`: a verbatim quote of ≤15 words
  from the USER's turns. Your own inference is not evidence. Cannot quote them? Not covered.
- Every dimension not \`covered\` MUST carry a specific \`gap\` noun phrase. "Needs more
  detail" is unacceptable; "no sense of length or format" is fine.
- An answer whose provenance is [chip] or [draft-unedited] is MY words, not theirs.
  Cap those at \`partial\`.
- Be stingy. Declaring something covered and stopping is much worse than one extra turn.
- At most TWO dimensions may improve in a single turn.`;

/**
 * The turn prompt, split at its cache boundaries.
 *
 * `system` never changes. `prefix` only ever grows at the end — the idea, then the fact
 * ledger, then the transcript — so a provider can put a prompt-cache breakpoint after it
 * and hit that cache on every later turn. `tail` holds the volatile parts (the coverage
 * map, rewritten every turn, and this turn's instructions) and must come last for that to
 * hold. That ordering is the only reason the coverage map now sits below the transcript
 * rather than above it.
 *
 * @returns {{system: string, prefix: string, tail: string}}
 */
export function buildTurnPromptParts(
  session, { target, moves, injected = null, budget = BUDGET_BYTES } = {}
) {
  const t = getDimension(target);
  const lastClass = lastAnswerClass(session);
  const { text: transcript } = buildTranscriptBlock(session, budget);
  const moveMenu = moves.map((m) => `  ${m.padEnd(14)} ${MOVES[m]}`).join('\n');

  const prefix = [
    '=== THE IDEA (their opening statement, verbatim) ===',
    session.opening || '(not yet given)',
    '',
    '=== FACTS ESTABLISHED SO FAR ===',
    'Treat every one of these as ALREADY KNOWN. Asking about one is the worst error you can make.',
    serializeFacts(session.facts),
    '',
    '=== TRANSCRIPT ===',
    'Answers marked [voice] are dictated: read for intent, tolerate transcription errors,',
    'and never ask them to clarify a mis-transcription.',
    transcript,
  ].filter((l) => l !== '').join('\n');

  const tail = [
    '=== COVERAGE MAP ===',
    'Format: id | label | level | status | gap',
    coverageBlock(session),
    '',
    '=== THIS TURN ===',
    `Turn number: ${session.turns.length + 1}`,
    `Target dimension: ${target} (${t.label})`,
    `What "covered" means here: ${t.covered}`,
    `Known gap: ${session.coverage[target].gap || '(nothing established yet)'}`,
    `Turns already spent on it: ${session.coverage[target].turnsSpent}`,
    `Last answer quality: ${lastClass}`,
    `Moves already used: ${session.moveHistory.join(', ') || '(none)'}`,
    '',
    'Pick exactly ONE move from this list and name it. Do not invent or blend moves.',
    moveMenu,
    injected ? `\nADDITIONAL DIRECTIVE FOR THIS TURN:\n${injected}` : '',
    '',
    '=== OUTPUT — return this JSON object and nothing else ===',
    '{',
    '  "bridge": string | null,',
    '  "question": string,',
    `  "move": one of ${JSON.stringify(moves)},`,
    '  "dimension": string,',
    '  "chips": [string, ...],',
    '  "new_facts": [{"dimension": string, "fact": string}],   // <=3, from their LAST answer only',
    '  "coverage": { "<dimension_id>": {"level": "thin"|"partial"|"covered",',
    '                                   "gap": string|null, "evidence": string|null} },',
    '  "suggest_wrap": boolean,',
    '  "wrap_reason": string | null',
    '}',
  ].filter((l) => l !== '').join('\n');

  return { system: RULES, prefix, tail };
}

/**
 * @returns {string} the complete prompt. Deterministic for a given session state.
 */
export function buildTurnPrompt(session, opts = {}) {
  const { system, prefix, tail } = buildTurnPromptParts(session, opts);
  return [system, prefix, tail].join('\n');
}

/**
 * The no-model fallback: the first bank question not yet asked, preferring the target
 * dimension and then any other dimension still being probed. Deterministic, so a degraded
 * interview replays identically.
 *
 * @returns {{question: string, dimension: string} | null}
 */
export function pickBankQuestion(session, targetId) {
  const asked = new Set(session.turns.map((t) => t.question));
  const rest = DIMENSION_IDS.filter(
    (id) => id !== targetId && session.coverage[id] && session.coverage[id].status === 'probing'
  );
  for (const id of [targetId, ...rest]) {
    if (!id || !session.coverage[id]) continue;
    const question = getDimension(id).bank.find((q) => !asked.has(q));
    if (question) return { question, dimension: id };
  }
  return null;
}

/** Stable non-cryptographic hash (FNV-1a, 32-bit) for cache-replay recovery. */
export function promptHash(str) {
  let h = 0x811c9dc5;
  for (let i = 0; i < str.length; i++) {
    h ^= str.charCodeAt(i);
    h = Math.imul(h, 0x01000193) >>> 0;
  }
  return h.toString(36);
}

// ───────────────────────────────────────────────────── result validation
// sample.json validates NOTHING. Never trust the shape.

const RE_GENERIC = /target audience|go.to.market|value prop|at scale|monetiz|user persona|pain point|north star/i;
const BANNED_BRIDGE = /\b(great|interesting|love|smart|excellent|awesome|perfect|helpful|insightful|good point|makes sense|absolutely|totally)\b/i;

export function parseTurnResult(raw, { moves = MOVE_IDS, target = null } = {}) {
  const warnings = [];
  const o = (raw && typeof raw === 'object' && !Array.isArray(raw)) ? raw : {};

  let question = typeof o.question === 'string' ? o.question.trim() : '';
  if (!question) warnings.push('no question');

  let bridge = typeof o.bridge === 'string' ? o.bridge.trim() : null;
  if (bridge && (BANNED_BRIDGE.test(bridge) || bridge.split(/\s+/).length > 15)) {
    bridge = null;                       // silently drop; never regenerate for this
    warnings.push('bridge dropped');
  }

  const move = moves.includes(o.move) ? o.move : (moves[0] || null);
  if (o.move && !moves.includes(o.move)) warnings.push(`illegal move ${o.move}`);

  const dimension = DIMENSION_IDS.includes(o.dimension) ? o.dimension : target;

  let chips = Array.isArray(o.chips)
    ? o.chips.filter((c) => typeof c === 'string' && c.trim()).map((c) => c.trim()).slice(0, 4)
    : [];
  chips = chips.filter((c) => c.split(/\s+/).length <= 14);

  const facts = Array.isArray(o.new_facts)
    ? o.new_facts.filter((f) => f && typeof f.fact === 'string' && f.fact.trim())
        .map((f) => ({ dimension: DIMENSION_IDS.includes(f.dimension) ? f.dimension : null, fact: f.fact.trim() }))
        .slice(0, 3)
    : [];

  const coverage = {};
  if (o.coverage && typeof o.coverage === 'object') {
    for (const id of DIMENSION_IDS) {
      const c = o.coverage[id];
      if (!c || typeof c !== 'object') continue;
      coverage[id] = {
        level: ['thin', 'partial', 'covered'].includes(c.level) ? c.level : null,
        gap: typeof c.gap === 'string' ? c.gap.trim() : null,
        evidence: typeof c.evidence === 'string' ? c.evidence.trim() : null,
      };
    }
  }

  return {
    ok: Boolean(question),
    bridge, question, move, dimension, chips, facts, coverage,
    suggestWrap: o.suggest_wrap === true,
    wrapReason: typeof o.wrap_reason === 'string' ? o.wrap_reason.trim() : null,
    warnings,
  };
}

/** Tripwires. A hit means regenerate ONCE with a corrective directive. */
export function questionTripwire(question, session) {
  const q = String(question || '');
  if (RE_GENERIC.test(q)) return 'generic';
  if ((q.match(/\?/g) || []).length > 1) return 'compound';
  // Two interrogatives joined by and/or is compound at ANY length:
  // "What is it and why does it matter?" is only eight words.
  const interrogatives = (q.match(/\b(what|why|how|who|when|where|which)\b/gi) || []).length;
  if (interrogatives >= 2 && /\b(and|or)\b/i.test(q)) return 'compound';
  if (q.split(/\s+/).length > 20 && /\b(and|or)\b/i.test(q)) return 'compound';
  const asked = session.turns.map((t) => new Set(contentWords(t.question)));
  const now = contentWords(q);
  if (now.length >= 3) {
    for (const prev of asked) {
      if (!prev.size) continue;
      const overlap = now.filter((w) => prev.has(w)).length / now.length;
      if (overlap > 0.6) return 'repeat';
    }
  }
  return null;
}

export const TRIPWIRE_DIRECTIVE = {
  generic: 'Your previous question was generic MBA-speak. Ask something specific that only this person could answer.',
  compound: 'Your previous question was compound. Ask ONE thing, in fewer than 20 words.',
  repeat: 'Your previous question repeated one already asked. Ask about something genuinely new.',
};
