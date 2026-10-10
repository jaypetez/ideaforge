// Native output is deliberately resolved at call time: voices arrive late, and browser
// probes replace the platform objects. Importing this module must also be safe in Node.

import { speechChunks } from '../core/markdown.js';
import { ProviderError } from '../providers/errors.js';

let primedSynth = null;
let active = null;
const synthesis = () => typeof window === 'undefined' ? null : window.speechSynthesis;
const utteranceType = () => typeof window === 'undefined' ? null : window.SpeechSynthesisUtterance;
const voicesFrom = (synth) => Array.from(synth?.getVoices() || []);
const cancelled = () => new ProviderError('aborted', 'Speech cancelled.');
const result = (status, meta = {}, error) => ({
  status, backend: 'browser', ...meta, ...(error ? { error } : {}),
});

export function ttsSupported() {
  return typeof synthesis()?.speak === 'function' && typeof utteranceType() === 'function';
}

/** Call synchronously from Start/Resume/Preview. Acceptance is not a voice-quality check. */
export function primeSpeech() {
  if (!ttsSupported()) {
    return result('failed', {}, new ProviderError('config', 'Browser speech is unavailable.'));
  }
  const synth = synthesis();
  if (primedSynth === synth) return result('primed');
  try {
    const Utterance = utteranceType();
    const u = new Utterance(' ');
    u.volume = 0;
    synth.speak(u);
    primedSynth = synth;
    return result('primed');
  } catch (cause) {
    return result('failed', {}, new ProviderError('config',
      'Browser speech could not be primed. Try Preview from a user gesture.', { cause }));
  }
}

/** The actual, current SpeechSynthesisVoice objects, not a quality-ranked catalogue. */
export function getVoices() {
  return voicesFrom(synthesis());
}

/** Subscribe to later voice installations/removals. The caller owns the unsubscribe. */
export function subscribeVoices(onChange) {
  const synth = synthesis();
  const changed = () => onChange(voicesFrom(synth));
  synth?.addEventListener('voiceschanged', changed);
  return () => synth?.removeEventListener('voiceschanged', changed);
}

/** An empty list is a valid timeout result; cancellation rejects and removes the listener. */
export function voicesReady(timeoutMs = 1000, { signal } = {}) {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) return reject(cancelled());
    if (!Number.isFinite(timeoutMs) || timeoutMs < 0) {
      return reject(new ProviderError('config', 'Voice readiness needs a nonnegative timeout.'));
    }
    const synth = synthesis();
    const voices = voicesFrom(synth);
    if (voices.length || !synth) return resolve(voices);
    let timer;
    let settled = false;
    const finish = (error) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      synth.removeEventListener('voiceschanged', changed);
      signal?.removeEventListener('abort', aborted);
      if (error) reject(error);
      else {
        try { resolve(voicesFrom(synth)); }
        catch (cause) { reject(new ProviderError('bad_response', 'Browser voices could not be read.', { cause })); }
      }
    };
    const changed = () => {
      try { if (voicesFrom(synth).length) finish(); }
      catch (cause) { finish(new ProviderError('bad_response', 'Browser voices could not be read.', { cause })); }
    };
    const aborted = () => finish(cancelled());
    synth.addEventListener('voiceschanged', changed);
    signal?.addEventListener('abort', aborted, { once: true });
    timer = setTimeout(() => finish(), timeoutMs);
  });
}

export function validateSpeechRate(rate, { min = 0.1, max = 10 } = {}) {
  if (typeof rate !== 'number' || !Number.isFinite(rate) || rate < min || rate > max) {
    throw new ProviderError('config', `Speech rate must be a number from ${min} to ${max}.`);
  }
  return rate;
}

function pickVoice(voices, voiceURI, lang) {
  const selected = voiceURI ? voices.find((v) => v.voiceURI === voiceURI) : null;
  if (selected) return selected;
  const locale = (value) => String(value || '').toLowerCase().replace(/_/g, '-');
  const wanted = locale(lang);
  const exact = voices.filter((v) => locale(v.lang) === wanted);
  const sameLanguage = voices.filter((v) => locale(v.lang).split('-')[0] === wanted.split('-')[0]);
  const candidates = exact.length ? exact : sameLanguage;
  // Respect the browser's default within the locale. Name, order and localService are
  // not evidence of quality. With no matching locale, leave the browser to choose.
  return candidates.find((v) => v.default) || candidates[0] || null;
}

/**
 * Keep the existing sentence grouping, but enforce its advisory character limit even
 * for one giant sentence/token. Never split a UTF-16 surrogate pair or drop nonspace text.
 */
export function splitSpeechText(text, { maxChars = 350, maxWords = Infinity } = {}) {
  if (!Number.isInteger(maxChars) || maxChars < 2
      || (maxWords !== Infinity && (!Number.isInteger(maxWords) || maxWords < 1))) {
    throw new ProviderError('config', 'Speech chunk limits must be positive integers.');
  }
  const out = [];
  for (let rest of speechChunks(text, { maxChars })) {
    rest = rest.trim();
    while (rest) {
      let end = Math.min(maxChars, rest.length);
      if (end < rest.length && /[\uD800-\uDBFF]/.test(rest[end - 1])
          && /[\uDC00-\uDFFF]/.test(rest[end])) end--;
      const part = rest.slice(0, end);
      const words = [...part.matchAll(/\S+/g)];
      if (words.length > maxWords) end = words[maxWords].index;
      else if (end < rest.length && !/\s/.test(rest[end])) {
        const boundary = part.search(/\s+\S*$/);
        if (boundary > 0) end = boundary;
      }
      const chunk = rest.slice(0, end).trim();
      if (chunk) out.push(chunk);
      rest = rest.slice(end).trimStart();
    }
  }
  return out;
}

export function speechTimeoutMs(text, rate = 1) {
  validateSpeechRate(rate);
  const words = String(text).trim().split(/\s+/).length;
  // Character length catches languages/long tokens without spaces. Engines can clamp
  // fast rates, so only slower rates lengthen the budget; faster ones never shorten it.
  const seconds = Math.max(words / 2.6, String(text).length / 13) / Math.min(rate, 1);
  return Math.min(30000, 2000 + Math.ceil(seconds * 1000));
}

function utter(text, { synth, voice, lang, rate, signal, onStatus, meta }) {
  return new Promise((resolve) => {
    if (signal.aborted) return resolve(result('cancelled', meta, cancelled()));
    const Utterance = utteranceType();
    const u = new Utterance(text);
    let timer;
    let settled = false;
    let announced = false;
    const finish = (status, error) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      signal.removeEventListener('abort', abort);
      u.onend = u.onerror = u.onstart = null;
      if (status !== 'spoken') {
        if (status === 'failed' && primedSynth === synth) primedSynth = null;
        try { synth.cancel(); }
        catch (cause) {
          error = new ProviderError(status === 'cancelled' ? 'aborted' : 'bad_response',
            'Browser speech could not be stopped.', { cause });
        }
      }
      resolve(result(status, meta, error));
    };
    const abort = () => finish('cancelled', cancelled());
    u.lang = voice?.lang || lang;
    u.rate = rate;
    if (voice) u.voice = voice;
    const speaking = () => {
      if (settled || announced) return;
      announced = true;
      onStatus({ phase: 'speaking', backend: 'browser', text, ...meta });
    };
    u.onstart = speaking;
    u.onend = () => finish('spoken');
    u.onerror = (event) => {
      const code = event.error || 'synthesis-failed';
      const wasCancelled = code === 'interrupted' || code === 'canceled';
      finish(wasCancelled ? 'cancelled' : 'failed', wasCancelled ? cancelled()
        : new ProviderError(code === 'network' ? 'network' : 'config',
          `Browser speech failed (${code}). Try a voice preview or another voice.`));
    };
    signal.addEventListener('abort', abort, { once: true });
    timer = setTimeout(() => finish('failed',
      new ProviderError('timeout', 'Browser speech did not finish; playback was stopped.')),
    speechTimeoutMs(text, rate));
    try { synth.speak(u); }
    catch (cause) {
      finish('failed', new ProviderError('bad_response', 'Browser speech could not start.', { cause }));
    }
  });
}

/**
 * Backwards-compatible awaiting, now with an observable result instead of silent success.
 * Never rejects. `voiceFallback` identifies a vanished selection; `voiceURI` is the voice
 * actually used (empty means the browser selected its default).
 */
export async function speak(text, {
  voiceURI = '', lang = 'en-US', rate = 1.02, signal, onStatus = () => {},
} = {}) {
  if (signal?.aborted) return result('cancelled', {}, cancelled());
  let op;
  let meta = { requestedVoiceURI: voiceURI, voiceURI: '', voiceFallback: false, lang, rate };
  try {
    validateSpeechRate(rate);
    if (!ttsSupported()) throw new ProviderError('config', 'Browser speech is unavailable.');
    if (!String(text || '').trim()) throw new ProviderError('config', 'There is no text to speak.');
    if (typeof voiceURI !== 'string' || typeof lang !== 'string' || !lang.trim()) {
      throw new ProviderError('config', 'Speech needs a voice identifier and a language.');
    }
    cancelSpeech();
    op = { controller: new AbortController(), synth: synthesis() };
    active = op;
    const stop = signal ? AbortSignal.any([signal, op.controller.signal]) : op.controller.signal;
    const voices = await voicesReady(1000, { signal: stop });
    if (stop.aborted) return result('cancelled', meta, cancelled());
    const voice = pickVoice(voices, voiceURI, lang);
    meta = { ...meta, voiceURI: voice?.voiceURI || '', lang: voice?.lang || lang,
      voiceFallback: !!voiceURI && voice?.voiceURI !== voiceURI };
    onStatus({
      phase: 'preparing', backend: 'browser', text: String(text), ...meta,
      ...(meta.voiceFallback ? { message:
        'The selected browser voice is unavailable; using a voice for this language instead.' } : {}),
    });
    const maxWords = Math.max(1, Math.floor(24 * Math.min(rate, 1)));
    for (const chunk of splitSpeechText(text, { maxChars: maxWords * 5, maxWords })) {
      const spoken = await utter(chunk, {
        synth: op.synth, voice, lang, rate, signal: stop, onStatus, meta,
      });
      if (spoken.status !== 'spoken') return spoken;
      if (stop.aborted) return result('cancelled', meta, cancelled());
    }
    return result('spoken', meta);
  } catch (error) {
    if (op?.controller.signal.aborted || signal?.aborted || error.code === 'aborted') {
      return result('cancelled', meta, cancelled());
    }
    return result('failed', meta, error instanceof ProviderError ? error
      : new ProviderError('bad_response', 'Browser speech failed.', { cause: error }));
  } finally {
    if (active === op) active = null;
  }
}

export function cancelSpeech() {
  if (active) {
    active.controller.abort();
    return result('cancelled');
  }
  try { synthesis()?.cancel(); }
  catch (cause) {
    return result('cancelled', {}, new ProviderError('aborted',
      'Browser speech could not be stopped.', { cause }));
  }
  return result('cancelled');
}
