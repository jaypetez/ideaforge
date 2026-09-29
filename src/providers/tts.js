// Buffered speech prototype, not a verified direct-browser integration. The UI must
// obtain explicit consent and a successful no-fallback preview before selecting it.
// No retries: a failed paid request must not silently spend again.

import { ProviderError } from './errors.js';
import { AUTH_BEARER, applyAuth, httpError, withDeadline, abortError } from './http.js';

export const MAX_TTS_INPUT_CHARS = 4096;
export const MAX_TTS_AUDIO_BYTES = 8 * 1024 * 1024;
export const MAX_TTS_AUDIO_SECONDS = 120;
export const TTS_DEADLINE_MS = 30000;

const OPENAI = Object.freeze({
  label: 'OpenAI speech',
  baseUrl: 'https://api.openai.com/v1',
  url: 'https://api.openai.com/v1/audio/speech',
  auth: AUTH_BEARER,
  model: 'gpt-4o-mini-tts',
  voices: Object.freeze(['marin', 'cedar']),
  responseFormat: 'wav',
  maxChars: MAX_TTS_INPUT_CHARS,
});
export const TTS_PRESETS = Object.freeze({ openai: OPENAI });

const badAudio = (message) => new ProviderError('bad_response', message);
const aborted = () => new ProviderError('aborted', 'Speech cancelled.');
const UNKNOWN_WAV_SIZE = 0xffffffff;

function rateFor(value) {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0.25 || value > 4) {
    throw new ProviderError('config', 'OpenAI speech rate must be a number from 0.25 to 4.');
  }
  return value;
}

// Race even injected/buggy transports that ignore abort; their late results are observed
// only to release promise handlers, never to continue reading or playing audio.
function abortable(promise, signal) {
  return new Promise((resolve, reject) => {
    const abort = () => { signal.removeEventListener('abort', abort); reject(signal.reason); };
    if (signal.aborted) {
      Promise.resolve(promise).catch(() => {});
      abort();
      return;
    }
    signal.addEventListener('abort', abort, { once: true });
    Promise.resolve(promise).then((value) => {
      signal.removeEventListener('abort', abort);
      if (signal.aborted) reject(signal.reason);
      else resolve(value);
    }, (error) => {
      signal.removeEventListener('abort', abort);
      reject(error);
    });
  });
}

async function readBounded(res, signal, limit) {
  const lengthHeader = res.headers.get('content-length');
  let expected = null;
  if (lengthHeader !== null) {
    if (!/^\d+$/.test(lengthHeader)) throw badAudio('Speech returned an invalid audio length.');
    expected = Number(lengthHeader);
    if (!Number.isSafeInteger(expected) || expected > limit) {
      throw badAudio('Speech audio exceeds the response size limit.');
    }
  }
  if (!res.body?.getReader) throw badAudio('Speech returned no readable audio body.');
  const reader = res.body.getReader();
  const chunks = [];
  let size = 0;
  let complete = false;
  try {
    for (;;) {
      signal.throwIfAborted();
      const { done, value } = await abortable(reader.read(), signal);
      if (done) { complete = true; break; }
      if (!(value instanceof Uint8Array)) throw badAudio('Speech returned malformed audio bytes.');
      size += value.byteLength;
      if (size > limit) throw badAudio('Speech audio exceeds the response size limit.');
      chunks.push(value);
    }
    if (expected !== null && size !== expected) throw badAudio('Speech audio was truncated.');
    if (!size) throw badAudio('Speech returned empty audio.');
    const bytes = new Uint8Array(size);
    let offset = 0;
    for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
    return bytes.buffer;
  } finally {
    // Cancelling an already-errored body can reject; preserve the original failure.
    if (!complete) reader.cancel().catch(() => {});
    reader.releaseLock();
  }
}

/**
 * Strict sized-PCM validation before decodeAudioData: some decoders accept truncated
 * files. Only the adapter may normalize streaming size sentinels, after bounded HTTP EOF.
 */
export function validateSpeechAudio(audio) {
  return inspectSpeechAudio(audio).info;
}

function inspectSpeechAudio(audio, completedBody = false) {
  if (!(audio instanceof ArrayBuffer) || audio.byteLength < 44) {
    throw badAudio('Speech returned empty or malformed WAV audio.');
  }
  if (audio.byteLength > MAX_TTS_AUDIO_BYTES) throw badAudio('Speech audio exceeds the response size limit.');
  const bytes = new Uint8Array(audio);
  const data = new DataView(audio);
  const tag = (at) => String.fromCharCode(...bytes.subarray(at, at + 4));
  if (tag(0) !== 'RIFF' || tag(8) !== 'WAVE') throw badAudio('Speech returned an unsupported audio format.');
  const sizes = [];
  const riffSize = data.getUint32(4, true);
  if (riffSize + 8 !== bytes.length) {
    if (!completedBody || riffSize !== UNKNOWN_WAV_SIZE) {
      throw badAudio('Speech returned truncated or unknown-length WAV audio.');
    }
    sizes.push([4, bytes.length - 8]);
  }
  let format = null;
  let samples = null;
  let at = 12;
  while (at < bytes.length) {
    if (at + 8 > bytes.length) throw badAudio('Speech returned a truncated WAV chunk.');
    const name = tag(at);
    let size = data.getUint32(at + 4, true);
    const start = at + 8;
    if (completedBody && name === 'data' && size === UNKNOWN_WAV_SIZE) {
      if (!format) throw badAudio('Streaming WAV audio is missing its preceding PCM format.');
      size = bytes.length - start;
      sizes.push([at + 4, size]);
    }
    const end = start + size;
    if (end + (size % 2) > bytes.length) throw badAudio('Speech returned a truncated WAV chunk.');
    if (name === 'fmt ') {
      if (format || size < 16) throw badAudio('Speech returned a malformed WAV format.');
      format = {
        encoding: data.getUint16(start, true), channels: data.getUint16(start + 2, true),
        sampleRate: data.getUint32(start + 4, true), byteRate: data.getUint32(start + 8, true),
        blockAlign: data.getUint16(start + 12, true), bits: data.getUint16(start + 14, true),
      };
    } else if (name === 'data') {
      if (samples !== null) throw badAudio('Speech returned multiple WAV data chunks.');
      samples = size;
    }
    at = end + (size % 2);
  }
  if (!format || !samples) throw badAudio('Speech returned no playable WAV samples.');
  const { encoding, channels, sampleRate, byteRate, blockAlign, bits } = format;
  if (encoding !== 1 || ![1, 2].includes(channels) || ![8, 16, 24, 32].includes(bits)
      || sampleRate < 8000 || sampleRate > 96000 || blockAlign !== channels * bits / 8
      || byteRate !== sampleRate * blockAlign || samples % blockAlign !== 0) {
    throw badAudio('Speech returned an unsupported or malformed PCM WAV format.');
  }
  const duration = samples / byteRate;
  if (duration > MAX_TTS_AUDIO_SECONDS) throw badAudio('Speech audio exceeds the duration limit.');
  return { info: { duration, sampleRate, channels }, sizes };
}

/**
 * Only this fixed endpoint/model/stock-voice set is allowed. `fetch` and `deadline` are
 * test seams, not user-entered endpoint settings. One call returns one buffered WAV.
 */
export function createTTSProvider({
  apiKey = '', voice = 'marin', rate = 1,
  fetch: fetchImpl = (...args) => globalThis.fetch(...args),
  deadlineMs = TTS_DEADLINE_MS, deadline = withDeadline,
} = {}) {
  if (typeof apiKey !== 'string' || !apiKey.trim()) {
    throw new ProviderError('config', 'OpenAI speech needs its own API key.');
  }
  if (!OPENAI.voices.includes(voice)) throw new ProviderError('config', 'Choose the Marin or Cedar speech voice.');
  rateFor(rate);
  if (!Number.isInteger(deadlineMs) || deadlineMs < 1 || deadlineMs > 120000) {
    throw new ProviderError('config', 'Speech requests need a deadline between 1 and 120000 ms.');
  }

  return {
    id: 'openai',
    async synthesize(text, { signal, rate: speed = rate } = {}) {
      if (signal?.aborted) throw aborted();
      if (typeof text !== 'string' || !text.trim() || text.length > MAX_TTS_INPUT_CHARS) {
        throw new ProviderError('config', `Speech input must contain 1 to ${MAX_TTS_INPUT_CHARS} characters.`);
      }
      rateFor(speed);
      const lifetime = new AbortController();
      const caller = signal ? AbortSignal.any([signal, lifetime.signal]) : lifetime.signal;
      const stop = deadline(caller, deadlineMs);
      try {
        stop.throwIfAborted();
        const { url, headers } = applyAuth(OPENAI.url, {
          auth: OPENAI.auth, apiKey: apiKey.trim(),
          headers: { 'content-type': 'application/json', accept: 'audio/wav' },
        });
        const res = await abortable(fetchImpl(url, {
          method: 'POST', headers, credentials: 'omit', mode: 'cors',
          cache: 'no-store', redirect: 'error', signal: stop,
          body: JSON.stringify({
            model: OPENAI.model, input: text, voice, response_format: OPENAI.responseFormat, speed,
          }),
        }), stop);
        if (!res.ok) {
          // Providers can echo credentials, including encoded or partial keys. Do not
          // retain their error body or reason phrase in UI messages, stacks or causes.
          throw await abortable(httpError({
            status: res.status, statusText: 'speech request failed', headers: res.headers,
            json: async () => ({}),
          }, { label: OPENAI.label, keyHint: 'check the speech key in Settings' }), stop);
        }
        const type = (res.headers.get('content-type') || '').split(';')[0].trim().toLowerCase();
        if (!['audio/wav', 'audio/wave', 'audio/x-wav', 'audio/vnd.wave', 'application/octet-stream'].includes(type)) {
          throw badAudio('Speech returned an unsupported audio content type.');
        }
        const audio = await readBounded(res, stop, MAX_TTS_AUDIO_BYTES);
        stop.throwIfAborted();
        const { info, sizes } = inspectSpeechAudio(audio, true);
        // Nonseekable WAV encoders leave RIFF/data sizes at UINT32_MAX. Rewrite only
        // those fields after successful EOF, full-frame alignment and all size checks.
        const header = new DataView(audio);
        for (const [offset, size] of sizes) header.setUint32(offset, size, true);
        return { audio, contentType: 'audio/wav', ...info };
      } catch (error) {
        if (signal?.aborted && (!stop.aborted || stop.reason === signal.reason)) throw aborted();
        const cancellation = abortError(stop.aborted ? stop.reason : error,
          { label: OPENAI.label, ms: deadlineMs });
        if (cancellation) throw cancellation;
        if (error instanceof ProviderError) throw error;
        throw new ProviderError('network',
          'The speech request could not be read. Check the speech key, connection and browser compatibility; '
          + 'a CORS-blocked response can hide the actual error.', { cause: error });
      } finally {
        lifetime.abort();
      }
    },
  };
}
