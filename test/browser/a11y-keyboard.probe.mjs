// Keyboard order, focus visibility and coarse-pointer target sizes on every panel (#111).
//
// Node-side, driven through CDP by tools/browser-policy-check.mjs, because the claims are
// about what the browser does with real input, and the in-page probes cannot send it:
//
// - Tab is a real key press (Input.dispatchKeyEvent), so the order is the browser's own
//   sequential focus navigation — tabindex, `hidden`, `inert`, display:none, closed
//   popovers, focusable scrollers and the popover invoker's scoping are all the browser's
//   decisions, not a re-implementation of them that could agree with a broken page. A
//   simulated walk over querySelectorAll would pass a page whose CSS hides a control the
//   browser still focuses, which is exactly the failure being looked for;
// - Enter opens an overflow menu and Escape closes it, which a synthetic KeyboardEvent
//   never does (light dismiss and focus return are browser behaviour);
// - pointer: coarse is the real media state under Emulation.setTouchEmulationEnabled, not
//   a stylesheet rule switched on by the probe, so a rule the browser would not match
//   cannot make a control look big enough.
//
// The walk on each panel starts at the skip link and presses Tab until focus leaves the
// document. Every stop must be visible, on screen and not covered (WCAG 2.4.11), the walk
// must end (no trap), Shift+Tab must step back, and the order must follow the reading
// order: the skip link, then the navigation, then the panel's regions in the order they
// are read (a side column after the column it sits beside), and within a region top to
// bottom and, along a row, left to right. The phone's bottom navigation comes before the
// content in that order on purpose: it is the same <nav> as the desktop top bar, and after
// any navigation focus lands on the destination's heading, so the next Tab is the content.
//
// Target sizes: every visible control at least 48 by 48 CSS pixels under a coarse pointer,
// on every panel, at phone and desktop widths, including open menus and the voice stage.
// Exempt, and listed in the output when applied: a link inside a sentence (WCAG 2.5.8's
// inline exception), and the desktop coverage panel's summary, which is a heading there
// (aria-disabled) rather than a control.

const PHONE = { width: 390, height: 800 };
const DESKTOP = { width: 1280, height: 900 };
const NAV = ['.skip-link', 'nav.bar-right'];

export async function runA11yKeyboard(cdp, { origin, bootstrap, check }) {
  const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
  const evaluate = (expression) => cdp.evaluate(expression);
  async function until(expression, label, ms = 8000) {
    const end = Date.now() + ms;
    let value;
    do {
      try { value = await evaluate(expression); } catch { value = null; }
      if (value) return value;
      await sleep(40);
    } while (Date.now() < end);
    throw new Error(`${label}: timed out waiting for ${expression}`);
  }
  const KEYS = { Tab: 9, Enter: 13, Escape: 27 };
  async function press(key, { shift = false } = {}) {
    const base = {
      key, code: key, windowsVirtualKeyCode: KEYS[key], nativeVirtualKeyCode: KEYS[key],
      modifiers: shift ? 8 : 0,
    };
    if (key === 'Enter') await cdp.send('Input.dispatchKeyEvent', { type: 'keyDown', text: '\r', ...base });
    else await cdp.send('Input.dispatchKeyEvent', { type: 'rawKeyDown', ...base });
    await cdp.send('Input.dispatchKeyEvent', { type: 'keyUp', ...base });
    await sleep(30);
  }
  /** Redefine the share sheet before Done is laid out, as done.browser.mjs does per visit. */
  const shareProfile = (profile) => evaluate(`(() => {
    const files = ${JSON.stringify(profile)} === 'file share';
    Object.defineProperty(navigator, 'share', { configurable: true, value: files ? async () => {} : undefined });
    Object.defineProperty(navigator, 'canShare', { configurable: true, value: files ? () => true : undefined });
  })()`);
  const focusInfo = (regions) => evaluate(`window.__a11y.focus(${JSON.stringify(regions)})`);

  await cdp.send('Page.addScriptToEvaluateOnNewDocument', { source: bootstrap });
  await cdp.send('Page.bringToFront');
  await cdp.send('Emulation.setFocusEmulationEnabled', { enabled: true });
  // Reduced motion: no View Transition or panel animation sits between a key and its result.
  await cdp.send('Emulation.setEmulatedMedia', {
    features: [{ name: 'prefers-reduced-motion', value: 'reduce' }],
  });
  const viewport = (size) => cdp.send('Emulation.setDeviceMetricsOverride', {
    ...size, deviceScaleFactor: 1, mobile: false,
  });
  await viewport(PHONE);
  await cdp.send('Page.navigate', { url: `${origin}/index.html` });
  await until(`!!document.getElementById('version')?.textContent && !!window.__a11y`, 'first boot');

  // Two ideas through the app's own modules: an interview with a thread and suggestions, so
  // the interview's controls are on screen; and a finished one.
  await evaluate(`(async () => {
    const s = await import('/src/core/session.js');
    const { seedTurn, submitAnswer } = await import('/src/runtime/turn.js');
    const { saveSession } = await import('/src/store/sessions.js');
    const at = Date.now();
    let a = seedTurn(s.createSession({ id: 's_a11y_active', now: at }), { now: at });
    a = submitAnswer(a, { text: 'A kitchen opening checklist that stays honest.', now: at + 1 });
    const asked = [
      ['Who opens the kitchen on a busy morning?', 'users', 'The duty manager, before seven.'],
      ['What does a finished checklist look like?', 'outcome', 'One laminated page, twelve lines.'],
      ['Which mistake would make you stop trusting it?', 'risks', 'A step for a fridge we removed.'],
    ];
    for (const [question, dimension, answer] of asked) {
      a = s.askQuestion(a, { question, dimension, source: 'model', now: at + 2 });
      a = s.answerQuestion(a, { text: answer, now: at + 3 });
    }
    a = s.askQuestion(a, {
      question: 'How long should one pass through the checklist take?', dimension: 'constraints',
      chips: ['Ten minutes', 'Under five minutes'], source: 'model', now: at + 4,
    });
    a = s.setTitle(a, 'Kitchen checklist', at + 6);
    await saveSession(a);
    let d = seedTurn(s.createSession({ id: 's_a11y_done', now: at + 10 }), { now: at + 10 });
    d = submitAnswer(d, { text: 'A finished idea for the keyboard walk.', now: at + 11 });
    d = s.setSynthesis(d, { text: '## Task\\nWalk the keyboard.', assumptions: [], openQuestions: [], now: at + 12 });
    d = s.setTitle(d, 'Keyboard walk', at + 13);
    await saveSession(d);
  })()`);
  await cdp.send('Page.reload');
  await until(`!!document.getElementById('version')?.textContent && document.body.dataset.panel === 'library'
    && !!document.querySelector('#library-rows .idea-card')`, 'boot with saved ideas');

  const settled = async (panel, label) => {
    await until(`document.body.dataset.panel === ${JSON.stringify(panel)}
      && !document.documentElement.matches(':active-view-transition')`, label);
    await sleep(250);
  };
  const click = async (selector, panel, label) => {
    // A walk can end on the skip link, which, focused, is drawn over the phone's back arrow.
    await evaluate(`document.activeElement?.blur();
      document.querySelector(${JSON.stringify(selector)}).scrollIntoView({ block: 'center' })`);
    await cdp.click(selector);
    await settled(panel, label);
  };

  /**
   * Tab from the skip link to the end of the document, recording each stop, and judge it.
   * `regions` are selectors in reading order; a stop belongs to the first one it is inside.
   */
  async function walk(label, regions) {
    const all = [...NAV, ...regions];
    await evaluate(`window.scrollTo(0, 0); document.querySelector('.skip-link').focus()`);
    const stops = [await focusInfo(all)];
    // Past the last control focus either leaves the document (to the browser's own UI) or
    // wraps to the skip link; both are the end. A trap is never getting there.
    let ended = false;
    for (let i = 0; i < 160; i++) {
      await press('Tab');
      const info = await focusInfo(all);
      if (info.end || info.name === stops[0].name) { ended = true; break; }
      stops.push(info);
    }
    check(`${label}: Tab reaches the end of the page, with no focus trap`, ended,
      `${stops.length} stops; last ${stops.at(-1)?.name}`);
    // And back: Shift+Tab from the end visits the same stops in exactly the reverse order.
    const backwards = [];
    for (let i = 0; i < 160 && backwards.length < stops.length; i++) {
      await press('Tab', { shift: true });
      const info = await focusInfo(all);
      if (info.end) { if (backwards.length) break; continue; }
      backwards.push(info.name);
      if (info.name === stops[0].name) break;
    }
    const forwards = stops.map((s) => s.name).reverse();
    check(`${label}: Shift+Tab walks the same stops in reverse`,
      backwards.join(' > ') === forwards.join(' > '),
      `${backwards.slice(0, 6).join(' > ')} vs ${forwards.slice(0, 6).join(' > ')}`);

    const unseen = stops.filter((s) => !s.shown);
    check(`${label}: every Tab stop is visible (nothing hidden, inert or display:none is reachable)`,
      unseen.length === 0, unseen.map((s) => s.name).join(' | '));
    const offscreen = stops.filter((s) => !s.inView);
    check(`${label}: every Tab stop is scrolled on screen when focused`,
      offscreen.length === 0, offscreen.map((s) => s.name).join(' | '));
    const covered = stops.filter((s) => s.covered);
    check(`${label}: no focused control is covered by a fixed or sticky bar`,
      covered.length === 0, covered.map((s) => `${s.name} under ${s.covered}`).join(' | '));
    const stray = stops.filter((s) => s.region < 0);
    check(`${label}: every Tab stop is in a known region`, stray.length === 0,
      stray.map((s) => s.name).join(' | '));

    // Reading order: regions never go backwards, and within one, each stop is below the
    // last or, on the same row, to its right.
    const wrong = [];
    for (let i = 1; i < stops.length; i++) {
      const a = stops[i - 1];
      const b = stops[i];
      if (b.region < a.region) { wrong.push(`${b.name} (region ${b.region}) after ${a.name} (region ${a.region})`); continue; }
      if (b.region !== a.region) continue;
      const ay = a.box.top + a.box.height / 2;
      const by = b.box.top + b.box.height / 2;
      const sameRow = Math.abs(ay - by) < Math.min(a.box.height, b.box.height) / 2;
      const ok = sameRow ? b.box.left + b.box.width / 2 > a.box.left + a.box.width / 2 : by > ay;
      if (!ok) wrong.push(`${b.name} after ${a.name}`);
    }
    check(`${label}: Tab order follows the reading order`, wrong.length === 0, wrong.join(' | '));
    return stops;
  }

  /** Open an overflow menu from the keyboard, walk it, and close it with Escape. */
  async function menu(label, trigger, menuSelector) {
    await evaluate(`document.querySelector(${JSON.stringify(trigger)}).focus()`);
    await press('Enter');
    await sleep(150);
    // The first item a person can use: a hidden one (Done's Share where nothing can be
    // shared) or a disabled one is not it.
    const opened = await evaluate(`(() => {
      const menu = document.querySelector(${JSON.stringify(menuSelector)});
      const first = [...menu.querySelectorAll('button')]
        .find((b) => b.getClientRects().length > 0 && !b.disabled);
      const active = document.activeElement;
      return {
        ok: menu.matches(':popover-open') && !!first && first === active,
        detail: 'open ' + menu.matches(':popover-open') + '; first ' + (first ? first.id || first.textContent : 'none')
          + '; focus ' + (active ? active.id || active.textContent || active.tagName : 'none'),
      };
    })()`);
    check(`${label}: Enter on the overflow trigger opens the menu with focus on its first usable item`,
      opened.ok, opened.detail);
    const items = await evaluate(`[...document.querySelector(${JSON.stringify(menuSelector)})
      .querySelectorAll('button')].filter((b) => b.getClientRects().length && !b.disabled).map((b) => b.textContent)`);
    const order = [];
    for (let i = 0; i < items.length; i++) {
      order.push(await evaluate('document.activeElement.textContent'));
      if (i < items.length - 1) await press('Tab');
    }
    check(`${label}: Tab walks the menu's items top to bottom`, order.join('|') === items.join('|'),
      `${order.join(' | ')} vs ${items.join(' | ')}`);
    await press('Escape');
    await sleep(150);
    const closed = await evaluate(`({
      open: document.querySelector(${JSON.stringify(menuSelector)}).matches(':popover-open'),
      returned: document.activeElement === document.querySelector(${JSON.stringify(trigger)}),
    })`);
    check(`${label}: Escape closes the menu and returns focus to its trigger`,
      !closed.open && closed.returned, JSON.stringify(closed));
  }

  /** Exactly one destination current on Start, Settings and Ideas; none on a detail screen. */
  async function current(label, expected) {
    const marked = await evaluate(`[...document.querySelectorAll('[aria-current="page"]')].map((el) => el.id).join(',')`);
    check(`${label}: aria-current="page" marks ${expected || 'no destination'}`, marked === expected,
      marked || 'none');
  }

  /** Every visible control at least 48 by 48 under the coarse pointer now emulated. */
  async function targets(label) {
    const found = await evaluate('window.__a11y.targets()');
    const small = found.filter((t) => !t.exempt && !(t.width >= 47.5 && t.height >= 47.5));
    check(`${label}: every control is at least 48 by 48 on a coarse pointer`, small.length === 0,
      small.map((t) => `${t.name} ${Math.round(t.width)}x${Math.round(t.height)}`).join(' | ')
        || `${found.length} controls`);
    return found.filter((t) => t.exempt).map((t) => `${t.name}: ${t.exempt}`);
  }

  const exemptions = new Set();
  for (const [size, at] of [[PHONE, 'phone'], [DESKTOP, 'desktop']]) {
    await viewport(size);
    await sleep(200);
    const wide = at === 'desktop';

    // Start
    await click('#b-nav-new', 'setup', `${at} Start`);
    await current(`${at} Start`, 'b-nav-new');
    await walk(`${at} Start`, ['#main']);
    // The skip link: Enter moves focus into the content without touching location.hash,
    // which re-prompts for the microphone in an installed iOS app (CLAUDE.md, no hash routing).
    await evaluate(`document.querySelector('.skip-link').focus()`);
    const skipVisible = await evaluate(`(() => {
      const r = document.querySelector('.skip-link').getBoundingClientRect();
      return r.top >= 0 && r.bottom <= innerHeight;
    })()`);
    check(`${at}: the focused skip link is on screen`, skipVisible);
    await press('Enter');
    await sleep(100);
    const skipped = await evaluate(`({ hash: location.hash, focus: document.activeElement.id })`);
    check(`${at}: the skip link moves focus to the main content without a hash change`,
      skipped.hash === '' && skipped.focus === 'main', JSON.stringify(skipped));
    await press('Tab');
    const afterSkip = await focusInfo(['#main']);
    check(`${at}: Tab after the skip link lands on the first control of the content`,
      afterSkip.region === 0, afterSkip.name);

    // Settings
    await click('#b-settings', 'setup', `${at} Settings`);
    await current(`${at} Settings`, 'b-settings');
    await walk(`${at} Settings`, wide
      ? ['#settings-index', '#panel-setup > :not(#settings-index)'] : ['#main']);

    // Ideas
    await click('#b-library', 'library', `${at} Ideas`);
    await until(`!!document.querySelector('#library-rows .idea-card')`, `${at} Ideas cards`);
    await current(`${at} Ideas`, 'b-library');
    await walk(`${at} Ideas`, wide
      ? ['#panel-library > :not(#library-detail)', '#library-detail'] : ['#main']);
    await menu(`${at} Ideas card`, '#library-rows [data-session-id="s_a11y_active"] .idea-more',
      '#idea-menu-s_a11y_active');
    if (wide) {
      await until(`!!document.querySelector('#library-detail .idea-more')`, 'detail pane');
      await menu(`${at} Ideas detail pane`, '#library-detail .idea-more', '#library-detail-menu');
    }

    // Interview
    await click('#library-rows [data-session-id="s_a11y_active"] .idea-actions > button:first-child',
      'interview', `${at} Interview`);
    await until(`document.querySelectorAll('#chips .chip').length > 0`,
      `${at} Interview controls`);
    await current(`${at} Interview`, '');
    await walk(`${at} Interview`, wide
      ? ['#manual-interview > :not(details.cov)', '#manual-interview > details.cov'] : ['#main']);

    // Done
    // Done, in both share profiles, so the result never depends on the host's share sheet.
    // Where files can be shared, Share .md is the primary and Copy the first menu item.
    // Where nothing can be shared, Copy is the primary and the menu's first child is the
    // hidden Share button, which focus must skip (#159 failed on Linux, which has no sheet).
    for (const profile of ['file share', 'no share']) {
      await shareProfile(profile);
      await click('#b-library', 'library', `${at} back to Ideas`);
      await until(`!!document.querySelector('#library-rows [data-session-id="s_a11y_done"]')`, 'done card');
      await click('#library-rows [data-session-id="s_a11y_done"] .idea-actions > button:first-child',
        'done', `${at} Done, ${profile}`);
      const layout = await evaluate(`({
        primary: document.querySelector('#done-actions > button:not(.done-more)').id,
        first: document.getElementById('done-menu').firstElementChild.id,
        firstHidden: document.getElementById('done-menu').firstElementChild.hidden,
      })`);
      check(`${at} Done, ${profile}: the share profile laid Done out as expected`,
        profile === 'file share'
          ? layout.primary === 'b-share' && layout.first === 'b-copy' && !layout.firstHidden
          : layout.primary === 'b-copy' && layout.first === 'b-share' && layout.firstHidden,
        JSON.stringify(layout));
      await current(`${at} Done, ${profile}`, '');
      await walk(`${at} Done, ${profile}`, ['#main']);
      await menu(`${at} Done, ${profile}`, '#b-done-more', '#done-menu');
    }
  }

  // The voice stage, from Start with voice, at phone width.
  await viewport(PHONE);
  await click('#b-library', 'library', 'voice Ideas');
  await click('#b-nav-new', 'setup', 'voice Start');
  await evaluate(`(() => {
    for (const [id, value] of [['provider', 'artifact'], ['stt', 'browser'], ['speech', 'browser']]) {
      const field = document.getElementById(id);
      field.value = value;
      field.dispatchEvent(new Event('change'));
    }
  })()`);
  await cdp.click('#b-start-voice');
  await until(`!document.getElementById('voice-stage').hidden
    && document.getElementById('voice-stage').dataset.phase === 'listening'`, 'voice stage listening');
  await current('voice stage', '');
  await walk('voice stage', ['#voice-stage']);

  // Coarse pointer: the same panels again, now measuring targets.
  await cdp.send('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 5 });
  check('touch emulation makes (pointer: coarse) match',
    await evaluate(`matchMedia('(pointer: coarse)').matches && !matchMedia('(pointer: fine)').matches`));
  for (const name of await targets('phone voice stage')) exemptions.add(name);
  await evaluate(`document.getElementById('b-voice-exit').click()`);
  await until(`document.getElementById('voice-stage').hidden`, 'voice exit');
  const go = async (id, panel) => {
    await evaluate(`document.getElementById(${JSON.stringify(id)}).click()`);
    await settled(panel, id);
  };
  for (const [size, at] of [[PHONE, 'phone'], [DESKTOP, 'desktop']]) {
    await viewport(size);
    await sleep(200);
    await go('b-nav-new', 'setup');
    // Unfold every disclosure, so the controls inside them are measured too.
    await evaluate(`document.querySelectorAll('#panel-setup details').forEach((d) => { d.open = true; })`);
    for (const name of await targets(`${at} Start`)) exemptions.add(name);
    await go('b-settings', 'setup');
    await evaluate(`document.querySelectorAll('#panel-setup details').forEach((d) => { d.open = true; })`);
    for (const name of await targets(`${at} Settings`)) exemptions.add(name);
    await go('b-library', 'library');
    await until(`!!document.querySelector('#library-rows .idea-card')`, `${at} coarse Ideas`);
    await evaluate(`document.querySelectorAll('#library-rows details').forEach((d) => { d.open = true; })`);
    for (const name of await targets(`${at} Ideas`)) exemptions.add(name);
    await evaluate(`document.getElementById('idea-menu-s_a11y_active').showPopover()`);
    for (const name of await targets(`${at} Ideas, menu open`)) exemptions.add(name);
    await evaluate(`document.getElementById('idea-menu-s_a11y_active').hidePopover()`);
    await evaluate(`document.querySelector('#library-rows [data-session-id="s_a11y_active"] .idea-actions > button:first-child').click()`);
    await settled('interview', `${at} coarse Interview`);
    await until(`document.querySelectorAll('#chips .chip').length > 0`, `${at} coarse chips`);
    await evaluate(`(() => {
      document.querySelector('details.cov').open = true;
      const more = document.getElementById('b-thread-more');
      if (!more.hidden) more.click();
    })()`);
    for (const name of await targets(`${at} Interview`)) exemptions.add(name);
    await go('b-library', 'library');
    await until(`!!document.querySelector('#library-rows [data-session-id="s_a11y_done"]')`, 'coarse done card');
    await evaluate(`document.querySelector('#library-rows [data-session-id="s_a11y_done"] .idea-actions > button:first-child').click()`);
    await settled('done', `${at} coarse Done`);
    for (const name of await targets(`${at} Done`)) exemptions.add(name);
    await evaluate(`document.getElementById('done-menu').showPopover()`);
    for (const name of await targets(`${at} Done, menu open`)) exemptions.add(name);
    await evaluate(`document.getElementById('done-menu').hidePopover()`);
    await go('b-library', 'library');
  }
  check('the only target-size exemptions applied are the documented ones',
    [...exemptions].every((e) => /: (inline link in text|not operable \(aria-disabled\))$/.test(e)),
    [...exemptions].join(' | ') || 'none');
}
