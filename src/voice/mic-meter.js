// An app-owned microphone and analyser. Nothing is connected to the speakers.
// Keep the input across active turns; stop sampling between them, dispose on Pause/Exit.

import { rmsOf } from './vad.js';

const RESUME_MS = 1500;

/** getUserMedia cannot be cancelled: reject now, and stop any late-granted tracks. */
function acquireMicrophone(signal) {
  if (signal?.aborted) return Promise.reject(signal.reason);
  if (!globalThis.navigator?.mediaDevices?.getUserMedia) {
    return Promise.reject(new Error('This browser cannot open a microphone for level monitoring.'));
  }
  return new Promise((resolve, reject) => {
    const cancel = () => {
      signal.removeEventListener('abort', cancel);
      reject(signal.reason);
    };
    signal?.addEventListener('abort', cancel, { once: true });
    let request;
    try {
      request = navigator.mediaDevices.getUserMedia({
        audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true },
      });
    } catch (err) {
      signal?.removeEventListener('abort', cancel);
      reject(new Error(describeMicError(err), { cause: err }));
      return;
    }
    request.then((stream) => {
      signal?.removeEventListener('abort', cancel);
      if (signal?.aborted) {
        stream.getTracks().forEach((track) => track.stop());
        return;
      }
      resolve(stream);
    }, (err) => {
      signal?.removeEventListener('abort', cancel);
      reject(signal?.aborted ? signal.reason : new Error(describeMicError(err), { cause: err }));
    });
  });
}

/**
 * Acquire once. `signal` cancels acquisition; the returned owner controls its lifetime.
 * `start` reports real RMS only, or an explicit error when sampling cannot run. Meter
 * failure does not disable the stream: a recorder can still capture it without Web Audio.
 */
export async function createMicMeter({ signal } = {}) {
  const AudioCtx = globalThis.AudioContext || globalThis.webkitAudioContext;
  const stream = await acquireMicrophone(signal);
  let ctx = null;
  let source = null;
  let analyser = null;
  let buf = null;
  let active = null;
  let disposed = false;
  let closing = Promise.resolve();
  let meterError = null;

  function stopSampling() {
    const run = active;
    active = null;
    if (run) {
      cancelAnimationFrame(run.raf);
      clearTimeout(run.timer);
    }
  }

  function closeMeter() {
    stopSampling();
    for (const node of [source, analyser]) {
      try { node?.disconnect(); } catch (err) {
        console.warn('Could not disconnect the microphone analyser.', err);
      }
    }
    if (ctx && ctx.state !== 'closed') {
      try {
        closing = ctx.close().catch((err) => {
          console.warn('Could not close the microphone audio context.', err);
        });
      } catch (err) { console.warn('Could not close the microphone audio context.', err); }
    }
    source = analyser = buf = ctx = null;
    return closing;
  }

  function stop() {
    stopSampling();
    stream.getAudioTracks().forEach((track) => { track.enabled = false; });
  }

  function dispose() {
    if (disposed) return closing;
    disposed = true;
    stop();
    stream.getTracks().forEach((track) => track.stop());
    return closeMeter();
  }

  try {
    signal?.throwIfAborted();
    if (!AudioCtx) throw new Error('This browser cannot measure microphone levels.');
    ctx = new AudioCtx();
    source = ctx.createMediaStreamSource(stream);
    analyser = ctx.createAnalyser();
    analyser.fftSize = 1024;
    source.connect(analyser);
    buf = new Uint8Array(analyser.fftSize);
  } catch (err) {
    if (signal?.aborted) { await dispose(); throw err; }
    meterError = err;
    closeMeter();
  }
  stop();

  function start(onLevel, onUnavailable) {
    if (disposed) throw new Error('The microphone meter has been disposed.');
    stop();
    const run = { raf: 0, timer: null };
    active = run;
    stream.getAudioTracks().forEach((track) => { track.enabled = true; });

    const fail = (err) => {
      if (active !== run) return;
      meterError = err;
      closeMeter();
      onUnavailable(err);
    };
    if (meterError) { fail(meterError); return; }
    const tick = () => {
      if (active !== run) return;
      let rms;
      try {
        const tracks = stream.getAudioTracks();
        if (ctx.state !== 'running' || !tracks.length
          || tracks.some((track) => track.readyState !== 'live' || track.muted)) {
          throw new Error('Microphone levels are unavailable because the audio input was interrupted.');
        }
        analyser.getByteTimeDomainData(buf);
        rms = rmsOf(buf);
      } catch (err) {
        fail(err);
        return;
      }
      onLevel(rms);
      if (active === run) run.raf = requestAnimationFrame(tick);
    };
    const ready = () => {
      if (active !== run) return;
      clearTimeout(run.timer);
      if (ctx.state !== 'running') {
        fail(new Error('Microphone levels are unavailable while the audio input is suspended.'));
        return;
      }
      run.raf = requestAnimationFrame(tick);
    };
    if (ctx.state === 'running') {
      ready();
    } else {
      run.timer = setTimeout(() => fail(
        new Error('Microphone levels are unavailable while the audio input is suspended.'),
      ), RESUME_MS);
      try { ctx.resume().then(ready, fail); } catch (err) { fail(err); }
    }
  }

  return { stream, start, stop, dispose };
}

/** getUserMedia's error names are terse; the user needs to know what to actually do. */
function describeMicError(err) {
  const name = err?.name || '';
  if (name === 'NotAllowedError' || name === 'SecurityError') {
    return 'Microphone access was denied. Allow it in the site settings and try again.';
  }
  if (name === 'NotFoundError' || name === 'OverconstrainedError') {
    return 'No microphone was found on this device.';
  }
  if (name === 'NotReadableError') return 'The microphone is in use by another app.';
  return `Could not open the microphone (${name || 'unknown error'}).`;
}
