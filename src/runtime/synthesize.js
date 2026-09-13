// The wrap-up call: one request that turns the whole interview into the refined prompt.
//
// Same injected-provider, injected-clock discipline as turn.js. The important property is
// that failure is survivable: markdown.js already renders a complete document with no
// synthesis at all, so a failed wrap-up costs the user the refined prompt and nothing
// else — never the transcript.

import { setPending, clearPending, setSynthesis, setTitle, setStatusField } from '../core/session.js';
import { buildSynthesisPromptParts, buildSynthesisPrompt, parseSynthesisResult } from '../core/synthesis.js';
import { promptHash } from '../core/engine.js';

/**
 * @param {object} session
 * @param {{provider: object|null, now?: number, modelTier?: string, budget?: number,
 *          signal?: AbortSignal}} deps
 * @returns {Promise<{session: object, ok: boolean, error: Error|null, aborted: boolean,
 *                    warnings: string[], calls: number}>}
 */
export async function runSynthesis(session, deps = {}) {
  // The wrap-up is the one call where quality beats latency: it happens once, the user is
  // waiting for it on purpose, and it is the artifact they came for.
  const { provider = null, now = 0, modelTier = 'complex', budget, signal } = deps;
  const warnings = [];

  if (!provider) {
    return { session, ok: false, error: null, aborted: false, calls: 0,
             warnings: ['no provider; the export will use the checklist layout'] };
  }

  const parts = buildSynthesisPromptParts(session, { budget });
  let working = setPending(session, {
    kind: 'synthesis',
    promptHash: promptHash(buildSynthesisPrompt(session, { budget })),
    startedAt: now,
  }, now);
  working = setStatusField(working, 'synthesizing', now);

  let result;
  try {
    result = await provider.sampleJson(parts, { modelTier, signal, cache: true });
  } catch (err) {
    const restored = setStatusField(clearPending(working, now), session.status, now);
    if (err && err.code === 'aborted') {
      return { session: restored, ok: false, error: null, aborted: true, warnings, calls: 1 };
    }
    return { session: restored, ok: false, error: err, aborted: false, warnings, calls: 1 };
  }

  const parsed = parseSynthesisResult(result.json);
  warnings.push(...parsed.warnings);

  if (!parsed.ok) {
    const restored = setStatusField(clearPending(working, now), session.status, now);
    return { session: restored, ok: false, error: null, aborted: false,
             warnings: [...warnings, 'the model returned no usable prompt'], calls: 1 };
  }

  let s = setSynthesis(working, {
    text: parsed.text,
    tier: result.modelTierApplied || modelTier,
    assumptions: parsed.assumptions,
    openQuestions: parsed.openQuestions,
    now,
  });
  if (parsed.title && !session.title) s = setTitle(s, parsed.title, now);
  s = clearPending(s, now);

  return { session: s, ok: true, error: null, aborted: false, warnings, calls: 1 };
}

/**
 * Resume an interrupted wrap-up. Simpler than resumeTurn: synthesis is idempotent — it
 * appends no turn and mutates no coverage — so re-running it is always safe, and the
 * prompt hash is only a cache-replay hint.
 */
export async function resumeSynthesis(session, deps = {}) {
  const { now = 0 } = deps;
  if (!session.pending || session.pending.kind !== 'synthesis') {
    return { session, ok: false, error: null, aborted: false, warnings: [], calls: 0 };
  }
  return runSynthesis(clearPending(session, now), deps);
}
