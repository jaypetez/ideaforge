// Zero-gain arithmetic, split out so it can be tested without a provider.
//
// `shouldOfferWrap` returns 'exhausted' on `zeroGainStreak >= 2`, but until now nothing
// in the codebase ever wrote that field. This is the only writer's arithmetic.

import { DIMENSION_IDS, LEVEL_RANK } from '../core/dimensions.js';

/** @returns {Record<string, string>} dimension id -> level, before or after a turn. */
export function levelSnapshot(session) {
  const out = {};
  for (const id of DIMENSION_IDS) out[id] = session.coverage[id].level;
  return out;
}

/**
 * A turn gained nothing when no dimension sits at a higher level after the ratchet than
 * before it.
 *
 * Measured on the applied result, never on the model's claim. A model that asserts
 * `covered` three turns running while `applyCoverage` clamps every one of them is making
 * no progress at all — that pathology is exactly what the exhaustion exit is for, and
 * measuring the claim instead would mean the counter never fires.
 *
 * Levels only: a turn that banks three facts but lifts no level is still zero gain. The
 * question is not "did anything arrive" but "are we still learning things that change
 * whether a section can be written".
 */
export function isZeroGain(before, after) {
  return DIMENSION_IDS.every((id) => LEVEL_RANK[after[id]] <= LEVEL_RANK[before[id]]);
}
