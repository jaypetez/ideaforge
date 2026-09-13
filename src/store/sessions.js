// Session persistence.
//
// runTurn bumps `rev` five or six times per question, so the rule here is: save the
// object the runtime hands back, once, after the turn settles. Saving on every rev change
// is six writes per question for no benefit.

import { SESSIONS, get, put, del, all } from './db.js';
import { migrate } from '../core/session.js';

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
 * Session ids are generated here rather than in core — createSession refuses to invent
 * one precisely so that no randomness reaches the pure layer.
 */
export function newSessionId() {
  const rand = crypto.randomUUID ? crypto.randomUUID().slice(0, 8)
    : Math.random().toString(36).slice(2, 10);
  return `s_${Date.now().toString(36)}_${rand}`;
}
