// Desktop layouts at 1024px and wider (#109), against the real app in an iframe.
//
// What this pins, at 1280px:
//
// - Ideas is a list beside a detail pane, side by side, with nothing scrolling sideways;
// - selecting a card (its body, or its Details button) fills the pane with THAT idea — the
//   pane's session id, title and preview all agree with the card, and the selection mark
//   moves with it. A pane that kept the first idea, or showed the previous one, fails here;
// - the pane is text: a title and an export full of markup arrive as characters, never as
//   elements (the export is model output, and the name is the user's);
// - the pane's primary action navigates to the idea it describes: View result opens Done on
//   that idea's document, and Continue opens the interview, whose coverage side panel (#105)
//   still sits beside the question at the wider page;
// - Settings is an index beside its groups, and an index entry brings its group into view
//   and moves focus to the group's heading, without touching location.hash.
//
// And at 800px none of it exists: no pane content, no selection mark, no Details button, no
// index, and the page is back to its single column.

import { createSession, renameSession, setSynthesis, setTitle } from '../../src/core/session.js';
import { seedTurn, submitAnswer } from '../../src/runtime/turn.js';
import { deleteSession, listSessions, saveSession } from '../../src/store/sessions.js';

const BOOT_MS = 5000;
const settle = (ms = 150) => new Promise((resolve) => setTimeout(resolve, ms));
const HOSTILE_NAME = '<img src=x onerror="window.__pwned=1">Hostile <b>name</b>';
const HOSTILE_LINE = 'Never render <script>window.__pwned=2</script> or <b>raw</b> markup.';

async function until(predicate, ms = 4000) {
  const deadline = performance.now() + ms;
  do {
    let value;
    try { value = await predicate(); } catch { value = null; }
    if (value) return value;
    await settle(25);
  } while (performance.now() < deadline);
  return null;
}

function loadFrame(width, height) {
  return new Promise((resolve, reject) => {
    const frame = document.createElement('iframe');
    frame.width = width;
    frame.height = height;
    frame.style.position = 'absolute';
    frame.style.left = '-10000px';
    frame.onload = () => resolve(frame);
    frame.onerror = () => reject(new Error('desktop iframe failed to load'));
    frame.src = '/index.html';
    document.body.append(frame);
  });
}

function activeSession(id, now, opening, { name = '', answered = true } = {}) {
  let session = seedTurn(createSession({ id, now }), { now });
  if (name) session = renameSession(session, name, now + 1);
  if (answered) session = submitAnswer(session, { text: opening, now: now + 2 });
  return session;
}

function completedSession(id, now, name, body) {
  let session = activeSession(id, now, `the idea called ${name}`, { name });
  session = setSynthesis(session, {
    text: `## Task\n${body}\n\n- first point\n- second point`,
    assumptions: [],
    openQuestions: [],
    now: now + 5,
  });
  return setTitle(session, `${name} generated`, now + 6);
}

const IDS = ['d_done', 'd_active', 'd_fresh', 'd_hostile'];

async function clean() {
  for (const registration of await navigator.serviceWorker.getRegistrations()) {
    await registration.unregister();
  }
  for (const key of await caches.keys()) await caches.delete(key);
  for (const session of await listSessions()) await deleteSession(session.id);
}

export default async function run(check) {
  await clean();
  const now = Date.now();
  // Newest first in the list: hostile, fresh, active, done.
  await saveSession(completedSession('d_done', now, 'Orchard planner',
    'Plan the orchard rows by sunlight and water.'));
  await saveSession(activeSession('d_active', now + 10, 'A bike route finder', { name: 'Bike routes' }));
  await saveSession(activeSession('d_fresh', now + 20, '', { name: 'Fresh idea', answered: false }));
  await saveSession(completedSession('d_hostile', now + 30, HOSTILE_NAME, HOSTILE_LINE));

  const frame = await loadFrame(1280, 900);
  const win = frame.contentWindow;
  const doc = frame.contentDocument;
  const $ = (id) => doc.getElementById(id);
  const errors = [];
  win.addEventListener('error', (event) => errors.push(String(event.message)));
  win.addEventListener('unhandledrejection', (event) => errors.push(String(event.reason)));
  win.fetch = async () => { throw new Error('network disabled in desktop probe'); };
  const rendered = (el) => Boolean(el) && win.getComputedStyle(el).display !== 'none'
    && el.getClientRects().length > 0;
  const box = (el) => el.getBoundingClientRect();
  const noSideways = () => doc.documentElement.scrollWidth <= doc.documentElement.clientWidth;
  /** True while a View Transition is still drawing (see the Settings step below). */
  const transitioning = () => doc.documentElement.matches(':active-view-transition');
  const card = (id) => $('library-rows').querySelector(`[data-session-id="${id}"]`);
  const button = (root, label) =>
    [...root.querySelectorAll('button')].find((item) => item.textContent === label);
  const detail = () => $('library-detail');

  try {
    const booted = await until(() => $('version') && $('version').textContent
      && doc.body.dataset.panel === 'library'
      && $('library-rows').querySelectorAll('.idea-card').length === IDS.length, BOOT_MS);
    check('at 1280px the app boots to Ideas with every saved idea listed', Boolean(booted),
      `${doc.body.dataset.panel}, ${$('library-rows') && $('library-rows').children.length} rows`);

    // ── list | detail ────────────────────────────────────────────────────────
    $('library-status').value = 'all';
    $('library-status').dispatchEvent(new win.Event('change'));
    await settle(100);
    const rows = box($('library-rows'));
    const pane = box(detail());
    check('Ideas widens the page past the 720px column',
      box(doc.querySelector('.wrap')).width > 1000, `${Math.round(box(doc.querySelector('.wrap')).width)}px`);
    check('the list and the detail pane sit side by side',
      rendered(detail()) && pane.left >= rows.right && pane.top < rows.bottom && pane.width >= 400,
      `rows ${Math.round(rows.left)}–${Math.round(rows.right)}, pane ${Math.round(pane.left)}–${Math.round(pane.right)} top ${Math.round(pane.top)}`);
    check('…with nothing scrolling sideways, and the pane inside the page column',
      noSideways() && pane.right <= box(doc.querySelector('.wrap')).right,
      `${doc.documentElement.scrollWidth}/${doc.documentElement.clientWidth}, pane right ${Math.round(pane.right)}, wrap right ${Math.round(box(doc.querySelector('.wrap')).right)}`);
    check('the detail region is labelled',
      detail().getAttribute('role') === 'region' && Boolean(detail().getAttribute('aria-label')));
    const first = $('library-rows').querySelector('.idea-card');
    check('the first idea listed is selected to begin with',
      detail().dataset.sessionId === first.dataset.sessionId
        && first.getAttribute('aria-current') === 'true',
      `pane ${detail().dataset.sessionId}, first ${first.dataset.sessionId}`);

    const marked = () => [...$('library-rows').querySelectorAll('.idea-card[aria-current="true"]')]
      .map((el) => el.dataset.sessionId);
    const agrees = (id) => {
      const title = detail().querySelector('.library-detail-title');
      return detail().dataset.sessionId === id
        && title && title.textContent === card(id).querySelector('h3').textContent
        && marked().length === 1 && marked()[0] === id;
    };

    // The card's body selects it. Clicked on the meta line, which is not a control.
    card('d_done').querySelector('.idea-meta').click();
    await settle(50);
    check('clicking a card body shows that idea in the pane, and only it is marked',
      agrees('d_done'), `pane ${detail().dataset.sessionId}, marked ${marked().join(',')}`);
    const preview = detail().querySelector('.library-preview');
    check('…with its export rendered as elements, not as markdown source',
      Boolean(preview) && preview.querySelectorAll('h2, p, li').length >= 3
        && preview.textContent.includes('Plan the orchard rows by sunlight and water.')
        && !preview.textContent.includes('## Task'),
      preview ? preview.textContent.slice(0, 120) : 'no preview');
    check('…and its primary action and overflow trigger',
      Boolean(button(detail(), 'View result')) && Boolean(detail().querySelector('.idea-more')));

    // The Details button is the keyboard's way in, and it moves focus to the pane.
    const details = card('d_active').querySelector('.idea-select');
    check('each card has a visible Details button at this width',
      rendered(details) && details.getAttribute('aria-controls') === 'library-detail');
    details.click();
    await settle(50);
    check('Details selects its idea and moves focus to the pane heading',
      agrees('d_active') && doc.activeElement === detail().querySelector('.library-detail-title')
        && details.getAttribute('aria-pressed') === 'true',
      `pane ${detail().dataset.sessionId}, focus ${doc.activeElement && doc.activeElement.className}`);
    check('…and the card it left is no longer marked',
      !card('d_done').hasAttribute('aria-current')
        && card('d_done').querySelector('.idea-select').getAttribute('aria-pressed') === 'false');

    // A control inside a card keeps its own meaning: opening the card's menu selects nothing.
    card('d_done').querySelector('.idea-more').click();
    await settle(50);
    check('opening a card\'s overflow menu does not change the selection', agrees('d_active'));
    card('d_done').querySelector('[popover]').hidePopover();

    card('d_fresh').querySelector('.idea-meta').click();
    await settle(50);
    check('an idea with nothing answered says so instead of previewing an empty document',
      agrees('d_fresh') && /Nothing to preview yet/.test(detail().textContent)
        && Boolean(button(detail(), 'Continue')));

    // Markup in the name and in the model's write-up arrives as text.
    card('d_hostile').querySelector('.idea-meta').click();
    await settle(50);
    const hostileTitle = detail().querySelector('.library-detail-title');
    check('a name full of markup is shown as text in the pane',
      agrees('d_hostile') && hostileTitle.textContent.includes('<img')
        && hostileTitle.textContent.includes('<b>name</b>'),
      hostileTitle && hostileTitle.textContent);
    check('…and so is markup in the export: no element is injected',
      detail().querySelectorAll('img, script, b, iframe').length === 0
        && detail().querySelector('.library-preview').textContent.includes('<b>raw</b>')
        && win.__pwned === undefined,
      `${detail().querySelectorAll('img, script, b, iframe').length} injected`);

    // The pane's overflow menu is the card's, under its own id.
    const paneMore = detail().querySelector('.idea-more');
    paneMore.click();
    const paneMenu = detail().querySelector('[popover]');
    check('the pane\'s overflow menu opens with the card\'s actions',
      paneMenu.matches(':popover-open') && paneMenu.id === 'library-detail-menu'
        && ['Share .md', 'Download .md', 'Archive', 'Delete'].every((label) => button(paneMenu, label)));
    paneMenu.hidePopover();

    // Filtering the selection out moves the pane to an idea that is still listed.
    $('library-search').value = 'bike';
    $('library-search').dispatchEvent(new win.Event('input'));
    await settle(50);
    check('a selection the filters hide falls to the first idea still listed', agrees('d_active'),
      `pane ${detail().dataset.sessionId}`);
    // Filters that match nothing: the list says so, and there is no pane beside it — not an
    // empty bordered card, and not "Select an idea" next to "No ideas match these filters".
    $('library-search').value = 'no idea is called this';
    $('library-search').dispatchEvent(new win.Event('input'));
    await settle(50);
    check('when the filters match nothing there is no pane beside the message',
      !$('library-empty').hidden && !rendered(detail()) && marked().length === 0,
      `empty shown ${!$('library-empty').hidden}, pane "${detail().textContent}"`);
    $('library-search').value = '';
    $('library-search').dispatchEvent(new win.Event('input'));
    await settle(50);

    const originalFont = doc.documentElement.style.fontSize;
    const originalTheme = doc.documentElement.dataset.theme;
    try {
      doc.documentElement.style.fontSize = '200%';
      for (const theme of ['light', 'dark']) {
        doc.documentElement.dataset.theme = theme;
        for (const width of [1024, 1200, 1440]) {
          frame.width = width;
          await settle(100);
          card('d_done').querySelector('.idea-meta').click();
          await settle(50);
          const region = box(detail());
          const actions = [...detail().querySelectorAll('.idea-actions > button')].map(box);
          const preview = detail().querySelector('.library-preview');
          const padding = win.getComputedStyle(detail());
          const label = `${theme} Ideas at ${width}px and 200% text`;
          check(`${label}: both detail actions fit inside the pane without sideways scrolling`,
            parseFloat(win.getComputedStyle(doc.body).fontSize) === 32 && actions.length === 2
              && actions.every((r) => r.left >= region.left && r.right <= region.right
                && r.width >= 48 && r.height >= 48)
              && detail().scrollWidth <= detail().clientWidth,
            `pane ${region.width}px, actions ${actions.map((r) => `${r.width}px to ${r.right}`).join(',')}`
              + `, scroll ${detail().scrollWidth}/${detail().clientWidth}`);
          check(`${label}: prose preview keeps a usable share of the workspace`,
            region.width >= box($('library-rows')).width
              && preview.getBoundingClientRect().width >= detail().clientWidth
                - parseFloat(padding.paddingLeft) - parseFloat(padding.paddingRight) - 1
              && preview.scrollWidth <= preview.clientWidth && noSideways(),
            `preview ${preview.getBoundingClientRect().width}px, scroll ${preview.scrollWidth}/${preview.clientWidth}`);
          win.scrollTo(0, doc.documentElement.scrollHeight);
          const stuck = box(detail());
          check(`${label}: the sticky pane remains fully in the viewport at page end`,
            stuck.top >= 0 && stuck.bottom <= win.innerHeight,
            `${stuck.top} to ${stuck.bottom} of ${win.innerHeight}`);
          win.scrollTo(0, 0);
        }
      }
    } finally {
      doc.documentElement.style.fontSize = originalFont;
      if (originalTheme === undefined) delete doc.documentElement.dataset.theme;
      else doc.documentElement.dataset.theme = originalTheme;
      frame.width = 1280;
      await settle(100);
    }

    // ── the pane's primary navigates to the idea it shows ─────────────────────
    card('d_done').querySelector('.idea-meta').click();
    await settle(50);
    button(detail(), 'View result').click();
    const done = await until(() => !$('panel-done').hidden
      && ($('output').dataset.source || '').includes('Plan the orchard rows'));
    check('View result in the pane opens Done on that idea', Boolean(done),
      `${doc.body.dataset.panel}: ${($('output').dataset.source || '').slice(0, 80)}`);

    // While the library loads, the placeholders stand alone: no empty pane beside them. A
    // MutationObserver sees the 'loading' state as soon as renderLoading has run, before the
    // read can replace it.
    const loadingSamples = [];
    const watcher = new win.MutationObserver(() => {
      if ($('panel-library').dataset.libraryState === 'loading') {
        loadingSamples.push(rendered(detail()) ? `pane "${detail().textContent}"` : 'none');
      }
    });
    watcher.observe($('panel-library'), { attributes: true, attributeFilter: ['data-library-state'] });
    $('b-library').click();
    await until(() => !$('panel-library').hidden && card('d_active'));
    watcher.disconnect();
    check('while the library loads there is no pane beside the placeholders',
      loadingSamples.length > 0 && loadingSamples.every((sample) => sample === 'none'),
      loadingSamples.join(' | ') || 'the loading state was never seen');
    card('d_active').querySelector('.idea-meta').click();
    await settle(50);
    button(detail(), 'Continue').click();
    const interview = await until(() => !$('panel-interview').hidden && doc.querySelector('details.cov'));
    check('Continue in the pane opens the interview on that idea', Boolean(interview),
      doc.body.dataset.panel);
    await settle(200);
    const question = box(doc.querySelector('.question-card'));
    const coverage = box(doc.querySelector('details.cov'));
    check('the interview still has Coverage beside the question at 1280px',
      coverage.left >= question.right && coverage.top < question.bottom && coverage.width >= 240,
      `card ${Math.round(question.left)}–${Math.round(question.right)}, cov ${Math.round(coverage.left)}–${Math.round(coverage.right)}`);
    check('…on the 1120px page, with nothing scrolling sideways',
      box(doc.querySelector('.wrap')).width <= 1120 && box(doc.querySelector('.wrap')).width > 1000
        && noSideways(),
      `${Math.round(box(doc.querySelector('.wrap')).width)}px`);

    // ── Settings: index | groups ─────────────────────────────────────────────
    $('b-settings').click();
    // Layout is measured after the navigation's View Transition (#108) has finished: while
    // one is active the root reports a full-viewport snapshot, scrollbar gutter included, as
    // scrollWidth (1280/1265 here) although no scrollbar appears and nothing can scroll.
    await until(() => $('panel-setup').dataset.view === 'settings' && !$('panel-setup').hidden
      && !transitioning());
    await settle(100);
    const index = $('settings-index');
    const indexBox = box(index);
    const groups = ['connect', 'settings-listen', 'settings-appearance', 'settings-data', 'settings-about'];
    check('Settings shows its index beside the groups',
      rendered(index) && groups.every((id) => box($(id)).left >= indexBox.right)
        && indexBox.top < box($('connect')).bottom,
      `index ${Math.round(indexBox.left)}–${Math.round(indexBox.right)}, connect ${Math.round(box($('connect')).left)}`);
    check('…with one entry per group, in order',
      [...index.querySelectorAll('button')].map((b) => b.dataset.target).join() === groups.join()
        && [...index.querySelectorAll('button')].map((b) => b.textContent).join('|')
          === 'Connection|Voice|Appearance|Your data|About');
    check('…and nothing scrolling sideways', noSideways(),
      `${doc.documentElement.scrollWidth}/${doc.documentElement.clientWidth}`);

    const hash = win.location.hash;
    // In view: its top edge at the top of the viewport (the scroll margin), or, for a group
    // too near the end of the page to scroll that far, wholly on screen.
    const inView = (el) => {
      const r = box(el);
      return r.top >= -1 && (r.top <= 24 || r.bottom <= win.innerHeight + 1);
    };
    win.scrollTo(0, 0);
    await settle(50);
    const aboutStart = box($('settings-about')).top;
    for (const target of ['settings-data', 'settings-about', 'connect']) {
      const entry = index.querySelector(`[data-target="${target}"]`);
      entry.click();
      const reached = await until(() => inView($(target)), 2500);
      check(`the ${entry.textContent} entry brings its group into view and focuses its heading`,
        Boolean(reached) && doc.activeElement === $(target).querySelector('h2')
          && entry.getAttribute('aria-current') === 'true',
        `top ${Math.round(box($(target)).top)} of ${win.innerHeight}, focus ${doc.activeElement && doc.activeElement.tagName}`);
    }
    check('…the last groups started below the fold, so the index really scrolled',
      aboutStart > win.innerHeight, `About started at ${Math.round(aboutStart)} of ${win.innerHeight}`);
    check('…without changing location.hash', win.location.hash === hash, win.location.hash);

    // ── 800px: none of it ────────────────────────────────────────────────────
    frame.width = 800;
    await settle(200);
    check('at 800px Settings has no index and the single column',
      !rendered(index) && box(doc.querySelector('.wrap')).width <= 720 && noSideways(),
      `${Math.round(box(doc.querySelector('.wrap')).width)}px`);
    $('b-library').click();
    await until(() => !$('panel-library').hidden && card('d_done') && !transitioning());
    await settle(100);
    check('at 800px Ideas has no detail pane and no selection mark',
      !rendered(detail()) && detail().childNodes.length === 0
        && marked().length === 0 && box(doc.querySelector('.wrap')).width <= 720,
      `${detail().childNodes.length} nodes, marked ${marked().join(',')}`);
    check('…no Details buttons',
      [...$('library-rows').querySelectorAll('.idea-select')].every((el) => !rendered(el)));
    card('d_done').querySelector('.idea-meta').click();
    await settle(50);
    check('…and a card body click selects nothing',
      marked().length === 0 && detail().childNodes.length === 0 && !$('panel-library').hidden);
    check('…with nothing scrolling sideways', noSideways());

    // Widening again brings the pane back without a re-render.
    frame.width = 1280;
    await settle(200);
    check('back at 1280px the pane returns, describing a listed idea',
      rendered(detail()) && marked().length === 1 && detail().dataset.sessionId === marked()[0]);

    check('no page errors during the desktop run', errors.length === 0, errors.join(' | '));
  } finally {
    frame.remove();
    for (const id of IDS) await deleteSession(id);
  }
}
