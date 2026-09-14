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
