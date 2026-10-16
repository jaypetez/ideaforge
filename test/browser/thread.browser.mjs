// The conversation thread and the docked composer (#104), against the real app in an
// iframe with a scripted `window.claude`.
//
// What this pins:
//
// - #thread shows every settled pair (answered or skipped), the newest two by default and
//   the rest behind #b-thread-more, whose label counts them and whose aria-expanded tells
//   the truth both ways;
// - a chip sent as offered carries the "suggestion" marker, and a typed answer does not —
//   the same provenance the ratchet reads, end to end through IndexedDB;
// - model and user text is text. Questions, bridges, chips and answers seeded with an
//   `<img onerror>` render as their literal characters and never as elements;
// - at a 360×640 phone the docked composer keeps #answer on screen and uncovered at both
//   ends of the scroll, follows --keyboard-inset up over an on-screen keyboard, and does not
//   let focus scroll anything in underneath it.
//
// Seeds go straight into IndexedDB and are opened through the library's Continue.

import { askQuestion, createSession, skipQuestion } from '../../src/core/session.js';
import { DIMENSION_IDS } from '../../src/core/dimensions.js';
import { seedTurn, submitAnswer } from '../../src/runtime/turn.js';
import { deleteSession, listSessions, loadSession, saveSession } from '../../src/store/sessions.js';
import { clearCredentials } from '../../src/store/secrets.js';
import { acknowledgeInference } from './fixtures/inference.js';

// Each one would run script if it ever reached innerHTML. __pwned is the tell.
const INJECT = '<img src=x onerror="window.__pwned=1">';
const OPENING = `A notebook for couriers ${INJECT} that remembers gate codes.`;
const Q2 = `Which entrance ${INJECT} gives riders the most trouble?`;
const CHIP = `The back gate ${INJECT} behind the bins`;
const Q3 = 'How should a note about a fierce dog be shown to the next rider?';
const Q4 = `What happens when two customers share one buzzer ${INJECT}?`;
const BRIDGE4 = `Skipping the dog note is fine ${INJECT}.`;
const NEXT_CHIP = 'Whoever wrote it last';
const QUESTIONS = [
  'Who should be allowed to edit an instruction somebody else wrote?',
  'When does an old delivery instruction stop being trustworthy?',
  'Where would a rider glance at the notebook while still on the bike?',
];

async function until(predicate, ms = 5000) {
  const deadline = performance.now() + ms;
  do {
    let value;
    try { value = await predicate(); } catch { value = null; }
    if (value) return value;
    await new Promise((resolve) => setTimeout(resolve, 20));
  } while (performance.now() < deadline);
  return null;
}

const settle = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function cleanStorage() {
  for (const registration of await navigator.serviceWorker.getRegistrations()) {
    await registration.unregister();
  }
  for (const key of await caches.keys()) await caches.delete(key);
  for (const session of await listSessions()) await deleteSession(session.id);
  await clearCredentials();
  localStorage.clear();
}

function loadFrame(width, height) {
  return new Promise((resolve, reject) => {
    const frame = document.createElement('iframe');
    frame.width = width;
    frame.height = height;
    frame.style.position = 'absolute';
    frame.style.left = '-10000px';
    frame.onload = () => resolve(frame);
    frame.onerror = () => reject(new Error('thread iframe failed to load'));
    frame.src = '/index.html';
    document.body.append(frame);
  });
}

function visible(element) {
  return !!element && element.getClientRects().length > 0
    && element.ownerDocument.defaultView.getComputedStyle(element).visibility !== 'hidden';
}

/** Distinct questions, each with one chip, and every dimension at `partial`. */
function scriptedModel() {
  let asked = 0;
  return {
    claude: {
      use: async () => async (prompt, options) => {
        if (options && options.modelTier === 'complex') throw new Error('no wrap-up here');
        const question = QUESTIONS[asked++ % QUESTIONS.length];
        return {
          text: JSON.stringify({
            question, move: 'concretize', chips: [NEXT_CHIP, 'The building manager'], facts: [],
            coverage: Object.fromEntries(DIMENSION_IDS.map((id) =>
              [id, { level: 'partial', gap: 'needs detail' }])),
          }),
          modelTierApplied: 'scripted-thread',
        };
      },
    },
  };
}

/** Three settled pairs — typed, chip, skipped — and an open question, all carrying markup. */
function seedSession(id) {
  let s = seedTurn(createSession({ id, now: 1 }), { now: 1 });
  s = submitAnswer(s, { text: OPENING, now: 2 });
  s = askQuestion(s, { question: Q2, dimension: 'outcome', chips: [CHIP], source: 'model', now: 3 });
  s = submitAnswer(s, { text: CHIP, source: 'chip', now: 4 });
  s = askQuestion(s, { question: Q3, dimension: 'audience', source: 'model', now: 5 });
  s = skipQuestion(s, { now: 6 });
  return askQuestion(s, {
    question: Q4, dimension: 'constraints', bridge: BRIDGE4, chips: [CHIP], source: 'model', now: 7,
  });
}

export default async function run(report) {
  // Details only on a failure: the passing lines would otherwise print the whole thread.
  const check = (label, ok, detail = '') => report(label, ok, ok ? '' : detail);
  await cleanStorage();
  const seed = seedSession('s_thread_probe');
  await saveSession(seed);
  const frame = await loadFrame(360, 640);
  const win = frame.contentWindow;
  const doc = frame.contentDocument;
  const $ = (id) => doc.getElementById(id);
  const errors = [];
  win.addEventListener('error', (event) => errors.push(String(event.message)));
  win.addEventListener('unhandledrejection', (event) => {
    errors.push(String(event.reason?.stack || event.reason));
    event.preventDefault();
  });
  win.fetch = async () => { throw new Error('network disabled in thread probe'); };
  win.claude = scriptedModel().claude;
  const items = () => [...$('thread').children];
  const describe = () => JSON.stringify({
    items: items().map((li) => li.textContent),
    toggle: [$('b-thread-more').hidden, $('b-thread-more').textContent],
    question: $('question').textContent, err: $('err').textContent, errors,
  });

  try {
    const booted = await until(() => /^IdeaForge v/.test($('version').textContent)
      && $('provider').options.length > 0);
    check('thread: the app boots', !!booted);
    $('provider').value = 'artifact';
    $('provider').dispatchEvent(new win.Event('change'));
    $('stt').value = 'off';
    $('stt').dispatchEvent(new win.Event('change'));
    await acknowledgeInference(win);
    $('b-library').click();
    const card = await until(() => visible($('panel-library'))
      && $('library-rows').querySelector(`[data-session-id="${seed.id}"]`));
    const resume = card && [...card.querySelectorAll('button')].find((b) => b.textContent === 'Continue');
    if (!resume) { check('thread: the seeded idea can be continued', false, describe()); return; }
    resume.click();
    const opened = await until(() => visible($('manual-interview')) && $('question').textContent === Q4
      && !$('answer').disabled);
    check('thread: the seeded interview resumes on its open question', !!opened, describe());

    // ── the collapsed thread ───────────────────────────────────────────────
    check('the thread is shown once a pair has settled', visible($('thread')), describe());
    check('only the newest two pairs show by default', items().length === 2, describe());
    check('the toggle counts the folded pairs',
      visible($('b-thread-more')) && $('b-thread-more').textContent === 'Show 1 earlier'
        && $('b-thread-more').getAttribute('aria-expanded') === 'false', describe());
    const [chipPair, skippedPair] = items();
    check('the open question is not in the thread',
      !items().some((li) => li.querySelector('.thread-q').textContent === Q4), describe());
    check('a chip sent as offered is marked as a suggestion',
      chipPair.querySelector('.thread-a-text').textContent === CHIP
        && chipPair.querySelector('.thread-tag')?.textContent === 'suggestion', chipPair.outerHTML);
    check('a skipped question reads as skipped, with no suggestion marker',
      skippedPair.querySelector('.thread-q').textContent === Q3
        && skippedPair.querySelector('.thread-a-text').textContent === 'Skipped'
        && !skippedPair.querySelector('.thread-tag'), skippedPair.outerHTML);

    // ── expand ─────────────────────────────────────────────────────────────
    $('b-thread-more').click();
    check('the toggle unfolds every settled pair, oldest first',
      items().length === 3 && items()[0].querySelector('.thread-a-text').textContent === OPENING
        && items()[1].querySelector('.thread-q').textContent === Q2, describe());
    check('…and says so',
      $('b-thread-more').getAttribute('aria-expanded') === 'true'
        && $('b-thread-more').textContent === 'Hide earlier answers', describe());
    check('a typed answer carries no suggestion marker', !items()[0].querySelector('.thread-tag'),
      items()[0].outerHTML);

    // ── text, never markup ─────────────────────────────────────────────────
    await settle(100);
    const inertRoots = ['thread', 'question', 'bridge', 'chips'];
    check('model and user text renders as text: no <img> anywhere in the conversation',
      inertRoots.every((id) => !$(id).querySelector('img')), inertRoots.map((id) => $(id).innerHTML).join(' | '));
    check('…the question and bridge keep the literal markup as characters',
      $('question').textContent === Q4 && $('question').children.length === 0
        && $('bridge').textContent === BRIDGE4 && $('bridge').children.length === 0);
    check('…and none of it ran', win.__pwned === undefined);

    // ── the docked composer at a phone ─────────────────────────────────────
    const uncovered = (where) => {
      const box = $('answer').getBoundingClientRect();
      const inView = box.top >= 0 && box.bottom <= win.innerHeight + 0.5 && box.height > 40;
      const points = [[0.5, 0.5], [0.1, 0.15], [0.9, 0.15], [0.1, 0.85], [0.9, 0.85]]
        .map(([fx, fy]) => doc.elementFromPoint(box.left + box.width * fx, box.top + box.height * fy));
      const hit = points.every((el) => el === $('answer'));
      check(`at 360×640, scrolled to the ${where}, #answer is on screen and nothing covers it`,
        inView && hit,
        `answer ${Math.round(box.top)}–${Math.round(box.bottom)} of ${win.innerHeight}; hits ${points.map((el) => el?.id || el?.className || el?.tagName).join(',')}`);
    };
    check('the expanded thread makes the page taller than the phone',
      doc.documentElement.scrollHeight > win.innerHeight + 100,
      `${doc.documentElement.scrollHeight}/${win.innerHeight}`);
    win.scrollTo(0, 0);
    await settle(50);
    uncovered('top');
    win.scrollTo(0, doc.documentElement.scrollHeight);
    await settle(50);
    uncovered('bottom');
    const actions = $('composer').querySelector('.interview-actions').getBoundingClientRect();
    check('inside the composer the actions sit below the answer box',
      actions.top >= $('answer').getBoundingClientRect().bottom - 1);
    const buttons = [...doc.querySelectorAll('.interview-actions button')].filter(visible);
    check('every visible composer action clears the 24px target floor',
      buttons.length > 1 && buttons.every((b) => b.getBoundingClientRect().height >= 24
        && b.getBoundingClientRect().width >= 24),
      buttons.map((b) => `${b.id} ${Math.round(b.getBoundingClientRect().height)}`).join(','));
    check('the switch keeps its checkbox and is a named switch',
      $('handsfree').type === 'checkbox' && $('handsfree').getAttribute('role') === 'switch'
        && /Hands-free/.test($('handsfree-wrap').textContent));

    // An on-screen keyboard that shrank only the visual viewport: the dock rises above it.
    win.scrollTo(0, 0);
    doc.documentElement.style.setProperty('--keyboard-inset', '260px');
    await settle(50);
    const lifted = $('composer').getBoundingClientRect();
    check('the composer rides above a 260px keyboard',
      lifted.bottom <= win.innerHeight - 260 + 0.5 && $('answer').getBoundingClientRect().top >= 0,
      `composer bottom ${Math.round(lifted.bottom)} of ${win.innerHeight}`);
    doc.documentElement.style.setProperty('--keyboard-inset', '0px');
    await settle(50);

    // Something brought into view from below stops above the dock, not underneath it.
    // `nearest` rather than focus(): Chrome centres a focused element, which hides the
    // question, while Safari and every scrollIntoView-nearest caller align it to the edge —
    // and only scroll-padding moves that edge up to the composer's top.
    win.scrollTo(0, 0);
    await settle(50);
    const summary = doc.querySelector('details.cov summary');
    summary.scrollIntoView({ block: 'nearest' });
    summary.focus({ preventScroll: true });
    await settle(100);
    const dock = $('composer').getBoundingClientRect();
    const target = summary.getBoundingClientRect();
    check('bringing something up from below the fold leaves it clear of the docked composer',
      target.bottom <= dock.top + 0.5 && target.top >= 0,
      `summary ${Math.round(target.top)}–${Math.round(target.bottom)}, composer top ${Math.round(dock.top)}`);

    // ── a live turn joins the thread with its provenance ───────────────────
    const chip = [...$('chips').querySelectorAll('.chip')].find((b) => b.textContent === CHIP);
    check('the open question offers its chip, as text', !!chip && !chip.querySelector('img'));
    chip.click();
    $('b-send').click();
    const next = await until(() => $('question').textContent === QUESTIONS[0] && !$('answer').disabled
      && $('asking').hidden);
    check('sending a chip asks the next question', !!next, describe());
    check('the answered question joins the thread, still unfolded',
      items().length === 4 && items()[3].querySelector('.thread-q').textContent === Q4
        && items()[3].querySelector('.thread-tag')?.textContent === 'suggestion', describe());
    const stored = await loadSession(seed.id);
    check('…and its provenance was stored as chip', stored.turns[3].answerSource === 'chip',
      stored.turns[3].answerSource);

    $('answer').value = 'Only the person who wrote it, or a dispatcher.';
    $('answer').dispatchEvent(new win.Event('input', { bubbles: true }));
    $('b-send').click();
    const typed = await until(() => $('question').textContent === QUESTIONS[1] && !$('answer').disabled
      && $('asking').hidden);
    check('a typed answer joins the thread unmarked', !!typed && items().length === 5
      && !items()[4].querySelector('.thread-tag'), describe());

    $('b-thread-more').click();
    check('folding again shows the newest two and counts the rest',
      items().length === 2 && $('b-thread-more').textContent === 'Show 3 earlier'
        && $('b-thread-more').getAttribute('aria-expanded') === 'false', describe());
    check('thread: no uncaught async error', errors.length === 0, errors.join('\n'));
  } catch (error) {
    check('thread probe', false, String(error?.stack || error) + '\n' + describe());
  } finally {
    frame.remove();
    await cleanStorage();
  }
}
