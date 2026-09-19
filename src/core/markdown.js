// The export. Pure — no model involved. Parts 2 and 3 are always available even
// when synthesis failed or Claude was never reachable.
//
// Order is refined prompt → open questions → transcript (not transcript first):
// the prompt is what you came for, and a 30-turn transcript above it means
// scrolling past everything you already know.

import { DIMENSION_IDS, getDimension, LEVEL_RANK } from './dimensions.js';
import { isLowConfidence } from './session.js';

const LOW_CONF_NOTE = '_(drafted by Claude and submitted unedited — treat as unconfirmed)_';

function isoDate(ms) {
  return new Date(ms || 0).toISOString().slice(0, 10);
}

export function coveragePercent(session) {
  const probing = DIMENSION_IDS.filter((id) => session.coverage[id].status === 'probing');
  if (!probing.length) return 100;
  const got = probing.reduce((a, id) => a + LEVEL_RANK[session.coverage[id].level], 0);
  return Math.round((got / (probing.length * 2)) * 100);
}

export function slug(text, fallback = 'idea') {
  const s = String(text || '').toLowerCase().replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '').slice(0, 48);
  return s || fallback;
}

export function exportFilename(session) {
  return `ideaforge-${slug(session.title)}-${isoDate(session.updatedAt)}.md`;
}

function transcriptSection(session) {
  const answered = session.turns.filter((t) => t.answer || t.skipped);
  if (!answered.length) return '_No exchanges recorded._';
  return answered.map((t) => {
    const dim = t.dimension ? getDimension(t.dimension).label : 'Opening';
    const head = `### ${t.n} · ${dim}${t.move ? `  \`${t.move}\`` : ''}`;
    if (t.skipped) return `${head}\n\n**Q:** ${t.question}\n\n**A:** _(skipped)_`;
    const note = isLowConfidence(t) ? `\n\n${LOW_CONF_NOTE}` : '';
    const voice = t.answerSource === 'voice' ? ' _(dictated)_' : '';
    return `${head}\n\n**Q:** ${t.question}\n\n**A:**${voice} ${t.answer}${note}`;
  }).join('\n\n');
}

function openQuestionsSection(session) {
  const items = [];
  for (const q of session.openQuestions || []) {
    const dim = q.dimension && DIMENSION_IDS.includes(q.dimension)
      ? getDimension(q.dimension).label : null;
    items.push(`- ${dim ? `**${dim}** — ` : ''}${q.question || q.text}` +
      (q.why_it_matters ? `\n  _Why it matters: ${q.why_it_matters}_` : ''));
  }
  // Deliberate omissions are not failures — say so.
  for (const id of DIMENSION_IDS) {
    const c = session.coverage[id];
    if (c.status === 'waived') items.push(`- **${getDimension(id).label}** — deliberately left out${c.waivedReason ? ` (“${c.waivedReason}”)` : ''}.`);
    else if (c.status === 'deferred') items.push(`- **${getDimension(id).label}** — a decision still to make.`);
  }
  return items.length ? items.join('\n') : '_Nothing outstanding._';
}

function coverageTable(session) {
  const rows = DIMENSION_IDS.map((id) => {
    const c = session.coverage[id];
    const mark = c.status !== 'probing' ? c.status
      : { thin: '○ thin', partial: '◐ partial', covered: '● covered' }[c.level];
    return `| ${getDimension(id).label} | ${mark} | ${c.gap || '—'} |`;
  });
  return ['| Dimension | Coverage | Gap |', '|---|---|---|', ...rows].join('\n');
}

/**
 * @param {object} session
 * @param {{mode?: 'claude'|'checklist', note?: string}} opts
 */
export function buildExport(session, { mode = 'claude', note = null } = {}) {
  const title = session.title || 'Untitled idea';
  const answered = session.turns.filter((t) => t.answer || t.skipped).length;
  const lowConf = session.turns.filter((t) => isLowConfidence(t)).length;

  const meta = [
    isoDate(session.updatedAt),
    `${answered} question${answered === 1 ? '' : 's'}`,
    `coverage ${coveragePercent(session)}%`,
    mode === 'checklist' ? 'assembled locally' : 'synthesised by Claude',
  ].join(' · ');

  const parts = [`# ${title}`, `_${meta}_`];

  if (note) parts.push(`> ${note}`);
  if (lowConf > 0 && answered > 0 && lowConf / answered > 0.5) {
    parts.push('> **A lot of this was drafted for you rather than written by you.** ' +
               'Worth a read before you rely on it.');
  }
  if (session.synthesis.stale) {
    parts.push('> The refined prompt below predates the most recent answers — regenerate it.');
  }
  // A turn whose model call failed is answered from the static question bank instead, and
  // the interview carries on. That is the right behaviour live — it never dead-ends — but
  // this document outlives the session, and without a line here it is indistinguishable
  // from one where every question was written for the answer before it. A wrap-up call can
  // succeed while every turn failed, so the meta line's "synthesised by Claude" is not the
  // same claim and cannot stand in for this.
  const banked = session.turns.filter((t) => t.questionSource === 'bank').length;
  if (banked > 0) {
    parts.push(`> **${banked} of these questions came from the built-in checklist, not ` +
               'from the model** — the model could not be reached for those turns, so they ' +
               'are generic rather than grounded in your answers.');
  }

  parts.push('## Refined prompt');
  parts.push(session.synthesis.text ||
    '_Not generated. The transcript and open questions below are still complete._');

  if (session.synthesis.assumptions && session.synthesis.assumptions.length) {
    parts.push('### Assumptions made during synthesis');
    parts.push(session.synthesis.assumptions.map((a) => `- ${a}`).join('\n'));
  }

  parts.push('## Open questions');
  parts.push(openQuestionsSection(session));

  parts.push('## Coverage');
  parts.push(coverageTable(session));

  parts.push('## Transcript');
  if (session.opening) parts.push(`**The idea, as first stated:** ${session.opening}`);
  parts.push(transcriptSection(session));

  parts.push('---');
  parts.push('_Forged with IdeaForge._');

  return parts.join('\n\n') + '\n';
}

/**
 * The same text, said out loud.
 *
 * Markdown read by a speech synthesiser is punctuation soup — "hash hash Refined prompt",
 * "star star Constraints star star" — so the markers come out and the structure is carried
 * by sentence breaks instead, which is all a listener gets anyway.
 *
 * Chunked by the caller, not here: speak.js caps its own wait at thirty seconds to survive
 * Chrome's utterance watchdog, so one long passage is abandoned mid-sentence rather than
 * read. `speechChunks` is the other half of this.
 */
export function forSpeech(markdown, { maxChars = 3000 } = {}) {
  let t = String(markdown || '');
  t = t.replace(/```[\s\S]*?```/g, ' ');            // a code fence read aloud is noise
  t = t.replace(/`([^`]*)`/g, '$1');
  t = t.replace(/!\[[^\]]*\]\([^)]*\)/g, ' ');
  t = t.replace(/\[([^\]]*)\]\([^)]*\)/g, '$1');    // a URL is unsayable; its label is not
  t = t.replace(/^\s{0,3}#{1,6}\s+(.*)$/gm, '$1.');  // a heading is a sentence of its own
  t = t.replace(/^\s{0,3}>\s?/gm, '');
  t = t.replace(/^\s{0,3}[-*+]\s+/gm, '');           // bullets become sentences
  t = t.replace(/^\s{0,3}\d+[.)]\s+/gm, '');
  t = t.replace(/^\s{0,3}([-*_])(\s*\1){2,}\s*$/gm, ' ');
  t = t.replace(/\*\*([^*]+)\*\*/g, '$1');
  t = t.replace(/([*_])([^*_]+)\1/g, '$2');
  t = t.replace(/[ \t]+/g, ' ');
  t = t.split('\n').map((l) => l.trim()).filter(Boolean)
    .map((l) => (/[.!?:]$/.test(l) ? l : `${l}.`))
    .join(' ');
  t = t.replace(/\s+([.,!?;:])/g, '$1').replace(/\.{2,}/g, '.').trim();

  if (t.length <= maxChars) return t;
  // Cut on a sentence boundary: trailing off mid-clause sounds like a crash.
  const cut = t.slice(0, maxChars);
  const stop = Math.max(cut.lastIndexOf('. '), cut.lastIndexOf('! '), cut.lastIndexOf('? '));
  return (stop > maxChars * 0.5 ? cut.slice(0, stop + 1) : cut).trim();
}

/**
 * Split spoken text into pieces a synthesiser will actually finish.
 *
 * speak.js waits at most `2s + words/2.6` and gives up, because Chrome silently drops an
 * utterance that outlasts its own watchdog. A six-hundred-word prompt read as one utterance
 * is therefore abandoned partway through with no error — the failure this exists to avoid.
 */
export function speechChunks(text, { maxChars = 350 } = {}) {
  const out = [];
  let current = '';
  for (const piece of String(text || '').split(/(?<=[.!?])\s+/)) {
    if (!piece) continue;
    if (current && (current.length + piece.length + 1) > maxChars) {
      out.push(current);
      current = piece;
    } else {
      current = current ? `${current} ${piece}` : piece;
    }
  }
  if (current) out.push(current);
  return out;
}
