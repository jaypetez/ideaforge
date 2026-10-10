// The wrap-up offer, as the inline #wrap-offer card above the composer (#99).
//
// What this pins, against the real app in an iframe with a scripted `window.claude`:
//
// - the card appears on the turn the engine offers — for coverage and for the soft
//   ceiling — carrying the engine's own sentence, and the shared #note stays out of it;
// - Keep going dismisses it, and it does not come back on the next turn. That is the #12
//   regression: the advisory used to repeat after every answer once coverage was reached;
// - Write it up runs the synthesis;
// - the hard ceiling still says "That is the last question." once, and the answer to that
//   question still wraps up on its own;
// - hands-free, the offer is still spoken with its yes/no, exactly once, and the card never
//   appears — on the voice stage or after Exit.
//
// Seeds go straight into IndexedDB and are opened through the library's Continue, so each
// scenario starts one answer away from the offer instead of a dozen turns of setup.

import { askQuestion, createSession } from '../../src/core/session.js';
import { DIMENSION_IDS } from '../../src/core/dimensions.js';
import { HARD_TURN_CEILING, SOFT_TURN_CEILING, wrapAdvisory } from '../../src/core/engine.js';
import { seedTurn, submitAnswer } from '../../src/runtime/turn.js';
import { deleteSession, listSessions, loadSession, saveSession } from '../../src/store/sessions.js';
import { clearCredentials } from '../../src/store/secrets.js';

const ANSWER = 'Couriers need the gate code and the buzzer note before they reach the door.';
const WRITE_UP = '## Task\nBuild the courier delivery notebook.';
const COVERAGE_TEXT = wrapAdvisory({ wrapOffered: false }, 'coverage');
const SOFT_TEXT = wrapAdvisory({ wrapOffered: false }, 'soft_ceiling');
const HARD_TEXT = wrapAdvisory({ wrapOffered: false }, 'hard_ceiling');
// Distinct wording per call, so the repeat tripwire never swaps in a bank question.
const QUESTIONS = [
  'Which building type gives couriers the most trouble finding the entrance?',
  'How should a note about a fierce dog be shown to the next rider?',
  'What happens when two customers share one apartment buzzer?',
  'Where would a rider glance at the notebook while still on the bike?',
  'Who should be allowed to edit an instruction somebody else wrote?',
  'When does an old delivery instruction stop being trustworthy?',
];

async function within(promise, describe, ms = 5000) {
  let timer;
  try {
    return await Promise.race([
      promise,
      new Promise((_, reject) => {
        timer = setTimeout(() => reject(new Error(`Timed out after ${ms}ms: ${describe}`)), ms);
      }),
    ]);
  } finally {
    clearTimeout(timer);
  }
}

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

/** Hold for a while and prove something stays true, rather than catching it once. */
async function remains(predicate, ms = 300) {
  const deadline = performance.now() + ms;
  do {
    if (!(await predicate())) return false;
    await new Promise((resolve) => setTimeout(resolve, 20));
  } while (performance.now() < deadline);
  return true;
}

async function cleanStorage() {
  await within((async () => {
    for (const registration of await navigator.serviceWorker.getRegistrations()) {
      await registration.unregister();
    }
    for (const key of await caches.keys()) await caches.delete(key);
    for (const session of await listSessions()) await deleteSession(session.id);
    await clearCredentials();
    localStorage.clear();
  })(), 'isolated storage cleanup');
}

function loadFrame() {
  return new Promise((resolve, reject) => {
    const frame = document.createElement('iframe');
    frame.width = 900;
    frame.height = 800;
    frame.style.position = 'absolute';
    frame.style.left = '-10000px';
    frame.onload = () => resolve(frame);
    frame.onerror = () => reject(new Error('wrap-offer iframe failed to load'));
    frame.src = '/index.html';
    document.body.append(frame);
  });
}

function visible(element) {
  return !!element && element.getClientRects().length > 0
    && element.ownerDocument.defaultView.getComputedStyle(element).visibility !== 'hidden';
}

/** A model that always answers; every dimension claimed `partial`, so nothing is gained. */
function scriptedModel() {
  const calls = [];
  let asked = 0;
  return {
    calls,
    claude: {
      use: async () => async (prompt, options) => {
        const tier = options && options.modelTier;
        calls.push({ tier });
        if (tier === 'complex') {
          return {
            text: JSON.stringify({
              title: 'Courier delivery notebook', prompt: WRITE_UP, assumptions: [], openQuestions: [],
            }),
            modelTierApplied: 'scripted-wrap-offer',
          };
        }
        const question = QUESTIONS[asked++ % QUESTIONS.length];
        return {
          text: JSON.stringify({
            question, move: 'concretize', chips: [], facts: [],
            coverage: Object.fromEntries(DIMENSION_IDS.map((id) =>
              [id, { level: 'partial', gap: 'needs detail' }])),
          }),
          modelTierApplied: 'scripted-wrap-offer',
        };
      },
    },
    turns() { return calls.filter((call) => call.tier !== 'complex').length; },
    syntheses() { return calls.filter((call) => call.tier === 'complex').length; },
  };
}

/** `answered` answered turns and one open question; coverage optionally already enough. */
function seedSession(id, answered, { ready = false } = {}) {
  let session = seedTurn(createSession({ id, now: 1 }), { now: 1 });
  for (let i = 0; i < answered; i++) {
    session = submitAnswer(session, { text: `${ANSWER} Stop ${i + 1}.`, now: 2 + i * 2 });
    session = askQuestion(session, {
      question: `Which instruction matters most at delivery stop ${i + 1}?`,
      dimension: 'outcome', source: 'model', now: 3 + i * 2,
    });
  }
  if (ready) {
    // Five covered and the rest partial: isReadyToWrap, so the next turn offers 'coverage'.
    const coverage = { ...session.coverage };
    DIMENSION_IDS.forEach((dim, index) => {
      coverage[dim] = { ...coverage[dim], level: index < 5 ? 'covered' : 'partial' };
    });
    session = { ...session, coverage };
  }
  return session;
}

function detail(app) {
  return JSON.stringify({
    panels: ['panel-setup', 'panel-interview', 'panel-library', 'panel-done']
      .filter((id) => visible(app.$(id))),
    question: app.$('question').textContent,
    offerHidden: app.$('wrap-offer').hidden,
    offerText: app.$('wrap-offer-text').textContent,
    note: app.$('note').hidden ? '' : app.$('note').textContent,
    error: app.$('err').textContent,
    voiceVisible: visible(app.$('voice-stage')),
    calls: app.model.calls.map((call) => call.tier || 'default'),
    spoken: app.fake.synthesis.spoken.map((u) => u.text).filter((t) => t.trim()),
    errors: app.errors,
  });
}

async function expect(app, check, label, predicate, ms) {
  const result = await until(predicate, ms);
  check(label, !!result, result ? '' : detail(app));
  return result;
}

const offerShowing = (app) => visible(app.$('wrap-offer')) && !app.$('wrap-offer').hidden;

async function withApp(check, label, scenario, { seed, voice = false } = {}) {
  await cleanStorage();
  if (seed) await saveSession(seed);
  let frame;
  let app;
  const fake = window.__FakeVoice;
  try {
    frame = await loadFrame();
    const win = frame.contentWindow;
    const $ = (id) => frame.contentDocument.getElementById(id);
    const errors = [];
    const violations = [];
    win.addEventListener('error', (event) => errors.push(String(event.message)));
    win.addEventListener('unhandledrejection', (event) => {
      errors.push(String(event.reason?.stack || event.reason));
      event.preventDefault();
    });
    win.addEventListener('securitypolicyviolation',
      (event) => violations.push(event.violatedDirective + ' blocked ' + event.blockedURI));
    fake.install(win, { script: [], speakMs: voice ? 40 : 10 });
    const model = scriptedModel();
    win.claude = model.claude;
    const requests = [];
    win.fetch = async (input) => {
      requests.push(String(input?.url || input));
      throw new Error('network disabled in wrap-offer probe');
    };
    app = { frame, win, $, fake, errors, model };
    if (!(await expect(app, check, `${label}: the app finishes asynchronous setup`, () =>
      /^IdeaForge v/.test($('version').textContent) && $('provider').options.length > 0))) return;
    $('provider').value = 'artifact';
    $('provider').dispatchEvent(new win.Event('change'));
    $('stt').value = voice ? 'browser' : 'off';
    $('stt').dispatchEvent(new win.Event('change'));
    if (seed) {
      $('b-library').click();
      const card = await until(() => visible($('panel-library'))
        && $('library-rows').querySelector(`[data-session-id="${seed.id}"]`));
      const resume = card && [...card.querySelectorAll('button')]
        .find((button) => button.textContent === 'Continue');
      if (!resume) { check(`${label}: the seeded idea can be continued`, false, detail(app)); return; }
      resume.click();
      if (!(await expect(app, check, `${label}: the seeded interview resumes on its open question`, () =>
        visible($('manual-interview')) && $('question').textContent === seed.turns.at(-1).question
          && !$('answer').disabled))) return;
    }
    await scenario(app);
    check(`${label}: no uncaught async error`, errors.length === 0, errors.join('\n'));
    check(`${label}: no provider network request`, requests.length === 0, requests.join('\n'));
    check(`${label}: no CSP violation`, violations.length === 0, violations.join('\n'));
  } catch (error) {
    check(label, false, String(error?.stack || error) + (app ? '\n' + detail(app) : ''));
  } finally {
    try {
      if (app && visible(app.$('voice-stage'))) app.$('b-voice-exit').click();
      await new Promise((resolve) => setTimeout(resolve, 100));
    } finally {
      frame?.remove();
      fake.restore();
      await cleanStorage();
    }
  }
}

/** Type and send one answer, then wait for the turn it starts to settle. */
async function answer(app, check, label, text = ANSWER) {
  const before = app.model.turns();
  app.$('answer').value = text;
  app.$('answer').dispatchEvent(new app.win.Event('input', { bubbles: true }));
  app.$('b-send').click();
  return expect(app, check, label, () =>
    app.model.turns() > before && !app.$('answer').disabled && app.$('asking').hidden);
}

export default async function run(check) {
  await import('./fixtures/fake-voice.js');

  // ── coverage: the card appears, Keep going dismisses it, and it stays gone ────────
  const ready = seedSession('s_wrap_offer_coverage', 3, { ready: true });
  await withApp(check, 'coverage offer', async (app) => {
    check('no offer is showing before the turn that makes it', !offerShowing(app), detail(app));
    if (!(await answer(app, check, 'the answer that reaches coverage settles'))) return;
    check('the wrap-up offer appears as the inline card, with the coverage advisory',
      offerShowing(app) && app.$('wrap-offer-text').textContent === COVERAGE_TEXT, detail(app));
    check('...above the composer, in the interview panel',
      app.$('panel-interview').contains(app.$('wrap-offer'))
        && (app.$('wrap-offer').compareDocumentPosition(app.$('answer'))
          & app.win.Node.DOCUMENT_POSITION_FOLLOWING) !== 0, detail(app));
    check('...offering Write it up and Keep going',
      visible(app.$('b-wrap-offer')) && !app.$('b-wrap-offer').disabled
        && app.$('b-wrap-offer').textContent === 'Write it up'
        && visible(app.$('b-wrap-keep')) && app.$('b-wrap-keep').textContent === 'Keep going',
      detail(app));
    check('the advisory no longer goes through the shared #note banner',
      app.$('note').hidden || !app.$('note').textContent.includes(COVERAGE_TEXT), detail(app));
    check('the offer is recorded on the saved idea',
      (await loadSession(ready.id))?.wrapOffered === true, detail(app));

    app.$('b-wrap-keep').click();
    check('Keep going dismisses the card', !offerShowing(app), detail(app));
    check('...without writing anything up or asking the model',
      app.model.syntheses() === 0 && visible(app.$('manual-interview')), detail(app));

    // The #12 regression: the next answers must not bring the offer back.
    for (let turn = 1; turn <= 2; turn++) {
      if (!(await answer(app, check, `the answer after Keep going settles (${turn})`))) return;
      check(`the offer does not come back on the next turn (${turn})`,
        await remains(() => !offerShowing(app)), detail(app));
    }
    check('nothing repeated the advisory in #note either',
      app.$('note').hidden || !app.$('note').textContent.includes(COVERAGE_TEXT), detail(app));
  }, { seed: ready });

  // ── answering without dismissing still retires the card ───────────────────────────
  const ignored = seedSession('s_wrap_offer_ignored', 3, { ready: true });
  await withApp(check, 'ignored offer', async (app) => {
    if (!(await answer(app, check, 'the offer turn settles'))) return;
    if (!(await expect(app, check, 'the coverage offer is showing', () => offerShowing(app)))) return;
    if (!(await answer(app, check, 'answering past the card settles'))) return;
    check('an answer given over the card retires it, and it does not return',
      await remains(() => !offerShowing(app)), detail(app));
  }, { seed: ignored });

  // ── Write it up runs the synthesis ────────────────────────────────────────────────
  const writing = seedSession('s_wrap_offer_write', 3, { ready: true });
  await withApp(check, 'write it up from the card', async (app) => {
    if (!(await answer(app, check, 'the offer turn settles'))) return;
    if (!(await expect(app, check, 'the card is showing', () => offerShowing(app)))) return;
    app.$('b-wrap-offer').click();
    if (!(await expect(app, check, 'Write it up runs exactly one synthesis and shows the result', () =>
      app.model.syntheses() === 1 && visible(app.$('panel-done'))
        && (app.$('output').dataset.source || '').includes(WRITE_UP)))) return;
    const saved = await loadSession(writing.id);
    check('the written-up idea is saved as done', saved?.status === 'done'
      && saved.synthesis.text === WRITE_UP, JSON.stringify(saved && saved.synthesis));
    check('the card is gone with the interview', app.$('wrap-offer').hidden, detail(app));
  }, { seed: writing });

  // ── the soft ceiling offers through the same card ─────────────────────────────────
  const soft = seedSession('s_wrap_offer_soft', SOFT_TURN_CEILING - 1);
  await withApp(check, 'soft ceiling offer', async (app) => {
    if (!(await answer(app, check, 'the answer past the soft ceiling settles'))) return;
    check('the soft ceiling shows its own advisory in the card',
      offerShowing(app) && app.$('wrap-offer-text').textContent === SOFT_TEXT, detail(app));
    app.$('b-wrap-keep').click();
    if (!(await answer(app, check, 'the answer after the soft-ceiling Keep going settles'))) return;
    check('the soft-ceiling offer is made once', await remains(() => !offerShowing(app)), detail(app));
  }, { seed: soft });

  // ── the hard ceiling: the last question is announced once, then it wraps up ───────
  const hard = seedSession('s_wrap_offer_hard', HARD_TURN_CEILING - 2);
  await withApp(check, 'hard ceiling', async (app) => {
    if (!(await answer(app, check, 'the answer that reaches the last question settles'))) return;
    check('the last question carries the hard-ceiling advisory in the card',
      offerShowing(app) && app.$('wrap-offer-text').textContent === HARD_TEXT
        && app.$('turnline').textContent.startsWith(`question ${HARD_TURN_CEILING} of up to`),
      detail(app));
    app.$('b-wrap-keep').click();
    check('Keep going leaves the last question open', !offerShowing(app)
      && !app.$('answer').disabled, detail(app));
    app.$('answer').value = ANSWER;
    app.$('b-send').click();
    if (!(await expect(app, check, 'answering the last question still wraps up on its own', () =>
      app.model.syntheses() === 1 && visible(app.$('panel-done'))
        && (app.$('output').dataset.source || '').includes(WRITE_UP)))) return;
    check('...without another model turn past the ceiling',
      app.model.turns() === 1, detail(app));
  }, { seed: hard });

  // ── hands-free: the spoken offer is unchanged, and the card never appears ─────────
  const spokenSeed = seedSession('s_wrap_offer_voice', 3, { ready: true });
  await withApp(check, 'hands-free offer', async (app) => {
    const said = (text) => [{ at: 30, interim: text }, { at: 120, final: text, confidence: 0.9 }];
    app.fake.script([
      said(`${ANSWER} over`),
      said('keep going'),
      said('Riders read it at the kerb before they lock the bike over'),
    ]);
    app.$('handsfree').checked = true;
    app.$('handsfree').dispatchEvent(new app.win.Event('change'));
    const spoken = () => app.fake.synthesis.spoken.map((u) => u.text);
    const offers = () => spoken().filter((text) => text.includes(COVERAGE_TEXT));
    if (!(await expect(app, check, 'hands-free speaks the coverage advisory with its yes/no question', () =>
      offers().some((text) => /Shall I write it up\? Say yes, or say keep going\./.test(text)), 15000))) return;
    if (!(await expect(app, check, '"keep going" carries on to a second answer', () =>
      app.model.turns() >= 2 && spoken().some((text) => text.includes('carrying on')), 20000))) return;
    check('the card never appears on the voice stage',
      !offerShowing(app) && visible(app.$('voice-stage')), detail(app));
    check('the spoken offer is made once, not after every answer',
      await remains(() => offers().length === 1, 600), detail(app));
    check('the spoken offer is recorded on the saved idea',
      (await loadSession(spokenSeed.id))?.wrapOffered === true, detail(app));
    check('no synthesis ran on a "keep going"', app.model.syntheses() === 0, detail(app));
    app.$('b-voice-exit').click();
    if (!(await expect(app, check, 'Exit returns to the manual interview', () =>
      visible(app.$('manual-interview')) && !visible(app.$('voice-stage')), 8000))) return;
    check('...and the spoken offer is not repeated as a card after Exit',
      await remains(() => !offerShowing(app)), detail(app));
  }, { seed: spokenSeed, voice: true });
}
