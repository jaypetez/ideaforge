import {
  archiveSession, createSession, renameSession, setDraftAnswer, setSessionTags,
  setPending, setStatusField, setSynthesis, setTitle,
} from '../../src/core/session.js';
import { seedTurn, submitAnswer } from '../../src/runtime/turn.js';
import { deleteSession, loadSession, saveSession } from '../../src/store/sessions.js';
import { buildBackup } from '../../src/core/backup.js';

const BOOT_MS = 4000;
const settle = (ms = 150) => new Promise((resolve) => setTimeout(resolve, ms));

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
  findButton(draftCard, 'Archive').click();
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
  findButton(doneCard, 'Share .md').click();
  await settle(150);
  check('a completed idea shares a markdown file through the native adapter',
    shared.length === 1 && shared[0].files[0].name.endsWith('.md'),
    shared[0] && shared[0].files[0].name);
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
  Object.defineProperty($('backup-file'), 'files', {
    configurable: true,
    value: transfer.files,
  });
  $('backup-file').dispatchEvent(new win.Event('change'));
  await settle(300);
  check('a JSON backup imports through the library file control',
    Boolean(await loadSession('s_imported')));

  frame.style.width = '390px';
  await settle(100);
  check('the library has no horizontal overflow at a phone width',
    doc.documentElement.scrollWidth <= doc.documentElement.clientWidth,
    `${doc.documentElement.scrollWidth}/${doc.documentElement.clientWidth}`);

  const synthesisCard = $('library-rows').querySelector('[data-session-id="s_synthesis"]');
  findButton(synthesisCard, 'Continue').click();
  await settle(300);
  const recoveredSynthesis = await loadSession('s_synthesis');
  check('an interrupted synthesis resumes through the write-up path',
    rendered('panel-done') && recoveredSynthesis.pending === null
      && recoveredSynthesis.status === 'done');

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
  win.confirm = () => true;
  const removable = $('library-rows').querySelector('[data-session-id="s_six"]');
  findButton(removable, 'Delete').click();
  await settle(250);
  check('permanent delete removes the IndexedDB record', (await loadSession('s_six')) === null);

  for (const session of sessions) await deleteSession(session.id);
  await deleteSession('s_imported');
  frame.remove();
}
