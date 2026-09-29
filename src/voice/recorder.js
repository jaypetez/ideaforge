// Microphone capture: getUserMedia + MediaRecorder, with the silence gate driving
// hands-free auto-submit.
//
// The MediaStream is acquired once per active voice run rather than re-acquired per answer.
// Explicit Pause/Exit releases it. WebKit bug 215884 re-prompts for permission on a standalone
// home-screen app more eagerly than anywhere else, and a permission dialog between every
// question would make hands-free mode unusable.

import { createSilenceGate, DEFAULTS } from './vad.js';
import { createMicMeter } from './mic-meter.js';

/** In preference order. Safari only recently grew webm, and still prefers mp4. */
const MIME_CANDIDATES = [
  'audio/webm;codecs=opus',
  'audio/webm',
  'audio/ogg;codecs=opus',
  'audio/mp4',
  '',                                  // let the browser pick
];
const FLUSH_MS = 1000;

export function micSupported() {
  return typeof navigator !== 'undefined'
    && !!navigator.mediaDevices
    && typeof navigator.mediaDevices.getUserMedia === 'function'
    && typeof MediaRecorder !== 'undefined';
}

function pickMime() {
  for (const m of MIME_CANDIDATES) {
    if (!m) return '';
    if (MediaRecorder.isTypeSupported && MediaRecorder.isTypeSupported(m)) return m;
  }
  return '';
}

/**
 * Holds the microphone for the session. Create once, on a user gesture.
 *
 * `signal` cancels pending acquisition, including a permission grant arriving after abort.
 * @returns {Promise<{record: Function, stop: Function, abort: Function, dispose: Function,
 *                    recording: Function, mime: string}>}
 */
export async function createRecorder({ vad = {}, signal } = {}) {
  if (!micSupported()) throw new Error('this browser cannot record audio');

  const input = await createMicMeter({ signal });
  let mime;
  try {
    signal?.throwIfAborted();
    mime = pickMime();
  } catch (err) {
    await input.dispose();
    throw err;
  }
  let active = null;
  let disposed = false;

  /**
   * Record until silence, or until stop() is called.
   *
   * Metering is optional. Input loss rejects with {code: 'audio-capture', fatal: true, audio}
   * after flushing the captured Blob, so callers can retain a draft without continuing.
   * @param {{onLevel?: Function, onStart?: Function, onLevelUnavailable?: Function,
   *          autoStop?: boolean}} opts
   * @returns {Promise<Blob>}
   */
  function record({ onLevel, onStart, onLevelUnavailable, autoStop = true, ...gateOpts } = {}) {
    if (disposed) throw new Error('The microphone recorder has been disposed.');
    if (active) throw new Error('already recording');

    const cfg = { ...DEFAULTS, ...vad, ...gateOpts };
    if (!Number.isFinite(cfg.maxMs) || cfg.maxMs <= 0 || cfg.maxMs > 2147483647) {
      throw new Error('Recording maxMs must be a finite positive timer duration.');
    }
    const liveInput = () => input.stream.getAudioTracks().some((track) => track.readyState === 'live');
    const inputEnded = () => Object.assign(new Error(
      'Microphone input ended. The captured audio was retained; resume to reconnect.',
    ), { code: 'audio-capture', fatal: true });
    if (!liveInput()) throw inputEnded();
    const rec = new MediaRecorder(input.stream, mime ? { mimeType: mime } : undefined);
    const chunks = [];
    const gate = createSilenceGate(cfg);
    let settled = false;
    let stopping = false;
    let sampled = false;
    let levelUnavailable = false;
    let inputError = null;
    let limit = null;
    let health = null;
    let flushing = null;

    return new Promise((resolve, reject) => {
      const unavailable = (err) => {
        if (levelUnavailable) return;
        levelUnavailable = true;
        const reason = `Microphone levels and automatic silence detection are unavailable; `
          + `speech evidence is unknown. Recording keeps its time limit. ${err.message}`;
        if (onLevelUnavailable) onLevelUnavailable(reason);
        else console.warn(reason);
      };
      const finish = (err = null, discard = false) => {
        if (settled) return;
        settled = true;
        clearTimeout(limit);
        clearInterval(health);
        clearTimeout(flushing);
        input.stream.getAudioTracks().forEach((track) => track.removeEventListener('ended', lostInput));
        input.stop();
        active = null;
        rec.onstart = rec.ondataavailable = rec.onstop = rec.onerror = null;
        let failure = discard ? null : err || inputError;
        if (!discard && !sampled && !levelUnavailable) {
          try { unavailable(new Error('No microphone samples were available for this capture.')); }
          catch (error) { failure = error; }
        }
        const audio = new Blob(discard ? [] : chunks, { type: rec.mimeType || mime || 'audio/webm' });
        if (failure) {
          if (failure.code === 'audio-capture') failure.audio = audio;
          reject(failure);
        } else resolve(audio);
      };
      const stop = () => {
        if (settled || stopping) return;
        stopping = true;
        clearTimeout(limit);
        clearInterval(health);
        input.stop();
        flushing = setTimeout(() => {
          finish(Object.assign(new Error('The recorder did not finish flushing its captured audio.'),
            { code: 'audio-capture', fatal: true }));
        }, FLUSH_MS);
        try {
          if (rec.state !== 'inactive') rec.stop();
        } catch (err) { finish(err); }
      };
      const fail = (err) => {
        if (settled) return;
        finish(err);
        try { if (rec.state !== 'inactive') rec.stop(); } catch (stopError) {
          console.warn('Could not stop the failed microphone recording.', stopError);
        }
      };
      const lostInput = () => {
        if (settled || stopping) return;
        inputError = inputEnded();
        stop();
      };

      rec.ondataavailable = (e) => { if (!settled && e.data?.size) chunks.push(e.data); };
      rec.onstart = () => {
        if (settled || stopping) return;
        try { onStart?.(); } catch (err) { fail(err); }
      };
      rec.onstop = () => {
        if (!stopping && !liveInput()) inputError = inputEnded();
        finish();
      };
      rec.onerror = (e) => {
        if (!liveInput()) lostInput();
        else fail(new Error(`recording failed: ${e.error?.name || 'unknown'}`));
      };

      active = {
        stop,
        abort() {
          stop();
          finish(null, true);
        },
        get stopping() { return stopping; },
      };
      try {
        input.stream.getAudioTracks().forEach((track) => track.addEventListener('ended', lostInput));
        // Neither bound depends on analyser frames, which can stop while MediaRecorder runs.
        limit = setTimeout(stop, cfg.maxMs);
        health = setInterval(() => { if (!liveInput()) lostInput(); }, 100);
        rec.start(250);
        if (settled || stopping) return;
        input.start((rms) => {
          if (settled || stopping) return;
          sampled = true;
          // Report the state of THIS sample, including a terminal gate verdict.
          const verdict = gate.push(rms, performance.now());
          if (onLevel) onLevel(rms, gate.state());
          if (verdict === 'done' && autoStop) stop();
        }, (err) => {
          if (settled || stopping) return;
          try { unavailable(err); } catch (error) { fail(error); }
        });
      } catch (err) { fail(err); }
    });
  }

  return {
    mime,
    record,
    /** End the current recording early; the record() promise resolves with what was captured. */
    stop() { if (active) active.stop(); },
    /** Discard the current recording and ignore any late data/stop events. */
    abort() { if (active) active.abort(); },
    recording: () => !!active && !active.stopping,
    dispose() {
      disposed = true;
      if (active) active.stop();
      return input.dispose();
    },
  };
}
