// The app shell. Everything platform-shaped lives here or under src/store/ and
// src/providers/; src/core/ and src/runtime/ stay clean and are lint-enforced to be.
//
// Deliberately no router. WebKit re-prompts for the microphone whenever the URL hash
// changes in a standalone home-screen app (bug 215884), and phase 3 puts a live mic in
// this page — so panels are toggled with `hidden` and the URL never moves.

import { createSession, openTurn, waiveDimension, skipQuestion } from '../core/session.js';
import { DIMENSIONS, getDimension } from '../core/dimensions.js';
import { coveragePercent, buildExport, exportFilename } from '../core/markdown.js';
import { SOFT_TURN_CEILING, HARD_TURN_CEILING } from '../core/engine.js';
import { seedTurn, submitAnswer, runTurn, resumeTurn } from '../runtime/turn.js';
import { runSynthesis } from '../runtime/synthesize.js';
import {
  createProvider, PROVIDER_CHOICES, defaultProviderKind, isLoopback,
} from '../providers/index.js';
import { saveSession, loadSession, listSessions, newSessionId } from '../store/sessions.js';
import {
  saveCredentials, loadCredentials, clearCredentials, maskKey,
  emptyKeyring, credsFor, withCreds, withStt, hasAnyKey,
} from '../store/secrets.js';
import { requestPersistence } from '../store/db.js';
import { createVoice, STT_PRESETS, primeSpeech, ttsSupported } from '../voice/index.js';
import { VERSION } from '../version.js';

const $ = (id) => document.getElementById(id);
const els = {};
for (const id of [
  'meter', 'meter-fill', 'meter-label', 'b-settings',
  'panel-setup', 'provider', 'provider-note', 'field-key', 'apikey', 'keylink',
  'field-base', 'baseurl', 'base-note', 'field-model', 'model', 'model-list', 'model-note',
  'b-start', 'b-check', 'resume', 'resume-rows',
  'stt', 'stt-note', 'field-sttkey', 'sttkey', 'sttkey-note', 'b-forget', 'version',
  'panel-interview', 'bridge', 'question', 'asking', 'chips', 'answer',
  'b-send', 'b-mic', 'b-skip', 'b-wrap', 'turnline', 'coverage',
  'listening', 'listening-label', 'pulse', 'handsfree', 'handsfree-wrap',
  'panel-done', 'done-title', 'done-meta', 'output',
  'b-copy', 'b-download', 'b-reopen', 'b-new', 'note', 'err',
]) els[id] = $(id);

const state = {
  session: null,
  provider: null,
  creds: null,
  busy: false,
  /** Set when a chip was tapped, so answerQuestion records the right provenance. */
  answerSource: 'typed',
  voice: null,
  handsFree: false,
  /** True while a hands-free cycle owns the turn, so nothing else drives it. */
  cycling: false,
};

const now = () => Date.now();

// ─────────────────────────────────────────────────────────────── plumbing

function show(panel) {
  for (const p of ['panel-setup', 'panel-interview', 'panel-done']) els[p].hidden = p !== panel;
}

function say(msg) {
  els.note.textContent = msg || '';
  els.note.hidden = !msg;
}

function fail(err) {
  const msg = err && err.message ? err.message : String(err || '');
  els.err.textContent = msg;
  els.err.hidden = !msg;
  if (msg) console.error(err);
}

function busy(on, label) {
  state.busy = on;
  els['b-send'].disabled = on;
  els['b-skip'].disabled = on;
  els['b-wrap'].disabled = on;
  els.asking.hidden = !on;
  if (label) els.asking.textContent = label;
}

/** Persist once per settled turn — runTurn bumps `rev` five or six times internally. */
async function persist() {
  try {
    await saveSession(state.session);
  } catch (e) {
    say(`This session could not be saved to this device (${e.message}). Export before you close the tab.`);
  }
}

// ────────────────────────────────────────────────────────────── settings

function renderProviderChoices() {
  els.provider.innerHTML = '';
  for (const c of PROVIDER_CHOICES) {
    const o = document.createElement('option');
    o.value = c.id;
    o.textContent = c.label;
    els.provider.append(o);
  }
  els.provider.value = (state.creds && state.creds.active) || defaultProviderKind();
  onProviderChange();
}

function currentChoice() {
  return PROVIDER_CHOICES.find((c) => c.id === els.provider.value) || PROVIDER_CHOICES[0];
}

function onProviderChange() {
  const c = currentChoice();
  const saved = credsFor(state.creds, c.id);

  els['field-key'].hidden = !c.needsKey;
  // A local server is the case where the address and the model are worth asking about.
  // This used to read `c.id !== 'custom'` against a registry that offered no such choice,
  // so the field could never appear at all.
  els['field-base'].hidden = !c.local;
  els['field-model'].hidden = !c.discoverModels;
  els['provider-note'].textContent = c.note || '';
  els.keylink.href = c.keyUrl || '#';
  els.keylink.hidden = !c.keyUrl;

  els.baseurl.value = saved.baseUrl || (c.id === 'custom' ? '' : defaultBaseUrl(c.id));
  els.model.value = saved.model || '';
  // A model list read off a different server is a lie about this one.
  els['model-list'].innerHTML = '';
  els['model-note'].textContent = '';

  els.apikey.placeholder = saved.apiKey ? `saved: ${maskKey(saved.apiKey)}` : 'paste your key';
  els['b-check'].textContent = c.needsKey ? 'Check the key' : 'Check the connection';
  // Only offer to forget a key when there is one; an inert button is worse than none.
  els['b-forget'].hidden = !hasAnyKey(state.creds);
  onBaseChange();
}

/** The preset's own address, so the field starts usable rather than empty. */
function defaultBaseUrl(id) {
  const preset = PROVIDER_CHOICES.find((c) => c.id === id);
  return (preset && preset.defaultBaseUrl) || '';
}

/**
 * Refuse a remote address as it is typed, rather than at the first call. The CSP will not
 * permit it anyway, and a CSP refusal reaches the page as an opaque failure with no
 * explanation — so saying it here is the only place the user can learn why.
 */
function onBaseChange() {
  const c = currentChoice();
  const typed = els.baseurl.value.trim();
  if (!c.local || !typed) {
    els['base-note'].textContent = '';
    els['b-start'].disabled = false;
    els['b-check'].disabled = false;
    return;
  }

  // `[::1]` really is loopback, so isLoopback says yes — but CSP's host-source grammar has
  // no IPv6-literal form, so the browser drops that entry from connect-src without a word
  // and blocks the request anyway. Saying so beats letting a correct-looking address fail
  // as an unexplained network error.
  const ipv6 = /^\w+:\/\/\[/.test(typed);
  const bad = !isLoopback(typed);

  els['base-note'].textContent = bad
    ? 'That address is not on this machine. Local servers only — localhost or 127.0.0.1.'
    : ipv6
      ? 'This page cannot reach an IPv6 address in brackets — write it as 127.0.0.1 instead.'
      : '';
  els['b-start'].disabled = bad || ipv6;
  els['b-check'].disabled = bad || ipv6;
}

/**
 * Ask the server what it actually has, instead of guessing on its behalf.
 *
 * Only ever on an explicit press. From a hosted page this request is what triggers
 * Chrome's local-network permission prompt, and firing a permission prompt off a dropdown
 * change is how you teach someone to deny it.
 */
async function refreshModels() {
  const c = currentChoice();
  if (!c.discoverModels) return;
  els['model-note'].textContent = 'Reading the model list…';
  try {
    const provider = await buildProvider({ requireModel: false });
    const names = await provider.listModels();
    fillModelList(names);
    els['model-note'].textContent = names.length
      ? `${names.length} model${names.length === 1 ? '' : 's'} installed.`
      : 'That server answered, but it has no models. Pull one first.';
  } catch (err) {
    // Leave whatever they typed alone — it may well be right, and the server may simply
    // not answer /models.
    els['model-note'].textContent = `${err && err.message ? err.message : err} ` +
      'You can still type the model name yourself.';
  }
}

function fillModelList(names) {
  els['model-list'].innerHTML = '';
  for (const name of names) {
    const o = document.createElement('option');
    o.value = name;
    els['model-list'].append(o);
  }
  // One installed model is not a choice, so make it for them.
  if (!els.model.value.trim() && names.length === 1) els.model.value = names[0];
}

/**
 * Delete the stored key. The whole security story of this app is that the key lives on
 * your device, which is only honest if there is a way to take it off again.
 */
async function forgetKey() {
  await clearCredentials();
  state.creds = emptyKeyring();
  els.apikey.value = '';
  els.sttkey.value = '';
  els.model.value = '';
  els['model-list'].innerHTML = '';
  fail(null);
  onProviderChange();
  onSttChange();
  say('Key deleted from this device.');
}

/**
 * The form, folded into the keyring rather than replacing it. Every other provider's
 * record survives, which is what stops switching provider destroying the previous key.
 */
function readRingFromForm() {
  const c = currentChoice();
  const saved = credsFor(state.creds, c.id);
  const apiKey = els.apikey.value.trim() || saved.apiKey || '';
  const sttKind = els.stt.value;

  let ring = withCreds(state.creds || emptyKeyring(), c.id, {
    apiKey,
    baseUrl: els.baseurl.value.trim(),
    model: els.model.value.trim(),
  });
  ring = withStt(ring, {
    kind: sttKind,
    // Groq and OpenAI serve both chat and transcription, so when the inference provider
    // is one of them the same key covers dictation and there is nothing extra to paste.
    apiKey: sttKind === c.id ? apiKey
      : (els.sttkey.value.trim() || (state.creds && state.creds.stt && state.creds.stt.apiKey) || ''),
  });
  return ring;
}

function onSttChange() {
  const kind = els.stt.value;
  const preset = STT_PRESETS[kind];
  const sharesKey = kind === currentChoice().id;
  els['field-sttkey'].hidden = !preset || sharesKey;
  els['sttkey-note'].textContent = preset ? `Sent only to ${new URL(preset.baseUrl).host}.` : '';
  els['stt-note'].textContent =
    kind === 'browser'
      ? 'Free, and shows words as you speak — but it does not work in an installed iPhone app, ' +
        'in Edge, or in Firefox. Pick Whisper below if you want dictation everywhere.'
      : kind === 'off' ? ''
      : `${preset.note}${sharesKey ? ' Uses the same key as above.' : ''}`;
}

async function buildProvider({ requireModel = true } = {}) {
  const c = currentChoice();
  const ring = readRingFromForm();
  const creds = credsFor(ring, c.id);
  if (c.needsKey && !creds.apiKey) throw new Error('This provider needs an API key.');

  const provider = await createProvider({
    kind: c.id,
    apiKey: creds.apiKey,
    baseUrl: creds.baseUrl || undefined,
    model: creds.model || undefined,
    // Reading the model list is how you find out what to put in the model box, so that
    // one call cannot be the thing that insists the box is already filled.
    modelRequired: requireModel ? undefined : false,
  });

  state.creds = ring;
  state.provider = provider;
  // Always, not only when there is a key. Guarding this on `apiKey` meant choosing a local
  // provider was never persisted at all: pick Ollama, reload, and the choice was gone.
  await saveCredentials(ring);
  return provider;
}

// ───────────────────────────────────────────────────────────── interview

function renderCoverage() {
  const s = state.session;
  els.coverage.innerHTML = '';
  for (const d of DIMENSIONS) {
    const c = s.coverage[d.id];
    const row = document.createElement('div');
    row.className = 'row';

    const name = document.createElement('span');
    name.textContent = d.label;

    const right = document.createElement('span');
    const lvl = document.createElement('span');
    lvl.className = `lvl ${c.status === 'probing' ? c.level : ''}`;
    lvl.textContent = c.status === 'probing' ? c.level : c.status;
    right.append(lvl);

    if (c.status === 'probing') {
      const skip = document.createElement('button');
      skip.type = 'button';
      skip.className = 'ghost small';
      skip.textContent = 'not relevant';
      skip.title = `Stop asking about ${d.label.toLowerCase()}`;
      skip.onclick = async () => {
        state.session = waiveDimension(state.session, d.id, null, now());
        await persist();
        render();
      };
      right.append(' ');
      right.append(skip);
    }

    const gap = document.createElement('span');
    gap.className = 'gap';
    gap.textContent = c.status === 'probing' && c.gap ? c.gap : '';

    row.append(name, right, gap);
    els.coverage.append(row);
  }
}

function render() {
  const s = state.session;
  const pct = coveragePercent(s);
  els.meter.hidden = false;
  els['meter-fill'].style.width = `${pct}%`;
  els['meter-label'].textContent = `${pct}%`;

  const open = openTurn(s);
  if (open) {
    els.bridge.textContent = open.bridge || '';
    els.bridge.hidden = !open.bridge;
    els.question.textContent = open.question;
    renderChips(open.chips || []);
    const n = s.turns.length;
    const dim = open.dimension ? getDimension(open.dimension).label : '';
    els.turnline.textContent =
      `question ${n} of up to ${HARD_TURN_CEILING}` +
      (dim ? ` · ${dim.toLowerCase()}` : '') +
      (open.questionSource === 'bank' ? ' · from the built-in checklist' : '');
  }
  els['b-wrap'].hidden = !(s.turns.length >= 4);
  renderCoverage();
}

function renderChips(chips) {
  els.chips.innerHTML = '';
  for (const text of chips) {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'chip';
    b.textContent = text;
    b.onclick = () => {
      // A tapped chip is Claude's wording, not the user's, and the ratchet caps it at
      // `partial` for exactly that reason — unless they edit it, which flips it back.
      els.answer.value = text;
      state.answerSource = 'chip';
      els.answer.focus();
    };
    els.chips.append(b);
  }
}

async function nextQuestion() {
  busy(true, 'thinking of the next question…');
  fail(null);
  try {
    const out = await runTurn(state.session, { provider: state.provider, now: now() });
    state.session = out.session;
    await persist();

    if (out.error) {
      fail(`${out.error.message} — falling back to the built-in checklist for this question.`);
    }
    if (!out.turn) {
      await wrapUp(out.wrap === 'exhausted'
        ? 'The interview ran out of questions.'
        : 'That is everything worth asking.');
      return;
    }
    render();
    if (out.wrap && !state.session.wrapOffered) {
      say(wrapMessage(out.wrap));
    }
    els.answer.focus();
  } catch (e) {
    fail(e);
  } finally {
    busy(false);
  }
  // Guarded, so the hands-free driver's own call to nextQuestion does not re-enter it.
  if (state.handsFree && !state.cycling) runHandsFree();
}

function wrapMessage(reason) {
  if (reason === 'coverage') return 'There is enough here to write it up whenever you like.';
  if (reason === 'exhausted') return 'The last couple of answers did not add much — worth wrapping up.';
  if (reason === 'soft_ceiling') return `That is ${SOFT_TURN_CEILING} questions. Wrap up whenever you like.`;
  if (reason === 'hard_ceiling') return 'That is the last question.';
  return '';
}

/** Record an answer and ask the next question. The hands-free loop calls this directly. */
async function submitAndAdvance(text, source) {
  state.session = submitAnswer(state.session, { text, source, now: now() });
  els.answer.value = '';
  state.answerSource = 'typed';
  say('');
  await persist();
  await nextQuestion();
}

async function send(text, source) {
  const body = String(text == null ? els.answer.value : text).trim();
  if (!body || state.busy) return;
  await submitAndAdvance(body, source || state.answerSource);
}

// ───────────────────────────────────────────────────────────────── voice

/**
 * Built once per interview, on the tap that starts it — which is also the only moment
 * iOS will let us unlock speech synthesis, and the gesture the microphone permission
 * prompt needs to be attached to.
 */
async function setupVoice() {
  const { kind: sttKind, apiKey: sttKey } = (state.creds && state.creds.stt) || {};
  primeSpeech();
  try {
    state.voice = await createVoice({
      stt: STT_PRESETS[sttKind] && sttKey ? { kind: sttKind, apiKey: sttKey } : null,
      preferRecorder: sttKind === 'groq' || sttKind === 'openai',
    });
  } catch {
    state.voice = null;
  }
  const on = state.voice && state.voice.available;
  els['b-mic'].hidden = !on;
  els['handsfree-wrap'].hidden = !(on && ttsSupported());
  if (on && state.voice.mode === 'recorder') {
    // Say it once, plainly, rather than surprising anyone with a bill.
    say(`Dictation goes through ${state.voice.transcriberLabel} — roughly a penny for a whole interview.`);
  } else if (state.voice && state.voice.unavailableReason && els.stt.value !== 'off') {
    say(state.voice.unavailableReason);
  }
}

async function listenOnce({ prompt, autoStop }) {
  els.listening.hidden = false;
  els['listening-label'].textContent = state.voice.mode === 'recorder'
    ? 'Listening — stop talking when you’re done.'
    : 'Listening…';
  els['b-mic'].textContent = 'Stop listening';
  try {
    return await state.voice.listen({
      prompt,
      autoStop,
      onInterim: (t) => { els.answer.value = t; },
      onLevel: (rms) => {
        els.pulse.style.setProperty('--level', String(0.6 + Math.min(1.7, rms * 16)));
      },
    });
  } finally {
    els.listening.hidden = true;
    els['b-mic'].textContent = 'Answer out loud';
    els.pulse.style.removeProperty('--level');
  }
}

/**
 * The hands-free cycle: read the question, listen, submit, repeat.
 *
 * A single driver loop rather than a chain of callbacks, because the alternative is
 * mutual recursion between "ask" and "answer" that is one stray await away from running
 * two microphones at once. `state.cycling` is what stops nextQuestion re-entering it.
 */
async function runHandsFree() {
  if (state.cycling || !state.handsFree || !state.voice) return;
  state.cycling = true;
  try {
    while (state.handsFree && !state.busy) {
      const turn = openTurn(state.session);
      if (!turn) break;

      await state.voice.speak(spoken(turn));
      if (!state.handsFree) break;

      let heard = '';
      try {
        heard = await listenOnce({ prompt: turn.question, autoStop: true });
      } catch (e) {
        fail(e);
        break;
      }
      if (!state.handsFree) break;
      if (!heard.trim()) {
        say('I didn’t catch that. Type your answer, or tap the mic to try again.');
        break;
      }
      await submitAndAdvance(heard, 'voice');
    }
  } finally {
    state.cycling = false;
  }
}

/** What gets read aloud: the bridge and the question, never the chips. */
function spoken(turn) {
  return turn.bridge ? `${turn.bridge} ${turn.question}` : turn.question;
}

/** The open question, used to prime the transcriber with this turn's vocabulary. */
function currentQuestion() {
  const t = state.session && openTurn(state.session);
  return t ? t.question : '';
}

function teardownVoice() {
  setHandsFree(false);
  if (state.voice) state.voice.dispose();
  state.voice = null;
  els['b-mic'].hidden = true;
  els['handsfree-wrap'].hidden = true;
  els.listening.hidden = true;
}

function setHandsFree(on) {
  state.handsFree = on;
  els.handsfree.checked = on;
  if (!on) {
    if (state.voice) { state.voice.cancelSpeech(); state.voice.stop(); }
    return;
  }
  runHandsFree();
}

// ────────────────────────────────────────────────────────────── wrap-up

async function wrapUp(note) {
  teardownVoice();
  show('panel-done');
  els['done-title'].textContent = 'Writing it up…';
  els.output.textContent = '';
  els['done-meta'].textContent = note || '';

  const out = await runSynthesis(state.session, { provider: state.provider, now: now() });
  state.session = out.session;
  await persist();

  if (!out.ok) {
    fail(out.error
      ? `The wrap-up call failed: ${out.error.message}`
      : 'The model returned no usable prompt.');
    // markdown.js renders a complete document with no synthesis at all, so the
    // transcript and open questions are never lost to a failed wrap-up.
  }
  renderDone();
}

function renderDone() {
  const s = state.session;
  els['done-title'].textContent = s.title || 'Your refined prompt';
  const degraded = s.turns.some((t) => t.questionSource === 'bank');
  els['done-meta'].textContent =
    `${s.turns.filter((t) => t.answer || t.skipped).length} questions · coverage ${coveragePercent(s)}%` +
    (degraded ? ' · some questions came from the built-in checklist' : '');
  els.output.textContent = buildExport(s, {
    mode: s.synthesis.text ? 'claude' : 'checklist',
    note: s.synthesis.text ? null : 'The refined prompt could not be generated, but nothing else was lost.',
  });
}

function download() {
  const blob = new Blob([els.output.textContent], { type: 'text/markdown' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = exportFilename(state.session);
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

// ──────────────────────────────────────────────────────────────── boot

async function startInterview() {
  fail(null);
  try {
    await buildProvider();
  } catch (e) {
    fail(e);
    return;
  }
  state.session = seedTurn(createSession({ id: newSessionId(), now: now() }), { now: now() });
  await persist();
  show('panel-interview');
  render();
  await setupVoice();
  els.answer.focus();
}

async function resumeInterview(id) {
  const s = await loadSession(id);
  if (!s) { fail('That session was written by a newer version of IdeaForge.'); return; }
  try {
    await buildProvider();
  } catch (e) {
    say(`Continuing without a model (${e.message}). Questions will come from the built-in checklist.`);
    state.provider = null;
  }
  state.session = s;
  show('panel-interview');
  await setupVoice();

  if (s.pending) {
    busy(true, 'picking up where the last question left off…');
    try {
      const out = await resumeTurn(s, { provider: state.provider, now: now() });
      state.session = out.session;
      await persist();
    } catch (e) { fail(e); } finally { busy(false); }
  }
  if (s.status === 'done' && !openTurn(state.session)) { renderDone(); show('panel-done'); return; }
  if (!openTurn(state.session)) { await nextQuestion(); return; }
  render();
}

async function renderResumeList() {
  let rows;
  try { rows = await listSessions(); } catch { return; }
  const open = rows.filter((s) => s.turns.length > 1).slice(0, 5);
  els.resume.hidden = open.length === 0;
  els['resume-rows'].innerHTML = '';
  for (const s of open) {
    const row = document.createElement('div');
    row.className = 'row';
    const name = document.createElement('span');
    name.textContent = s.title || s.opening || '(no opening statement yet)';
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'ghost small';
    b.textContent = s.status === 'done' ? 'reopen' : 'continue';
    b.onclick = () => resumeInterview(s.id);
    const meta = document.createElement('span');
    meta.className = 'gap';
    meta.textContent = `${s.turns.length} questions · coverage ${coveragePercent(s)}%`;
    row.append(name, b, meta);
    els['resume-rows'].append(row);
  }
}

function bind() {
  els.provider.onchange = () => { onProviderChange(); onSttChange(); };
  els.stt.onchange = onSttChange;
  els['b-start'].onclick = startInterview;
  els['b-mic'].onclick = async () => {
    if (!state.voice) return;
    if (!els.listening.hidden) { state.voice.stop(); return; }
    setHandsFree(false);                 // a manual tap takes the wheel back
    try {
      // autoStop false: the button is press-to-talk, so the user decides when they are
      // done. Whatever came back lands in the box for them to edit before sending.
      const heard = await listenOnce({ prompt: currentQuestion(), autoStop: false });
      if (heard.trim()) { els.answer.value = heard; state.answerSource = 'voice'; }
    } catch (e) { fail(e); }
  };
  els.handsfree.onchange = () => setHandsFree(els.handsfree.checked);
  els.baseurl.oninput = onBaseChange;
  els['b-check'].onclick = async () => {
    fail(null); say('Checking…');
    const c = currentChoice();
    try {
      if (c.discoverModels) {
        // For a local server "does the key work" is the wrong question — it has no key.
        // What you actually want to know is whether it answers, and what it can run.
        await refreshModels();
        say('That server answered.');
      } else {
        const p = await buildProvider();
        await p.validateKey();
        say('That key works.');
      }
    } catch (e) { say(''); fail(e); }
  };
  els['b-forget'].onclick = forgetKey;
  els['b-send'].onclick = () => send();
  els['b-skip'].onclick = async () => {
    if (state.busy || !openTurn(state.session)) return;
    state.session = skipQuestion(state.session, { now: now() });
    els.answer.value = '';
    say('');
    await persist();
    await nextQuestion();
  };
  els['b-wrap'].onclick = () => wrapUp('Wrapped up early, at your request.');
  els['b-settings'].onclick = () => { teardownVoice(); show('panel-setup'); renderResumeList(); };
  els['b-copy'].onclick = async () => {
    try {
      await navigator.clipboard.writeText(els.output.textContent);
      say('Copied.');
    } catch { say('Could not reach the clipboard — select the text above instead.'); }
  };
  els['b-download'].onclick = download;
  els['b-new'].onclick = () => { teardownVoice(); show('panel-setup'); renderResumeList(); };
  els['b-reopen'].onclick = async () => { show('panel-interview'); await nextQuestion(); };

  // Ctrl/Cmd+Enter sends; a plain Enter must still make a paragraph, because dictated
  // answers are long and people press Enter mid-thought.
  els.answer.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); send(); }
  });
  // Editing a chip makes the words theirs again, which un-caps the coverage grade.
  els.answer.addEventListener('input', () => {
    if (state.answerSource === 'chip') state.answerSource = 'typed';
  });
}

async function boot() {
  bind();
  try { state.creds = await loadCredentials(); } catch { /* first run, or storage blocked */ }
  if (state.creds && state.creds.stt && state.creds.stt.kind) {
    els.stt.value = state.creds.stt.kind;
  }
  renderProviderChoices();
  onSttChange();
  els.version.textContent = `IdeaForge v${VERSION}`;
  await renderResumeList();
  requestPersistence();
  show('panel-setup');

  // Installability and an offline cold start. Needs a secure context, so it simply does
  // not register on a file:// open — which is fine, the app still runs.
  if ('serviceWorker' in navigator && location.protocol !== 'file:') {
    navigator.serviceWorker.register('./sw.js').catch(() => { /* not fatal */ });
  }
}

boot();
