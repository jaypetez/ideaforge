// Session persistence.
//
// runTurn bumps `rev` five or six times per question, so the rule here is: save the
// object the runtime hands back, once, after the turn settles. Saving on every rev change
// is six writes per question for no benefit.

import { SESSIONS, get, put, del, all } from './db.js';
import {
  copySession, MAX_SESSION_NAME, migrate, sessionDisplayTitle,
} from '../core/session.js';
import { sessionsEqual } from '../core/backup.js';

export async function saveSession(session) {
  await put(SESSIONS, session);
  return session;
}

/** @returns {Promise<object|null>} migrated, or null if it was written by a newer build. */
export async function loadSession(id) {
  const raw = await get(SESSIONS, id);
  return raw ? migrate(raw) : null;
}

export const deleteSession = (id) => del(SESSIONS, id);

/** Newest first, for a session list. Rows a newer build wrote are skipped, not guessed at. */
export async function listSessions() {
  const rows = await all(SESSIONS);
  return rows
    .map(migrate)
    .filter(Boolean)
    .sort((a, b) => b.updatedAt - a.updatedAt);
}

/**
 * Import migrated sessions without silently overwriting local work.
 *
 * Exact duplicates are skipped. A conflicting id is retained as a copy so both versions
 * survive and the user can decide which one to keep.
 */
export async function importSessions(sessions, { now = Date.now() } = {}) {
  const local = new Map((await listSessions()).map((session) => [session.id, session]));
  const summary = { imported: 0, copied: 0, duplicates: 0 };

  for (const session of sessions || []) {
    const existing = local.get(session.id);
    if (!existing) {
      await saveSession(session);
      local.set(session.id, session);
      summary.imported++;
      continue;
    }
    if (sessionsEqual(existing, session)) {
      summary.duplicates++;
      continue;
    }

    const id = newSessionId();
    const suffix = ' (imported)';
    const baseName = sessionDisplayTitle(session).slice(0, MAX_SESSION_NAME - suffix.length);
    const copied = copySession(session, {
      id,
      name: `${baseName}${suffix}`,
      now,
    });
    await saveSession(copied);
    local.set(id, copied);
    summary.copied++;
  }

  return summary;
}

/**
 * Session ids are generated here rather than in core — createSession refuses to invent
 * one precisely so that no randomness reaches the pure layer.
 */
export function newSessionId() {
  const rand = crypto.randomUUID ? crypto.randomUUID().slice(0, 8)
    : Math.random().toString(36).slice(2, 10);
  return `s_${Date.now().toString(36)}_${rand}`;
}
