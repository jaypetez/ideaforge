import { askQuestion, createSession } from '../../src/core/session.js';
import { DIMENSION_IDS } from '../../src/core/dimensions.js';
import { HARD_TURN_CEILING } from '../../src/core/engine.js';
import { seedTurn, submitAnswer } from '../../src/runtime/turn.js';
import { deleteSession, listSessions, loadSession, saveSession } from '../../src/store/sessions.js';
import { loadPrefs } from '../../src/store/prefs.js';
import { clearCredentials } from '../../src/store/secrets.js';

const OLD_ANSWER = 'A bicycle courier needs a reliable way to remember delivery instructions.';
const OLD_QUESTION = 'Which courier would test the delivery notebook first?';
const NEW_DRAFT = 'This separate idea is a quiet timer for a pottery studio.';
const WRITE_UP = '## Task\nBuild the bicycle delivery notebook for the original interview.';

async function within(promise, describe, ms = 5000) {
  let timer;
  try {
    return await Promise.race([
      promise,
      new Promise((_, reject) => {
        timer = setTimeout(() => reject(new Error(
          `Timed out after ${ms}ms: ${typeof describe === 'function' ? describe() : describe}`,
        )), ms);
      }),
    ]);
  } finally {
    clearTimeout(timer);
  }
}

async function until(predicate, ms = 5000) {
  const deadline = performance.now() + ms;
  do {
    const value = await predicate();
    if (value) return value;
    await new Promise((resolve) => setTimeout(resolve, 20));
  } while (performance.now() < deadline);
  return null;
}

async function cleanStorage() {
  let phase = 'listing service workers';
  await within((async () => {
    for (const registration of await navigator.serviceWorker.getRegistrations()) {
      phase = `unregistering service worker ${registration.scope}`;
      await registration.unregister();
    }
    phase = 'listing caches';
    for (const key of await caches.keys()) {
      phase = `deleting cache ${key}`;
      await caches.delete(key);
    }
    phase = 'listing IndexedDB sessions';
    for (const session of await listSessions()) {
      phase = `deleting IndexedDB session ${session.id}`;
      await deleteSession(session.id);
    }
    phase = 'clearing encrypted credentials';
    await clearCredentials();
    phase = 'clearing local preferences';
    localStorage.clear();
  })(), () => `isolated storage cleanup: ${phase}`);
}

function loadFrame() {
  return new Promise((resolve, reject) => {
    const frame = document.createElement('iframe');
    frame.width = 900;
    frame.height = 800;
    frame.style.position = 'absolute';
    frame.style.left = '-10000px';
    frame.onload = () => resolve(frame);
    frame.onerror = () => reject(new Error('lifecycle iframe failed to load'));
    frame.src = '/index.html';
    document.body.append(frame);
  });
}

function turnResult() {
  return {
    question: OLD_QUESTION, move: 'concretize', chips: [], facts: [],
    coverage: Object.fromEntries(DIMENSION_IDS.map((id) =>
      [id, { level: 'partial', gap: 'needs detail' }])),
  };
}

function synthesisResult() {
  return {
    title: 'Original delivery notebook', prompt: WRITE_UP, assumptions: [], openQuestions: [],
  };
}

function deferredModel() {
  const calls = [];
  return {
    calls,
    claude: {
      use: async () => (prompt, options) => new Promise((resolve) => {
        const call = {
          prompt, tier: options.modelTier, released: false,
          release(json = options.modelTier === 'complex' ? synthesisResult() : turnResult()) {
            if (call.released) return;
            call.released = true;
            resolve({ text: JSON.stringify(json), modelTierApplied: 'scripted-lifecycle' });
          },
        };
        calls.push(call);
      }),
    },
    releaseAll() { for (const call of calls) call.release(); },
  };
}

// Observe real committed session writes, not reducer revs or the number of rows.
// A held completion delays only the save promise; IndexedDB still commits real data.
function observeWrites(win) {
  const prototype = win.IDBObjectStore.prototype;
  const put = prototype.put;
  const oncomplete = Object.getOwnPropertyDescriptor(win.IDBTransaction.prototype, 'oncomplete');
  const committed = [];
  const holds = [];
  let nextHold = null;

  prototype.put = function (value, ...args) {
    const request = put.call(this, value, ...args);
    if (this.name !== 'sessions') return request;
    const snapshot = win.structuredClone(value);
    const transaction = this.transaction;
    transaction.addEventListener('complete', () => committed.push(snapshot), { once: true });
    if (nextHold && nextHold.matches(snapshot)) {
      const hold = nextHold;
      nextHold = null;
      hold.snapshot = snapshot;
      Object.defineProperty(transaction, 'oncomplete', {
        configurable: true,
        get() { return oncomplete.get.call(transaction); },
        set(callback) {
          oncomplete.set.call(transaction, function (event) {
            hold.committed = true;
            hold.promise.then(() => callback.call(transaction, event));
          });
        },
      });
    }
    return request;
  };

  return {
    committed,
    holdNext(matches) {
      let release;
      const promise = new Promise((resolve) => { release = resolve; });
      const hold = { matches, promise, release, committed: false, snapshot: null };
      nextHold = hold;
      holds.push(hold);
      return hold;
    },
    releaseAll() { for (const hold of holds) hold.release(); },
    restore() { prototype.put = put; },
  };
}

function visible(element) {
  return !!element && element.getClientRects().length > 0
    && element.ownerDocument.defaultView.getComputedStyle(element).visibility !== 'hidden';
}

function detail(app) {
  return JSON.stringify({
    panels: ['panel-setup', 'panel-interview', 'panel-library', 'panel-done']
      .filter((id) => visible(app.$(id))),
    question: app.$('question').textContent,
    answer: app.$('answer').value,
    error: app.$('err').textContent,
    voicePhase: app.$('voice-stage').dataset.phase,
    voiceVisible: visible(app.$('voice-stage')),
    reopenDisabled: app.$('b-reopen').disabled,
    doneTitle: app.$('done-title').textContent,
    calls: app.model.calls.map((call) => ({ tier: call.tier, released: call.released })),
    writes: app.writes.committed.map((row) => ({
      id: row.id, rev: row.rev, turns: row.turns.length, draft: row.draftAnswer,
      synthesis: !!row.synthesis.text,
    })),
    errors: app.errors,
  });
}

async function expect(app, check, label, predicate) {
  const result = await until(predicate);
  check(label, !!result, result ? '' : detail(app));
  return result;
}

// Keep the actual DOM click, but retain the async handler's completion so navigation
// checks cannot pass merely because a released model result has not reached the UI yet.
function click(app, target, { dispatch = false } = {}) {
  const button = typeof target === 'string' ? app.$(target) : target;
  const handler = button && button.onclick;
  if (typeof handler !== 'function') throw new Error('missing click handler: ' + (button?.id || target));
  const operation = { name: button.id || button.textContent, done: false };
  button.onclick = function (event) {
    const result = handler.call(this, event);
    operation.promise = Promise.resolve(result).then(
      () => { operation.done = true; },
      (error) => { app.errors.push(String(error?.stack || error)); operation.done = true; },
    );
    app.operations.push(operation);
    return result;
  };
  try {
    if (dispatch) button.dispatchEvent(new app.win.MouseEvent('click', { bubbles: true, cancelable: true }));
    else button.click();
  } finally { button.onclick = handler; }
  if (!operation.promise) throw new Error('click did not run: ' + (button.id || button.textContent));
  return operation;
}

function type(app, text) {
  app.$('answer').value = text;
  app.$('answer').dispatchEvent(new app.win.Event('input', { bubbles: true }));
}

async function withApp(check, label, scenario, { seed, voice = false } = {}) {
  await cleanStorage();
  if (seed) await saveSession(seed);
  let app;
  let frame;
  const fake = window.__FakeVoice;
  try {
    frame = await loadFrame();
    const win = frame.contentWindow;
    const $ = (id) => frame.contentDocument.getElementById(id);
    const errors = [];
    const violations = [];
    win.addEventListener('error', (event) => errors.push(String(event.message)));
    win.addEventListener('unhandledrejection', (event) => {
      errors.push(String(event.reason?.stack || event.reason));
      event.preventDefault();
    });
    win.addEventListener('securitypolicyviolation',
      (event) => violations.push(event.violatedDirective + ' blocked ' + event.blockedURI));
    fake.install(win, { script: [], speakMs: voice ? 150 : 10 });
    const model = deferredModel();
    win.claude = model.claude;
    const requests = [];
    win.fetch = async (input) => {
      requests.push(String(input?.url || input));
      throw new Error('network disabled in lifecycle probe');
    };
    app = { frame, win, $, fake, errors, violations, model, requests, operations: [],
      writes: observeWrites(win) };
    if (!(await expect(app, check, label + ': app finishes asynchronous setup', () =>
      /^IdeaForge v/.test($('version').textContent) && $('provider').options.length > 0))) return;
    $('provider').value = 'artifact';
    $('provider').dispatchEvent(new win.Event('change'));
    $('stt').value = voice ? 'browser' : 'off';
    $('stt').dispatchEvent(new win.Event('change'));
    await scenario(app);
    check(label + ': no uncaught async error', errors.length === 0, errors.join('\n'));
    check(label + ': no provider network request', requests.length === 0, requests.join('\n'));
    check(label + ': no CSP violation', violations.length === 0, violations.join('\n'));
  } catch (error) {
    check(label, false, String(error?.stack || error) + (app ? '\n' + detail(app) : ''));
  } finally {
    let cleanupError = null;
    const snapshot = app ? detail(app) : 'iframe setup did not complete';
    try {
      if (app) {
        if (visible(app.$('voice-stage'))) click(app, 'b-voice-exit');
        app.writes.releaseAll();
        app.model.releaseAll();
        await within(Promise.all(app.operations.map((operation) => operation.promise)),
          () => 'waiting for started mutations: ' + app.operations
            .filter((operation) => !operation.done).map((operation) => operation.name).join(', '));
      }
    } catch (error) {
      cleanupError = error;
      check(`${label}: cleanup started mutations`, false,
        String(error?.stack || error) + (app ? '\n' + detail(app) : ''));
    } finally {
      app?.writes.restore();
      frame?.remove();
      fake.restore();
    }
    try {
      await cleanStorage();
    } catch (error) {
      cleanupError = error;
      check(`${label}: cleanup storage`, false, String(error?.stack || error) + '\n' + snapshot);
    }
    if (cleanupError) {
      throw new Error(`${label}: cleanup failed; later scenarios will not reuse this origin`,
        { cause: cleanupError });
    }
    check(label + ': isolated fixture cleanup completes', true);
  }
}

async function start(app, check, label) {
  const before = new Set((await listSessions()).map((session) => session.id));
  const operation = click(app, 'b-start');
  return expect(app, check, label, async () => {
    if (!operation.done || !visible(app.$('panel-interview'))) return null;
    return (await listSessions()).find((session) => !before.has(session.id)) || null;
  });
}

async function openLibrary(app, check, label) {
  const operation = click(app, 'b-library');
  return expect(app, check, label, () => operation.done && visible(app.$('panel-library')));
}

async function newIdea(app, check) {
  const operation = click(app, 'b-library-new');
  if (!(await expect(app, check, 'New idea returns to setup while old work is pending',
    () => operation.done && visible(app.$('panel-setup'))))) return null;
  const session = await start(app, check, 'another interview starts before the old response is released');
  if (!session) return null;
  type(app, NEW_DRAFT);
  if (!(await expect(app, check, 'the new interview owns its independently persisted draft', async () =>
    (await loadSession(session.id))?.draftAnswer === NEW_DRAFT))) return null;
  return session;
}

function settledTurnWrites(app, id) {
  return app.writes.committed.filter((session) => session.id === id && !session.pending
    && session.turns.some((turn) => turn.question === OLD_QUESTION));
}

async function assertActiveIdea(app, check, active, label) {
  const saved = await loadSession(active.id);
  check(label + ': the new active interview is not replaced',
    visible(app.$('panel-interview')) && !visible(app.$('panel-done'))
      && app.$('question').textContent === active.turns[0].question
      && app.$('answer').value === NEW_DRAFT,
    detail(app));
  check(label + ': old answers and metadata never enter the new record',
    saved && saved.turns.length === 1 && !saved.turns[0].answer && !saved.title
      && !saved.synthesis.text && saved.draftAnswer === NEW_DRAFT,
    JSON.stringify(saved));
  const after = NEW_DRAFT + ' The wheel should be usable with clay-covered hands.';
  type(app, after);
  await expect(app, check, label + ': later edits still persist to the new session', async () =>
    (await loadSession(active.id))?.draftAnswer === after);
}

function synthesisSeed() {
  let session = seedTurn(createSession({ id: 's_lifecycle_synthesis', now: 1 }), { now: 1 });
  for (let i = 0; i < 3; i++) {
    session = submitAnswer(session, { text: OLD_ANSWER + ' Detail ' + i, now: 2 + i * 2 });
    session = askQuestion(session, {
      question: `What delivery detail matters at stop ${i + 1}?`,
      dimension: 'outcome', source: 'model', now: 3 + i * 2,
    });
  }
  return session;
}

function terminalSeed() {
  let session = seedTurn(createSession({ id: 's_lifecycle_terminal', now: 1 }), { now: 1 });
  for (let index = 1; index < HARD_TURN_CEILING; index++) {
    session = submitAnswer(session, {
      text: `${OLD_ANSWER} Recorded delivery ${index}.`, source: 'voice', now: index * 2,
    });
    session = askQuestion(session, {
      question: `Which delivery instruction matters at stop ${index}?`,
      dimension: 'outcome', source: 'model', now: index * 2 + 1,
    });
  }
  return session;
}

export default async function run(check) {
  await import('./fixtures/fake-voice.js');

  await withApp(check, 'delayed turn after switching interviews', async (app) => {
    const old = await start(app, check, 'the originating interview starts');
    if (!old) return;
    type(app, OLD_ANSWER);
    const send = click(app, 'b-send');
    if (!(await expect(app, check, 'the scripted turn is held in flight',
      () => app.model.calls.length === 1))) return;
    check('the answer is durable before the model is released',
      (await loadSession(old.id))?.turns[0].answer === OLD_ANSWER);
    if (!(await openLibrary(app, check, 'Library is usable while a model turn is pending'))) return;
    const active = await newIdea(app, check);
    if (!active) return;
    app.model.calls[0].release();
    if (!(await expect(app, check, 'the old turn settles after navigation',
      () => send.done))) return;
    const saved = await loadSession(old.id);
    check('the settled question stays with the original answer',
      saved?.turns.length === 2 && saved.turns[0].answer === OLD_ANSWER
        && saved.turns[1].question === OLD_QUESTION && !saved.pending, JSON.stringify(saved));
    check('the old settled model result is persisted exactly once',
      settledTurnWrites(app, old.id).length === 1,
      `${settledTurnWrites(app, old.id).length} committed settled writes`);
    await assertActiveIdea(app, check, active, 'delayed turn');
  });

  await withApp(check, 'deleted interview with an in-flight turn', async (app) => {
    const old = await start(app, check, 'the deletable interview starts');
    if (!old) return;
    type(app, OLD_ANSWER);
    const send = click(app, 'b-send');
    if (!(await expect(app, check, 'the deletable interview has a held model request',
      () => app.model.calls.length === 1))) return;
    if (!(await openLibrary(app, check, 'the pending interview can be found in the library'))) return;
    app.win.confirm = () => true;
    const card = app.$('library-rows').querySelector(`[data-session-id="${old.id}"]`);
    const remove = card && [...card.querySelectorAll('button')]
      .find((button) => button.textContent === 'Delete');
    const deletion = click(app, remove);
    if (!(await expect(app, check, 'Delete removes the pending interview before the result arrives',
      async () => deletion.done && await loadSession(old.id) === null))) return;
    const active = await newIdea(app, check);
    if (!active) return;
    const writesBefore = app.writes.committed.filter((row) => row.id === old.id).length;
    app.model.calls[0].release();
    if (!(await expect(app, check, 'the deleted interview operation finishes',
      () => send.done))) return;
    check('a late model result never resurrects a deleted interview',
      await loadSession(old.id) === null, detail(app));
    check('there is no session write for the deleted id after deletion',
      app.writes.committed.filter((row) => row.id === old.id).length === writesBefore,
      detail(app));
    await assertActiveIdea(app, check, active, 'deleted origin');
  });

  await withApp(check, 'duplicate send during answer persistence', async (app) => {
    const old = await start(app, check, 'the duplicate-send interview starts');
    if (!old) return;
    const held = app.writes.holdNext((row) => row.id === old.id
      && row.turns[0]?.answer === OLD_ANSWER);
    type(app, OLD_ANSWER);
    const send = click(app, 'b-send');
    if (!(await expect(app, check, 'the pre-request answer save has committed but has not returned',
      () => held.committed))) return;
    check('Send and Skip are locked before awaiting answer persistence',
      app.$('b-send').disabled && app.$('b-skip').disabled, detail(app));
    check('no model request runs ahead of the durability boundary', app.model.calls.length === 0);
    // Refill the box: a cleared textarea would make the second send a vacuous empty no-op.
    type(app, OLD_ANSWER);
    app.$('answer').dispatchEvent(new app.win.KeyboardEvent('keydown', {
      key: 'Enter', ctrlKey: true, bubbles: true, cancelable: true,
    }));
    app.$('b-send').click();
    held.release();
    if (!(await expect(app, check, 'one model request follows the released save',
      () => app.model.calls.length > 0))) return;
    app.model.releaseAll();
    if (!(await expect(app, check, 'the original send finishes after the duplicate gestures',
      () => send.done))) return;
    const saved = await loadSession(old.id);
    check('duplicate send gestures produce exactly one model call', app.model.calls.length === 1,
      `${app.model.calls.length} calls`);
    check('duplicate send records the answer and next question once',
      saved?.turns.length === 2 && saved.turns.filter((turn) => turn.answer).length === 1
        && saved.turns[0].answer === OLD_ANSWER && saved.turns[1].question === OLD_QUESTION,
      JSON.stringify(saved));
    check('the settled duplicate-send result is saved exactly once',
      settledTurnWrites(app, old.id).length === 1,
      `${settledTurnWrites(app, old.id).length} committed settled writes`);
  });

  const seed = synthesisSeed();
  await withApp(check, 'delayed synthesis after switching interviews', async (app) => {
    if (!(await openLibrary(app, check, 'the prepared interview appears in the library'))) return;
    const card = app.$('library-rows').querySelector(`[data-session-id="${seed.id}"]`);
    const resume = card && [...card.querySelectorAll('button')]
      .find((button) => button.textContent === 'Continue');
    const opening = click(app, resume);
    if (!(await expect(app, check, 'the prepared interview can be wrapped up',
      () => opening.done && visible(app.$('b-wrap')) && !app.$('b-wrap').disabled))) return;
    const wrapping = click(app, 'b-wrap');
    if (!(await expect(app, check, 'the scripted synthesis is held in flight',
      () => app.model.calls.length === 1 && app.model.calls[0].tier === 'complex'))) return;
    if (!(await openLibrary(app, check, 'Library is usable during synthesis'))) return;
    const active = await newIdea(app, check);
    if (!active) return;
    app.model.calls[0].release(synthesisResult());
    if (!(await expect(app, check, 'the old synthesis operation finishes',
      () => wrapping.done))) return;
    const saved = await loadSession(seed.id);
    check('the completed write-up is retained by its originating interview',
      saved?.status === 'done' && saved.synthesis.text === WRITE_UP && !saved.pending,
      JSON.stringify(saved));
    const writes = app.writes.committed.filter((row) => row.id === seed.id
      && row.synthesis.text === WRITE_UP && !row.pending);
    check('the settled write-up is persisted exactly once', writes.length === 1,
      `${writes.length} committed synthesis writes`);
    await assertActiveIdea(app, check, active, 'delayed synthesis');
  }, { seed });

  const terminal = terminalSeed();
  await withApp(check, 'Exit during the terminal turn settled save', async (app) => {
    if (!(await openLibrary(app, check, 'the terminal interview is available in the library'))) return;
    const card = app.$('library-rows').querySelector(`[data-session-id="${terminal.id}"]`);
    const opening = click(app, [...card.querySelectorAll('button')]
      .find((button) => button.textContent === 'Continue'));
    if (!(await expect(app, check, 'the last unanswered turn resumes in manual mode', () =>
      opening.done && visible(app.$('manual-interview'))
        && app.$('question').textContent === terminal.turns.at(-1).question))) return;
    const answer = 'Keep the final delivery instruction beside the address on this device';
    let terminalWrites = 0;
    const held = app.writes.holdNext((row) => {
      if (row.id !== terminal.id || row.turns.at(-1)?.answer !== answer) return false;
      // The first save makes the answer durable; the second belongs to settled runTurn.
      return ++terminalWrites === 2;
    });
    app.fake.script([[{ at: 30, final: `${answer} over`, confidence: 0.9 }]]);
    app.$('handsfree').checked = true;
    app.$('handsfree').dispatchEvent(new app.win.Event('change'));
    if (!(await expect(app, check, 'the terminal result has committed while its save completion is held',
      () => held.committed))) return;
    check('the held save is the terminal settled result, not pre-request persistence',
      terminalWrites === 2 && held.snapshot.turns.length === HARD_TURN_CEILING
        && held.snapshot.turns.at(-1).answer === answer && !held.snapshot.pending
        && app.model.calls.length === 0, detail(app));
    check('the terminal turn is still voice-owned while its settled save is pending',
      visible(app.$('voice-stage')) && app.$('voice-stage').dataset.phase === 'thinking', detail(app));
    const exiting = click(app, 'b-voice-exit');
    if (!(await expect(app, check, 'Exit restores manual presentation before the terminal save returns', () =>
      exiting.done && !visible(app.$('voice-stage')) && visible(app.$('manual-interview'))))) return;
    held.release();
    if (!(await expect(app, check, 'the released terminal result stays manual and ready to wrap', () =>
      visible(app.$('panel-interview')) && visible(app.$('manual-interview'))
        && !visible(app.$('voice-stage')) && !visible(app.$('panel-done'))
        && /ready to write it up/i.test(app.$('question').textContent)
        && visible(app.$('b-wrap')) && !app.$('b-wrap').disabled))) return;
    const saved = await within(loadSession(terminal.id), 'reading the terminal result after Exit');
    check('Exit prevents automatic synthesis after the terminal save settles',
      app.model.calls.length === 0 && !saved.synthesis.text && saved.status !== 'done'
        && !saved.pending && saved.turns.at(-1).answer === answer
        && saved.turns.at(-1).answerSource === 'voice', detail(app));
    const saves = app.writes.committed.filter((row) => row.id === terminal.id
      && row.turns.at(-1)?.answer === answer);
    check('only the answer-durability and settled saves occur, and the terminal turn stays disabled',
      saves.length === 2 && app.$('b-send').disabled && app.$('b-skip').disabled
        && app.$('answer').disabled, detail(app));

    const starts = app.fake.recognition.startCount;
    const speeches = app.fake.synthesis.spoken.length;
    app.$('handsfree').checked = true;
    app.$('handsfree').dispatchEvent(new app.win.Event('change'));
    if (!(await expect(app, check, 'toggling Hands-free after terminal Exit stays manual and ready to wrap', () =>
      visible(app.$('manual-interview')) && !visible(app.$('voice-stage'))
        && !visible(app.$('panel-done')) && !app.$('handsfree').checked
        && /ready to write it up/i.test(app.$('question').textContent)
        && visible(app.$('b-wrap')) && !app.$('b-wrap').disabled))) return;
    const afterToggle = await within(loadSession(terminal.id), 'reading the terminal idea after the voice toggle');
    check('a rejected terminal voice toggle does not persist hands-free as enabled',
      loadPrefs().handsFree === false, `handsFree=${loadPrefs().handsFree}`);
    check('the terminal toggle starts no synthesis, narration or recognition',
      app.model.calls.length === 0 && app.fake.recognition.startCount === starts
        && app.fake.synthesis.spoken.length === speeches, detail(app));
    check('the rejected voice toggle preserves the saved terminal answer unchanged',
      JSON.stringify(afterToggle) === JSON.stringify(saved), detail(app));
  }, { seed: terminal, voice: true });

  const pendingSynthesis = synthesisSeed();
  await withApp(check, 'Ask me more during same-session synthesis', async (app) => {
    if (!(await openLibrary(app, check, 'the synthesis guard interview appears in the library'))) return;
    const card = app.$('library-rows').querySelector(`[data-session-id="${pendingSynthesis.id}"]`);
    const opening = click(app, [...card.querySelectorAll('button')]
      .find((button) => button.textContent === 'Continue'));
    if (!(await expect(app, check, 'the synthesis guard interview is ready to write up', () =>
      opening.done && visible(app.$('b-wrap')) && !app.$('b-wrap').disabled))) return;
    const wrapping = click(app, 'b-wrap');
    if (!(await expect(app, check, 'the original same-session synthesis is held in flight', () =>
      app.model.calls.length === 1 && app.model.calls[0].tier === 'complex'))) return;
    check('Ask me more is disabled while the same interview is being synthesized',
      visible(app.$('b-reopen')) && app.$('b-reopen').disabled, detail(app));
    app.$('b-reopen').click();
    // A dispatched event bypasses native disabled-click suppression, not the app's guard.
    const reopening = click(app, 'b-reopen', { dispatch: true });
    await expect(app, check, 'the reopen handler refuses rather than joining the pending synthesis result',
      () => reopening.done);
    check('the guarded attempt leaves the original write-up and request kind alone',
      visible(app.$('panel-done')) && !visible(app.$('panel-interview'))
        && app.model.calls.length === 1 && !app.model.calls[0].released, detail(app));
    app.model.calls[0].release(synthesisResult());
    if (!(await expect(app, check, 'the original synthesis completes after the guarded reopen attempt', () =>
      wrapping.done && reopening.done && visible(app.$('panel-done'))
        && app.$('output').dataset.source?.includes(WRITE_UP)))) return;
    const saved = await within(loadSession(pendingSynthesis.id), 'reading the guarded synthesis result');
    check('Ask me more neither creates a second synthesis nor corrupts the first result',
      app.model.calls.length === 1 && saved.status === 'done' && saved.synthesis.text === WRITE_UP
        && !saved.pending && JSON.stringify(saved.turns) === JSON.stringify(pendingSynthesis.turns)
        && app.$('err').textContent === '', detail(app));
    const writes = app.writes.committed.filter((row) => row.id === pendingSynthesis.id
      && row.synthesis.text === WRITE_UP && !row.pending);
    check('the guarded synthesis result is committed once and Ask me more is available afterward',
      writes.length === 1 && !app.$('b-reopen').disabled,
      `${writes.length} settled writes; reopen disabled=${app.$('b-reopen').disabled}`);
  }, { seed: pendingSynthesis });
}
