// Injected before index.html. Only recognition, inference and the exact speech HTTP
// response are scripted. AudioContext construction, decoding, rendering and events stay native.
(() => {
  'use strict';
  const { wav } = window.__appAudioPolicyConfig;
  delete window.__appAudioPolicyConfig;
  const bytes = Uint8Array.from(atob(wav), (char) => char.charCodeAt(0));
  const synth = window.speechSynthesis;
  const Context = window.AudioContext;
  const events = [];
  const handlers = [];
  const contexts = [];
  const resumes = [];
  const decodes = [];
  const sources = [];
  const requests = [];
  const streams = [];
  const errors = [];
  let activeHandler = null;
  let capture = null;
  let inferenceCalls = 0;
  const stamp = (kind) => {
    const entry = { kind, order: events.length, at: performance.now() };
    events.push(entry);
    return entry;
  };
  const trackContext = (context) => {
    let record = contexts.find((entry) => entry.context === context);
    if (!record) {
      record = { id: contexts.length, context, output: false };
      contexts.push(record);
    }
    return record;
  };

  // Reuse only the strict recognizer constructor. Never install the fixture's synthesizer
  // on the actual window, even during setup.
  const recognitionOnly = {};
  const fake = window.__FakeVoice.install(recognitionOnly, { script: [] });
  class Recognition extends recognitionOnly.SpeechRecognition {
    start() {
      if (this.continuous) capture = this;
      return super.start();
    }
  }
  window.SpeechRecognition = window.webkitSpeechRecognition = Recognition;
  window.claude = {
    async use() {
      stamp('inference-setup');
      return async () => {
        inferenceCalls++;
        return { modelTierApplied: 'policy-fixture', text: JSON.stringify({
          question: 'Which workshop attendee would try the notebook first?',
          move: 'concretize', chips: [], facts: [],
          coverage: { outcome: { level: 'partial', gap: 'needs detail' } },
        }) };
      };
    },
  };

  const nativeFetch = window.fetch;
  window.fetch = function (input, options = {}) {
    const url = new URL(typeof input === 'string' || input instanceof URL ? input : input.url, location.href);
    if (url.href === 'https://api.openai.com/v1/audio/speech' && options.method === 'POST') {
      const body = JSON.parse(options.body);
      requests.push({ ...stamp('speech-request'), input: body.input, model: body.model,
        format: body.response_format, consent: document.getElementById('tts-consent').checked });
      return Promise.resolve(new Response(bytes.slice(), {
        headers: { 'content-type': 'audio/wav', 'content-length': String(bytes.length) },
      }));
    }
    if (url.origin !== location.origin) {
      const error = new Error(`unexpected network request: ${url.origin}${url.pathname}`);
      errors.push(error.message);
      return Promise.reject(error);
    }
    return nativeFetch.call(this, input, options);
  };

  const getUserMedia = navigator.mediaDevices.getUserMedia;
  navigator.mediaDevices.getUserMedia = function (...args) {
    stamp('microphone-request');
    return getUserMedia.apply(this, args).then((stream) => { streams.push(stream); return stream; });
  };
  const resume = Context.prototype.resume;
  Context.prototype.resume = function (...args) {
    const context = trackContext(this);
    const stack = new Error().stack;
    context.output ||= stack.includes('/src/voice/playback.js');
    resumes.push({ ...stamp('resume'), context: context.id, state: this.state,
      handler: activeHandler?.id ?? null, trusted: activeHandler?.trusted === true,
      active: navigator.userActivation.isActive, stack });
    return resume.apply(this, args);
  };
  const close = Context.prototype.close;
  Context.prototype.close = function (...args) {
    trackContext(this);
    stamp('context-close');
    return close.apply(this, args);
  };
  const decode = Context.prototype.decodeAudioData;
  Context.prototype.decodeAudioData = function (...args) {
    const context = trackContext(this);
    const record = { ...stamp('decode'), context: context.id, stack: new Error().stack };
    decodes.push(record);
    const pending = decode.apply(this, args);
    pending.then((buffer) => {
      record.duration = buffer.duration;
      record.nonSilent = buffer.getChannelData(0).some((value) => Math.abs(value) > 0.01);
    }, (error) => { errors.push(`native decode: ${error.message}`); });
    return pending;
  };
  const createSource = Context.prototype.createBufferSource;
  Context.prototype.createBufferSource = function (...args) {
    const context = trackContext(this);
    const source = createSource.apply(this, args);
    const record = { context: context.id, started: false, ended: false, stops: 0, disconnected: false };
    sources.push(record);
    const connect = source.connect;
    source.connect = function (...targets) {
      record.destination = targets[0] === context.context.destination;
      return connect.apply(this, targets);
    };
    const start = source.start;
    source.start = function (...when) {
      Object.assign(record, stamp('source-start'), { started: true,
        duration: this.buffer.duration, startClock: context.context.currentTime, stack: new Error().stack });
      return start.apply(this, when);
    };
    const stop = source.stop;
    source.stop = function (...when) { record.stops++; return stop.apply(this, when); };
    const disconnect = source.disconnect;
    source.disconnect = function (...targets) {
      record.disconnected = true;
      return disconnect.apply(this, targets);
    };
    source.addEventListener('ended', (event) => {
      record.ended = true;
      record.trustedEnd = event.isTrusted;
      record.endClock = context.context.currentTime;
      record.endedAt = performance.now();
    }, { once: true });
    return source;
  };
  const createInput = Context.prototype.createMediaStreamSource;
  Context.prototype.createMediaStreamSource = function (...args) {
    trackContext(this);
    return createInput.apply(this, args);
  };
  addEventListener('error', (event) => errors.push(event.message));
  addEventListener('unhandledrejection', (event) => errors.push(String(event.reason?.stack || event.reason)));
  addEventListener('securitypolicyviolation', (event) =>
    errors.push(`${event.violatedDirective}: ${event.blockedURI}`));

  window.__appAudioPolicy = {
    arm() {
      for (const id of ['b-speech-preview', 'b-start-voice', 'b-voice-pause', 'b-voice-exit']) {
        const button = document.getElementById(id);
        const handler = button.onclick;
        if (typeof handler !== 'function') throw new Error(`missing app handler: ${id}`);
        button.onclick = function (event) {
          const record = { ...stamp('handler-enter'), id: handlers.length, control: id,
            label: this.textContent.trim(), trusted: event.isTrusted,
            active: navigator.userActivation.isActive };
          handlers.push(record);
          activeHandler = record;
          try { return handler.call(this, event); }
          finally {
            record.returned = stamp('handler-return').order;
            activeHandler = null;
          }
        };
      }
    },
    answer() {
      if (!capture?._live) throw new Error('there is no scripted answer capture');
      capture._step({ final: 'A quiet notebook for remembering workshop names over', confidence: 0.9 });
    },
    snapshot() {
      return {
        handlers, resumes, decodes, sources, requests, events, errors, inferenceCalls,
        nativeContext: window.AudioContext === Context,
        nativeSynthesis: window.speechSynthesis === synth,
        contexts: contexts.map(({ id, context, output }) => ({
          id, output, state: context.state, native: context instanceof Context,
        })),
        tracks: streams.flatMap((stream) => stream.getTracks().map((track) => track.readyState)),
        captures: fake.recognition.sessions.filter((session) => session.flags.continuous),
        activation: navigator.userActivation.hasBeenActive,
        phase: document.getElementById('voice-stage')?.dataset.phase,
        preview: document.getElementById('speech-check')?.textContent,
        backend: document.getElementById('voice-backend')?.textContent,
        pauseDisabled: document.getElementById('b-voice-pause')?.disabled,
        stageHidden: document.getElementById('voice-stage')?.hidden,
        manualVisible: !!document.getElementById('manual-interview')?.getClientRects().length,
        level: Number.parseFloat(getComputedStyle(document.getElementById('voice-signal'))
          .getPropertyValue('--voice-level')),
      };
    },
  };
})();
