// The synthesis pass: the single call at the end of an interview that turns a transcript
// into the thing the user actually came for — a prompt they can paste into any LLM.
//
// Pure, like the rest of core. It builds a prompt and parses a reply; nothing here knows
// what a network is. Split into parts for the same cache reason as the turn prompt, and
// deterministic for the same crash-recovery reason.
//
// The one rule that matters more than any other: synthesis must not invent. A gap becomes
// an open question, never a plausible-sounding guess. The whole value of the coverage
// ratchet is lost if the last step quietly fills in what the user never said.

import { DIMENSIONS, DIMENSION_IDS, getDimension, LEVEL_RANK } from './dimensions.js';
import { buildTranscriptBlock, serializeFacts, BUDGET_BYTES } from './digest.js';

/** Cap on what we accept back, so one runaway field cannot dominate the export. */
export const MAX_ASSUMPTIONS = 6;
export const MAX_OPEN_QUESTIONS = 8;
export const MAX_TITLE_WORDS = 8;

const sectionList = () => DIMENSIONS.map((d) => `  ${d.section} — ${d.label}`).join('\n');

const SYNTH_RULES = `You are the synthesist inside IdeaForge. An interview just finished. You are writing the
artifact the user came for: a refined prompt they can paste into any LLM and get what they
actually wanted.

You are not summarising the conversation. You are not writing a report about their idea.
You are writing an instruction to a model, in the second person, that carries everything
the interview extracted.

WHAT YOU ARE WRITING
A markdown prompt organised under these headings, in this order, omitting any heading you
have nothing real to say under:
${sectionList()}

HARD CONSTRAINTS
1. NEVER invent. If the interview did not establish something, it does not go in the
   prompt — it goes in open_questions. A confident-sounding guess is the worst failure
   available to you, because it will be read as something the user said.
2. Use THEIR vocabulary and THEIR specifics — the real numbers, names, and cases from the
   transcript. Those specifics are the entire reason the interview happened.
3. No praise, no meta-commentary, no "based on our conversation", no restating the
   interview back. The prompt should read as though the user wrote it carefully.
4. Write the prompt in the second person, addressed to the model that will execute it.
5. Where the user gave a verbatim fragment of the voice or format they want, quote it.
6. Answers marked [chip], [draft-unedited] or [skipped] in the transcript are NOT the
   user's own words. Do not treat them as established fact.
7. Dictated answers are marked [voice]. Read them for intent and silently repair obvious
   transcription noise; never carry a mis-transcription through into the prompt.

ASSUMPTIONS
Anything you had to decide that the user did not decide. Be honest and specific — "assumed
the 2-page limit applies to the appendix too", not "assumed standard conventions". If you
made none, return an empty array. Never pad it.

OPEN QUESTIONS
The decisions still outstanding, one per entry, each with why it matters. Prefer the gaps
recorded in the coverage map, but include anything the transcript left genuinely unsettled.
Do not include questions the transcript already answers.

TITLE
Max ${MAX_TITLE_WORDS} words, their words where possible, no trailing punctuation. It names
the idea; it is not a description of it.`;

function coverageDigest(session) {
  return DIMENSION_IDS.map((id) => {
    const c = session.coverage[id];
    const d = getDimension(id);
    const state = c.status !== 'probing' ? c.status : c.level;
    return `${d.section} | ${state} | ${c.status === 'probing' && LEVEL_RANK[c.level] < 2
      ? (c.gap || 'unspecified gap') : (c.evidence || '—')}`;
  }).join('\n');
}

/**
 * The synthesis prompt, split at its cache boundary. `prefix` is the same append-only
 * material the turn prompts already sent, so on a provider with prompt caching the
 * synthesis call largely reads from a cache the interview already paid for.
 *
 * @returns {{system: string, prefix: string, tail: string}}
 */
export function buildSynthesisPromptParts(session, { budget = BUDGET_BYTES } = {}) {
  const { text: transcript } = buildTranscriptBlock(session, budget);

  const prefix = [
    '=== THE IDEA (their opening statement, verbatim) ===',
    session.opening || '(not given)',
    '',
    '=== FACTS ESTABLISHED ===',
    serializeFacts(session.facts),
    '',
    '=== TRANSCRIPT ===',
    'Answers marked [voice] are dictated: read for intent, tolerate transcription errors.',
    transcript,
  ].filter((l) => l !== '').join('\n');

  const tail = [
    '=== COVERAGE AT WRAP ===',
    'Format: section | level | evidence or remaining gap',
    coverageDigest(session),
    '',
    '=== OUTPUT — return this JSON object and nothing else ===',
    '{',
    '  "title": string,',
    '  "prompt": string,                       // markdown, the refined prompt itself',
    `  "assumptions": [string, ...],           // <=${MAX_ASSUMPTIONS}, [] if none`,
    '  "open_questions": [{"dimension": string, "question": string, "why_it_matters": string}]',
    '}',
  ].join('\n');

  return { system: SYNTH_RULES, prefix, tail };
}

/** @returns {string} the complete synthesis prompt. Deterministic for a session state. */
export function buildSynthesisPrompt(session, opts = {}) {
  const { system, prefix, tail } = buildSynthesisPromptParts(session, opts);
  return [system, prefix, tail].join('\n');
}

const str = (v) => (typeof v === 'string' ? v.trim() : '');

/**
 * Validate a synthesis reply. As with parseTurnResult, the model is assumed to lie about
 * shape: every field is optional, and a missing prompt is a failure the caller can fall
 * back from rather than an exception.
 *
 * @returns {{ok: boolean, title: string, text: string, assumptions: string[],
 *            openQuestions: object[], warnings: string[]}}
 */
export function parseSynthesisResult(raw) {
  const warnings = [];
  const o = (raw && typeof raw === 'object' && !Array.isArray(raw)) ? raw : {};

  const text = str(o.prompt);
  if (!text) warnings.push('no prompt');

  let title = str(o.title).replace(/[.!?,;:\s]+$/, '');
  if (title.split(/\s+/).filter(Boolean).length > MAX_TITLE_WORDS) {
    title = title.split(/\s+/).slice(0, MAX_TITLE_WORDS).join(' ');
    warnings.push('title truncated');
  }

  const assumptions = Array.isArray(o.assumptions)
    ? o.assumptions.map(str).filter(Boolean).slice(0, MAX_ASSUMPTIONS)
    : [];
  if (o.assumptions && !Array.isArray(o.assumptions)) warnings.push('assumptions not an array');

  const openQuestions = Array.isArray(o.open_questions)
    ? o.open_questions
        .filter((q) => q && typeof q === 'object' && str(q.question))
        .map((q) => ({
          dimension: DIMENSION_IDS.includes(q.dimension) ? q.dimension : null,
          question: str(q.question),
          why_it_matters: str(q.why_it_matters) || null,
        }))
        .slice(0, MAX_OPEN_QUESTIONS)
    : [];

  return { ok: Boolean(text), title, text, assumptions, openQuestions, warnings };
}
