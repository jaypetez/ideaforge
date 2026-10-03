import test from 'node:test';
import assert from 'node:assert/strict';

import {
  BACKUP_FORMAT, BACKUP_VERSION, backupFilename, buildBackup, parseBackup, sessionsEqual,
  MAX_BACKUP_BYTES, MAX_BACKUP_SESSIONS,
} from '../src/core/backup.js';
import { createSession, renameSession } from '../src/core/session.js';

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
