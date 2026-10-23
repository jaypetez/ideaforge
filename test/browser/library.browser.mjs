import {
  archiveSession, createSession, renameSession, setDraftAnswer, setSessionTags,
  setPending, setStatusField, setSynthesis, setTitle,
} from '../../src/core/session.js';
import { seedTurn, submitAnswer } from '../../src/runtime/turn.js';
import { deleteSession, loadSession, saveSession } from '../../src/store/sessions.js';
import { buildBackup } from '../../src/core/backup.js';
import { acknowledgeInference } from './fixtures/inference.js';

const BOOT_MS = 4000;
const settle = (ms = 150) => new Promise((resolve) => setTimeout(resolve, ms));
const visibleIn = (root, label) => {
  const found = [...root.querySelectorAll('button')].find((item) => item.textContent === label);
  return Boolean(found && found.getClientRects().length > 0);
};

function loadFrame(src) {
  return new Promise((resolve, reject) => {
    const frame = document.createElement('iframe');
    frame.width = 900;
    frame.height = 800;
    frame.style.position = 'absolute';
    frame.style.left = '-10000px';
    frame.onload = () => resolve(frame);
    frame.onerror = () => reject(new Error(`iframe failed to load ${src}`));
    frame.src = src;
    document.body.append(frame);
  });
}

function idbRequest(request) {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
    request.onblocked = () => reject(new Error('blocked by an open connection'));
  });
}

/** boot() writes the version last, after it has chosen the first screen. */
async function booted(frame) {
  for (let waited = 0; waited < BOOT_MS; waited += 50) {
    const version = frame.contentDocument && frame.contentDocument.getElementById('version');
    if (version && version.textContent) return true;
    await settle(50);
  }
  return false;
}

function activeSession(id, now, opening, { name = '', tags = [], draft = '' } = {}) {
  let session = seedTurn(createSession({ id, now }), { now });
  if (name) session = renameSession(session, name, now + 1);
  if (tags.length) session = setSessionTags(session, tags, now + 2);
  if (draft) session = setDraftAnswer(session, draft, now + 3);
  return session;
}

function completedSession(id, now, opening, name) {
  let session = activeSession(id, now, opening, { name });
  session = submitAnswer(session, { text: opening, now: now + 4 });
  session = setSynthesis(session, {
    text: `## Task\nBuild ${opening}.`,
    assumptions: [],
    openQuestions: [],
    now: now + 5,
  });
  return setTitle(session, `${name} generated`, now + 6);
}

function pendingSynthesis(id, now) {
  let session = activeSession(id, now, 'An interrupted write-up');
  session = submitAnswer(session, { text: 'An interrupted write-up', now: now + 1 });
  session = setPending(session, {
    kind: 'synthesis', promptHash: 'pending-hash', startedAt: now + 2,
  }, now + 2);
  return setStatusField(session, 'synthesizing', now + 3);
}

function findButton(root, label) {
  return [...root.querySelectorAll('button')].find((item) => item.textContent === label);
}

// Everything but the primary action lives in the card's overflow menu (#102). Open it the way
// a person does, through its trigger, and only then find the action by its visible label.
// Returns null when the menu did not open, so a hidden-but-present button cannot pass.
function menuButton(card, label) {
  const trigger = card.querySelector('.idea-more');
  const menu = card.querySelector('[popover]');
  if (!trigger || !menu) return null;
  if (!menu.matches(':popover-open')) {
    trigger.focus();
    trigger.click();
  }
  if (!menu.matches(':popover-open')) return null;
  const found = findButton(menu, label);
  return found && found.getClientRects().length > 0 ? found : null;
}

export default async function run(check) {
  const now = Date.now();
  const draftText = 'A draft that must survive closing the app.';
  const sessions = [
    activeSession('s_draft', now, 'A notebook for customer questions', {
      name: 'Customer notebook', tags: ['research'], draft: draftText,
    }),
    completedSession('s_done', now + 10, 'a conference name helper', 'Conference helper'),
    activeSession('s_search', now + 20, 'A quiet tool', {
      name: 'Long Unicode 東京 — mémoire <b>literal</b> '.repeat(4),
      tags: Array.from({ length: 12 }, (_, i) => `context-${i + 1}`),
      draft: 'A quiet tool with the unique needle phrase',
    }),
    activeSession('s_four', now + 30, 'A fourth idea'),
    activeSession('s_five', now + 40, 'A fifth idea'),
    activeSession('s_six', now + 50, 'A sixth idea'),
    pendingSynthesis('s_synthesis', now + 55),
    archiveSession(activeSession('s_archived', now + 60, 'An archived idea'), now + 61),
    // Archiving does not change status: a finished idea that was archived is still 'done',
    // so its card still offers View result. The guide said otherwise once.
    archiveSession(completedSession('s_done_archived', now + 62, 'an archived result',
      'Archived result'), now + 69),
  ];
  for (const session of sessions) await saveSession(session);

  const frame = await loadFrame('/index.html');
  const win = frame.contentWindow;
  const doc = frame.contentDocument;
  await settle(BOOT_MS);
  const $ = (id) => doc.getElementById(id);
  const rendered = (id) => win.getComputedStyle($(id)).display !== 'none';

  // With saved ideas the app opens on them (#103), Ideas current; Start is a tap away.
  check('with saved ideas the app boots to Ideas',
    rendered('panel-library') && !rendered('panel-setup') && doc.body.dataset.panel === 'library',
    doc.body.dataset.panel);
  check('…with Ideas as the current destination',
    $('b-library').getAttribute('aria-current') === 'page'
      && !$('b-nav-new').hasAttribute('aria-current'));
  check('…listing every stored idea from the boot read',
    $('library-rows').querySelectorAll('.idea-card').length === sessions.length,
    `${$('library-rows').querySelectorAll('.idea-card').length} cards`);
  check('the setup screen shows only three recent ideas',
    $('resume-rows').children.length === 3, `${$('resume-rows').children.length} rows`);

  frame.width = 390;
  frame.height = 844;
  await settle(100);
  await Promise.all($('panel-library').getAnimations().map((animation) => animation.finished));
  const firstRows = [...$('library-rows').querySelectorAll('.idea-card')].slice(0, 3)
    .map((row) => row.getBoundingClientRect());
  const navigationTop = doc.querySelector('.bar-right').getBoundingClientRect().top;
  check('at 390x844 the first short-title idea starts by y=320',
    firstRows.length === 3 && firstRows[0].top <= 320,
    firstRows.map((row) => `${Math.round(row.top)}–${Math.round(row.bottom)}`).join(', '));
  check('at 390x844 three complete short-title ideas fit above navigation',
    firstRows.length === 3 && firstRows.every((row) => row.bottom <= navigationTop),
    `rows end ${firstRows.map((row) => Math.round(row.bottom)).join(', ')}; navigation ${navigationTop}`);
  frame.width = 900;
  frame.height = 800;
  await settle(100);

  $('b-nav-new').click();
  await settle(300);
  check('New idea leaves the library for Start', rendered('panel-setup') && !rendered('panel-library'));
  $('b-library').click();
  await settle(300);
  check('the Ideas button opens the library', rendered('panel-library'));
  check('the library does not show one interview’s coverage as a global meter', !rendered('meter'));
  check('the library shows every stored idea',
    $('library-rows').children.length === sessions.length,
    `${$('library-rows').children.length} cards`);
  const filters = doc.querySelector('.library-filter-options');
  check('Status and Tag are in one initially collapsed filter disclosure',
    !filters.open && filters.contains($('library-status')) && filters.contains($('library-tag'))
      && !$('library-status').checkVisibility() && !$('library-tag').checkVisibility());
  filters.open = true;
  check('opening Filters exposes both labelled controls',
    $('library-status').getClientRects().length > 0 && $('library-tag').getClientRects().length > 0);
  $('library-status').value = 'active';
  $('library-status').dispatchEvent(new win.Event('change'));
  $('library-tag').value = 'research';
  $('library-tag').dispatchEvent(new win.Event('change'));
  check('status/tag filters share a truthful compact summary and clear together',
    $('library-rows').children.length === 1
      && /Active.*research/.test($('library-filter-summary').textContent)
      && !$('b-library-clear').hidden);
  $('b-library-clear').click();
  filters.open = false;

  $('library-search').value = 'unique needle';
  $('library-search').dispatchEvent(new win.Event('input'));
  check('search reads the saved conversation text',
    $('library-rows').children.length === 1
      && $('library-rows').firstElementChild.dataset.sessionId === 's_search',
    `${$('library-rows').children.length} cards / `
      + `${$('library-rows').firstElementChild && $('library-rows').firstElementChild.dataset.sessionId}`);
  $('library-search').value = '';
  $('library-search').dispatchEvent(new win.Event('input'));

  // One primary action and one overflow trigger, without a repeated editor. The action follows the
  // same status rule as everywhere else: a finished idea is viewed, any other is continued.
  const cards = [...$('library-rows').querySelectorAll('.idea-card')];
  const primary = (card) => card.querySelector('.idea-actions > button:not(.idea-more)');
  check('rows have no repeated rename disclosures or input fields',
    cards.length === sessions.length
      && cards.every((card) => !card.querySelector('details, input')),
    cards.map((card) => card.querySelectorAll('details, input').length).join(','));
  check('every card shows exactly one primary action beside its overflow trigger',
    cards.every((card) => card.querySelectorAll('.idea-actions > button').length === 2
      && card.querySelectorAll('.idea-more').length === 1),
    cards.map((card) => card.querySelectorAll('.idea-actions > button').length).join(','));
  const finished = ['s_done', 's_done_archived'];
  const finishedPrimary = (id) => primary($('library-rows').querySelector(`[data-session-id="${id}"]`));
  check('a finished idea’s primary action is View result, every other idea’s is Continue',
    finished.every((id) => finishedPrimary(id).textContent === 'View result')
      && cards.filter((card) => !finished.includes(card.dataset.sessionId))
        .every((card) => primary(card).textContent === 'Continue'),
    cards.map((card) => primary(card).textContent).join(','));
  check('the secondary actions are hidden until the overflow menu opens',
    cards.every((card) => !findButton(card, 'Delete').getClientRects().length));
  const longCard = $('library-rows').querySelector('[data-session-id="s_search"]');
  const longTags = longCard.querySelector('.idea-tags');
  check('many tags have two visible labels and an accessible remaining-tag count',
    longTags.children.length === 3 && longTags.lastElementChild.textContent.includes('+10 more tags'));
  menuButton(longCard, 'Rename or tag').click();
  const allTagsEditor = doc.querySelector('dialog.idea-editor');
  const storedLong = await loadSession('s_search');
  check('the explicit editor reveals every tag, not only the two shown in its row',
    allTagsEditor.querySelector('#idea-editor-tags').value.split(',').length === 12
      && allTagsEditor.querySelector('#idea-editor-tags').value.includes('context-12'));
  allTagsEditor.querySelector('#idea-editor-name').value = 'Do not save this name';
  findButton(allTagsEditor, 'Cancel').click();
  await settle(30);
  check('Cancel leaves the saved long name and every tag unchanged',
    (await loadSession('s_search')).name === storedLong.name
      && (await loadSession('s_search')).tags.join('|') === storedLong.tags.join('|'));
  const originalFont = doc.documentElement.style.fontSize;
  const originalTheme = doc.documentElement.dataset.theme;
  try {
    for (const theme of ['light', 'dark']) for (const scale of [1, 2]) {
      doc.documentElement.dataset.theme = theme;
      doc.documentElement.style.fontSize = `${scale * 100}%`;
      for (const width of [320, 360, 390, 600, 840, 1280, 1440]) {
        frame.width = width;
        await settle(35);
        const fits = cards.every((card) => {
          const row = card.getBoundingClientRect();
          const summary = card.querySelector('.idea-summary').getBoundingClientRect();
          const actions = [...card.querySelectorAll('.idea-actions > button')]
            .map((button) => button.getBoundingClientRect());
          return !card.querySelector('button button') && actions.every((r) => r.width >= 48
            && r.height >= 48 && r.left >= summary.right && r.right <= row.right)
            && summary.left >= row.left && summary.right <= row.right;
        });
        check(`${theme} ${width}px text ${scale}: long Unicode/tags reflow beside separate 48px row actions`,
          fits && doc.documentElement.scrollWidth <= doc.documentElement.clientWidth,
          `${doc.documentElement.scrollWidth}/${doc.documentElement.clientWidth}`);
      }
    }
  } finally {
    doc.documentElement.style.fontSize = originalFont;
    if (originalTheme === undefined) delete doc.documentElement.dataset.theme;
    else doc.documentElement.dataset.theme = originalTheme;
    frame.width = 900;
    await settle(100);
  }
  const archivedCard = $('library-rows').querySelector('[data-session-id="s_archived"]');
  check('an archived idea’s menu offers Restore rather than Archive',
    Boolean(menuButton(archivedCard, 'Restore')) && !findButton(archivedCard, 'Archive'));
  archivedCard.querySelector('[popover]').hidePopover();
  const archivedDone = $('library-rows').querySelector('[data-session-id="s_done_archived"]');
  check('a completed idea that was archived still offers View result, and Restore',
    finishedPrimary('s_done_archived').textContent === 'View result'
      && Boolean(menuButton(archivedDone, 'Restore')),
    finishedPrimary('s_done_archived').textContent);
  archivedDone.querySelector('[popover]').hidePopover();

  // Focus goes into the menu when it opens, and back to the trigger when it closes. Native
  // popover behaviour, but only if focus was inside the menu, which library.js does on toggle.
  const escCard = $('library-rows').querySelector('[data-session-id="s_four"]');
  const escTrigger = escCard.querySelector('.idea-more');
  const escMenu = escCard.querySelector('[popover]');
  escTrigger.focus();
  escTrigger.click();
  await settle(100);
  check('the overflow trigger opens its menu and moves focus into it',
    escMenu.matches(':popover-open') && escMenu.contains(doc.activeElement),
    doc.activeElement && doc.activeElement.textContent);
  check('the open menu holds Archive and Delete by their exact labels',
    ['Archive', 'Delete'].every((label) => visibleIn(escMenu, label)));
  // Esc itself is checked with trusted keyboard input in the real-app policy phase
  // (app-audio-policy.probe.mjs): a synthetic KeyboardEvent never closes a popover.
  escMenu.hidePopover();
  await settle(50);
  check('closing the menu returns focus to its trigger', doc.activeElement === escTrigger,
    doc.activeElement && (doc.activeElement.className || doc.activeElement.tagName));

  // The menu is positioned once, against its trigger. A resize would leave it pointing at
  // nothing, so it closes instead, and a later resize finds no stale listener to run.
  escTrigger.click();
  await settle(100);
  const opened = escMenu.matches(':popover-open');
  frame.width = 880;
  await settle(150);
  check('resizing the window closes an open overflow menu',
    opened && !escMenu.matches(':popover-open'), `opened ${opened}`);
  frame.width = 900;
  await settle(100);

  let draftCard = $('library-rows').querySelector('[data-session-id="s_draft"]');
  menuButton(draftCard, 'Rename or tag').click();
  await settle(50);
  const edit = doc.querySelector('dialog.idea-editor');
  check('Rename or tag opens one labelled modal editor for the intended idea',
    edit?.matches(':modal') && edit.textContent.includes('Customer notebook')
      && doc.activeElement === edit.querySelector('input')
      && [...edit.querySelectorAll('input')].every((input) =>
        edit.querySelector(`label[for="${input.id}"]`)));
  const inputs = edit.querySelectorAll('input');
  inputs[0].value = 'Renamed customer notebook';
  inputs[1].value = 'Research, Mobile';
  const put = win.IDBObjectStore.prototype.put;
  win.IDBObjectStore.prototype.put = function (...args) {
    if (this.name === 'sessions') throw new win.DOMException('Scripted edit storage failure', 'QuotaExceededError');
    return put.apply(this, args);
  };
  findButton(edit, 'Save details').click();
  await settle(150);
  check('a failed edit keeps the entered details and a named inline retry error',
    edit.open && /Details could not be saved/.test(edit.querySelector('[role="alert"]').textContent)
      && inputs[0].value === 'Renamed customer notebook' && !inputs[0].disabled
      && (await loadSession('s_draft')).name === 'Customer notebook');
  win.IDBObjectStore.prototype.put = put;
  findButton(edit, 'Save details').click();
  await settle(250);
  const edited = await loadSession('s_draft');
  check('renaming an idea persists through IndexedDB', edited.name === 'Renamed customer notebook');
  check('tag edits are normalized and persisted',
    edited.tags.join('|') === 'Research|Mobile', edited.tags.join('|'));
  check('a successful edit closes its dialog, clears the retry error and restores row focus',
    !edit.isConnected && $('err-toast').hidden
      && doc.activeElement.closest('.idea-card')?.dataset.sessionId === 's_draft');

  draftCard = $('library-rows').querySelector('[data-session-id="s_draft"]');
  menuButton(draftCard, 'Archive').click();
  await settle(250);
  check('archiving an idea persists', (await loadSession('s_draft')).archivedAt > 0);

  const shared = [];
  Object.defineProperty(win.navigator, 'canShare', {
    configurable: true, value: ({ files }) => files && files[0] && files[0].type === 'text/markdown',
  });
  Object.defineProperty(win.navigator, 'share', {
    configurable: true, value: async (payload) => { shared.push(payload); },
  });
  const doneCard = $('library-rows').querySelector('[data-session-id="s_done"]');
  const shareButton = menuButton(doneCard, 'Share .md');
  check('a completed idea’s menu offers Share .md and Download .md',
    Boolean(shareButton) && visibleIn(doneCard.querySelector('[popover]'), 'Download .md'));
  // Mobile share sheets need the click's user activation, so navigator.share has to be called
  // before the handler yields. click() dispatches synchronously: a call recorded while this
  // flag is still set happened inside the click, with no await in front of it.
  let inClick = true;
  const during = [];
  Object.defineProperty(win.navigator, 'share', {
    configurable: true,
    value: async (payload) => { shared.push(payload); during.push(inClick); },
  });
  shareButton.click();
  inClick = false;
  await settle(150);
  check('a completed idea shares a markdown file through the native adapter',
    shared.length === 1 && shared[0].files[0].name.endsWith('.md'),
    shared[0] && shared[0].files[0].name);
  check('Share .md calls navigator.share synchronously inside the click',
    during.length === 1 && during[0] === true, JSON.stringify(during));
  check('choosing an action closes the menu',
    !doneCard.querySelector('[popover]').matches(':popover-open'));
  check('the shared file contains the refined prompt',
    shared.length === 1 && (await shared[0].files[0].text()).includes('## Refined prompt'));

  const imported = activeSession('s_imported', now + 70, 'An idea brought from another phone', {
    name: 'Imported phone idea',
  });
  const transfer = new win.DataTransfer();
  transfer.items.add(new win.File(
    [buildBackup([imported], { now: now + 71 })],
    'ideaforge-backup.json',
    { type: 'application/json' },
  ));
  // Backup and import live in Settings now (#101), not in the Library. Find them the way a
  // person does, by their visible labels in the Settings view, and import from there.
  const visible = (el) => !!el && el.getClientRects().length > 0;
  check('the Library no longer carries the backup controls',
    !$('panel-library').contains($('b-import')) && !$('panel-library').contains($('b-backup')));
  $('b-settings').click();
  await settle(300);
  const importButton = findButton($('panel-setup'), 'Import ideas');
  check('Settings shows Backup ideas and Import ideas in its Your data group',
    visible(findButton($('panel-setup'), 'Backup ideas')) && visible(importButton)
      && importButton.closest('.group').querySelector('h2').textContent === 'Your data');
  let picked = false;
  $('backup-file').addEventListener('click', (event) => { picked = true; event.preventDefault(); },
    { once: true });
  importButton.click();
  check('Import ideas in Settings opens the backup file picker', picked);
  Object.defineProperty($('backup-file'), 'files', {
    configurable: true,
    value: transfer.files,
  });
  $('backup-file').dispatchEvent(new win.Event('change'));
  await settle(300);
  check('a JSON backup imports through the Settings file control',
    Boolean(await loadSession('s_imported')));
  check('…and reports what it imported', /1 imported/.test($('note').textContent),
    $('note').textContent);
  $('b-library').click();
  await settle(300);
  check('the imported idea is in the Library afterwards',
    Boolean($('library-rows').querySelector('[data-session-id="s_imported"]')));

  frame.style.width = '390px';
  await settle(100);
  check('the library has no horizontal overflow at a phone width',
    doc.documentElement.scrollWidth <= doc.documentElement.clientWidth,
    `${doc.documentElement.scrollWidth}/${doc.documentElement.clientWidth}`);
  const phoneCards = [...$('library-rows').querySelectorAll('.idea-card')];
  check('…and every card, with its primary action and overflow trigger, fits inside it',
    phoneCards.every((card) => card.getBoundingClientRect().right <= doc.documentElement.clientWidth
      && card.querySelector('.idea-more').getBoundingClientRect().right
        <= card.getBoundingClientRect().right),
    phoneCards.map((card) => Math.round(card.getBoundingClientRect().right)).join(','));
  const phoneCard = phoneCards[0];
  menuButton(phoneCard, 'Delete');
  const phoneMenu = phoneCard.querySelector('[popover]').getBoundingClientRect();
  check('an open overflow menu stays inside a phone-width viewport',
    phoneMenu.left >= 0 && phoneMenu.right <= doc.documentElement.clientWidth
      && doc.documentElement.scrollWidth <= doc.documentElement.clientWidth,
    `${Math.round(phoneMenu.left)}–${Math.round(phoneMenu.right)} of ${doc.documentElement.clientWidth}`);
  phoneCard.querySelector('[popover]').hidePopover();

  await acknowledgeInference(win);
  const synthesisCard = $('library-rows').querySelector('[data-session-id="s_synthesis"]');
  findButton(synthesisCard, 'Continue').click();
  await settle(300);
  const recoveredSynthesis = await loadSession('s_synthesis');
  check('an interrupted synthesis resumes through the write-up path',
    rendered('panel-done') && recoveredSynthesis.pending === null
      && recoveredSynthesis.status === 'done');

  // The result is rendered markdown now, so #output.textContent is prose with every marker
  // stripped. Copy, Download and Share must still hand over the source — which is what the
  // element keeps in dataset.source, and what silently rewrote docs/examples/ as one
  // unbroken line the first time this was wired up.
  const source = $('output').dataset.source || '';
  check('the rendered result still carries its markdown source',
    /^#\s/.test(source) && source.includes('## Refined prompt'), source.slice(0, 60));
  check('...which is not what the rendered view reads as',
    !$('output').textContent.includes('## Refined prompt'));
  check('...and the document still renders as elements, not as text',
    $('output').querySelectorAll('h2, p').length > 2,
    `${$('output').querySelectorAll('h2, p').length} blocks`);

  $('b-library').click();
  await settle(250);
  const archivedDraft = $('library-rows').querySelector('[data-session-id="s_draft"]');
  findButton(archivedDraft, 'Continue').click();
  await settle(2200);
  check('opening a saved conversation restores its unfinished answer',
    $('answer').value === draftText, JSON.stringify($('answer').value));
  check('the resumed conversation opens the interview panel', rendered('panel-interview'));
  $('answer').value = 'A changed draft saved by the app.';
  $('answer').dispatchEvent(new win.Event('input'));
  await settle(850);
  check('editing an answer box persists the unfinished draft',
    (await loadSession('s_draft')).draftAnswer === 'A changed draft saved by the app.');

  $('b-library').click();
  await settle(250);
  let removable = $('library-rows').querySelector('[data-session-id="s_six"]');
  menuButton(removable, 'Delete').click();
  await settle(50);
  let confirmation = doc.querySelector('dialog[open]');
  check('Delete awaits an actual modal, with Cancel focused and no early deletion',
    confirmation?.matches(':modal')
      && doc.activeElement === confirmation.querySelector('[value="cancel"]')
      && Boolean(await loadSession('s_six')));
  confirmation.querySelector('[value="cancel"]').click();
  await settle(100);
  check('cancelling Delete keeps the idea and returns focus to its More trigger',
    Boolean(await loadSession('s_six')) && doc.activeElement === removable.querySelector('.idea-more'));
  removable = $('library-rows').querySelector('[data-session-id="s_six"]');
  menuButton(removable, 'Delete').click();
  await settle(50);
  confirmation = doc.querySelector('dialog[open]');
  confirmation.querySelector('[value="confirm"]').click();
  await settle(250);
  check('confirmed Delete removes only its idea and restores focus to an available row',
    (await loadSession('s_six')) === null && Boolean(await loadSession('s_five'))
      && Boolean(doc.activeElement.closest('.idea-card')));

  // Filters that match nothing are not the same as having no ideas.
  $('library-search').value = 'nothing in any saved idea says this';
  $('library-search').dispatchEvent(new win.Event('input'));
  check('filters matching nothing show the empty-filter message, not the first-run state',
    rendered('library-empty') && !rendered('library-zero')
      && $('library-rows').children.length === 0 && visible($('library-search')));
  check('no-match keeps an explicit Clear filters action', visible($('b-library-clear')));
  $('b-library-clear').click();
  check('Clear filters restores the list and search focus',
    !rendered('library-empty') && $('library-rows').children.length > 0
      && doc.activeElement === $('library-search') && $('library-status').value === 'all'
      && !$('library-tag').value);

  // No ideas at all: the first-run state, with a way to start one.
  for (const session of sessions) await deleteSession(session.id);
  await deleteSession('s_imported');
  const busyStates = [];
  const observer = new win.MutationObserver(() => {
    const list = $('library-rows');
    const skeletons = [...list.querySelectorAll('.idea-skeleton')];
    busyStates.push(`${list.getAttribute('aria-busy')}:${skeletons.length}`
      + `:${skeletons.every((el) => el.getAttribute('aria-hidden') === 'true') ? 'hidden' : 'exposed'}`
      + `:${list.querySelectorAll('.idea-card').length}:${$('library-count').textContent}`);
  });
  observer.observe($('library-rows'), { attributes: true, childList: true });
  $('b-settings').click();
  await settle(200);
  $('b-library').click();
  await settle(400);
  observer.disconnect();
  check('the list is marked busy with placeholder cards while it loads, then settles',
    busyStates.some((entry) => /^true:[1-9]/.test(entry))
      && !$('library-rows').hasAttribute('aria-busy')
      && !$('library-rows').querySelector('.idea-skeleton'),
    busyStates.join(' '));
  check('placeholder cards are hidden from assistive technology and are not idea cards',
    busyStates.filter((entry) => /^true:[1-9]/.test(entry))
      .every((entry) => /:hidden:0:/.test(entry)), busyStates.join(' '));
  // The bars are decoration below 3:1, so the loading state has to be said in text (#111).
  check('while it loads, the list says so in text, not only with low-contrast bars',
    busyStates.filter((entry) => /^true:[1-9]/.test(entry))
      .every((entry) => entry.endsWith(':Loading ideas…')), busyStates.join(' '));
  check('with no ideas at all the library shows its first-run state, not the filter message',
    rendered('library-zero') && !rendered('library-empty'));
  check('…without search and filters that have nothing to search',
    !visible($('library-search')));
  const zeroNew = findButton($('library-zero'), 'New idea');
  check('the first-run state offers New idea', visible(zeroNew));
  zeroNew.click();
  await settle(400);
  check('New idea from the first-run state opens Start',
    rendered('panel-setup') && $('panel-setup').dataset.view === 'start' && !rendered('panel-library'),
    $('panel-setup').dataset.view);

  frame.remove();

  // Where the app opens (#103) follows what is stored, and nothing else: no ideas, Start;
  // any idea, Ideas. Every idea was deleted above, so this is a returning visitor's device
  // in the state a fresh one is in.
  const empty = await loadFrame('/index.html');
  const $e = (id) => empty.contentDocument.getElementById(id);
  const shownE = (id) => empty.contentWindow.getComputedStyle($e(id)).display !== 'none';
  check('with no saved ideas the app boots to Start',
    await booted(empty) && shownE('panel-setup') && $e('panel-setup').dataset.view === 'start'
      && !shownE('panel-library') && empty.contentDocument.body.dataset.panel === 'setup',
    `${empty.contentDocument.body.dataset.panel}/${$e('panel-setup').dataset.view}`);
  check('…with Start as the current destination',
    $e('b-nav-new').getAttribute('aria-current') === 'page'
      && !$e('b-library').hasAttribute('aria-current'));

  // One idea saved, then the same tab reloaded: it opens on Ideas, showing that idea.
  await saveSession(activeSession('s_one', now + 70, 'The only idea', { name: 'Only idea' }));
  empty.contentWindow.location.reload();
  await new Promise((resolve) => { empty.onload = resolve; });
  check('reloading with one saved idea boots to Ideas',
    await booted(empty) && shownE('panel-library') && !shownE('panel-setup')
      && empty.contentDocument.body.dataset.panel === 'library',
    empty.contentDocument.body.dataset.panel);
  check('…with Ideas as the current destination',
    $e('b-library').getAttribute('aria-current') === 'page'
      && !$e('b-nav-new').hasAttribute('aria-current')
      && !$e('b-library').hasAttribute('aria-label'));
  check('…showing the stored idea from the boot read, not skeletons or the first-run state',
    Boolean($e('library-rows').querySelector('.idea-card[data-session-id="s_one"]'))
      && !$e('library-rows').hasAttribute('aria-busy') && !shownE('library-zero'));
  check('…and no error', $e('err-toast').hidden, $e('err').textContent);
  empty.remove();
  await deleteSession('s_one');

  // Archived ideas count: the library's default filter shows them, so a device holding only
  // archived ideas has a populated library to open on, not a first run.
  await saveSession(archiveSession(activeSession('s_only_archived', now + 75, 'Put away'), now + 76));
  const archivedOnly = await loadFrame('/index.html');
  const $a = (id) => archivedOnly.contentDocument.getElementById(id);
  check('a device holding only archived ideas boots to Ideas, listing them',
    await booted(archivedOnly) && !$a('panel-library').hidden
      && Boolean($a('library-rows').querySelector('[data-session-id="s_only_archived"]')),
    archivedOnly.contentDocument.body.dataset.panel);
  archivedOnly.remove();
  await deleteSession('s_only_archived');

  // A failed read is not an empty device. The fault has to be in place before the app's
  // modules run, so it is one the page cannot route around: this window upgrades the
  // database past the version the app asks for, the way a newer build would, and every
  // open the app makes fails with VersionError until it is put back.
  const kept = activeSession('s_kept', now + 80, 'An idea that is still stored', {
    name: 'Still stored',
  });
  await saveSession(kept);
  const newer = await idbRequest(indexedDB.open('ideaforge', 2));
  const second = await loadFrame('/index.html');
  const win2 = second.contentWindow;
  const $2 = (id) => second.contentDocument.getElementById(id);
  const shown2 = (id) => win2.getComputedStyle($2(id)).display !== 'none';
  check('a failed sessions read at boot falls back to Start',
    await booted(second) && shown2('panel-setup')
      && $2('panel-setup').dataset.view === 'start' && !shown2('panel-library')
      && second.contentDocument.body.dataset.panel === 'setup',
    `panel: ${second.contentDocument.body.dataset.panel}`);
  check('…with Start as the current destination',
    $2('b-nav-new').getAttribute('aria-current') === 'page'
      && !$2('b-library').hasAttribute('aria-current'));
  check('…and says the saved ideas could not be read, and why',
    !$2('err-toast').hidden && /saved ideas could not be read/i.test($2('err').textContent)
      && /version/i.test($2('err').textContent),
    $2('err').textContent);
  newer.close();
  await idbRequest(indexedDB.deleteDatabase('ideaforge'));
  await saveSession(kept);

  // Nothing has been read successfully yet, so opening the library while reads still fail
  // must not report "No ideas yet" with the filters gone.
  const getAll = win2.IDBObjectStore.prototype.getAll;
  let planted = 0;
  win2.IDBObjectStore.prototype.getAll = function patched(...args) {
    if (this.name === 'sessions') {
      planted++;
      throw new win2.DOMException('planted read failure', 'UnknownError');
    }
    return getAll.apply(this, args);
  };
  $2('b-library').click();
  await settle(400);
  check('a failed first library read is not reported as having no ideas',
    planted > 0 && !shown2('library-zero') && !shown2('library-empty'),
    `reads failed: ${planted}; zero shown: ${shown2('library-zero')}`);
  check('…it clears the placeholders and the busy state',
    !$2('library-rows').hasAttribute('aria-busy') && $2('library-rows').children.length === 0);
  check('…and says what went wrong', !$2('err-toast').hidden && $2('err').textContent.length > 0,
    $2('err').textContent);
  check('unavailable storage has an explicit retry, not an empty-library action',
    shown2('library-unavailable') && shown2('b-library-retry') && !shown2('library-zero'));
  win2.IDBObjectStore.prototype.getAll = getAll;
  $2('b-library-retry').click();
  await settle(400);
  check('the next successful read shows the stored ideas',
    Boolean($2('library-rows').querySelector('[data-session-id="s_kept"]'))
      && !shown2('library-zero') && $2('err-toast').hidden);
  await deleteSession('s_kept');
  second.remove();
}
