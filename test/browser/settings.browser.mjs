import {
  clearCredentials, emptyKeyring, loadCredentials, saveCredentials, withCreds, withStt, withTts,
} from '../../src/store/secrets.js';
import { get, put, SECRETS } from '../../src/store/db.js';
import { PREFS_KEY } from '../../src/store/prefs.js';
import { createSession } from '../../src/core/session.js';
import { seedTurn } from '../../src/runtime/turn.js';
import { deleteSession, saveSession } from '../../src/store/sessions.js';

const KEY = 'sk-settings-scripted-key-never-sent-194';
const sleep = (ms = 30) => new Promise((resolve) => setTimeout(resolve, ms));
async function until(predicate, label) {
  for (const end = Date.now() + 5000; Date.now() < end;) {
    if (await predicate()) return;
    await sleep();
  }
  throw new Error(`Settings probe did not reach ${label}`);
}
const savedRing = () => withTts(withStt(withCreds(emptyKeyring(), 'anthropic',
  { apiKey: KEY }), { kind: 'groq', apiKey: 'gsk-settings-transcription-194' }),
{ kind: 'openai', apiKey: 'sk-settings-separate-speech-194', verified: false });

async function visit(path, run, {
  ring = savedRing(), prepare = async () => {}, beforeBoot = null,
} = {}) {
  for (const worker of await navigator.serviceWorker.getRegistrations()) await worker.unregister();
  for (const name of await caches.keys()) await caches.delete(name);
  await clearCredentials();
  if (ring) await saveCredentials(ring);
  const idea = `settings-probe-${crypto.randomUUID()}`;
  await saveSession(seedTurn(createSession({ id: idea, now: Date.now() })));
  const previous = localStorage.getItem(PREFS_KEY);
  localStorage.setItem(PREFS_KEY, JSON.stringify({ theme: 'system' }));
  await prepare();
  const frame = document.createElement('iframe');
  frame.width = 390;
  frame.height = 844;
  frame.style.cssText = 'position:absolute;left:-10000px';
  const loaded = new Promise((resolve, reject) => {
    frame.onload = resolve;
    frame.onerror = () => reject(new Error('Settings probe iframe failed'));
  });
  if (beforeBoot) {
    const markup = await (await fetch(`${path}index.html`)).text();
    const app = markup.match(/<script type="module" src="\.\/src\/ui\/app\.js"><\/script>/);
    if (!app) throw new Error('The owning app module tag could not be deferred for the read-failure fixture');
    frame.srcdoc = markup.replace(app[0], '');
  } else frame.src = `${path}index.html`;
  document.body.append(frame);
  await loaded;
  const win = frame.contentWindow;
  const doc = frame.contentDocument;
  const $ = (id) => doc.getElementById(id);
  const shown = (el) => Boolean(el && el.getClientRects().length);
  const errors = [];
  let restoreBoot;
  let microphones = 0;
  win.addEventListener('unhandledrejection', (event) => errors.push(String(event.reason)));
  win.addEventListener('error', (event) => errors.push(event.message));
  win.navigator.mediaDevices.getUserMedia = async () => {
    microphones++;
    throw new win.DOMException('No microphone in Settings', 'NotAllowedError');
  };
  win.fetch = async () => { throw new TypeError('No unscripted Settings HTTP'); };
  const change = (id, value, event = 'change') => {
    $(id).value = value;
    $(id).dispatchEvent(new win.Event(event, { bubbles: true }));
  };
  const settings = async () => {
    $('b-settings').click();
    await until(() => $('panel-setup').dataset.view === 'settings', 'Settings destination');
    await until(() => !doc.documentElement.matches(':active-view-transition'), 'completed Settings navigation');
    await Promise.all($('panel-setup').getAnimations().map((animation) => animation.finished));
  };
  try {
    if (beforeBoot) {
      restoreBoot = beforeBoot(win);
      const module = doc.createElement('script');
      module.type = 'module';
      module.src = new URL(`${path}src/ui/app.js`, location.origin).href;
      doc.body.append(module);
    }
    try {
      await until(() => $('version')?.textContent, 'boot');
    } catch (error) {
      throw new Error(`${error.message}: ${JSON.stringify({
        path, ready: doc.readyState, version: $('version')?.textContent,
        providers: $('provider')?.options.length, errors,
        credential: $('credential-error')?.textContent,
      })}`);
    }
    await settings();
    await run({ win, doc, $, shown, change, frame, settings });
    await sleep();
    if (errors.length) throw new Error(errors.join('; '));
    if (microphones) throw new Error('Settings requested a microphone');
  } finally {
    restoreBoot?.();
    frame.remove();
    await clearCredentials();
    await deleteSession(idea);
    if (previous === null) localStorage.removeItem(PREFS_KEY);
    else localStorage.setItem(PREFS_KEY, previous);
    for (const worker of await navigator.serviceWorker.getRegistrations()) await worker.unregister();
  }
}

export default async function run(check, { subpath }) {
  for (const path of ['/', subpath]) {
    await hierarchy(check, path);
    await errorsAndPreferences(check, path);
    await cancellation(check, path);
    await delayedForget(check, path);
    await failedForget(check, path);
    await unreadable(check, path);
    await typedTranscriptionKey(check, path);
  }
  await unreadableRetained(check);
}

async function typedTranscriptionKey(check, path) {
  await visit(path, async ({ win, $, change }) => {
    change('stt', 'groq');
    change('sttkey', 'gsk-typed-then-saved-transcription-194', 'input');
    win.fetch = async () => new win.Response(JSON.stringify({
      error: { message: 'Scripted refusal after saving the typed transcription key' },
    }), { status: 401 });
    $('b-check').click();
    await until(() => !$('err').hidden, 'form-entered transcription key saved');
    const first = await loadCredentials();
    check(`${path} the first transcription key really passed through the form and encrypted save`,
      first.stt.kind === 'groq' && first.stt.apiKey === 'gsk-typed-then-saved-transcription-194');
    change('stt', 'openai');
    $('b-check').click();
    await until(() => $('b-check').getAttribute('aria-busy') === 'false', 'next transcription selection saved');
    const next = await loadCredentials();
    check(`${path} a typed-and-saved transcription key cannot cross services without a reload`,
      next.stt.kind === 'openai' && next.stt.apiKey === '' && $('sttkey').value === '');
  });
}

async function unreadableRetained(check) {
  await visit('/', async ({ $, shown }) => {
    check('a failed credential read leaves the original encrypted payload intact',
      Boolean(await get(SECRETS, 'credentials')));
    check('failed reads report unknown saved keys, not an empty credential store',
      !/No keys saved/.test($('keys-summary').textContent)
        && /could not be read|may remain|unknown/i.test($('keys-summary').textContent));
    check('possibly retained keys keep an explicit Forget path available',
      shown($('b-forget')) && shown($('credential-error')));
  }, { beforeBoot(win) {
    const read = win.IDBObjectStore.prototype.get;
    win.IDBObjectStore.prototype.get = function (key) {
      if (this.name === SECRETS && key === 'credentials') throw new Error('Scripted retained-key read failure');
      return read.call(this, key);
    };
    return () => { win.IDBObjectStore.prototype.get = read; };
  } });
}

async function hierarchy(check, path) {
  await visit(path, async ({ doc, win, $, shown, change, frame }) => {
    const groups = [...$('panel-setup').querySelectorAll(':scope > .group')];
    check(`${path} Settings has five quiet named sections`,
      groups.map((group) => group.querySelector('h2').innerText.trim()).join('|')
        === 'Connection|Voice|Appearance|Your data|About');
    check(`${path} each section has a truthful current-state summary`,
      groups.every((group) => group.querySelector('.settings-summary')?.textContent.trim()));
    check(`${path} Forget keys belongs to Your data, not the connection check`,
      $('settings-data').contains($('b-forget')) && $('b-forget').textContent === 'Forget keys');
    check(`${path} backup explains exclusion of device preferences and import copies`,
      /preferences/.test($('settings-data').textContent)
        && /collision|same id|same identifier/.test($('settings-data').textContent)
        && /cop(y|ies)/.test($('settings-data').textContent));

    change('speech', 'openai');
    $('speech-options').open = false;
    check(`${path} required hosted fields and consent cannot be hidden in optional voice choices`,
      ['ttskey', 'tts-voice', 'tts-consent', 'b-speech-preview']
        .every((id) => shown($(id)) && !$(id).closest('details')));
    check(`${path} unconsented hosted speech cannot be checked`,
      !$('tts-consent').checked && $('b-speech-preview').disabled);
    change('provider', 'groq');
    change('stt', 'groq');
    check(`${path} matching-provider transcription key reuse is explicitly named`,
      !shown($('field-sttkey')) && /Groq/.test($('stt-note').textContent)
        && /inference key/.test($('stt-note').textContent));
    check(`${path} hosted dictation explains audio/text transfer and metered use`,
      /audio/.test($('stt-note').textContent) && /text/.test($('stt-note').textContent)
        && /metered/.test($('stt-note').textContent));
    change('stt', 'openai');
    check(`${path} a different transcription provider keeps its independent key field`,
      shown($('sttkey')) && /OpenAI/.test($('stt-note').textContent));
    change('apikey', 'gsk-scripted-settings-inference-194', 'input');
    win.fetch = async () => new win.Response(JSON.stringify({
      error: { message: 'Scripted key refusal after saving the selected settings' },
    }), { status: 401 });
    $('b-check').click();
    await until(() => !$('err').hidden, 'separate transcription setting saved');
    const stored = await loadCredentials();
    check(`${path} a saved transcription key never crosses to another transcription provider`,
      stored.stt.kind === 'openai' && stored.stt.apiKey === '');
    for (const theme of ['light', 'dark']) {
      doc.documentElement.dataset.theme = theme;
      for (const width of [320, 390, 840, 1280]) {
        frame.width = width;
        await sleep();
        check(`${path} ${theme} Settings reflows at ${width}`,
          doc.documentElement.scrollWidth <= win.innerWidth,
          `${doc.documentElement.scrollWidth}/${win.innerWidth}`);
      }
    }
  });
}

async function errorsAndPreferences(check, path) {
  await visit(path, async ({ win, doc, $, change, shown, settings }) => {
    change('apikey', KEY, 'input');
    win.fetch = async () => new win.Response(JSON.stringify({
      error: { message: 'Scripted rejected Settings key' },
    }), { status: 401 });
    $('b-check').click();
    await until(() => !$('err').hidden, 'connection failure');
    check(`${path} connection error is actionable beside the responsible operation`,
      shown($('connection-error')) && /Scripted rejected Settings key/.test($('connection-error').textContent));

    const encrypt = win.crypto.subtle.encrypt;
    win.crypto.subtle.encrypt = async () => { throw new Error('Scripted credential save failure'); };
    try {
      change('apikey', `${KEY}-new`, 'input');
      $('b-check').click();
      await until(() => /Scripted credential save failure/.test($('err').textContent), 'credential save failure');
      $('b-library').click();
      await until(() => !$('panel-library').hidden, 'Ideas after credential failure');
      await settings();
      check(`${path} credential failure survives navigation and cannot become a Saved toast`,
        shown($('credential-error')) && /Scripted credential save failure/.test($('credential-error').textContent));
    } finally { win.crypto.subtle.encrypt = encrypt; }

    const setItem = win.Storage.prototype.setItem;
    win.Storage.prototype.setItem = function (key, value) {
      if (key === PREFS_KEY) throw new win.DOMException('Scripted preferences quota', 'QuotaExceededError');
      return setItem.call(this, key, value);
    };
    try {
      const radio = $('theme-choice').querySelector('[value="dark"]');
      radio.checked = true;
      radio.dispatchEvent(new win.Event('change'));
      check(`${path} theme applies immediately even when persistence fails`,
        doc.documentElement.dataset.theme === 'dark');
      check(`${path} failed theme persistence is explicit in Appearance`,
        shown($('appearance-status')) && /could not be saved/i.test($('appearance-status').textContent)
          && $('appearance-status').dataset.status === 'error');
    } finally { win.Storage.prototype.setItem = setItem; }

    const getAll = win.IDBObjectStore.prototype.getAll;
    win.IDBObjectStore.prototype.getAll = function (...args) {
      if (this.name === 'sessions') throw new Error('Scripted backup read failure');
      return getAll.apply(this, args);
    };
    try {
      $('b-backup').click();
      await until(() => /Scripted backup read failure/.test($('err').textContent), 'backup failure');
      check(`${path} the backup handler surfaces its actual failed read`,
        /Scripted backup read failure/.test($('err').textContent), $('err').textContent);
      check(`${path} backup failure remains beside Your data`,
        shown($('data-status')) && /Scripted backup read failure/.test($('data-status').textContent)
          && $('data-status').dataset.status === 'error');
    } finally { win.IDBObjectStore.prototype.getAll = getAll; }
  });
}

async function cancellation(check, path) {
  await visit(path, async ({ doc, $, shown }) => {
    const before = JSON.stringify(await loadCredentials());
    $('b-forget').focus();
    $('b-forget').click();
    const dialog = doc.querySelector('dialog[open]');
    check(`${path} Forget opens a native labelled confirmation without mutating keys`,
      Boolean(dialog) && JSON.stringify(await loadCredentials()) === before);
    if (!dialog) return;
    const cancel = dialog.querySelector('[value="cancel"]');
    const confirm = dialog.querySelector('[value="confirm"]');
    check(`${path} confirmation states consequences and defaults safely to Cancel`,
      shown(cancel) && shown(confirm) && doc.activeElement === cancel
        && Boolean(dialog.getAttribute('aria-labelledby'))
        && /ideas.*(kept|stay|not)|not.*ideas/i.test(dialog.textContent));
    // Calling the action twice must neither stack modals nor approve another operation.
    $('b-forget').click();
    check(`${path} duplicate Forget does not stack confirmations`,
      doc.querySelectorAll('dialog[open]').length === 1);
    cancel.click();
    await until(() => !doc.querySelector('dialog'), 'settled Cancel');
    check(`${path} Cancel returns to Forget without changing any credential`,
      doc.activeElement === $('b-forget') && JSON.stringify(await loadCredentials()) === before);
    $('b-forget').click();
    const escape = doc.querySelector('dialog[open]');
    if (!escape) throw new Error('Second confirmation missing');
    escape.dispatchEvent(new Event('cancel', { cancelable: true }));
    await until(() => !doc.querySelector('dialog'), 'settled native cancel');
    check(`${path} native cancel resolves false and restores initiating focus`,
      JSON.stringify(await loadCredentials()) === before && doc.activeElement === $('b-forget'));
  });
}

async function delayedForget(check, path) {
  await visit(path, async ({ win, doc, $, change, shown }) => {
    const encrypt = win.crypto.subtle.encrypt;
    let release;
    let held = false;
    win.crypto.subtle.encrypt = async function (...args) {
      const value = await encrypt.apply(this, args);
      if (!held) {
        held = true;
        await new Promise((resolve) => { release = resolve; });
      }
      return value;
    };
    try {
      change('apikey', `${KEY}-pending`, 'input');
      $('b-check').click();
      await until(() => held, 'pending encrypted credential write');
      $('b-forget').click();
      const dialog = doc.querySelector('dialog[open]');
      check(`${path} Forget confirmation also protects an in-flight key check`, Boolean(dialog));
      if (dialog) dialog.querySelector('[value="confirm"]').click();
      await until(() => /Removing saved keys/.test($('data-status')?.textContent), 'pending confirmed removal');
      change('apikey', 'sk-new-unsaved-key-entered-during-removal', 'input');
      $('model-settings').open = true;
      change('model', 'a-new-unsaved-question-model', 'input');
      $('apikey').focus();
      release();
      await until(() => $('b-forget').hidden || /could not/.test($('err').textContent), 'confirmed Forget');
      await sleep(100);
      const blob = await get(SECRETS, 'credentials');
      const key = await get(SECRETS, 'wrapping-key');
      check(`${path} confirmed Forget cannot resurrect a delayed encrypted payload or wrapping key`,
        !blob && !key, `payload=${Boolean(blob)}, wrapping=${Boolean(key)}`);
      check(`${path} completed Forget cannot erase fields newly edited while storage was pending`,
        $('apikey').value === 'sk-new-unsaved-key-entered-during-removal'
          && $('model').value === 'a-new-unsaved-question-model'
          && doc.activeElement === $('apikey'));
      check(`${path} confirmed Forget remains acknowledged beside Your data`,
        shown($('data-status')) && /keys.*(forgotten|removed|deleted)/i.test($('data-status').textContent));
      check(`${path} retired check cannot leave the connection busy`,
        $('b-check').getAttribute('aria-busy') === 'false');
    } finally {
      release?.();
      win.crypto.subtle.encrypt = encrypt;
    }
  });
}

async function failedForget(check, path) {
  await visit(path, async ({ win, doc, $, shown }) => {
    const remove = win.IDBObjectStore.prototype.delete;
    win.IDBObjectStore.prototype.delete = function (key) {
      if (this.name === SECRETS && key === 'wrapping-key') throw new Error('Scripted wrapping-key removal failure');
      return remove.call(this, key);
    };
    try {
      $('b-forget').click();
      doc.querySelector('dialog [value="confirm"]').click();
      await until(() => /could not be removed completely/.test($('data-status').textContent), 'partial Forget failure');
      check(`${path} failed removal stays actionable and cannot reuse the old in-memory key`,
        shown($('b-forget')) && !$('apikey').placeholder.includes('saved:')
          && $('apikey').value === '' && shown($('credential-error')));
    } finally { win.IDBObjectStore.prototype.delete = remove; }
    $('b-forget').click();
    doc.querySelector('dialog [value="confirm"]').click();
    await until(() => /keys were removed/.test($('data-status').textContent), 'retry Forget');
    check(`${path} retry completes key removal without discarding ideas`,
      !(await get(SECRETS, 'wrapping-key')) && !shown($('credential-error')));
  });
}

async function unreadable(check, path) {
  await visit(path, async ({ $, shown }) => {
    check(`${path} unreadable encrypted keys have persistent re-entry recovery`,
      shown($('credential-error')) && /could not be read/.test($('credential-error').textContent)
        && /paste|enter/i.test($('credential-error').textContent));
  }, { prepare: () => put(SECRETS, {
    iv: new Uint8Array(12), ciphertext: new Uint8Array([1, 2, 3]),
  }, 'credentials') });
}
