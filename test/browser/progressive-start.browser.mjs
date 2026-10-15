import { PROVIDER_CHOICES } from '../../src/providers/index.js';
import {
  clearCredentials, credsFor, emptyKeyring, loadCredentials, saveCredentials, withCreds,
} from '../../src/store/secrets.js';
import { deleteSession, listSessions } from '../../src/store/sessions.js';
import { del, SECRETS } from '../../src/store/db.js';

const sleep = (ms = 40) => new Promise((resolve) => setTimeout(resolve, ms));

async function until(predicate, label) {
  for (const end = Date.now() + 4000; Date.now() < end;) {
    if (predicate()) return;
    await sleep();
  }
  throw new Error(`Progressive Start did not reach ${label}`);
}

async function visit(run, ring = null, prepare = async () => {}) {
  await clearCredentials();
  if (ring) await saveCredentials(ring);
  await prepare();
  const held = new Set((await listSessions()).map((s) => s.id));
  const frame = document.createElement('iframe');
  frame.width = 390;
  frame.height = 844;
  frame.style.cssText = 'position:absolute;left:-10000px';
  const loaded = new Promise((resolve, reject) => {
    frame.onload = resolve;
    frame.onerror = () => reject(new Error('Progressive Start iframe failed'));
  });
  frame.src = '/index.html';
  document.body.append(frame);
  await loaded;
  const win = frame.contentWindow;
  const doc = frame.contentDocument;
  const $ = (id) => doc.getElementById(id);
  let microphones = 0;
  let recognitionStarts = 0;
  win.navigator.mediaDevices.getUserMedia = async () => {
    microphones++;
    throw new win.DOMException('Scripted microphone denial', 'NotAllowedError');
  };
  win.SpeechRecognition = class {
    start() {
      recognitionStarts++;
      win.queueMicrotask(() => this.onerror?.({ error: 'not-allowed' }));
    }
    abort() {}
    stop() {}
  };
  win.webkitSpeechRecognition = win.SpeechRecognition;
  win.fetch = async () => { throw new TypeError('Unscripted request in Start probe'); };
  const change = (id, value, event = 'input') => {
    $(id).value = value;
    $(id).dispatchEvent(new win.Event(event, { bubbles: true }));
  };
  const shown = (id) => Boolean($(id)?.getClientRects().length);
  try {
    await until(() => $('version')?.textContent, 'boot');
    if ($('panel-setup').hidden) {
      $('b-nav-new').click();
      await until(() => !$('panel-setup').hidden, 'Start');
    }
    await doc.fonts.ready;
    await run({ win, doc, $, change, shown, frame,
      microphones: () => microphones + recognitionStarts });
  } finally {
    frame.remove();
    await clearCredentials();
    for (const row of await listSessions()) if (!held.has(row.id)) await deleteSession(row.id);
    for (const worker of await navigator.serviceWorker.getRegistrations()) await worker.unregister();
  }
}

export default async function run(check) {
  await freshFlow(check);
  await checkRaces(check);
  await declaredFieldsAndManualModel(check);
  await savedOverride(check);
  await viewerAndOpaqueFailure(check);
  await staleCatalogues(check);
  await credentialRetirement(check);
  await delayedConstruction(check);
  await startPresentationRetirement(check);
  await unreadableKeyring(check);
  await reflow(check);
}

async function freshFlow(check) {
  await visit(async ({ $, change, shown, doc, win, microphones }) => {
    check('fresh Start labels the provider step without displaying required-key fields yet',
      $('connect').dataset.step === 'provider' && shown('provider') && !shown('apikey'));
    check('provider choices are grouped as hosted/viewer and local without losing a registry entry',
      $('provider').querySelectorAll('optgroup').length >= 2
        && [...$('provider').options].map((o) => o.value).sort().join(',')
          === PROVIDER_CHOICES.map((c) => c.id).sort().join(','));
    const navTop = doc.querySelector('.bar-right').getBoundingClientRect().top;
    check('fresh390x844 shows both voice-primary and Type start actions above fixed navigation',
      ['b-start-voice', 'b-start'].every((id) => {
        const box = $(id).getBoundingClientRect();
        return box.height >= 64 && box.top >= 0 && box.bottom <= navTop;
      }));
    check('progressive connection controls exist in the one shared form',
      !!$('b-connect-next') && !!$('b-connect-back')
        && doc.querySelectorAll('#panel-setup').length === 1
        && doc.querySelectorAll('#apikey').length === 1);
    if (!$('b-connect-next')) return;

    $('b-connect-next').click();
    await until(() => $('connect').dataset.step === 'fields', 'required connection fields');
    check('the required hosted key has a visible label and no local address field',
      shown('apikey') && $('apikey').required && !shown('field-base')
        && doc.querySelector('label[for="apikey"]').textContent.includes('API key'));
    $('b-connect-next').click();
    await until(() => $('apikey').getAttribute('aria-invalid') === 'true', 'inline missing-key error');
    check('missing-key recovery is inline and global, not a disabled unexplained Next',
      shown('key-error') && $('key-error').textContent.trim() && !$('err').hidden);
    const key = 'sk-probe-unsaved-progressive-193';
    change('apikey', key);
    $('b-connect-back').click();
    check('Back returns to provider selection without discarding the unsaved key',
      $('connect').dataset.step === 'provider' && $('apikey').value === key);
    $('b-connect-next').click();
    $('b-connect-next').click();
    await until(() => $('connect').dataset.step === 'ready', 'ready without a network check');
    check('Continue does not save credentials or invent a successful check',
      !(await loadCredentials()) && /not checked/i.test($('connect-status').textContent)
        && $('connect-summary-text').dataset.icon !== 'check');
    check('ready Start keeps voice first and Type visible before optional/recent content',
      shown('b-start-voice') && shown('b-start')
        && Boolean($('b-start-voice').compareDocumentPosition($('b-start')) & win.Node.DOCUMENT_POSITION_FOLLOWING)
        && (!$('resume').getClientRects().length
          || $('b-start').getBoundingClientRect().bottom <= $('resume').getBoundingClientRect().top));
    $('b-connect-change').click();
    check('Change returns to the existing fields with the unsaved value and useful focus',
      shown('apikey') && $('apikey').value === key && doc.activeElement === $('provider'));
    change('stt', 'off', 'change');
    $('b-start').click();
    await until(() => !$('panel-interview').hidden, 'unchecked typed interview');
    check('Type starts without a mandatory check and stores the typed value',
      credsFor(await loadCredentials(), 'anthropic').apiKey === key);
    check('provider selection, Continue, typing and Type never request a microphone',
      microphones() === 0, String(microphones()));
  });
}

async function checkRaces(check) {
  const ring = withCreds(emptyKeyring(), 'anthropic', { apiKey: 'sk-probe-saved-193-0001' });
  await visit(async ({ $, change, win, doc, microphones }) => {
    const status = () => $('connect-status')?.textContent || $('connect-summary-text').textContent;
    check('a saved key is explicitly untested on a fresh visit, without a success checkmark',
      /saved/i.test($('connect-summary-text').textContent) && /not checked/i.test(status())
        && $('connect-summary-text').dataset.icon !== 'check', status());
    $('b-connect-change').click();
    const requests = [];
    win.fetch = (_url, init) => new Promise((resolve) => requests.push({ init, resolve }));
    const reply = (index, ok) => requests[index].resolve(new win.Response(JSON.stringify(ok
      ? { content: [{ type: 'text', text: 'ok' }], model: 'scripted' }
      : { error: { message: 'Scripted invalid key' } }), { status: ok ? 200 : 401 }));
    try {
      $('b-check').focus();
      $('b-check').click();
      await until(() => requests.length === 1, 'first deferred check');
      check('checking has distinct inline text and a native busy duplicate-check guard',
        /checking/i.test(status()) && $('b-check').disabled
          && $('b-check').getAttribute('aria-busy') === 'true', status());
      check('a pending optional check does not add a network-success start gate', !$('b-start').disabled);
      change('apikey', 'sk-probe-newer-key-193-0002');
      $('b-check').focus();
      $('b-check').click();
      await until(() => requests.length === 2, 'newer configuration check');
      check('an unsaved replacement key, not the saved key, signs the newer request',
        new win.Headers(requests[1].init.headers).get('x-api-key') === 'sk-probe-newer-key-193-0002');
      reply(1, true);
      await until(() => /checked|key works/i.test($('connect-summary-text').textContent), 'newer check success');
      reply(0, false);
      await sleep(150);
      check('an older rejected check cannot overwrite newer success or display its error',
        /checked|key works/i.test($('connect-summary-text').textContent) && $('err').hidden,
        `${status()} / ${$('err').textContent}`);

      $('b-connect-change').click();
      change('apikey', 'sk-probe-older-check-193-0003');
      $('b-check').click();
      await until(() => requests.length === 3, 'older successful response');
      change('apikey', 'sk-probe-latest-fails-193-0004');
      $('b-check').click();
      await until(() => requests.length === 4, 'latest rejected response');
      reply(3, false);
      await until(() => !$('err').hidden, 'current check failure');
      reply(2, true);
      await sleep(150);
      check('an older successful check cannot vouch for a newer failed configuration',
        /failed/i.test(status()) && !$('connect').hasAttribute('data-collapsed')
          && !$('err').hidden && $('connect-summary-text').dataset.icon !== 'check', status());
      change('model', 'a-different-question-model');
      check('editing a model invalidates check proof as well as editing its key',
        /not checked/i.test(status()), status());
      check('connection checks never request a microphone', microphones() === 0);
      check('check completion keeps useful visible focus rather than dropping it to the body',
        doc.activeElement !== doc.body && doc.activeElement.getClientRects().length > 0);
    } finally {
      for (const request of requests) request.resolve(new win.Response('{}', { status: 401 }));
    }
  }, ring);
}

async function savedOverride(check) {
  const ring = withCreds(emptyKeyring(), 'openai', { apiKey: 'sk-probe-saved-old-193' });
  await visit(async ({ $, change, microphones }) => {
    $('b-settings').click();
    await until(() => $('panel-setup').dataset.view === 'settings', 'Settings');
    change('apikey', 'sk-probe-unsaved-new-193');
    change('baseurl', 'https://untrusted.example/not-a-local-server');
    change('model', 'question-override');
    change('wrapmodel', 'wrap-override');
    change('stt', 'off', 'change');
    $('b-nav-new').click();
    await until(() => $('panel-setup').dataset.view === 'start', 'return to Start');
    check('returning to Start preserves an unsaved replacement and hidden model values',
      $('apikey').value === 'sk-probe-unsaved-new-193'
        && $('model').value === 'question-override' && $('wrapmodel').value === 'wrap-override'
        && /not checked/i.test($('connect-status').textContent));
    $('b-start').click();
    await until(() => !$('panel-interview').hidden, 'unsaved-override interview');
    const stored = credsFor(await loadCredentials(), 'openai');
    check('the original shared form stores the unsaved override, not the old key or hidden hosted address',
      stored.apiKey === 'sk-probe-unsaved-new-193' && stored.baseUrl === ''
        && stored.model === 'question-override' && stored.wrapModel === 'wrap-override');
    check('Settings edits and unchecked Type remain microphone-free', microphones() === 0);
  }, ring);
}

async function viewerAndOpaqueFailure(check) {
  await visit(async ({ $, change, win, microphones }) => {
    change('provider', 'artifact', 'change');
    $('b-connect-next').focus();
    await $('b-connect-next').onclick();
    check('failed viewer Continue restores focus to visible Provider, never its hidden server field',
      win.document.activeElement === $('provider') && $('provider').getClientRects().length > 0
        && $('field-base').hidden);
    $('b-check').click();
    await until(() => !$('err').hidden, 'unavailable viewer check');
    check('the viewer cannot earn successful check proof outside its runtime',
      /viewer is unavailable/i.test($('err').textContent)
        && /failed/i.test($('connect-status').textContent)
        && $('connect-summary-text').dataset.icon !== 'check');
    win.claude = { use: async () => async () => ({ text: '{}' }) };
    $('b-check').click();
    await until(() => /viewer available/i.test($('connect-summary-text').textContent), 'viewer availability');
    check('an available viewer is distinguished from an actual network round trip',
      /no network request was tested/i.test($('connect-status').textContent));
    $('b-connect-change').click();
    change('provider', 'openai', 'change');
    change('apikey', 'sk-probe-opaque-193');
    let requests = 0;
    win.fetch = async () => { requests++; throw new TypeError('Scripted opaque network failure'); };
    $('b-check').click();
    await until(() => !$('err').hidden, 'opaque hosted failure');
    check('opaque hosted failure stays failed with recovery and no success checkmark',
      requests > 0 && /failed/i.test($('connect-status').textContent)
        && $('err').textContent.trim() && $('connect-summary-text').dataset.icon !== 'check');
    check('viewer and failed hosted checks start neither recognition nor getUserMedia', microphones() === 0);
  });
}

async function staleCatalogues(check) {
  await visit(async ({ $, change, win }) => {
    change('provider', 'custom', 'change');
    change('baseurl', 'http://127.0.0.1:11991/v1');
    change('model', 'keep-my-model');
    const requests = [];
    win.fetch = () => new Promise((resolve) => requests.push(resolve));
    const reply = (index, names) => requests[index](new win.Response(
      JSON.stringify({ data: names.map((id) => ({ id })) }), { status: 200 }));
    try {
      $('b-check').click();
      await until(() => requests.length === 1, 'old server catalogue');
      change('baseurl', 'http://127.0.0.1:11992/v1');
      change('model', 'newer-manual-model');
      $('b-check').click();
      await until(() => requests.length === 2, 'new server catalogue');
      reply(1, ['new-model-a', 'new-model-b']);
      await until(() => /2 models installed/.test($('model-note').textContent), 'new catalogue');
      reply(0, ['old-server-model']);
      await sleep(100);
      check('an old server catalogue cannot replace current suggestions or a manually typed model',
        [...$('model-list').options].map((o) => o.value).join(',') === 'new-model-a,new-model-b'
          && $('model').value === 'newer-manual-model');
      change('baseurl', 'http://127.0.0.1:11994/v1');
      $('b-check').click();
      await until(() => requests.length === 3, 'catalogue before provider switch');
      change('provider', 'openai', 'change');
      const seeded = [...$('model-list').options].map((o) => o.value).join(',');
      reply(2, ['wrong-provider-model']);
      await sleep(100);
      check('a response from the previous provider cannot fill or certify the selected provider',
        $('provider').value === 'openai' && /not checked/i.test($('connect-status').textContent)
          && [...$('model-list').options].map((o) => o.value).join(',') === seeded);
      change('apikey', 'sk-probe-navigating-193');
      $('b-check').click();
      await until(() => requests.length === 4, 'check before navigation');
      $('b-library').click();
      await until(() => !$('panel-library').hidden, 'Ideas while check is pending');
      reply(3, ['a-model']);
      await sleep(100);
      check('a retired check cannot announce or navigate after leaving Start',
        !$('panel-library').hidden && $('note').hidden && $('err').hidden);
    } finally {
      for (const resolve of requests) resolve(new win.Response('{}', { status: 401 }));
    }
  });
}

async function credentialRetirement(check) {
  const ring = withCreds(emptyKeyring(), 'anthropic', { apiKey: 'sk-probe-epoch-193' });
  await visit(async ({ $, win, change }) => {
    $('b-settings').click();
    await until(() => $('panel-setup').dataset.view === 'settings', 'credential retirement Settings');
    const requests = [];
    win.fetch = () => new Promise((resolve) => requests.push(resolve));
    const reply = (index) => requests[index](new win.Response(JSON.stringify({
      content: [{ type: 'text', text: 'ok' }], model: 'scripted',
    }), { status: 200 }));
    try {
      $('b-check').click();
      await until(() => requests.length === 1, 'check before speech withdrawal');
      change('speech', 'browser', 'change');
      await until(() => /Hosted speech is disabled/.test($('speech-check').textContent), 'saved withdrawal');
      check('speech credential withdrawal retires a pending inference check without leaving busy UI',
        !$('b-check').disabled && $('b-check').getAttribute('aria-busy') === 'false'
          && !/^Checking\b/i.test($('connect-status').textContent),
        `${$('connect-status').textContent}; disabled=${$('b-check').disabled}`);
      if ($('b-check').disabled) { reply(0); return; }
      $('b-check').click();
      await until(() => requests.length === 2, 'check after credential retirement');
      reply(0);
      await sleep(80);
      check('the retired check cannot clear a newer credential-generation attempt',
        $('b-check').disabled && /checking/i.test($('connect-status').textContent));
      reply(1);
      await until(() => /Connection checked/.test($('connect-status').textContent), 'replacement check');
      change('speech', 'browser', 'change');
      await until(() => /Hosted speech is disabled/.test($('speech-check').textContent), 'second withdrawal');
      check('withdrawing speech does not erase already completed unchanged inference proof',
        /Connection checked/.test($('connect-status').textContent));
    } finally {
      for (const resolve of requests) resolve(new win.Response('{}', { status: 401 }));
    }
  }, ring);
}

async function delayedConstruction(check) {
  for (const newer of ['checking', 'checked']) {
    await visit(async ({ $, win, change }) => {
      let release;
      win.claude = { use: () => new Promise((resolve) => { release = resolve; }) };
      change('provider', 'artifact', 'change');
      change('stt', 'off', 'change');
      const starting = $('b-start').onclick();
      await until(() => !!release, 'delayed Start construction');
      change('provider', 'openai', 'change');
      change('apikey', 'sk-probe-new-construction-193');
      let answer;
      win.fetch = () => new Promise((resolve) => { answer = resolve; });
      const respond = () => answer(new win.Response(
        '{"data":[{"id":"newer-model"}]}', { status: 200 }));
      try {
        $('b-check').click();
        await until(() => !!answer, 'newer configuration request');
        if (newer === 'checked') {
          respond();
          await until(() => /Connection checked/.test($('connect-status').textContent), 'newer proof');
        }
        release(async () => ({ text: '{}' }));
        await starting;
        check(`a retired Start construction cannot overwrite newer ${newer} state`,
          (newer === 'checking' ? /checking/i : /Connection checked/).test($('connect-status').textContent)
            && $('err').hidden && !$('panel-setup').hidden, $('connect-status').textContent);
        if (newer === 'checking') respond();
        await sleep(80);
        check(`after a retired Start, the newer ${newer} check still owns its result/catalogue`,
          /Connection checked/.test($('connect-status').textContent)
            && [...$('model-list').options].some((o) => o.value === 'newer-model'));
      } finally {
        release(async () => ({ text: '{}' }));
        if (answer) answer(new win.Response('{}', { status: 401 }));
        await starting;
      }
    });
  }
  for (const action of ['start', 'continue']) {
    await visit(async ({ $, win, change }) => {
      let release;
      let calls = 0;
      win.claude = { use: () => ++calls === 1
        ? new Promise((resolve) => { release = resolve; })
        : Promise.resolve(async () => ({ text: '{}' })) };
      change('provider', 'artifact', 'change');
      change('stt', 'off', 'change');
      const operation = $(action === 'start' ? 'b-start' : 'b-connect-next').onclick();
      await until(() => !!release, `delayed ${action} before withdrawal`);
      change('speech', 'browser', 'change');
      await until(() => /Hosted speech is disabled/.test($('speech-check').textContent), 'withdrawal during construction');
      $('b-check').click();
      await until(() => /viewer available/i.test($('connect-summary-text').textContent), 'new-generation viewer proof');
      release(async () => ({ text: '{}' }));
      await operation;
      check(`credential-retired ${action} cannot replace newer same-provider proof with its cancellation`,
        /viewer available/i.test($('connect-summary-text').textContent)
          && $('err').hidden, $('connect-status').textContent);
    });
  }
  await visit(async ({ $, win, change }) => {
    let release;
    win.claude = { use: () => new Promise((resolve) => { release = resolve; }) };
    change('provider', 'artifact', 'change');
    const advancing = $('b-connect-next').onclick();
    await until(() => !!release, 'pending Use this connection');
    $('b-connect-back').click();
    release(async () => ({ text: '{}' }));
    await advancing;
    check('Back also retires delayed connection construction without undoing its presentation choice',
      $('connect').dataset.step === 'provider' && $('provider').getClientRects().length > 0);
  });
  await visit(async ({ $, win, change }) => {
    change('provider', 'anthropic', 'change');
    change('apikey', 'sk-probe-back-during-check-193');
    let reply;
    win.fetch = () => new Promise((resolve) => { reply = resolve; });
    $('b-check').click();
    await until(() => !!reply, 'check before Back');
    $('b-connect-back').click();
    reply(new win.Response('{"content":[{"type":"text","text":"ok"}]}', { status: 200 }));
    await sleep(80);
    check('Back retires a pending check too, so it cannot fold the provider choice on completion',
      $('connect').dataset.step === 'provider' && !$('b-check').disabled);
  });
}

async function startPresentationRetirement(check) {
  for (const action of ['back', 'change']) {
    await visit(async ({ $, change, win, microphones }) => {
      win.claude = { use: async () => async () => ({ text: '{}' }) };
      change('provider', 'artifact', 'change');
      if (action === 'change') await $('b-connect-next').onclick();
      let release;
      win.claude.use = () => new Promise((resolve) => { release = resolve; });
      const starting = $('b-start-voice').onclick();
      await until(() => !!release, 'pending voice Start construction');
      const control = $(action === 'back' ? 'b-connect-back' : 'b-connect-change');
      check(`${action} is an actual visible action while voice Start is pending`,
        control.getClientRects().length > 0);
      control.click();
      release(async () => ({ text: '{}' }));
      await starting;
      check(`${action} retires pending voice Start before it can open an interview or microphone`,
        !$('panel-setup').hidden && $('panel-interview').hidden && microphones() === 0,
        `panel=${win.document.body.dataset.panel}; captures=${microphones()}`);
    });
  }
  await visit(async ({ $, change, win }) => {
    const releases = [];
    win.claude = { use: () => new Promise((resolve) => releases.push(resolve)) };
    change('provider', 'artifact', 'change');
    change('stt', 'off', 'change');
    const first = $('b-start').onclick();
    await until(() => releases.length === 1, 'first pending Start');
    $('b-connect-back').click();
    const second = $('b-start').onclick();
    await sleep(80);
    check('Back permits a new Start without waiting for a retired capability request',
      releases.length === 2, `${releases.length} construction(s)`);
    if (releases.length !== 2) {
      for (const release of releases) release(async () => ({ text: '{}' }));
      await Promise.all([first, second]);
      return;
    }
    try {
      releases[0](async () => ({ text: '{}' }));
      await first;
      const third = $('b-start').onclick();
      await sleep(80);
      check('retired Start completion cannot clear the newer Start duplicate guard',
        releases.length === 2, `${releases.length} construction(s)`);
      for (const release of releases) release(async () => ({ text: '{}' }));
      await Promise.all([second, third]);
    } finally {
      for (const release of releases) release(async () => ({ text: '{}' }));
      await Promise.all([first, second]);
    }
  });
}

async function unreadableKeyring(check) {
  const ring = withCreds(emptyKeyring(), 'anthropic', { apiKey: 'sk-probe-unreadable-193' });
  await visit(async ({ $, shown }) => {
    check('an unreadable encrypted keyring reopens the provider path, never a false connected summary',
      $('connect').dataset.step === 'provider' && shown('provider')
        && !$('connect').hasAttribute('data-collapsed')
        && $('connect-summary-text').dataset.icon !== 'check');
  }, ring, () => del(SECRETS, 'wrapping-key'));
}

async function reflow(check) {
  await visit(async ({ $, doc, win, frame, change }) => {
    change('apikey', 'sk-probe-reflow-193');
    change('model', 'namespace/long-unbroken-model-identifier-'.repeat(24));
    for (const theme of ['light', 'dark']) {
      doc.documentElement.dataset.theme = theme;
      for (const scale of [1, 2]) {
        doc.documentElement.style.fontSize = `${16 * scale}px`;
        for (const width of [320, 360, 390, 600, 840, 1280, 1440]) {
          frame.width = width;
          await sleep(20);
          for (const step of ['provider', 'fields', 'ready']) {
            if (step === 'provider') {
              if ($('connect').dataset.step === 'ready') $('b-connect-change').click();
              if ($('connect').dataset.step === 'fields') $('b-connect-back').click();
            }
            if (step === 'fields') $('b-connect-next').click();
            if (step === 'ready') {
              $('b-connect-next').click();
            }
            await until(() => $('connect').dataset.step === step, `enlarged ${step} step`);
            check(`${theme} ${width}px text${scale} ${step}: no page-wide overflow`,
              doc.documentElement.scrollWidth <= doc.documentElement.clientWidth,
              `${doc.documentElement.scrollWidth}/${doc.documentElement.clientWidth}`);
            const ids = [step === 'ready' ? 'b-connect-change' : 'b-connect-next', 'b-start-voice', 'b-start'];
            const misses = [];
            for (const id of ids) {
              $(id).focus();
              $(id).scrollIntoView({ block: 'center', behavior: 'instant' });
              const box = $(id).getBoundingClientRect();
              const hit = doc.elementFromPoint(box.x + box.width / 2, box.y + box.height / 2);
              if (box.left < 0 || box.right > win.innerWidth || box.top < 0 || box.bottom > win.innerHeight
                  || !(hit === $(id) || $(id).contains(hit))) misses.push(id);
            }
            check(`${theme} ${width}px text${scale} ${step}: complete actions are reachable and not covered`,
              misses.length === 0, misses.join(','));
          }
        }
      }
    }
  });
}
async function declaredFieldsAndManualModel(check) {
  await visit(async ({ $, change, shown, win, microphones }) => {
    for (const choice of PROVIDER_CHOICES) {
      change('provider', choice.id, 'change');
      check(`${choice.id}: progressive views preserve provider-declared field rules`,
        $('field-key').hidden === !choice.needsKey && $('field-base').hidden === !choice.local
          && $('field-model').hidden === !choice.picksModel
          && $('field-wrapmodel').hidden === !choice.picksModel);
    }
    change('provider', 'custom', 'change');
    change('baseurl', 'http://127.0.0.1:11993/v1');
    let calls = 0;
    win.fetch = async () => {
      calls++;
      return new win.Response('{"error":{"message":"No model-list endpoint"}}', { status: 404 });
    };
    $('b-check').click();
    await until(() => /type the model/i.test($('model-note').textContent), 'manual model recovery');
    check('a model-list failure stays failed and preserves visible manual model recovery',
      /failed/i.test($('connect-status')?.textContent || '') && shown('model')
        && !$('connect').hasAttribute('data-collapsed'), $('model-note').textContent);
    change('model', 'a-known-installed-model');
    change('stt', 'off', 'change');
    if ($('b-connect-next')) {
      $('b-connect-next').click();
      await until(() => $('connect').dataset.step === 'ready', 'manual model ready');
      check('a known local model can become ready without retrying a failed list request', calls === 1);
    }
    $('b-start').click();
    await until(() => !$('panel-interview').hidden, 'manual-model typed interview');
    const saved = credsFor(await loadCredentials(), 'custom');
    check('manual model and local address reach the original provider/keyring path',
      saved.model === 'a-known-installed-model' && saved.baseUrl === 'http://127.0.0.1:11993/v1');
    check('local discovery/manual-model/Type needs no microphone', microphones() === 0);
  });
}
