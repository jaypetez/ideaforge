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
   * @param {{onLevel?: Function, onStart?: Function, onSilence?: Function, autoStop?: boolean}} opts
   * @returns {Promise<Blob>}
   */
  function record({ onLevel, onStart, autoStop = true, ...gateOpts } = {}) {
    if (disposed) throw new Error('The microphone recorder has been disposed.');
    if (active) throw new Error('already recording');

    const rec = new MediaRecorder(input.stream, mime ? { mimeType: mime } : undefined);
    const chunks = [];
    const gate = createSilenceGate({ ...DEFAULTS, ...vad, ...gateOpts });
    let settled = false;
    let stopping = false;

    return new Promise((resolve, reject) => {
      const finish = (err = null, discard = false) => {
        if (settled) return;
        settled = true;
        input.stop();
        active = null;
        rec.onstart = rec.ondataavailable = rec.onstop = rec.onerror = null;
        if (err) reject(err);
        else resolve(new Blob(discard ? [] : chunks, { type: rec.mimeType || mime || 'audio/webm' }));
      };
      const stop = () => {
        if (settled || stopping) return;
        stopping = true;
        input.stop();
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

      rec.ondataavailable = (e) => { if (!settled && e.data?.size) chunks.push(e.data); };
      rec.onstart = () => {
        if (settled || stopping) return;
        try { onStart?.(); } catch (err) { fail(err); }
      };
      rec.onstop = () => finish();
      rec.onerror = (e) => fail(new Error(`recording failed: ${e.error?.name || 'unknown'}`));

      active = {
        stop,
        abort() {
          stop();
          finish(null, true);
        },
        get stopping() { return stopping; },
      };
      try {
        rec.start(250);
        if (settled || stopping) return;
        input.start((rms) => {
          if (settled || stopping) return;
          // Report the state of THIS sample, including a terminal gate verdict.
          const verdict = gate.push(rms, performance.now());
          if (onLevel) onLevel(rms, gate.state());
          if (verdict === 'done' && autoStop) stop();
        }, fail);
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
