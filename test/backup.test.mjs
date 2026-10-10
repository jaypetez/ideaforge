import test from 'node:test';
import assert from 'node:assert/strict';

import {
  BACKUP_FORMAT, BACKUP_VERSION, backupFilename, buildBackup, parseBackup, sessionsEqual,
  MAX_BACKUP_BYTES, MAX_BACKUP_SESSIONS, mergeImported,
} from '../src/core/backup.js';
import { createSession, renameSession, setTitle, MAX_SESSION_NAME } from '../src/core/session.js';

test('a backup round-trips migrated sessions without adding credentials', () => {
  const session = renameSession(createSession({ id: 's_backup', now: 10 }), 'Portable idea', 11);
  const json = buildBackup([session], { now: Date.UTC(2026, 8, 20) });
  const parsed = parseBackup(json);

  assert.match(json, new RegExp(`"format": "${BACKUP_FORMAT}"`));
  assert.match(json, new RegExp(`"version": ${BACKUP_VERSION}`));
  assert.doesNotMatch(json, /apiKey|wrapping-key|ideaforge\.prefs/);
  assert.equal(parsed.skipped, 0);
  assert.equal(parsed.sessions.length, 1);
  assert.equal(parsed.sessions[0].name, 'Portable idea');
  assert.ok(sessionsEqual(parsed.sessions[0], session));
  assert.equal(backupFilename(Date.UTC(2026, 8, 20)), 'ideaforge-backup-2026-09-20.json');
});

test('backup parsing rejects malformed, oversized and future formats', () => {
  assert.throws(() => parseBackup(''), /empty/);
  assert.throws(() => parseBackup('{not json'), /valid JSON/);
  assert.throws(() => parseBackup('{}'), /not an IdeaForge backup/);
  assert.throws(() => parseBackup(JSON.stringify({
    format: BACKUP_FORMAT, version: BACKUP_VERSION + 1, sessions: [],
  })), /newer or unsupported/);
  assert.throws(() => parseBackup(JSON.stringify({
    format: BACKUP_FORMAT, version: BACKUP_VERSION, sessions: [],
  }), { maxBytes: 5 }), /larger than/);
  assert.throws(() => parseBackup(JSON.stringify({
    format: BACKUP_FORMAT, version: BACKUP_VERSION, sessions: [{}, {}],
  }), { maxSessions: 1 }), /more than 1 sessions/);
});

test('backup parsing skips unusable rows instead of inventing identities', () => {
  const good = createSession({ id: 's_good', now: 1 });
  const parsed = parseBackup(JSON.stringify({
    format: BACKUP_FORMAT,
    version: BACKUP_VERSION,
    sessions: [null, {}, { schema: 99, id: 'future' }, good],
  }));

  assert.equal(parsed.sessions.length, 1);
  assert.equal(parsed.sessions[0].id, 's_good');
  assert.equal(parsed.skipped, 3);
});

test('session equality ignores object key insertion order but catches content changes', () => {
  assert.equal(sessionsEqual({ id: 'x', nested: { a: 1, b: 2 } },
    { nested: { b: 2, a: 1 }, id: 'x' }), true);
  assert.equal(sessionsEqual({ id: 'x', value: 1 }, { id: 'x', value: 2 }), false);
});

test('backup limits and shape errors are specific, and the defaults are the documented ones', () => {
  assert.equal(MAX_BACKUP_BYTES, 10 * 1024 * 1024);
  assert.equal(MAX_BACKUP_SESSIONS, 500);
  assert.throws(() => parseBackup('[]'), /not an IdeaForge backup/);
  assert.throws(() => parseBackup('"text"'), /not an IdeaForge backup/);
  assert.throws(() => parseBackup(JSON.stringify({
    format: BACKUP_FORMAT, version: BACKUP_VERSION, sessions: {},
  })), /no session list/);
  assert.throws(() => parseBackup(JSON.stringify({
    format: BACKUP_FORMAT, version: BACKUP_VERSION, sessions: [{ id: 'a' }, { id: 'b' }],
  }), { maxSessions: 1 }), /more than 1 sessions/);
});

test('the size limit counts bytes, not characters', () => {
  // 400 three-byte characters is 1200 bytes but only ~400 characters.
  const json = JSON.stringify({
    format: BACKUP_FORMAT, version: BACKUP_VERSION, sessions: [], pad: '\u20ac'.repeat(400),
  });
  assert.ok(json.length < 1000);
  assert.throws(() => parseBackup(json, { maxBytes: 1000 }), /larger than/);
  assert.doesNotThrow(() => parseBackup(json, { maxBytes: 5000 }));
});

test('buildBackup writes an empty list for non-array input, and the filename tolerates a zero clock', () => {
  assert.deepEqual(JSON.parse(buildBackup(null)).sessions, []);
  assert.deepEqual(JSON.parse(buildBackup({ id: 'x' })).sessions, []);
  assert.equal(backupFilename(0), 'ideaforge-backup-1970-01-01.json');
  assert.equal(backupFilename(), 'ideaforge-backup-1970-01-01.json');
});

test('rows without a usable id are skipped and counted rather than failing the import', () => {
  const good = createSession({ id: 's_ok', now: 1 });
  const parsed = parseBackup(JSON.stringify({
    format: BACKUP_FORMAT,
    version: BACKUP_VERSION,
    sessions: [good, null, { id: '' }, { id: '   ' }, { id: 7 }, { id: 'future', schema: 9999 }],
  }));
  assert.equal(parsed.sessions.length, 1);
  assert.equal(parsed.skipped, 5);
});

const idsFrom = (...ids) => () => {
  if (!ids.length) throw new Error('newId called more often than there were collisions');
  return ids.shift();
};

test('an imported id collision becomes a named copy, and the local row is never written', () => {
  const local = renameSession(createSession({ id: 's_1', now: 1 }), 'Local version', 2);
  const incoming = renameSession(createSession({ id: 's_1', now: 1 }), 'Their version', 3);
  const { writes, summary } = mergeImported([local], [incoming], { newId: idsFrom('s_new'), now: 50 });

  assert.deepEqual(summary, { imported: 0, copied: 1, duplicates: 0 });
  assert.equal(writes.length, 1);
  // Overwriting s_1 would be silent data loss: nothing written may carry the local id.
  assert.ok(writes.every((s) => s.id !== 's_1'));
  assert.equal(writes[0].id, 's_new');
  assert.equal(writes[0].name, 'Their version (imported)');
  assert.equal(writes[0].updatedAt, 50);
  assert.equal(local.name, 'Local version');
});

test('the copy is named from name, then title, then opening, and stays within the name limit', () => {
  const local = createSession({ id: 's_t', now: 1 });
  const titled = setTitle(createSession({ id: 's_t', now: 1 }), 'Model title', 2);
  const long = renameSession(createSession({ id: 's_t', now: 1 }), 'x'.repeat(MAX_SESSION_NAME), 2);
  const { writes } = mergeImported([local], [titled, long], { newId: idsFrom('a', 'b') });
  assert.equal(writes[0].name, 'Model title (imported)');
  assert.equal(writes[1].name.length, MAX_SESSION_NAME);
  assert.ok(writes[1].name.endsWith(' (imported)'));
});

test('an identical session is skipped whatever its key order, and costs no id', () => {
  const local = createSession({ id: 's_same', now: 1 });
  const reordered = Object.fromEntries(Object.entries(structuredClone(local)).reverse());
  const { writes, summary } = mergeImported([local], [reordered], { newId: idsFrom() });
  assert.deepEqual(summary, { imported: 0, copied: 0, duplicates: 1 });
  assert.deepEqual(writes, []);
});

test('new ids import as they are, and a repeat within one import is a duplicate of the first', () => {
  const a = createSession({ id: 's_a', now: 1 });
  const b = createSession({ id: 's_b', now: 1 });
  const { writes, summary } = mergeImported([], [a, b, a], { newId: idsFrom() });
  assert.deepEqual(summary, { imported: 2, copied: 0, duplicates: 1 });
  assert.deepEqual(writes.map((s) => s.id), ['s_a', 's_b']);
  assert.deepEqual(mergeImported(null, null, { newId: idsFrom() }),
    { writes: [], summary: { imported: 0, copied: 0, duplicates: 0 } });
});
