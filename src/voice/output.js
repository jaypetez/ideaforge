// The UI owns consent and the successful speech-check gate. Construct kind='openai'
// only for that explicit check or after it succeeds; construction/prime never fetch.

import { ProviderError } from '../providers/errors.js';
import { createTTSProvider, MAX_TTS_INPUT_CHARS } from '../providers/tts.js';
import {
  speak, primeSpeech, cancelSpeech, ttsSupported, splitSpeechText, validateSpeechRate,
} from './speak.js';
import { createAudioPlayback } from './playback.js';

const browserSpeech = { speak, prime: primeSpeech, cancel: cancelSpeech, supported: ttsSupported };
const cancelled = () => new ProviderError('aborted', 'Speech cancelled.');

/**
 * `speak` always resolves {status:'spoken'|'cancelled'|'failed', backend, error?}.
 * `check` uses the selected backend, never fallback, and rejects any non-spoken result.
 * `prime` is called synchronously in the user gesture, before awaiting other work; its
 * promise resolves {status:'primed'|'cancelled'|'failed', backend, error?}.
 *
 * Test seams: native, fetch, deadline, deadlineMs, createAudioContext, primeMs, decodeMs.
 */
export function createSpeechOutput({
  kind = 'browser', apiKey = '', voice = 'marin', browserVoice = '', rate = 1, lang = 'en-US',
  onStatus = () => {}, native = browserSpeech,
  fetch, deadline, deadlineMs, createAudioContext, primeMs, decodeMs,
} = {}) {
  if (!['browser', 'openai'].includes(kind)) throw new ProviderError('config', 'Unknown speech output.');
  validateSpeechRate(rate, kind === 'openai' ? { min: 0.25, max: 4 } : {});
  const audio = kind === 'openai' ? createAudioPlayback({ createAudioContext, primeMs, decodeMs }) : null;
  let provider = null;
  let active = null;
  let disposed = false;
  let downgraded = false;
  let voiceNotice = false;
  let generation = 0;
  const backend = () => kind === 'openai' && !downgraded ? 'openai' : 'browser';
  const report = (phase, engine, text, message) =>
    onStatus({ phase, backend: engine, text, ...(message ? { message } : {}) });

  function prime() {
    const at = generation;
    const engine = backend();
    if (disposed) return Promise.resolve({ status: 'failed', backend: engine,
      error: new ProviderError('config', 'Speech output has been disposed.') });
    let pending;
    try {
      const nativeReady = Promise.resolve(native.prime()).then((value) => {
        if (value?.status === 'failed') throw value.error;
      });
      // Prime both in the gesture, including for a new explicit check after degradation.
      // An unused engine's priming failure must not disable the engine actually selected.
      const hostedReady = audio?.prime();
      nativeReady.catch(() => {});
      hostedReady?.catch(() => {});
      pending = engine === 'openai' ? hostedReady : nativeReady;
    } catch (error) { pending = Promise.reject(error); }
    return Promise.resolve(pending).then(() => ({
      status: disposed || at !== generation ? 'cancelled' : 'primed', backend: engine,
    }), (cause) => {
      const error = cause instanceof ProviderError ? cause
        : new ProviderError('config', 'Speech could not be primed.', { cause });
      const stopped = disposed || at !== generation || error.code === 'aborted';
      if (!stopped) report('error', engine, '', error.message);
      return { status: stopped ? 'cancelled' : 'failed', backend: engine, error };
    });
  }

  function cancel() {
    generation++;
    const op = active;
    if (!op) return;
    op.controller.abort();
    audio?.cancel();
    if (op.backend === 'browser') native.cancel();
  }

  async function run(text, options, checking) {
    const selected = checking ? kind : backend();
    if (disposed) return { status: 'failed', backend: selected,
      error: new ProviderError('config', 'Speech output has been disposed.') };
    if (options.signal?.aborted) return { status: 'cancelled', backend: selected, error: cancelled() };
    if (active) cancel();
    const op = { controller: new AbortController(), backend: selected };
    active = op;
    const signal = options.signal ? AbortSignal.any([options.signal, op.controller.signal]) : op.controller.signal;
    const stopped = () => disposed || active !== op || signal.aborted;
    const guard = () => { if (stopped()) throw cancelled(); };
    const emit = (phase, message) => { if (!stopped()) report(phase, op.backend, text, message); };
    let metadata = {};
    let terminal = 'failed';
    try {
      const speed = options.rate === undefined ? rate : options.rate;
      const language = options.lang === undefined ? lang : options.lang;
      const voiceURI = options.voiceURI === undefined ? browserVoice : options.voiceURI;
      validateSpeechRate(speed, selected === 'openai' ? { min: 0.25, max: 4 } : {});
      if (typeof text !== 'string' || !text.trim()) {
        throw new ProviderError('config', 'There is no text to speak.');
      }
      if (typeof language !== 'string' || !language.trim() || typeof voiceURI !== 'string') {
        throw new ProviderError('config', 'Speech needs a voice identifier and a language.');
      }
      const chunks = splitSpeechText(text, { maxChars: Math.min(350, MAX_TTS_INPUT_CHARS) });
      for (const chunk of chunks) {
        guard();
        emit('preparing');
        guard();
        if (op.backend === 'openai') {
          try {
            await audio.ready({ signal });
            guard();
            provider ||= createTTSProvider({ apiKey, voice, rate, fetch, deadline, deadlineMs });
            const response = await provider.synthesize(chunk, { signal, rate: speed });
            guard();
            await audio.play(response.audio, { signal, onStart: () => emit('speaking') });
            guard();
            continue;
          } catch (error) {
            if (stopped() || error.code === 'aborted') throw cancelled();
            if (checking) throw error;
            audio.cancel();
            downgraded = true;
            emit('error', `${error.message} Using browser speech for this session.`);
            guard();
            op.backend = 'browser';
          }
        }
        const spoken = await native.speak(chunk, {
          voiceURI, rate: speed, lang: language, signal,
          onStatus(status) {
            if (stopped()) return;
            let message = status.message;
            if (status.voiceFallback) {
              if (voiceNotice) message = undefined;
              voiceNotice = true;
            }
            if (status.phase === 'preparing' || status.phase === 'speaking') {
              emit(status.phase, message);
            }
          },
        });
        guard();
        if (spoken?.status === 'cancelled') throw cancelled();
        if (spoken?.status !== 'spoken') {
          throw spoken?.error || new ProviderError('bad_response', 'Browser speech did not finish.');
        }
        metadata = { voiceURI: spoken.voiceURI, requestedVoiceURI: spoken.requestedVoiceURI,
          voiceFallback: spoken.voiceFallback, lang: spoken.lang, rate: spoken.rate };
      }
      terminal = 'spoken';
      return { status: terminal, backend: op.backend, ...metadata };
    } catch (cause) {
      const error = stopped() || cause.code === 'aborted' ? cancelled()
        : cause instanceof ProviderError ? cause
        : new ProviderError('bad_response', 'Speech output failed.', { cause });
      if (error.code !== 'aborted') emit('error', error.message);
      terminal = error.code === 'aborted' ? 'cancelled' : 'failed';
      return { status: terminal, backend: op.backend, error };
    } finally {
      if (active === op) {
        active = null;
        if (!disposed && terminal !== 'failed') report('idle', op.backend, text);
      }
    }
  }

  return {
    prime,
    speak: (text, options = {}) => run(text, options, false),
    async check(text, options = {}) {
      const spoken = await run(text, options, true);
      if (spoken.status !== 'spoken') throw spoken.error;
      return spoken;
    },
    cancel,
    async dispose() {
      if (disposed) return { status: 'disposed', backend: backend() };
      cancel();
      disposed = true;
      try {
        await audio?.dispose();
        return { status: 'disposed', backend: backend() };
      } catch (error) {
        return { status: 'failed', backend: backend(), error };
      }
    },
    supported: () => !disposed && (backend() === 'browser' ? native.supported() : audio.supported()),
  };
}
