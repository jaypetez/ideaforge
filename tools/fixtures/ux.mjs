import {
  archiveSession, askQuestion, createSession, renameSession, setDraftAnswer,
  setSessionTags, setSynthesis, setTitle,
} from '../../src/core/session.js';
import { seedTurn, submitAnswer } from '../../src/runtime/turn.js';

export const UX_NOW = Date.parse('2026-09-13T09:00:00Z');
export const UX_QUESTION = 'What would make this idea useful on a difficult first day, '
  + 'when the person using it has little time, an unreliable connection, a long list of '
  + 'unfinished work, and needs to understand the next step without losing the notes '
  + 'they already wrote?';
export const UX_ANSWER = 'I want a calm place to keep the names, questions and small details '
  + 'from conversations. It should work with a long answer and leave the original words '
  + 'intact, including a deliberatelyunbrokentoken'.padEnd(260, 'x') + '.';
export const UX_CHIPS = [
  'Help someone find the one next step in a long list without losing their original notes.',
  'Keep the useful details on this device so an unreliable connection does not erase them.',
  'Make the question easy to read with enlarged text and a narrow window.',
];
export const UX_DIMENSION_REASON = 'This area belongs to a later project. Keep the original notes and the current question while I decide what is relevant to this idea.';
export const UX_MARKDOWN = `## Task
Build a calm, local-first notebook. Preserve the user's words.

## First-day workflow
1. Open the current idea.
2. Add the next useful detail.
3. Copy the complete markdown when ready.

| Situation | Recovery |
| --- | --- |
| No connection | Keep the local transcript |
| Save failed | Export before closing |

## Example
\`\`\`js
const note = { title: "A long idea", source: "<script>not executable</script>" };
\`\`\`

## A long token
${'unbrokentoken'.repeat(35)}

## Further detail
${Array.from({ length: 8 }, (_, i) => `### Detail ${i + 1}\n${UX_ANSWER}`).join('\n\n')}
`;

const app = (id, surface, extra = {}) => ({ id, surface, evidence: 'real-app', ...extra });
const phase = (name) => ({
  id: `voice-${name}`, surface: 'voice', evidence: 'isolated-component', phase: name,
});

export const UX_STATES = [
  app('fresh', 'start'),
  app('ready', 'start'),
  app('empty', 'library'),
  app('library', 'library', { dataset: 'mixed' }),
  app('library-archived', 'library', { dataset: 'mixed' }),
  app('library-100', 'library', { dataset: 'hundred' }),
  app('library-long', 'library', { dataset: 'long' }),
  app('filtered', 'library', { dataset: 'mixed' }),
  app('unavailable', 'library'),
  app('interview-long', 'interview', { dataset: 'long', sessionId: 'ux-long-active' }),
  app('result-long', 'result', { dataset: 'long', sessionId: 'ux-long-done' }),
  app('bank', 'interview', { dataset: 'bank', sessionId: 'ux-bank' }),
  app('save-failure', 'interview'),
  app('synthesis-failure', 'result', { dataset: 'long', sessionId: 'ux-long-active' }),
  app('busy-turn', 'interview'),
  app('writing', 'result', { dataset: 'long', sessionId: 'ux-long-active' }),
  ...['ready', 'starting', 'speaking', 'listening', 'transcribing', 'thinking',
    'paused', 'recovering', 'blocked', 'complete'].map(phase),
  { ...phase('writing'), platform: 'android' },
];

function active(id, name, offset, long = false, source = 'model') {
  const now = UX_NOW + offset;
  let session = seedTurn(createSession({ id, now }), { now });
  session = submitAnswer(session, {
    text: long ? UX_ANSWER : 'A local notebook for the names and details from conversations.',
    now: now + 1,
  });
  session = askQuestion(session, {
    question: 'Who would use the notebook on their first day?',
    dimension: 'audience', chips: long ? UX_CHIPS : ['Me after a conference', 'A small team'],
    source, now: now + 2,
  });
  if (long) {
    for (const [index, question] of [
      'What must remain on this device when the connection is unavailable?', UX_QUESTION,
    ].entries()) {
      session = submitAnswer(session, { text: UX_ANSWER, now: now + 3 + index * 2 });
      session = askQuestion(session, {
        question, dimension: 'constraints', chips: UX_CHIPS, source, now: now + 4 + index * 2,
      });
    }
  }
  session = renameSession(session, name, session.updatedAt + 1);
  return setDraftAnswer(session, long ? UX_ANSWER : 'Keep this unfinished draft.', session.updatedAt + 1);
}

function finished(session, long = false) {
  const now = session.updatedAt;
  session = submitAnswer(session, { text: UX_ANSWER, now: now + 1 });
  session = setSynthesis(session, {
    text: long ? UX_MARKDOWN : '## Task\nBuild a local notebook.\n\n## Recovery\nKeep the original notes.',
    assumptions: ['The original notes stay on this device.'],
    openQuestions: [{ dimension: 'constraints', question: 'What is the first useful limit?' }],
    tier: 'scripted-ux', now: now + 2,
  });
  return setTitle(session, 'A calm local notebook', now + 3);
}

function ideas(count, includeArchive) {
  return Array.from({ length: count }, (_, index) => {
    let session = active(`ux-idea-${String(index + 1).padStart(3, '0')}`,
      `Conversation notebook ${index + 1}`, index * 20);
    session = setSessionTags(session, ['research', index % 2 ? 'work' : 'personal'], session.updatedAt + 1);
    if (index % 3 === 1) session = finished(session);
    if (includeArchive && index % 3 === 2) session = archiveSession(session, session.updatedAt + 1);
    return session;
  });
}

/** Data only: no provider, credential, microphone, UI hook or second state store. */
export function buildUxFixtures() {
  let long = active('ux-long-active', 'A deliberately long idea name '.padEnd(120, 'x'), 5000, true);
  long = setSessionTags(long, Array.from({ length: 12 }, (_, i) => `context-and-follow-up-${i + 1}`),
    long.updatedAt + 1);
  return {
    schema: 1, now: UX_NOW, states: UX_STATES,
    text: { question: UX_QUESTION, answer: UX_ANSWER, chips: UX_CHIPS, markdown: UX_MARKDOWN, dimensionReason: UX_DIMENSION_REASON },
    datasets: {
      mixed: ideas(12, true),
      hundred: ideas(100, false),
      long: [long, finished(active('ux-long-done', 'A completed idea with a long document', 5100, true), true)],
      bank: [active('ux-bank', 'The checklist still keeps the interview moving', 5200, false, 'bank')],
    },
  };
}
