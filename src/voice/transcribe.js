// Speech to text, for every platform where the browser's own recogniser is missing or
// lying — which, on an installed iPhone PWA, is all of them.
//
// Both endpoints are OpenAI-shaped multipart POSTs, so this is one adapter with a base
// URL. Groq's hosted whisper-large-v3-turbo is the default at roughly $0.0007/minute:
// a fifteen-minute interview costs about a penny, and it is the one provider whose CORS
// headers are unambiguous on both the preflight and the real response.

import { ProviderError, codeForStatus, retryAfterMs, withRetry } from '../providers/errors.js';

/** 25 MB is the documented limit on both services. Opus at 24 kbps reaches it around 2h. */
export const MAX_AUDIO_BYTES = 25 * 1024 * 1024;

export const STT_PRESETS = {
  groq: {
    label: 'Groq Whisper',
    baseUrl: 'https://api.groq.com/openai/v1',
    model: 'whisper-large-v3-turbo',
    note: 'About a penny per interview. Free tier, no card.',
  },
  openai: {
    label: 'OpenAI',
    baseUrl: 'https://api.openai.com/v1',
    model: 'gpt-4o-mini-transcribe',
    note: 'Around 0.3 cents a minute.',
  },
};

/**
 * @param {{kind?: string, apiKey: string, baseUrl?: string, model?: string,
 *          language?: string, prompt?: string}} config
 */
export function createTranscriber(config) {
  const cfg = config || {};
  const preset = STT_PRESETS[cfg.kind] || null;
  const baseUrl = String(cfg.baseUrl || (preset && preset.baseUrl) || '').replace(/\/+$/, '');
  const model = cfg.model || (preset && preset.model);
  const { apiKey = '', language, prompt } = cfg;

  if (!baseUrl || !model) throw new ProviderError('config', 'transcription needs a base URL and a model');
  if (!apiKey) throw new ProviderError('config', 'transcription needs an API key');

  async function once(blob, signal) {
    if (blob.size > MAX_AUDIO_BYTES) {
      throw new ProviderError('config',
        `that recording is ${(blob.size / 1048576).toFixed(1)} MB, over the 25 MB limit`);
    }
    const form = new FormData();
    form.append('file', blob, filenameFor(blob.type));
    form.append('model', model);
    form.append('response_format', 'json');
    if (language) form.append('language', language);
    // The question just asked is a free accuracy win: it primes the recogniser with the
    // domain words the answer is most likely to reuse.
    if (prompt) form.append('prompt', String(prompt).slice(0, 800));

    const res = await fetch(`${baseUrl}/audio/transcriptions`, {
      method: 'POST',
      credentials: 'omit',
      signal,
      headers: { authorization: `Bearer ${apiKey}` },   // no content-type: FormData sets the boundary
      body: form,
    }).catch((err) => {
      if (err && err.name === 'AbortError') throw new ProviderError('aborted', 'cancelled');
      // Same opaque-CORS trap as the chat endpoints: a rejected key can come back with no
      // response at all, so "check the key" is a better guess than "you are offline".
      throw new ProviderError('auth',
        'the transcription request was blocked before a reply came back — usually a rejected key.',
        { cause: err });
    });

    if (!res.ok) {
      let detail = '';
      try {
        const body = await res.json();
        detail = (body && body.error && (body.error.message || body.error.code)) || '';
      } catch { /* a non-JSON error body is still an error */ }
      const code = codeForStatus(res.status);
      throw new ProviderError(code,
        `transcription ${res.status}: ${detail || res.statusText}` +
        (code === 'auth' ? ' — check the transcription key in Settings' : ''),
        { status: res.status, retryAfterMs: retryAfterMs(res.headers) });
    }

    const body = await res.json();
    return String((body && body.text) || '').trim();
  }

  return {
    id: cfg.kind || 'custom',
    label: (preset && preset.label) || baseUrl,
    model,
    /** @returns {Promise<string>} the transcript, '' when nothing intelligible was said. */
    transcribe: (blob, { signal } = {}) => withRetry(() => once(blob, signal), { signal }),
  };
}

/** Whisper endpoints dispatch on the extension, so the name has to match the codec. */
function filenameFor(mime) {
  const type = String(mime || '').toLowerCase();
  if (type.includes('ogg')) return 'answer.ogg';
  if (type.includes('mp4') || type.includes('m4a') || type.includes('aac')) return 'answer.mp4';
  if (type.includes('wav')) return 'answer.wav';
  if (type.includes('mpeg') || type.includes('mp3')) return 'answer.mp3';
  return 'answer.webm';
}
