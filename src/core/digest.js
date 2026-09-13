// Transcript compaction and the 64 KiB budgeter.
//
// `sample()` caps total input at 64 KiB. A long interview WILL exceed that, so
// compaction is a day-one requirement, not an optimization. The strategy:
// verbatim while it fits, then a fact ledger plus a sliding verbatim window.
//
// The true verbatim transcript is kept by the caller for the export and is
// never model-normalized — only what we SEND is compacted.

export const MAX_INPUT_BYTES = 64 * 1024;
/** Leave headroom for instructions and the output schema. */
export const BUDGET_BYTES = 48 * 1024;
/** Any single answer longer than this is elided when serialized for the model. */
export const MAX_ANSWER_CHARS = 1200;

export const FULL_VERBATIM_UNTIL_TURN = 8;
export const WINDOW_STEPS = [6, 3, 0];

/** UTF-8 byte length, without depending on TextEncoder or Buffer. */
export function utf8Length(str) {
  let bytes = 0;
  for (let i = 0; i < str.length; i++) {
    const c = str.codePointAt(i);
    if (c <= 0x7f) bytes += 1;
    else if (c <= 0x7ff) bytes += 2;
    else if (c <= 0xffff) bytes += 3;
    else { bytes += 4; i++; }
  }
  return bytes;
}

function clip(text, max = MAX_ANSWER_CHARS) {
  const t = String(text || '');
  return t.length <= max ? t : t.slice(0, max) + ' […elided…]';
}

/** Provenance the model must be able to see, so it can discount its own words. */
function provenance(turn) {
  return turn.answerSource || 'typed';
}

export function serializeTurn(turn) {
  const q = `Q${turn.n}${turn.move ? ` [${turn.move}]` : ''}: ${turn.question}`;
  if (turn.skipped) return `${q}\nA${turn.n} [skipped]: (user skipped)`;
  return `${q}\nA${turn.n} [${provenance(turn)}]: ${clip(turn.answer)}`;
}

export function serializeFacts(facts) {
  if (!facts.length) return '(nothing established yet)';
  return facts.map((f) => `- ${f.dimension ? `(${f.dimension}) ` : ''}${f.fact}`).join('\n');
}

/**
 * Build the transcript block for a turn prompt, shrinking until it fits.
 * Returns {text, windowSize, facts, truncated}.
 */
export function buildTranscriptBlock(session, budget = BUDGET_BYTES) {
  const answered = session.turns.filter((t) => t.answer || t.skipped);

  const render = (windowSize) => {
    const parts = [];
    if (windowSize === null) {
      parts.push(answered.map(serializeTurn).join('\n\n'));
    } else {
      const recent = windowSize > 0 ? answered.slice(-windowSize) : [];
      const older = windowSize > 0 ? answered.slice(0, -windowSize) : answered;
      if (older.length) {
        parts.push(
          `[${older.length} earlier exchange(s) compacted to facts — see FACTS ESTABLISHED]`
        );
      }
      if (recent.length) parts.push(recent.map(serializeTurn).join('\n\n'));
    }
    return parts.filter(Boolean).join('\n\n') || '(no exchanges yet)';
  };

  // Verbatim while the interview is short and it fits.
  if (answered.length <= FULL_VERBATIM_UNTIL_TURN) {
    const text = render(null);
    if (utf8Length(text) <= budget) {
      return { text, windowSize: null, truncated: false };
    }
  }
  for (const windowSize of WINDOW_STEPS) {
    const text = render(windowSize);
    if (utf8Length(text) <= budget) return { text, windowSize, truncated: windowSize === 0 };
  }
  // Even facts-only overflows: hard-clip. Losing the tail of the SEND is
  // survivable; the export still holds everything verbatim.
  const text = render(0);
  return { text: text.slice(0, budget), windowSize: 0, truncated: true };
}
