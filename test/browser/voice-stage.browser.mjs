import { DIMENSION_IDS } from '../../src/core/dimensions.js';
import { DRIVING } from '../../src/core/driving.js';
import { askQuestion, createSession, isLowConfidence, setDraftAnswer } from '../../src/core/session.js';
import { seedTurn, submitAnswer } from '../../src/runtime/turn.js';
import { createSilenceGate, rmsOf } from '../../src/voice/vad.js';
import { deleteSession, listSessions, loadSession, saveSession } from '../../src/store/sessions.js';
import { loadPrefs } from '../../src/store/prefs.js';
import {
  clearCredentials, emptyKeyring, loadCredentials, saveCredentials, withCreds, withTts,
} from '../../src/store/secrets.js';

const PARTIAL = 'A guide explaining how to pause voice reminders and exit voice menus without losing my place';
const TYPED = 'Keep this typed idea about a quiet notebook for remembering conference names.';
const NEXT_QUESTION = 'Which conference attendee would try the notebook first?';
const PHASES = new Set([
  'ready', 'starting', 'speaking', 'listening', 'transcribing', 'thinking',
  'paused', 'recovering', 'blocked', 'complete',
]);

async function within(promise, describe, ms = 5000) {
  let timer;
  try {
    return await Promise.race([
      promise,
      new Promise((_, reject) => {
        timer = setTimeout(() => reject(new Error(
          `Timed out after ${ms}ms: ${typeof describe === 'function' ? describe() : describe}`,
        )), ms);
      }),
    ]);
  } finally {
    clearTimeout(timer);
  }
}

async function until(predicate, ms = 5000) {
  const deadline = performance.now() + ms;
  do {
    const value = await predicate();
    if (value) return value;
    await new Promise((resolve) => setTimeout(resolve, 20));
  } while (performance.now() < deadline);
  return null;
}

async function remains(predicate, ms = 240) {
  const deadline = performance.now() + ms;
  do {
    if (!(await predicate())) return false;
    await new Promise((resolve) => setTimeout(resolve, 20));
  } while (performance.now() < deadline);
  return true;
}

async function cleanStorage() {
  let phase = 'listing service workers';
  await within((async () => {
    for (const registration of await navigator.serviceWorker.getRegistrations()) {
      phase = `unregistering service worker ${registration.scope}`;
      await registration.unregister();
    }
    phase = 'listing caches';
    for (const key of await caches.keys()) {
      phase = `deleting cache ${key}`;
      await caches.delete(key);
    }
    phase = 'listing IndexedDB sessions';
    for (const session of await listSessions()) {
      phase = `deleting IndexedDB session ${session.id}`;
      await deleteSession(session.id);
    }
    phase = 'clearing encrypted credentials';
    await clearCredentials();
    phase = 'clearing local preferences';
    localStorage.clear();
  })(), () => `isolated storage cleanup: ${phase}`);
}

function loadFrame() {
  return new Promise((resolve, reject) => {
    const frame = document.createElement('iframe');
    frame.width = 390;
    frame.height = 780;
    // A visible frame keeps the real analyser's animation frames from being throttled.
    frame.style.cssText = 'position:fixed;left:0;top:0;border:0;z-index:1';
    frame.onload = () => resolve(frame);
    frame.onerror = () => reject(new Error('voice-stage iframe failed to load'));
    frame.src = '/index.html';
    document.body.append(frame);
  });
}

function visible(element) {
  return !!element && element.getClientRects().length > 0
    && element.ownerDocument.defaultView.getComputedStyle(element).visibility !== 'hidden';
}

function turnResult(question = NEXT_QUESTION) {
  return {
    question, move: 'concretize', chips: [], facts: [],
    coverage: Object.fromEntries(DIMENSION_IDS.map((id) =>
      [id, { level: 'partial', gap: 'needs detail' }])),
  };
}

function modelFixture() {
  const calls = [];
  return {
    calls,
    claude: {
      use: async () => (prompt, { modelTier } = {}) => new Promise((resolve) => {
        const call = {
          prompt, tier: modelTier, released: false,
          release(json = modelTier === 'complex'
            ? { title: 'Conference name notebook', prompt: 'Build a conference name notebook.',
              assumptions: [], open_questions: [] }
            : turnResult()) {
            if (call.released) return;
            call.released = true;
            resolve({
              text: JSON.stringify(json),
              modelTierApplied: 'scripted-voice-stage',
            });
          },
        };
        calls.push(call);
      }),
    },
    releaseAll() { for (const call of calls) call.release(); },
  };
}

function instrumentMic(win, { muted = false } = {}) {
  const media = win.navigator.mediaDevices;
  const getUserMedia = media.getUserMedia;
  const streams = [];
  let disposed = false;
  let requests = 0;
  let nextHold = null;
  const holds = [];
  media.getUserMedia = async function (...args) {
    requests++;
    const hold = nextHold;
    nextHold = null;
    if (hold) {
      hold.requested = true;
      await hold.promise;
    }
    if (disposed) throw new win.DOMException('Microphone fixture disposed.', 'AbortError');
    const stream = await getUserMedia.apply(this, args);
    for (const track of stream.getAudioTracks()) track.enabled = !muted;
    streams.push(stream);
    if (disposed) for (const track of stream.getTracks()) track.stop();
    return stream;
  };
  const tracks = () => streams.flatMap((stream) => stream.getTracks());
  return {
    streams, tracks,
    get requests() { return requests; },
    allStopped: () => tracks().every((track) => track.readyState === 'ended'),
    holdNext() {
      let resolve;
      const promise = new Promise((done) => { resolve = done; });
      const hold = {
        promise, requested: false, released: false,
        release() { hold.released = true; resolve(); },
      };
      holds.push(hold);
      nextHold = hold;
      return hold;
    },
    releaseHeld() { for (const hold of holds) hold.release(); },
    mute(on) {
      muted = on;
      for (const track of tracks()) track.enabled = !on;
    },
    dispose() {
      disposed = true;
      for (const hold of holds) hold.release();
      for (const track of tracks()) track.stop();
      media.getUserMedia = getUserMedia;
    },
  };
}

// All results still originate in the shared strict fixture. Only delivery/endpoint
// completion is held, to reproduce events already queued when Pause was pressed.
function instrumentRecognition(win) {
  const Base = win.SpeechRecognition;
  const captures = [];
  const releases = [];
  class ObservedRecognition extends Base {
    start() {
      super.start();
      if (this.continuous) captures.push(this);
    }
  }
  win.SpeechRecognition = ObservedRecognition;
  win.webkitSpeechRecognition = ObservedRecognition;
  return {
    captures,
    current: () => captures.findLast((recognizer) => recognizer._live),
    queueResult(recognizer, step) {
      const handler = recognizer.onresult;
      let deliver;
      recognizer.onresult = (event) => {
        const snapshot = { resultIndex: event.resultIndex, results: event.results.slice() };
        deliver = () => handler(snapshot);
      };
      try { recognizer._step(step); } finally { recognizer.onresult = handler; }
      if (!deliver) throw new Error('the strict recognizer did not produce a queued result');
      return deliver;
    },
    holdEnd(recognizer) {
      const end = recognizer._end;
      const hold = {
        pending: false,
        release() {
          recognizer._end = end;
          if (hold.pending) end.call(recognizer);
          hold.pending = false;
        },
      };
      recognizer._end = () => { hold.pending = true; };
      releases.push(hold.release);
      return hold;
    },
    releaseHeld() {
      for (const release of releases.splice(0)) release();
    },
    dispose() {
      // A live app restarts onend and appends to captures. Forced cleanup must neither
      // invoke that recovery path nor iterate an array it can grow synchronously.
      const owned = [...new Set(captures)];
      for (const recognizer of owned) {
        recognizer.onstart = recognizer.onaudiostart = recognizer.onresult = null;
        recognizer.onerror = recognizer.onend = null;
      }
      for (const release of releases.splice(0)) release();
      for (const recognizer of owned) recognizer.abort();
    },
  };
}

function deferredWakeLocks(win) {
  const original = Object.getOwnPropertyDescriptor(win.navigator, 'wakeLock');
  const requests = [];
  Object.defineProperty(win.navigator, 'wakeLock', {
    configurable: true,
    value: {
      request(type) {
        if (type !== 'screen') throw new Error(`unexpected wake-lock type: ${type}`);
        let resolve;
        let released = false;
        const promise = new Promise((done) => { resolve = done; });
        const lock = new win.EventTarget();
        const entry = {
          lock, resolved: false, adopted: false,
          complete() { entry.resolved = true; resolve(lock); },
        };
        Object.defineProperty(lock, 'released', { get: () => released });
        lock.release = async () => {
          if (released) return;
          released = true;
          lock.dispatchEvent(new win.Event('release'));
        };
        const addListener = lock.addEventListener;
        lock.addEventListener = function (name, listener, options) {
          if (name === 'release') entry.adopted = true;
          return addListener.call(this, name, listener, options);
        };
        requests.push(entry);
        return promise;
      },
    },
  });
  return {
    requests,
    releasePending() { for (const entry of [...requests]) entry.complete(); },
    dispose() {
      for (const entry of [...requests]) {
        entry.complete();
        entry.lock.release();
      }
      if (original) Object.defineProperty(win.navigator, 'wakeLock', original);
      else delete win.navigator.wakeLock;
    },
  };
}

function phase(app) { return app.$('voice-stage').dataset.phase; }
function level(app) {
  return Number.parseFloat(app.win.getComputedStyle(app.$('voice-signal'))
    .getPropertyValue('--voice-level'));
}
function captureCount(app) {
  return app.fake.recognition.sessions.filter((session) => session.flags.continuous).length;
}
function liveCaptures(app) {
  return app.fake.recognition.sessions.filter((session) =>
    session.flags.continuous && session.endedAt === null);
}
function spoken(app) { return app.fake.synthesis.spoken.filter((utterance) => utterance.text.trim()); }

function workerState(worker) {
  return worker ? { scriptURL: worker.scriptURL, state: worker.state } : null;
}

async function inspectRegistration(app) {
  const workers = app.win.navigator.serviceWorker;
  if (!workers) {
    app.registration = { status: 'unsupported' };
    return null;
  }
  app.registration = { status: 'pending' };
  try {
    const registration = await within(workers.getRegistration(),
      'service-worker registration diagnostic', 1000);
    app.registration = {
      status: registration ? 'found' : 'absent',
      scope: registration?.scope || null,
      installing: workerState(registration?.installing),
      waiting: workerState(registration?.waiting),
      active: workerState(registration?.active),
    };
    return registration;
  } catch (error) {
    app.registration = { status: 'lookup-failed', error: String(error?.message || error) };
    return null;
  }
}

function bootDetails(app) {
  return {
    version: app.$('version').textContent,
    provider: {
      value: app.$('provider').value,
      options: [...app.$('provider').options].map((option) => option.value),
    },
    readyState: app.doc.readyState,
    setupVisible: visible(app.$('panel-setup')),
    controller: workerState(app.win.navigator.serviceWorker?.controller),
    registration: app.registration || { status: 'not-sampled' },
  };
}

function detail(app) {
  return JSON.stringify({
    boot: bootDetails(app),
    phase: phase(app), status: app.$('voice-status').textContent,
    caption: app.$('voice-question').textContent,
    transcript: app.$('voice-transcript').textContent,
    answer: app.$('answer').value, pauseDisabled: app.$('b-voice-pause').disabled,
    detail: app.$('voice-detail').textContent, backend: app.$('voice-backend').textContent,
    error: app.$('err').textContent, level: level(app),
    captures: captureCount(app), live: liveCaptures(app).length,
    tracks: app.mic.tracks().map((track) => track.readyState),
    micRequests: app.mic.requests,
    dictation: app.$('stt').value,
    hostedSpeechAllowed: loadPrefs().hostedSpeechAllowed,
    wakeLocks: app.wakeLocks?.requests.map((entry) => ({
      resolved: entry.resolved, adopted: entry.adopted, released: entry.lock.released,
    })),
    spoken: spoken(app).map((utterance) => utterance.text),
    modelCalls: app.model.calls.length, modelTiers: app.model.calls.map((call) => call.tier),
    doneVisible: visible(app.$('panel-done')), doneTitle: app.$('done-title').textContent,
    exportLength: (app.$('output').dataset.source || '').length,
    fetches: app.requests, errors: app.errors,
  });
}

async function expect(app, check, label, predicate, ms) {
  const value = await until(predicate, ms);
  check(label, !!value, value ? '' : detail(app));
  return value;
}

async function withApp(check, label, scenario, {
  remembered = false, speakMs = 220, seed, credentials, preferences, reuseStorage = false,
} = {}) {
  if (!reuseStorage) await cleanStorage();
  if (seed) await within(saveSession(seed), 'seeding the recorded voice interview');
  if (credentials) await within(saveCredentials(credentials), 'encrypting synthetic speech credentials');
  if (remembered || preferences) {
    localStorage.setItem('ideaforge.prefs', JSON.stringify({
      ...preferences, ...(remembered ? { handsFree: true } : {}),
    }));
  }
  let app;
  let frame;
  const fake = window.__FakeVoice;
  try {
    frame = await loadFrame();
    const win = frame.contentWindow;
    const doc = frame.contentDocument;
    const $ = (id) => doc.getElementById(id);
    const errors = [];
    const violations = [];
    win.addEventListener('error', (event) => errors.push(String(event.message)));
    win.addEventListener('unhandledrejection', (event) => {
      errors.push(String(event.reason?.stack || event.reason));
      event.preventDefault();
    });
    win.addEventListener('securitypolicyviolation',
      (event) => violations.push(event.violatedDirective + ' blocked ' + event.blockedURI));
    fake.install(win, { script: [], speakMs });
    const recognition = instrumentRecognition(win);
    const mic = instrumentMic(win, { muted: true });
    const model = modelFixture();
    win.claude = model.claude;
    const requests = [];
    win.fetch = async (input) => {
      const url = String(input?.url || input);
      requests.push(url);
      if (url === 'https://api.openai.com/v1/audio/speech') {
        return new win.Response(JSON.stringify({ error: { message: 'scripted preview refusal' } }), {
          status: 401, headers: { 'content-type': 'application/json' },
        });
      }
      throw new Error('network disabled in voice-stage probe');
    };
    app = { frame, win, doc, $, fake, mic, recognition, model, errors, violations, requests,
      expectedRequests: 0, expectedRequestUrl: 'https://api.openai.com/v1/audio/speech',
      phases: [], originalUrl: win.location.href };
    const observer = new win.MutationObserver(() => app.phases.push(phase(app)));
    observer.observe($('voice-stage'), { attributes: true, attributeFilter: ['data-phase'] });
    app.observer = observer;
    const setupReady = await until(() =>
      doc.readyState === 'complete'
        && /^IdeaForge v\d+\.\d+\.\d+$/.test($('version').textContent)
        && $('provider').options.length > 0
        && visible($('panel-setup'))
        && typeof $('b-start').onclick === 'function'
        && typeof $('b-start-voice').onclick === 'function');
    // Registration is optional background work, not evidence that app setup completed.
    await inspectRegistration(app);
    check(label + ': app finishes asynchronous setup', !!setupReady,
      JSON.stringify(bootDetails(app)));
    if (!setupReady) return;
    $('provider').value = 'artifact';
    $('provider').dispatchEvent(new win.Event('change'));
    $('stt').value = 'browser';
    $('stt').dispatchEvent(new win.Event('change'));
    await scenario(app);
    check(label + ': every visible voice phase is part of the public state contract',
      app.phases.every((value) => PHASES.has(value)), app.phases.join(', '));
    check(label + ': no uncaught async error', errors.length === 0, errors.join('\n'));
    check(label + ': no unexpected provider request',
      requests.length === app.expectedRequests
        && requests.every((url) => url === app.expectedRequestUrl),
      requests.join('\n'));
    check(label + ': no CSP violation', violations.length === 0, violations.join('\n'));
  } catch (error) {
    check(label, false, String(error?.stack || error) + (app ? '\n' + detail(app) : ''));
  } finally {
    let cleanupError = null;
    let cleanupPhase = 'requesting Exit';
    let snapshot = app ? detail(app) : 'iframe setup did not complete';
    try {
      if (app) {
        let exiting;
        if (visible(app.$('voice-stage'))) {
          const button = app.$('b-voice-exit');
          const handler = button.onclick;
          if (typeof handler !== 'function' || button.disabled) {
            throw new Error('Exit is unavailable during cleanup');
          }
          button.onclick = function (event) {
            exiting = handler.call(this, event);
            return exiting;
          };
          try { button.click(); } finally { button.onclick = handler; }
        }
        // Exit must cancel the loop before releasing an endpoint or model result.
        app.recognition.releaseHeld();
        app.mic.releaseHeld();
        app.wakeLocks?.releasePending();
        app.model.releaseAll();
        cleanupPhase = 'waiting for Exit and its draft save';
        await within(Promise.resolve(exiting), cleanupPhase);
        cleanupPhase = 'waiting for the stage and capture to stop';
        if (!(await until(() => !visible(app.$('voice-stage'))
          && liveCaptures(app).length === 0 && app.mic.allStopped()))) {
          throw new Error('stage or microphone still active after 5000ms');
        }
      }
    } catch (error) {
      cleanupError = error;
      check(`${label}: cleanup ${cleanupPhase}`, false,
        String(error?.stack || error) + (app ? '\n' + detail(app) : ''));
    } finally {
      if (app) {
        snapshot = detail(app);
        try {
          app.recognition.dispose();
          app.win.speechSynthesis.cancel();
        } finally {
          app.observer.disconnect();
          app.mic.dispose();
          app.wakeLocks?.dispose();
          frame?.remove();
          fake.restore();
        }
      } else {
        frame?.remove();
        fake.restore();
      }
    }
    try {
      await cleanStorage();
    } catch (error) {
      cleanupError = error;
      check(`${label}: cleanup storage`, false, String(error?.stack || error) + '\n' + snapshot);
    }
    if (cleanupError) {
      throw new Error(`${label}: cleanup failed; later scenarios will not reuse this origin`,
        { cause: cleanupError });
    }
    check(label + ': isolated fixture cleanup completes', true);
  }
}

async function startVoice(app, check) {
  const before = new Set((await listSessions()).map((session) => session.id));
  app.$('b-start-voice').click();
  return expect(app, check, 'Start with voice enters the dedicated speaking stage', async () => {
    if (!visible(app.$('voice-stage')) || phase(app) !== 'speaking' || !spoken(app).length) return null;
    return (await listSessions()).find((session) => !before.has(session.id)) || null;
  });
}

async function listening(app, check, label = 'speech completes before one capture begins') {
  return expect(app, check, label, () =>
    phase(app) === 'listening' && liveCaptures(app).length === 1 && app.recognition.current());
}

async function startTypedDraft(app, check, draft = '') {
  app.$('b-start').click();
  if (!(await expect(app, check, 'the typed interview is ready without voice ownership', () =>
    visible(app.$('manual-interview')) && !visible(app.$('voice-stage'))
      && app.doc.activeElement === app.$('answer')))) return null;
  const session = (await within(listSessions(), 'reading the typed interview'))[0];
  if (draft) {
    app.$('answer').value = draft;
    app.$('answer').dispatchEvent(new app.win.Event('input'));
    if (!(await expect(app, check, 'the retained typed draft is durable before voice starts', async () =>
      (await within(loadSession(session.id), 'reading the retained draft'))?.draftAnswer === draft))) return null;
  }
  return session;
}

async function listenWithRetainedDraft(app, check, origin) {
  const draft = origin === 'typed' ? TYPED : PARTIAL;
  const session = origin === 'typed'
    ? await startTypedDraft(app, check, draft) : await startVoice(app, check);
  if (!session) return null;
  if (origin === 'typed') {
    app.$('handsfree').checked = true;
    app.$('handsfree').dispatchEvent(new app.win.Event('change'));
  } else {
    const capture = await listening(app, check, 'a spoken draft is captured before Pause');
    if (!capture) return null;
    capture._step({ final: draft, confidence: 0.9 });
    app.$('b-voice-pause').click();
    if (!(await expect(app, check, 'Pause retains the spoken draft without answering', async () =>
      phase(app) === 'paused' && !app.$('b-voice-pause').disabled
        && (await within(loadSession(session.id), 'reading the paused draft'))?.draftAnswer === draft))) return null;
    app.$('b-voice-pause').click();
  }
  const capture = await listening(app, check, 'voice is listening with the retained draft');
  return capture ? { session, draft, capture } : null;
}

function reducedMotionRules(app) {
  const sheet = [...app.doc.styleSheets].find((item) =>
    item.href && new URL(item.href).pathname.endsWith('/src/ui/app.css'));
  const rules = sheet ? [...sheet.cssRules].filter((rule) =>
    rule.type === app.win.CSSRule.MEDIA_RULE
      && /prefers-reduced-motion\s*:\s*reduce/.test(rule.conditionText)) : [];
  const previous = rules.map((rule) => rule.media.mediaText);
  // Activate the shipped rule bodies in this frame only, without fabricating replacement
  // declarations or claiming OS-level media emulation / browser-policy coverage.
  rules.forEach((rule) => { rule.media.mediaText = 'all'; });
  return {
    count: rules.length,
    restore() { rules.forEach((rule, index) => { rule.media.mediaText = previous[index]; }); },
  };
}

async function checkMeterAndLayout(app, check) {
  if (!(await expect(app, check, 'Web Speech exposes an instrumented real microphone stream',
    () => phase(app) === 'listening' && app.mic.tracks().some((track) =>
      track.kind === 'audio' && track.readyState === 'live' && track.enabled)))) return;
  // Capture startup re-enables its tracks, so mute only after that owner has started.
  app.mic.mute(true);
  if (!(await expect(app, check, 'muting the real input produces a silent meter',
    () => Number.isFinite(level(app)) && level(app) === 0))) return;

  const reduced = reducedMotionRules(app);
  check('the app ships an activatable reduced-motion rule', reduced.count > 0);
  const signal = app.$('voice-signal');
  const bars = [...signal.querySelectorAll('span')];
  const before = bars.map((bar) => app.win.getComputedStyle(bar).transform);
  const status = app.$('voice-status').textContent;
  const changes = [];
  const observer = new app.win.MutationObserver((records) => changes.push(...records));
  observer.observe(app.$('voice-status'), { childList: true, characterData: true, subtree: true });
  try {
    app.mic.mute(false);
    await expect(app, check, 'real fake-device audio, without transcript events, changes the meter',
      () => Number.isFinite(level(app)) && level(app) > 0.01 && level(app) <= 1, 7000);
    check('reduced motion keeps the signal static even when measured audio changes',
      bars.length > 0 && bars.every((bar, index) =>
        app.win.getComputedStyle(bar).transform === before[index])
        && signal.getAnimations({ subtree: true }).every((animation) => animation.playState !== 'running'),
      `transforms=${bars.map((bar) => app.win.getComputedStyle(bar).transform).join(', ')}`);
    app.mic.mute(true);
    await expect(app, check, 'muting that same stream returns the meter to zero',
      () => level(app) === 0);
    check('meter samples do not re-announce the live status on every frame',
      changes.length === 0 && app.$('voice-status').textContent === status,
      `${changes.length} live-region mutations`);
    check('reduced motion retains readable status and question captions',
      visible(app.$('voice-status')) && visible(app.$('voice-question'))
        && visible(app.$('voice-transcript')) && /listening/i.test(status)
        && app.$('voice-question').textContent.trim().length > 0
        && app.$('voice-transcript').textContent === PARTIAL);
  } finally {
    observer.disconnect();
    reduced.restore();
  }

  for (const width of [320, 390]) {
    app.frame.width = width;
    await expect(app, check, `the voice frame reaches ${width}px`, () =>
      app.win.innerWidth === width && app.frame.getBoundingClientRect().width === width);
    const contentWidth = app.doc.documentElement.clientWidth;
    const pause = app.$('b-voice-pause').getBoundingClientRect();
    const exit = app.$('b-voice-exit').getBoundingClientRect();
    check(`Pause and Exit remain at least 48 by 48 CSS pixels at ${width}px`,
      [pause, exit].every((rect) => rect.height >= 48 && rect.width >= 48),
      `${pause.width}x${pause.height}, ${exit.width}x${exit.height}`);
    check(`the voice stage has no horizontal overflow at ${width}px`,
      app.doc.documentElement.scrollWidth <= app.doc.documentElement.clientWidth,
      `${app.doc.documentElement.scrollWidth}/${app.doc.documentElement.clientWidth}`);
    check(`voice captions and controls stay within the ${width}px viewport`,
      ['voice-status', 'voice-question', 'voice-transcript', 'b-voice-pause', 'b-voice-exit']
        .filter((id) => visible(app.$(id)))
        .every((id) => {
          const rect = app.$(id).getBoundingClientRect();
          return rect.left >= -0.5 && rect.right <= contentWidth + 0.5;
        }));
    check(`Pause and Exit do not overlap at ${width}px`,
      pause.right <= exit.left || exit.right <= pause.left
        || pause.bottom <= exit.top || exit.bottom <= pause.top);
  }
  app.$('b-voice-pause').focus({ focusVisible: true });
  const focus = app.win.getComputedStyle(app.$('b-voice-pause'));
  check('voice controls have a visible keyboard focus indicator',
    app.doc.activeElement === app.$('b-voice-pause')
      && focus.outlineStyle !== 'none' && Number.parseFloat(focus.outlineWidth) >= 2,
    `${focus.outlineStyle} ${focus.outlineWidth}`);
}

function noOverlap(app, check) {
  const captures = app.fake.recognition.sessions.filter((session) => session.flags.continuous);
  const overlaps = captures.filter((session) => spoken(app).some((utterance) =>
    utterance.startedAt < session.startedAt
      && (utterance.endedAt === null || utterance.endedAt > session.startedAt)));
  check('no answer capture starts during substantive speech playback',
    captures.length > 0 && overlaps.length === 0,
    `${overlaps.length} overlaps across ${captures.length} captures`);
}

export default async function run(check) {
  await import('./fixtures/fake-voice.js');

  await withApp(check, 'voice capture pause, late result, resume and exit', async (app) => {
    check('voice is inactive before the explicit Start gesture',
      !visible(app.$('voice-stage')) && captureCount(app) === 0 && app.mic.streams.length === 0);
    const session = await startVoice(app, check);
    if (!session) return;
    check('voice entry hides the manual composer, top navigation and coverage',
      !visible(app.$('manual-interview')) && !visible(app.$('b-library'))
        && !visible(app.$('b-settings')) && !visible(app.$('meter')), detail(app));
    check('speaking has a real question caption and no pretend microphone waveform',
      app.$('voice-question').textContent.includes(session.turns[0].question)
        && level(app) === 0 && liveCaptures(app).length === 0
        && app.$('voice-signal').getAnimations({ subtree: true }).every((animation) =>
          animation.effect.getTiming().iterations !== Infinity), detail(app));
    check('the voice status is a polite atomic status region',
      app.$('voice-status').getAttribute('role') === 'status'
        && app.$('voice-status').getAttribute('aria-live') === 'polite'
        && app.$('voice-status').getAttribute('aria-atomic') === 'true');
    const capture = await listening(app, check);
    if (!capture) return;
    check('focus belongs to the visible voice stage rather than a hidden answer box',
      app.$('voice-stage').contains(app.doc.activeElement), app.doc.activeElement?.id);
    capture._step({ final: PARTIAL, confidence: 0.9 });
    if (!(await expect(app, check, 'a confirmed partial answer remains visible and unsubmitted',
      () => app.$('voice-transcript').textContent === PARTIAL
        && phase(app) === 'listening' && app.model.calls.length === 0))) return;
    check('embedded pause voice and exit voice phrases are not commands',
      phase(app) === 'listening' && app.$('answer').value === PARTIAL);
    await checkMeterAndLayout(app, check);
    app.mic.mute(false);
    if (!(await expect(app, check, 'a real input sample is active immediately before Pause',
      () => level(app) > 0.01 && level(app) <= 1, 7000))) return;
    const late = app.recognition.queueResult(capture, { final: 'over', confidence: 0.9 });
    const endpoint = app.recognition.holdEnd(capture);
    const lateEnd = capture.onend;
    const starts = captureCount(app);
    const speeches = spoken(app).length;
    app.$('b-voice-pause').click();
    if (!(await expect(app, check, 'Pause exposes a finishing draft instead of starting another capture',
      () => phase(app) === 'paused' && endpoint.pending))) return;
    check('Pause releases every acquired microphone track before draft finalization',
      app.mic.tracks().length > 0 && app.mic.allStopped(), detail(app));
    check('Resume is disabled while captured words are still finishing',
      /^Resume$/i.test(app.$('b-voice-pause').textContent.trim())
        && app.$('b-voice-pause').disabled, detail(app));
    app.$('b-voice-pause').click();
    check('a Resume tap during finalization cannot start another loop',
      captureCount(app) === starts && spoken(app).length === speeches);
    late();
    endpoint.release();
    if (!(await expect(app, check, 'the confirmed words settle as a durable unsent draft', async () => {
      const saved = await loadSession(session.id);
      return phase(app) === 'paused' && !app.$('b-voice-pause').disabled
        && saved?.draftAnswer === PARTIAL && saved.turns.length === 1
        && !saved.turns[0].answer && !saved.turns[0].skipped
        && app.$('answer').value === PARTIAL && app.$('voice-transcript').textContent === PARTIAL;
    }))) return;
    late();
    lateEnd();
    check('late final and endpoint events cannot submit, speak or restart after Pause',
      await remains(() => phase(app) === 'paused' && captureCount(app) === starts
        && spoken(app).length === speeches && app.model.calls.length === 0
        && app.mic.allStopped() && level(app) === 0), detail(app));
    app.$('b-voice-pause').click();
    if (!(await listening(app, check, 'an explicit Resume returns to listening'))) return;
    check('Resume starts exactly one new capture and reads the question once',
      captureCount(app) === starts + 1
        && spoken(app).filter((utterance) => utterance.text.includes(session.turns[0].question)).length === 2,
      detail(app));
    check('the resumed loop stays single while waiting for speech',
      await remains(() => liveCaptures(app).length === 1 && captureCount(app) === starts + 1),
      detail(app));
    app.$('b-voice-exit').click();
    if (!(await expect(app, check, 'Exit restores the manual composer and captured draft', () =>
      !visible(app.$('voice-stage')) && visible(app.$('manual-interview'))
        && app.$('answer').value === PARTIAL))) return;
    const saved = await loadSession(session.id);
    check('Exit neither deletes, skips, wraps nor submits the interview',
      saved?.turns.length === 1 && !saved.turns[0].answer && !saved.turns[0].skipped
        && saved.status !== 'done' && !saved.synthesis.text && app.model.calls.length === 0,
      JSON.stringify(saved));
    check('Exit restores navigation, coverage and focus without changing the URL',
      visible(app.$('b-library')) && visible(app.$('b-settings')) && visible(app.$('meter'))
        && app.doc.activeElement === app.$('answer') && app.win.location.href === app.originalUrl,
      detail(app));
    check('Exit leaves all tracks stopped and no stale meter',
      app.mic.allStopped() && level(app) === 0 && liveCaptures(app).length === 0, detail(app));
    noOverlap(app, check);
  });

  await withApp(check, 'Type instead and pause during playback', async (app) => {
    app.$('b-start').click();
    if (!(await expect(app, check, 'Type instead opens an explicitly manual interview', () =>
      visible(app.$('manual-interview')) && app.doc.activeElement === app.$('answer')))) return;
    check('a remembered hands-free preference cannot auto-start the Type instead path',
      await remains(() => !visible(app.$('voice-stage')) && captureCount(app) === 0
        && spoken(app).length === 0 && app.mic.streams.length === 0), detail(app));
    const session = (await listSessions())[0];
    app.$('answer').value = TYPED;
    app.$('answer').dispatchEvent(new app.win.Event('input'));
    if (!(await expect(app, check, 'the typed draft is saved before voice entry',
      async () => (await loadSession(session.id))?.draftAnswer === TYPED))) return;
    app.$('handsfree').checked = true;
    app.$('handsfree').dispatchEvent(new app.win.Event('change'));
    if (!(await expect(app, check, 'explicit hands-free entry starts question playback', () =>
      phase(app) === 'speaking' && spoken(app).some((utterance) => utterance.endedAt === null)))) return;
    const utterance = spoken(app).findLast((entry) => entry.endedAt === null);
    const before = captureCount(app);
    app.$('b-voice-pause').click();
    check('Pause cancels the in-flight utterance immediately', utterance.endedAt !== null, detail(app));
    const cancelledAt = utterance.endedAt;
    if (!(await expect(app, check, 'playback Pause settles with an explicit Resume control',
      () => phase(app) === 'paused' && !app.$('b-voice-pause').disabled))) return;
    // The shared fixture still delivers its queued onend after cancellation.
    if (!(await expect(app, check, 'the cancelled speech endpoint is actually delivered',
      () => utterance.endedAt > cancelledAt))) return;
    check('late speech completion after Pause never opens the microphone',
      phase(app) === 'paused' && captureCount(app) === before && app.mic.allStopped()
        && app.model.calls.length === 0, detail(app));
    app.$('b-voice-exit').click();
    await expect(app, check, 'Exit after interrupted playback preserves the typed draft exactly',
      async () => visible(app.$('manual-interview')) && !visible(app.$('voice-stage'))
        && app.$('answer').value === TYPED
        && (await loadSession(session.id))?.draftAnswer === TYPED);
  }, { remembered: true, speakMs: 450 });

  await withApp(check, 'spoken commands and a model settling while paused', async (app) => {
    const session = await startVoice(app, check);
    if (!session) return;
    const first = await listening(app, check);
    if (!first) return;
    first._step({ final: 'pause voice', confidence: 0.9 });
    if (!(await expect(app, check, 'the whole spoken pause voice command pauses without a tap', () =>
      phase(app) === 'paused' && !app.$('b-voice-pause').disabled))) return;
    let saved = await loadSession(session.id);
    check('pause voice is never recorded as answer text or an unfinished draft',
      saved?.turns.length === 1 && !saved.turns[0].answer && !saved.draftAnswer
        && app.model.calls.length === 0 && app.mic.allStopped(), JSON.stringify(saved));
    app.$('b-voice-pause').click();
    const second = await listening(app, check, 'Resume after a spoken pause starts one capture');
    if (!second) return;
    second._step({ final: 'A notebook for remembering names at conferences over', confidence: 0.9 });
    if (!(await expect(app, check, 'a completed answer enters the thinking phase', () =>
      app.model.calls.length === 1 && phase(app) === 'thinking'))) return;
    check('thinking shows no microphone waveform or active recognizer',
      level(app) === 0 && liveCaptures(app).length === 0, detail(app));
    const count = spoken(app).length;
    app.$('b-voice-pause').click();
    if (!(await expect(app, check, 'the model can remain in flight while voice is paused', () =>
      phase(app) === 'paused' && app.mic.allStopped()))) return;
    app.model.calls[0].release();
    if (!(await expect(app, check, 'the already-submitted model turn is saved while paused', async () => {
      const row = await loadSession(session.id);
      return row?.turns.length === 2 && row.turns[1].question === NEXT_QUESTION && !row.pending;
    }))) return;
    check('settling a submitted turn while paused does not narrate or listen automatically',
      await remains(() => phase(app) === 'paused' && spoken(app).length === count
        && liveCaptures(app).length === 0 && app.mic.allStopped()), detail(app));
    app.$('b-voice-pause').click();
    const third = await listening(app, check, 'Resume after model settlement listens to the new question');
    if (!third) return;
    check('Resume does not replay the model request or read the wrong question',
      app.model.calls.length === 1 && app.$('voice-question').textContent === NEXT_QUESTION
        && spoken(app).filter((utterance) => utterance.text === NEXT_QUESTION).length === 1,
      detail(app));
    third._step({ final: 'exit voice', confidence: 0.9 });
    if (!(await expect(app, check, 'the whole spoken exit voice command restores manual mode', () =>
      visible(app.$('manual-interview')) && !visible(app.$('voice-stage'))))) return;
    saved = await loadSession(session.id);
    check('exit voice is neither an answer, a skip, a draft nor a wrap-up',
      saved?.turns.length === 2 && !saved.turns[1].answer && !saved.turns[1].skipped
        && !saved.draftAnswer && saved.status !== 'done' && app.model.calls.length === 1,
      JSON.stringify(saved));
    check('the spoken Exit also releases acquired microphone tracks',
      app.mic.tracks().length > 0 && app.mic.allStopped(), detail(app));
    noOverlap(app, check);
  });

  let recorded = seedTurn(createSession({ id: 's_voice_wrap_after_resume', now: 1 }), { now: 1 });
  for (let index = 0; index < 4; index++) {
    recorded = submitAnswer(recorded, {
      text: `Conference notebook requirement ${index + 1}: keep each contact useful and easy to find`,
      source: 'voice', now: 2 + index * 2,
    });
    recorded = askQuestion(recorded, {
      question: `Which notebook detail matters for conversation ${index + 1}?`,
      dimension: 'outcome', source: 'model', now: 3 + index * 2,
    });
  }
  await withApp(check, 'recorded wrap eligibility survives Pause and Resume', async (app) => {
    app.$('b-library').click();
    const card = await expect(app, check, 'the four-answer interview is available in the library', () =>
      visible(app.$('panel-library'))
        && app.$('library-rows').querySelector(`[data-session-id="${recorded.id}"]`));
    if (!card) return;
    [...card.querySelectorAll('button')].find((button) => button.textContent === 'Continue').click();
    if (!(await expect(app, check, 'the recorded interview resumes at its unanswered question', () =>
      visible(app.$('manual-interview'))
        && app.$('question').textContent === recorded.turns.at(-1).question))) return;
    app.$('handsfree').checked = true;
    app.$('handsfree').dispatchEvent(new app.win.Event('change'));
    if (!(await listening(app, check, 'voice starts over the recorded interview history'))) return;
    app.$('b-voice-pause').click();
    if (!(await expect(app, check, 'the recorded interview pauses before a fresh voice loop', () =>
      phase(app) === 'paused' && !app.$('b-voice-pause').disabled))) return;
    app.$('b-voice-pause').click();
    const capture = await listening(app, check, 'Resume starts the fresh loop without another answer');
    if (!capture) return;
    const before = await within(loadSession(recorded.id), 'reading recorded wrap eligibility');
    check('four durable answers, not this loop lifetime, establish wrap eligibility',
      before.turns.filter((turn) => turn.answer).length === 4 && app.model.calls.length === 0);
    capture._step({ final: 'wrap it up', confidence: 0.9 });
    const synthesis = await expect(app, check, 'wrap it up remains eligible immediately after Pause and Resume', () =>
      app.model.calls.length === 1 && app.model.calls[0].tier === 'complex' && app.model.calls[0]);
    if (!synthesis) return;
    synthesis.release();
    const completed = await expect(app, check, 'the recorded answers survive the resumed wrap-up', async () => {
      const saved = await within(loadSession(recorded.id), 'reading the resumed wrap-up');
      return saved?.status === 'done' && saved.synthesis.text && saved;
    });
    if (!completed) return;
    check('resumed wrap uses one synthesis and does not record its command as an answer',
      app.model.calls.length === 1
        && JSON.stringify(completed.turns) === JSON.stringify(before.turns), detail(app));
  }, { seed: recorded });

  for (const command of ['pause voice', 'exit voice']) {
    for (const action of ['Pause', 'Exit']) {
      await withApp(check, `unsettled ${command} followed by ${action}`, async (app) => {
        const prefix = 'Keep my original conference notebook wording.';
        app.$('b-start').click();
        if (!(await expect(app, check, 'the command-race interview starts in manual mode', () =>
          visible(app.$('manual-interview')) && app.doc.activeElement === app.$('answer')))) return;
        const session = (await within(listSessions(), 'reading the command-race session'))[0];
        app.$('answer').value = prefix;
        app.$('answer').dispatchEvent(new app.win.Event('input'));
        if (!(await expect(app, check, 'a legitimate draft precedes the unsettled command', async () =>
          (await within(loadSession(session.id), 'reading the pre-command draft'))?.draftAnswer === prefix))) return;
        app.$('handsfree').checked = true;
        app.$('handsfree').dispatchEvent(new app.win.Event('change'));
        const capture = await listening(app, check, 'the strict recognizer can deliver an Android command draft');
        if (!capture) return;
        const started = performance.now();
        capture._step({ final: command, confidence: 0 });
        check('a zero-confidence command is still awaiting settlement before the tap',
          phase(app) === 'listening' && app.$('answer').value === `${prefix} ${command}`, detail(app));
        app.$(action === 'Pause' ? 'b-voice-pause' : 'b-voice-exit').click();
        check(`${action} occurs before the Android command settlement deadline`,
          performance.now() - started < DRIVING.settleMs,
          `${Math.round(performance.now() - started)}ms / ${DRIVING.settleMs}ms`);
        if (!(await expect(app, check, `${action} finishes without submitting the unsettled command`, () =>
          action === 'Pause'
            ? phase(app) === 'paused' && !app.$('b-voice-pause').disabled
            : !visible(app.$('voice-stage')) && visible(app.$('manual-interview'))
              && app.doc.activeElement === app.$('answer')))) return;
        check(`${action} retains only the legitimate draft, not ${command}`,
          app.$('answer').value === prefix
            && (await within(loadSession(session.id), 'reading the interrupted command draft'))?.draftAnswer === prefix
            && app.model.calls.length === 0, detail(app));
        const starts = captureCount(app);
        check('the old settlement timer cannot restore or submit the command after the tap',
          await remains(() => app.$('answer').value === prefix && app.model.calls.length === 0
            && captureCount(app) === starts && liveCaptures(app).length === 0,
          DRIVING.settleMs + 50), detail(app));
        if (action === 'Pause') app.$('b-voice-pause').click();
        else {
          app.$('handsfree').checked = true;
          app.$('handsfree').dispatchEvent(new app.win.Event('change'));
        }
        const next = await listening(app, check, 'a deliberate return to voice starts the next capture');
        if (!next) return;
        next._step({ final: `${PARTIAL} over`, confidence: 0.9 });
        if (!(await expect(app, check, 'the subsequent answer reaches the model once',
          () => app.model.calls.length === 1))) return;
        const answered = await within(loadSession(session.id), 'reading the answer after the command race');
        check('embedded command phrases stay in the answer without a prepended stale command',
          answered.turns[0].answer === `${prefix} ${PARTIAL}`
            && answered.turns[0].answerSource === 'voice', JSON.stringify(answered.turns[0].answer));
        app.model.calls[0].release();
        await expect(app, check, 'the clean subsequent answer advances exactly one turn', async () =>
          (await within(loadSession(session.id), 'reading the settled command-race turn'))?.turns.length === 2);
      });
    }
  }

  await withApp(check, 'recorder acquisition reports starting until native capture begins', async (app) => {
    app.$('stt').value = 'groq';
    app.$('stt').dispatchEvent(new app.win.Event('change'));
    app.$('sttkey').value = 'probe-transcription-key-never-sent';
    const pending = app.mic.holdNext();
    const NativeRecorder = app.win.MediaRecorder;
    const recorders = [];
    let started = 0;
    let earlyListening = false;
    app.win.MediaRecorder = class extends NativeRecorder {
      constructor(...args) {
        super(...args);
        recorders.push(this);
        this.addEventListener('start', () => { started++; }, { once: true });
      }
    };
    const observer = new app.win.MutationObserver(() => {
      if (phase(app) === 'listening' && !started) earlyListening = true;
    });
    observer.observe(app.$('voice-stage'), { attributes: true, attributeFilter: ['data-phase'] });
    try {
      if (!(await startVoice(app, check))) return;
      if (!(await expect(app, check, 'recorder getUserMedia is requested but deliberately unresolved',
        () => pending.requested && !pending.released))) return;
      check('pending recorder acquisition stays Starting rather than claiming Listening',
        await remains(() => phase(app) === 'starting' && !started && !recorders.length
          && app.mic.streams.length === 0 && level(app) === 0), detail(app));
      pending.release();
      if (!(await expect(app, check, 'Listening follows the real MediaRecorder start event', () =>
        started === 1 && phase(app) === 'listening' && recorders[0].state === 'recording'
          && app.mic.tracks().some((track) => track.readyState === 'live')))) return;
      check('no Listening phase appeared before native recorder startup', !earlyListening, detail(app));
      app.$('b-voice-exit').click();
      await expect(app, check, 'Exit releases the real recorder without a transcription or model request', () =>
        !visible(app.$('voice-stage')) && visible(app.$('manual-interview')) && app.mic.allStopped()
          && recorders[0].state === 'inactive' && app.requests.length === 0 && app.model.calls.length === 0);
    } finally {
      observer.disconnect();
      app.win.MediaRecorder = NativeRecorder;
    }
  });

  await withApp(check, 'wrap-up narration pause, resume and exit', async (app) => {
    const answers = [
      'A pocket notebook for remembering names after meeting people at conferences',
      'Independent designers who meet dozens of new collaborators at a conference',
      'Keep the name and one detail about the conversation on this device',
      'Let me retrieve a name within three seconds even without a connection',
    ];
    const questions = [
      NEXT_QUESTION,
      'Which detail about a new contact must the notebook keep?',
      'How quickly should a saved name be retrievable?',
      'What should happen when the phone has no connection?',
    ];
    const synthesis = {
      title: 'Conference name notebook',
      prompt: [
        'Build a pocket notebook that helps independent designers remember the people they meet at conferences. '
          + 'Keep each name beside one useful detail from the conversation so the next meeting has a natural starting point.',
        'Let a person create a note with one hand between conversations. '
          + 'Keep the original wording available for editing rather than replacing it with an invented summary.',
        'Make saved names retrievable within three seconds without an internet connection. '
          + 'Show the matching conversation detail alongside the name, with a clear way to correct either field.',
        'Keep the notebook on this device and preserve its contents when the user leaves the voice interface. '
          + 'Provide a readable export containing the complete instructions and the interview answers.',
      ].join('\n\n'),
      assumptions: [],
      open_questions: [],
    };
    const session = await startVoice(app, check);
    if (!session) return;
    for (let index = 0; index < answers.length; index++) {
      const capture = await listening(app, check, `wrap-up setup listens for answer ${index + 1}`);
      if (!capture) return;
      if (index === 0 && !(await expect(app, check, 'wrap-up setup acquires real microphone tracks',
        () => app.mic.tracks().some((track) => track.readyState === 'live')))) return;
      capture._step({ final: `${answers[index]} over`, confidence: 0.9 });
      const call = await expect(app, check, `wrap-up answer ${index + 1} reaches the scripted model`, () =>
        phase(app) === 'thinking' && app.model.calls.length === index + 1
          && app.model.calls[index].tier !== 'complex' && app.model.calls[index]);
      if (!call) return;
      call.release(turnResult(questions[index]));
    }
    const command = await listening(app, check, 'four answers make the spoken wrap command available');
    if (!command) return;
    command._step({ final: 'wrap it up', confidence: 0.9 });
    const writing = await expect(app, check, 'spoken wrap starts exactly one held synthesis', () =>
      phase(app) === 'thinking' && app.model.calls.length === answers.length + 1
        && app.model.calls.at(-1).tier === 'complex' && app.model.calls.at(-1));
    if (!writing) return;
    const captures = captureCount(app);
    const recognitionStarts = app.fake.recognition.startCount;
    const micRequests = app.mic.requests;
    const noInput = () => app.mic.tracks().length > 0 && app.mic.allStopped()
      && liveCaptures(app).length === 0 && captureCount(app) === captures
      && app.fake.recognition.startCount === recognitionStarts
      && app.mic.requests === micRequests && level(app) === 0;
    check('write-up releases the microphone instead of leaving a hidden listener',
      noInput(), detail(app));

    app.$('b-voice-pause').click();
    if (!(await expect(app, check, 'write-up can pause while its synthesis is still in flight', () =>
      phase(app) === 'paused' && !app.$('b-voice-pause').disabled && !writing.released))) return;
    const beforeReadback = spoken(app).length;
    writing.release(synthesis);
    const completed = await expect(app, check, 'the full write-up settles durably while voice stays paused',
      async () => {
        const saved = await within(loadSession(session.id), 'reading the completed wrap-up');
        return saved?.status === 'done' && saved.synthesis.text === synthesis.prompt && !saved.pending
          && app.$('output').dataset.source?.includes(synthesis.prompt) && phase(app) === 'paused'
          && saved;
      });
    if (!completed) return;
    const exported = app.$('output').dataset.source;
    check('the completed export contains every spoken answer and the entire scripted prompt',
      completed.turns.filter((turn) => turn.answer).length === answers.length
        && answers.every((answer, index) => completed.turns[index].answer === answer
          && completed.turns[index].answerSource === 'voice' && exported.includes(answer))
        && completed.title === synthesis.title && exported.includes(synthesis.prompt),
      `${completed.turns.length} turns; ${exported.length} export characters`);
    check('a completed write-up does not narrate or acquire input without an explicit Resume tap',
      await remains(() => phase(app) === 'paused' && noInput()
        && spoken(app).length === beforeReadback), detail(app));

    const readingResult = () => {
      const utterance = spoken(app).at(-1);
      return phase(app) === 'speaking' && utterance?.endedAt === null
        && (utterance.text.includes(synthesis.title)
          || synthesis.prompt.replace(/\s+/g, ' ').includes(utterance.text))
        && utterance;
    };
    app.$('b-voice-pause').click();
    const narration = await expect(app, check, 'an explicit Resume starts the completed prompt narration',
      () => spoken(app).length === beforeReadback + 1 && readingResult());
    if (!narration) return;
    check('read-back keeps all microphone tracks off', noInput(), detail(app));
    app.$('b-voice-pause').click();
    check('Pause immediately stops the in-flight wrap-up narration',
      narration.endedAt !== null && !app.win.speechSynthesis.speaking, detail(app));
    const cancelledAt = narration.endedAt;
    const pausedSpeechCount = spoken(app).length;
    if (!(await expect(app, check, 'paused read-back offers Resume after stopping speech', () =>
      phase(app) === 'paused' && !app.$('b-voice-pause').disabled))) return;
    if (!(await expect(app, check, 'the cancelled read-back actually receives its queued late endpoint',
      () => narration.endedAt > cancelledAt))) return;
    check('late read-back completion cannot start another chunk or the next capture',
      await remains(() => phase(app) === 'paused' && noInput()
        && spoken(app).length === pausedSpeechCount), detail(app));

    app.$('b-voice-pause').click();
    const resumed = await expect(app, check, 'a second explicit Resume starts one narration, not an interview',
      () => spoken(app).length === pausedSpeechCount + 1 && readingResult());
    if (!resumed) return;
    check('resuming narration neither reacquires a microphone nor repeats synthesis',
      noInput() && app.model.calls.length === answers.length + 1
        && app.model.calls.filter((call) => call.tier === 'complex').length === 1, detail(app));
    app.$('b-voice-exit').click();
    check('Exit immediately stops resumed wrap-up speech',
      resumed.endedAt !== null && !app.win.speechSynthesis.speaking, detail(app));
    const exitedAt = resumed.endedAt;
    const exitedSpeechCount = spoken(app).length;
    if (!(await expect(app, check, 'Exit reveals the completed export without losing any text', () =>
      !visible(app.$('voice-stage')) && visible(app.$('panel-done'))
        && app.$('done-title').textContent === synthesis.title
        && app.$('output').dataset.source === exported && app.doc.activeElement === app.$('b-copy')))) return;
    if (!(await expect(app, check, 'the exited narration also receives its queued late endpoint',
      () => resumed.endedAt > exitedAt))) return;
    check('late narration after Exit leaves the completed export and microphone state alone',
      await remains(() => visible(app.$('panel-done')) && !visible(app.$('voice-stage')) && noInput()
        && spoken(app).length === exitedSpeechCount && app.$('output').dataset.source === exported),
      detail(app));
    const saved = await within(loadSession(session.id), 'reading the export after voice Exit');
    check('Pause, Resume and Exit preserve the completed session without duplicate synthesis',
      JSON.stringify(saved) === JSON.stringify(completed)
        && app.model.calls.length === answers.length + 1
        && app.model.calls.filter((call) => call.tier === 'complex').length === 1,
      detail(app));
    noOverlap(app, check);
  }, { speakMs: 450 });

  const chipText = 'A pocket notebook for conference names';
  const chipSession = askQuestion(createSession({ id: 's_voice_chip_prefix', now: 1 }), {
    question: 'What form should the notebook take?',
    dimension: 'outcome', chips: [chipText], source: 'model', now: 1,
  });
  for (const origin of ['typed', 'paused', 'chip']) {
    await withApp(check, `bare finish word sends the retained ${origin} draft`, async (app) => {
      let retained;
      if (origin === 'chip') {
        app.$('b-library').click();
        const card = await expect(app, check, 'the open chip question is available in the library', () =>
          visible(app.$('panel-library'))
            && app.$('library-rows').querySelector(`[data-session-id="${chipSession.id}"]`));
        if (!card) return;
        [...card.querySelectorAll('button')].find((button) => button.textContent === 'Continue').click();
        const chip = await expect(app, check, 'the resumed question offers a real selectable chip', () =>
          [...app.$('chips').querySelectorAll('button')]
            .find((button) => visible(button) && button.textContent === chipText));
        if (!chip) return;
        chip.click();
        if (!(await expect(app, check, 'selecting the chip retains its exact unedited wording', async () =>
          app.$('answer').value === chipText
            && (await within(loadSession(chipSession.id), 'reading the chip draft'))?.draftAnswer === chipText))) return;
        app.$('handsfree').checked = true;
        app.$('handsfree').dispatchEvent(new app.win.Event('change'));
        const capture = await listening(app, check, 'voice starts with the unedited chip prefix');
        if (!capture) return;
        retained = { session: chipSession, draft: chipText, capture };
      } else {
        retained = await listenWithRetainedDraft(app, check, origin);
      }
      if (!retained) return;
      if (origin === 'typed' || origin === 'chip') {
        const pause = origin === 'typed';
        retained.capture._step({ final: pause ? 'pause voice' : 'exit voice', confidence: 0 });
        app.$(pause ? 'b-voice-pause' : 'b-voice-exit').click();
        if (!(await expect(app, check, `${pause ? 'Pause' : 'Exit'} restores only the ${origin} prefix`, () =>
          app.$('answer').value === retained.draft && (pause
            ? phase(app) === 'paused' && !app.$('b-voice-pause').disabled
            : visible(app.$('manual-interview')) && !visible(app.$('voice-stage')))))) return;
        if (pause) app.$('b-voice-pause').click();
        else {
          app.$('handsfree').checked = true;
          app.$('handsfree').dispatchEvent(new app.win.Event('change'));
        }
        retained.capture = await listening(app, check, `the restored ${origin} prefix starts a fresh capture`);
        if (!retained.capture) return;
      }
      retained.capture._step({ final: 'over', confidence: 0.9 });
      if (!(await expect(app, check, 'the bare finish word submits the existing draft once',
        () => app.model.calls.length === 1))) return;
      const submitted = await within(loadSession(retained.session.id), 'reading the bare-trigger answer');
      check('the recorded answer is exactly the retained draft, without the finish word',
        submitted.turns.length === 1 && submitted.turns[0].answer === retained.draft
          && !submitted.turns[0].skipped,
        JSON.stringify(submitted.turns[0]));
      const expectedSource = origin === 'paused' ? 'voice' : origin;
      check(`a bare finish word preserves ${expectedSource} provenance rather than relabeling the prefix`,
        submitted.turns[0].answerSource === expectedSource
          && isLowConfidence(submitted.turns[0]) === (origin === 'chip'),
        `source=${submitted.turns[0].answerSource}; lowConfidence=${isLowConfidence(submitted.turns[0])}`);
      app.model.calls[0].release();
      if (!(await expect(app, check, 'the retained answer advances one question and clears its draft', async () => {
        const saved = await within(loadSession(retained.session.id), 'reading the settled retained answer');
        return saved?.turns.length === 2 && !saved.draftAnswer;
      }))) return;
      check('the bare trigger does not cause a duplicate model request',
        await remains(() => app.model.calls.length === 1), detail(app));
    }, { seed: origin === 'chip' ? chipSession : undefined });
  }

  for (const failure of ['empty', 'error']) {
    await withApp(check, `three ${failure} captures preserve a retained draft`, async (app) => {
      const retained = await listenWithRetainedDraft(app, check, failure === 'empty' ? 'typed' : 'paused');
      if (!retained) return;
      let capture = retained.capture;
      for (let index = 0; index < 3; index++) {
        if (failure === 'error') capture._step({ error: 'network' });
        // With no scripted results, the real native-recognition watchdog returns an
        // empty capture. Do not retime it or replace the recorder/recognizer controller.
        const ended = await expect(app, check, `${failure} capture ${index + 1} reaches recovery or stand-down`,
          () => !capture._live && (
            ['paused', 'blocked'].includes(phase(app)) || !visible(app.$('voice-stage'))
            || (phase(app) === 'listening' && app.recognition.current() !== capture)
            || app.model.calls.length > 0
          ), failure === 'empty' ? 25000 : 5000);
        if (!ended) return;
        const saved = await within(loadSession(retained.session.id), 'reading the draft after capture failure');
        const preserved = saved?.turns.length === 1 && !saved.turns[0].answer && !saved.turns[0].skipped
          && saved.draftAnswer === retained.draft && app.$('answer').value === retained.draft
          && app.model.calls.length === 0;
        check(`${failure} capture ${index + 1} never skips, deletes or submits the retained words`,
          preserved, detail(app));
        if (!preserved) return;
        if (index === 2) break;
        if (['paused', 'blocked'].includes(phase(app))) {
          if (!(await expect(app, check, 'stand-down offers a deliberate Resume for the saved draft',
            () => !app.$('b-voice-pause').disabled))) return;
          app.$('b-voice-pause').click();
        } else if (!visible(app.$('voice-stage'))) {
          app.$('handsfree').checked = true;
          app.$('handsfree').dispatchEvent(new app.win.Event('change'));
        }
        capture = await listening(app, check, 'the next capture still belongs to the unanswered draft');
        if (!capture) return;
      }
      check('three failed captures leave the original idea intact',
        app.model.calls.length === 0 && app.$('answer').value === retained.draft, detail(app));
    });
  }

  await withApp(check, 'manual dictation releases and reacquires real microphone tracks', async (app) => {
    const session = await startTypedDraft(app, check);
    if (!session) return;
    for (let index = 0; index < 2; index++) {
      const acquisitions = app.mic.requests;
      const previousTracks = app.mic.tracks().slice();
      app.$('b-mic').click();
      const capture = await expect(app, check, `manual mic tap ${index + 1} acquires a live stream`, () =>
        app.recognition.current() && app.mic.requests === acquisitions + 1
          && app.mic.tracks().some((track) => track.readyState === 'live')
          && app.recognition.current());
      if (!capture) return;
      check('manual dictation does not take over the voice stage or revive an ended track',
        visible(app.$('manual-interview')) && !visible(app.$('voice-stage'))
          && previousTracks.every((track) => track.readyState === 'ended'), detail(app));
      const beforeSpeech = spoken(app).length;
      check('the mode switch is disabled while manual capture owns the microphone',
        app.$('handsfree').disabled);
      app.$('handsfree').checked = true;
      app.$('handsfree').dispatchEvent(new app.win.Event('change'));
      check('a forced mode-switch event cannot start speech over a manual recording',
        !app.$('handsfree').checked && !visible(app.$('voice-stage'))
          && app.recognition.current() === capture && spoken(app).length === beforeSpeech);
      const text = `The words from manual recording ${index + 1} stay available for editing`;
      capture._step({ final: text, confidence: 0.9 });
      app.$('b-mic').click();
      if (!(await expect(app, check, `manual capture ${index + 1} finishes with every acquired track stopped`, () =>
        !visible(app.$('listening')) && app.mic.allStopped() && liveCaptures(app).length === 0
          && app.$('answer').value === text))) return;
      check('hands-free becomes available again after manual capture releases its input',
        !app.$('handsfree').disabled);
      await expect(app, check, `manual capture ${index + 1} remains an unsent durable draft`, async () => {
        const saved = await within(loadSession(session.id), 'reading the manual dictation draft');
        return saved?.draftAnswer === text && !saved.turns[0].answer && app.model.calls.length === 0;
      });
    }
  });

  for (const via of ['button', 'ctrl', 'meta']) {
    await withApp(check, `sending live browser dictation through ${via}`, async (app) => {
      const session = await startTypedDraft(app, check);
      if (!session) return;
      app.$('b-mic').click();
      const capture = await expect(app, check, 'manual recognition owns live input before sending', () =>
        app.recognition.current() && app.mic.tracks().some((track) => track.readyState === 'live')
          && app.recognition.current());
      if (!capture) return;
      const answer = 'A pocket notebook reminds me of the people I meet at conferences.';
      capture._step({ final: answer, confidence: 0.9 });
      if (!(await expect(app, check, 'the current spoken words are visible before Send',
        () => app.$('answer').value === answer))) return;
      if (via === 'button') app.$('b-send').click();
      else app.$('answer').dispatchEvent(new app.win.KeyboardEvent('keydown', {
        key: 'Enter', ctrlKey: via === 'ctrl', metaKey: via === 'meta',
        bubbles: true, cancelable: true,
      }));
      const call = await expect(app, check, 'manual Send commits the visible words exactly once',
        () => app.model.calls.length === 1 && app.model.calls[0]);
      if (!call) return;
      const saved = await within(loadSession(session.id), 'reading the live dictated submission');
      check('sending visible dictation preserves its voice provenance without a transcription request',
        saved.turns[0].answer === answer && saved.turns[0].answerSource === 'voice'
          && app.requests.length === 0, JSON.stringify(saved.turns[0]));
      if (!(await expect(app, check, 'Send releases recognition and microphone before the next question',
        () => liveCaptures(app).length === 0 && app.mic.allStopped()
          && !visible(app.$('listening')) && !app.$('handsfree').disabled))) return;
      call.release();
      await expect(app, check, 'the next question is editable without an orphaned listener',
        () => app.$('question').textContent === NEXT_QUESTION && !app.$('answer').disabled
          && app.$('answer').value === '' && !visible(app.$('listening')) && app.mic.allStopped());
    });
  }

  for (const move of ['stay', 'navigate', 'advance', 'send-live']) {
    await withApp(check, `manual recorder loss: ${move}`,
      async (app) => {
        const transcript = 'saved before the microphone ended';
        const apiKey = 'gsk-synthetic-manual-input-loss-key-never-sent';
        const endpoint = 'https://api.groq.com/openai/v1/audio/transcriptions';
        app.$('stt').value = 'groq';
        app.$('stt').dispatchEvent(new app.win.Event('change'));
        app.$('sttkey').value = apiKey;
        const session = await startTypedDraft(app, check);
        if (!session) return;
        const NativeRecorder = app.win.MediaRecorder;
        const analyser = app.win.AnalyserNode.prototype;
        const readSamples = analyser.getByteTimeDomainData;
        const blockedFetch = app.win.fetch;
        const gate = createSilenceGate();
        const recorders = [];
        const seen = { bytes: 0, samples: 0, request: null, responseRead: false };
        let releaseTranscript;
        let captureOperation;
        const responseReady = new Promise((resolve) => { releaseTranscript = resolve; });
        app.win.MediaRecorder = class extends NativeRecorder {
          constructor(...args) {
            super(...args);
            recorders.push(this);
            this.addEventListener('dataavailable', (event) => { seen.bytes += event.data.size; });
          }
        };
        analyser.getByteTimeDomainData = function (buffer) {
          readSamples.call(this, buffer);
          if (recorders.some((recorder) => recorder.state === 'recording')) {
            seen.samples++;
            gate.push(rmsOf(buffer), app.win.performance.now());
          }
        };
        app.win.fetch = async (input, options) => {
          const url = String(input?.url || input);
          if (url !== endpoint) return blockedFetch(input, options);
          app.requests.push(url);
          seen.request = {
            method: options.method,
            authorization: new app.win.Headers(options.headers).get('authorization'),
            audio: options.body.get('file'),
            signal: options.signal,
          };
          await responseReady;
          const response = new app.win.Response(JSON.stringify({ text: transcript }), {
            status: 200, headers: { 'content-type': 'application/json' },
          });
          const json = response.json.bind(response);
          response.json = async () => {
            const body = await json();
            seen.responseRead = true;
            return body;
          };
          return response;
        };
        try {
          const micButton = app.$('b-mic');
          const handler = micButton.onclick;
          micButton.onclick = function (event) {
            captureOperation = Promise.resolve(handler.call(this, event));
            return captureOperation;
          };
          try { micButton.click(); } finally { micButton.onclick = handler; }
          if (!(await expect(app, check, 'manual recording has real speech evidence and more than 1600 captured bytes',
            () => recorders.length === 1 && recorders[0].state === 'recording'
              && seen.samples > 0 && gate.state().heardSpeech && seen.bytes > 1600, 8000))) return;
          check('input-loss precondition comes from native audio, not a fabricated transcript',
            gate.state().heardSpeech && seen.bytes > 1600 && app.requests.length === 0,
            `${seen.samples} real analyser samples; ${seen.bytes} recorded bytes`);
          if (move === 'send-live') {
            const replacement = 'I choose this typed answer instead of the unfinished recording.';
            app.$('answer').value = replacement;
            app.$('answer').dispatchEvent(new app.win.Event('input'));
            app.$('b-send').click();
            const call = await expect(app, check, 'a typed answer can be sent while real audio is still recording',
              () => app.model.calls.length === 1 && app.model.calls[0]);
            if (!call) return;
            if (!(await expect(app, check, 'Send releases the active recorder without waiting for Stop',
              () => app.mic.allStopped() && recorders[0].state === 'inactive'
                && !visible(app.$('listening')) && !app.$('handsfree').disabled))) return;
            await within(captureOperation, 'cancelling the superseded manual recorder');
            const saved = await within(loadSession(session.id), 'reading the replacement answer');
            check('discarded manual audio creates no transcription request and preserves the typed answer',
              app.requests.length === 0 && seen.request === null && saved.turns[0].answer === replacement
                && saved.turns[0].answerSource === 'typed', detail(app));
            call.release();
            await expect(app, check, 'the next question has no leftover recorder or draft',
              () => app.$('question').textContent === NEXT_QUESTION && !app.$('answer').disabled
                && app.$('answer').value === '' && app.mic.allStopped() && !visible(app.$('listening')));
            return;
          }
          app.expectedRequestUrl = endpoint;
          app.expectedRequests = 1;
          app.mic.tracks().forEach((track) => track.stop());
          if (!(await expect(app, check, 'ended manual input sends its retained audio to the fake transcription endpoint',
            () => seen.request !== null && app.mic.allStopped()))) return;
          check('the intercepted transcription is an authenticated multipart POST of real retained audio',
            seen.request.method === 'POST' && seen.request.authorization === `Bearer ${apiKey}`
              && seen.request.audio instanceof app.win.Blob && seen.request.audio.size > 1600,
            `${seen.request.method}; ${seen.request.audio?.size} audio bytes`);

          let current = null;
          const newDraft = 'An unrelated notebook idea must not receive the earlier microphone result.';
          if (move === 'navigate') {
            app.$('b-library').click();
            if (!(await expect(app, check, 'navigation can leave the interrupted capture while transcription is held',
              () => visible(app.$('panel-library'))))) return;
            app.$('b-library-new').click();
            if (!(await expect(app, check, 'a new idea can start before the old transcription reply',
              () => visible(app.$('panel-setup'))))) return;
            app.$('stt').value = 'off';
            app.$('stt').dispatchEvent(new app.win.Event('change'));
            current = await startTypedDraft(app, check, newDraft);
            if (!current) return;
            check('the new idea has a different session identity', current.id !== session.id);
          } else if (move === 'advance') {
            const replacement = 'A pocket notebook helps me remember the people I meet at conferences.';
            app.$('answer').value = replacement;
            app.$('answer').dispatchEvent(new app.win.Event('input'));
            app.$('b-send').click();
            const call = await expect(app, check, 'a typed replacement advances while the old transcription is held',
              () => app.model.calls.length === 1 && app.model.calls[0]);
            if (!call) return;
            check('Send also cancels an already-started transcription',
              seen.request.signal.aborted);
            call.release();
            current = await expect(app, check, 'the same interview reaches its next unanswered question', async () => {
              const saved = await within(loadSession(session.id), 'reading the advanced interview');
              return saved?.turns.length === 2 && saved.turns[0].answer === replacement
                && !saved.turns[1].answer && !app.$('answer').disabled
                && app.$('question').textContent === NEXT_QUESTION && saved;
            });
            if (!current) return;
            app.$('answer').value = newDraft;
            app.$('answer').dispatchEvent(new app.win.Event('input'));
            if (!(await expect(app, check, 'the next question owns a durable draft before the old reply',
              async () => (await within(loadSession(session.id), 'reading the next-question draft'))
                ?.draftAnswer === newDraft))) return;
          }
          releaseTranscript();
          if (!(await expect(app, check, 'the deferred transcription response is actually consumed',
            () => seen.responseRead))) return;
          await within(captureOperation, 'settling the manual input-loss handler');
          if (move === 'navigate') {
            const saved = await within(loadSession(current.id), 'reading the idea after a stale transcription');
            check('late input-loss text and error cannot overwrite the new active idea',
              visible(app.$('manual-interview')) && app.$('answer').value === newDraft
                && saved.draftAnswer === newDraft && !saved.turns[0].answer
                && app.$('err').textContent === '' && app.model.calls.length === 0, detail(app));
          } else if (move === 'advance') {
            const saved = await within(loadSession(session.id), 'reading the next question after the old reply');
            check('late input-loss text and error cannot overwrite a later question in the same interview',
              visible(app.$('manual-interview')) && app.$('answer').value === newDraft
                && saved?.draftAnswer === newDraft && saved.turns.length === 2
                && saved.turns[0].answer === current.turns[0].answer && !saved.turns[1].answer
                && app.$('err').textContent === '' && app.model.calls.length === 1, detail(app));
          } else {
            const saved = await within(loadSession(session.id), 'reading the retained manual input-loss draft');
            check('manual input loss keeps the transcription in both the textarea and persistent unsent draft',
              app.$('answer').value === transcript && saved?.draftAnswer === transcript
                && saved.turns.length === 1 && !saved.turns[0].answer && !saved.turns[0].skipped
                && app.model.calls.length === 0, detail(app));
            check('manual input loss still reports the microphone error after retaining the draft',
              visible(app.$('err')) && /microphone input ended/i.test(app.$('err').textContent),
              app.$('err').textContent);
          }
          check('input-loss handling releases all microphone tracks and stops recording',
            app.mic.allStopped() && recorders.every((recorder) => recorder.state === 'inactive')
              && !visible(app.$('listening')) && app.model.calls.length === (move === 'advance' ? 1 : 0),
            detail(app));
        } finally {
          try {
            releaseTranscript();
            if (recorders.some((recorder) => recorder.state === 'recording')) app.$('b-settings').click();
            if (captureOperation) await within(captureOperation, 'closing the manual recorder regression');
          } finally {
            analyser.getByteTimeDomainData = readSamples;
            app.win.MediaRecorder = NativeRecorder;
            app.win.fetch = blockedFetch;
          }
        }
      });
  }

  const unfinished = setDraftAnswer(recorded, TYPED, 20);
  await withApp(check, 'remembered hands-free with Dictation Off resumes safely', async (app) => {
    app.$('stt').value = 'off';
    app.$('stt').dispatchEvent(new app.win.Event('change'));
    check('the resume precondition combines remembered hands-free with Dictation Off',
      loadPrefs().handsFree === true && app.$('stt').value === 'off');
    app.$('b-library').click();
    const card = await expect(app, check, 'the unfinished idea can be selected with dictation disabled', () =>
      visible(app.$('panel-library'))
        && app.$('library-rows').querySelector(`[data-session-id="${unfinished.id}"]`));
    if (!card) return;
    [...card.querySelectorAll('button')].find((button) => button.textContent === 'Continue').click();
    const available = () => (visible(app.$('manual-interview')) && !visible(app.$('voice-stage')))
      || (visible(app.$('voice-stage')) && phase(app) === 'blocked'
        && app.$('voice-detail').textContent.trim() && !app.$('b-voice-exit').disabled);
    if (!(await expect(app, check, 'Dictation Off resumes manually or explicitly blocked, never stuck Starting',
      available))) return;
    check('disabled dictation cannot silently acquire a microphone or narrate the saved interview',
      await remains(() => available() && captureCount(app) === 0 && app.mic.requests === 0
        && spoken(app).length === 0 && app.model.calls.length === 0), detail(app));
    if (visible(app.$('voice-stage'))) app.$('b-voice-exit').click();
    await expect(app, check, 'the unfinished draft remains editable after the safe resume', async () =>
      visible(app.$('manual-interview')) && !app.$('answer').disabled && app.$('answer').value === TYPED
        && (await within(loadSession(unfinished.id), 'reading the dictation-off idea'))?.draftAnswer === TYPED);
  }, { seed: unfinished, remembered: true });

  await withApp(check, 'delayed wake locks are deduplicated and released across Resume', async (app) => {
    const wake = deferredWakeLocks(app.win);
    app.wakeLocks = wake;
    if (!(await startVoice(app, check)) || !(await listening(app, check))) return;
    if (!(await expect(app, check, 'the initial screen wake-lock request is observable',
      () => wake.requests.length > 0))) return;
    const initial = wake.requests.slice();
    initial.forEach((entry) => entry.complete());
    if (!(await expect(app, check, 'initial wake locks are adopted before pausing',
      () => initial.every((entry) => entry.adopted)))) return;
    app.$('b-voice-pause').click();
    if (!(await expect(app, check, 'Pause releases every acquired screen wake lock', () =>
      phase(app) === 'paused' && !app.$('b-voice-pause').disabled
        && initial.every((entry) => entry.lock.released)))) return;

    let before = wake.requests.length;
    app.$('b-voice-pause').click();
    if (!(await listening(app, check, 'Resume completes setup while its wake-lock promise remains pending'))) return;
    check('Resume and its asynchronous voice setup share one pending wake-lock request',
      await remains(() => wake.requests.length === before + 1
        && !wake.requests.at(-1).resolved), detail(app));
    const latePause = wake.requests.at(-1);
    app.$('b-voice-pause').click();
    if (!(await expect(app, check, 'Pause completes without waiting for an unresolved wake lock', () =>
      phase(app) === 'paused' && !app.$('b-voice-pause').disabled))) return;
    latePause.complete();
    if (!(await expect(app, check, 'a wake lock returned after Pause is immediately released',
      () => latePause.lock.released))) return;

    before = wake.requests.length;
    app.$('b-voice-pause').click();
    if (!(await listening(app, check, 'the next explicit Resume creates a fresh pending wake-lock request'))) return;
    check('the next Resume is also deduplicated', wake.requests.length === before + 1, detail(app));
    const lateExit = wake.requests.at(-1);
    app.$('b-voice-exit').click();
    if (!(await expect(app, check, 'Exit restores manual mode while the wake lock is still pending', () =>
      !visible(app.$('voice-stage')) && visible(app.$('manual-interview'))))) return;
    lateExit.complete();
    if (!(await expect(app, check, 'a wake lock returned after Exit is immediately released',
      () => lateExit.lock.released))) return;

    app.$('handsfree').checked = true;
    app.$('handsfree').dispatchEvent(new app.win.Event('change'));
    if (!(await listening(app, check, 'explicit voice reentry can acquire another screen lock'))) return;
    const acquired = wake.requests.at(-1);
    acquired.complete();
    if (!(await expect(app, check, 'the final screen lock is genuinely acquired before Exit',
      () => acquired.adopted && !acquired.lock.released))) return;
    app.$('b-voice-exit').click();
    if (!(await expect(app, check, 'Exit releases the acquired lock and all older late locks', () =>
      !visible(app.$('voice-stage')) && wake.requests.every((entry) => entry.lock.released)))) return;
    const requests = wake.requests.length;
    check('retired wake-lock continuations cannot acquire another lock or restart capture',
      await remains(() => wake.requests.length === requests && app.mic.allStopped()
        && liveCaptures(app).length === 0 && app.model.calls.length === 0), detail(app));
  });

  const verifiedSpeech = withTts(withCreds(emptyKeyring(), 'artifact', {}), {
    kind: 'openai', apiKey: 'sk-synthetic-encrypted-speech-key-never-sent',
    voice: 'marin', verified: true,
  });
  await withApp(check, 'legacy speech verification does not imply hosted consent', async (app) => {
    check('a verified encrypted speech record still defaults to hosted output disallowed',
      loadPrefs().hostedSpeechAllowed === false && !app.$('tts-consent').checked);
    if (!(await startVoice(app, check))) return;
    check('a legacy verified key cannot automatically select hosted speech',
      app.requests.length === 0 && /browser/i.test(app.$('voice-backend').textContent), detail(app));
  }, { credentials: verifiedSpeech, remembered: true });

  for (const action of ['withdraw consent', 'switch to browser']) {
    await withApp(check, `verified hosted speech ${action} persists without Start`, async (app) => {
      check('the synthetic encrypted record begins verified and explicitly allowed',
        app.$('speech').value === 'openai' && app.$('tts-consent').checked
          && loadPrefs().hostedSpeechAllowed === true);
      if (action === 'withdraw consent') {
        app.$('tts-consent').checked = false;
        app.$('tts-consent').dispatchEvent(new app.win.Event('change'));
      } else {
        app.$('speech').value = 'browser';
        app.$('speech').dispatchEvent(new app.win.Event('change'));
      }
      check('hosted permission is revoked immediately in device preferences',
        loadPrefs().hostedSpeechAllowed === false, app.$('speech-check').textContent);
      if (!(await expect(app, check, 'revocation updates encrypted credentials without starting an interview',
        async () => {
          const credentials = await within(loadCredentials(), 'reading revoked synthetic speech credentials');
          return credentials?.tts.verified === false
            && (action !== 'switch to browser' || credentials.tts.kind === 'browser');
        }))) return;
      check('settings-only revocation made no provider request or new session',
        app.requests.length === 0 && (await within(listSessions(), 'checking settings-only revocation')).length === 0);

      // Nothing is capturing or previewing in this settings-only frame. Restore the
      // single-window fixture before loading a new document over the SAME persisted data.
      app.fake.restore();
      app.frame.hidden = true;
      await withApp(check, `reload after ${action} cannot re-enable hosted speech`, async (reloaded) => {
        check('the new document reloads the persisted revocation, not a fresh default keyring',
          loadPrefs().hostedSpeechAllowed === false && !reloaded.$('tts-consent').checked
            && (action !== 'switch to browser' || reloaded.$('speech').value === 'browser'));
        const credentials = await within(loadCredentials(), 'reading the reloaded revoked keyring');
        check('the synthetic speech key is retained but its verification remains revoked',
          credentials?.tts.apiKey === verifiedSpeech.tts.apiKey && !credentials.tts.verified);
        if (!(await startVoice(reloaded, check))) return;
        check('reloaded voice uses browser speech without an automatic hosted request',
          reloaded.requests.length === 0 && /browser/i.test(reloaded.$('voice-backend').textContent),
          detail(reloaded));
      }, { reuseStorage: true });
    }, { credentials: verifiedSpeech, preferences: { handsFree: true, hostedSpeechAllowed: true } });
  }

  await withApp(check, 'consent withdrawal reports a preferences-only persistence failure', async (app) => {
    check('hosted speech starts with a verified keyring and an independently allowed preference',
      app.$('tts-consent').checked && loadPrefs().hostedSpeechAllowed === true);
    const storage = app.win.Storage.prototype;
    const setItem = storage.setItem;
    let rejectedWrites = 0;
    storage.setItem = function (key, value) {
      if (this === app.win.localStorage && key === 'ideaforge.prefs') {
        rejectedWrites++;
        throw new app.win.DOMException('Synthetic preferences-only quota failure.', 'QuotaExceededError');
      }
      return setItem.call(this, key, value);
    };
    try {
      app.$('tts-consent').checked = false;
      app.$('tts-consent').dispatchEvent(new app.win.Event('change'));
      if (!(await expect(app, check, 'IndexedDB revocation succeeds even though writing preferences failed', async () => {
        const credentials = await within(loadCredentials(), 'reading the independently revoked keyring');
        return rejectedWrites > 0 && credentials?.tts.verified === false
          && credentials.tts.apiKey === verifiedSpeech.tts.apiKey;
      }))) return;
      check('only the permission preference remained unchanged, not the saved keyring',
        loadPrefs().hostedSpeechAllowed === true && rejectedWrites > 0);
      await expect(app, check, 'the speech status reports partial persistence rather than normal saved success', () =>
        /permission preference could not be updated/i.test(app.$('speech-check').textContent)
          && !/^Hosted speech is disabled\. A new check and preview/.test(app.$('speech-check').textContent));
      if (!(await startVoice(app, check))) return;
      check('the in-memory revocation and unverified keyring prevent hosted speech despite the stale preference',
        loadPrefs().hostedSpeechAllowed === true && app.requests.length === 0
          && /browser/i.test(app.$('voice-backend').textContent), detail(app));
    } finally {
      storage.setItem = setItem;
    }
  }, { credentials: verifiedSpeech, preferences: { hostedSpeechAllowed: true } });

  await withApp(check, 'hosted speech opt-in gate', async (app) => {
    check('hosted speech defaults off without consent or saved verification',
      app.$('speech').value === 'browser' && !app.$('tts-consent').checked
        && app.requests.length === 0);
    app.$('speech').value = 'openai';
    app.$('speech').dispatchEvent(new app.win.Event('change'));
    app.$('ttskey').value = 'sk-probe-not-a-real-speech-key';
    app.$('ttskey').dispatchEvent(new app.win.Event('input'));
    app.$('b-speech-preview').click();
    check('an unconsented preview cannot issue a speech request',
      await remains(() => app.requests.length === 0));
    app.$('tts-consent').checked = true;
    app.$('tts-consent').dispatchEvent(new app.win.Event('change'));
    if (!(await startVoice(app, check))) return;
    check('consent alone cannot automatically enable unverified hosted speech',
      app.requests.length === 0 && /browser/i.test(app.$('voice-backend').textContent),
      detail(app));
    app.$('b-voice-exit').click();
    if (!(await expect(app, check, 'the unverified browser fallback exits cleanly', () =>
      !visible(app.$('voice-stage')) && visible(app.$('manual-interview'))))) return;
    app.$('b-settings').click();
    if (!(await expect(app, check, 'speech settings reopen for an explicit preview', () =>
      visible(app.$('panel-setup'))))) return;
    app.$('speech').value = 'openai';
    app.$('speech').dispatchEvent(new app.win.Event('change'));
    app.$('tts-consent').checked = true;
    app.$('tts-consent').dispatchEvent(new app.win.Event('change'));
    app.expectedRequests = 1;
    app.$('b-speech-preview').click();
    if (!(await expect(app, check, 'only an explicit consented preview reaches the fake speech endpoint', () =>
      app.requests.length === 1 && /failed|did not finish/i.test(app.$('speech-check').textContent)))) return;
    if (!(await startVoice(app, check))) return;
    check('a failed consented preview still cannot authorize automatic hosted speech',
      app.requests.length === 1 && /browser/i.test(app.$('voice-backend').textContent),
      detail(app));
  });
}
