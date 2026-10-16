// Panel motion and guarded View Transitions (#108), against the real app in an iframe.
//
// document.startViewTransition defers its update callback past the click, so wrapping the
// wrong handler takes the gesture away from whatever needed it: Start voice and Resume prime
// speech synchronously, Share calls navigator.share before its first await. So the claim
// here is a count. A stubbed startViewTransition, installed on the frame's document, is
// called once per click on each of the three navigation buttons (Ideas — also the back arrow
// on a detail screen — New idea and Settings) and never for Start voice, Type instead, Send,
// Share, Copy or a library card's actions. Never under reduced motion (the media query is
// emulated through matchMedia, which is what app.js asks), never in voice mode, and a
// transition that throws or rejects still leaves the app on the right screen.
//
// Focus after navigation lands on the destination's heading. The interview's #answer, the
// voice stage and Done's primary keep their own targets; voice-stage, done, thread and
// turn-lifecycle probes pin those, and the audio policy probe pins the priming.

import { DIMENSION_IDS } from '../../src/core/dimensions.js';
import { createSession, setSynthesis, setTitle } from '../../src/core/session.js';
import { seedTurn, submitAnswer } from '../../src/runtime/turn.js';
import { deleteSession, listSessions, saveSession } from '../../src/store/sessions.js';
import { clearCredentials } from '../../src/store/secrets.js';
import { acknowledgeInference } from './fixtures/inference.js';

const ID = 's_motion_done';

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

function loadFrame() {
  return new Promise((resolve, reject) => {
    const frame = document.createElement('iframe');
    frame.width = 900;
    frame.height = 800;
    // Visible, so animations and the native View Transition actually run.
    frame.style.cssText = 'position:fixed;left:0;top:0;border:0;z-index:1';
    frame.onload = () => resolve(frame);
    frame.onerror = () => reject(new Error('motion iframe failed to load'));
    frame.src = '/index.html';
    document.body.append(frame);
  });
}

function visible(element) {
  return !!element && element.getClientRects().length > 0;
}

function finished(now) {
  let session = seedTurn(createSession({ id: ID, now }), { now });
  session = submitAnswer(session, { text: 'A helper that remembers names at conferences', now: now + 1 });
  session = setSynthesis(session, {
    text: '## Task\nBuild a helper that remembers names at conferences.',
    assumptions: [], openQuestions: [], now: now + 2,
  });
  return setTitle(session, 'Conference name helper', now + 3);
}

function scriptedModel() {
  return {
    use: async () => async (prompt, options) => {
      if (options && options.modelTier === 'complex') throw new Error('no wrap-up here');
      return {
        text: JSON.stringify({
          question: 'Which conference would you try it at first?', move: 'concretize',
          chips: [], facts: [],
          coverage: Object.fromEntries(DIMENSION_IDS.map((id) =>
            [id, { level: 'partial', gap: 'needs detail' }])),
        }),
        modelTierApplied: 'scripted-motion',
      };
    },
  };
}

export default async function run(check) {
  await import('./fixtures/fake-voice.js');
  const fake = window.__FakeVoice;
  await cleanStorage();
  await saveSession(finished(Date.now()));
  const frame = await loadFrame();
  const win = frame.contentWindow;
  const doc = frame.contentDocument;
  const $ = (id) => doc.getElementById(id);
  const errors = [];
  win.addEventListener('error', (event) => errors.push(String(event.message)));
  win.addEventListener('unhandledrejection', (event) => {
    errors.push(String(event.reason?.stack || event.reason));
    event.preventDefault();
  });
  win.fetch = async () => { throw new Error('network disabled in motion probe'); };
  win.claude = scriptedModel();
  fake.install(win, { script: [], speakMs: 400 });

  // ── the stub: counts, and behaves like the API (the update runs after the click returns)
  let calls = 0;
  let mode = 'ok';
  // What each update returned. A promise means the transition was held open across the
  // navigation's storage reads — a frozen frame that swallows taps and hides skeletons.
  const returned = [];
  const stub = (update) => {
    calls++;
    if (mode === 'throw') throw new win.DOMException('refused', 'InvalidStateError');
    const done = new Promise((resolve) => setTimeout(resolve, 0)).then(() => {
      const value = update();
      returned.push(value);
      return value;
    });
    if (mode === 'reject') {
      return {
        updateCallbackDone: done,
        ready: Promise.reject(new Error('transition skipped')),
        finished: Promise.reject(new Error('transition skipped')),
        skipTransition() {},
      };
    }
    return { updateCallbackDone: done, ready: done, finished: done, skipTransition() {} };
  };
  const useStub = () => Object.defineProperty(doc, 'startViewTransition', {
    configurable: true, writable: true, value: stub,
  });
  useStub();
  // The media query is emulated, both ways, through the matchMedia app.js asks: the machine
  // running the suite may itself prefer reduced motion (a Windows host with animation
  // effects off does), and the claims below must not depend on whose desk they run at.
  const realMatchMedia = win.matchMedia;
  const realReduced = realMatchMedia.call(win, '(prefers-reduced-motion: reduce)').matches;
  let reduced = false;
  win.matchMedia = (query) => (/prefers-reduced-motion:\s*reduce/.test(query)
    ? { matches: reduced, media: query, addEventListener() {}, removeEventListener() {} }
    : realMatchMedia.call(win, query));

  const heading = {
    settings: () => $('panel-setup').querySelector(':scope > h2.settings-only'),
    start: () => $('panel-setup').querySelector('.start-hero h2'),
    ideas: () => $('panel-library').querySelector('.section-head h2'),
  };
  const on = {
    settings: () => visible($('panel-setup')) && $('panel-setup').dataset.view === 'settings',
    start: () => visible($('panel-setup')) && $('panel-setup').dataset.view === 'start',
    ideas: () => visible($('panel-library')) && !$('library-rows').hasAttribute('aria-busy'),
  };
  const button = { settings: 'b-settings', start: 'b-nav-new', ideas: 'b-library' };
  const describe = () => JSON.stringify({
    calls, panel: doc.body.dataset.panel, view: $('panel-setup').dataset.view,
    active: doc.activeElement && (doc.activeElement.id || doc.activeElement.tagName
      + ':' + doc.activeElement.textContent.slice(0, 30)),
    err: $('err').textContent, errors,
  });

  /** Click one nav button; resolve once its screen is up and focus has had its chance. */
  async function nav(where) {
    $(button[where]).click();
    const arrived = await until(() => on[where]() && doc.activeElement === heading[where]());
    return !!arrived;
  }

  /** Assert a click leaves the transition count alone. */
  async function untouched(label, act, ready) {
    const before = calls;
    await act();
    const arrived = ready ? await until(ready) : true;
    await settle(60);
    check(label, !!arrived && calls === before, describe());
  }

  try {
    const booted = await until(() => /^IdeaForge v/.test($('version').textContent)
      && $('provider').options.length > 0 && visible($('panel-library')));
    check('motion: the app boots on Ideas', !!booted, describe());
    if (!booted) return;
    $('provider').value = 'artifact';
    $('provider').dispatchEvent(new win.Event('change'));
    $('stt').value = 'browser';
    $('stt').dispatchEvent(new win.Event('change'));
    check('booting draws no View Transition', calls === 0, describe());
    await acknowledgeInference(win);
    calls = 0;
    returned.length = 0;

    // ── the three nav buttons: one transition each, focus on the heading
    for (const where of ['settings', 'start', 'ideas']) {
      const before = calls;
      const arrived = await nav(where);
      check(`the ${where} nav button runs exactly one View Transition`, calls === before + 1,
        describe());
      check(`…arrives on ${where} with focus on its heading`, arrived, describe());
    }
    check('each update makes only the synchronous screen change: it returns no promise',
      returned.length === 3 && returned.every((value) => !(value && typeof value.then === 'function')),
      String(returned));

    // Two nav taps inside one frame: the later one is where the app ends up, even though
    // the browser runs the earlier transition's update first.
    $('b-library').click();
    $('b-settings').click();
    await settle(300);
    check('two quick nav taps land on the second destination',
      !!(await until(() => on.settings() && doc.activeElement === heading.settings())) && !on.ideas(),
      describe());
    await nav('ideas');

    check('every focused heading is a programmatic target, out of the Tab order',
      Object.values(heading).every((h) => h().getAttribute('tabindex') === '-1'));

    // ── library card actions: none of them is navigation
    const card = () => $('library-rows').querySelector(`[data-session-id="${ID}"]`);
    const shared = [];
    Object.defineProperty(win.navigator, 'share', {
      configurable: true, value: async (payload) => { shared.push(payload); },
    });
    Object.defineProperty(win.navigator, 'canShare', { configurable: true, value: () => true });
    Object.defineProperty(win.navigator, 'clipboard', {
      configurable: true, value: { writeText: async () => {} },
    });
    await untouched('a library card’s Share .md runs no View Transition', async () => {
      card().querySelector('.idea-more').click();
      const share = [...card().querySelectorAll('[popover] button')]
        .find((b) => b.textContent === 'Share .md');
      share.click();
    }, () => shared.length === 1);
    await untouched('a library card’s primary action runs no View Transition', () =>
      card().querySelector('.idea-actions > button:not(.idea-more)').click(),
    () => visible($('panel-done')) && !!$('output').dataset.source);

    // A transition's update runs after the click returns. A later tap that navigates first
    // wins: the deferred Settings must not land on top of the result it opened.
    {
      await nav('ideas');
      $('b-settings').click();
      card().querySelector('.idea-actions > button:not(.idea-more)').click();
      const opened = await until(() => visible($('panel-done')) && !!$('output').dataset.source);
      await settle(200);
      check('a nav superseded by a later tap does not land on top of it',
        !!opened && visible($('panel-done')) && !visible($('panel-setup')), describe());
    }

    // ── Done: Share and Copy keep their gesture
    await untouched('Done’s Share .md runs no View Transition', () => $('b-share').click(),
      () => shared.length === 2);
    await untouched('Done’s Copy markdown runs no View Transition', () => $('b-copy').click());

    // The back arrow on a detail screen is the Ideas button, and is navigation.
    {
      const before = calls;
      const arrived = await nav('ideas');
      check('the back arrow on Done runs one View Transition and lands on the Ideas heading',
        arrived && calls === before + 1, describe());
    }

    // ── Type instead and Send
    await nav('start');
    await untouched('Type instead runs no View Transition', () => $('b-start').click(),
      () => visible($('manual-interview')) && !$('answer').disabled
        && doc.activeElement === $('answer'));
    check('the interview keeps focus on #answer, not a heading', doc.activeElement === $('answer'),
      describe());
    $('answer').value = 'A notebook that remembers who I met and where.';
    $('answer').dispatchEvent(new win.Event('input', { bubbles: true }));
    await untouched('Send runs no View Transition', () => $('b-send').click(),
      () => !$('answer').disabled && /Which conference/.test($('question').textContent));
    {
      const before = calls;
      const arrived = await nav('ideas');
      check('the back arrow on a typed interview runs one View Transition',
        arrived && calls === before + 1, describe());
    }

    // ── Start voice: never wrapped, and voice mode suppresses the nav transition too
    await nav('start');
    await untouched('Start with voice runs no View Transition', () => $('b-start-voice').click(),
      () => visible($('voice-stage')));
    {
      const before = calls;
      // Hidden on the stage, but its handler is still the one a stray activation would reach.
      $('b-library').click();
      const left = await until(() => on.ideas() && !visible($('voice-stage')));
      check('in voice mode the Ideas button navigates with no View Transition',
        !!left && calls === before, describe());
    }

    // ── reduced motion
    reduced = true;
    try {
      for (const where of ['settings', 'ideas']) {
        const before = calls;
        const arrived = await nav(where);
        check(`under reduced motion the ${where} button runs no View Transition`,
          calls === before, describe());
        check(`…and still arrives on ${where} with its heading focused`, arrived, describe());
      }
    } finally {
      reduced = false;
    }

    // ── a transition that throws or rejects leaves the state right
    mode = 'throw';
    check('a startViewTransition that throws still reaches Settings, heading focused',
      await nav('settings'), describe());
    mode = 'reject';
    check('one whose ready/finished reject still reaches Ideas, heading focused',
      await nav('ideas'), describe());
    mode = 'ok';

    // ── no API: a plain call, and the panel's own keyframe entry plays instead
    Object.defineProperty(doc, 'startViewTransition', { configurable: true, value: undefined });
    {
      $('b-settings').click();
      const focused = await until(() => on.settings() && doc.activeElement === heading.settings());
      const entry = $('panel-setup').getAnimations().find((a) => a.animationName === 'panel-in');
      check('without the API the nav still works and focuses the heading', !!focused, describe());
      // The keyframe itself is CSS, so it follows the real media query, not the emulation.
      if (realReduced) {
        check('…the panel is marked for entry, and this reduced-motion host plays no panel-in',
          'enter' in $('panel-setup').dataset && !entry, describe());
      } else {
        check('…and the panel’s panel-in keyframe is playing while the heading already has focus',
          'enter' in $('panel-setup').dataset && !!entry && entry.playState === 'running',
          `${entry && entry.playState}; ${describe()}`);
      }
    }

    // ── the real API, end to end
    delete doc.startViewTransition;
    if (typeof doc.startViewTransition === 'function') {
      const arrived = await nav('ideas');
      check('the native View Transition reaches Ideas with the heading focused', arrived,
        describe());
      check('…and draws no panel-in under it', !$('panel-library').getAnimations()
        .some((a) => a.animationName === 'panel-in'), describe());
      // While a View Transition is active the browser hit-tests the root element, by
      // specification: a tap in the crossfade reaches nothing. The guard against that is
      // brevity, so pin it — the window closes within a few frames of the short token.
      $('b-settings').click();
      const began = await until(() => doc.documentElement.matches(':active-view-transition'), 2000);
      const t0 = performance.now();
      const ended = await until(() => !doc.documentElement.matches(':active-view-transition'), 2000);
      const took = performance.now() - t0;
      check('the native transition is short: a tap is unanswered for well under half a second',
        !!began && !!ended && took < 450 && on.settings(), `${Math.round(took)}ms; ${describe()}`);
    } else {
      check('this Chrome has document.startViewTransition', false);
    }

    await settle(400);
    check('motion: no uncaught error or unhandled rejection', errors.length === 0, errors.join('\n'));
  } catch (error) {
    check('motion probe', false, String(error?.stack || error) + '\n' + describe());
  } finally {
    try { win.speechSynthesis?.cancel(); } catch {}
    frame.remove();
    fake.restore();
    await cleanStorage();
  }
}
