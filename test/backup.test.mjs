import test from 'node:test';
import assert from 'node:assert/strict';

import {
  BACKUP_FORMAT, BACKUP_VERSION, backupFilename, buildBackup, parseBackup, sessionsEqual,
  MAX_BACKUP_BYTES, MAX_BACKUP_SESSIONS, mergeImported,
} from '../src/core/backup.js';
import { createSession, renameSession, setTitle, MAX_SESSION_NAME } from '../src/core/session.js';
import { readFileSync } from 'node:fs';
import { ANDROID_FIXTURE, WEB_FIXTURE, buildWebBackupFixture } from '../tools/backup-fixtures.mjs';

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

test('a collision with a stored row this build cannot read becomes a copy, never a write to that id', () => {
  // listSessions skips newer-schema rows, so the store passes every stored id as reserved.
  const incoming = renameSession(createSession({ id: 's_future', now: 1 }), 'Imported', 2);
  const same = createSession({ id: 's_same', now: 1 });
  const { writes, summary } = mergeImported([same], [incoming, same], {
    newId: idsFrom('s_new'), now: 9, reservedIds: ['s_future', 's_same', 's_unrelated'],
  });
  assert.deepEqual(summary, { imported: 0, copied: 1, duplicates: 1 });
  assert.deepEqual(writes.map((s) => [s.id, s.name]), [['s_new', 'Imported (imported)']]);
});

// ── Interop with the Android app (issue #87) ────────────────────────────────────────────
// Each direction is pinned by a checked-in file the other platform reads; see
// tools/backup-fixtures.mjs. Neither file is edited by hand.

test('the web backup fixture is exactly what the real buildBackup writes today', async () => {
  // android/app's BackupViewModelTest imports this file. If the JS changes what it writes,
  // regenerate it (node tools/backup-fixtures.mjs) so the phone is tested against the
  // backup a browser would really hand it, not last month's.
  assert.equal(readFileSync(WEB_FIXTURE, 'utf8'), await buildWebBackupFixture(),
    'test/fixtures/backup-from-web.json is stale; run: node tools/backup-fixtures.mjs');
});

test('the web backup fixture imports with the summary the Android test asserts', () => {
  // The same library the Kotlin test builds: an exact copy of the archived idea, and a
  // different idea under the draft's id. Both platforms must reach the same counts.
  const parsed = parseBackup(readFileSync(WEB_FIXTURE, 'utf8'));
  const byId = Object.fromEntries(parsed.sessions.map((s) => [s.id, s]));
  const mine = renameSession(byId.s_web_draft, 'My own draft', 1);
  const { writes, summary } = mergeImported([byId.s_web_archived, mine], parsed.sessions, {
    newId: idsFrom('s_copy'), now: 2, reservedIds: ['s_web_archived', 's_web_draft'],
  });
  assert.equal(parsed.skipped, 1);
  assert.equal(byId.s_web_draft.contentReports[0].note, 'Neutral web report note.');
  assert.deepEqual(summary, { imported: 1, copied: 1, duplicates: 1 });
  assert.deepEqual(writes.map((s) => [s.id, s.name]),
    [['s_web_remember_names', ''], ['s_copy', 'Names at meetups — draft (imported)']]);
});

test('a backup the Android app exported imports through the web path', () => {
  const text = readFileSync(ANDROID_FIXTURE, 'utf8');
  const parsed = parseBackup(text);

  assert.equal(parsed.skipped, 0);
  // Newest first, as listSessions orders an export.
  assert.deepEqual(parsed.sessions.map((s) => s.id),
    ['s_android_reading', 's_android_bike_log', 's_android_cafe_rota']);
  // Byte for byte the file the web would have written for the same library.
  assert.equal(buildBackup(parsed.sessions, { now: JSON.parse(text).exportedAt }), text);
  assert.doesNotMatch(text, /apiKey|keyring|wrapping-key|ideaforge\.prefs/);

  const [reading, bike, cafe] = parsed.sessions;
  assert.equal(cafe.name, 'Café shift swaps ☕');
  assert.deepEqual(cafe.tags, ['work', 'rota']);
  assert.ok(bike.archivedAt > 0);
  assert.match(reading.turns[0].answer, /"what did I learn" note\.$/);
  assert.equal(reading.turns[0].classification, 'substantive');
  assert.equal(reading.contentReports[0].note, 'Neutral Android report note.');
  assert.equal(reading.contentReports[0].output.question, reading.turns[1].question);

  // Into an empty browser library: everything imports as it is, no id minted.
  const fresh = mergeImported([], parsed.sessions, { newId: idsFrom() });
  assert.deepEqual(fresh.summary, { imported: 3, copied: 0, duplicates: 0 });

  // Into a library that already holds one idea as is and an edited version of another.
  const edited = renameSession(bike, 'Edited in the browser', 5);
  const again = mergeImported([cafe, edited], parsed.sessions, {
    newId: idsFrom('s_copy'), now: 6, reservedIds: [cafe.id, bike.id],
  });
  assert.deepEqual(again.summary, { imported: 1, copied: 1, duplicates: 1 });
  assert.ok(again.writes.every((s) => s.id !== bike.id && s.id !== cafe.id));
  assert.deepEqual(again.writes.map((s) => s.id), [reading.id, 's_copy']);
  assert.ok(again.writes[1].name.startsWith('A log of every repair'));
  assert.ok(again.writes[1].name.endsWith(' (imported)'));
});
