// A whole interview driven by voice, against the real app.
//
// Everything below this is covered somewhere cheaper: the matching rules in
// test/driving.test.mjs, the loop's sequencing in test/drive-loop.test.mjs, the dictation
// state machine in dictation.browser.mjs. What none of those can see is whether the real
// app — the real DOM, the real event plumbing between app.js, voice/index.js and
// webspeech.js, the real CSP, the real IndexedDB — actually joins them up.
//
// So this loads index.html in an iframe, replaces the two platform voice objects inside it,
// and then never touches the keyboard again. The model is the artifact provider with a
// scripted `window.claude`, so the whole run makes no network request at all and works in
// CI with no key.
//
// Assertions read IndexedDB rather than the DOM, for the reason validate-local.mjs gives:
// the screen shows what the app is willing to claim, the store shows what it recorded.

import { DIMENSION_IDS } from '../../src/core/dimensions.js';

const BOOT_MS = 4000;

function loadFrame(src) {
  return new Promise((resolve, reject) => {
    const frame = document.createElement('iframe');
    frame.width = 900;
    frame.height = 700;
    frame.style.position = 'absolute';
    frame.style.left = '-10000px';
    frame.onload = () => resolve(frame);
    frame.onerror = () => reject(new Error('iframe failed to load ' + src));
    frame.src = src;
    document.body.append(frame);
  });
}

const settle = (ms) => new Promise((r) => setTimeout(r, ms));

/**
 * Wait for a condition, or give up and return null.
 *
 * Null rather than throwing, so a failure reports as the claim it broke with the state that
 * broke it, instead of as "probe threw" with nothing to go on.
 *
 * The predicate is AWAITED: an async one returns a Promise, which is always truthy, so a
 * missing await makes every wait succeed instantly and every assertion read undefined.
 */
async function until(fn, ms) {
  const deadline = performance.now() + ms;
  for (;;) {
    let v;
    try { v = await fn(); } catch { v = null; }
    if (v) return v;
    if (performance.now() > deadline) return null;
    await settle(50);
  }
}

/** Everything worth knowing when a wait times out. */
function diagnose(fake, app, session) {
  return [
    `spoken=${JSON.stringify(fake.synthesis.spoken.map((u) => u.text))}`,
    `sessions=${fake.recognition.startCount}`,
    `scriptLeft=${fake.recognition.scriptRemaining()}`,
    `answers=${JSON.stringify((session ? session.turns : []).map((t) => t.answer))}`,
    `note=${app.$('note').textContent}`,
    `err=${app.$('err').textContent}`,
  ].join(' | ');
}

/** A model that always answers, so the interview is never the thing under test. */
function scriptedClaude() {
  let n = 0;
  return {
    use: async () => async (prompt) => {
      // The synthesis prompt asks for a different shape; everything else is a turn.
      if (/refined prompt|assumptions/i.test(prompt) && /title/i.test(prompt)) {
        return {
          text: JSON.stringify({
            title: 'Conference Name Recall',
            prompt: '## Task\nBuild a tool that helps recall names.',
            assumptions: ['mobile first'],
            openQuestions: [],
          }),
          modelTierApplied: 'fake',
        };
      }
      n += 1;
      // Coverage has to actually rise. A model that claims nothing leaves zeroGainStreak
      // climbing, and at two the engine offers the wrap-up — correctly — which then eats
      // the next scripted utterance as its yes/no. The first version of this fixture did
      // exactly that and looked like a broken loop.
      const coverage = {};
      for (const id of DIMENSION_IDS) coverage[id] = { level: 'partial', gap: 'needs detail' };
      return {
        text: JSON.stringify({
          question: `Scripted question ${n}?`,
          move: 'concretize',
          chips: [],
          facts: [],
          coverage,
        }),
        modelTierApplied: 'fake',
      };
    },
  };
}

/** Read the most recently updated session straight out of the app's own store. */
function latestSession(win) {
  return new Promise((resolve, reject) => {
    const open = win.indexedDB.open('ideaforge');
    open.onerror = () => reject(open.error);
    open.onsuccess = () => {
      const db = open.result;
      const tx = db.transaction('sessions', 'readonly').objectStore('sessions').getAll();
      tx.onerror = () => reject(tx.error);
      tx.onsuccess = () => {
        const rows = (tx.result || []).slice().sort((a, b) => b.updatedAt - a.updatedAt);
        db.close();
        resolve(rows[0] || null);
      };
    };
  });
}

/** Start an interview in the frame, hands-free, with a scripted voice and model. */
async function startDriving(fake, script) {
  const frame = await loadFrame('/index.html');
  const win = frame.contentWindow;
  const doc = frame.contentDocument;
  await settle(BOOT_MS);

  const violations = [];
  win.addEventListener('securitypolicyviolation',
    (e) => violations.push(e.violatedDirective + ' blocked ' + e.blockedURI));
  const errors = [];
  win.addEventListener('error', (e) => errors.push(String(e.message || e)));

  // Installed into the FRAME's window, not this one. webspeech.js resolves the constructor
  // lazily and speak.js reads speechSynthesis at call time, so neither notices.
  fake.install(win, { script, speakMs: 15 });
  win.claude = scriptedClaude();

  const $ = (id) => doc.getElementById(id);
  $('provider').value = 'artifact';
  $('provider').dispatchEvent(new win.Event('change'));
  $('b-start').click();

  await until(() => !$('panel-interview').hidden, 5000);
  await until(() => !$('handsfree-wrap').hidden, 5000);
  $('handsfree').checked = true;
  $('handsfree').dispatchEvent(new win.Event('change'));

  return { frame, win, doc, $, violations, errors };
}

export default async function run(check) {
  await import('./fixtures/fake-voice.js');
  const fake = window.__FakeVoice;

  // ── it asks, it hears, it advances ───────────────────────────────────────
  {
    const spoken = (text) => [{ at: 40, interim: text }, { at: 160, final: text }];
    const app = await startDriving(fake, [
      spoken('a tool for remembering names over'),
      spoken('forty people and I could name four over'),
      spoken('three seconds with one thumb over'),
    ]);

    const session = await until(async () => {
      const s = await latestSession(app.win);
      return s && s.turns.filter((t) => t.answer).length >= 3 ? s : null;
    }, 25000);
    if (!session) {
      check('three answers are recorded under voice alone', false,
        diagnose(fake, app, await latestSession(app.win)));
      app.frame.remove();
      fake.restore();
      return;
    }

    check('the app read the question out loud before listening',
      fake.synthesis.spoken.length > 0 && fake.recognition.startCount > 0,
      `${fake.synthesis.spoken.length} spoken, ${fake.recognition.startCount} sessions`);

    const first = session.turns[0];
    check('the trigger word is stripped from what is recorded',
      first.answer === 'a tool for remembering names', JSON.stringify(first.answer));
    check('the answer is recorded as spoken, not typed',
      first.answerSource === 'voice', first.answerSource);
    check('the interview advanced under voice alone',
      session.turns.filter((t) => t.answer).length >= 3,
      `${session.turns.filter((t) => t.answer).length} answered`);

    // A stray await between speaking and listening makes the app transcribe its own voice
    // on any device without echo cancellation. This is the only place that is observable.
    //
    // Capture sessions only. probeWebSpeech deliberately starts one the instant
    // primeSpeech() has unlocked the synthesiser, and those two overlapping is both
    // expected and harmless — the priming utterance is a space at zero volume.
    const captures = fake.recognition.sessions.filter((s) => s.flags.continuous);
    const overlapped = captures.filter((s) => fake.synthesis.speakingAt(s.startedAt));
    check('no listening session began while the app was still talking',
      overlapped.length === 0,
      `${overlapped.length} of ${captures.length} captures overlapped an utterance`);

    check('the CSP blocked nothing the driving path needed',
      app.violations.length === 0, app.violations.join('; '));
    check('no uncaught error while driving', app.errors.length === 0, app.errors.join('; '));
    app.frame.remove();
    fake.restore();
  }

  // ── a spoken command is a command, not an answer ─────────────────────────
  {
    const spoken = (text) => [{ at: 40, interim: text }, { at: 160, final: text }];
    const app = await startDriving(fake, [
      spoken('skip this one'),
      spoken('but this one I can answer over'),
    ]);

    const session = await until(async () => {
      const s = await latestSession(app.win);
      return s && s.turns.filter((t) => t.answer || t.skipped).length >= 2 ? s : null;
    }, 25000);
    if (!session) {
      check('a spoken command reaches the interview', false,
        diagnose(fake, app, await latestSession(app.win)));
      app.frame.remove();
      fake.restore();
      return;
    }

    const skipped = session.turns.find((t) => t.skipped);
    check('"skip this one" skips the question', !!skipped);
    // If the command had reached submitAnswer, RE_REFUSAL would have classified it and
    // capped that dimension's coverage — the command would appear to work and quietly
    // damage the interview.
    check('...and is never recorded as the answer to it',
      !session.turns.some((t) => /skip this one/i.test(t.answer || '')),
      JSON.stringify(session.turns.map((t) => t.answer)));

    app.frame.remove();
    fake.restore();
  }

  // ── it does not dead-end ─────────────────────────────────────────────────
  // The old loop `break`s here and leaves a message telling you to tap the microphone.
  {
    const spoken = (text) => [{ at: 40, interim: text }, { at: 160, final: text }];
    const app = await startDriving(fake, [
      [{ at: 60, end: true }],                             // heard nothing at all
      [{ at: 60, error: 'network' }, { at: 90, end: true }], // and then a failure
      [{ at: 40, interim: 'I was in the middle of' }, { deaf: true }], // and then went deaf
      spoken('and it still carried on over'),
    ]);

    const session = await until(async () => {
      const s = await latestSession(app.win);
      return s && s.turns.some((t) => /still carried on/.test(t.answer || '')) ? s : null;
    }, 40000);

    check('an empty capture, an error and a dead recogniser are all survived',
      !!session, session ? 'an answer arrived after all three'
        : diagnose(fake, app, await latestSession(app.win)));
    check('...by listening again rather than waiting for a tap',
      fake.recognition.startCount >= 4, `${fake.recognition.startCount} sessions`);
    check('and nothing on screen asked for one',
      !/tap|type|keyboard/i.test(app.$('note').textContent || ''),
      app.$('note').textContent);

    app.frame.remove();
    fake.restore();
  }

  // The four probes share one origin. Leave nothing behind for the next one.
  try {
    const regs = await navigator.serviceWorker.getRegistrations();
    await Promise.all(regs.map((r) => r.unregister()));
  } catch { /* no worker to clear */ }
  try { localStorage.removeItem('ideaforge.prefs'); } catch { /* nothing to clean */ }
}
