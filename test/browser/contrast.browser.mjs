// Contrast of every colour pair the redesigned app draws, in light and in dark (#111).
//
// Two layers, both read off the live page rather than the stylesheet text, so they measure
// what light-dark() and color-mix() actually resolved to under each theme:
//
// - the colour roles as tokens: every text role against every surface at 4.5:1, and
//   --outline (field and chip borders) and --primary (the focus ring) at 3:1;
// - the components as drawn: the computed colour of each control, chip, badge, toast, menu,
//   switch and voice-stage part against the background actually behind it. A token pair can
//   pass while the component fails, because components mix tokens: a tonal chip is primary
//   at 10% over a card, a status badge is its own colour at 14% over the card, and neither
//   pair exists in the token list.
//
// Text is held to 4.5:1, or 3:1 when it is large (24px, or 18.66px at weight 700). Non-text
// parts a person needs to see to find or operate a control are held to 3:1 (WCAG 2.2
// 1.4.3 and 1.4.11). What is exempt, and why, is listed where it is skipped:
//
// - disabled controls are exempt in WCAG, but the project still measures their text;
// - the coverage meter's segments, measured by coverage.browser.mjs;
// - container edges that identify nothing (cards, the question card, dividers): the text
//   and controls inside are what is measured;
// - the voice stage's tonal ring and the skeleton bars, which repeat state that text says
//   (the status line under the ring, the caption or title beside a skeleton). The text is
//   measured instead, and the voice stage's phase glyph inside the ring is held to 3:1.
//
// Colours are flattened through a 1x1 canvas, so a translucent colour in any CSS syntax
// (color(srgb …), rgb(… / a)) composites over the real stack of ancestor backgrounds exactly
// as the browser paints it, and an unparseable colour throws rather than reading as black.

const BOOT_MS = 3500;
const settle = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function loadFrame(width) {
  return new Promise((resolve, reject) => {
    const frame = document.createElement('iframe');
    frame.width = width;
    frame.height = 800;
    frame.style.position = 'absolute';
    frame.style.left = '-10000px';
    frame.onload = () => resolve(frame);
    frame.onerror = () => reject(new Error('contrast iframe failed to load'));
    frame.src = '/index.html';
    document.body.append(frame);
  });
}

const canvas = document.createElement('canvas');
canvas.width = 1;
canvas.height = 1;
const ctx = canvas.getContext('2d', { willReadFrequently: true });
const SENTINEL = '#010203';

/** Paint `layers` bottom-up over opaque white and read back the pixel. */
function flatten(layers) {
  ctx.clearRect(0, 0, 1, 1);
  ctx.fillStyle = '#ffffff';
  ctx.fillRect(0, 0, 1, 1);
  for (const layer of layers) {
    ctx.fillStyle = SENTINEL;
    ctx.fillStyle = layer;
    if (ctx.fillStyle === SENTINEL && layer !== SENTINEL) throw new Error(`unparseable colour: ${layer}`);
    ctx.fillRect(0, 0, 1, 1);
  }
  return [...ctx.getImageData(0, 0, 1, 1).data.slice(0, 3)];
}

function luminance([r, g, b]) {
  const lin = (v) => { const c = v / 255; return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4; };
  return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b);
}

function ratio(a, b) {
  const [x, y] = [luminance(a), luminance(b)].sort((p, q) => q - p);
  return (x + 0.05) / (y + 0.05);
}

const transparent = (css) => !css || css === 'transparent' || /^rgba\(0, 0, 0, 0\)$/.test(css)
  || /\/\s*0\)$/.test(css);

/** The background colours behind `el`, root first: what a pixel of its text sits on. */
function backdrop(el) {
  const win = el.ownerDocument.defaultView;
  const layers = [];
  for (let node = el; node && node.nodeType === 1; node = node.parentElement) {
    const bg = win.getComputedStyle(node).backgroundColor;
    if (!transparent(bg)) layers.unshift(bg);
  }
  return layers;
}

function isLarge(el, pseudo) {
  const style = el.ownerDocument.defaultView.getComputedStyle(el, pseudo);
  const size = parseFloat(style.fontSize);
  return size >= 24 || (size >= 18.66 && Number(style.fontWeight) >= 700);
}

/** Composite translucent backgrounds; reject unsupported opacity groups instead of
 * reporting the unfaded token's contrast as the painted result. */
export function computedContrast(el, { fg, bg, min, pseudo } = {}) {
  const win = el.ownerDocument.defaultView;
  const need = min ?? (isLarge(el, pseudo) ? 3 : 4.5);
  for (let node = el; node && node.nodeType === 1; node = node.parentElement) {
    const opacity = Number(win.getComputedStyle(node).opacity);
    if (opacity !== 1) return {
      ok: false, minimum: need,
      detail: `opacity ${opacity} on ${node.id || node.tagName} requires paint compositing`,
    };
  }
  if (pseudo && Number(win.getComputedStyle(el, pseudo).opacity) !== 1) {
    return { ok: false, minimum: need, detail: `${pseudo} opacity requires paint compositing` };
  }
  const under = bg || backdrop(el);
  const colour = fg || win.getComputedStyle(el, pseudo).color;
  const value = ratio(flatten([...under, colour]), flatten(under));
  return { ok: value >= need, ratio: value, minimum: need, detail: `${value.toFixed(2)} (needs ${need})` };
}

export { backdrop };

export default async function run(report) {
  const check = (label, ok, detail = '') => report(label, ok, detail);
  const frames = [];
  try {
    const phone = await loadFrame(390);
    const desk = await loadFrame(1280);
    frames.push(phone, desk);
    await settle(BOOT_MS);
    // End states only: a theme switch would otherwise be read mid-transition, halfway
    // between the two themes' colours.
    for (const frame of frames) {
      const still = frame.contentDocument.createElement('style');
      still.textContent = '*, *::before, *::after { transition: none !important; animation: none !important; }';
      frame.contentDocument.head.append(still);
    }

    for (const theme of ['light', 'dark']) {
      // ── the colour roles as tokens ──────────────────────────────────────
      {
        const doc = desk.contentDocument;
        const win = desk.contentWindow;
        doc.documentElement.dataset.theme = theme;
        const probe = doc.createElement('div');
        doc.body.append(probe);
        const resolve = (token) => {
          probe.style.color = `var(${token})`;
          return win.getComputedStyle(probe).color;
        };
        const surfaces = ['--surface-container-lowest', '--surface-container-low', '--surface-container',
          '--surface-container-high'];
        const pairs = [
          ...['--on-surface', '--on-surface-variant', '--primary', '--error', '--success', '--warning']
            .flatMap((fg) => surfaces.map((bg) => [fg, bg, 4.5])),
          ['--on-primary', '--primary', 4.5],
          ...surfaces.map((bg) => ['--outline', bg, 3]),
        ];
        const failures = pairs
          .map(([fg, bg, min]) => [fg, bg, min, ratio(flatten([resolve(bg), resolve(fg)]), flatten([resolve(bg)]))])
          .filter(([, , min, r]) => !(r >= min))
          .map(([fg, bg, min, r]) => `${fg} on ${bg} ${r.toFixed(2)} < ${min}`);
        check(`every colour role meets WCAG AA contrast in the ${theme} theme`, failures.length === 0,
          failures.join('; ') || `${pairs.length} pairs`);
        probe.remove();
      }

      // ── the components as drawn ─────────────────────────────────────────
      const results = [];
      /**
       * Measure one pair. `fg` is a computed colour; `bg` defaults to whatever is painted
       * behind `el`. `min` defaults to the text threshold for `el`'s own type size.
       */
      const measure = (label, el, { fg, bg, min, pseudo } = {}) => {
        if (!el) { results.push({ label, ok: false, detail: 'element missing' }); return; }
        results.push({ label, ...computedContrast(el, { fg, bg, min, pseudo }) });
      };
      /** A token's resolved colour under the current theme (a custom property reads back raw). */
      const token = (doc, name) => {
        const el = doc.createElement('span');
        el.style.color = `var(${name})`;
        doc.body.append(el);
        const value = doc.defaultView.getComputedStyle(el).color;
        el.remove();
        return value;
      };
      const css = (el, prop, pseudo) => el.ownerDocument.defaultView.getComputedStyle(el, pseudo)
        .getPropertyValue(prop);

      for (const frame of [phone, desk]) {
        const doc = frame.contentDocument;
        const $ = (id) => doc.getElementById(id);
        doc.documentElement.dataset.theme = theme;
        const at = frame === phone ? 'phone' : 'desktop';
        const scratch = [];
        const make = (parent, tag, className, text = '') => {
          const el = doc.createElement(tag);
          if (className) el.className = className;
          el.textContent = text;
          parent.append(el);
          scratch.push(el);
          return el;
        };

        // Start: the shell, the actions and the connection card.
        doc.body.dataset.panel = 'setup';
        $('panel-setup').dataset.view = 'start';
        const nav = ['b-library', 'b-nav-new', 'b-settings'].map($);
        nav.forEach((item) => item.removeAttribute('aria-current'));
        $('b-nav-new').setAttribute('aria-current', 'page');
        measure(`${at}: the current destination's label`, $('b-nav-new'));
        measure(`${at}: a destination that is not current`, $('b-settings'));
        if (at === 'phone') {
          measure(`${at}: the current destination's icon`, $('b-nav-new'),
            { fg: css($('b-nav-new'), 'background-color', '::before'), min: 3 });
          measure(`${at}: a destination's icon that is not current`, $('b-settings'),
            { fg: css($('b-settings'), 'background-color', '::before'), min: 3 });
        } else {
          measure(`${at}: the current destination's outline`, $('b-nav-new'),
            { fg: css($('b-nav-new'), 'border-top-color'), bg: backdrop($('b-nav-new').parentElement), min: 3 });
        }
        measure(`${at}: a filled button (Start with voice)`, $('b-start-voice'));
        measure(`${at}: a filled button's icon`, $('b-start-voice'),
          { fg: css($('b-start-voice'), 'background-color', '::before'), min: 3 });
        measure(`${at}: a ghost button (Type instead)`, $('b-start'));
        measure(`${at}: a ghost button's outline`, $('b-start'),
          { fg: css($('b-start'), 'border-top-color'), bg: backdrop($('b-start').parentElement), min: 3 });
        measure(`${at}: a quiet button (Change)`, $('b-connect-change'));
        measure(`${at}: a danger button (Forget my key)`, $('b-forget'));
        measure(`${at}: field text`, $('apikey'));
        measure(`${at}: field placeholder`, $('apikey'), { fg: css($('apikey'), 'color', '::placeholder') });
        measure(`${at}: a field's outline`, $('apikey'),
          { fg: css($('apikey'), 'border-top-color'), bg: backdrop($('apikey').parentElement), min: 3 });
        measure(`${at}: a field label`, doc.querySelector('label[for="apikey"]'));
        measure(`${at}: a hint`, $('provider-note'));
        measure(`${at}: an inline link`, $('keylink'));
        measure(`${at}: the version footer`, $('version'));
        measure(`${at}: the backup warning callout`, doc.querySelector('.backup-warning'));
        measure(`${at}: the skip link`, doc.querySelector('.skip-link'));
        // The focus ring is drawn outside the control, so it sits on the control's backdrop.
        for (const id of ['b-start-voice', 'b-start', 'apikey', 'b-connect-change']) {
          measure(`${at}: the focus ring around #${id}`, $(id),
            { fg: token(doc, '--primary'), bg: backdrop($(id).parentElement), min: 3 });
        }

        // Settings: the theme radios, the index and the speech controls.
        $('panel-setup').dataset.view = 'settings';
        nav.forEach((item) => item.removeAttribute('aria-current'));
        $('b-settings').setAttribute('aria-current', 'page');
        const radio = doc.querySelector('#theme-choice input');
        measure(`${at}: a theme radio's label`, radio.nextElementSibling);
        measure(`${at}: the theme radio itself`, radio, { fg: token(doc, '--primary'), min: 3 });
        if (at === 'desktop') {
          const index = [...$('settings-index').querySelectorAll('button')];
          index[0].setAttribute('aria-current', 'true');
          measure(`${at}: the current Settings section`, index[0]);
          measure(`${at}: a Settings section that is not current`, index[1]);
          index[0].removeAttribute('aria-current');
        }

        // Ideas: cards, badges, tags, the overflow trigger, the menu.
        doc.body.dataset.panel = 'library';
        const card = make($('library-rows'), 'article', 'idea-card');
        const head = make(card, 'div', 'idea-head');
        make(head, 'h3', '', 'A probe idea');
        for (const status of ['active', 'completed', 'archived']) {
          measure(`${at}: the ${status} badge`, make(head, 'span', `idea-status ${status}`, status));
        }
        measure(`${at}: card metadata`, make(card, 'p', 'idea-meta', '3 questions'));
        measure(`${at}: a tag`, make(make(card, 'div', 'idea-tags'), 'span', '', 'work'));
        const actions = make(card, 'div', 'idea-actions');
        measure(`${at}: a card's primary action`, make(actions, 'button', '', 'Continue'));
        const more = make(actions, 'button', 'quiet idea-more');
        more.dataset.icon = 'more-vert';
        measure(`${at}: the overflow trigger's icon`, more,
          { fg: css(more, 'background-color', '::before'), min: 3 });
        const menu = $('done-menu');
        measure(`${at}: a menu's edge against the page`, menu,
          { fg: css(menu, 'border-top-color'), bg: backdrop($('panel-done')), min: 3 });
        measure(`${at}: a menu item`, $('b-copy'));
        measure(`${at}: a menu item's icon`, $('b-copy'), { fg: css($('b-copy'), 'background-color', '::before'), min: 3 });
        measure(`${at}: a destructive menu item`, make(menu, 'button', 'quiet idea-menu-item danger', 'Delete'));
        const inactive = make(menu, 'button', 'quiet idea-menu-item', 'Not available');
        inactive.disabled = true;
        measure(`${at}: a disabled menu item remains readable`, inactive, { min: 4.5 });
        measure(`${at}: the library count`, $('library-count'));
        if (at === 'desktop') {
          card.setAttribute('aria-current', 'true');
          measure(`${at}: the selected card's edge`, card,
            { fg: css(card, 'border-top-color'), bg: backdrop(card.parentElement), min: 3 });
        }

        // Interview: thread, question card, chips, coverage rows, the switch, the composer.
        doc.body.dataset.panel = 'interview';
        const turn = make($('thread'), 'li', 'thread-turn');
        measure(`${at}: an earlier question in the thread`, make(turn, 'p', 'thread-q', 'Who is it for?'));
        const bubble = make(turn, 'div', 'thread-a');
        measure(`${at}: an answer bubble`, make(bubble, 'p', 'thread-a-text', 'Me.'));
        measure(`${at}: an answer's tag`, make(bubble, 'span', 'thread-tag', 'voice'));
        const skipped = make(turn, 'div', 'thread-a skipped');
        measure(`${at}: a skipped answer`, make(skipped, 'p', 'thread-a-text', 'Skipped'));
        measure(`${at}: the question`, $('question'));
        measure(`${at}: the bridge line`, $('bridge'));
        measure(`${at}: the turn line`, $('turnline'));
        measure(`${at}: the skeleton's caption`, $('asking-label'));
        const chip = make($('chips'), 'button', 'chip', 'A suggestion');
        measure(`${at}: a suggestion chip's text`, chip);
        measure(`${at}: a suggestion chip's edge`, chip,
          { fg: css(chip, 'border-top-color'), bg: backdrop($('chips')), min: 3 });
        measure(`${at}: Show earlier answers`, $('b-thread-more'));
        measure(`${at}: the Coverage heading`, doc.querySelector('details.cov > summary'));
        const row = make($('coverage'), 'div', 'row');
        make(row, 'span', '', 'Outcome');
        for (const level of ['thin', 'partial', 'covered']) {
          measure(`${at}: the ${level} coverage label`, make(row, 'span', `lvl ${level}`, level));
        }
        measure(`${at}: a coverage gap`, make(row, 'span', 'gap', 'needs detail'));
        measure(`${at}: the meter's percentage`, $('meter-label'));
        const sw = $('handsfree');
        const swRow = $('handsfree-wrap');
        measure(`${at}: the switch's title`, doc.querySelector('.switch-title'));
        measure(`${at}: the switch's hint`, doc.querySelector('.switch-hint'));
        for (const on of [false, true]) {
          sw.checked = on;
          const state = on ? 'on' : 'off';
          measure(`${at}: the switch's track edge, ${state}`, sw,
            { fg: css(sw, 'border-top-color'), bg: backdrop(swRow), min: 3 });
          measure(`${at}: the switch's thumb, ${state}`, sw,
            { fg: css(sw, 'background-color', '::before'), bg: [...backdrop(swRow), css(sw, 'background-color')], min: 3 });
        }
        sw.checked = false;
        measure(`${at}: the wrap-up offer`, $('wrap-offer-text'));
        for (const status of ['error', 'warning', 'success']) {
          const callout = make($('question-card'), 'p', 'hint callout', `${status}: an explicit explanation`);
          callout.dataset.status = status;
          measure(`${at}: the ${status} callout text`, callout);
          measure(`${at}: the ${status} callout marker`, callout, {
            fg: css(callout, 'border-inline-start-color'), bg: backdrop(callout), min: 3,
          });
        }
        measure(`${at}: Write it up (tonal)`, $('b-wrap'));
        measure(`${at}: Keep going (quiet)`, $('b-wrap-keep'));
        measure(`${at}: the answer box`, $('answer'));
        measure(`${at}: the answer box placeholder`, $('answer'), { fg: css($('answer'), 'color', '::placeholder') });
        measure(`${at}: Send`, $('b-send'));
        measure(`${at}: Skip this one`, $('b-skip'));
        measure(`${at}: Answer out loud`, $('b-mic'));
        measure(`${at}: the listening label`, $('listening-label'));

        // Toasts, over the page.
        measure(`${at}: a note toast`, $('note'));
        measure(`${at}: a note toast's edge`, $('note'),
          { fg: css($('note'), 'border-top-color'), bg: backdrop($('toasts')), min: 3 });
        measure(`${at}: an error toast`, $('err'));
        measure(`${at}: an error toast's edge`, $('err-toast'),
          { fg: css($('err-toast'), 'border-left-color'), bg: backdrop($('toasts')), min: 3 });
        measure(`${at}: the error toast's dismiss icon`, $('b-err-dismiss'),
          { fg: css($('b-err-dismiss'), 'background-color', '::before'), min: 3 });

        // Done.
        doc.body.dataset.panel = 'done';
        measure(`${at}: the Done heading`, $('done-title'));
        measure(`${at}: the Done meta line`, $('done-meta'));
        const out = make($('output'), 'p', '', 'Build it.');
        measure(`${at}: the rendered prompt`, out);
        measure(`${at}: Share .md`, $('b-share'));
        measure(`${at}: the More actions icon`, $('b-done-more'),
          { fg: css($('b-done-more'), 'background-color', '::before'), min: 3 });
        const panels = ['panel-setup', 'panel-library', 'panel-interview', 'panel-done'].map($);
        const hidden = panels.map((panel) => panel.hidden);
        const download = $('b-download');
        const disabled = download.disabled;
        panels.forEach((panel) => { panel.hidden = panel.id !== 'panel-done'; });
        download.disabled = true;
        try {
          menu.showPopover();
          check(`${at}: the actual disabled Done action is visible and opaque in ${theme}`,
            menu.matches(':popover-open') && download.getClientRects().length > 0
              && css(download, 'opacity') === '1', css(download, 'opacity'));
          measure(`${at}: actual Done disabled action text`, download, { min: 4.5 });
          measure(`${at}: actual Done disabled action icon`, download,
            { fg: css(download, 'background-color', '::before'), min: 3 });
          const opacity = download.style.opacity;
          const menuOpacity = menu.style.opacity;
          try {
            download.style.opacity = '.6';
            check(`${at}: faded disabled paint cannot report an unfaded contrast pass in ${theme}`,
              !computedContrast(download, { min: 4.5 }).ok);
            download.style.opacity = '1';
            menu.style.opacity = '.6';
            check(`${at}: ancestor opacity cannot silently pass the Done contrast check in ${theme}`,
              !computedContrast(download, { min: 4.5 }).ok);
          } finally {
            download.style.opacity = opacity;
            menu.style.opacity = menuOpacity;
          }
        } finally {
          menu.hidePopover();
          download.disabled = disabled;
          panels.forEach((panel, index) => { panel.hidden = hidden[index]; });
        }

        // The voice stage, in every phase it can show.
        const stage = $('voice-stage');
        const orbit = stage.querySelector('.voice-orbit');
        const signal = $('voice-signal');
        for (const phase of ['ready', 'speaking', 'listening', 'transcribing', 'thinking', 'paused',
          'recovering', 'blocked', 'complete']) {
          stage.dataset.phase = phase;
          measure(`${at}: the voice status, ${phase}`, $('voice-status'));
          measure(`${at}: the voice phase glyph, ${phase}`, signal,
            { fg: css(signal, 'color'), bg: backdrop(orbit), min: 3 });
          for (const id of ['voice-question', 'voice-transcript', 'voice-detail', 'voice-backend']) {
            $(id).textContent = 'Probe text';
            measure(`${at}: #${id}, ${phase}`, $(id));
            $(id).textContent = '';
          }
          measure(`${at}: the voice eyebrow, ${phase}`, stage.querySelector('.voice-eyebrow'));
        }
        stage.dataset.phase = 'listening';
        for (const id of ['b-voice-pause', 'b-voice-exit']) {
          const button = $(id);
          button.dataset.icon = id === 'b-voice-pause' ? 'pause' : 'close';
          measure(`${at}: #${id}'s label`, button);
          measure(`${at}: #${id}'s icon on its circle`, button, {
            fg: css(button, 'background-color', '::before'),
            bg: [...backdrop(button), css(button, 'background-color', '::after')], min: 3,
          });
        }
        stage.dataset.phase = 'ready';

        for (const el of scratch) el.remove();
        nav.forEach((item) => item.removeAttribute('aria-current'));
      }

      const failures = results.filter((r) => !r.ok);
      check(`every component colour pair passes in the ${theme} theme`, failures.length === 0,
        failures.map((r) => `${r.label}: ${r.detail}`).join('; ') || `${results.length} pairs`);
      check(`the component pass measured a full set in the ${theme} theme`, results.length > 150,
        `${results.length} pairs`);
    }
  } finally {
    for (const frame of frames) frame.remove();
    for (const r of await navigator.serviceWorker.getRegistrations()) await r.unregister();
  }
}
