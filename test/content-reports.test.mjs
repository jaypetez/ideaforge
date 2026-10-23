import test from 'node:test';
import assert from 'node:assert/strict';
import {
  createSession, askQuestion, addContentReport, reportableOutput, replaceReportedQuestion, setDraftText,
  mergeContentReports, migrate, answerQuestion, setDraftAnswer, setSynthesis,
  applyCoverage,
  setStatusField,
} from '../src/core/session.js';
import { seedTurn, submitAnswer, runTurn } from '../src/runtime/turn.js';
import { runSynthesis } from '../src/runtime/synthesize.js';
import { buildTurnPrompt, selectNextDimension, legalMoves } from '../src/core/engine.js';
import { buildSynthesisPrompt } from '../src/core/synthesis.js';
import { buildExport } from '../src/core/markdown.js';
import { buildBackup, parseBackup } from '../src/core/backup.js';
import { getDimension } from '../src/core/dimensions.js';

const question = () => askQuestion(createSession({ id: 's_report', now: 1 }), {
  question: 'Neutral generated question marker?', dimension: 'outcome',
  bridge: 'Neutral generated bridge.', chips: ['Neutral suggestion A', 'Neutral suggestion B'], now: 2,
});
const report = (s, kind = 'question', id = 'r_1') => addContentReport(s, {
  id, output: reportableOutput(s, { kind, turnId: 't1' }),
  category: 'inaccurate', note: 'Private neutral note marker.', now: 3,
});

test('report payload is an explicit generated-output snapshot, not an idea, credential or answer dump', () => {
  const s = question();
  const output = reportableOutput(s, { kind: 'suggestions', turnId: 't1' });
  const next = addContentReport(s, {
    id: 'r_safe', output: { ...output, apiKey: 'never-copy-this' }, category: 'other',
    note: '  Neutral optional note.  ', now: 10, credentials: { apiKey: 'never-copy-this' },
  });
  assert.deepEqual(next.contentReports, [{
    id: 'r_safe', at: 10, category: 'other', note: 'Neutral optional note.',
    output: { kind: 'suggestions', turnId: 't1', question: s.turns[0].question,
      text: 'Neutral suggestion A\nNeutral suggestion B' },
  }]);
  assert.equal(s.contentReports, undefined, 'reducers never mutate the input');
  assert.doesNotMatch(JSON.stringify(next.contentReports), /never-copy-this|credentials|answerSource/);
});

test('hardcoded, checklist, replacement and absent content never acquire AI labels or report targets', () => {
  const seed = seedTurn(createSession({ id: 'seed' }));
  assert.equal(reportableOutput(seed, { kind: 'question', turnId: 't1' }), null);
  assert.equal(reportableOutput(seed, { kind: 'result' }), null);
  for (const source of ['bank', 'replacement', undefined]) {
    const s = question();
    s.turns[0].questionSource = source;
    assert.equal(reportableOutput(s, { kind: 'question', turnId: 't1' }), null);
    assert.equal(reportableOutput(s, { kind: 'suggestions', turnId: 't1' }), null);
  }
  assert.equal(reportableOutput(question(), { kind: 'question', turnId: 't99' }), null);
});

test('report saving is idempotent and rejects stale targets, invalid categories, notes and hostile backup shape', () => {
  const s = question();
  const saved = report(s);
  const again = addContentReport(saved, {
    id: 'r_1', output: saved.contentReports[0].output,
    category: 'inaccurate', note: 'Private neutral note marker.', now: 3,
  });
  assert.equal(again, saved, 'retrying the same durable report must not duplicate it');
  for (const patch of [{ category: 'unknown' }, { note: 'x'.repeat(2001) },
    { output: { kind: 'question', turnId: 't1', question: 'other', text: 'other' } }]) {
    assert.throws(() => addContentReport(s, {
      id: 'r_bad', output: reportableOutput(s, { kind: 'question', turnId: 't1' }),
      category: 'other', note: '', now: 3, ...patch,
    }));
  }
  const hostile = migrate({ ...saved, contentReports: [
    ...saved.contentReports, null, [], { id: 'bad', category: 'other', note: 42, output: [] },
    { ...saved.contentReports[0], id: 'overflow', at: 9e15 },
  ] });
  assert.deepEqual(hostile.contentReports, saved.contentReports);
});

test('a reported current unanswered question is replaced without any answer, turn, coverage or draft change', () => {
  const original = setDraftAnswer(question(), 'My unfinished neutral draft.', 2);
  const saved = report(original);
  const next = replaceReportedQuestion(saved, { reportId: 'r_1', now: 4 });
  assert.notEqual(next.turns[0].question, saved.turns[0].question);
  assert.equal(next.turns[0].questionSource, 'replacement');
  assert.deepEqual(next.turns[0].chips, []);
  assert.equal(next.turns[0].bridge, null);
  assert.equal(next.turns[0].answer, '');
  assert.equal(next.turns[0].answerSource, null);
  assert.equal(next.turns[0].skipped, false);
  assert.equal(next.turns[0].answeredAt, 0);
  for (const key of ['coverage', 'facts', 'zeroGainStreak', 'moveHistory', 'opening', 'draftAnswer']) {
    assert.deepEqual(next[key], saved[key], key);
  }
  assert.equal(next.turns.length, saved.turns.length);
  assert.equal(next.turns[0].id, 't1');
  assert.equal(next.turns[0].n, 1);
  assert.deepEqual(next.contentReports, saved.contentReports, 'the removed output stays in its report');
  assert.throws(() => replaceReportedQuestion(next, { reportId: 'r_1' }), /current unanswered/);
});

test('replacement refuses answered, pending, wrong-output and exhausted cases without fake success', () => {
  const saved = report(question());
  assert.throws(() => replaceReportedQuestion(answerQuestion(saved, { text: 'A neutral answer.' }),
    { reportId: 'r_1' }), /current unanswered/);
  assert.throws(() => replaceReportedQuestion({ ...saved, pending: { kind: 'turn' } },
    { reportId: 'r_1' }), /current unanswered/);
  assert.throws(() => replaceReportedQuestion(saved, { reportId: 'not-saved' }), /current unanswered/);
  const exhausted = question();
  for (const text of getDimension('outcome').bank) {
    exhausted.turns.unshift({ id: `past-${exhausted.turns.length}`, question: text, answer: 'neutral' });
  }
  const withReport = report(exhausted);
  assert.throws(() => replaceReportedQuestion(withReport, { reportId: 'r_1' }), /No different checklist question/);
  assert.equal(withReport.turns.at(-1).question, 'Neutral generated question marker?');
});

test('private report metadata never changes either model prompt and survives export, migration and backup', () => {
  const s = question();
  const target = selectNextDimension(s);
  const opts = { target, moves: legalMoves(s, target) };
  const saved = report(s);
  assert.equal(buildTurnPrompt(saved, opts), buildTurnPrompt(s, opts));
  assert.equal(buildSynthesisPrompt(saved), buildSynthesisPrompt(s));
  assert.doesNotMatch(buildSynthesisPrompt(saved), /Private neutral note marker/);
  assert.deepEqual(parseBackup(buildBackup([saved])).sessions[0].contentReports, saved.contentReports);
  assert.match(buildExport(saved), /## Content reports \(saved locally\)/);
  assert.match(buildExport(saved), /> Private neutral note marker\./);
  assert.match(buildExport(saved), /not automatically sent/);
  const without = { ...s, contentReports: [] };
  assert.equal(buildExport(without), buildExport(s), 'existing no-report exports stay byte-identical');
  const merged = mergeContentReports(s, saved, 5);
  assert.deepEqual(merged.contentReports, saved.contentReports, 'a stale writer keeps saved reports');
  assert.equal(mergeContentReports(saved, saved, 5), saved);
});

test('the document report contains generated parts but no raw user answers or local coverage table', () => {
  const s = answerQuestion(question(), { text: 'PRIVATE_RAW_ANSWER_MARKER' });
  const written = setSynthesis(applyCoverage(s, { outcome: { level: 'partial', gap: 'Neutral generated gap marker.' } }), {
    text: 'Neutral generated prompt marker.', assumptions: ['Neutral assumption.'],
    openQuestions: [{ question: 'Neutral open question?', why_it_matters: 'Neutral reason.' }], now: 6,
  });

  const output = reportableOutput(written, { kind: 'result' });
  assert.match(output.text, /Neutral generated prompt marker/);
  assert.match(output.text, /Neutral generated question marker/);
  assert.match(output.text, /Neutral suggestion A/);
  assert.match(output.text, /Neutral assumption/);
  assert.match(output.text, /Neutral open question/);
  assert.match(output.text, /Neutral generated gap marker/);
  assert.doesNotMatch(output.text, /PRIVATE_RAW_ANSWER_MARKER|turnsSpent|Coverage/);
});

test('an imported unedited generated draft remains reportable without labelling its static question as AI', () => {
  const seed = seedTurn(createSession({ id: 's_imported_draft' }));
  const drafted = setDraftText(seed, 'Neutral generated draft marker.', 2);
  const answered = answerQuestion(drafted, { text: drafted.draftAnswer, source: 'draft', now: 3 });
  assert.equal(reportableOutput(answered, { kind: 'question', turnId: 't1' }), null);
  assert.equal(reportableOutput(answered, { kind: 'suggestions', turnId: 't1' }).text, drafted.draftAnswer);
  assert.equal(reportableOutput(answered, { kind: 'result' }).text, drafted.draftAnswer);
});

test('generated gaps remain reportable after the only model question is replaced and synthesis fails', async () => {
  let s = submitAnswer(seedTurn(createSession({ id: 's_gap_only' })),
    { text: 'A neutral test idea with enough initial detail.' });
  s = (await runTurn(s, { provider: { async sampleJson() { return { json: {
    question: 'Which neutral detail matters most for the initial example?', dimension: 'outcome',
    move: 'concretize', chips: [], coverage: { outcome: { level: 'partial', gap: 'Neutral generated gap marker.' } },
  } }; } } })).session;
  const turn = s.turns.at(-1);
  s = addContentReport(s, { id: 'r_gap', output: reportableOutput(s, { kind: 'question', turnId: turn.id }),
    category: 'other' });
  s = replaceReportedQuestion(s, { reportId: 'r_gap' });
  s = submitAnswer(s, { text: 'Another neutral user-written answer.' });
  const unavailable = { async sampleJson() { throw Object.assign(new Error('Neutral outage.'), { code: 'network' }); } };
  s = (await runTurn(s, { provider: unavailable })).session;
  s = setStatusField((await runSynthesis(s, { provider: unavailable })).session, 'done');
  assert.ok(s.turns.every((t) => t.questionSource !== 'model'));
  assert.equal(s.synthesis.text, '');
  const output = reportableOutput(s, { kind: 'result' });
  assert.ok(output, 'a generated coverage gap is still visible AI output in Result');
  assert.equal(output.text, 'Neutral generated gap marker.');
  assert.equal(addContentReport(s, { id: 'r_gap_result', output, category: 'inaccurate' }).contentReports.length, 2);
});
