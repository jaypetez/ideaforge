import { createSession, setPending, setSynthesis } from '../../src/core/session.js';
import { seedTurn, submitAnswer } from '../../src/runtime/turn.js';
import { deleteSession, listSessions, loadSession, saveSession } from '../../src/store/sessions.js';
import { clearCredentials, emptyKeyring, saveCredentials, withCreds } from '../../src/store/secrets.js';
import { PREFS_KEY, INFERENCE_KEY } from '../../src/store/prefs.js';
import { createInferenceConsent } from '../../src/store/inference-consent.js';
import { acknowledgeInference } from './fixtures/inference.js';
import { expect, holdCredentialCommit, startVoice, withApp } from './voice-stage.browser.mjs';

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function until(predicate, label) {
  for (const end = performance.now() + 5000; performance.now() < end;) {
    if (await predicate()) return;
    await sleep(25);
  }
  throw new Error(`Inference consent did not reach ${label}`);
}

async function visit(run, preferences = {}, seed = null) {
  const held = new Set((await listSessions()).map((session) => session.id));
  const previous = localStorage.getItem(PREFS_KEY);
  const previousConsent = localStorage.getItem(INFERENCE_KEY);
  for (const registration of await navigator.serviceWorker.getRegistrations()) await registration.unregister();
  for (const key of await caches.keys()) await caches.delete(key);
  await clearCredentials();
  await saveCredentials(withCreds(emptyKeyring(), 'groq', { apiKey: 'scripted-consent-not-a-key' }));
  localStorage.setItem(PREFS_KEY, JSON.stringify(preferences));
  localStorage.removeItem(INFERENCE_KEY);
  if (seed) await saveSession(seed);
  const frame = document.createElement('iframe');
  frame.width = 390;
  frame.height = 844;
  frame.style.cssText = 'position:absolute;left:-10000px';
  const loaded = new Promise((resolve, reject) => {
    frame.onload = resolve;
    frame.onerror = () => reject(new Error('Inference consent iframe failed'));
  });
  frame.src = '/index.html';
  document.body.append(frame);
  await loaded;
  const win = frame.contentWindow;
  let doc = frame.contentDocument;
  const $ = (id) => doc.getElementById(id);
  const requests = [];
  let microphones = 0;
  let recognitions = 0;
  const errors = [];
  const network = {
    holdNext: false, waiting: [],
    release(invalid = false) {
      for (const reply of this.waiting.splice(0)) reply(invalid);
    },
  };
  function install() {
    doc = frame.contentDocument;
    win.navigator.mediaDevices.getUserMedia = async () => {
      microphones++;
      throw new win.DOMException('No actual microphone in consent tests', 'NotAllowedError');
    };
    win.SpeechRecognition = class {
      start() {
        recognitions++;
        win.queueMicrotask(() => this.onerror?.({ error: 'not-allowed' }));
      }
      abort() {}
      stop() {}
    };
    win.webkitSpeechRecognition = win.SpeechRecognition;
    win.fetch = async (url, init = {}) => {
      const request = { url: String(url), method: init.method || 'GET', body: init.body || '' };
      requests.push(request);
      if (request.method !== 'POST') {
        return new win.Response(JSON.stringify({ data: [{ id: 'scripted-model' }] }));
      }
      const prompt = JSON.parse(init.body).messages.map((message) => message.content).join('\n');
      const target = /Target dimension: (\w+)/.exec(prompt)?.[1] || 'users';
      const moves = /"move": one of (\[[^\n]+\])/.exec(prompt)?.[1];
      const json = prompt.includes('=== COVERAGE AT WRAP ===')
        ? { title: 'Private notebook', prompt: 'Build a conference notebook.', assumptions: [], open_questions: [] }
        : { question: 'Which customer role would use the notebook during a conference?',
          dimension: target, move: moves ? JSON.parse(moves)[0] : 'concretize',
          chips: [], new_facts: [], coverage: {} };
      let invalid = false;
      if (network.holdNext) {
        network.holdNext = false;
        invalid = await new Promise((resolve) => network.waiting.push(resolve));
      }
      return new win.Response(JSON.stringify({
        choices: [{ message: { content: JSON.stringify(invalid ? {} : json) } }],
      }));
    };
    win.addEventListener('error', (event) => errors.push(event.message));
    win.addEventListener('unhandledrejection', (event) => {
      errors.push(String(event.reason));
      event.preventDefault();
    });
  }
  async function ready() {
    await until(() => $('version')?.textContent, 'boot');
    if ($('panel-setup').hidden) {
      $('b-nav-new').click();
      await until(() => !$('panel-setup').hidden, 'Start');
    }
  }
  try {
    install();
    await ready();
    await run({
      win, doc, $, requests, errors, network,
      change: (id, value, event = 'input') => {
        $(id).value = value;
        $(id).dispatchEvent(new win.Event(event, { bubbles: true }));
      },
      settings: async () => {
        $('b-settings').click();
        await until(() => !$('panel-setup').hidden
          && $('panel-setup').dataset.view === 'settings', 'Settings');
      },
      reload: async () => {
        const reloaded = new Promise((resolve) => { frame.onload = resolve; });
        win.location.reload();
        await reloaded;
        install();
        await ready();
      },
      captures: () => microphones + recognitions,
      newSessions: async () => (await listSessions()).filter((session) => !held.has(session.id)),
      forceStart: async (voice = false) => {
        // Dispatch also exercises the handler behind a disabled button.
        $(voice ? 'b-start-voice' : 'b-start').dispatchEvent(new win.MouseEvent('click', { bubbles: true }));
        await until(() => !$('err').hidden || !$('panel-interview').hidden, 'start outcome');
        await sleep(200);
      },
    });
  } finally {
    network.release();
    frame.remove();
    for (const session of await listSessions()) if (!held.has(session.id)) await deleteSession(session.id);
    await clearCredentials();
    if (previous === null) localStorage.removeItem(PREFS_KEY);
    else localStorage.setItem(PREFS_KEY, previous);
    if (previousConsent === null) localStorage.removeItem(INFERENCE_KEY);
    else localStorage.setItem(INFERENCE_KEY, previousConsent);
    for (const registration of await navigator.serviceWorker.getRegistrations()) await registration.unregister();
  }
}

export default async function run(check) {
  await import('./fixtures/fake-voice.js');
  for (const [kind, finish] of [['browser', 'exit'], ['groq', 'exit'], ['groq', 'withdrawal']]) {
    await withApp(check, `retired provider rebuild preserves voice ownership: ${kind}/${finish}`, async (app) => {
      app.$('stt').value = kind;
      app.$('stt').dispatchEvent(new app.win.Event('change'));
      app.$('sttkey').value = 'gsk-synthetic-consent-recorder-not-a-key';
      app.expectedRequestUrl = 'https://api.groq.com/openai/v1/audio/transcriptions';
      app.win.fetch = async (input) => {
        const url = String(input?.url || input);
        if (url !== app.expectedRequestUrl) throw new Error(`Unexpected recorder request: ${url}`);
        app.requests.push(url);
        app.expectedRequests++;
        return new app.win.Response(JSON.stringify({ text: '' }));
      };
      const session = await startVoice(app, check);
      if (!session || !(await expect(app, check, 'the original granted input starts listening',
        () => app.$('voice-stage').dataset.phase === 'listening'
          && app.mic.tracks().some((track) => track.readyState === 'live')))) return;
      app.mic.mute(true);
      const grant = createInferenceConsent();
      grant.select('artifact', 'artifact');
      grant.withdraw();
      if (!(await expect(app, check, 'another tab withdraws and fully stops the first input',
        () => app.$('voice-stage').dataset.phase === 'blocked' && app.mic.allStopped()
          && !app.$('b-voice-pause').disabled && !app.$('inference-consent').checked))) return;
      grant.accept('artifact');
      if (!(await expect(app, check, 'a fresh grant enables deliberate Resume',
        () => app.$('inference-consent').checked && !app.$('b-voice-pause').disabled))) return;
      const commit = holdCredentialCommit(app.win);
      let resumed;
      try {
        const button = app.$('b-voice-pause');
        const handler = button.onclick;
        button.onclick = function (event) {
          resumed = Promise.resolve(handler.call(this, event));
          return resumed;
        };
        try { button.click(); } finally { button.onclick = handler; }
        if (!(await expect(app, check, 'Resume holds its actual provider-rebuild credential completion',
          commit.held))) return;
        app.$('b-voice-exit').click();
        if (!(await expect(app, check, 'Exit retires the held Resume before manual controls return',
          () => app.$('manual-interview').getClientRects().length > 0
            && !app.$('handsfree-wrap').hidden && app.mic.allStopped()))) return;
        app.$('handsfree').checked = true;
        app.$('handsfree').dispatchEvent(new app.win.Event('change'));
        if (!(await expect(app, check, 'the same idea acquires one newer input before the old rebuild settles',
          () => app.$('voice-stage').dataset.phase === 'listening'
            && app.mic.tracks().filter((track) => track.readyState === 'live').length === 1))) return;
        app.mic.mute(true);
        const requests = app.mic.requests;
        const recognitions = app.fake.recognition.sessions.length;
        const active = app.mic.tracks().filter((track) => track.readyState === 'live');
        commit.release();
        await resumed;
        await sleep(300);
        check('a retired Resume neither replaces nor reacquires the newer microphone',
          app.mic.requests === requests && app.fake.recognition.sessions.length === recognitions
            && active.every((track) => track.readyState === 'live'),
          `microphone ${requests} -> ${app.mic.requests}; recognition ${recognitions} -> ${app.fake.recognition.sessions.length}`);
        if (finish === 'exit') app.$('b-voice-exit').click();
        else grant.withdraw();
        await expect(app, check, `${finish} closes every input after the retired rebuild completes`,
          () => app.mic.allStopped(), 1000);
        check('retired Resume preserves the original single unanswered idea',
          (await listSessions()).length === 1 && (await loadSession(session.id)).turns.length === 1
            && app.model.calls.length === 0);
      } finally {
        commit.restore();
      }
    });
  }

  for (const voice of [false, true]) {
    await visit(async ({ $, captures, requests, newSessions, forceStart, errors }) => {
      check(`${voice ? 'Voice' : 'Type'}: ready setup has a provider-named unchecked inference agreement`,
        $('inference-consent')?.checked === false
          && /Groq/.test($('inference-disclosure')?.textContent || ''));
      await forceStart(voice);
      check(`${voice ? 'Voice' : 'Type'}: missing agreement creates no session, capture or inference request`,
        (await newSessions()).length === 0 && captures() === 0 && requests.length === 0,
        JSON.stringify({ sessions: (await newSessions()).length, captures: captures(), requests }));
      check(`${voice ? 'Voice' : 'Type'}: refusal is explicit and never labelled as checklist fallback`,
        /agree|agreement|consent/i.test($('err').textContent)
          && !/checklist|falling back/i.test($('err').textContent));
      check(`${voice ? 'Voice' : 'Type'}: consent refusal has no uncaught error`, errors.length === 0);
    });
  }

  await visit(async ({ $, requests, forceStart, newSessions, captures }) => {
    $('b-settings').click();
    $('b-check').click();
    await until(() => /checked this visit/.test($('connect-status').textContent), 'optional connection check');
    check('checking the connection without inference agreement sends no interview',
      requests.length === 1 && requests[0].method === 'GET' && /\/models$/.test(requests[0].url));
    check('key/connection success does not check the inference agreement', $('inference-consent')?.checked === false);
    $('b-nav-new').click();
    await until(() => !$('panel-setup').hidden && $('panel-setup').dataset.view === 'start', 'return to Start');
    await forceStart();
    check('a successful check still cannot authorize an interview or microphone',
      (await newSessions()).length === 0 && captures() === 0 && requests.length === 1);
  }, { hostedSpeechAllowed: true, handsFree: true, trigger: 'finished' });

  await allowedAndReload(check);
  await changedDestinations(check);
  await failedStorage(check);
  await anotherTabsWithdrawal(check);
  await retiredRequest(check);
  await retainedIdeas(check);
}

async function allowedAndReload(check) {
  await visit(async ({ win, $, requests, newSessions, captures, forceStart, reload }) => {
    await acknowledgeInference(win);
    const grant = JSON.parse(win.localStorage.getItem(INFERENCE_KEY)).inferenceGrant;
    await reload();
    check('a persisted affirmative agreement survives an actual page reload',
      $('inference-consent').checked
        && JSON.parse(win.localStorage.getItem(INFERENCE_KEY)).inferenceGrant === grant);
    await win.document.fonts.ready;
    await Promise.all($('panel-setup').getAnimations().map((animation) => animation.finished));
    const navigation = win.document.querySelector('.bar-right').getBoundingClientRect().top;
    const actions = ['b-start-voice', 'b-start'].map((id) => {
      const { top, bottom, height } = $(id).getBoundingClientRect();
      return { id, top, bottom, height };
    });
    check('ready390x844 retains both 64px start actions above navigation beside the mandatory disclosure',
      actions.every(({ top, bottom, height }) => top >= 0 && bottom <= navigation && height >= 64),
      JSON.stringify({ actions, navigation }));
    await forceStart();
    check('agreed Type starts one idea without borrowing microphone permission',
      (await newSessions()).length === 1 && captures() === 0 && requests.length === 0);
    $('answer').value = 'A private notebook for conference organizers to record attendee names and follow-up commitments.';
    $('answer').dispatchEvent(new win.Event('input', { bubbles: true }));
    $('b-send').click();
    await until(() => /Which customer role/.test($('question').textContent), 'authorized model question');
    check('an agreed answer reaches the selected model and produces a real model question',
      requests.length === 1 && requests[0].method === 'POST'
        && requests[0].body.includes('private notebook')
        && !/checklist/.test($('turnline').textContent),
      JSON.stringify(requests.map(({ url, method }) => ({ url, method }))));
  });
  await visit(async ({ win, $, newSessions, captures, forceStart }) => {
    await acknowledgeInference(win);
    await forceStart(true);
    check('agreeing permits an explicit Voice start but does not grant microphone permission',
      (await newSessions()).length === 1 && captures() > 0
        && !$('voice-stage').hidden);
  });
  await visit(async ({ win, $, settings, reload, forceStart, newSessions, captures, requests }) => {
    await acknowledgeInference(win);
    await settings();
    $('inference-consent').click();
    await reload();
    await forceStart(true);
    check('withdrawal remains denied after reload without a new session, microphone or model call',
      !$('inference-consent').checked && (await newSessions()).length === 0
        && captures() === 0 && requests.length === 0);
  });
}

async function changedDestinations(check) {
  await visit(async ({ win, $, change, settings, forceStart, newSessions, requests }) => {
    await acknowledgeInference(win);
    await settings();
    change('model', 'a-different-question-model');
    change('wrapmodel', 'a-different-wrap-model');
    check('model-only changes retain the existing inference agreement', $('inference-consent').checked);
    change('provider', 'openai', 'change');
    change('provider', 'groq', 'change');
    check('switching provider kind and back never revives an old agreement', !$('inference-consent').checked);
    $('b-nav-new').click();
    await until(() => $('panel-setup').dataset.view === 'start', 'changed provider Start');
    await forceStart();
    check('switch-back denial is a request boundary, not just an unchecked control',
      (await newSessions()).length === 0 && requests.length === 0);
  });
  await visit(async ({ win, $, change, settings }) => {
    await settings();
    change('provider', 'custom', 'change');
    change('baseurl', 'http://127.0.0.1:11993/v1/');
    change('model', 'scripted-model');
    await acknowledgeInference(win);
    const grant = JSON.parse(win.localStorage.getItem(INFERENCE_KEY)).inferenceGrant;
    change('baseurl', 'http://127.0.0.1:11993/v1');
    change('model', 'another-model');
    check('canonical local address spelling and model-only edits retain the grant',
      $('inference-consent').checked
        && JSON.parse(win.localStorage.getItem(INFERENCE_KEY)).inferenceGrant === grant);
    change('baseurl', 'http://127.0.0.1:11994/v1');
    change('baseurl', 'http://127.0.0.1:11993/v1');
    check('changing a validated local destination and back requires fresh agreement',
      !$('inference-consent').checked
        && /127\.0\.0\.1:11993/.test($('inference-disclosure').textContent));
    change('provider', 'ollama', 'change');
    change('baseurl', 'http://127.0.0.1:11993/v1');
    change('model', 'scripted-model');
    await acknowledgeInference(win);
    const localGrant = JSON.parse(win.localStorage.getItem(INFERENCE_KEY)).inferenceGrant;
    change('baseurl', '');
    check('clearing a preset-backed local field does not invent an agreed localhost recipient',
      $('inference-consent').disabled
        && JSON.parse(win.localStorage.getItem(INFERENCE_KEY)).inferenceGrant === localGrant);
    change('baseurl', 'http://127.0.0.1:11993/v1');
    check('restoring the same validated server after an incomplete edit retains its grant',
      $('inference-consent').checked
        && JSON.parse(win.localStorage.getItem(INFERENCE_KEY)).inferenceGrant === localGrant);
  });
}

async function failedStorage(check) {
  await visit(async ({ win, $, settings, forceStart, newSessions, captures, requests }) => {
    await settings();
    const prototype = win.Storage.prototype;
    const write = prototype.setItem;
    try {
      prototype.setItem = function (key, value) {
        if (key === INFERENCE_KEY) throw new win.DOMException('Scripted full device', 'QuotaExceededError');
        return write.call(this, key, value);
      };
      $('inference-consent').click();
      check('failed preference persistence is explicitly refused, never checked as accepted',
        !$('inference-consent').checked && /could not be saved/.test($('err').textContent));
      $('b-nav-new').click();
      await until(() => $('panel-setup').dataset.view === 'start', 'failed-storage Start');
      await forceStart(true);
      check('failed acceptance starts no session, capture or request',
        (await newSessions()).length === 0 && captures() === 0 && requests.length === 0);
    } finally {
      prototype.setItem = write;
    }
  });
  await visit(async ({ win, $, requests, settings, forceStart }) => {
    await acknowledgeInference(win);
    await forceStart();
    const prototype = win.Storage.prototype;
    const read = prototype.getItem;
    const draft = 'Keep this private answer on the device when preferences become unreadable.';
    try {
      prototype.getItem = function (key) {
        if (key === INFERENCE_KEY) throw new win.DOMException('Scripted blocked storage', 'SecurityError');
        return read.call(this, key);
      };
      $('answer').value = draft;
      $('answer').dispatchEvent(new win.Event('input', { bubbles: true }));
      $('b-send').click();
      await until(() => !$('err').hidden, 'unreadable-consent refusal');
      check('unreadable preferences stop even a cached provider without losing the local draft',
        requests.length === 0 && $('answer').value === draft
          && /could not be read/.test($('err').textContent));
    } finally {
      prototype.getItem = read;
    }
    await settings();
    check('recovering storage does not silently revive a permit retired by a read failure',
      !$('inference-consent').checked);
  });
}

async function anotherTabsWithdrawal(check) {
  await visit(async ({ win, $, forceStart, requests, captures }) => {
    await acknowledgeInference(win);
    await forceStart();
    const stalePreferences = JSON.parse(win.localStorage.getItem(PREFS_KEY));
    const peer = document.createElement('iframe');
    peer.style.cssText = 'position:absolute;left:-10000px;width:390px;height:844px';
    const loaded = new Promise((resolve) => { peer.onload = resolve; });
    peer.src = '/index.html';
    document.body.append(peer);
    try {
      await loaded;
      const other = peer.contentWindow;
      const get = (id) => other.document.getElementById(id);
      other.fetch = async () => { throw new Error('No network in the peer consent actor'); };
      await until(() => get('version')?.textContent, 'second-tab boot');
      get('b-settings').click();
      await until(() => !get('panel-setup').hidden
        && get('panel-setup').dataset.view === 'settings', 'second-tab Settings');
      get('inference-consent').click();
      await until(() => !get('inference-consent').checked, 'second-tab withdrawal');
      win.localStorage.setItem(PREFS_KEY, JSON.stringify({ ...stalePreferences, theme: 'dark' }));
      const draft = 'Keep this private draft after another tab withdrew the agreement.';
      $('answer').value = draft;
      $('answer').dispatchEvent(new win.Event('input', { bubbles: true }));
      await $('b-send').onclick();
      check('a real second-tab withdrawal survives a stale ordinary preference write and blocks the cached provider',
        requests.length === 0 && captures() === 0 && $('answer').value === draft
          && JSON.parse(win.localStorage.getItem(INFERENCE_KEY)).inferenceGrant === '');
    } finally {
      peer.remove();
    }
  });
}

async function retiredRequest(check) {
  await visit(async ({ win, $, settings, requests, network, newSessions, forceStart }) => {
    await acknowledgeInference(win);
    const oldGrant = JSON.parse(win.localStorage.getItem(INFERENCE_KEY)).inferenceGrant;
    await forceStart();
    const session = (await newSessions())[0];
    network.holdNext = true;
    $('answer').value = 'Conference organizers keep names and promised follow-ups in a private notebook.';
    $('answer').dispatchEvent(new win.Event('input', { bubbles: true }));
    const sending = $('b-send').onclick();
    await until(() => network.waiting.length === 1, 'already submitted request');
    await settings();
    $('inference-consent').click();
    await acknowledgeInference(win);
    check('a fresh affirmative choice has a different grant from retired model work',
      JSON.parse(win.localStorage.getItem(INFERENCE_KEY)).inferenceGrant !== oldGrant);
    network.release(true);
    await sending;
    const stored = await loadSession(session.id);
    check('withdrawal and re-agreement cannot authorize a retry from the old cached provider',
      requests.length === 1 && stored.turns.length === 1
        && stored.turns[0].answer.includes('Conference organizers'),
      JSON.stringify({ requests: requests.length, turns: stored.turns.length, pending: stored.pending }));
  });
  await visit(async ({ win, $, settings, change, network, requests, forceStart, newSessions }) => {
    await settings();
    change('provider', 'custom', 'change');
    change('baseurl', 'http://127.0.0.1:11993/v1');
    change('model', 'scripted-model');
    await acknowledgeInference(win);
    $('b-nav-new').click();
    await until(() => $('panel-setup').dataset.view === 'start', 'local Start');
    await forceStart();
    network.holdNext = true;
    $('answer').value = 'A private local notebook for conference organizers and their named follow-ups.';
    $('answer').dispatchEvent(new win.Event('input', { bubbles: true }));
    const sending = $('b-send').onclick();
    await until(() => network.waiting.length === 1, 'submitted local request');
    await settings();
    change('baseurl', 'http://public.example/v1');
    network.release(true);
    await sending;
    const stored = (await newSessions())[0];
    check('invalid keystrokes never redirect an already authorised request to a different recipient',
      requests.length === 2 && requests.every(({ url }) =>
        url === 'http://127.0.0.1:11993/v1/chat/completions')
        && stored.turns.length === 2 && stored.turns[1].questionSource === 'model',
      JSON.stringify({ requests: requests.map(({ url }) => url), turns: stored.turns.length }));
  });
}

async function retainedIdeas(check) {
  for (const kind of ['turn', 'synthesis', 'done']) {
    let session = submitAnswer(seedTurn(createSession({ id: `consent-retained-${kind}`, now: 1 })),
      { text: 'Private retained conference notebook', now: 2 });
    session = kind === 'done'
      ? setSynthesis(session, { text: '## Task\nKeep this notebook private.', now: 3 })
      : setPending(session, { kind, startedAt: 3, promptHash: 'recorded-before-consent' }, 3);
    await visit(async ({ $, requests, captures }) => {
      $('b-library').click();
      await until(() => !$('panel-library').hidden, 'retained Ideas');
      const card = $('library-rows').querySelector(`[data-session-id="${session.id}"]`);
      card.querySelector('.idea-actions > button:not(.idea-more)').click();
      await until(() => !$('panel-done').hidden, 'local read-only export');
      check(`${kind}: retained local text stays readable and exportable without new inference consent`,
        $('output').dataset.source.includes('Private retained conference notebook')
          && !$('b-copy').disabled && !$('b-download').disabled);
      await sleep(100);
      check(`${kind}: viewing does not replay pending work, capture audio or change the stored idea`,
        requests.length === 0 && captures() === 0
          && JSON.stringify(await loadSession(session.id)) === JSON.stringify(session));
    }, {}, session);
  }
}
