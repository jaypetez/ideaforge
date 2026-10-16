// Toasts (#100): #note and #err float in a fixed region, a note hides itself, an error stays
// until it is dismissed, and nothing outlives the screen it was raised on.
//
// The bug this exists for: the wrap-up offer used to be a note, and "enough here to write
// up" was still sitting on the finished document after the wrap-up — a panel change never
// cleared anything. The fix is a clear inside show(), and the trap it sets is the opposite
// bug: a note said just BEFORE the show() that opens its screen is wiped unread. Both
// directions are asserted here with notes the app really says, not planted ones, except
// where the point is the clearing itself.
//
// Layout claims are hit tests, not class names: with both toasts up and carrying long text,
// document.elementFromPoint at Send, Skip, the app bar, the bottom navigation and the voice
// stage's Pause and Exit must still land on the control. A toast over Send looks fine in a
// screenshot and makes the interview unanswerable on the phone it was drawn for.

import { createSession, setSynthesis, setTitle } from '../../src/core/session.js';
import { DIMENSION_IDS } from '../../src/core/dimensions.js';
import { seedTurn, submitAnswer } from '../../src/runtime/turn.js';
import { deleteSession, listSessions, saveSession } from '../../src/store/sessions.js';
import { clearCredentials } from '../../src/store/secrets.js';
import { acknowledgeInference } from './fixtures/inference.js';

const NOTE_MS = 400;
const LONG = 'A deliberately long message that wraps onto several lines at phone width, '
  + 'because a toast that only ever carries "Copied." proves nothing about what it covers '
  + 'when the provider sends back a paragraph of error text instead.';
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function until(predicate, ms = 5000) {
  const deadline = performance.now() + ms;
  do {
    const value = await predicate();
    if (value) return value;
    await sleep(20);
  } while (performance.now() < deadline);
  return null;
}

async function cleanStorage() {
  for (const registration of await navigator.serviceWorker.getRegistrations()) {
    await registration.unregister();
  }
  for (const key of await caches.keys()) await caches.delete(key);
  for (const session of await listSessions()) await deleteSession(session.id);
  await clearCredentials();
  localStorage.clear();
}

function loadFrame(width, height) {
  return new Promise((resolve, reject) => {
    const frame = document.createElement('iframe');
    frame.width = width;
    frame.height = height;
    frame.style.position = 'absolute';
    frame.style.left = '-10000px';
    frame.onload = () => resolve(frame);
    frame.onerror = () => reject(new Error('toast iframe failed to load'));
    frame.src = '/index.html';
    document.body.append(frame);
  });
}

function turnResult() {
  return {
    question: 'Who would use the notebook on their first day?', move: 'concretize', chips: [],
    facts: [], coverage: Object.fromEntries(DIMENSION_IDS.map((id) =>
      [id, { level: 'partial', gap: 'needs detail' }])),
  };
}

const SYNTHESIS = {
  title: 'Toast probe idea', prompt: '## Task\nBuild the toast probe idea.',
  assumptions: [], openQuestions: [],
};

/** win.claude: turns fail while `failTurns` is set, the wrap-up always succeeds. */
function scriptedModel() {
  const model = { failTurns: false, calls: 0 };
  model.claude = {
    use: async () => async (prompt, options) => {
      model.calls += 1;
      if (options.modelTier === 'complex') {
        return { text: JSON.stringify(SYNTHESIS), modelTierApplied: 'scripted-toasts' };
      }
      if (model.failTurns) throw Object.assign(new Error('scripted refusal'), { code: 'not_granted' });
      return { text: JSON.stringify(turnResult()), modelTierApplied: 'scripted-toasts' };
    },
  };
  return model;
}

async function openApp(check, label, { width = 900, height = 800, stt = 'off', claude } = {}) {
  const frame = await loadFrame(width, height);
  const win = frame.contentWindow;
  const doc = frame.contentDocument;
  const $ = (id) => doc.getElementById(id);
  const errors = [];
  win.addEventListener('error', (event) => errors.push(String(event.message)));
  win.addEventListener('unhandledrejection', (event) => {
    errors.push(String(event.reason?.stack || event.reason));
    event.preventDefault();
  });
  win.fetch = async () => { throw new Error('network disabled in toast probe'); };
  if (claude) win.claude = claude;
  const booted = await until(() => /^IdeaForge v/.test($('version').textContent)
    && $('provider').options.length > 0);
  check(`${label}: the app boots`, !!booted);
  $('provider').value = 'artifact';
  $('provider').dispatchEvent(new win.Event('change'));
  $('stt').value = stt;
  $('stt').dispatchEvent(new win.Event('change'));
  await acknowledgeInference(win);
  $('toasts').dataset.noteMs = String(NOTE_MS);
  const shown = (el) => !!el && !el.hidden && el.getClientRects().length > 0;
  return { frame, win, doc, $, errors, shown };
}

/** Both toasts up with long text, exactly as say() and fail() leave them. */
function raiseBoth(app) {
  const { $ } = app;
  $('note').textContent = LONG;
  $('note').hidden = false;
  $('err').textContent = LONG;
  $('err').hidden = false;
  $('err-toast').hidden = false;
}

/**
 * Done's actions are the last row of the page, 72px above the bottom at full scroll, and the
 * toasts sit at the bottom there: hit-test them scrolled to the end, under a long error.
 */
function doneHitTest(app, check, label) {
  raiseBoth(app);
  app.win.scrollTo(0, app.doc.documentElement.scrollHeight);
  // One primary action and the overflow trigger (#106); whichever of Share and Copy is the
  // primary in this browser, it is the first button in the row.
  const primary = app.$('done-actions').querySelector('button:not(.done-more)');
  hitTest(app, check, `${label}, scrolled to the end`, [primary.id, 'b-done-more']);
  // The menu is in the top layer, over the toasts: every item in it takes its own tap.
  app.$('done-menu').showPopover();
  hitTest(app, check, `${label}, menu open`, [...app.$('done-menu').querySelectorAll('button')]
    .filter((button) => !button.hidden).map((button) => button.id));
  app.$('done-menu').hidePopover();
  app.win.scrollTo(0, 0);
}

/** Every point of a control a thumb might hit lands on it, not on a toast. */
function hitTest(app, check, label, ids) {
  const { $, doc, shown } = app;
  for (const id of ids) {
    const el = $(id);
    if (!shown(el)) { check(`${label}: #${id} is on screen to be hit-tested`, false); continue; }
    const r = el.getBoundingClientRect();
    const points = [[0.5, 0.5], [0.15, 0.2], [0.85, 0.2], [0.15, 0.8], [0.85, 0.8]]
      .map(([fx, fy]) => [r.left + r.width * fx, r.top + r.height * fy]);
    const misses = points.map(([x, y]) => [x, y, doc.elementFromPoint(x, y)])
      .filter(([, , hit]) => !hit || !(hit === el || el.contains(hit)))
      .map(([x, y, hit]) => `${Math.round(x)},${Math.round(y)} → ${hit ? hit.id || hit.className || hit.tagName : 'nothing'}`);
    check(`${label}: a tap on #${id} reaches it with both toasts showing`, misses.length === 0,
      misses.join(' | '));
  }
}

async function lifecycle(check) {
  const model = scriptedModel();
  const app = await openApp(check, 'lifecycle', { claude: model.claude });
  const { $, win, shown } = app;
  try {
    check('#toasts is a fixed region that lets taps through',
      win.getComputedStyle($('toasts')).position === 'fixed'
        && win.getComputedStyle($('toasts')).pointerEvents === 'none');

    // A real note, then nothing: it hides itself.
    $('b-check').click();
    check('a note is shown as a toast', !!(await until(() => shown($('note'))
      && /viewer is available/.test($('note').textContent))), $('note').textContent);
    check('a note hides itself after its interval',
      !!(await until(() => $('note').hidden, NOTE_MS * 5)), $('note').textContent);

    // A second note restarts the clock instead of inheriting the first one's.
    $('b-check').click();
    await until(() => shown($('note')));
    await sleep(NOTE_MS * 0.6);
    $('b-check').click();
    await sleep(NOTE_MS * 0.6);
    check('a new note restarts the auto-hide clock', shown($('note')),
      'hidden at 1.2 intervals after the first note');
    check('…and still hides on its own', !!(await until(() => $('note').hidden, NOTE_MS * 5)));

    // A note on Start does not follow the user into the interview.
    $('toasts').dataset.noteMs = '60000';
    $('b-check').click();
    await until(() => shown($('note')) && /viewer is available/.test($('note').textContent));
    $('b-start').click();
    const started = await until(() => shown($('panel-interview')) && !$('answer').disabled);
    check('the interview opens', !!started);
    check('changing panel clears the note raised on Start', $('note').hidden, $('note').textContent);
    raiseBoth(app);
    hitTest(app, check, 'desktop Interview', ['b-send', 'b-skip', 'b-library', 'b-settings']);
    $('b-err-dismiss').click();
    $('note').hidden = true;

    // An error stays until dismissed.
    $('toasts').dataset.noteMs = String(NOTE_MS);
    model.failTurns = true;
    $('answer').value = 'A notebook that remembers names for a forgetful volunteer.';
    $('answer').dispatchEvent(new win.Event('input', { bubbles: true }));
    $('b-send').click();
    check('a failed turn raises an error toast', !!(await until(() => shown($('err'))
      && shown($('err-toast')) && !$('answer').disabled)), $('err').textContent);
    await sleep(NOTE_MS * 3);
    check('an error does not hide itself', shown($('err')) && shown($('err-toast')),
      $('err').textContent);
    check('the dismiss button is a named icon button',
      $('b-err-dismiss').getAttribute('aria-label') === 'Dismiss error'
        && $('b-err-dismiss').textContent === '');
    $('b-err-dismiss').focus();
    $('b-err-dismiss').click();
    check('dismissing hides the error and its toast', $('err').hidden && $('err-toast').hidden
      && $('err').textContent === '');
    check('dismissing does not drop keyboard focus on the body',
      app.doc.activeElement === $('main'), app.doc.activeElement?.id || app.doc.activeElement?.tagName);

    // The stale-note bug itself: a note and an error up during the interview, then Done.
    $('answer').value = 'Second answer, to raise the error once more.';
    $('answer').dispatchEvent(new win.Event('input', { bubbles: true }));
    $('b-send').click();
    await until(() => shown($('err')) && !$('answer').disabled);
    $('toasts').dataset.noteMs = '60000';
    $('note').textContent = 'There is enough here to write up.';
    $('note').hidden = false;
    $('b-wrap').click();
    const done = await until(() => shown($('panel-done')) && $('output').dataset.source);
    check('the wrap-up reaches Done', !!done);
    check('after wrap-up, #note is hidden on #panel-done', $('note').hidden, $('note').textContent);
    check('after wrap-up, #err is hidden on #panel-done', $('err').hidden && $('err-toast').hidden,
      $('err').textContent);

    // Wider than a phone the toasts sit at the bottom, where Done's actions can be too.
    doneHitTest(app, check, 'desktop Done');
    $('b-err-dismiss').click();
    $('note').hidden = true;

    // A note raised on Done, after the show() that opened it, is not cleared.
    $('b-copy').click();
    check('a note raised on Done survives', !!(await until(() => shown($('note')))),
      $('note').textContent);
    check('lifecycle: no uncaught error', app.errors.length === 0, app.errors.join('\n'));
  } finally {
    app.frame.remove();
  }
}

/** Resume with a provider that cannot start: the note is said after show(), so it survives. */
async function resumeNote(check) {
  const now = Date.now();
  await saveSession(seedTurn(createSession({ id: 's_toast_resume', now }), { now }));
  const app = await openApp(check, 'resume', {
    claude: { use: async () => null },
  });
  const { $, shown } = app;
  try {
    const row = await until(() => [...$('resume-rows').querySelectorAll('button')]
      .find((b) => b.textContent === 'continue'));
    check('the saved idea is offered to resume', !!row);
    row.click();
    const opened = await until(() => shown($('panel-interview')) && shown($('note')));
    check('a note said just after a panel change survives it',
      !!opened && /Continuing without a model/.test($('note').textContent),
      `${$('note').hidden ? 'hidden' : 'shown'}: ${$('note').textContent}`);
    check('resume: no uncaught error', app.errors.length === 0, app.errors.join('\n'));
  } finally {
    app.frame.remove();
  }
}

async function phoneLayout(check) {
  const app = await openApp(check, 'phone', { width: 390, height: 760, claude: scriptedModel().claude });
  const { $, shown, doc } = app;
  try {
    raiseBoth(app);
    hitTest(app, check, 'phone Start', ['b-library', 'b-nav-new', 'b-settings']);
    const nav = doc.querySelector('.bar-right').getBoundingClientRect();
    const lowest = Math.max(...['err-toast', 'note'].map((id) => $(id).getBoundingClientRect().bottom));
    check('phone Start: the toasts sit above the bottom navigation', lowest <= nav.top + 0.5,
      `lowest toast bottom ${lowest}, nav top ${nav.top}`);

    $('b-start').click();
    check('phone: the interview opens', !!(await until(() => shown($('panel-interview'))
      && !$('answer').disabled)));
    // The scroll that matters: Send pinned to the bottom of a full screen.
    app.win.scrollTo(0, doc.documentElement.scrollHeight);
    raiseBoth(app);
    hitTest(app, check, 'phone Interview', ['b-send', 'b-skip', 'b-library', 'b-settings']);
  } finally {
    app.frame.remove();
  }
}

async function voiceLayout(check, width) {
  await import('./fixtures/fake-voice.js');
  const fake = window.__FakeVoice;
  const label = `voice at ${width}px`;
  const app = await openApp(check, label, { width, height: 760, stt: 'browser', claude: scriptedModel().claude });
  try {
    fake.install(app.win, { script: [], speakMs: 50 });
    app.$('b-start-voice').click();
    const up = await until(() => app.shown(app.$('voice-stage')) && app.shown(app.$('b-voice-exit')));
    check(`${label}: the voice stage opens`, !!up);
    raiseBoth(app);
    hitTest(app, check, label, ['b-voice-pause', 'b-voice-exit']);
    app.$('b-voice-exit').click();
    await until(() => app.$('voice-stage').hidden);
  } finally {
    app.frame.remove();
    fake.restore();
  }
}

/** Done at phone width, opened from the start screen's recent ideas. */
async function phoneDone(check) {
  const now = Date.now();
  let session = submitAnswer(seedTurn(createSession({ id: 's_toast_done', now }), { now }),
    { text: 'A phone Done probe idea', now: now + 1 });
  session = setSynthesis(session, {
    text: '## Task\nBuild the phone Done probe idea.', assumptions: [], openQuestions: [], now: now + 2,
  });
  await saveSession(setTitle(session, 'Phone Done probe', now + 3));
  const app = await openApp(check, 'phone Done', { width: 390, height: 760, claude: scriptedModel().claude });
  try {
    const row = await until(() => [...app.$('resume-rows').querySelectorAll('button')]
      .find((b) => b.textContent === 'view'));
    check('phone Done: the finished idea is offered', !!row);
    row.click();
    check('phone Done: the document opens', !!(await until(() => app.shown(app.$('panel-done'))
      && app.$('output').dataset.source)));
    doneHitTest(app, check, 'phone Done');
  } finally {
    app.frame.remove();
  }
}

/** Make every IndexedDB write to the sessions store fail, as a full disk would. */
function breakSaves(win) {
  const put = win.IDBObjectStore.prototype.put;
  win.IDBObjectStore.prototype.put = function (...args) {
    if (this.name === 'sessions') throw new win.DOMException('scripted full disk', 'QuotaExceededError');
    return put.apply(this, args);
  };
}

/** A failed save is an error that persists, not a note that hides itself (manual wrap-up). */
async function saveFailure(check) {
  const model = scriptedModel();
  const app = await openApp(check, 'save failure', { claude: model.claude });
  const { $, win, shown } = app;
  try {
    $('b-start').click();
    await until(() => shown($('panel-interview')) && !$('answer').disabled);
    // Write it up appears from the fourth question on.
    for (const text of ['A notebook for names at conferences.', 'Mostly for the evening receptions.',
      'Me and two teammates on the same circuit.']) {
      $('answer').value = text;
      $('answer').dispatchEvent(new win.Event('input', { bubbles: true }));
      $('b-send').click();
      await until(() => !$('answer').disabled && $('asking').hidden);
    }
    check('save failure: Write it up is offered', !!(await until(() => shown($('b-wrap')))));
    breakSaves(win);
    $('b-wrap').click();
    check('save failure: the wrap-up reaches Done', !!(await until(() => shown($('panel-done'))
      && $('output').dataset.source)));
    await sleep(NOTE_MS * 3);
    check('a failed save is still on Done after several note intervals',
      shown($('err')) && /could not be saved/.test($('err').textContent),
      `${$('err').hidden ? 'hidden' : 'shown'}: ${$('err').textContent} | note: ${$('note').textContent}`);
  } finally {
    app.frame.remove();
  }
}

export default async function run(check) {
  await cleanStorage();
  try {
    await lifecycle(check);
    await cleanStorage();
    await resumeNote(check);
    await cleanStorage();
    await phoneLayout(check);
    await cleanStorage();
    await phoneDone(check);
    await cleanStorage();
    await saveFailure(check);
    await cleanStorage();
    await voiceLayout(check, 390);
    await cleanStorage();
    await voiceLayout(check, 900);
  } finally {
    await cleanStorage();
  }
}
