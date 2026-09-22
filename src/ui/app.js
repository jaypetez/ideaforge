// The app shell. Everything platform-shaped lives here or under src/store/ and
// src/providers/; src/core/ and src/runtime/ stay clean and are lint-enforced to be.
//
// Deliberately no router. WebKit re-prompts for the microphone whenever the URL hash
// changes in a standalone home-screen app (bug 215884), and phase 3 puts a live mic in
// this page — so panels are toggled with `hidden` and the URL never moves.

import {
  archiveSession, createSession, openTurn, renameSession, reopen, restoreSession,
  sessionDisplayTitle, setDraftAnswer, setSessionTags, setStatusField,
  waiveDimension, skipQuestion, setWrapOffered,
} from '../core/session.js';
import { DIMENSIONS, getDimension } from '../core/dimensions.js';
import {
  coveragePercent, buildExport, exportFilename, forSpeech, speechChunks, renderBlocks,
} from '../core/markdown.js';
import { HARD_TURN_CEILING, wrapAdvisory, shouldOfferWrap } from '../core/engine.js';
import {
  DRIVING, parseSpeech, matchAffirmation, normalizeTrigger, triggerWarning,
} from '../core/driving.js';
import { seedTurn, submitAnswer, runTurn, resumeTurn } from '../runtime/turn.js';
import { resumeSynthesis, runSynthesis } from '../runtime/synthesize.js';
import { createDriveLoop } from '../runtime/drive.js';
import {
  createProvider, PROVIDER_CHOICES, defaultProviderKind, isLoopback, presetModels,
} from '../providers/index.js';
import {
  deleteSession, importSessions, saveSession, loadSession, listSessions, newSessionId,
} from '../store/sessions.js';
import {
  saveCredentials, loadCredentials, clearCredentials, maskKey,
  emptyKeyring, credsFor, withCreds, withStt, hasAnyKey,
} from '../store/secrets.js';
import { requestPersistence } from '../store/db.js';
import { loadPrefs, savePrefs } from '../store/prefs.js';
import { createVoice, STT_PRESETS, primeSpeech, ttsSupported } from '../voice/index.js';
import { DRIVING_GATE, CONFIRM_GATE } from '../voice/vad.js';
import {
  backupFilename, buildBackup, MAX_BACKUP_BYTES, parseBackup,
} from '../core/backup.js';
import { createInstallController } from './install.js';
import { createLibraryView } from './library.js';
import { downloadText, shareTextFile } from './share.js';
import { VERSION } from '../version.js';

const $ = (id) => document.getElementById(id);
const els = {};
for (const id of [
  'meter', 'meter-fill', 'meter-label', 'b-library', 'b-settings',
  'panel-setup', 'provider', 'provider-note', 'field-key', 'apikey', 'keylink',
  'field-base', 'baseurl', 'base-note', 'field-model', 'model', 'model-list', 'model-note',
  'field-wrapmodel', 'wrapmodel',
  'install-card', 'install-title', 'install-note', 'b-install',
  'b-start', 'b-check', 'resume', 'resume-rows', 'b-view-all',
  'stt', 'stt-note', 'field-sttkey', 'sttkey', 'sttkey-note', 'b-forget', 'version',
  'field-stopword', 'stopword', 'stopword-note',
  'panel-interview', 'bridge', 'question', 'asking', 'chips', 'answer',
  'b-send', 'b-mic', 'b-skip', 'b-wrap', 'turnline', 'coverage',
  'listening', 'listening-label', 'pulse', 'handsfree', 'handsfree-wrap',
  'panel-library', 'library-search', 'library-status', 'library-tag',
  'library-rows', 'library-empty', 'library-count',
  'b-library-new', 'b-backup', 'b-import', 'backup-file',
  'panel-done', 'done-title', 'done-meta', 'output',
  'b-share', 'b-copy', 'b-download', 'b-reopen', 'b-new', 'note', 'err',
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
  /** The running drive loop, so switching hands-free off can stop it mid-listen. */
  drive: null,
  /** The word that ends a spoken answer. */
  trigger: DRIVING.trigger,
  /** Whether hands-free was on last time, restored once the voice is known to work. */
  wantHandsFree: false,
  /** Debounced write of the unfinished answer box. */
  draftTimer: null,
  library: null,
  install: null,
};

const now = () => Date.now();

// ─────────────────────────────────────────────────────────────── plumbing

function show(panel) {
  for (const p of ['panel-setup', 'panel-interview', 'panel-library', 'panel-done']) {
    els[p].hidden = p !== panel;
  }
  if (panel === 'panel-setup' || panel === 'panel-library') els.meter.hidden = true;
}

function say(msg) {
  els.note.textContent = msg || '';
  els.note.hidden = !msg;
}

/**
 * Say it and, when nobody is looking at the screen, say it out loud.
 *
 * Always awaited. Opening the microphone while the app is still talking means it answers
 * its own question, and on a device with no echo cancellation the recogniser transcribes
 * the synthesiser.
 *
 * `fail()` deliberately stays silent: raw provider errors read appallingly aloud
 * ("HTTP 429 rate_limit_exceeded"), so each of those sites pairs it with an announce()
 * written for an ear instead.
 */
async function announce(msg, { display = true } = {}) {
  if (!msg) return;
  if (display) say(msg);
  if (state.handsFree && state.voice) await state.voice.speak(msg);
}

/**
 * The runtime threads a `warnings` array through every return value and the UI used to
 * drop it on the floor, so a turn the model mangled looked exactly like a clean one.
 *
 * The console rather than the screen, deliberately: every warning a user can act on is
 * already surfaced somewhere — a bank fallback shows on the turnline, a provider error in
 * #err, a failed wrap-up in its own message. What was missing was any way to see the
 * model misbehaving while running against a real provider.
 */
function warn(out) {
  if (out && out.warnings && out.warnings.length) {
    console.debug('[ideaforge]', out.warnings.join('; '));
  }
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
    return true;
  } catch (e) {
    say(`This session could not be saved to this device (${e.message}). Export before you close the tab.`);
    return false;
  }
}

function cancelDraftSave() {
  if (state.draftTimer) clearTimeout(state.draftTimer);
  state.draftTimer = null;
}

async function flushDraft() {
  cancelDraftSave();
  if (!state.session || !openTurn(state.session)) return true;
  const draft = els.answer.value;
  if (draft === state.session.draftAnswer) return true;
  const previous = state.session;
  state.session = setDraftAnswer(previous, draft, now());
  if (await persist()) return true;
  state.session = previous;
  return false;
}

function queueDraftSave() {
  cancelDraftSave();
  if (!state.session || !openTurn(state.session)) return;
  state.draftTimer = setTimeout(() => {
    flushDraft().catch((error) => fail(error));
  }, 600);
}

function exportFor(session) {
  return buildExport(session, {
    mode: session.synthesis.text ? 'claude' : 'checklist',
    note: session.synthesis.text
      ? null
      : 'The refined prompt could not be generated, but nothing else was lost.',
  });
}

function downloadSession(session) {
  downloadText(exportFor(session), exportFilename(session), { type: 'text/markdown' });
}

async function shareSession(session) {
  try {
    const result = await shareTextFile({
      text: exportFor(session),
      filename: exportFilename(session),
      title: sessionDisplayTitle(session),
      type: 'text/markdown',
    });
    if (result.kind === 'unsupported') {
      downloadSession(session);
      say('This browser has no share sheet, so the markdown was downloaded instead.');
    } else if (result.kind === 'text') {
      say('This share target received the markdown as text.');
    } else if (result.kind === 'file') {
      say('Shared.');
    }
  } catch (error) {
    downloadSession(session);
    say(`The share sheet failed (${error.message}). The markdown was downloaded instead.`);
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
  // A local server is the case where the address is worth asking about. This used to read
  // `c.id !== 'custom'` against a registry that offered no such choice, so the field could
  // never appear at all.
  els['field-base'].hidden = !c.local;
  // The model boxes used to be gated on `discoverModels`, which meant every hosted provider
  // ran on a tier map nobody could see, let alone change. The real question is whether this
  // provider has a model id at all — only the Claude viewer does not.
  els['field-model'].hidden = !c.picksModel;
  els['field-wrapmodel'].hidden = !c.picksModel;
  const onPhone = state.install && ['android', 'ios'].includes(state.install.state().platform);
  els['provider-note'].textContent = (c.note || '') + (c.local && onPhone
    ? ' On a phone, localhost means this phone, not a computer running Ollama.'
    : '');
  els.keylink.href = c.keyUrl || '#';
  els.keylink.hidden = !c.keyUrl;

  els.baseurl.value = saved.baseUrl || (c.id === 'custom' ? '' : defaultBaseUrl(c.id));
  els.model.value = saved.model || '';
  els.wrapmodel.value = saved.wrapModel || '';

  // The placeholders are this provider's own tier map, which is the whole answer to "which
  // model is this actually using?" — a question that had no answer anywhere in the UI
  // before. Blank means the placeholder is what runs.
  const tiers = c.models || null;
  els.model.placeholder = (tiers && tiers.default)
    || 'press Check the connection to list what is available';
  els.wrapmodel.placeholder = (tiers && tiers.complex) || 'same as the questions model';

  // A model list read off a different server is a lie about this one. The provider's own
  // defaults are not: seed with those, and let a successful Check replace them.
  fillModelList(presetModels(c));
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
    // One installed model is not a choice, so make it for them. Only ever after a real
    // read: doing this from the seeded preset list would write a value into a box where
    // blank — "use whatever the provider defaults to" — is the right answer.
    if (!els.model.value.trim() && names.length === 1) els.model.value = names[0];
    // "installed" for a local server, "available" for a hosted account: different claims
    // about different things. tools/validate-local.mjs matches on the local wording.
    const word = c.local ? 'installed' : 'available';
    els['model-note'].textContent = names.length
      ? `${names.length} model${names.length === 1 ? '' : 's'} ${word}.`
      : c.local
        ? 'That server answered, but it has no models. Pull one first.'
        : 'That account answered, but it lists no models.';
  } catch (err) {
    // Leave whatever they typed alone — it may well be right, and the server may simply
    // not answer /models.
    els['model-note'].textContent = `${err && err.message ? err.message : err} ` +
      'You can still type the model name yourself.';
    // And rethrow. This used to be swallowed, so the caller went on to report "That server
    // answered." over the top of a server that had not answered at all — the reason sitting
    // in #model-note where nobody was looking.
    throw err;
  }
}

/** Shared by both model boxes: the candidates are the same, only the job differs. */
function fillModelList(names) {
  els['model-list'].innerHTML = '';
  for (const name of names) {
    const o = document.createElement('option');
    o.value = name;
    els['model-list'].append(o);
  }
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
  els.wrapmodel.value = '';
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

  // Only the fields this choice actually declares. A hidden field is not an empty one:
  // onProviderChange fills #baseurl with the preset's own address for every OpenAI-compatible
  // provider, hosted ones included, and reading it back unconditionally handed
  // `https://api.openai.com/v1` to the loopback assertion in createProvider. OpenAI, Groq
  // and OpenRouter were therefore unusable — every Check and every Start died with "that
  // address is not on this machine", about an address the user never typed and could not see.
  let ring = withCreds(state.creds || emptyKeyring(), c.id, {
    apiKey,
    baseUrl: c.local ? els.baseurl.value.trim() : '',
    model: c.picksModel ? els.model.value.trim() : '',
    wrapModel: c.picksModel ? els.wrapmodel.value.trim() : '',
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
    wrapModel: creds.wrapModel || undefined,
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
      // Quiet: it is a per-row escape hatch, and as a bordered secondary it competed with
      // the coverage level beside it — which is the thing the row exists to show.
      skip.className = 'quiet small';
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
  // The number was on screen and announced to nobody. The element carries role="progressbar".
  els.meter.setAttribute('aria-valuenow', String(pct));

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
      queueDraftSave();
    };
    els.chips.append(b);
  }
}

async function nextQuestion() {
  busy(true, 'thinking of the next question…');
  fail(null);
  try {
    const out = await runTurn(state.session, { provider: state.provider, now: now() });
    warn(out);
    state.session = out.session;
    // Decided before the save, and recorded in the same one, so a settled turn is still
    // exactly one write — and so the advisory survives a reload instead of greeting the
    // user again on resume.
    // In hands-free the loop asks rather than announces, so it owns this.
    const advisory = state.handsFree ? null : wrapAdvisory(out.session, out.wrap);
    if (advisory) state.session = setWrapOffered(state.session, true, now());
    await persist();

    if (out.error) {
      fail(`${out.error.message} — falling back to the built-in checklist for this question.`);
      await announce('I lost the connection, so this question comes from the checklist.',
        { display: false });
    }
    if (!out.turn) {
      await wrapUp(out.wrap === 'exhausted'
        ? 'The interview ran out of questions.'
        : 'That is everything worth asking.');
      return;
    }
    render();
    if (advisory) say(advisory);
    els.answer.focus();
  } catch (e) {
    fail(e);
  } finally {
    busy(false);
  }
  // Guarded, so the hands-free driver's own call to nextQuestion does not re-enter it.
  if (state.handsFree && !state.cycling) runHandsFree();
}

/** Give up on the open question. Both the button and the spoken command land here. */
async function doSkip() {
  if (!openTurn(state.session)) return;
  cancelDraftSave();
  state.session = skipQuestion(state.session, { now: now() });
  els.answer.value = '';
  say('');
  await persist();
  await nextQuestion();
}

/** Record an answer and ask the next question. The hands-free loop calls this directly. */
async function submitAndAdvance(text, source) {
  cancelDraftSave();
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
  const canDrive = on && ttsSupported();
  els['handsfree-wrap'].hidden = !canDrive;
  // A regular driver should not re-tick this every trip.
  if (canDrive && state.wantHandsFree && !state.handsFree) setHandsFree(true);
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
 * Driving mode: read a question, listen, submit, repeat, without ever needing a tap.
 *
 * The sequencing lives in src/runtime/drive.js, where it is drivable from a Node test with
 * no browser and no microphone — which is the only reason its failure ladder has more than
 * one case. This is the adapter: every effect the loop needs, expressed in DOM.
 */
async function runHandsFree() {
  if (state.cycling || !state.handsFree || !state.voice) return;
  state.cycling = true;
  state.drive = createDriveLoop({
    speak: (text) => state.voice.speak(text),
    listen: ({ prompt, confirm }) => listenForDriving(prompt, !!confirm),
    openTurn: () => openTurn(state.session),
    submit: (text) => submitAndAdvance(text, 'voice'),
    skip: () => doSkip(),
    wrap: () => wrapUp('Wrapped up by voice.'),
    offerWrap: () => shouldOfferWrap(state.session),
    // Reading it is also the moment it counts as offered, so a reload does not re-ask.
    advisory: (reason) => {
      const text = wrapAdvisory(state.session, reason);
      if (text) state.session = setWrapOffered(state.session, true, now());
      return text;
    },
    notify: say,
    running: () => state.handsFree && !!state.voice,
    config: { trigger: state.trigger },
  });
  try {
    await state.drive.run();
  } catch (e) {
    fail(e);
  } finally {
    state.cycling = false;
    state.drive = null;
  }
}

/**
 * One capture, ended by the trigger word rather than by a pause.
 *
 * `autoStop` is off: the engine's own endpoint is a pause, and a driver pauses to change
 * lane. `isComplete` is what ends it instead, and it fires on a command too — someone
 * saying "skip this one" has finished talking by definition.
 */
async function listenForDriving(prompt, confirm) {
  els.listening.hidden = false;
  els['listening-label'].textContent = confirm
    ? 'Listening — yes, or keep going.'
    : `Listening — say “${state.trigger}” when you’re done.`;
  els['b-mic'].textContent = 'Stop listening';
  const isComplete = (text) => {
    const said = parseSpeech(text, { trigger: state.trigger });
    if (said.stopped || said.kind !== 'answer') return true;
    return confirm && matchAffirmation(said.text) !== null;
  };
  try {
    return await state.voice.listen({
      prompt,
      autoStop: false,
      isComplete,
      settleMs: DRIVING.settleMs,
      gate: confirm ? CONFIRM_GATE : DRIVING_GATE,
      maxSegments: confirm ? 1 : 6,
      onInterim: (t) => { els.answer.value = t; },
      onLevel: (rms) => {
        els.pulse.style.setProperty('--level', String(0.6 + Math.min(1.7, rms * 16)));
      },
    });
  } finally {
    els.listening.hidden = true;
    els['b-mic'].textContent = 'Answer out loud';
    els.pulse.style.removeProperty('--level');
    els.answer.value = '';
  }
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
    if (state.drive) state.drive.stop();
    if (state.voice) { state.voice.cancelSpeech(); state.voice.abort(); }
    return;
  }
  runHandsFree();
}

// ────────────────────────────────────────────────────────────── wrap-up

/**
 * Write it up, and — if the interview was being driven — read it back.
 *
 * The order matters. This used to tear the voice down FIRST, which is why the finished
 * prompt could never be spoken: by the time there was something to say, there was nothing
 * left to say it with. Now driving stops, the voice stays alive through the synthesis, and
 * teardown is the last thing.
 */
async function wrapUp(note) {
  const wasDriving = state.handsFree;
  setHandsFree(false);                       // stop driving…
  if (state.voice) state.voice.abort();      // …but keep the voice for the read-back

  show('panel-done');
  els['done-title'].textContent = 'Writing it up…';
  els.output.textContent = '';
  els['done-meta'].textContent = note || '';
  if (wasDriving) await speakLong('Writing it up.');

  const out = await runSynthesis(state.session, { provider: state.provider, now: now() });
  warn(out);
  state.session = out.ok ? out.session : setStatusField(out.session, 'done', now());
  await persist();

  if (!out.ok) {
    fail(out.error
      ? `The wrap-up call failed: ${out.error.message}`
      : 'The model returned no usable prompt.');
    // markdown.js renders a complete document with no synthesis at all, so the
    // transcript and open questions are never lost to a failed wrap-up.
  }
  renderDone();

  if (wasDriving) await speakLong(spokenResult(out.ok));
  teardownVoice();
}

/** What the finished interview sounds like. */
function spokenResult(ok) {
  const s = state.session;
  if (!ok || !s.synthesis.text) {
    return 'I could not write the prompt, but nothing is lost — your answers are saved '
      + 'and the document is on screen.';
  }
  const title = sessionDisplayTitle(s, '') ? `${sessionDisplayTitle(s, '')}. ` : '';
  return `Here it is. ${title}${forSpeech(s.synthesis.text)}`;
}

/**
 * Read a long passage in pieces.
 *
 * Not optional: speak.js caps its own wait at `2s + words/2.6` to survive Chrome's utterance
 * watchdog, so a six-hundred-word prompt handed over in one go is abandoned partway through
 * with no error at all.
 */
async function speakLong(text) {
  if (!state.voice || !text) return;
  for (const chunk of speechChunks(text)) {
    if (!state.voice) return;
    await state.voice.speak(chunk);
  }
}

function renderDone() {
  const s = state.session;
  els['done-title'].textContent = sessionDisplayTitle(s, 'Your refined prompt');
  const degraded = s.turns.some((t) => t.questionSource === 'bank');
  els['done-meta'].textContent =
    `${s.turns.filter((t) => t.answer || t.skipped).length} questions · coverage ${coveragePercent(s)}%` +
    (degraded ? ' · some questions came from the built-in checklist' : '');
  drawExport(exportFor(s));
}

/**
 * The export, drawn.
 *
 * The parsing is in src/core/markdown.js, which is purity-linted and so cannot touch the
 * DOM; this is the half that can. Every span goes in through textContent — the blocks come
 * from `synthesis.text`, which is model output, and there is no innerHTML anywhere in this
 * app for exactly that reason.
 */
function drawExport(markdown) {
  els.output.textContent = '';
  // The rendered view and the bytes it was rendered from, together. `textContent` used to BE
  // the markdown and is now flattened prose with every marker gone, so anything reading the
  // artifact off the page — tools/screenshots.mjs writes docs/examples/ from here — needs the
  // source kept somewhere. Copy, Download and Share go through exportFor() instead, which is
  // the same bytes without depending on the DOM at all.
  els.output.dataset.source = markdown;
  const spans = (into, list) => {
    for (const s of list || []) {
      const tag = s.code ? 'code' : s.bold ? 'strong' : s.italic ? 'em' : null;
      if (!tag) { into.append(s.text); continue; }
      const el = document.createElement(tag);
      el.textContent = s.text;
      into.append(el);
    }
  };

  for (const block of renderBlocks(markdown)) {
    if (block.type === 'hr') { els.output.append(document.createElement('hr')); continue; }

    if (block.type === 'pre') {
      const pre = document.createElement('pre');
      pre.textContent = block.text;
      els.output.append(pre);
      continue;
    }

    if (block.type === 'list') {
      const list = document.createElement(block.ordered ? 'ol' : 'ul');
      for (const item of block.items) {
        const li = document.createElement('li');
        spans(li, item);
        list.append(li);
      }
      els.output.append(list);
      continue;
    }

    if (block.type === 'table') {
      const table = document.createElement('table');
      const thead = table.createTHead().insertRow();
      for (const cell of block.head) spans(thead.appendChild(document.createElement('th')), cell);
      const body = table.createTBody();
      for (const row of block.rows) {
        const tr = body.insertRow();
        for (const cell of row) spans(tr.insertCell(), cell);
      }
      els.output.append(table);
      continue;
    }

    const el = document.createElement(
      block.type === 'quote' ? 'blockquote'
        : /^h[123]$/.test(block.type) ? block.type
          : 'p');
    if (block.meta) el.className = 'meta';
    spans(el, block.spans);
    els.output.append(el);
  }
}

function download() {
  downloadSession(state.session);
}

async function refreshLibrary() {
  try {
    state.library.render(await listSessions());
  } catch (error) {
    fail(error);
  }
}

async function openLibrary() {
  say('');
  fail(null);
  if (!(await flushDraft())) return;
  teardownVoice();
  show('panel-library');
  await refreshLibrary();
  state.library.focus();
}

async function editLibrarySession(session, { name, tags }) {
  try {
    const changedAt = now();
    let updated = renameSession(session, name, changedAt);
    updated = setSessionTags(updated, tags, changedAt);
    await saveSession(updated);
    if (state.session && state.session.id === updated.id) state.session = updated;
    say('Idea details saved.');
    await refreshLibrary();
  } catch (error) {
    fail(error);
  }
}

async function setArchived(session, archived) {
  try {
    const updated = archived ? archiveSession(session, now()) : restoreSession(session, now());
    await saveSession(updated);
    if (state.session && state.session.id === updated.id) state.session = updated;
    say(archived ? 'Idea archived.' : 'Idea restored.');
    await refreshLibrary();
    await renderResumeList();
  } catch (error) {
    fail(error);
  }
}

async function removeLibrarySession(session) {
  try {
    await deleteSession(session.id);
    if (state.session && state.session.id === session.id) state.session = null;
    say('Idea deleted from this device.');
    await refreshLibrary();
    await renderResumeList();
  } catch (error) {
    fail(error);
  }
}

async function exportLibraryBackup() {
  try {
    const sessions = await listSessions();
    downloadText(buildBackup(sessions, { now: now() }), backupFilename(now()), {
      type: 'application/json',
    });
    say(`Backed up ${sessions.length} idea${sessions.length === 1 ? '' : 's'}.`);
  } catch (error) {
    fail(error);
  }
}

async function importLibraryBackup(file) {
  try {
    if (file.size > MAX_BACKUP_BYTES) {
      throw new Error(`The backup is larger than ${Math.round(MAX_BACKUP_BYTES / 1024 / 1024)} MB.`);
    }
    requestPersistence();
    const parsed = parseBackup(await file.text());
    const summary = await importSessions(parsed.sessions, { now: now() });
    const parts = [
      `${summary.imported} imported`,
      `${summary.copied} kept as copies`,
      `${summary.duplicates} duplicates skipped`,
    ];
    if (parsed.skipped) parts.push(`${parsed.skipped} invalid rows skipped`);
    say(parts.join(' · '));
    await refreshLibrary();
    await renderResumeList();
  } catch (error) {
    fail(error);
  }
}

function setupLibrary() {
  state.library = createLibraryView({
    elements: {
      search: els['library-search'],
      status: els['library-status'],
      tag: els['library-tag'],
      rows: els['library-rows'],
      empty: els['library-empty'],
      count: els['library-count'],
      backup: els['b-backup'],
      importButton: els['b-import'],
      importFile: els['backup-file'],
      newButton: els['b-library-new'],
    },
    onOpen: resumeInterview,
    onEdit: editLibrarySession,
    onArchive: (session) => setArchived(session, true),
    onRestore: (session) => setArchived(session, false),
    onDelete: removeLibrarySession,
    onShare: shareSession,
    onDownload: downloadSession,
    onBackup: exportLibraryBackup,
    onImport: importLibraryBackup,
    onError: fail,
    onNew: async () => {
      say('');
      fail(null);
      if (!(await flushDraft())) return;
      teardownVoice();
      show('panel-setup');
      await renderResumeList();
    },
  });
}

function renderInstall(info = state.install && state.install.state()) {
  if (!info || info.installed || info.platform === 'other') {
    els['install-card'].hidden = true;
    return;
  }

  els['install-card'].hidden = false;
  els['b-install'].hidden = !(info.platform === 'android' && info.canPrompt);
  if (info.platform === 'ios') {
    els['install-title'].textContent = 'Install on this iPhone';
    els['install-note'].textContent =
      'In Safari, tap Share, Add to Home Screen, leave Open as Web App on if shown, then tap Add. '
      + 'For dictation in the installed app, choose Groq or OpenAI Whisper below.';
  } else {
    els['install-title'].textContent = 'Install on this Android phone';
    els['install-note'].textContent = info.canPrompt
      ? 'Install it for a full-screen home-screen app and more durable offline storage.'
      : 'In Chrome, open the menu and choose Install app or Add to Home screen.';
  }
}

// ──────────────────────────────────────────────────────────────── boot

async function startInterview() {
  fail(null);
  requestPersistence();
  try {
    await buildProvider();
  } catch (e) {
    fail(e);
    return;
  }
  const platform = state.install ? state.install.state().platform : 'other';
  state.session = seedTurn(createSession({
    id: newSessionId(),
    now: now(),
    device: platform === 'other' ? 'desktop' : platform,
  }), { now: now() });
  await persist();
  show('panel-interview');
  render();
  await setupVoice();
  els.answer.focus();
}

async function resumeInterview(id) {
  say('');
  fail(null);
  if (!(await flushDraft())) return;
  teardownVoice();
  const s = await loadSession(id);
  if (!s) { fail('That session was written by a newer version of IdeaForge.'); return; }
  state.session = s;

  if (s.status === 'done' && !s.pending && !openTurn(s)) {
    renderDone();
    show('panel-done');
    return;
  }

  try {
    await buildProvider();
  } catch (e) {
    say(`Continuing without a model (${e.message}). Questions will come from the built-in checklist.`);
    state.provider = null;
  }

  if (s.pending && s.pending.kind === 'synthesis') {
    show('panel-done');
    els['done-title'].textContent = 'Picking up the write-up…';
    els.output.textContent = '';
    els['done-meta'].textContent = '';
    const out = await resumeSynthesis(s, { provider: state.provider, now: now() });
    warn(out);
    state.session = out.ok ? out.session : setStatusField(out.session, 'done', now());
    await persist();
    if (!out.ok) {
      fail(out.error
        ? `The wrap-up call failed: ${out.error.message}`
        : 'The refined prompt could not be regenerated, but the interview is intact.');
    }
    renderDone();
    return;
  }

  show('panel-interview');
  await setupVoice();

  if (s.pending && s.pending.kind === 'turn') {
    busy(true, 'picking up where the last question left off…');
    try {
      const out = await resumeTurn(s, { provider: state.provider, now: now() });
      warn(out);
      state.session = out.session;
      await persist();
    } catch (e) { fail(e); } finally { busy(false); }
  }
  if (state.session.status === 'done' && !openTurn(state.session)) {
    renderDone();
    show('panel-done');
    return;
  }
  if (!openTurn(state.session)) { await nextQuestion(); return; }
  render();
  els.answer.value = state.session.draftAnswer || '';
  els.answer.focus();
}

async function renderResumeList() {
  let rows;
  try { rows = await listSessions(); } catch { return; }
  const open = rows.filter((s) => !s.archivedAt && s.turns.length > 0).slice(0, 3);
  els.resume.hidden = open.length === 0;
  els['resume-rows'].innerHTML = '';
  for (const s of open) {
    const row = document.createElement('div');
    row.className = 'row';
    const name = document.createElement('span');
    name.textContent = sessionDisplayTitle(s, '(no opening statement yet)');
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'ghost small';
    b.textContent = s.status === 'done' ? 'view' : 'continue';
    b.onclick = () => resumeInterview(s.id).catch((error) => fail(error));
    const meta = document.createElement('span');
    meta.className = 'gap';
    const answered = s.turns.filter((turn) => turn.answer || turn.skipped).length;
    meta.textContent = `${answered} questions · coverage ${coveragePercent(s)}%`;
    row.append(name, b, meta);
    els['resume-rows'].append(row);
  }
}

function bind() {
  els.provider.onchange = () => { onProviderChange(); onSttChange(); };
  els.stt.onchange = onSttChange;
  // On change, not on input: normalising every keystroke fights whoever is typing.
  els.stopword.onchange = () => applyTrigger(els.stopword.value);
  els['b-start'].onclick = startInterview;
  els['b-install'].onclick = async () => {
    if (!state.install) return;
    try {
      const choice = await state.install.install();
      if (choice.outcome === 'dismissed') say('Installation was cancelled.');
    } catch (error) {
      fail(error);
    }
  };
  els['b-mic'].onclick = async () => {
    if (!state.voice) return;
    if (!els.listening.hidden) { state.voice.stop(); return; }
    setHandsFree(false);                 // a manual tap takes the wheel back
    try {
      // autoStop false: the button is press-to-talk, so the user decides when they are
      // done. Whatever came back lands in the box for them to edit before sending.
      const heard = await listenOnce({ prompt: currentQuestion(), autoStop: false });
      if (heard.trim()) {
        els.answer.value = heard;
        state.answerSource = 'voice';
        queueDraftSave();
      }
    } catch (e) { fail(e); }
  };
  els.handsfree.onchange = () => {
    savePrefs({ handsFree: els.handsfree.checked });
    setHandsFree(els.handsfree.checked);
  };
  els.baseurl.oninput = onBaseChange;
  els['b-check'].onclick = async () => {
    fail(null); say('Checking…');
    const c = currentChoice();
    try {
      if (c.discoverModels) {
        // For a local server "does the key work" is the wrong question — it has no key.
        // What you actually want to know is whether it answers, and what it can run. For a
        // hosted one the list read IS the key check: listModels and validateKey probe the
        // same /models endpoint, so one call answers both and the catalogue arrives in the
        // model boxes as a side effect of asking.
        await refreshModels();
        say(c.needsKey ? 'That key works.' : 'That server answered.');
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
    if (state.voice) state.voice.abort();      // a tap takes the wheel back mid-listen
    await doSkip();
  };
  els['b-wrap'].onclick = () => wrapUp('Wrapped up early, at your request.');
  els['b-library'].onclick = openLibrary;
  els['b-view-all'].onclick = openLibrary;
  els['b-settings'].onclick = async () => {
    say('');
    fail(null);
    if (!(await flushDraft())) return;
    teardownVoice();
    show('panel-setup');
    await renderResumeList();
  };
  els['b-share'].onclick = () => shareSession(state.session);
  els['b-copy'].onclick = async () => {
    try {
      // exportFor, not the DOM. #output renders the markdown now, so reading its textContent
      // back would hand over the prose with every marker stripped — which is not the thing
      // anyone is copying it for. One source of bytes for Copy, Download and Share.
      await navigator.clipboard.writeText(exportFor(state.session));
      say('Copied.');
    } catch { say('Could not reach the clipboard — select the text above instead.'); }
  };
  els['b-download'].onclick = download;
  els['b-new'].onclick = async () => {
    teardownVoice();
    say('');
    fail(null);
    show('panel-setup');
    await renderResumeList();
  };
  els['b-reopen'].onclick = async () => {
    try {
      await buildProvider();
    } catch (error) {
      say(`Continuing without a model (${error.message}). Questions will come from the built-in checklist.`);
      state.provider = null;
    }
    const reopenedAt = now();
    state.session = reopen(state.session, reopenedAt);
    if (state.session.archivedAt) state.session = restoreSession(state.session, reopenedAt);
    await persist();
    show('panel-interview');
    // wrapUp tore the voice down, so without this "Ask me more" has no microphone at all.
    await setupVoice();
    await nextQuestion();
  };

  // Ctrl/Cmd+Enter sends; a plain Enter must still make a paragraph, because dictated
  // answers are long and people press Enter mid-thought.
  els.answer.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); send(); }
  });
  // Editing a chip makes the words theirs again, which un-caps the coverage grade.
  els.answer.addEventListener('input', () => {
    if (state.answerSource === 'chip') state.answerSource = 'typed';
    queueDraftSave();
  });
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden') flushDraft();
  });
}

/** Read the trigger word out of the field, clean it up, and say if it looks unwise. */
function applyTrigger(raw, { persist: write = true } = {}) {
  state.trigger = normalizeTrigger(raw);
  els.stopword.value = state.trigger;
  const warning = triggerWarning(state.trigger);
  // Advisory, never enforced. Somebody who genuinely wants "right" should be able to
  // have it; they just deserve to be told first.
  els['stopword-note'].textContent = warning
    || 'Say this when you have finished an answer, and the interview moves on by itself. '
    + 'You can also say “repeat that”, “skip this one”, '
    + '“scratch that” or “wrap it up”.';
  if (write) savePrefs({ trigger: state.trigger });
}

async function boot() {
  bind();
  setupLibrary();
  state.install = createInstallController({
    onChange: renderInstall,
  });
  renderInstall();
  const prefs = loadPrefs();
  // Synchronously, before anything can start an interview: the loop needs the word to
  // build its matcher, and the keyring below is loaded asynchronously.
  applyTrigger(prefs.trigger, { persist: false });
  state.wantHandsFree = prefs.handsFree;
  try { state.creds = await loadCredentials(); } catch { /* first run, or storage blocked */ }
  if (state.creds && state.creds.stt && state.creds.stt.kind) {
    els.stt.value = state.creds.stt.kind;
  }
  renderProviderChoices();
  onSttChange();
  els.version.textContent = `IdeaForge v${VERSION}`;
  await renderResumeList();
  show('panel-setup');

  // Installability and an offline cold start. Needs a secure context, so it simply does
  // not register on a file:// open — which is fine, the app still runs.
  if ('serviceWorker' in navigator && location.protocol !== 'file:') {
    navigator.serviceWorker.register('./sw.js').catch(() => { /* not fatal */ });
  }
}

boot();
