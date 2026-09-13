// Getting an object back out of a model that was asked for one.
//
// Even with a provider-side JSON mode, models fence their output, prepend "Here is the
// JSON:", or emit a trailing comma. parseTurnResult is already hardened against a wrong
// *shape*; this file is only about getting from a string to *some* object so that
// hardening gets a chance to run.

import { ProviderError } from './errors.js';

const FENCE = /```(?:json|JSON)?\s*([\s\S]*?)```/;

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

const stripTrailingCommas = (s) => s.replace(/,(\s*[}\]])/g, '$1');
