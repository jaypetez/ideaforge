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
