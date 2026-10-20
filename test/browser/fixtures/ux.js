import { createVoiceStage } from '../../../src/ui/voice-stage.js';
import { listSessions, saveSession } from '../../../src/store/sessions.js';
import { acknowledgeInference } from './inference.js';

const visible = (el) => Boolean(el && !el.hidden && el.getClientRects().length);
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

export async function requireUxFont(doc) {
  await awaitUx(() => doc.getElementById('font-styles')?.rel === 'stylesheet'
    && [...doc.fonts].some((face) => face.family.includes('IBM Plex Sans')), 'font stylesheet');
  await doc.fonts.ready;
  const faces = await doc.fonts.load('600 16px "IBM Plex Sans"');
  if (!faces.some((face) => face.status === 'loaded')) {
    throw new Error('IBM Plex Sans/600 did not load; refusing fallback-font evidence');
  }
}

export async function awaitUx(predicate, label, ms = 8000) {
  const end = performance.now() + ms;
  do {
    if (await predicate()) return;
    await sleep(25);
  } while (performance.now() < end);
  throw new Error(`UX fixture did not reach ${label}`);
}

/** Runs before app modules; none of the scripted model's requests leave this origin. */
export function uxBootstrap({ now, theme, state }) {
  return `(() => {
    Date.now = () => ${Number(now)};
    if (document.documentElement) document.documentElement.dataset.theme = ${JSON.stringify(theme)};
    localStorage.setItem('ideaforge.prefs', JSON.stringify({
      theme: ${JSON.stringify(theme)}, handsFree: false, readback: false
    }));
    window.__uxModel = { calls: 0, mode: ${JSON.stringify(state)}, release: null };
    window.claude = { use: async () => async (_prompt, options) => {
      const model = window.__uxModel;
      model.calls++;
      if (model.mode === 'busy-turn' || model.mode === 'writing') {
        await new Promise((resolve) => { model.release = resolve; });
      }
      if (options.modelTier === 'complex') {
        if (model.mode === 'synthesis-failure') throw Object.assign(
          new Error('Scripted write-up failure'), { code: 'bad_response' });
        return { text: JSON.stringify({
          title: 'UX fixture notebook', prompt: '## Task\\nBuild a local notebook.',
          assumptions: [], openQuestions: []
        }), modelTierApplied: 'scripted-ux' };
      }
      return { text: JSON.stringify({
        question: 'What would a useful first week with this notebook look like?',
        move: 'concretize', chips: ['Keep the original notes'], coverage: {}, facts: []
      }), modelTierApplied: 'scripted-ux' };
    }};
    if (${JSON.stringify(state)} === 'unavailable') {
      Object.defineProperty(window, 'indexedDB', { configurable: true, get() {
        throw new DOMException('Scripted unavailable storage', 'SecurityError');
      }});
    }
  })();`;
}

export async function seedUxDataset(rows) {
  const held = new Set((await listSessions()).map((s) => s.id));
  for (const row of rows) {
    if (held.has(row.id)) throw new Error(`Refusing to overwrite an existing UX fixture id: ${row.id}`);
  }
  for (const row of rows) await saveSession(row);
}

export async function presentUxState(win, scene, fixtures) {
  if (scene.platform === 'android') throw new Error(`Requested UX state is native-only: ${scene.id}`);
  const doc = win.document;
  const $ = (id) => doc.getElementById(id);
  await awaitUx(() => $('version')?.textContent && $('provider')?.options.length, 'app boot');
  const change = (el, value, event = 'change') => {
    if (!el) throw new Error(`Missing fixture form control for ${scene.id}`);
    el.value = value;
    el.dispatchEvent(new win.Event(event, { bubbles: true }));
  };
  const click = (el) => {
    if (!visible(el) || el.disabled) throw new Error(`Fixture action is unavailable for ${scene.id}`);
    el.click();
  };
  if (scene.id !== 'fresh') {
    change($('provider'), 'artifact');
    change($('stt'), 'off');
  }
  if (scene.evidence === 'isolated-component') {
    // A shipped renderer/markup fixture, not an invented live capture or a second interview.
    const stage = $('voice-stage').cloneNode(true);
    stage.hidden = false;
    const wrap = doc.createElement('main');
    wrap.className = 'wrap';
    wrap.append(stage);
    doc.body.replaceChildren(wrap);
    doc.body.dataset.panel = 'interview';
    doc.body.dataset.voiceStage = 'active';
    createVoiceStage(stage).render({
      phase: scene.phase, question: fixtures.text.question, transcript: fixtures.text.answer,
      backend: 'Isolated presentation fixture',
      detail: 'No microphone level reading is supplied. No microphone or speech service is running.',
    });
    return ['#voice-status', '#voice-question', '#voice-transcript', '#b-voice-pause', '#b-voice-exit'];
  }
  if (scene.surface === 'interview' || ['writing', 'synthesis-failure'].includes(scene.id)) {
    await acknowledgeInference(win);
  }
  if (scene.surface === 'library') {
    if (!visible($('panel-library'))) click($('b-library'));
    await awaitUx(() => visible($('panel-library'))
      && ['zero', 'ready', 'unavailable'].includes($('panel-library').dataset.libraryState), 'Ideas state');
    if (scene.id === 'library-archived') change($('library-status'), 'archived');
    if (scene.id === 'filtered') change($('library-search'), 'no-fixture-can-match-this', 'input');
    if (scene.id === 'unavailable') {
      if ($('panel-library').dataset.libraryState !== 'unavailable'
        || visible($('library-zero'))) throw new Error('Unavailable storage became an empty library');
    } else if (scene.id === 'empty') {
      if (!visible($('library-zero'))) throw new Error('Empty fixture did not show the true empty state');
    } else if (scene.id === 'filtered') {
      if (!visible($('library-empty'))) throw new Error('Filtered fixture did not show the filtered state');
    } else {
      const expected = scene.id === 'library-archived'
        ? fixtures.datasets[scene.dataset].filter((s) => s.archivedAt).length
        : fixtures.datasets[scene.dataset].length;
      const actual = $('library-rows').querySelectorAll('.idea-card').length;
      if (actual !== expected) {
        throw new Error(`Missing rows in ${scene.id}: ${actual} of ${expected}`);
      }
    }
    if (scene.id === 'empty') return ['#panel-library h2', '#library-zero h3', '#b-library-zero-new'];
    if (scene.id === 'unavailable') return ['#panel-library h2', '#b-library-new', '#err'];
    const selectors = ['#panel-library h2', '#b-library-new', '#library-count'];
    if (scene.id !== 'filtered') {
      selectors.push('#library-rows .idea-card:first-child .idea-actions > button:first-child');
    }
    return selectors;
  }
  if (scene.sessionId) {
    const card = doc.querySelector(`.idea-card[data-session-id="${scene.sessionId}"]`);
    const primary = card?.querySelector('.idea-actions > button');
    click(primary);
    await awaitUx(() => visible($('panel-interview')) || visible($('panel-done')), 'opened idea');
  } else if (!['fresh', 'ready'].includes(scene.id)) {
    click($('b-start'));
    await awaitUx(() => visible($('panel-interview')) && !$('answer').disabled, 'typed interview');
  }
  if (scene.id === 'bank') {
    if (!/checklist/i.test($('turnline').textContent)) throw new Error('The bank fixture hid its question source');
  }
  if (scene.id === 'interview-long') {
    if ($('question').textContent !== fixtures.text.question || $('answer').value !== fixtures.text.answer) {
      throw new Error('Long interview text/draft is missing');
    }
  }
  if (scene.id === 'save-failure') {
    const put = win.IDBObjectStore.prototype.put;
    win.IDBObjectStore.prototype.put = function (...args) {
      if (this.name === 'sessions') throw new win.DOMException('Scripted full disk', 'QuotaExceededError');
      return put.apply(this, args);
    };
  }
  if (['save-failure', 'busy-turn'].includes(scene.id)) {
    change($('answer'), fixtures.text.answer, 'input');
    click($('b-send'));
    await awaitUx(() => scene.id === 'busy-turn' ? visible($('asking'))
      : visible($('err')) && /could not be saved/.test($('err').textContent), scene.id);
  }
  if (['writing', 'synthesis-failure'].includes(scene.id)) {
    click($('b-wrap'));
    await awaitUx(() => visible($('panel-done')) && (scene.id === 'writing'
      ? $('output').getAttribute('aria-busy') === 'true'
      : $('output').dataset.source && visible($('err'))), scene.id);
  }
  if (scene.surface === 'result') {
    await awaitUx(() => visible($('panel-done')) && (scene.id === 'writing'
      || $('output').dataset.source), 'document state');
    if (scene.id === 'result-long' && (!$('output').querySelector('table') || !$('output').querySelector('pre'))) {
      throw new Error('The long document fixture is missing its table/code');
    }
    return scene.id === 'writing' ? ['#done-title', '#output']
      : ['#done-title', '#output', '#done-actions > button:not(.done-more)'];
  }
  if (scene.surface === 'interview') return ['#question', '#answer', '#b-send', '#b-skip'];
  return ['#panel-setup h2', ...($('connect').hasAttribute('data-collapsed')
    ? ['#connect-summary-text', '#b-connect-change'] : ['#provider']),
  '#b-start-voice', '#b-start'];
}

export function assertUxGeometry(measurement) {
  if (!measurement.state || !measurement.controls.length) throw new Error('Missing requested capture state or controls');
  if (![measurement.width, measurement.height, measurement.pageWidth].every(Number.isFinite)) {
    throw new Error('Invalid capture geometry');
  }
  if (measurement.pageWidth > measurement.width + 1) {
    throw new Error(`Page-wide overflow in ${measurement.state}: ${measurement.pageWidth}px at ${measurement.width}px`
      + (measurement.overflow?.length ? ` (${measurement.overflow.join(', ')})` : ''));
  }
  for (const control of measurement.controls) {
    if (!control.visible || !control.reachable || ![control.width, control.height].every(Number.isFinite)
      || control.width <= 0 || control.height <= 0) {
      throw new Error(`Clipped or inaccessible ${control.selector} in ${measurement.state}`);
    }
  }
  return measurement;
}

export function measureUxState(win, state, selectors) {
  const doc = win.document;
  const original = { x: win.scrollX, y: win.scrollY };
  const controls = selectors.map((selector) => {
    const el = doc.querySelector(selector);
    if (!el) throw new Error(`Missing ${selector} in ${state}`);
    el.scrollIntoView({ block: 'center', inline: 'nearest', behavior: 'instant' });
    const box = el.getBoundingClientRect();
    const x = Math.max(0, Math.min(win.innerWidth - 1, box.x + box.width / 2));
    const y = Math.max(0, Math.min(win.innerHeight - 1, box.y + box.height / 2));
    const top = doc.elementFromPoint(x, y);
    const interactive = el.matches('button, input, textarea, select');
    let exposed = { left: box.left, right: box.right, top: box.top, bottom: box.bottom };
    for (let parent = el.parentElement; parent; parent = parent.parentElement) {
      const style = win.getComputedStyle(parent);
      const paint = /\bpaint\b/.test(style.contain);
      const clipX = paint || /^(auto|scroll|hidden|clip)$/.test(style.overflowX);
      const clipY = paint || /^(auto|scroll|hidden|clip)$/.test(style.overflowY);
      if (!clipX && !clipY) continue;
      const rect = parent.getBoundingClientRect();
      if (clipX) {
        exposed.left = Math.max(exposed.left, rect.left + parent.clientLeft);
        exposed.right = Math.min(exposed.right, rect.left + parent.clientLeft + parent.clientWidth);
      }
      if (clipY) {
        exposed.top = Math.max(exposed.top, rect.top + parent.clientTop);
        exposed.bottom = Math.min(exposed.bottom, rect.top + parent.clientTop + parent.clientHeight);
      }
    }
    return {
      selector, visible: visible(el),
      reachable: box.left >= -1 && box.right <= win.innerWidth + 1
        && (interactive ? box.top >= -1 && box.bottom <= win.innerHeight + 1
          && exposed.right - exposed.left >= box.width - 1
          && exposed.bottom - exposed.top >= box.height - 1
          && (el === top || el.contains(top) || el.disabled === true)
          : box.bottom > 0 && box.top < win.innerHeight),
      x: box.x, y: box.y, width: box.width, height: box.height,
    };
  });
  win.scrollTo(original.x, original.y);
  return assertUxGeometry({
    state, width: win.innerWidth, height: win.innerHeight,
    pageWidth: doc.documentElement.scrollWidth, pageHeight: doc.documentElement.scrollHeight,
    fontSize: parseFloat(win.getComputedStyle(doc.body).fontSize),
    fontFamily: win.getComputedStyle(doc.body).fontFamily,
    theme: doc.documentElement.dataset.theme, controls,
    overflow: [...doc.body.querySelectorAll('*')].filter((el) => {
      if (!visible(el)) return false;
      const box = el.getBoundingClientRect();
      return box.left < -1 || box.right > win.innerWidth + 1;
    }).slice(0, 5).map((el) => el.id ? `#${el.id}` : `${el.tagName.toLowerCase()}.${el.className}`),
  });
}
