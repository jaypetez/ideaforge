// Pure helpers for the on-device ideas library.

import { coveragePercent } from './markdown.js';
import { sessionDisplayTitle } from './session.js';

const text = (value) => typeof value === 'string' ? value : '';
const normalize = (value) => text(value).toLowerCase().replace(/\s+/g, ' ').trim();

export function sessionLibraryStatus(session) {
  if (session.archivedAt) return 'archived';
  return session.status === 'done' ? 'completed' : 'active';
}

export function sessionSearchText(session) {
  const turns = Array.isArray(session.turns) ? session.turns : [];
  const facts = Array.isArray(session.facts) ? session.facts : [];
  const tags = Array.isArray(session.tags) ? session.tags : [];
  return normalize([
    sessionDisplayTitle(session, ''),
    session.name,
    session.title,
    session.opening,
    session.draftAnswer,
    ...tags,
    ...turns.flatMap((turn) => [turn.question, turn.answer]),
    ...facts.map((fact) => fact.fact),
    session.synthesis && session.synthesis.text,
  ].map(text).join(' '));
}

export function libraryCard(session) {
  const turns = Array.isArray(session.turns) ? session.turns : [];
  return {
    id: session.id,
    title: sessionDisplayTitle(session),
    status: sessionLibraryStatus(session),
    updatedAt: session.updatedAt || 0,
    questionCount: turns.filter((turn) => turn.answer || turn.skipped).length,
    coverage: coveragePercent(session),
    tags: Array.isArray(session.tags) ? session.tags : [],
    canExport: turns.some((turn) => turn.answer || turn.skipped),
  };
}

export function availableTags(sessions) {
  const out = new Map();
  for (const session of sessions || []) {
    for (const tag of session.tags || []) {
      const key = normalize(tag);
      if (key && !out.has(key)) out.set(key, tag);
    }
  }
  return [...out.values()].sort((a, b) => a.localeCompare(b));
}

export function filterSessions(sessions, {
  query = '',
  status = 'active',
  tag = '',
} = {}) {
  const needle = normalize(query);
  const tagNeedle = normalize(tag);
  return [...(sessions || [])]
    .filter((session) => status === 'all' || sessionLibraryStatus(session) === status)
    .filter((session) => !tagNeedle
      || (session.tags || []).some((value) => normalize(value) === tagNeedle))
    .filter((session) => !needle || sessionSearchText(session).includes(needle))
    .sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0));
}
