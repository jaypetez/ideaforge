import test from 'node:test';
import assert from 'node:assert/strict';

import {
  archiveSession, askQuestion, answerQuestion, createSession, migrate,
  normalizeTags, renameSession, restoreSession, sessionDisplayTitle, setSessionTags,
} from '../src/core/session.js';
import {
  availableTags, filterSessions, libraryCard, sessionLibraryStatus, sessionSearchText,
} from '../src/core/library.js';
import { exportFilename } from '../src/core/markdown.js';

function idea(id, now, opening) {
  let session = createSession({ id, now });
  session = askQuestion(session, {
    question: 'What is the idea?', dimension: 'outcome', now,
  });
  return answerQuestion(session, { text: opening, now: now + 1 });
}

test('library metadata is normalized by reducers and survives migration', () => {
  const base = idea('s_library', 1, 'A field notebook for recurring customer questions.');
  const renamed = renameSession(base, '  Customer   question notebook  ', 3);
  const tagged = setSessionTags(renamed, [
    'Research', ' research ', 'Product discovery', '', 42, 'x'.repeat(80),
  ], 4);
  const archived = archiveSession(tagged, 5);

  assert.equal(renamed.name, 'Customer question notebook');
  assert.deepEqual(tagged.tags, ['Research', 'Product discovery', 'x'.repeat(32)]);
  assert.equal(sessionLibraryStatus(archived), 'archived');
  assert.equal(sessionLibraryStatus(restoreSession(archived, 6)), 'active');

  const old = migrate({
    ...JSON.parse(JSON.stringify(base)),
    schema: 1,
    name: 99,
    tags: ['One', ' one ', null],
    archivedAt: 'yesterday',
  });
  assert.equal(old.schema, 2);
  assert.equal(old.name, '');
  assert.deepEqual(old.tags, ['One']);
  assert.equal(old.archivedAt, 0);
});

test('a user name wins over synthesis title and the opening everywhere', () => {
  const changedAt = Date.UTC(2026, 8, 20);
  const base = idea('s_named', changedAt - 2, 'An opening statement');
  const generated = { ...base, title: 'Generated title' };
  const named = renameSession(generated, 'My own title', changedAt);

  assert.equal(sessionDisplayTitle(base), 'An opening statement');
  assert.equal(sessionDisplayTitle(generated), 'Generated title');
  assert.equal(sessionDisplayTitle(named), 'My own title');
  assert.match(exportFilename(named), /^ideaforge-my-own-title-2026-09-20\.md$/);
});

test('library search covers names, tags, transcript, facts and synthesis', () => {
  const first = {
    ...setSessionTags(renameSession(idea('s_one', 10, 'A conference memory tool'), 'Name recall', 12),
      ['people', 'events'], 13),
    draftAnswer: 'The unfinished draft mentions a paper badge.',
    facts: [{ dimension: 'boundary', fact: 'Must work with no signal' }],
    synthesis: { text: '## Task\nDesign a one-thumb workflow.', tier: '', at: 0, stale: false, assumptions: [] },
  };
  const second = {
    ...idea('s_two', 20, 'A recipe organizer'),
    status: 'done',
    title: 'Kitchen index',
  };
  const archived = archiveSession(idea('s_three', 30, 'A private travel journal'), 31);
  const sessions = [first, second, archived];

  assert.ok(sessionSearchText(first).includes('one-thumb'));
  assert.deepEqual(filterSessions(sessions, { status: 'all', query: 'paper badge' }).map((s) => s.id),
    ['s_one']);
  assert.deepEqual(filterSessions(sessions, { status: 'all', query: 'no signal' }).map((s) => s.id),
    ['s_one']);
  assert.deepEqual(filterSessions(sessions, { status: 'all', tag: 'PEOPLE' }).map((s) => s.id),
    ['s_one']);
  assert.deepEqual(filterSessions(sessions, { status: 'completed' }).map((s) => s.id),
    ['s_two']);
  assert.deepEqual(filterSessions(sessions, { status: 'archived' }).map((s) => s.id),
    ['s_three']);
  assert.deepEqual(availableTags(sessions), ['events', 'people']);
});

test('library cards report answered questions rather than allocated turns', () => {
  let session = idea('s_card', 1, 'An idea');
  session = askQuestion(session, {
    question: 'What is the limit?', dimension: 'boundary', now: 3,
  });
  const card = libraryCard(session);

  assert.equal(card.questionCount, 1);
  assert.equal(card.status, 'active');
  assert.equal(card.canExport, true);
});

test('tag normalization is bounded and case-insensitively deduplicated', () => {
  const tags = normalizeTags(Array.from({ length: 20 }, (_, i) => ` Tag ${i % 13} `));
  assert.equal(tags.length, 12);
  assert.equal(new Set(tags.map((tag) => tag.toLowerCase())).size, tags.length);
});

// ── filters and cards: the edges
test('filterSessions status "all" includes archived ideas and "archived" shows only those', () => {
  const live = idea('s_live', 10, 'live idea');
  const old = archiveSession(idea('s_old', 5, 'old idea'), 6);
  assert.deepEqual(filterSessions([live, old], { status: 'all' }).map((s) => s.id), ['s_live', 's_old']);
  assert.deepEqual(filterSessions([live, old], { status: 'archived' }).map((s) => s.id), ['s_old']);
  assert.deepEqual(filterSessions([live, old]).map((s) => s.id), ['s_live']);
});

test('filterSessions matches tags case-insensitively and exactly, and orders by recency', () => {
  const a = setSessionTags(idea('s_a', 1, 'alpha'), ['Research'], 2);
  const b = setSessionTags(idea('s_b', 5, 'beta'), ['research notes'], 6);
  const c = setSessionTags(idea('s_c', 9, 'gamma'), ['RESEARCH'], 10);
  assert.deepEqual(filterSessions([a, b, c], { tag: ' research ' }).map((s) => s.id), ['s_c', 's_a']);
  const noStamp = { ...a, id: 's_none', updatedAt: undefined };
  assert.equal(filterSessions([noStamp, c]).at(-1).id, 's_none', 'a missing updatedAt sorts last');
});

test('availableTags keeps the first spelling seen and sorts the result', () => {
  const s1 = setSessionTags(idea('s_1', 1, 'x'), ['Zed', 'research'], 2);
  const s2 = setSessionTags(idea('s_2', 3, 'y'), ['RESEARCH', 'alpha'], 4);
  assert.deepEqual(availableTags([s1, s2]), ['alpha', 'research', 'Zed']);
  assert.deepEqual(availableTags(null), []);
});

test('library helpers survive a damaged record', () => {
  const broken = { ...createSession({ id: 's_bad', now: 1 }), turns: 'nope', facts: 3, tags: null, updatedAt: 0 };
  assert.equal(typeof sessionSearchText(broken), 'string');
  const card = libraryCard(broken);
  assert.equal(card.questionCount, 0);
  assert.equal(card.canExport, false);
  assert.deepEqual(card.tags, []);
  assert.equal(card.updatedAt, 0);
});

test('a card can only export once something has been answered or skipped', () => {
  let s = createSession({ id: 's_card', now: 1 });
  s = askQuestion(s, { question: 'q', dimension: 'outcome', now: 1 });
  assert.equal(libraryCard(s).canExport, false);
  assert.equal(libraryCard(answerQuestion(s, { text: 'a', now: 2 })).canExport, true);
});
