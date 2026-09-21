// Portable, key-free backups for the local ideas library.

import { migrate } from './session.js';

export const BACKUP_FORMAT = 'ideaforge-backup';
export const BACKUP_VERSION = 1;
export const MAX_BACKUP_BYTES = 10 * 1024 * 1024;
export const MAX_BACKUP_SESSIONS = 500;

function utf8Bytes(value) {
  let bytes = 0;
  for (const char of value) {
    const code = char.codePointAt(0);
    bytes += code <= 0x7f ? 1 : code <= 0x7ff ? 2 : code <= 0xffff ? 3 : 4;
  }
  return bytes;
}

function canonical(value) {
  if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`;
  if (value && typeof value === 'object') {
    return `{${Object.keys(value).sort().map((key) =>
      `${JSON.stringify(key)}:${canonical(value[key])}`).join(',')}}`;
  }
  return JSON.stringify(value);
}

export function sessionsEqual(a, b) {
  return canonical(a) === canonical(b);
}

export function backupFilename(now = 0) {
  const date = new Date(now || 0).toISOString().slice(0, 10);
  return `ideaforge-backup-${date}.json`;
}

export function buildBackup(sessions, { now = 0 } = {}) {
  return JSON.stringify({
    format: BACKUP_FORMAT,
    version: BACKUP_VERSION,
    exportedAt: now,
    sessions: Array.isArray(sessions) ? sessions : [],
  }, null, 2) + '\n';
}

export function parseBackup(source, {
  maxBytes = MAX_BACKUP_BYTES,
  maxSessions = MAX_BACKUP_SESSIONS,
} = {}) {
  const raw = typeof source === 'string' ? source : '';
  if (!raw.trim()) throw new Error('The backup file is empty.');
  if (utf8Bytes(raw) > maxBytes) {
    throw new Error(`The backup is larger than ${Math.round(maxBytes / 1024 / 1024)} MB.`);
  }

  let parsed;
  try {
    parsed = JSON.parse(raw);
  } catch {
    throw new Error('The selected file is not valid JSON.');
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new Error('The selected file is not an IdeaForge backup.');
  }
  if (parsed.format !== BACKUP_FORMAT) {
    throw new Error('The selected file is not an IdeaForge backup.');
  }
  if (parsed.version !== BACKUP_VERSION) {
    throw new Error('This backup was written by a newer or unsupported IdeaForge version.');
  }
  if (!Array.isArray(parsed.sessions)) {
    throw new Error('The backup has no session list.');
  }
  if (parsed.sessions.length > maxSessions) {
    throw new Error(`The backup contains more than ${maxSessions} sessions.`);
  }

  const sessions = [];
  let skipped = 0;
  for (const stored of parsed.sessions) {
    if (!stored || typeof stored.id !== 'string' || !stored.id.trim()) {
      skipped++;
      continue;
    }
    const session = migrate(stored);
    if (!session) {
      skipped++;
      continue;
    }
    sessions.push(session);
  }
  return { sessions, skipped };
}
