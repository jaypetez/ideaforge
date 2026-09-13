// Microphone capture: getUserMedia + MediaRecorder, with the silence gate driving
// hands-free auto-submit.
//
// The MediaStream is acquired once and kept alive for the life of the app rather than
// re-acquired per answer. WebKit bug 215884 re-prompts for permission on a standalone
// home-screen app more eagerly than anywhere else, and a permission dialog between every
// question would make hands-free mode unusable.

import { createSilenceGate, rmsOf, DEFAULTS } from './vad.js';

/** In preference order. Safari only recently grew webm, and still prefers mp4. */
const MIME_CANDIDATES = [
  'audio/webm;codecs=opus',
  'audio/webm',
  'audio/ogg;codecs=opus',
  'audio/mp4',
  '',                                  // let the browser pick
];

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
 * @returns {Promise<{record: Function, stop: Function, dispose: Function, mime: string}>}
 */
export async function createRecorder({ vad = {} } = {}) {
  if (!micSupported()) throw new Error('this browser cannot record audio');

  let stream;
  try {
    stream = await navigator.mediaDevices.getUserMedia({
      audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true },
    });
  } catch (err) {
    throw new Error(describeMicError(err));
  }

  const AudioCtx = window.AudioContext || window.webkitAudioContext;
  const ctx = new AudioCtx();
  const source = ctx.createMediaStreamSource(stream);
  const analyser = ctx.createAnalyser();
  analyser.fftSize = 1024;
  source.connect(analyser);
  const buf = new Uint8Array(analyser.fftSize);

  const mime = pickMime();
  let active = null;

  /**
   * Record until silence, or until stop() is called.
   *
   * @param {{onLevel?: Function, onSilence?: Function, autoStop?: boolean}} opts
   * @returns {Promise<Blob>}
   */
  function record({ onLevel, autoStop = true, ...gateOpts } = {}) {
    if (active) throw new Error('already recording');
    // Safari suspends the context when the tab backgrounds and does not resume it itself.
    if (ctx.state === 'suspended') ctx.resume();

    const rec = new MediaRecorder(stream, mime ? { mimeType: mime } : undefined);
    const chunks = [];
    const gate = createSilenceGate({ ...DEFAULTS, ...gateOpts });
    let raf = 0;
    let settled = false;

    return new Promise((resolve, reject) => {
      const finish = () => {
        if (settled) return;
        settled = true;
        cancelAnimationFrame(raf);
        active = null;
        resolve(new Blob(chunks, { type: rec.mimeType || mime || 'audio/webm' }));
      };

      rec.ondataavailable = (e) => { if (e.data && e.data.size) chunks.push(e.data); };
      rec.onstop = finish;
      rec.onerror = (e) => {
        if (settled) return;
        settled = true;
        cancelAnimationFrame(raf);
        active = null;
        reject(new Error(`recording failed: ${(e.error && e.error.name) || 'unknown'}`));
      };

      const tick = () => {
        if (settled) return;
        analyser.getByteTimeDomainData(buf);
        const rms = rmsOf(buf);
        // Push first, then report: otherwise the state handed to onLevel always lags the
        // sample by one frame and a caller can never observe the terminal `done`.
        const verdict = gate.push(rms, performance.now());
        if (onLevel) onLevel(rms, gate.state());
        if (verdict === 'done' && autoStop) { safeStop(rec); return; }
        raf = requestAnimationFrame(tick);
      };

      active = { rec, stop: () => safeStop(rec), gate };
      rec.start(250);
      raf = requestAnimationFrame(tick);
    });
  }

  return {
    mime,
    record,
    /** End the current recording early; the record() promise resolves with what was captured. */
    stop() { if (active) active.stop(); },
    recording: () => !!active,
    dispose() {
      if (active) safeStop(active.rec);
      stream.getTracks().forEach((t) => t.stop());
      ctx.close().catch(() => {});
    },
  };
}

function safeStop(rec) {
  try { if (rec.state !== 'inactive') rec.stop(); } catch { /* already stopping */ }
}

/** getUserMedia's error names are terse; the user needs to know what to actually do. */
function describeMicError(err) {
  const name = (err && err.name) || '';
  if (name === 'NotAllowedError' || name === 'SecurityError') {
    return 'Microphone access was denied. Allow it in the site settings and try again.';
  }
  if (name === 'NotFoundError' || name === 'OverconstrainedError') {
    return 'No microphone was found on this device.';
  }
  if (name === 'NotReadableError') {
    return 'The microphone is in use by another app.';
  }
  return `Could not open the microphone (${name || 'unknown error'}).`;
}
