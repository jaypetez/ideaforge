// Loading and skeleton states (#107), against the real app in an iframe with a scripted,
// held `window.claude` — every model call waits until the probe releases it, so the busy
// state can be photographed mid-flight rather than guessed at.
//
// What this pins:
//
// - while a turn runs, the question card is aria-busy and shows the #asking skeleton in
//   place of the question, its bridge, chips and turn line; when the question lands both
//   go, together;
// - the skeleton is the size of a typical question card: the card's top and height move by
//   no more than 4px when the next question replaces it, at a phone and at a desktop width
//   (the answered pair joins the thread at submit, above the skeleton, not on arrival);
// - a failed turn (bank fallback) and navigating away mid-turn both leave no busy card and
//   no visible skeleton behind;
// - the write-up shows a skeleton in #output under aria-busy, cleared when the document is
//   drawn and when the call fails.
//
// Seeds go straight into IndexedDB and are opened through the library's Continue.

import { askQuestion, createSession } from '../../src/core/session.js';
import { DIMENSION_IDS } from '../../src/core/dimensions.js';
import { seedTurn, submitAnswer } from '../../src/runtime/turn.js';
import { deleteSession, listSessions, saveSession } from '../../src/store/sessions.js';
import { clearCredentials } from '../../src/store/secrets.js';

// The walkthrough's shape: a short bridge, a question of forty-odd characters (one line at
// a desktop width, two at a phone), a row of short chips. That is what the skeleton is drawn
// to match; a much longer question grows the card, which no placeholder can know in advance.
const OPEN = {
  bridge: 'Conferences, so a lot of names.',
  question: 'How many names did you lose last time?',
  chips: ['About ten', 'Most', 'One'],
};
const NEXT = [
  {
    bridge: 'Thirty names, evening ones first.',
    question: 'What would make you delete this app?',
    chips: ['Taps', 'Nagging', 'Ads'],
  },
  {
    bridge: 'Nothing between you and them.',
    question: 'What are the hard limits on time here?',
    chips: ['Seconds', 'No signal', 'Thumb'],
  },
  {
    bridge: 'A tool for three on one circuit.',
    question: 'What existing thing is this closest to?',
    chips: ['A card', 'Contacts', 'Notes'],
  },
];

async function until(predicate, ms = 5000) {
  const deadline = performance.now() + ms;
  do {
    let value;
    try { value = await predicate(); } catch { value = null; }
    if (value) return value;
    await new Promise((resolve) => setTimeout(resolve, 20));
  } while (performance.now() < deadline);
  return null;
}

const settle = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

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
    frame.onerror = () => reject(new Error('loading iframe failed to load'));
    frame.src = '/index.html';
    document.body.append(frame);
  });
}

function visible(element) {
  return !!element && element.getClientRects().length > 0
    && element.ownerDocument.defaultView.getComputedStyle(element).visibility !== 'hidden';
}

/** Every call is held until the probe releases it, with a turn, a synthesis or a failure. */
function heldModel() {
  const calls = [];
  return {
    calls,
    claude: {
      use: async () => (prompt, options) => new Promise((resolve, reject) => {
        calls.push({
          tier: options && options.modelTier,
          answer(json) { resolve({ text: JSON.stringify(json), modelTierApplied: 'scripted-loading' }); },
          fail(message) { reject(new Error(message)); },
        });
      }),
    },
  };
}

function turnJson(next) {
  return {
    ...next, move: 'concretize', facts: [],
    coverage: Object.fromEntries(DIMENSION_IDS.map((id) =>
      [id, { level: 'partial', gap: 'needs detail' }])),
  };
}

/** Four settled pairs (so Write it up is offered) and an open question shaped like OPEN. */
function seedSession(id) {
  let s = seedTurn(createSession({ id, now: 1 }), { now: 1 });
  s = submitAnswer(s, { text: 'A way to remember names at conferences.', now: 2 });
  const earlier = ['Who is it for?', 'Where would you use it?', 'What does it replace?'];
  earlier.forEach((question, i) => {
    s = askQuestion(s, { question, dimension: DIMENSION_IDS[i], source: 'model', now: 3 + i * 2 });
    s = submitAnswer(s, { text: `A considered answer number ${i + 1}.`, now: 4 + i * 2 });
  });
  return askQuestion(s, { ...OPEN, dimension: 'constraints', source: 'model', now: 20 });
}

async function openSeeded(win, $, id, check, describe) {
  const booted = await until(() => /^IdeaForge v/.test($('version').textContent)
    && $('provider').options.length > 0);
  if (!booted) { check('loading: the app boots', false, describe()); return false; }
  $('provider').value = 'artifact';
  $('provider').dispatchEvent(new win.Event('change'));
  $('stt').value = 'off';
  $('stt').dispatchEvent(new win.Event('change'));
  $('b-library').click();
  const card = await until(() => visible($('panel-library'))
    && $('library-rows').querySelector(`[data-session-id="${id}"]`));
  const resume = card && [...card.querySelectorAll('button')].find((b) => b.textContent === 'Continue');
  if (!resume) { check('loading: the seeded idea can be continued', false, describe()); return false; }
  resume.click();
  const opened = await until(() => visible($('manual-interview')) && $('question').textContent === OPEN.question
    && !$('answer').disabled);
  if (!opened) { check('loading: the seeded interview resumes', false, describe()); return false; }
  return true;
}

function answer($, win, text) {
  $('answer').value = text;
  $('answer').dispatchEvent(new win.Event('input', { bubbles: true }));
  $('b-send').click();
}

/** What a busy question card and a settled one look like, read off the DOM. */
function cardState($) {
  const card = $('question-card');
  return {
    busy: card.getAttribute('aria-busy'),
    skeleton: visible($('asking')),
    rows: ['bridge', 'question', 'chips', 'turnline'].filter((id) => visible($(id))
      && $(id).getBoundingClientRect().height > 2),
  };
}

async function layoutShift(width, height, report) {
  const tag = `${width}px`;
  const check = (label, ok, detail = '') => report(`${tag}: ${label}`, ok, ok ? '' : detail);
  await cleanStorage();
  const id = `s_loading_${width}`;
  await saveSession(seedSession(id));
  const frame = await loadFrame(width, height);
  const win = frame.contentWindow;
  const $ = (el) => frame.contentDocument.getElementById(el);
  const errors = [];
  win.addEventListener('error', (event) => errors.push(String(event.message)));
  win.addEventListener('unhandledrejection', (event) => {
    errors.push(String(event.reason?.stack || event.reason));
    event.preventDefault();
  });
  win.fetch = async () => { throw new Error('network disabled in loading probe'); };
  const model = heldModel();
  win.claude = model.claude;
  const describe = () => JSON.stringify({
    card: cardState($), question: $('question').textContent, err: $('err').textContent,
    calls: model.calls.length, errors,
  });
  try {
    if (!(await openSeeded(win, $, id, check, describe))) return;
    check('a settled card is not busy and shows no skeleton',
      cardState($).busy === null && !cardState($).skeleton, describe());

    for (const [i, next] of NEXT.entries()) {
      const before = model.calls.length;
      const sent = `An answer of reasonable length, number ${i + 1}.`;
      answer($, win, sent);
      const call = await until(() => model.calls.length > before && model.calls[model.calls.length - 1]);
      if (!call) { check('the turn reaches the model', false, describe()); return; }
      await settle(60);
      const busy = cardState($);
      const box = () => {
        const r = $('question-card').getBoundingClientRect();
        return { top: r.top + win.scrollY, height: r.height };
      };
      const skeletonBox = box();
      const skRows = [...$('asking').children].map((el) => el.getBoundingClientRect().height.toFixed(1)).join('/');
      if (i === 0) {
        // The pair joins the thread at submit, above the skeleton — not on arrival, which
        // would push the card down by a whole thread item as the question lands.
        const answers = [...$('thread').querySelectorAll('.thread-a-text')];
        check('mid-turn the answer just sent is already the last pair in #thread',
          answers.length > 0 && answers[answers.length - 1].textContent === sent,
          answers.map((a) => a.textContent).join(' | '));
        check('mid-turn the question card is aria-busy', busy.busy === 'true', describe());
        check('…and shows the skeleton in place of every real row',
          busy.skeleton && busy.rows.length === 0, describe());
        check('…whose bars are hidden from assistive technology',
          [...$('asking').children].filter((el) => el.id !== 'asking-label')
            .every((el) => el.getAttribute('aria-hidden') === 'true'), $('asking').outerHTML);
        check('…and the question keeps its live region in the accessibility tree',
          $('question').getClientRects().length > 0 && $('question').getAttribute('aria-live') === 'polite');
      }
      call.answer(turnJson(next));
      const landed = await until(() => $('question').textContent === next.question && $('asking').hidden
        && !$('answer').disabled);
      if (!landed) { check(`question ${i + 1} arrives`, false, describe()); return; }
      await settle(60);
      const after = box();
      const settled = cardState($);
      check(`question ${i + 1}: the card is no longer busy and every real row is back`,
        settled.busy === null && !settled.skeleton && settled.rows.length === 4, describe());
      const dTop = Math.abs(after.top - skeletonBox.top);
      const dHeight = Math.abs(after.height - skeletonBox.height);
      check(`question ${i + 1}: the card moves ≤4px as the question replaces the skeleton`,
        dTop <= 4 && dHeight <= 4,
        `rows ${skRows} vs ${['bridge', 'question', 'chips', 'turnline'].map((r) => $(r).getBoundingClientRect().height.toFixed(1)).join('/')}; top ${skeletonBox.top.toFixed(1)}→${after.top.toFixed(1)}, height ${skeletonBox.height.toFixed(1)}→${after.height.toFixed(1)}`);
    }
    check('no uncaught async error', errors.length === 0, errors.join('\n'));
  } catch (error) {
    check('layout probe', false, String(error?.stack || error) + '\n' + describe());
  } finally {
    frame.remove();
    await cleanStorage();
  }
}

async function failurePaths(report) {
  const check = (label, ok, detail = '') => report(label, ok, ok ? '' : detail);
  await cleanStorage();
  const id = 's_loading_paths';
  await saveSession(seedSession(id));
  const frame = await loadFrame(900, 800);
  const win = frame.contentWindow;
  const doc = frame.contentDocument;
  const $ = (el) => doc.getElementById(el);
  const errors = [];
  win.addEventListener('error', (event) => errors.push(String(event.message)));
  win.addEventListener('unhandledrejection', (event) => {
    errors.push(String(event.reason?.stack || event.reason));
    event.preventDefault();
  });
  win.fetch = async () => { throw new Error('network disabled in loading probe'); };
  const model = heldModel();
  win.claude = model.claude;
  const outputState = () => ({
    busy: $('output').getAttribute('aria-busy'),
    skeleton: !!$('output').querySelector('.output-skeleton'),
  });
  const describe = () => JSON.stringify({
    card: cardState($), output: outputState(), question: $('question').textContent,
    turnline: $('turnline').textContent, err: $('err').textContent,
    panel: doc.body.dataset.panel, calls: model.calls.map((c) => c.tier), errors,
  });
  const nextCall = async (before) => until(() => model.calls.length > before && model.calls[model.calls.length - 1]);
  try {
    if (!(await openSeeded(win, $, id, check, describe))) return;

    // ── a failed turn falls back to the bank, with nothing left busy ────────
    let before = model.calls.length;
    answer($, win, 'An answer that the model never reads.');
    let call = await nextCall(before);
    await settle(40);
    check('a turn about to fail is busy first', cardState($).busy === 'true', describe());
    call.fail('scripted outage');
    const fellBack = await until(() => $('asking').hidden && !$('answer').disabled
      && /built-in checklist/.test($('turnline').textContent));
    check('a failed turn lands a checklist question', !!fellBack, describe());
    const bank = cardState($);
    check('after the bank fallback the card is not busy and no skeleton is left',
      bank.busy === null && !bank.skeleton && bank.rows.includes('question'), describe());

    // ── a skipped question joins the thread while the next one is fetched ───
    before = model.calls.length;
    $('b-skip').click();
    call = await nextCall(before);
    await settle(40);
    const skippedItems = [...$('thread').querySelectorAll('.thread-a-text')];
    check('mid-skip the card is busy and the skipped pair is already last in #thread, as "Skipped"',
      cardState($).busy === 'true' && skippedItems.length > 0
        && skippedItems[skippedItems.length - 1].textContent === 'Skipped',
      skippedItems.map((a) => a.textContent).join(' | ') + ' ' + describe());
    call.answer(turnJson(NEXT[1]));
    const afterSkip = await until(() => $('question').textContent === NEXT[1].question && $('asking').hidden
      && !$('answer').disabled);
    check('the skip settles on the next question', !!afterSkip, describe());

    // ── navigating away mid-turn ────────────────────────────────────────────
    before = model.calls.length;
    answer($, win, 'An answer, and then I wander off to the library.');
    call = await nextCall(before);
    await settle(40);
    check('mid-turn, before leaving, the card is busy', cardState($).busy === 'true', describe());
    $('b-library').click();
    await until(() => visible($('panel-library')));
    check('leaving the interview mid-turn clears aria-busy and the skeleton',
      $('question-card').getAttribute('aria-busy') === null && $('asking').hidden, describe());
    call.answer(turnJson(NEXT[0]));
    await settle(150);
    check('…and the turn settling off-screen does not put them back',
      $('question-card').getAttribute('aria-busy') === null && $('asking').hidden, describe());
    const card = $('library-rows').querySelector(`[data-session-id="${id}"]`);
    [...card.querySelectorAll('button')].find((b) => b.textContent === 'Continue').click();
    const back = await until(() => visible($('manual-interview')) && $('question').textContent === NEXT[0].question
      && !$('answer').disabled);
    check('continuing shows the settled question, not a skeleton',
      !!back && cardState($).busy === null && !cardState($).skeleton, describe());

    // ── the write-up: success ───────────────────────────────────────────────
    before = model.calls.length;
    $('b-wrap').click();
    call = await nextCall(before);
    await settle(40);
    check('the write-up call is the strong tier', call && call.tier === 'complex', describe());
    check('while writing up, #output is aria-busy with a skeleton in it',
      visible($('panel-done')) && outputState().busy === 'true' && outputState().skeleton
        && visible($('output').querySelector('.output-skeleton')), describe());
    check('…whose bars are hidden from assistive technology',
      $('output').querySelector('.output-skeleton').getAttribute('aria-hidden') === 'true');
    call.answer({ title: 'Name keeper', prompt: '## Task\nBuild the name keeper.', assumptions: [], openQuestions: [] });
    const drawn = await until(() => $('done-title').textContent === 'Name keeper' && $('output').dataset.source);
    check('the drawn document replaces the skeleton and clears aria-busy',
      !!drawn && outputState().busy === null && !outputState().skeleton
        && /Build the name keeper/.test($('output').textContent), describe());

    // ── the write-up: failure ───────────────────────────────────────────────
    // Back into the interview through the library, so this never presses a Done button.
    $('b-library').click();
    await until(() => visible($('panel-library')));
    check('Done left behind keeps no busy output', outputState().busy === null, describe());
    frame.remove();
    await saveSession(seedSession('s_loading_fail'));
    const frame2 = await loadFrame(900, 800);
    const win2 = frame2.contentWindow;
    win2.fetch = async () => { throw new Error('network disabled in loading probe'); };
    const model2 = heldModel();
    win2.claude = model2.claude;
    win2.addEventListener('unhandledrejection', (event) => {
      errors.push(String(event.reason?.stack || event.reason));
      event.preventDefault();
    });
    const doc2 = frame2.contentDocument;
    const $2 = (el) => doc2.getElementById(el);
    const describe2 = () => JSON.stringify({
      panel: doc2.body.dataset.panel, library: $2('panel-library').dataset.libraryState,
      rows: [...$2('library-rows').querySelectorAll('.idea-card')].map((row) => row.dataset.sessionId),
      err: $2('err').textContent, calls: model2.calls.length,
    });
    try {
      if (!(await openSeeded(win2, $2, 's_loading_fail', check, describe2))) return;
      $2('b-wrap').click();
      const failing = await until(() => model2.calls.length > 0 && model2.calls[0]);
      await settle(40);
      check('a write-up about to fail is busy first',
        $2('output').getAttribute('aria-busy') === 'true' && !!$2('output').querySelector('.output-skeleton'),
        $2('output').outerHTML.slice(0, 300));
      failing.fail('scripted wrap-up outage');
      const failed = await until(() => /wrap-up call failed/.test($2('err').textContent));
      check('a failed write-up reports itself', !!failed, $2('err').textContent);
      check('…and leaves #output neither busy nor skeletal',
        $2('output').getAttribute('aria-busy') === null && !$2('output').querySelector('.output-skeleton')
          && $2('question-card').getAttribute('aria-busy') === null,
        $2('output').outerHTML.slice(0, 300));
    } finally {
      frame2.remove();
    }
    check('loading: no uncaught async error', errors.length === 0, errors.join('\n'));
  } catch (error) {
    check('loading paths probe', false, String(error?.stack || error) + '\n' + describe());
  } finally {
    frame.remove();
    await cleanStorage();
  }
}

/**
 * A first library read that fails (#111). renderLoading writes "Loading ideas…" into the
 * count, and the failed read leaves through settle() with nothing loaded; the count must
 * stop claiming to load. The failure is real: this page holds the database open at a newer
 * version, so the app's own indexedDB.open(…, 1) rejects with a VersionError, at boot and
 * when Ideas reads.
 */
async function failedFirstRead(report) {
  const check = (label, ok, detail = '') => report(label, ok, ok ? '' : detail);
  await cleanStorage();
  const newer = await new Promise((resolve, reject) => {
    const req = indexedDB.open('ideaforge', 2);
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
  const frame = await loadFrame(390, 760);
  const win = frame.contentWindow;
  win.addEventListener('unhandledrejection', (event) => event.preventDefault());
  const $ = (id) => frame.contentDocument.getElementById(id);
  try {
    await until(() => /^IdeaForge v/.test($('version').textContent));
    const counts = [];
    const observer = new win.MutationObserver(() => counts.push($('library-count').textContent));
    observer.observe($('library-count'), { childList: true, characterData: true, subtree: true });
    $('b-library').click();
    const settled = await until(() => $('panel-library').dataset.libraryState === 'unavailable'
      && !$('library-rows').hasAttribute('aria-busy'));
    observer.disconnect();
    check('a failed first library read leaves the loading state', !!settled,
      `${$('panel-library').dataset.libraryState} / ${$('library-rows').getAttribute('aria-busy')}`);
    check('…having said it was loading while it tried', counts.includes('Loading ideas…'),
      counts.join(' | '));
    check('…and the count no longer says it is loading',
      !/loading/i.test($('library-count').textContent), $('library-count').textContent);
  } finally {
    frame.remove();
    newer.close();
    await new Promise((resolve) => {
      const req = indexedDB.deleteDatabase('ideaforge');
      req.onsuccess = req.onerror = req.onblocked = () => resolve();
    });
  }
}

export default async function run(report) {
  await layoutShift(760, 900, report);
  await layoutShift(360, 640, report);
  await failurePaths(report);
  await failedFirstRead(report);
}
