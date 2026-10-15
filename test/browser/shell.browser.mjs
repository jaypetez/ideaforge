// One adaptive app shell: bottom/contextual bars below 600px, compact/expanded labelled
// rails above it, and nothing that ever sits on top of the content.
//
// Layout claims, so they are measured off real boxes in a 390px frame (an iPhone 12-15
// width) rather than read off class names. The one that matters most is "covers no
// content": a fixed bar that overlaps the last control on the page looks fine in every
// desktop screenshot and makes Start, Back up ideas or the version line untappable on the
// device the bar exists for.

import {
  createSession, renameSession, sessionDisplayTitle, setSynthesis, setTitle,
} from '../../src/core/session.js';
import { seedTurn, submitAnswer } from '../../src/runtime/turn.js';
import { deleteSession, saveSession } from '../../src/store/sessions.js';

const BOOT_MS = 4000;
const settle = (ms = 300) => new Promise((resolve) => setTimeout(resolve, ms));
const LONG_TITLE = '<img src=x onerror=window.__shellMarkup=1> A long idea title that remains literal across navigation';

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

function activeSession(id, now, name = '') {
  const session = seedTurn(createSession({ id, now }), { now });
  return name ? renameSession(session, name, now + 1) : session;
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
  const sessions = [activeSession('s_shell_active', now, LONG_TITLE), doneSession('s_shell_done', now + 10)];
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
    const captures = { media: 0, recognition: 0 };
    win.navigator.mediaDevices.getUserMedia = async () => {
      captures.media++;
      throw new Error('Navigation must not request a microphone');
    };
    win.SpeechRecognition = win.webkitSpeechRecognition = class {
      start() { captures.recognition++; }
      stop() {}
      abort() {}
    };
    const shown = (el) => !!el && el.getClientRects().length > 0
      && win.getComputedStyle(el).visibility !== 'hidden';
    const current = () => items.filter((item) => item.getAttribute('aria-current') === 'page')
      .map((item) => item.id).join(',');

    /**
     * Wait after a navigation or resize until what is measured is the settled page. A nav
     * click may run as a View Transition (#108), and measuring during its update read a
     * transient scrollbar as horizontal overflow: let the time pass, then wait for the
     * transition to end, then one frame for layout to land.
     */
    const settled = async (ms = 300) => {
      await settle(ms);
      const deadline = performance.now() + 3000;
      while (doc.documentElement.matches(':active-view-transition') && performance.now() < deadline) {
        await settle(20);
      }
      await new Promise((resolve) => win.requestAnimationFrame(() => resolve()));
    };

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

    // This profile holds the two ideas saved above, so boot opens on Ideas (#103), not Start.
    check('with saved ideas the shell boots to Ideas and names it on body',
      doc.body.dataset.panel === 'library' && current() === 'b-library',
      `${doc.body.dataset.panel} / ${current()}`);
    $('b-nav-new').click();
    await settled();
    check('the shell names the panel on body', doc.body.dataset.panel === 'setup',
      doc.body.dataset.panel);
    bottomNav('Start', 'b-nav-new');

    /** Keyboard focus scrolls into view ignoring fixed boxes, so prove it lands above the bar. */
    const focusClear = (label, el, mustScroll = true) => {
      win.scrollTo(0, 0);
      const before = el.getBoundingClientRect().top;
      // focus() alone centres the element, which no keyboard user gets; Tab scrolls the
      // minimum distance, as scrollIntoView 'nearest' does, and that is the case that hides.
      el.focus({ preventScroll: true });
      el.scrollIntoView({ block: 'nearest', inline: 'nearest' });
      const rect = el.getBoundingClientRect();
      const top = nav.getBoundingClientRect().top;
      const style = win.getComputedStyle(el);
      const ring = style.outlineStyle === 'none' ? 0
        : parseFloat(style.outlineWidth) + parseFloat(style.outlineOffset);
      check(`${label}: nearest scrolling keeps the focused control and its outline above navigation`,
        (!mustScroll || before > root.clientHeight) && doc.activeElement === el
          && rect.top - ring >= -0.5 && rect.bottom + ring <= top + 0.5,
        `started at ${Math.round(before)} of ${root.clientHeight}; focused edge ${rect.bottom + ring}, bar top ${top}`);
      win.scrollTo(0, 0);
    };
    focusClear('concise Start', $('b-start'), false);
    const originalProvider = $('provider').value;
    $('provider').value = 'custom';
    $('provider').dispatchEvent(new win.Event('change'));
    focusClear('Start required local fields', $('b-start'));
    $('provider').value = originalProvider;
    $('provider').dispatchEvent(new win.Event('change'));

    $('b-library').click();
    await settled();
    check('Ideas sets body[data-panel="library"]', doc.body.dataset.panel === 'library',
      doc.body.dataset.panel);
    bottomNav('Ideas', 'b-library');
    const lastCard = [...$('library-rows').querySelectorAll('.idea-card')].at(-1);
    focusClear('Ideas', [...lastCard.querySelectorAll('button')]
      .filter((b) => !b.closest('details:not([open]), [popover]:not(:popover-open)')).at(-1));

    $('b-settings').click();
    await settled();
    bottomNav('Settings', 'b-settings');

    $('b-nav-new').click();
    await settled();
    check('New idea returns to the start screen', !$('panel-setup').hidden
      && current() === 'b-nav-new', current());

    // Shared typography must grow the fixed bar's clearance as well as its labels. An
    // unchanged 64px token hid keyboard focus even at 100% (a selected item was 64.8px).
    const originalTheme = root.dataset.theme;
    const originalFont = root.style.fontSize;
    try {
      for (const theme of ['light', 'dark']) {
        root.dataset.theme = theme;
        for (const scale of [150, 175, 200]) {
          root.style.fontSize = `${scale}%`;
          for (const width of [320, 390]) {
            frame.width = width;
            await settled(100);
            const label = `${theme} Start at ${width}px and ${scale}% text`;
            bottomNav(label, 'b-nav-new');
            if (width === 320 && scale === 175) {
              const selected = $('b-nav-new');
              const text = selected.querySelector('.nav-label').getBoundingClientRect();
              const line = parseFloat(win.getComputedStyle(selected).lineHeight);
              check(`${theme}: the 175% regression really wraps the selected label onto two lines`,
                text.height > line * 1.5, `${text.height}px / ${line}px`);
            }
            focusClear(label, $('b-start'));
            check(`${label}: no sideways scrolling`,
              root.scrollWidth <= root.clientWidth, `${root.scrollWidth}/${root.clientWidth}`);
          }
        }
      }
    } finally {
      root.style.fontSize = originalFont;
      if (originalTheme === undefined) delete root.dataset.theme;
      else root.dataset.theme = originalTheme;
      frame.width = 390;
      await settled(100);
    }

    const savedTheme = root.dataset.theme;
    const savedFont = root.style.fontSize;
    try {
      for (const theme of ['light', 'dark']) {
        root.dataset.theme = theme;
        for (const scale of [100, 200]) {
          root.style.fontSize = `${scale}%`;
          for (const width of [599, 600, 840, 1199, 1200, 1440]) {
            frame.width = width;
            await settled(80);
            const label = `${theme} Start at ${width}px and ${scale}% text`;
            const rail = nav.getBoundingClientRect();
            const main = $('main').getBoundingClientRect();
            if (width < 600) {
              bottomNav(label, 'b-nav-new');
            } else {
              check(`${label}: the labelled navigation rail owns the viewport's left edge`,
                win.getComputedStyle(nav).position === 'fixed'
                  && rail.left === 0 && rail.top === 0
                  && Math.abs(rail.bottom - root.clientHeight) < 1
                  && main.left >= rail.right,
                `rail ${rail.left},${rail.top} to ${rail.right},${rail.bottom}; main ${main.left}`);
              check(`${label}: compact and expanded rails have distinct widths`,
                width < 1200 ? rail.width >= 80 && rail.width < 160
                  : rail.width >= 200 && rail.width <= 224,
                `${rail.width}px`);
              check(`${label}: exactly one labelled, comfortable control per destination`,
                items.every((item) => {
                  const rect = item.getBoundingClientRect();
                  const text = item.querySelector('.nav-label');
                  return doc.querySelectorAll(`#${item.id}`).length === 1
                    && shown(item) && shown(text) && rect.width >= 48 && rect.height >= 48
                    && text.getBoundingClientRect().right <= rail.right;
                }));
              check(`${label}: Start remains current and the page reflows beside the rail`,
                current() === 'b-nav-new' && root.scrollWidth <= root.clientWidth,
                `${current()}; ${root.scrollWidth}/${root.clientWidth}`);
            }
          }
        }
      }
    } finally {
      root.style.fontSize = savedFont;
      if (savedTheme === undefined) delete root.dataset.theme;
      else root.dataset.theme = savedTheme;
      frame.width = 390;
      await settled(100);
    }

    // Compact detail screens use the contextual top bar instead of bottom destinations.
    $('b-library').click();
    await settled();
    const card = $('library-rows').querySelector('[data-session-id="s_shell_active"]');
    [...card.querySelectorAll('button')].find((b) => b.textContent === 'Continue').click();
    await settled(800);
    check('Continue opens the interview', !$('panel-interview').hidden
      && doc.body.dataset.panel === 'interview', doc.body.dataset.panel);
    const title = doc.querySelector('.bar h1');
    check('Interview: its complete, literal session title survives visual truncation',
      title.textContent === sessionDisplayTitle(sessions[0])
        && title.title === sessionDisplayTitle(sessions[0])
        && !title.querySelector('img') && !win.__shellMarkup,
      title.textContent);
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
    await settled();
    check('the back arrow returns to Ideas and drops its detail name',
      doc.body.dataset.panel === 'library' && !$('b-library').hasAttribute('aria-label'));
    const doneCard = $('library-rows').querySelector('[data-session-id="s_shell_done"]');
    [...doneCard.querySelectorAll('button')].find((b) => b.textContent === 'View result').click();
    await settled(500);
    check('Done: a top app bar, not a bottom navigation bar',
      doc.body.dataset.panel === 'done' && win.getComputedStyle(nav).position !== 'fixed'
        && shown($('b-library')) && !shown($('b-nav-new')) && current() === ''
        && !shown($('meter')),
      `${doc.body.dataset.panel} ${win.getComputedStyle(nav).position} ${current()}`
        + ` meter shown: ${shown($('meter'))}`);

    // 360px: the narrowest common Android width. Nothing may scroll sideways on any panel.
    frame.width = 360;
    await settled();
    check('the shell frame narrows to 360px', win.innerWidth === 360, `${win.innerWidth}px`);
    const overflow = (label) => {
      check(`${label} has no horizontal overflow at 360px`, root.scrollWidth <= root.clientWidth,
        `scrollWidth ${root.scrollWidth}, clientWidth ${root.clientWidth}`);
    };
    overflow('Done');
    $('b-library').click();
    await settled();
    overflow('Ideas');
    $('b-settings').click();
    await settled();
    overflow('Settings');
    $('b-library').click();
    await settled();
    const again = $('library-rows').querySelector('[data-session-id="s_shell_active"]');
    [...again.querySelectorAll('button')].find((b) => b.textContent === 'Continue').click();
    await settled(800);
    overflow('Interview');
    check('navigation and manual resume never request microphone access',
      captures.media === 0 && captures.recognition === 0, JSON.stringify(captures));
  } finally {
    for (const frame of frames) frame.remove();
    for (const session of sessions) await deleteSession(session.id);
    for (const r of await navigator.serviceWorker.getRegistrations()) await r.unregister();
  }
}
