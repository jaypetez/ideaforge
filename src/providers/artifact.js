// The Claude artifact runtime: `window.claude.use('sample')`.
//
// The zero-setup path — no key, no settings screen, nothing to paste — which makes it the
// right default when the app is opened inside a Claude viewer. spikes/probe.js measured
// this surface on day 0; the shape below is what it found: sample(prompt, opts) resolving
// to { text, modelTierApplied }, with a five-minute cache keyed on the prompt bytes.
//
// That cache is why buildTurnPrompt is asserted to be timestamp-free: a turn interrupted
// mid-flight is recovered by re-issuing the byte-identical prompt and getting the same
// answer back for nothing.

import { ProviderError, withRetry } from './errors.js';
import { extractJson } from './json.js';

/** @returns {boolean} true when running inside a Claude viewer that offers sampling. */
export function artifactRuntimeAvailable() {
  return typeof window !== 'undefined'
    && !!window.claude
    && typeof window.claude.use === 'function';
}

export async function createArtifactProvider() {
  if (!artifactRuntimeAvailable()) {
    throw new ProviderError('config', 'not running inside a Claude viewer');
  }
  const sample = await window.claude.use('sample');
  if (typeof sample !== 'function') {
    throw new ProviderError('config', 'the sample capability was not granted');
  }

  async function call({ system, prefix, tail }, { modelTier = 'default', cache = true, signal, onText } = {}) {
    if (signal && signal.aborted) throw new ProviderError('aborted', 'cancelled');
    try {
      const r = await sample([system, prefix, tail].join('\n'), { modelTier, cache, onText });
      return { text: r.text, modelTierApplied: r.modelTierApplied || modelTier, usage: null };
    } catch (err) {
      throw translate(err);
    }
  }

  return {
    id: 'artifact',
    label: 'Claude (this viewer)',
    sample: (parts, opts = {}) => withRetry(() => call(parts, opts), { signal: opts.signal }),
    sampleJson: async (parts, opts = {}) => {
      const out = await withRetry(() => call(parts, opts), { signal: opts.signal });
      return { ...out, json: extractJson(out.text) };
    },
    validateKey: async () => true,
  };
}

/** The runtime rejects with a `code`, not an HTTP status. */
function translate(err) {
  const code = err && err.code;
  if (code === 'not_granted' || code === 'permission_denied') {
    return new ProviderError('auth', 'this viewer has not granted the sample capability');
  }
  if (code === 'rate_limited') return new ProviderError('rate_limit', 'the viewer is rate limiting');
  if (code === 'overloaded') return new ProviderError('overloaded', 'the model is busy');
  if (code === 'aborted') return new ProviderError('aborted', 'cancelled');
  return new ProviderError('bad_response', (err && err.message) || 'sample() failed', { cause: err });
}
