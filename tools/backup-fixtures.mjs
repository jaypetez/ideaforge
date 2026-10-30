// The web half of the backup interop fixtures (issue #87).
//
//   node tools/backup-fixtures.mjs           rewrite test/fixtures/backup-from-web.json
//   node tools/backup-fixtures.mjs --check   exit 1 if the file on disk is stale
//
// A backup made in the browser has to import on a phone, and one made on a phone has to
// import in the browser. Each direction is pinned by a file the OTHER side reads:
//
//   test/fixtures/backup-from-web.json      written here, by the real buildBackup over
//                                           sessions from a real (scripted) interview;
//                                           android/app imports it through BackupViewModel.
//   test/fixtures/backup-from-android.json  written by android/app's BackupInteropTest
//                                           through the real export path; test/backup.test.mjs
//                                           imports it through parseBackup + mergeImported.
//
// Neither file is edited by hand. This one is regenerated here and compared by
// test/backup.test.mjs, so a change to the JS that alters the bytes fails until it is rerun
// (and the Android import test then says whether the phone still agrees). Everything is
// deterministic: ids and clocks are fixed, and the model is the README walkthrough's script.

import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { buildBackup } from '../src/core/backup.js';
import {
  archiveSession, createSession, renameSession, setSessionTags,
  addContentReport, reportableOutput,
} from '../src/core/session.js';
import { seedTurn, submitAnswer, runTurn } from '../src/runtime/turn.js';
import { runSynthesis } from '../src/runtime/synthesize.js';
import { ANSWERS, TURNS, SYNTHESIS } from './fixtures/walkthrough.mjs';

export const ROOT = fileURLToPath(new URL('..', import.meta.url));
export const WEB_FIXTURE = join(ROOT, 'test', 'fixtures', 'backup-from-web.json');
export const ANDROID_FIXTURE = join(ROOT, 'test', 'fixtures', 'backup-from-android.json');

const T0 = 1_760_000_000_000;
const tick = (n) => T0 + n * 1000;

const turnNumberOf = (parts) => Number((/Turn number: (\d+)/.exec(parts.tail) || [])[1]);

/** The README walkthrough through the real runtime, stopping after `answers` answers. */
async function interview(id, answers, { synthesize = false } = {}) {
  const provider = {
    async sampleJson(parts) {
      const json = TURNS[turnNumberOf(parts)];
      if (!json) throw Object.assign(new Error('scripted bad_response'), { code: 'bad_response' });
      return { json, text: JSON.stringify(json), modelTierApplied: null };
    },
  };
  let s = seedTurn(createSession({ id, now: tick(0), device: 'desktop' }), { now: tick(0) });
  for (let n = 1; n <= answers; n++) {
    s = submitAnswer(s, { text: ANSWERS[n - 1], now: tick(n * 10) });
    if (n === answers) break;
    s = (await runTurn(s, { provider, now: tick(n * 10 + 5) })).session;
  }
  if (synthesize) {
    const synth = { async sampleJson() { return { json: SYNTHESIS, text: JSON.stringify(SYNTHESIS), modelTierApplied: 'complex-model' }; } };
    s = (await runSynthesis(s, { provider: synth, now: tick(999) })).session;
  }
  return s;
}

/**
 * Four rows, as a browser library would hold them: a finished, tagged idea; a renamed draft
 * part way through; an archived one-answer idea; and a row a NEWER web build wrote, which
 * this schema cannot read and every importer must skip and count.
 */
export async function webBackupSessions() {
  const finished = setSessionTags(
    await interview('s_web_remember_names', ANSWERS.length, { synthesize: true }),
    ['memory', 'mobile'], tick(1000),
  );
  const draft0 = renameSession(await interview('s_web_draft', 3), 'Names at meetups — draft', tick(1001));
  const draft = addContentReport(draft0, { id: 'r_web_backup',
    output: reportableOutput(draft0, { kind: 'question', turnId: 't2' }),
    category: 'other', note: 'Neutral web report note.', now: tick(1002) });
  const archived = archiveSession(
    submitAnswer(
      seedTurn(createSession({ id: 's_web_archived', now: tick(2000), device: 'phone' }), { now: tick(2000) }),
      { text: 'A shared grocery list that knows what is already in the fridge, so we stop buying a third jar of mustard.', now: tick(2010) },
    ),
    tick(2020),
  );
  const future = { ...createSession({ id: 's_web_from_the_future', now: tick(3000) }), schema: 99 };
  return [finished, draft, archived, future];
}

export async function buildWebBackupFixture() {
  return buildBackup(await webBackupSessions(), { now: tick(5000) });
}

const isMain = process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1];
if (isMain) {
  const text = await buildWebBackupFixture();
  if (process.argv.includes('--check')) {
    let current = '';
    try { current = readFileSync(WEB_FIXTURE, 'utf8'); } catch { /* missing is stale */ }
    if (current !== text) {
      console.error(`${WEB_FIXTURE} is stale; run: node tools/backup-fixtures.mjs`);
      process.exit(1);
    }
    console.log('backup fixture is current');
  } else {
    mkdirSync(dirname(WEB_FIXTURE), { recursive: true });
    writeFileSync(WEB_FIXTURE, text);
    console.log(`wrote ${WEB_FIXTURE}`);
  }
}
