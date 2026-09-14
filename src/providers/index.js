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
    note: 'No key needed. Only available when IdeaForge is opened inside a Claude viewer.',
  },
  {
    id: 'anthropic',
    label: 'Anthropic',
    needsKey: true,
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
    discoverModels: true,
    keyUrl: null,
    note: 'Any OpenAI-compatible server on this machine. Addresses on localhost or ' +
          '127.0.0.1 only.',
    models: null,
  },
];

/**
 * @param {{kind: string, apiKey?: string, baseUrl?: string, model?: string}} config
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
  if (kind === 'anthropic') return createAnthropicProvider(config);
  if (OPENAI_COMPAT_PRESETS[kind]) return createOpenAICompatProvider({ ...config, preset: kind });
  if (kind === 'custom') {
    // modelRequired defaults on, but never overrides a caller who switched it off — that
    // caller is the model-list read, which is how you find out what to put in the box.
    return createOpenAICompatProvider({ modelRequired: true, ...config, local: true });
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
