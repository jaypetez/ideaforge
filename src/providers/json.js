// Getting an object back out of a model that was asked for one.
//
// Even with a provider-side JSON mode, models fence their output, prepend "Here is the
// JSON:", or emit a trailing comma. parseTurnResult is already hardened against a wrong
// *shape*; this file is only about getting from a string to *some* object so that
// hardening gets a chance to run.

import { ProviderError } from './errors.js';

const FENCE = /```(?:json|JSON)?\s*([\s\S]*?)```/;

const REASONING_BLOCK = /<(think|thinking|reasoning)>[\s\S]*?<\/\1>/gi;
const REASONING_CLOSE = /<\/(?:think|thinking|reasoning)>/gi;

/**
 * @param {string} text raw model output
 * @returns {object} the parsed object
 * @throws {ProviderError} code 'bad_response' when nothing object-shaped can be recovered
 */
export function extractJson(text) {
  const raw = String(text == null ? '' : text).trim();
  if (!raw) throw new ProviderError('bad_response', 'model returned an empty response');

  for (const candidate of candidates(raw)) {
    try {
      const parsed = JSON.parse(candidate);
      if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) return parsed;
    } catch { /* try the next candidate */ }
  }
  throw new ProviderError(
    'bad_response',
    `model did not return a JSON object (got ${raw.length} chars starting "${raw.slice(0, 60)}")`
  );
}

function* candidates(raw) {
  yield* shapes(raw);

  // Only worth a second pass when there was reasoning to remove.
  const plain = withoutReasoning(raw);
  if (plain && plain !== raw) yield* shapes(plain);
}

function* shapes(raw) {
  yield raw;

  const fenced = raw.match(FENCE);
  if (fenced) yield fenced[1].trim();

  // Outermost braces: survives a preamble, a sign-off, or both.
  const first = raw.indexOf('{');
  const last = raw.lastIndexOf('}');
  if (first !== -1 && last > first) {
    const slice = raw.slice(first, last + 1);
    yield slice;
    yield stripTrailingCommas(slice);
  }
}

/**
 * Drop a reasoning model's narration.
 *
 * Local models that think out loud — qwen3, deepseek-r1, and anything else with a chat
 * template that emits <think> — narrate before they answer, and the narration routinely
 * contains braces. That defeats the outermost-brace rule above, which then starts its slice
 * somewhere inside the reasoning and parses nothing. The failure is worth naming because of
 * where it lands: extractJson throws from inside the adapter, which is OUTSIDE runTurn's
 * JSON-repair retry, so the turn goes straight to the question bank with no second attempt
 * and the interview quietly continues without the model.
 */
function withoutReasoning(raw) {
  let out = raw.replace(REASONING_BLOCK, '').trim();

  // A reply that was cut off, or one whose opening tag the server swallowed, arrives with a
  // closing tag and no opener. Anything before the last one is narration either way.
  const closes = [...out.matchAll(REASONING_CLOSE)];
  if (closes.length) {
    const last = closes[closes.length - 1];
    out = out.slice(last.index + last[0].length).trim();
  }
  return out;
}

const stripTrailingCommas = (s) => s.replace(/,(\s*[}\]])/g, '$1');
