import test from 'node:test';
import assert from 'node:assert/strict';

import { DIMENSIONS } from '../src/core/dimensions.js';
import {
  createSession, askQuestion, answerQuestion, setPending, setSynthesis, waiveDimension,
} from '../src/core/session.js';
import { SEED_QUESTION } from '../src/core/dimensions.js';
import {
  buildSynthesisPrompt, buildSynthesisPromptParts, parseSynthesisResult,
  MAX_ASSUMPTIONS, MAX_TITLE_WORDS,
} from '../src/core/synthesis.js';
import { buildExport } from '../src/core/markdown.js';
import { runSynthesis, resumeSynthesis } from '../src/runtime/synthesize.js';

function opened(text = 'a tool that interviews you about an idea') {
  let s = createSession({ id: 's_syn', now: 1 });
  s = askQuestion(s, { question: SEED_QUESTION, dimension: 'outcome', source: 'seed', now: 1 });
  return answerQuestion(s, { text, now: 2 });
}

// ───────────────────────────────────────────────────────────── the prompt
test('the synthesis prompt is deterministic and leaks no timestamp', () => {
  const a = opened();
  const b = JSON.parse(JSON.stringify(a));
  b.createdAt = 999999; b.updatedAt = 123456; b.rev = 77;
  b.turns = b.turns.map((t) => ({ ...t, askedAt: 5555, answeredAt: 6666 }));
  assert.equal(buildSynthesisPrompt(a), buildSynthesisPrompt(b));
});

test('buildSynthesisPrompt is exactly the three parts joined', () => {
  const s = opened();
  const { system, prefix, tail } = buildSynthesisPromptParts(s);
  assert.equal(buildSynthesisPrompt(s), [system, prefix, tail].join('\n'));
});

test('every section heading the export uses is named in the prompt', () => {
  const p = buildSynthesisPrompt(opened());
  for (const d of DIMENSIONS) assert.ok(p.includes(d.section), `missing section: ${d.section}`);
});

test('the cacheable system block carries no session content', () => {
  const s = opened('a wildly distinctive opening statement');
  const { system, prefix } = buildSynthesisPromptParts(s);
  assert.ok(!system.includes('wildly distinctive'));
  assert.ok(prefix.includes('wildly distinctive'));
});

test('a waived dimension is reported as waived rather than as a gap', () => {
  const s = waiveDimension(opened(), 'voice', 'tone does not matter here');
  assert.match(buildSynthesisPrompt(s), /Voice and form \| waived/);
});

// ──────────────────────────────────────────────── never trust the model
test('parseSynthesisResult survives every malformed shape', () => {
  for (const bad of [null, undefined, 'a string', 42, [], {}, { prompt: '   ' }]) {
    const r = parseSynthesisResult(bad);
    assert.equal(r.ok, false);
    assert.deepEqual(r.assumptions, []);
    assert.deepEqual(r.openQuestions, []);
  }
});

test('an over-long title is clipped and its trailing punctuation dropped', () => {
  const r = parseSynthesisResult({
    prompt: '## Task\nWrite it.',
    title: 'One Two Three Four Five Six Seven Eight Nine Ten.',
  });
  assert.equal(r.title.split(/\s+/).length, MAX_TITLE_WORDS);
  assert.ok(!/[.!?]$/.test(r.title));
  assert.ok(r.warnings.includes('title truncated'));
});

test('assumptions are capped and non-strings dropped', () => {
  const r = parseSynthesisResult({
    prompt: 'x',
    assumptions: ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 42, null],
  });
  assert.equal(r.assumptions.length, MAX_ASSUMPTIONS);
  assert.ok(r.assumptions.every((a) => typeof a === 'string'));
});

test('an unknown dimension on an open question becomes null rather than propagating', () => {
  const r = parseSynthesisResult({
    prompt: 'x',
    open_questions: [
      { dimension: 'not_a_dimension', question: 'How long?', why_it_matters: 'length' },
      { dimension: 'voice', question: 'How formal?' },
      { question: '' },
    ],
  });
  assert.equal(r.openQuestions.length, 2);
  assert.equal(r.openQuestions[0].dimension, null);
  assert.equal(r.openQuestions[1].dimension, 'voice');
  assert.equal(r.openQuestions[1].why_it_matters, null);
});

// ──────────────────────────────────── the parser and the export must agree
test('a parsed synthesis renders through buildExport with its open questions intact', () => {
  const parsed = parseSynthesisResult({
    title: 'Board memo generator',
    prompt: '## Task\nWrite a two-page memo.',
    assumptions: ['assumed the 2-page limit excludes the appendix'],
    open_questions: [{ dimension: 'voice', question: 'How formal?', why_it_matters: 'sets the register' }],
  });
  assert.equal(parsed.ok, true);

  const s = setSynthesis(opened(), {
    text: parsed.text, tier: 'fake', assumptions: parsed.assumptions,
    openQuestions: parsed.openQuestions, now: 5,
  });
  const md = buildExport(s);

  assert.match(md, /Write a two-page memo/);
  assert.match(md, /assumed the 2-page limit excludes the appendix/);
  assert.match(md, /\*\*Tone & form\*\* — How formal\?/);
  assert.match(md, /_Why it matters: sets the register_/);
  assert.equal(s.status, 'done');
});

// ─────────────────────────────────────────────── runtime orchestration
test('runSynthesis stores a successful result and preserves an existing title', async () => {
  const seen = [];
  const provider = {
    async sampleJson(parts, opts) {
      seen.push({ parts, opts });
      return {
        json: {
          title: 'A replacement title',
          prompt: '## Task\nWrite the refined result.',
          assumptions: ['the reader has five minutes'],
          open_questions: [{ dimension: 'voice', question: 'How formal?' }],
        },
        modelTierApplied: 'applied-tier',
      };
    },
  };
  const session = { ...opened(), title: 'Keep this title' };

  const out = await runSynthesis(session, { provider, now: 20, modelTier: 'complex' });

  assert.equal(out.ok, true);
  assert.equal(out.calls, 1);
  assert.equal(out.session.status, 'done');
  assert.equal(out.session.pending, null);
  assert.equal(out.session.title, 'Keep this title');
  assert.equal(out.session.synthesis.text, '## Task\nWrite the refined result.');
  assert.equal(out.session.synthesis.tier, 'applied-tier');
  assert.deepEqual(out.session.synthesis.assumptions, ['the reader has five minutes']);
  assert.equal(out.session.openQuestions[0].question, 'How formal?');
  assert.equal(seen.length, 1);
  assert.equal(seen[0].opts.cache, true);
});

test('runSynthesis degrades cleanly without a provider or usable model output', async () => {
  const session = opened();
  const local = await runSynthesis(session);
  assert.equal(local.session, session);
  assert.equal(local.ok, false);
  assert.equal(local.calls, 0);
  assert.match(local.warnings[0], /no provider/);

  const unusable = await runSynthesis(session, {
    provider: { async sampleJson() { return { json: { title: 'No prompt' } }; } },
    now: 30,
  });
  assert.equal(unusable.ok, false);
  assert.equal(unusable.calls, 1);
  assert.equal(unusable.session.status, session.status);
  assert.equal(unusable.session.pending, null);
  assert.ok(unusable.warnings.some((warning) => /no usable prompt/.test(warning)));
});

test('runSynthesis restores status and pending state after errors and aborts', async () => {
  const session = opened();
  const failure = new Error('provider unavailable');
  const failed = await runSynthesis(session, {
    provider: { async sampleJson() { throw failure; } },
    now: 40,
  });
  assert.equal(failed.ok, false);
  assert.equal(failed.error, failure);
  assert.equal(failed.aborted, false);
  assert.equal(failed.session.status, session.status);
  assert.equal(failed.session.pending, null);

  const abortedError = Object.assign(new Error('cancelled'), { code: 'aborted' });
  const aborted = await runSynthesis(session, {
    provider: { async sampleJson() { throw abortedError; } },
    now: 41,
  });
  assert.equal(aborted.ok, false);
  assert.equal(aborted.error, null);
  assert.equal(aborted.aborted, true);
  assert.equal(aborted.session.status, session.status);
  assert.equal(aborted.session.pending, null);
});

test('resumeSynthesis reruns only an interrupted synthesis', async () => {
  let calls = 0;
  const provider = {
    async sampleJson() {
      calls++;
      return { json: { prompt: '## Task\nResume the wrap-up.' } };
    },
  };
  const session = opened();
  const idle = await resumeSynthesis(session, { provider, now: 50 });
  assert.equal(idle.session, session);
  assert.equal(idle.calls, 0);
  assert.equal(calls, 0);

  const pending = setPending(session, {
    kind: 'synthesis', promptHash: 'old-hash', startedAt: 49,
  }, 49);
  const resumed = await resumeSynthesis(pending, { provider, now: 50 });
  assert.equal(resumed.ok, true);
  assert.equal(resumed.calls, 1);
  assert.equal(calls, 1);
  assert.equal(resumed.session.pending, null);
  assert.equal(resumed.session.synthesis.text, '## Task\nResume the wrap-up.');
});
