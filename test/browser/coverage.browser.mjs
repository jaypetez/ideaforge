// The coverage meter and the desktop coverage panel (#105), against the real app in an
// iframe with a scripted `window.claude`.
//
// What this pins:
//
// - #meter's aria-valuenow is exactly coveragePercent of the session the app stored, at
//   every point of a scripted interview — not a recomputation that happens to agree on the
//   first turn. The expected figure is computed here from src/core over the IndexedDB row,
//   so a meter that rounded differently, or counted a waived dimension, disagrees;
// - aria-valuetext says the same number and the covered count;
// - one segment per dimension, in DIMENSIONS order, each showing that dimension's stored
//   level (or "skipped" once it is set aside), each level painted differently and at 3:1
//   against the page in light and in dark;
// - at 1280px Coverage is an open side panel beside the question, and clicking its summary
//   does not close it; at 800px it is an ordinary disclosure again;
// - at 360px the phone top app bar holds the meter with nothing scrolling sideways;
// - Done hides the meter. (Voice mode hiding it is pinned in voice-stage.browser.mjs.)
//
// The scripted model claims `covered`, with a quote, for every dimension on every turn. The
// ratchet lets two dimensions rise one level per turn, so the levels — and the percentage —
// move by a known, mixed amount each turn: thin, partial and covered side by side.

import { askQuestion, createSession, sessionDisplayTitle } from '../../src/core/session.js';
import { DIMENSION_IDS } from '../../src/core/dimensions.js';
import { coveragePercent } from '../../src/core/markdown.js';
import { seedTurn, submitAnswer } from '../../src/runtime/turn.js';
import { deleteSession, listSessions, loadSession, saveSession } from '../../src/store/sessions.js';
import { clearCredentials } from '../../src/store/secrets.js';
import { acknowledgeInference } from './fixtures/inference.js';

const ID = 's_coverage_probe';
const QUESTIONS = [
  'Who will open this checklist first on a busy morning?',
  'What does a finished checklist look like when it is printed?',
  'Which mistake would make you stop trusting the checklist?',
  'How long should one pass through the checklist take?',
  'Where does the checklist live between uses?',
  'What should happen to an item nobody has ticked for a month?',
];
const ANSWERS = [
  'The duty manager, usually before seven, standing at the pass with coffee in one hand.',
  'One laminated page, twelve lines at most, grouped by station so nobody walks twice.',
  'If it ever told the fry cook to check a fridge that was removed last spring, it is dead to us.',
  'Ten minutes, start to finish, including the walk-in, or people will start skipping it.',
  'On a clipboard by the back door, next to the delivery log, with a pen on a string.',
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
    frame.onerror = () => reject(new Error('coverage iframe failed to load'));
    frame.src = '/index.html';
    document.body.append(frame);
  });
}

function visible(element) {
  return !!element && element.getClientRects().length > 0
    && element.ownerDocument.defaultView.getComputedStyle(element).visibility !== 'hidden';
}

function scriptedModel() {
  let asked = 0;
  return {
    use: async () => async (prompt, options) => {
      if (options && options.modelTier === 'complex') throw new Error('no wrap-up here');
      const question = QUESTIONS[asked++ % QUESTIONS.length];
      return {
        text: JSON.stringify({
          question, move: 'concretize', chips: [], facts: [],
          coverage: Object.fromEntries(DIMENSION_IDS.map((id) =>
            [id, { level: 'covered', gap: null, evidence: 'kitchen opening checklist' }])),
        }),
        modelTierApplied: 'scripted-coverage',
      };
    },
  };
}

function seedSession() {
  let s = seedTurn(createSession({ id: ID, now: 1 }), { now: 1 });
  s = submitAnswer(s, { text: 'A kitchen opening checklist that stays honest as the kitchen changes.', now: 2 });
  return askQuestion(s, {
    question: 'What goes wrong today when the kitchen opens late?', dimension: 'outcome',
    source: 'model', now: 3,
  });
}

/** WCAG relative luminance contrast of two computed `rgb()`/`rgba()` colours. */
function contrast(a, b) {
  const lum = (css) => {
    const [r, g, bl] = css.match(/[\d.]+/g).slice(0, 3).map(Number).map((v) => {
      const c = v / 255;
      return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
    });
    return 0.2126 * r + 0.7152 * g + 0.0722 * bl;
  };
  const [hi, lo] = [lum(a), lum(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

export default async function run(report) {
  const check = (label, ok, detail = '') => report(label, ok, ok ? '' : detail);
  await cleanStorage();
  await saveSession(seedSession());
  const frame = await loadFrame(1280, 900);
  const win = frame.contentWindow;
  const doc = frame.contentDocument;
  const $ = (id) => doc.getElementById(id);
  const errors = [];
  win.addEventListener('error', (event) => errors.push(String(event.message)));
  win.addEventListener('unhandledrejection', (event) => {
    errors.push(String(event.reason?.stack || event.reason));
    event.preventDefault();
  });
  win.fetch = async () => { throw new Error('network disabled in coverage probe'); };
  win.claude = scriptedModel();
  const segments = () => [...$('meter-fill').children];
  const meterState = () => ({
    now: $('meter').getAttribute('aria-valuenow'), text: $('meter').getAttribute('aria-valuetext'),
    label: $('meter-label').textContent,
    segs: segments().map((s) => `${s.dataset.dimension}:${s.dataset.level}`).join(' '),
    err: $('err').textContent, errors,
  });

  /** The meter against the stored session, at one point of the interview. */
  const agrees = async (where) => {
    const stored = await loadSession(ID);
    const pct = coveragePercent(stored);
    const state = meterState();
    check(`${where}: aria-valuenow is exactly coveragePercent of the stored session (${pct})`,
      state.now === String(pct) && state.label === `${pct}%`, JSON.stringify(state));
    const probing = DIMENSION_IDS.filter((id) => stored.coverage[id].status === 'probing');
    const covered = probing.filter((id) => stored.coverage[id].level === 'covered').length;
    check(`${where}: aria-valuetext gives the percentage and the covered count`,
      state.text.startsWith(`${pct}% — ${covered} of ${probing.length} covered`), state.text);
    const expected = DIMENSION_IDS.map((id) => `${id}:${stored.coverage[id].status === 'probing'
      ? stored.coverage[id].level : 'skipped'}`).join(' ');
    check(`${where}: seven segments, in DIMENSIONS order, each at its stored level`,
      state.segs === expected, `meter ${state.segs}\nstore ${expected}`);
    return stored;
  };

  const answer = async (text, nextQuestion) => {
    $('answer').value = text;
    $('answer').dispatchEvent(new win.Event('input', { bubbles: true }));
    $('b-send').click();
    return until(() => $('question').textContent === nextQuestion && $('asking').hidden
      && !$('answer').disabled);
  };

  try {
    const booted = await until(() => /^IdeaForge v/.test($('version').textContent)
      && $('provider').options.length > 0);
    check('coverage: the app boots', !!booted);
    $('provider').value = 'artifact';
    $('provider').dispatchEvent(new win.Event('change'));
    $('stt').value = 'off';
    $('stt').dispatchEvent(new win.Event('change'));
    await acknowledgeInference(win);
    $('b-library').click();
    const card = await until(() => visible($('panel-library'))
      && $('library-rows').querySelector(`[data-session-id="${ID}"]`));
    card?.querySelector('.idea-select').click();
    const resume = [...$('library-detail').querySelectorAll('button')]
      .find((b) => b.textContent === 'Continue');
    if (!resume) { check('coverage: the seeded idea can be continued', false, JSON.stringify(meterState())); return; }
    resume.click();
    const opened = await until(() => visible($('manual-interview')) && !$('answer').disabled);
    check('coverage: the seeded interview resumes', !!opened, JSON.stringify(meterState()));

    // ── the meter through a scripted interview ───────────────────────────────
    check('the meter is a progressbar from 0 to 100 and is shown during the interview',
      visible($('meter')) && $('meter').getAttribute('role') === 'progressbar'
        && $('meter').getAttribute('aria-valuemin') === '0'
        && $('meter').getAttribute('aria-valuemax') === '100');
    check('the segments are hidden from screen readers; the progressbar speaks for them',
      $('meter-fill').getAttribute('aria-hidden') === 'true');
    await agrees('on resume');
    // Three answers: two dimensions covered, two partial, three thin — 43%.
    let seen = new Set();
    for (let i = 0; i < 3; i++) {
      const next = await answer(ANSWERS[i], QUESTIONS[i]);
      if (!next) { check(`turn ${i + 1} asks the next question`, false, JSON.stringify(meterState())); return; }
      const stored = await agrees(`after answer ${i + 1}`);
      seen = new Set(DIMENSION_IDS.map((id) => stored.coverage[id].level));
    }
    check('the scripted turns leave thin, partial and covered side by side',
      ['thin', 'partial', 'covered'].every((level) => seen.has(level)), [...seen].join(','));
    check('the percentage is neither 0 nor 100 mid-interview, so a stuck meter would show',
      !['0', '100'].includes($('meter').getAttribute('aria-valuenow')), JSON.stringify(meterState()));

    // ── each level painted, and painted legibly ──────────────────────────────
    const byLevel = (level) => segments().find((s) => s.dataset.level === level);
    const paint = (seg) => {
      const cs = win.getComputedStyle(seg);
      return `${cs.backgroundColor}|${cs.backgroundImage}|${cs.boxShadow}`;
    };
    for (const theme of ['light', 'dark']) {
      doc.documentElement.dataset.theme = theme;
      await settle(30);
      // Segments transition their colour over .35s; finish that, or the colour read below
      // is wherever the easing got to and the contrast check passes or fails on timing.
      for (const seg of segments()) seg.getAnimations().forEach((a) => a.finish());
      const page = win.getComputedStyle(doc.body).backgroundColor;
      const levels = ['thin', 'partial', 'covered'].map(byLevel);
      check(`${theme}: thin, partial and covered segments are each painted differently`,
        levels.every(Boolean) && new Set(levels.map(paint)).size === 3, levels.map((s) => s && paint(s)).join('\n'));
      const ratios = levels.map((s) => contrast(win.getComputedStyle(s).color, page));
      check(`${theme}: every level's colour is at least 3:1 against the page`,
        ratios.every((r) => r >= 3), ratios.map((r) => r.toFixed(2)).join(' '));
    }
    delete doc.documentElement.dataset.theme;

    // ── setting a dimension aside ────────────────────────────────────────────
    const lastId = DIMENSION_IDS[DIMENSION_IDS.length - 1];
    const lastRow = [...$('coverage').children][DIMENSION_IDS.length - 1];
    const waive = lastRow && [...lastRow.querySelectorAll('button')].find((b) => b.textContent === 'not relevant');
    if (!waive) { check('the last dimension can be marked not relevant', false); return; }
    waive.click();
    await until(async () => (await loadSession(ID)).coverage[lastId].status === 'waived');
    await settle(50);
    await agrees('after marking one dimension not relevant');
    const skipped = segments()[DIMENSION_IDS.length - 1];
    check('a dimension set aside is its own segment, unlike thin, partial or covered',
      skipped.dataset.level === 'skipped'
        && !['thin', 'partial', 'covered'].map(byLevel).some((s) => s && paint(s) === paint(skipped))
        && /not relevant/.test(skipped.title),
      paint(skipped));
    check('…and the spoken text says it was set aside',
      /, 1 set aside$/.test($('meter').getAttribute('aria-valuetext')), $('meter').getAttribute('aria-valuetext'));

    // ── the side panel at 1280px ─────────────────────────────────────────────
    const cov = doc.querySelector('details.cov');
    const summary = cov.querySelector('summary');
    const card1280 = doc.querySelector('.question-card').getBoundingClientRect();
    const panel = cov.getBoundingClientRect();
    check('at 1280px Coverage is open and every row is visible',
      cov.open && visible($('coverage')) && [...$('coverage').children].every(visible),
      `open ${cov.open}`);
    check('…beside the question, not under it',
      panel.left >= card1280.right && panel.top < card1280.bottom && panel.width >= 240,
      `card ${Math.round(card1280.left)}–${Math.round(card1280.right)}, panel ${Math.round(panel.left)}–${Math.round(panel.right)} top ${Math.round(panel.top)}`);
    summary.click();
    await settle(50);
    check('…and its summary does not close it', cov.open && visible($('coverage')));
    check('…nor offers itself as a toggle', summary.getAttribute('aria-disabled') === 'true');
    check('at 1280px nothing scrolls sideways',
      doc.documentElement.scrollWidth <= doc.documentElement.clientWidth,
      `${doc.documentElement.scrollWidth}/${doc.documentElement.clientWidth}`);

    // ── a short laptop: the stuck panel fits the viewport and scrolls ────────
    frame.width = 1366;
    frame.height = 700;
    await settle(150);
    win.scrollTo(0, doc.documentElement.scrollHeight);
    await settle(50);
    const shortPanel = cov.getBoundingClientRect();
    check('at 1366×700 the side panel fits inside the viewport',
      shortPanel.top >= 0 && shortPanel.bottom <= win.innerHeight + 0.5,
      `panel ${Math.round(shortPanel.top)}–${Math.round(shortPanel.bottom)} of ${win.innerHeight}`);
    check('…because it is capped at the viewport and scrolls inside itself',
      win.getComputedStyle(cov).overflowY === 'auto'
        && Math.abs(parseFloat(win.getComputedStyle(cov).maxHeight)
          - (win.innerHeight - parseFloat(win.getComputedStyle(doc.querySelector('.wrap')).paddingBottom)
            - parseFloat(win.getComputedStyle(cov).top))) < 1,
      `${win.getComputedStyle(cov).maxHeight} / ${win.getComputedStyle(cov).overflowY}`);
    // Seven rows do not overflow 700px on their own, so shorten the window until they must.
    // Scrolled just past the panel's own top, so it is stuck. (At the very end of the page a
    // sticky box rides up with the end of its grid area, as sticky boxes do.)
    frame.height = 420;
    await settle(150);
    win.scrollTo(0, 0);
    win.scrollTo(0, cov.getBoundingClientRect().top - 16 + 24);
    await settle(50);
    const squeezed = cov.getBoundingClientRect();
    const before = cov.scrollTop;
    cov.scrollTop = cov.scrollHeight;
    check('at 1366×420, stuck, the panel still fits, and scrolls to reach its last rows',
      squeezed.top >= 0 && squeezed.bottom <= win.innerHeight + 0.5
        && cov.scrollHeight > cov.clientHeight && cov.scrollTop > before,
      `panel ${Math.round(squeezed.top)}–${Math.round(squeezed.bottom)} of ${win.innerHeight}; scrollHeight ${cov.scrollHeight}, clientHeight ${cov.clientHeight}, scrollTop ${cov.scrollTop}`);
    cov.scrollTop = 0;
    win.scrollTo(0, 0);
    frame.height = 900;

    // ── a disclosure again at 800px ──────────────────────────────────────────
    frame.width = 800;
    await settle(150);
    check('at 800px Coverage is back to the disclosure it was before the window widened',
      // Height, not visible(): a closed <details> still reports boxes for its unrendered rows.
      !cov.open && cov.getBoundingClientRect().height < summary.getBoundingClientRect().height + 40
        && !summary.hasAttribute('aria-disabled'),
      `open ${cov.open}; ${Math.round(cov.getBoundingClientRect().height)}px`);
    const below = cov.getBoundingClientRect();
    check('…under the question, in one column',
      below.top >= doc.querySelector('.question-card').getBoundingClientRect().bottom);
    summary.click();
    await settle(50);
    check('…and its summary opens it', cov.open && visible($('coverage')));
    summary.click();
    await settle(50);
    check('…and closes it again', !cov.open);

    // ── the compact meter at 360px ───────────────────────────────────────────
    frame.width = 360;
    await settle(150);
    const meter = $('meter').getBoundingClientRect();
    const back = $('b-library').getBoundingClientRect();
    const settings = $('b-settings').getBoundingClientRect();
    const segs = segments().map((s) => s.getBoundingClientRect());
    check('at 360px the meter sits in the top app bar, between back and settings',
      visible($('meter')) && meter.left >= back.right && meter.right <= settings.left
        && Math.abs((meter.top + meter.bottom) / 2 - (back.top + back.bottom) / 2) < 4,
      `back ${Math.round(back.right)} meter ${Math.round(meter.left)}–${Math.round(meter.right)} settings ${Math.round(settings.left)}`);
    check('…with all seven segments drawn',
      segs.length === 7 && segs.every((r) => r.width >= 4 && r.height > 0),
      segs.map((r) => Math.round(r.width)).join(','));
    check('…and nothing scrolls sideways',
      doc.documentElement.scrollWidth <= doc.documentElement.clientWidth,
      `${doc.documentElement.scrollWidth}/${doc.documentElement.clientWidth}`);
    const title = doc.querySelector('.bar h1');
    const titleSession = await loadSession(ID);
    const titleLine = parseFloat(win.getComputedStyle(title).lineHeight);
    const titleBox = title.getBoundingClientRect();
    check('…and the complete idea title remains accessible while its one-line display can truncate',
      title.getClientRects().length === 1 && titleBox.height <= titleLine * 1.2
        && titleBox.width > 0 && titleBox.right <= meter.left + 0.5
        && title.textContent === sessionDisplayTitle(titleSession)
        && title.title === sessionDisplayTitle(titleSession)
        && win.getComputedStyle(title).textOverflow === 'ellipsis',
      `h1 ${Math.round(titleBox.width)}x${Math.round(titleBox.height)} (needs ${title.scrollWidth}), line ${titleLine}, meter left ${Math.round(meter.left)}`);

    // ── Done hides it ────────────────────────────────────────────────────────
    frame.width = 1280;
    await settle(100);
    $('b-wrap').click();
    const done = await until(() => !$('panel-done').hidden, 8000);
    check('writing it up reaches Done', !!done, JSON.stringify(meterState()));
    check('Done hides the coverage meter', !!done && !visible($('meter')));
    check('coverage: no uncaught async error', errors.length === 0, errors.join('\n'));
  } catch (error) {
    check('coverage probe', false, String(error?.stack || error) + '\n' + JSON.stringify(meterState()));
  } finally {
    frame.remove();
    await cleanStorage();
  }
}
