import {
  archiveSession, createSession, renameSession, setDraftAnswer, setSessionTags,
  setPending, setStatusField, setSynthesis, setTitle,
} from '../../src/core/session.js';
import { seedTurn, submitAnswer } from '../../src/runtime/turn.js';
import { deleteSession, loadSession, saveSession } from '../../src/store/sessions.js';
import { buildBackup } from '../../src/core/backup.js';

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
  if (!menu.matches(':popover-open')) trigger.click();
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

  check('the library starts hidden', !rendered('panel-library'));
  check('the setup screen shows only three recent ideas',
    $('resume-rows').children.length === 3, `${$('resume-rows').children.length} rows`);

  $('b-library').click();
  await settle(300);
  check('the Ideas button opens the library', rendered('panel-library'));
  check('the library does not show one interview’s coverage as a global meter', !rendered('meter'));
  check('the library shows every stored idea',
    $('library-rows').children.length === sessions.length,
    `${$('library-rows').children.length} cards`);

  $('library-search').value = 'unique needle';
  $('library-search').dispatchEvent(new win.Event('input'));
  check('search reads the saved conversation text',
    $('library-rows').children.length === 1
      && $('library-rows').firstElementChild.dataset.sessionId === 's_search',
    `${$('library-rows').children.length} cards / `
      + `${$('library-rows').firstElementChild && $('library-rows').firstElementChild.dataset.sessionId}`);
  $('library-search').value = '';
  $('library-search').dispatchEvent(new win.Event('input'));

  // One primary action, one overflow trigger, one <details>. The primary action follows the
  // same status rule as everywhere else: a finished idea is viewed, any other is continued.
  const cards = [...$('library-rows').querySelectorAll('.idea-card')];
  const primary = (card) => card.querySelector('.idea-actions > button:not(.idea-more)');
  check('every card has exactly one <details>, the rename-and-tag editor',
    cards.length === sessions.length
      && cards.every((card) => card.querySelectorAll('details').length === 1
        && card.querySelector('details').classList.contains('idea-edit')),
    cards.map((card) => card.querySelectorAll('details').length).join(','));
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
  const edit = draftCard.querySelector('details');
  edit.open = true;
  const inputs = edit.querySelectorAll('input');
  inputs[0].value = 'Renamed customer notebook';
  inputs[1].value = 'Research, Mobile';
  findButton(edit, 'Save details').click();
  await settle(250);
  const edited = await loadSession('s_draft');
  check('renaming an idea persists through IndexedDB', edited.name === 'Renamed customer notebook');
  check('tag edits are normalized and persisted',
    edited.tags.join('|') === 'Research|Mobile', edited.tags.join('|'));

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
  const importButton = findButton($('panel-setup'), 'Import backup');
  check('Settings shows Back up ideas and Import backup in its Data group',
    visible(findButton($('panel-setup'), 'Back up ideas')) && visible(importButton)
      && importButton.closest('.group').querySelector('h2').textContent === 'Data');
  let picked = false;
  $('backup-file').addEventListener('click', (event) => { picked = true; event.preventDefault(); },
    { once: true });
  importButton.click();
  check('Import backup in Settings opens the backup file picker', picked);
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
  let asked = 0;
  win.confirm = () => { asked++; return false; };
  let removable = $('library-rows').querySelector('[data-session-id="s_six"]');
  menuButton(removable, 'Delete').click();
  await settle(250);
  check('Delete asks first, and declining keeps the idea',
    asked === 1 && Boolean(await loadSession('s_six')), `asked ${asked}`);
  win.confirm = () => true;
  removable = $('library-rows').querySelector('[data-session-id="s_six"]');
  menuButton(removable, 'Delete').click();
  await settle(250);
  check('permanent delete removes the IndexedDB record', (await loadSession('s_six')) === null);

  // Filters that match nothing are not the same as having no ideas.
  $('library-search').value = 'nothing in any saved idea says this';
  $('library-search').dispatchEvent(new win.Event('input'));
  check('filters matching nothing show the empty-filter message, not the first-run state',
    rendered('library-empty') && !rendered('library-zero')
      && $('library-rows').children.length === 0 && visible($('library-search')));
  $('library-search').value = '';
  $('library-search').dispatchEvent(new win.Event('input'));
  check('…and clearing the filter brings the ideas back',
    !rendered('library-empty') && $('library-rows').children.length > 0);

  // No ideas at all: the first-run state, with a way to start one.
  for (const session of sessions) await deleteSession(session.id);
  await deleteSession('s_imported');
  const busyStates = [];
  const observer = new win.MutationObserver(() => {
    const list = $('library-rows');
    const skeletons = [...list.querySelectorAll('.idea-skeleton')];
    busyStates.push(`${list.getAttribute('aria-busy')}:${skeletons.length}`
      + `:${skeletons.every((el) => el.getAttribute('aria-hidden') === 'true') ? 'hidden' : 'exposed'}`
      + `:${list.querySelectorAll('.idea-card').length}`);
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
      .every((entry) => entry.endsWith(':hidden:0')), busyStates.join(' '));
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

  // A failed first read is not an empty library. Someone with saved ideas whose first open
  // of the library fails must see the error, not "No ideas yet" with the filters gone.
  const kept = activeSession('s_kept', now + 80, 'An idea that is still stored', {
    name: 'Still stored',
  });
  await saveSession(kept);
  const second = await loadFrame('/index.html');
  const win2 = second.contentWindow;
  const $2 = (id) => second.contentDocument.getElementById(id);
  const shown2 = (id) => win2.getComputedStyle($2(id)).display !== 'none';
  await settle(BOOT_MS);
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
  win2.IDBObjectStore.prototype.getAll = getAll;
  $2('b-settings').click();
  await settle(200);
  $2('b-library').click();
  await settle(400);
  check('the next successful read shows the stored ideas',
    Boolean($2('library-rows').querySelector('[data-session-id="s_kept"]')) && !shown2('library-zero'));
  await deleteSession('s_kept');
  second.remove();
}
