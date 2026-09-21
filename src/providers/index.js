// Picking a provider, and describing the choices to the settings screen.
//
// Two things deliberately absent, both researched rather than assumed:
//
//   GitHub Models (models.github.ai) was retired on 2026-07-30 and now answers 410 Gone.
//   There is nothing to point a GitHub token at.
//
//   The Copilot chat endpoint is not a general-purpose inference API. The only sanctioned
//   route to it was GitHub App based Copilot Extensions, sunset in November 2025, and
//   GitHub's acceptable-use terms name "proxy usage" and "excessive or automated usage"
//   as grounds for permanently disabling Copilot access. It answers CORS preflights, but
//   that is a technical fact rather than permission. Groq's free tier fills the same
//   no-cost slot with none of the risk.

import { ProviderError } from './errors.js';
import { assertLoopback } from './http.js';
import { createAnthropicProvider, ANTHROPIC_TIERS } from './anthropic.js';
import { createOpenAICompatProvider, OPENAI_COMPAT_PRESETS } from './openaiCompat.js';
import { createArtifactProvider, artifactRuntimeAvailable } from './artifact.js';

/** What the settings screen renders. Order is the order they are offered in. */
export const PROVIDER_CHOICES = [
  {
    id: 'artifact',
    label: 'Claude (this viewer)',
    needsKey: false,
    // The only choice with no model id anywhere in it. createArtifactProvider takes no
    // config at all — the viewer is handed a `modelTier` and picks for itself — so the
    // settings screen has nothing to offer and hides both model boxes. Declared rather
    // than inferred from `id`, for the same reason `local` and `auth` are data.
    picksModel: false,
    note: 'No key needed. Only available when IdeaForge is opened inside a Claude viewer.',
  },
  {
    id: 'anthropic',
    label: 'Anthropic',
    needsKey: true,
    picksModel: true,
    keyUrl: 'https://console.anthropic.com/settings/keys',
    note: `Claude Haiku for questions, Sonnet for the wrap-up. Around 10-20 cents an interview.`,
    models: ANTHROPIC_TIERS,
  },
  ...Object.entries(OPENAI_COMPAT_PRESETS).map(([id, p]) => ({
    id,
    label: p.label,
    // Declared by the preset, not sniffed out of its URL. The old rule here was
    // `!/localhost/.test(p.baseUrl)`, which called 127.0.0.1 remote and, worse, called
    // localhost.evil.com local.
    needsKey: !p.local,
    local: !!p.local,
    picksModel: true,
    discoverModels: !!p.discoverModels,
    defaultBaseUrl: p.baseUrl,
    keyUrl: p.keyUrl || null,
    note: p.note || null,
    models: p.tiers,
  })),
  {
    // The escape hatch for a local server we have never heard of — llama.cpp, vLLM, an
    // Ollama on a second port. Loopback only; createProvider refuses anything else and
    // says why, because a free-text remote host would hand away the CSP allowlist that
    // keeps the stored key from being posted somewhere.
    id: 'custom',
    label: 'Another local server',
    needsKey: false,
    local: true,
    picksModel: true,
    discoverModels: true,
    keyUrl: null,
    note: 'Any OpenAI-compatible server on this machine. Addresses on localhost or ' +
          '127.0.0.1 only.',
    models: null,
  },
];

/**
 * The user's two model choices folded into a preset's tier map.
 *
 * The interesting line is `complex`, and the order in it is the entire reason the settings
 * screen asks twice. It prefers the PRESET's strong tier over the question model, so
 * someone who picks Haiku to make a dozen questions cheap does not also, silently, lose
 * Sonnet on the one call that reads the whole transcript back. Invert those two and every
 * wrap-up quietly runs on whatever was chosen for the turns — a worse export, with nothing
 * anywhere reporting that anything happened.
 *
 * A local preset has `tiers: null` and therefore no strong tier, so there `complex` does
 * fall through to the question model and one filled box is still enough.
 *
 * @param {{quick: string, default: string, complex: string}|null} baseTiers
 * @param {{model?: string, wrapModel?: string}} choices
 */
export function resolveTiers(baseTiers, choices = {}) {
  const base = baseTiers || {};
  const { model = '', wrapModel = '' } = choices || {};
  return {
    quick: model || base.quick,
    default: model || base.default,
    complex: wrapModel || base.complex || model || undefined,
  };
}

/**
 * The models a provider choice is already known to run, deduped and sorted — what the
 * settings screen suggests before anyone has pressed Check, and the only suggestions
 * Anthropic will ever have, since it publishes no browser-reachable catalogue. Every entry
 * is a model the app itself defaults to, so none of them can go stale without the app
 * being broken anyway. A local choice has no tier map and correctly offers nothing.
 */
export function presetModels(choice) {
  const tiers = (choice && choice.models) || null;
  if (!tiers) return [];
  return [...new Set([tiers.quick, tiers.default, tiers.complex].filter(Boolean))].sort();
}

/**
 * @param {{kind: string, apiKey?: string, baseUrl?: string, model?: string,
 *          wrapModel?: string}} config
 * @returns {Promise<object>} a provider exposing sample / sampleJson / validateKey
 */
export async function createProvider(config) {
  const { kind } = config || {};
  if (!kind) throw new ProviderError('config', 'no provider selected');
  // Policy lives here rather than in the factory. createOpenAICompatProvider is the
  // mechanism and stays unconstrained so a test or a fork can point it anywhere; the
  // registry is what decides that a base URL a user typed may only be on their machine,
  // because that decision is about this page's CSP rather than about HTTP.
  if (config.baseUrl) assertLoopback(config.baseUrl);
  if (kind === 'artifact') return createArtifactProvider();

  // `model` and `wrapModel` become a tier map here and are deliberately NOT forwarded. The
  // adapters resolve `model || tiers[modelTier]`, so passing the scalar on as well would
  // let the question model win the wrap-up straight back. One resolution, in one place.
  const { model, wrapModel, ...rest } = config;
  const choices = { model, wrapModel };

  if (kind === 'anthropic') {
    return createAnthropicProvider({ ...rest, tiers: resolveTiers(ANTHROPIC_TIERS, choices) });
  }
  if (OPENAI_COMPAT_PRESETS[kind]) {
    return createOpenAICompatProvider({
      ...rest,
      preset: kind,
      tiers: resolveTiers(OPENAI_COMPAT_PRESETS[kind].tiers, choices),
    });
  }
  if (kind === 'custom') {
    // modelRequired defaults on, but never overrides a caller who switched it off — that
    // caller is the model-list read, which is how you find out what to put in the box.
    return createOpenAICompatProvider({
      ...rest, local: true, tiers: resolveTiers(null, choices),
      // After the spread, and `??` rather than `||`. app.js sends
      // `modelRequired: requireModel ? undefined : false` — an OWN property holding
      // `undefined`, which wins a spread and overwrote the `true` that used to sit before
      // it. "Another local server" with an empty model box therefore built happily and
      // POSTed a body with no `model` key at all. `??` keeps the deliberate `false` that
      // the model-list read passes, and refuses the accidental `undefined`.
      modelRequired: rest.modelRequired ?? true,
    });
  }
  throw new ProviderError('config', `unknown provider: ${kind}`);
}

/**
 * The provider to offer on a first run: the artifact runtime when we are inside a Claude
 * viewer, because it needs no key and no setup at all.
 */
export function defaultProviderKind() {
  return artifactRuntimeAvailable() ? 'artifact' : 'anthropic';
}

export { ProviderError } from './errors.js';
export { isLoopback } from './http.js';
export { extractJson } from './json.js';
