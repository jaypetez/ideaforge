// The app shell on a phone: a fixed bottom navigation bar on Ideas and Start/Settings, a
// top app bar on Interview and Done, and nothing that ever sits on top of the content.
//
// Layout claims, so they are measured off real boxes in a 390px frame (an iPhone 12-15
// width) rather than read off class names. The one that matters most is "covers no
// content": a fixed bar that overlaps the last control on the page looks fine in every
// desktop screenshot and makes Start, Back up ideas or the version line untappable on the
// device the bar exists for.

import { createSession, setSynthesis, setTitle } from '../../src/core/session.js';
import { seedTurn, submitAnswer } from '../../src/runtime/turn.js';
import { deleteSession, saveSession } from '../../src/store/sessions.js';

const BOOT_MS = 4000;
const settle = (ms = 300) => new Promise((resolve) => setTimeout(resolve, ms));

function loadFrame(src, width, { sandbox = null } = {}) {
  return new Promise((resolve, reject) => {
    const frame = document.createElement('iframe');
    if (sandbox !== null) frame.setAttribute('sandbox', sandbox);
    frame.width = width;
    frame.height = 760;
    frame.style.position = 'absolute';
    frame.style.left = '-10000px';
    frame.onload = () => resolve(frame);
    frame.onerror = () => reject(new Error(`iframe failed to load ${src}`));
    frame.src = src;
    document.body.append(frame);
  });
}

function activeSession(id, now) {
  return seedTurn(createSession({ id, now }), { now });
}

function doneSession(id, now) {
  let session = submitAnswer(activeSession(id, now), { text: 'A shell probe idea', now: now + 1 });
  session = setSynthesis(session, {
    text: '## Task\nBuild a shell probe idea.', assumptions: [], openQuestions: [], now: now + 2,
  });
  return setTitle(session, 'Shell probe result', now + 3);
}

export default async function run(check) {
  const now = Date.now();
  const sessions = [activeSession('s_shell_active', now), doneSession('s_shell_done', now + 10)];
  for (const session of sessions) await saveSession(session);

  const frames = [];
  try {
    // Before boot() runs show(): a sandbox with no script permission paints the page exactly
    // as a phone does while app.js is still loading. Without a shipped data-panel the desktop
    // grid squeezed the title into one letter per line and the nav then jumped to the bottom.
    const early = await loadFrame('/index.html', 390, { sandbox: 'allow-same-origin' });
    frames.push(early);
    await settle(500);
    {
      const doc = early.contentDocument;
      const win = early.contentWindow;
      const h1 = doc.querySelector('.bar h1').getBoundingClientRect();
      const lineHeight = parseFloat(win.getComputedStyle(doc.querySelector('.bar h1')).lineHeight);
      check('before boot, index.html ships body[data-panel="setup"]',
        doc.body.dataset.panel === 'setup', String(doc.body.dataset.panel));
      check('before boot, a phone already paints the bottom navigation and a one-line title',
        win.getComputedStyle(doc.querySelector('.bar-right')).position === 'fixed'
          && h1.height <= lineHeight * 1.5,
        `${win.getComputedStyle(doc.querySelector('.bar-right')).position}; h1 ${h1.width}x${h1.height}`);
    }

    const frame = await loadFrame('/index.html', 390);
    frames.push(frame);
    await settle(BOOT_MS);
    const win = frame.contentWindow;
    const doc = frame.contentDocument;
    const $ = (id) => doc.getElementById(id);
    const nav = doc.querySelector('.bar-right');
    const root = doc.documentElement;
    const items = ['b-library', 'b-nav-new', 'b-settings'].map($);
    const shown = (el) => !!el && el.getClientRects().length > 0
      && win.getComputedStyle(el).visibility !== 'hidden';
    const current = () => items.filter((item) => item.getAttribute('aria-current') === 'page')
      .map((item) => item.id).join(',');

    check('the shell frame is phone width', win.innerWidth === 390, `${win.innerWidth}px`);

    /** Scroll to the very bottom, then prove no visible box in #main reaches under the bar. */
    const coversNothing = (label) => {
      win.scrollTo(0, doc.documentElement.scrollHeight);
      const top = nav.getBoundingClientRect().top;
      const covered = [...$('main').querySelectorAll('*')]
        // A closed <details> still reports its unrendered children's boxes; they are not on screen.
        .filter((el) => !el.parentElement.closest('details:not([open])') || el.matches('details > summary'))
        .filter((el) => shown(el) && el.getBoundingClientRect().height > 0)
        .filter((el) => el.getBoundingClientRect().bottom > top + 0.5)
        .map((el) => `${el.id || el.className || el.tagName}@${Math.round(el.getBoundingClientRect().bottom)}`);
      check(`${label}: the bottom navigation covers no content when scrolled to the end`,
        covered.length === 0, `bar top ${top}; under it: ${covered.slice(0, 5).join(' | ')}`);
      win.scrollTo(0, 0);
    };

    const bottomNav = (label, expected) => {
      const style = win.getComputedStyle(nav);
      const rect = nav.getBoundingClientRect();
      check(`${label}: the navigation is a bar fixed to the bottom of the viewport`,
        style.position === 'fixed' && Math.abs(rect.bottom - root.clientHeight) < 1
          && rect.left === 0 && Math.abs(rect.right - root.clientWidth) < 1,
        `${style.position} ${rect.left},${rect.top} → ${rect.right},${rect.bottom}`
          + ` in ${root.clientWidth}x${root.clientHeight}`);
      const sizes = items.map((item) => item.getBoundingClientRect());
      check(`${label}: all three destinations show, each at least 48 by 48`,
        items.every(shown) && sizes.every((r) => r.width >= 48 && r.height >= 48),
        sizes.map((r) => `${Math.round(r.width)}x${Math.round(r.height)}`).join(' '));
      check(`${label}: each destination has an icon and a visible label`,
        items.every((item) => {
          const labelEl = item.querySelector('.nav-label');
          return win.getComputedStyle(item, '::before').maskImage !== 'none'
            && labelEl && shown(labelEl) && labelEl.getBoundingClientRect().width > 1;
        }));
      check(`${label}: aria-current marks exactly the ${expected} destination`,
        current() === expected, current() || 'none');
      coversNothing(label);
    };

    check('the shell names the panel on body', doc.body.dataset.panel === 'setup',
      doc.body.dataset.panel);
    bottomNav('Start', 'b-nav-new');

    /** Keyboard focus scrolls into view ignoring fixed boxes, so prove it lands above the bar. */
    const focusClear = (label, el) => {
      win.scrollTo(0, 0);
      const before = el.getBoundingClientRect().top;
      // focus() alone centres the element, which no keyboard user gets; Tab scrolls the
      // minimum distance, as scrollIntoView 'nearest' does, and that is the case that hides.
      el.focus({ preventScroll: true });
      el.scrollIntoView({ block: 'nearest', inline: 'nearest' });
      const rect = el.getBoundingClientRect();
      const top = nav.getBoundingClientRect().top;
      check(`${label}: a focused control below the fold is not hidden under the navigation`,
        before > root.clientHeight && doc.activeElement === el && rect.bottom <= top + 0.5,
        `started at ${Math.round(before)} of ${root.clientHeight}; focused bottom ${rect.bottom}, bar top ${top}`);
      win.scrollTo(0, 0);
    };
    focusClear('Start', $('b-start'));

    $('b-library').click();
    await settle();
    check('Ideas sets body[data-panel="library"]', doc.body.dataset.panel === 'library',
      doc.body.dataset.panel);
    bottomNav('Ideas', 'b-library');
    const lastCard = [...$('library-rows').querySelectorAll('.idea-card')].at(-1);
    focusClear('Ideas', [...lastCard.querySelectorAll('button')]
      .filter((b) => !b.closest('details:not([open])')).at(-1));

    $('b-settings').click();
    await settle();
    bottomNav('Settings', 'b-settings');

    $('b-nav-new').click();
    await settle();
    check('New idea returns to the start screen', !$('panel-setup').hidden
      && current() === 'b-nav-new', current());

    // Open the active idea: a detail screen, so a top app bar and no bottom navigation.
    $('b-library').click();
    await settle();
    const card = $('library-rows').querySelector('[data-session-id="s_shell_active"]');
    [...card.querySelectorAll('button')].find((b) => b.textContent === 'Continue').click();
    await settle(800);
    check('Continue opens the interview', !$('panel-interview').hidden
      && doc.body.dataset.panel === 'interview', doc.body.dataset.panel);
    const bar = doc.querySelector('.bar').getBoundingClientRect();
    check('Interview: the navigation is not a fixed bottom bar',
      win.getComputedStyle(nav).position !== 'fixed' && !shown($('b-nav-new'))
        && items.every((item) => item.getBoundingClientRect().bottom <= bar.bottom + 0.5),
      `${win.getComputedStyle(nav).position}; new idea shown: ${shown($('b-nav-new'))}`);
    check('Interview: no destination is current on a detail screen', current() === '', current());
    const back = $('b-library').getBoundingClientRect();
    const settings = $('b-settings').getBoundingClientRect();
    const meter = $('meter').getBoundingClientRect();
    check('Interview: the top app bar holds back, title, meter and settings on one row',
      shown($('meter')) && back.left < meter.left && meter.right <= settings.left
        && [meter, settings].every((r) => Math.abs((r.top + r.bottom) / 2 - (back.top + back.bottom) / 2) < 4),
      `back ${back.left},${back.top} meter ${meter.left},${meter.top} settings ${settings.left},${settings.top}`);
    check('Interview: back and settings are at least 48 by 48',
      [back, settings].every((r) => r.width >= 48 && r.height >= 48),
      [back, settings].map((r) => `${r.width}x${r.height}`).join(' '));
    check('Interview: the back arrow is named for where it goes',
      $('b-library').getAttribute('aria-label') === 'Back to ideas');
    check('Interview: body carries no navigation clearance',
      parseFloat(win.getComputedStyle(doc.body).paddingBottom) === 0,
      win.getComputedStyle(doc.body).paddingBottom);

    // The finished idea opens on Done, also a detail screen.
    $('b-library').click();
    await settle();
    check('the back arrow returns to Ideas and drops its detail name',
      doc.body.dataset.panel === 'library' && !$('b-library').hasAttribute('aria-label'));
    const doneCard = $('library-rows').querySelector('[data-session-id="s_shell_done"]');
    [...doneCard.querySelectorAll('button')].find((b) => b.textContent === 'View result').click();
    await settle(500);
    check('Done: a top app bar, not a bottom navigation bar',
      doc.body.dataset.panel === 'done' && win.getComputedStyle(nav).position !== 'fixed'
        && shown($('b-library')) && !shown($('b-nav-new')) && current() === ''
        && !shown($('meter')),
      `${doc.body.dataset.panel} ${win.getComputedStyle(nav).position} ${current()}`
        + ` meter shown: ${shown($('meter'))}`);

    // 360px: the narrowest common Android width. Nothing may scroll sideways on any panel.
    frame.width = 360;
    await settle();
    check('the shell frame narrows to 360px', win.innerWidth === 360, `${win.innerWidth}px`);
    const overflow = (label) => {
      check(`${label} has no horizontal overflow at 360px`, root.scrollWidth <= root.clientWidth,
        `scrollWidth ${root.scrollWidth}, clientWidth ${root.clientWidth}`);
    };
    overflow('Done');
    $('b-library').click();
    await settle();
    overflow('Ideas');
    $('b-settings').click();
    await settle();
    overflow('Settings');
    $('b-library').click();
    await settle();
    const again = $('library-rows').querySelector('[data-session-id="s_shell_active"]');
    [...again.querySelectorAll('button')].find((b) => b.textContent === 'Continue').click();
    await settle(800);
    overflow('Interview');
  } finally {
    for (const frame of frames) frame.remove();
    for (const session of sessions) await deleteSession(session.id);
    for (const r of await navigator.serviceWorker.getRegistrations()) await r.unregister();
  }
}
