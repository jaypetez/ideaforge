// Not a .browser.mjs: only the separate fresh-profile, normal-policy CDP phase may run it.
// Microphone/recognizer fixtures are confined to their own profile, never playback evidence.

import { rmsOf } from '../../src/voice/vad.js';

const state = window.__audioPolicy = { ready: false, done: false, results: [], error: null };
const check = (name, ok, detail = '') => state.results.push({ name, ok: !!ok, detail: String(detail) });
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const errors = [];
addEventListener('error', (event) => errors.push(String(event.message)));
addEventListener('unhandledrejection', (event) => errors.push(String(event.reason)));
addEventListener('securitypolicyviolation', (event) =>
  errors.push(`${event.violatedDirective}: ${event.blockedURI}`));

function activation(event) {
  return {
    trusted: event.isTrusted,
    active: navigator.userActivation.isActive,
    everActive: navigator.userActivation.hasBeenActive,
  };
}

/** A real, unconnected AnalyserNode still runs; microphone audio never reaches destination. */
function sample(analyser) {
  const bytes = new Uint8Array(analyser.fftSize);
  analyser.getByteTimeDomainData(bytes);
  return rmsOf(bytes);
}

async function autoplay(required) {
  check('policy assertions use native Web Audio and no speech fixture',
    /\[native code\]/.test(String(AudioContext)) && !window.__FakeVoice);
  const context = new AudioContext();
  const analyser = context.createAnalyser();
  analyser.fftSize = 1024;
  analyser.connect(context.destination);
  let raf = 0;
  let frames = 0;
  let peak = 0;
  let resumed = false;
  const sources = [];
  const button = document.getElementById('enable-audio');
  let click;
  try {
    const response = await fetch('/__audio-policy.wav');
    if (!response.ok) throw new Error(`audio fixture returned HTTP ${response.status}`);
    const buffer = await context.decodeAudioData(await response.arrayBuffer());
    const data = buffer.getChannelData(0);
    check('real WAV bytes decode to a half-second non-silent buffer',
      Math.abs(buffer.duration - 0.5) < 0.001 && data.some((value) => Math.abs(value) > 0.01),
      `${buffer.length} frames at ${buffer.sampleRate}Hz`);

    function play() {
      const source = context.createBufferSource();
      source.buffer = buffer;
      source.connect(analyser);
      sources.push(source);
      const playback = { ended: false, trustedEnd: false, startTime: context.currentTime, endTime: null };
      playback.completion = new Promise((resolve) => {
        source.onended = (event) => {
          playback.ended = true;
          playback.trustedEnd = event.isTrusted;
          playback.endTime = context.currentTime;
          source.disconnect();
          resolve();
        };
      });
      source.start();
      return playback;
    }

    function tick() {
      peak = Math.max(peak, sample(analyser));
      frames++;
      raf = requestAnimationFrame(tick);
    }
    raf = requestAnimationFrame(tick);
    context.resume().then(() => { resumed = true; }).catch((error) => errors.push(String(error)));
    const beforePlayback = play();
    let synthetic;
    let trusted;
    let afterPlayback;
    const gesture = new Promise((resolve, reject) => {
      click = (event) => {
        const observed = activation(event);
        // Resume synchronously in the input handler, before any decode/network await.
        const resume = context.resume();
        if (!event.isTrusted) {
          synthetic = observed;
          resume.catch(reject);
          return;
        }
        trusted = observed;
        afterPlayback = beforePlayback.ended ? play() : beforePlayback;
        Promise.all([resume, afterPlayback.completion]).then(resolve, reject);
      };
      button.addEventListener('click', click);
    });
    await sleep(750);
    state.before = { context: context.state, resumed, time: context.currentTime, ended: beforePlayback.ended };
    check('fresh document has no user activation before input',
      !navigator.userActivation.hasBeenActive && !navigator.userActivation.isActive);
    if (required) {
      check('normal policy suspends resume and scheduled audio before a gesture',
        context.state === 'suspended' && !resumed && context.currentTime === 0 && !beforePlayback.ended,
        JSON.stringify(state.before));
    } else {
      check('headless default policy is observed, not assumed to require a gesture',
        ['running', 'suspended'].includes(context.state), JSON.stringify(state.before));
    }

    // Negative control only. The unlocking click below comes from CDP Input, never this call.
    button.click();
    await sleep(150);
    check('a DOM click is untrusted and supplies no user activation',
      synthetic && !synthetic.trusted && !synthetic.active && !synthetic.everActive,
      JSON.stringify(synthetic));
    if (required) {
      check('an untrusted DOM click cannot unlock the real audio graph',
        context.state === 'suspended' && !resumed && context.currentTime === 0 && !beforePlayback.ended);
    }
    state.ready = true;
    await gesture;
    check('CDP input delivers a trusted click with actual user activation',
      trusted?.trusted && trusted.active && trusted.everActive, JSON.stringify(trusted));
    check('the trusted gesture resumes the previously created AudioContext',
      context.state === 'running' && resumed, context.state);
    check('decoded audio reaches a native ended event without stop or a timer pretending completion',
      afterPlayback.ended && afterPlayback.trustedEnd
        && afterPlayback.endTime >= afterPlayback.startTime + buffer.duration,
      `audio clock ${afterPlayback.startTime.toFixed(3)} -> ${afterPlayback.endTime.toFixed(3)}`);
    check('the real output analyser measures non-zero audio',
      Number.isFinite(peak) && peak > 0.01 && peak <= 1, `peak RMS ${peak.toFixed(4)}`);
  } finally {
    button.removeEventListener('click', click);
    cancelAnimationFrame(raf);
    for (const source of sources) source.disconnect();
    analyser.disconnect();
    await context.close();
    const stoppedFrames = frames;
    await sleep(100);
    check('playback cleanup closes the context and stops sampling',
      context.state === 'closed' && frames === stoppedFrames, `${frames} sampled frames`);
  }
}

async function microphone() {
  const { listenViaWebSpeech, forgetVerdict } = await import('../../src/voice/webspeech.js');
  await import('./fixtures/fake-voice.js');
  const fake = window.__FakeVoice;
  fake.install(window, {
    script: [[
      { at: 100, interim: 'the real signal' },
      { at: 250, final: 'the real signal stays live' },
      { at: 450, replay: 0 },
      { at: 2400, interim: 'while metering' },
      { at: 2800, final: 'while metering is independent' },
    ]],
  });
  let context;
  let stream;
  let source;
  let analyser;
  let capture;
  let raf = 0;
  let frames = 0;
  let levels = [];
  let settled = false;
  const button = document.getElementById('enable-audio');
  let click;
  try {
    check('synthetic microphone phase starts without capture or user activation',
      !navigator.userActivation.hasBeenActive && fake.recognition.startCount === 0);
    const gesture = new Promise((resolve, reject) => {
      click = (event) => {
        const observed = activation(event);
        check('microphone setup uses trusted input', observed.trusted && observed.active, JSON.stringify(observed));
        context = new AudioContext();
        context.resume().then(resolve, reject);
      };
      button.addEventListener('click', click, { once: true });
    });
    state.ready = true;
    await gesture;
    stream = await navigator.mediaDevices.getUserMedia({
      audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true },
    });
    source = context.createMediaStreamSource(stream);
    analyser = context.createAnalyser();
    analyser.fftSize = 1024;
    check('an unconnected input analyser reports silence', sample(analyser) === 0);
    source.connect(analyser);
    // No connection to context.destination: this is a meter, never microphone monitoring.
    capture = listenViaWebSpeech({ autoStop: false });
    const answer = capture.promise.then(
      (text) => ({ text }),
      (error) => ({ error }),
    ).finally(() => { settled = true; });
    function tick() {
      levels.push(sample(analyser));
      frames++;
      raf = requestAnimationFrame(tick);
    }
    raf = requestAnimationFrame(tick);
    await sleep(1500);
    const livePeak = Math.max(...levels);
    check('real synthetic-device samples have finite, bounded, non-zero RMS',
      levels.length > 5 && levels.every((level) => Number.isFinite(level) && level >= 0 && level <= 1)
        && livePeak > 0.01,
      `${levels.length} frames; peak RMS ${livePeak.toFixed(4)}`);

    stream.getAudioTracks().forEach((track) => { track.enabled = false; });
    await sleep(300);
    levels = [];
    await sleep(300);
    check('disabled real microphone tracks produce silence, not a fabricated animation',
      levels.length > 5 && levels.every((level) => level === 0), `${levels.length} silent frames`);
    stream.getAudioTracks().forEach((track) => { track.enabled = true; });
    levels = [];
    await sleep(1500);
    check('metering recovers when the same track is enabled',
      Math.max(...levels) > 0.01 && context.state === 'running');
    check('metering did not stop or restart the strict recognizer fixture',
      !settled && fake.recognition.startCount === 1 && fake.recognition.stopCount === 0
        && fake.recognition.abortCount === 0 && fake.recognition.sessions[0].flags.continuous
        && fake.recognition.sessions[0].flags.interimResults);
    capture.stop();
    const outcome = await answer;
    if (outcome.error) throw outcome.error;
    const text = outcome.text;
    check('cumulative recognizer results remain intact while acquiring real levels',
      text === 'the real signal stays live while metering is independent', text);
  } finally {
    button.removeEventListener('click', click);
    cancelAnimationFrame(raf);
    if (capture && !settled) capture.abort();
    source?.disconnect();
    analyser?.disconnect();
    stream?.getTracks().forEach((track) => track.stop());
    if (context) await context.close();
    const stoppedFrames = frames;
    await sleep(100);
    check('meter teardown ends every owned track, closes its context, and stops frames',
      !!stream && stream.getTracks().every((track) => track.readyState === 'ended')
        && context?.state === 'closed' && frames === stoppedFrames,
      `${frames} sampled frames`);
    check('strict recognizer has no live session after teardown',
      fake.recognition.sessions.length > 0 && fake.recognition.sessions.every((session) => session.endedAt !== null));
    fake.restore();
    forgetVerdict();
  }
}

try {
  const mode = new URL(location.href).searchParams.get('mode');
  if (mode === 'microphone') await microphone();
  else if (mode === 'required' || mode === 'observe') await autoplay(mode === 'required');
  else throw new Error('unknown audio policy mode');
  check('no uncaught error, unhandled rejection, or CSP violation', errors.length === 0, errors.join('; '));
  state.done = true;
} catch (error) {
  state.error = error.stack || String(error);
}
