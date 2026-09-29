// One gesture-primed Web Audio context, one buffered utterance at a time. No media
// elements, object URLs, persistent cache, or microphone connection to the destination.

import { ProviderError } from '../providers/errors.js';
import { MAX_TTS_AUDIO_SECONDS, validateSpeechAudio } from '../providers/tts.js';

const contextType = () => globalThis.AudioContext || globalThis.webkitAudioContext;
const cancelled = () => new ProviderError('aborted', 'Speech cancelled.');

function bounded(promise, { signal, ms, message }) {
  return new Promise((resolve, reject) => {
    let timer;
    const cleanup = () => {
      clearTimeout(timer);
      signal?.removeEventListener('abort', abort);
    };
    const abort = () => { cleanup(); reject(cancelled()); };
    Promise.resolve(promise).then((value) => {
      cleanup();
      if (signal?.aborted) reject(cancelled());
      else resolve(value);
    }, (error) => { cleanup(); reject(error); });
    if (signal?.aborted) { abort(); return; }
    signal?.addEventListener('abort', abort, { once: true });
    timer = setTimeout(() => {
      cleanup();
      reject(new ProviderError('timeout', message));
    }, ms);
  });
}

export function createAudioPlayback({ createAudioContext, primeMs = 2500, decodeMs = 10000 } = {}) {
  if (![primeMs, decodeMs].every((ms) => Number.isInteger(ms) && ms > 0 && ms <= 120000)) {
    throw new ProviderError('config', 'Speech priming and decoding need finite, bounded deadlines.');
  }
  let context = null;
  let priming = null;
  let primeAbort = null;
  let active = null;
  let disposed = false;
  const supported = () => !disposed && (!!createAudioContext || typeof contextType() === 'function');
  const requireLive = () => {
    if (disposed) throw new ProviderError('config', 'Speech output has been disposed.');
    if (!supported()) throw new ProviderError('config', 'Web Audio playback is unavailable.');
  };

  // Not async: constructing/resuming the context must happen before the gesture handler
  // yields, even if its caller does not await the returned readiness promise.
  function prime() {
    try {
      requireLive();
      if (!context || context.state === 'closed') {
        const Context = contextType();
        context = createAudioContext ? createAudioContext() : new Context();
      }
      primeAbort?.abort();
      primeAbort = new AbortController();
      const ctx = context;
      priming = bounded(ctx.resume(), {
        signal: primeAbort.signal, ms: primeMs,
        message: 'Speech playback is blocked. Use Start, Resume or Preview from a user gesture.',
      }).then(() => {
        if (disposed) throw cancelled();
        if (ctx.state !== 'running') throw new ProviderError('config',
          'Speech playback is not running. Use a speech preview to check this browser.');
      }, (cause) => {
        if (cause instanceof ProviderError) throw cause;
        throw new ProviderError('config',
          'Speech playback was blocked. Use Start, Resume or Preview from a user gesture.', { cause });
      });
    } catch (cause) {
      priming = Promise.reject(cause instanceof ProviderError ? cause
        : new ProviderError('config', 'Speech playback could not be primed.', { cause }));
    }
    return priming;
  }

  async function ready({ signal } = {}) {
    requireLive();
    if (!priming) throw new ProviderError('config',
      'Speech playback must be primed by Start, Resume or Preview before requesting audio.');
    await bounded(priming, { signal, ms: primeMs, message: 'Speech playback did not become ready.' });
    if (context?.state !== 'running') throw new ProviderError('config',
      'Speech playback was suspended. Tap Resume or try Preview again.');
  }

  async function play(audio, { signal, onStart = () => {} } = {}) {
    if (signal?.aborted) throw cancelled();
    requireLive();
    cancel();
    const op = { controller: new AbortController() };
    active = op;
    const stop = signal ? AbortSignal.any([signal, op.controller.signal]) : op.controller.signal;
    try {
      await ready({ signal: stop });
      const info = validateSpeechAudio(audio);
      const ctx = context;
      let buffer;
      try {
        buffer = await bounded(ctx.decodeAudioData(audio), {
          signal: stop, ms: decodeMs, message: 'Speech audio decoding did not finish.',
        });
      } catch (cause) {
        if (cause instanceof ProviderError) throw cause;
        throw new ProviderError('bad_response', 'This browser could not decode the speech audio.', { cause });
      }
      if (stop.aborted || disposed || active !== op) throw cancelled();
      if (!Number.isFinite(buffer?.duration) || buffer.duration <= 0
          || buffer.duration > MAX_TTS_AUDIO_SECONDS || !(buffer.length > 0)
          || buffer.numberOfChannels !== info.channels
          || Math.abs(buffer.duration - info.duration) > 0.05) {
        throw new ProviderError('bad_response', 'Speech decoded to empty, truncated or invalid audio.');
      }
      if (ctx.state !== 'running') throw new ProviderError('config',
        'Speech playback was suspended before the audio could start.');

      await new Promise((resolve, reject) => {
        const source = ctx.createBufferSource();
        let timer;
        let started = false;
        let settled = false;
        const finish = (error) => {
          if (settled) return;
          settled = true;
          clearTimeout(timer);
          stop.removeEventListener('abort', abort);
          ctx.removeEventListener('statechange', changed);
          source.onended = null;
          let cleanupError;
          try { if (error && started) source.stop(); }
          catch (cause) { cleanupError = new ProviderError('bad_response', 'Speech could not be stopped.', { cause }); }
          try { source.disconnect(); }
          catch (cause) { cleanupError ||= new ProviderError('bad_response', 'Speech could not be disconnected.', { cause }); }
          if (error || cleanupError) reject(error || cleanupError);
          else resolve();
        };
        const abort = () => finish(cancelled());
        const changed = () => {
          if (ctx.state !== 'running') finish(new ProviderError('config',
            'Speech playback was interrupted. Tap Resume or use the manual interview.'));
        };
        source.onended = () => finish();
        stop.addEventListener('abort', abort, { once: true });
        ctx.addEventListener('statechange', changed);
        if (stop.aborted) { abort(); return; }
        timer = setTimeout(() => finish(new ProviderError('timeout',
          'Speech playback did not finish; the audio was stopped.')),
        Math.ceil(buffer.duration * 1000) + 2000);
        try {
          source.buffer = buffer;
          source.connect(ctx.destination);
          started = true;
          source.start();
          if (!settled) onStart();
        } catch (cause) {
          finish(new ProviderError('config', 'Speech audio could not start playing.', { cause }));
        }
      });
    } finally {
      if (active === op) active = null;
    }
  }

  function cancel() { active?.controller.abort(); }

  function dispose() {
    if (disposed) return Promise.resolve();
    disposed = true;
    cancel();
    primeAbort?.abort();
    const ctx = context;
    context = null;
    if (!ctx || ctx.state === 'closed') return Promise.resolve();
    try {
      return bounded(ctx.close(), { ms: primeMs, message: 'The speech audio context did not close.' });
    } catch (cause) {
      return Promise.reject(new ProviderError('config', 'The speech audio context could not close.', { cause }));
    }
  }

  return { prime, ready, play, cancel, dispose, supported };
}
