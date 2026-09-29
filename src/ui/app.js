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
  emptyKeyring, credsFor, withCreds, withStt, withTts, hasAnyKey,
} from '../store/secrets.js';
import { requestPersistence } from '../store/db.js';
import { loadPrefs, savePrefs, speechPreferences } from '../store/prefs.js';
import { createVoice, STT_PRESETS, forgetVerdict, ttsSupported } from '../voice/index.js';
import { createSpeechOutput } from '../voice/output.js';
import { createVoiceStage } from './voice-stage.js';
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
  'field-wrapmodel', 'wrapmodel', 'model-settings',
  'install-card', 'install-title', 'install-note', 'b-install',
  'b-start', 'b-start-voice', 'b-check', 'resume', 'resume-rows', 'b-view-all',
  'stt', 'stt-note', 'field-sttkey', 'sttkey', 'sttkey-note', 'b-forget', 'version',
  'field-stopword', 'stopword', 'stopword-note',
  'speech', 'speech-note', 'browser-voice', 'field-browser-voice', 'speech-rate',
  'speech-rate-value', 'field-tts', 'ttskey', 'tts-voice', 'tts-consent',
  'b-speech-preview', 'speech-check', 'speech-options',
  'panel-interview', 'bridge', 'question', 'asking', 'chips', 'answer',
  'manual-interview', 'voice-stage', 'voice-status', 'voice-signal', 'voice-question',
  'voice-transcript', 'voice-detail', 'voice-backend', 'b-voice-pause', 'b-voice-exit',
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
  voiceSetup: null,
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
  navigation: 0,
  speech: null,
  previewSpeech: null,
  voiceStage: null,
  voiceMode: 'manual',
  voicePhase: 'ready',
  voiceEpoch: 0,
  voiceCaption: '',
  voiceTranscript: null,
  voiceDetail: '',
  voiceBackend: 'Browser voice',
  capturePrefix: '',
  capturePending: false,
  captureConfirm: false,
  readback: false,
  writing: null,
  credentialEpoch: 0,
  wakeLock: null,
  starting: false,
  voiceWrapPending: null,
};

const now = () => Date.now();
const workBySession = new Map();
const deletedSessions = new Set();
const barActions = document.querySelector('.bar-right');

// ─────────────────────────────────────────────────────────────── plumbing

function show(panel) {
  for (const p of ['panel-setup', 'panel-interview', 'panel-library', 'panel-done']) {
    els[p].hidden = p !== panel;
  }
  if (panel === 'panel-setup' || panel === 'panel-library') els.meter.hidden = true;
  renderVoiceStage();
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
  if (state.handsFree && state.speech) await state.speech.speak(msg);
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
  const hasQuestion = !!(state.session && openTurn(state.session));
  els['b-send'].disabled = on || !hasQuestion;
  els['b-skip'].disabled = on || !hasQuestion;
  els['b-mic'].disabled = on || !hasQuestion;
  els.answer.disabled = on || !hasQuestion;
  els['b-wrap'].disabled = on;
  els['b-reopen'].disabled = on || !!(state.session
    && (workBySession.has(state.session.id) || state.writing === state.session.id));
  els.asking.hidden = !on;
  if (label) els.asking.textContent = label;
  if (on && state.voiceMode === 'active') setVoicePhase('thinking');
}

/** Persist once per settled turn — runTurn bumps `rev` five or six times internally. */
async function persist(session = state.session) {
  if (!session || deletedSessions.has(session.id)) return false;
  try {
    await saveSession(session);
    return true;
  } catch (e) {
    say(`This session could not be saved to this device (${e.message}). Export before you close the tab.`);
    return false;
  }
}

function currentSession(id) {
  return state.session && state.session.id === id && !deletedSessions.has(id);
}

function currentView(id, navigation) {
  return currentSession(id) && state.navigation === navigation;
}

/** Keep library edits separate from a model's older snapshot of the same idea. */
function withLibraryMetadata(session, latest) {
  if (session.name !== latest.name) session = renameSession(session, latest.name, now());
  if (JSON.stringify(session.tags) !== JSON.stringify(latest.tags)) {
    session = setSessionTags(session, latest.tags, now());
  }
  if (session.archivedAt !== latest.archivedAt) {
    session = latest.archivedAt
      ? archiveSession(session, latest.archivedAt) : restoreSession(session, now());
  }
  return session;
}

function sessionWork(session, kind, run) {
  const pending = workBySession.get(session.id);
  if (pending) {
    if (pending.kind !== kind) {
      throw new Error('This interview is still finishing a different request.');
    }
    return pending.promise;
  }
  const job = { kind, controller: new AbortController(), metadata: session, promise: null };
  job.promise = (async () => {
    const out = await run(job.controller.signal);
    if (deletedSessions.has(session.id)) return { ...out, discarded: true };
    const settled = withLibraryMetadata(out.session, job.metadata);
    await persist(settled);
    if (deletedSessions.has(session.id)) return { ...out, discarded: true };
    if (currentSession(session.id)) state.session = settled;
    return { ...out, session: settled };
  })().finally(() => {
    if (workBySession.get(session.id) === job) workBySession.delete(session.id);
    if (currentSession(session.id)) busy(false);
  });
  workBySession.set(session.id, job);
  return job.promise;
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
  const navigation = state.navigation;
  if (await persist()) return true;
  if (currentView(previous.id, navigation)) state.session = previous;
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
  els['model-settings'].hidden = !c.picksModel;
  els['model-settings'].open = !!c.local;
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
    els['b-start-voice'].disabled = els.stt.value === 'off';
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
  els['b-start-voice'].disabled = bad || ipv6 || els.stt.value === 'off';
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
  state.credentialEpoch++;
  teardownVoice();
  if (state.previewSpeech) state.previewSpeech.dispose();
  state.previewSpeech = null;
  for (const job of workBySession.values()) job.controller.abort();
  await clearCredentials();
  state.creds = emptyKeyring();
  state.provider = null;
  els.apikey.value = '';
  els.sttkey.value = '';
  els.ttskey.value = '';
  els.model.value = '';
  els.wrapmodel.value = '';
  els['model-list'].innerHTML = '';
  fail(null);
  onProviderChange();
  onSttChange();
  renderSpeechSettings();
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
  ring = withTts(ring, readSpeechFromForm());
  return ring;
}

function readSpeechFromForm() {
  const saved = (state.creds && state.creds.tts) || emptyKeyring().tts;
  if (els.speech.value !== 'openai') return { ...saved, kind: 'browser' };
  const apiKey = els.ttskey.value.trim() || saved.apiKey;
  const voice = els['tts-voice'].value === 'cedar' ? 'cedar' : 'marin';
  return {
    kind: 'openai', apiKey, voice,
    verified: saved.verified && saved.apiKey === apiKey && saved.voice === voice
      && els['tts-consent'].checked,
  };
}

function saveSpeechPreferences() {
  const preferences = speechPreferences({
    speechVoice: els['browser-voice'].value,
    speechRate: Number(els['speech-rate'].value),
  });
  savePrefs(preferences);
  els['speech-rate-value'].value = `${preferences.speechRate.toFixed(2)}×`;
  return preferences;
}

function renderSpeechVoices() {
  const selected = els['browser-voice'].value || loadPrefs().speechVoice;
  const voices = window.speechSynthesis ? window.speechSynthesis.getVoices() : [];
  els['browser-voice'].replaceChildren();
  const automatic = document.createElement('option');
  automatic.value = '';
  automatic.textContent = 'Automatic — browser voice for English';
  els['browser-voice'].append(automatic);
  for (const voice of voices) {
    const option = document.createElement('option');
    option.value = voice.voiceURI || voice.name;
    option.textContent = `${voice.name} · ${voice.lang} · ${voice.localService ? 'on device' : 'network'}`;
    els['browser-voice'].append(option);
  }
  if (selected && !voices.some((voice) => (voice.voiceURI || voice.name) === selected)) {
    const unavailable = document.createElement('option');
    unavailable.value = selected;
    unavailable.textContent = 'Saved voice unavailable — automatic fallback';
    els['browser-voice'].append(unavailable);
  }
  els['browser-voice'].value = selected;
}

function onSpeechChange() {
  const hosted = els.speech.value === 'openai';
  if (hosted) els['speech-options'].open = true;
  els['field-tts'].hidden = !hosted;
  els['field-browser-voice'].hidden = hosted;
  els['speech-note'].textContent = hosted
    ? 'Optional AI-generated speech. A successful, consented check is required on this device. '
      + 'Until then, replies use the browser voice. This is separate from dictation.'
    : 'Choose and preview a voice from this browser. Voice quality and offline availability depend on the device.';
  els['b-speech-preview'].textContent = hosted ? 'Check and preview OpenAI voice' : 'Preview voice';
  els['b-speech-preview'].disabled = hosted && !els['tts-consent'].checked;
}

function renderSpeechSettings() {
  const saved = (state.creds && state.creds.tts) || emptyKeyring().tts;
  const preferences = loadPrefs();
  els.speech.value = saved.kind;
  els['tts-voice'].value = saved.voice;
  els['tts-consent'].checked = saved.verified;
  els.ttskey.placeholder = saved.apiKey ? `saved: ${maskKey(saved.apiKey)}` : 'paste a scoped OpenAI key';
  els['speech-rate'].value = String(preferences.speechRate);
  els['speech-rate-value'].value = `${preferences.speechRate.toFixed(2)}×`;
  renderSpeechVoices();
  onSpeechChange();
}

async function previewSpeech() {
  if (state.previewSpeech) {
    state.previewSpeech.dispose();
    state.previewSpeech = null;
    els['speech-check'].textContent = 'Preview cancelled.';
    onSpeechChange();
    return;
  }
  const config = readSpeechFromForm();
  if (config.kind === 'openai' && (!config.apiKey || !els['tts-consent'].checked)) {
    els['speech-check'].textContent = 'Enter a separate speech key and confirm metered text transfer first.';
    return;
  }
  const preferences = saveSpeechPreferences();
  const credentialEpoch = state.credentialEpoch;
  const preview = createSpeechOutput({
    ...config, browserVoice: preferences.speechVoice, rate: preferences.speechRate,
  });
  state.previewSpeech = preview;
  els['speech-check'].textContent = config.kind === 'openai'
    ? 'Checking one short, metered speech sample…' : 'Preparing the browser voice…';
  els['b-speech-preview'].textContent = 'Cancel preview';
  try {
    const primed = preview.prime();
    await primed;
    const result = await preview.check(
      'IdeaForge is ready. Tell me what you have in mind, and we will take it one question at a time.',
    );
    if (state.previewSpeech !== preview || credentialEpoch !== state.credentialEpoch) return;
    if (!result || result.status !== 'spoken') {
      els['speech-check'].textContent = 'The preview did not finish. Speech has not been enabled.';
      return;
    }
    state.creds = withTts(state.creds || emptyKeyring(), {
      ...config, verified: config.kind === 'openai' ? true : config.verified,
    });
    await saveCredentials(state.creds);
    if (state.previewSpeech !== preview) return;
    els.ttskey.value = '';
    els['speech-check'].textContent = config.kind === 'openai'
      ? 'OpenAI speech played successfully in this browser. Enhanced speech is enabled.'
      : 'Browser voice preview complete.';
    renderSpeechSettings();
    els['b-forget'].hidden = !hasAnyKey(state.creds);
  } catch (error) {
    if (state.previewSpeech === preview) {
      els['speech-check'].textContent = `The voice check failed: ${error.message}`;
    }
  } finally {
    preview.dispose();
    if (state.previewSpeech === preview) {
      state.previewSpeech = null;
      onSpeechChange();
    }
  }
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
  onBaseChange();
}

async function buildProvider({ requireModel = true } = {}) {
  const navigation = state.navigation;
  const credentialEpoch = state.credentialEpoch;
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

  if (navigation !== state.navigation || credentialEpoch !== state.credentialEpoch) {
    throw new Error('Provider setup was cancelled.');
  }
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
  } else if (state.voiceWrapPending) {
    els.question.textContent = 'Ready to write it up.';
    els.bridge.hidden = true;
    els.chips.replaceChildren();
    els.turnline.textContent = 'Your answers are saved. Write it up whenever you are ready.';
  }
  els['b-wrap'].hidden = !(s.turns.length >= 4 || state.voiceWrapPending);
  els['b-send'].disabled = state.busy || !open;
  els['b-skip'].disabled = state.busy || !open;
  els.answer.disabled = state.busy || !open;
  renderCoverage();
  renderVoiceStage();
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

async function nextQuestion(
  session = state.session, provider = state.provider, navigation = state.navigation,
  fromVoice = state.voiceMode !== 'manual',
) {
  if (!session || deletedSessions.has(session.id)) return;
  if (currentView(session.id, navigation)) {
    busy(true, 'thinking of the next question…');
    fail(null);
  }
  try {
    const out = await sessionWork(session, 'turn', async (signal) => {
      const result = await runTurn(session, { provider, now: now(), signal });
      const advisory = currentView(session.id, navigation) && state.voiceMode === 'manual' && !result.aborted
        ? wrapAdvisory(result.session, result.wrap) : null;
      if (advisory) result.session = setWrapOffered(result.session, true, now());
      return { ...result, advisory };
    });
    if (out.discarded || out.aborted || !currentView(session.id, navigation)) return;
    warn(out);

    if (out.error) {
      fail(`${out.error.message} — falling back to the built-in checklist for this question.`);
      await announce('I lost the connection, so this question comes from the checklist.',
        { display: false });
      if (!currentView(session.id, navigation)) return;
    }
    if (!out.turn) {
      if (state.voiceMode === 'paused' || state.voiceMode === 'blocked'
          || (fromVoice && state.voiceMode !== 'active')) {
        state.voiceWrapPending = out.wrap || 'coverage';
        state.voiceCaption = 'Ready to write it up';
        state.voiceDetail = 'Resume to finish the interview, or Exit to review your words first.';
        render();
        return;
      }
      await wrapUp(out.wrap === 'exhausted'
        ? 'The interview ran out of questions.'
        : 'That is everything worth asking.');
      return;
    }
    render();
    if (out.advisory) say(out.advisory);
    if (state.voiceMode === 'manual') els.answer.focus();
  } catch (e) {
    if (currentView(session.id, navigation)) fail(e);
  } finally {
    if (currentView(session.id, navigation)) busy(false);
  }
  // Guarded, so the hands-free driver's own call to nextQuestion does not re-enter it.
  if (currentView(session.id, navigation) && state.handsFree && !state.cycling) runHandsFree();
}

/** Give up on the open question. Both the button and the spoken command land here. */
async function doSkip() {
  if (state.busy || !state.session || !openTurn(state.session)) return;
  busy(true);
  cancelDraftSave();
  state.session = skipQuestion(state.session, { now: now() });
  const session = state.session;
  const provider = state.provider;
  const navigation = state.navigation;
  const credentialEpoch = state.credentialEpoch;
  const fromVoice = state.voiceMode !== 'manual';
  els.answer.value = '';
  say('');
  await persist(session);
  await nextQuestion(session, credentialEpoch === state.credentialEpoch ? provider : null, navigation, fromVoice);
}

/** Record an answer and ask the next question. The hands-free loop calls this directly. */
async function submitAndAdvance(text, source) {
  if (state.busy || !state.session || !openTurn(state.session)) return;
  busy(true);
  cancelDraftSave();
  state.session = submitAnswer(state.session, { text, source, now: now() });
  const session = state.session;
  const provider = state.provider;
  const navigation = state.navigation;
  const credentialEpoch = state.credentialEpoch;
  const fromVoice = state.voiceMode !== 'manual';
  els.answer.value = '';
  state.answerSource = 'typed';
  say('');
  await persist(session);
  await nextQuestion(session, credentialEpoch === state.credentialEpoch ? provider : null, navigation, fromVoice);
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
function renderVoiceStage() {
  if (!state.voiceStage) return;
  const visible = state.voiceMode !== 'manual' && !els['panel-interview'].hidden;
  els['voice-stage'].hidden = !visible;
  els['manual-interview'].hidden = visible;
  barActions.hidden = visible;
  els.meter.hidden = visible || els['panel-interview'].hidden;
  document.body.classList.toggle('voice-active', visible);
  const hasQuestion = !!(state.session && openTurn(state.session));
  els['b-send'].disabled = visible || state.busy || !hasQuestion;
  els['b-skip'].disabled = visible || state.busy || !hasQuestion;
  els['b-mic'].disabled = visible || state.busy || !hasQuestion;
  els['b-wrap'].disabled = visible || state.busy;
  els.answer.disabled = visible || state.busy || !hasQuestion;
  if (!visible) return;
  state.voiceStage.render({
    phase: state.voiceMode === 'paused' || state.voiceMode === 'blocked'
      ? state.voiceMode : state.voicePhase,
    question: state.voiceCaption || currentQuestion(),
    transcript: state.voiceTranscript === null ? els.answer.value : state.voiceTranscript,
    detail: state.voiceDetail || (state.voicePhase === 'listening'
      ? `Say “${state.trigger}” to send. “Pause voice” or “exit voice” are commands.`
      : 'One question at a time. Take your time.'),
    backend: state.voiceBackend,
    pending: state.capturePending,
  });
}

function setVoicePhase(phase, detail) {
  if (state.voiceMode !== 'active') return;
  state.voicePhase = phase;
  if (phase === 'listening' || phase === 'thinking') state.voiceCaption = '';
  if (detail !== undefined) state.voiceDetail = detail;
  if (phase !== 'listening') state.voiceStage.reset();
  renderVoiceStage();
}

function prepareSpeechOutput() {
  if (state.speech) state.speech.dispose();
  const config = readSpeechFromForm();
  const preferences = saveSpeechPreferences();
  const kind = config.kind === 'openai' && config.verified ? 'openai' : 'browser';
  state.voiceBackend = kind === 'openai' ? 'OpenAI · AI-generated voice' : 'Browser voice';
  state.voiceDetail = config.kind === 'openai' && !config.verified
    ? 'OpenAI speech has not been checked. Using the browser voice.' : '';
  let output;
  output = createSpeechOutput({
    ...config, kind, browserVoice: preferences.speechVoice, rate: preferences.speechRate,
    onStatus: (event) => {
      if (state.speech !== output || state.voiceMode !== 'active') return;
      if (event.backend) state.voiceBackend = event.backend === 'openai'
        ? 'OpenAI · AI-generated voice' : 'Browser voice';
      if (event.text) state.voiceCaption = event.text;
      if (event.message) state.voiceDetail = event.message;
      if (event.phase === 'speaking') state.voicePhase = 'speaking';
      if (event.phase === 'preparing') state.voicePhase = 'thinking';
      renderVoiceStage();
    },
  });
  state.speech = output;
  Promise.resolve(output.prime()).catch((error) => {
    if (state.speech !== output) return;
    state.voiceDetail = `Speech needs a fresh tap to start: ${error.message}`;
    renderVoiceStage();
  });
}

async function speakForVoice(text) {
  const speech = state.speech;
  const epoch = state.voiceEpoch;
  if (!speech || state.voiceMode !== 'active') return;
  const result = await speech.speak(text);
  if (speech !== state.speech || epoch !== state.voiceEpoch || state.voiceMode !== 'active') return;
  if (!result || result.status !== 'spoken') {
    await pauseVoice({
      blocked: true,
      detail: 'Speech was interrupted or could not play. Tap Resume to try again, or Exit to type.',
    });
  }
}

function releaseWakeLock() {
  const lock = state.wakeLock;
  state.wakeLock = null;
  if (lock && !lock.released) {
    lock.release().catch((error) => console.warn('Could not release the screen wake lock:', error.name));
  }
}

async function keepScreenAwake() {
  if (!navigator.wakeLock || document.hidden || state.voiceMode !== 'active' || state.wakeLock) return;
  const epoch = state.voiceEpoch;
  try {
    const lock = await navigator.wakeLock.request('screen');
    if (epoch !== state.voiceEpoch || state.voiceMode !== 'active' || document.hidden) {
      await lock.release();
      return;
    }
    state.wakeLock = lock;
    lock.addEventListener('release', () => {
      if (state.wakeLock === lock) state.wakeLock = null;
    }, { once: true });
  } catch (error) {
    if (epoch !== state.voiceEpoch || state.voiceMode !== 'active') return;
    state.voiceDetail = 'Keep this screen open. The browser could not keep the display awake.';
    renderVoiceStage();
  }
}

async function setupVoice({ defer = false, automatic = state.wantHandsFree } = {}) {
  const { kind: sttKind, apiKey: sttKey } = (state.creds && state.creds.stt) || {};
  if (sttKind === 'off') {
    els['b-mic'].hidden = true;
    els['handsfree-wrap'].hidden = true;
    return;
  }
  if (automatic && (document.hidden || state.voiceMode === 'paused')) return;
  if (defer) {
    const possible = !!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia)
      || !!(window.SpeechRecognition || window.webkitSpeechRecognition);
    els['b-mic'].hidden = !possible;
    els['handsfree-wrap'].hidden = !possible || !ttsSupported();
    return;
  }
  const epoch = state.voiceEpoch;
  const navigation = state.navigation;
  if (state.voiceSetup) state.voiceSetup.abort();
  const setup = new AbortController();
  state.voiceSetup = setup;
  try {
    const voice = await createVoice({
      stt: STT_PRESETS[sttKind] && sttKey ? { kind: sttKind, apiKey: sttKey } : null,
      preferRecorder: sttKind === 'groq' || sttKind === 'openai',
      signal: setup.signal,
    });
    if (setup.signal.aborted || epoch !== state.voiceEpoch || navigation !== state.navigation) {
      voice.dispose();
      return;
    }
    state.voice = voice;
  } catch (error) {
    if (setup.signal.aborted || epoch !== state.voiceEpoch || navigation !== state.navigation) return;
    state.voice = null;
    state.voiceDetail = `Microphone setup failed: ${error.message}`;
    say(state.voiceDetail);
  } finally {
    if (state.voiceSetup === setup) state.voiceSetup = null;
  }
  const on = state.voice && state.voice.available;
  els['b-mic'].hidden = !on;
  const canDrive = on && state.speech && state.speech.supported();
  els['handsfree-wrap'].hidden = !canDrive;
  if (canDrive && automatic && !state.handsFree) setHandsFree(true);
  if (!canDrive && automatic) {
    state.voiceMode = 'blocked';
    state.voiceDetail = (state.voice && state.voice.unavailableReason)
      || state.voiceDetail || 'Voice is not available. Check microphone and speech settings, or Exit to type.';
    renderVoiceStage();
  }
  if (on && state.voice.mode === 'recorder') {
    // Say it once, plainly, rather than surprising anyone with a bill.
    say(`Dictation goes through ${state.voice.transcriberLabel} — roughly a penny for a whole interview.`);
  } else if (state.voice && state.voice.unavailableReason && els.stt.value !== 'off') {
    say(state.voice.unavailableReason);
  }
}

async function listenOnce({ prompt, autoStop }) {
  const voice = state.voice;
  const epoch = state.voiceEpoch;
  const navigation = state.navigation;
  els.listening.hidden = false;
  els['listening-label'].textContent = 'Getting the microphone ready…';
  els['b-mic'].textContent = 'Stop listening';
  try {
    return await voice.listen({
      prompt,
      autoStop,
      onPhase: (phase) => {
        if (epoch !== state.voiceEpoch || navigation !== state.navigation) return;
        els['listening-label'].textContent = phase === 'transcribing'
          ? 'Turning speech into text…'
          : voice.mode === 'recorder' ? 'Listening — stop talking when you’re done.' : 'Listening…';
      },
      onInterim: (t) => {
        if (epoch !== state.voiceEpoch || navigation !== state.navigation) return;
        els.answer.value = t;
        queueDraftSave();
      },
      onLevel: (rms) => {
        els.pulse.style.setProperty('--level', String(0.6 + Math.min(1.7, rms * 16)));
      },
    });
  } finally {
    if (epoch === state.voiceEpoch && navigation === state.navigation) {
      els.listening.hidden = true;
      els['b-mic'].textContent = 'Answer out loud';
      els.pulse.style.removeProperty('--level');
    }
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
  if (state.cycling || state.busy || !state.handsFree || !state.voice || !state.session) return;
  const voice = state.voice;
  const id = state.session.id;
  const navigation = state.navigation;
  const epoch = state.voiceEpoch;
  const running = () => state.handsFree && state.voiceMode === 'active'
    && voice === state.voice && epoch === state.voiceEpoch && currentView(id, navigation);
  state.cycling = true;
  const drive = createDriveLoop({
    speak: (text) => running() ? speakForVoice(text) : undefined,
    listen: ({ prompt, confirm }) => listenForDriving(prompt, !!confirm, voice, running),
    openTurn: () => running() ? openTurn(state.session) : null,
    submit: (text) => running()
      ? submitAndAdvance([state.capturePrefix, text].filter(Boolean).join(' '), 'voice') : undefined,
    skip: () => running() ? doSkip() : undefined,
    wrap: () => running() ? wrapUp('Wrapped up by voice.') : undefined,
    offerWrap: () => running() ? shouldOfferWrap(state.session) : null,
    answeredCount: () => state.session.turns.filter((turn) => turn.answer || turn.skipped).length,
    pause: () => pauseVoice({ command: true }),
    exit: () => exitVoice({ command: true }),
    scratch: () => {
      if (!running()) return;
      cancelDraftSave();
      state.capturePrefix = '';
      els.answer.value = '';
      queueDraftSave();
    },
    onState: (phase) => {
      if (!running()) return;
      if (phase !== 'stopped' && phase !== 'paused') {
        setVoicePhase(phase === 'processing' ? 'thinking' : phase === 'listening' ? 'starting' : phase);
      }
    },
    // Reading it is also the moment it counts as offered, so a reload does not re-ask.
    advisory: (reason) => {
      if (!running()) return null;
      const text = wrapAdvisory(state.session, reason);
      if (text) state.session = setWrapOffered(state.session, true, now());
      return text;
    },
    notify: (text) => {
      if (!running()) return;
      if (state.voicePhase === 'speaking') {
        state.voiceCaption = text || '';
        state.voiceDetail = '';
      } else {
        state.voiceDetail = text || '';
      }
      renderVoiceStage();
    },
    running,
    config: { trigger: state.trigger },
  });
  state.drive = drive;
  try {
    const result = await drive.run();
    if (result === 'stopped' && running()) {
      await pauseVoice({
        blocked: true,
        detail: state.voiceDetail || state.voiceCaption
          || 'Voice paused after input trouble. Resume to retry, or Exit to type.',
      });
    }
  } catch (e) {
    if (running()) {
      await pauseVoice({ blocked: true, detail: `Voice stopped: ${e.message}` });
    }
  } finally {
    if (state.drive === drive) {
      state.cycling = false;
      state.drive = null;
      if (state.handsFree && state.voiceMode === 'active' && !state.busy && currentSession(id)
          && (epoch !== state.voiceEpoch || voice !== state.voice)) {
        runHandsFree();
      }
    }
  }
}

/**
 * One capture, ended by the trigger word rather than by a pause.
 *
 * `autoStop` is off: the engine's own endpoint is a pause, and a driver pauses to change
 * lane. `isComplete` is what ends it instead, and it fires on a command too — someone
 * saying "skip this one" has finished talking by definition.
 */
async function listenForDriving(prompt, confirm, voice, running) {
  if (!running()) return '';
  const prefix = els.answer.value.trim();
  state.capturePrefix = prefix;
  state.captureConfirm = confirm;
  state.voiceTranscript = confirm ? '' : null;
  setVoicePhase('starting', 'Getting the microphone ready…');
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
    return await voice.listen({
      prompt,
      autoStop: false,
      isComplete,
      settleMs: DRIVING.settleMs,
      gate: confirm ? CONFIRM_GATE : DRIVING_GATE,
      maxSegments: confirm ? 1 : 6,
      onInterim: (t) => {
        if (!running()) return;
        if (confirm) {
          state.voiceTranscript = t;
        } else {
          els.answer.value = [prefix, t].filter(Boolean).join(' ');
          state.answerSource = 'voice';
          queueDraftSave();
        }
        renderVoiceStage();
      },
      onLevel: (rms) => {
        if (!running()) return;
        els.pulse.style.setProperty('--level', String(0.6 + Math.min(1.7, rms * 16)));
        state.voiceStage.level(rms);
      },
      onLevelUnavailable: () => {
        if (!running()) return;
        state.voiceDetail = `Listening without a live meter. Say “${state.trigger}” to send.`;
        renderVoiceStage();
      },
      onPhase: (phase) => {
        if (running()) setVoicePhase(phase, phase === 'listening' ? '' : 'Turning the captured audio into words…');
      },
    });
  } finally {
    if (running()) {
      els.listening.hidden = true;
      els['b-mic'].textContent = 'Answer out loud';
      els.pulse.style.removeProperty('--level');
      els.answer.value = prefix;
      state.voiceTranscript = null;
      state.voiceStage.reset();
    }
  }
}

/** The open question, used to prime the transcriber with this turn's vocabulary. */
function currentQuestion() {
  const t = state.session && openTurn(state.session);
  return t ? t.question : '';
}

function teardownVoice() {
  if (state.voiceSetup) state.voiceSetup.abort();
  state.voiceSetup = null;
  if (state.previewSpeech) {
    state.previewSpeech.dispose();
    state.previewSpeech = null;
    els['speech-check'].textContent = 'Preview cancelled.';
    onSpeechChange();
  }
  setHandsFree(false);
  if (state.voice) state.voice.dispose();
  if (state.speech) state.speech.dispose();
  state.voice = null;
  state.speech = null;
  state.drive = null;
  state.cycling = false;
  state.capturePending = false;
  state.captureConfirm = false;
  state.voiceTranscript = null;
  state.readback = false;
  els['b-mic'].hidden = true;
  els['handsfree-wrap'].hidden = true;
  els.listening.hidden = true;
}

function setHandsFree(on) {
  state.handsFree = on;
  els.handsfree.checked = on;
  if (!on) {
    if (state.voiceSetup) state.voiceSetup.abort();
    state.voiceSetup = null;
    state.voiceEpoch++;
    state.voiceMode = 'manual';
    if (state.drive) state.drive.stop();
    if (state.voice) { state.voice.cancelSpeech(); state.voice.abort(); }
    if (state.speech) state.speech.cancel();
    releaseWakeLock();
    if (state.voiceStage) state.voiceStage.reset();
    renderVoiceStage();
    return;
  }
  state.voiceMode = 'active';
  state.voicePhase = state.busy ? 'thinking' : 'starting';
  renderVoiceStage();
  keepScreenAwake();
  runHandsFree();
}

async function pauseVoice({ command = false, blocked = false, detail = '' } = {}) {
  if (state.voiceMode === 'manual' || state.capturePending) return;
  const voice = state.voice;
  const id = state.session && state.session.id;
  const navigation = state.navigation;
  const wasCapturing = state.voicePhase === 'listening' || state.voicePhase === 'transcribing';
  const confirmation = state.captureConfirm;
  const prefix = state.capturePrefix;
  const captured = capturedDraftText(prefix);
  if (command || confirmation) els.answer.value = prefix;
  else if (wasCapturing) els.answer.value = retainedDraft(prefix, captured);
  cancelDraftSave();
  state.voiceEpoch++;
  const epoch = state.voiceEpoch;
  if (state.voiceSetup) state.voiceSetup.abort();
  state.voiceSetup = null;
  state.handsFree = false;
  els.handsfree.checked = false;
  if (state.drive) state.drive.stop();
  if (state.speech) state.speech.cancel();
  releaseWakeLock();
  state.voiceMode = blocked ? 'blocked' : 'paused';
  state.voicePhase = state.voiceMode;
  state.voiceTranscript = null;
  state.voiceCaption = state.readback ? 'Your refined prompt' : currentQuestion();
  state.voiceDetail = detail || 'Microphone off. Your captured words stay here. Tap Resume when you are ready.';
  state.capturePending = !!voice;
  state.voiceStage.reset();
  renderVoiceStage();
  let finalText = '';
  try {
    if (voice) finalText = await voice.pause();
  } catch (error) {
    if (currentView(id, navigation)) {
      state.voiceDetail = `Paused. The last audio segment could not be saved: ${error.message}`;
    }
  } finally {
    if (voice) voice.dispose();
    if (state.voice === voice) state.voice = null;
    if (epoch === state.voiceEpoch && currentView(id, navigation)
        && ['paused', 'blocked'].includes(state.voiceMode)) {
      if (!command && !confirmation && wasCapturing) {
        els.answer.value = retainedDraft(prefix, finalText || captured);
        if (els.answer.value !== prefix) state.answerSource = 'voice';
      }
      await flushDraft();
      if (epoch === state.voiceEpoch && currentView(id, navigation)
          && ['paused', 'blocked'].includes(state.voiceMode)) {
        state.capturePending = false;
        els.listening.hidden = true;
        renderVoiceStage();
        els['b-voice-pause'].focus();
      }
    }
  }
}

async function resumeVoice() {
  if (state.capturePending || !state.session) return;
  state.voiceEpoch++;
  state.voiceMode = 'active';
  state.voicePhase = 'starting';
  state.voiceDetail = '';
  prepareSpeechOutput();
  renderVoiceStage();
  keepScreenAwake();
  if (state.readback && state.session.status === 'done') {
    await finishReadback();
    return;
  }
  if (state.voiceWrapPending) {
    state.voiceWrapPending = null;
    await wrapUp('The interview is ready to write up.');
    return;
  }
  if (state.writing === state.session.id) return;
  forgetVerdict();
  await setupVoice({ automatic: true });
}

async function exitVoice({ command = false } = {}) {
  if (state.voiceMode === 'manual') return;
  if (command) els.answer.value = state.capturePrefix;
  else if (!state.captureConfirm && ['listening', 'transcribing'].includes(state.voicePhase)) {
    els.answer.value = retainedDraft(state.capturePrefix, capturedDraftText(state.capturePrefix));
  }
  const unfinishedAudio = state.voice && state.voice.mode === 'recorder'
    && (state.capturePending || ['listening', 'transcribing'].includes(state.voicePhase));
  const done = state.session && (state.session.status === 'done' || state.writing === state.session.id);
  const navigation = state.navigation;
  teardownVoice();
  state.wantHandsFree = false;
  savePrefs({ handsFree: false });
  await flushDraft();
  if (navigation !== state.navigation) return;
  if (done) {
    show('panel-done');
    if (state.session.status === 'done') renderDone();
    els['b-copy'].focus();
  } else {
    show('panel-interview');
    await setupVoice({ defer: true, automatic: false });
    renderVoiceStage();
    els.answer.focus();
  }
  if (unfinishedAudio) {
    say('Voice stopped. Transcribed words are kept; the unfinished audio segment was cancelled.');
  }
}

function capturedDraftText(prefix) {
  const text = els.answer.value.trim();
  if (!prefix) return text;
  if (text === prefix) return '';
  return text.startsWith(`${prefix} `) ? text.slice(prefix.length + 1) : text;
}

function retainedDraft(prefix, captured) {
  const said = parseSpeech(captured, { trigger: state.trigger });
  return said.kind === 'answer' && said.text
    ? [prefix, said.text].filter(Boolean).join(' ') : prefix;
}

// ────────────────────────────────────────────────────────────── wrap-up

/**
 * Write it up, and — if the interview was being driven — read it back.
 *
 * Capture and output have separate lifetimes: release the microphone now, but retain the
 * speech output and its Pause/Exit controls until narration finishes.
 */
async function wrapUp(note) {
  const session = state.session;
  if (!session || state.writing === session.id) return;
  const navigation = state.navigation;
  const provider = state.provider;
  const credentialEpoch = state.credentialEpoch;
  const wasDriving = state.voiceMode === 'active';
  state.writing = session.id;
  state.voiceWrapPending = null;
  state.handsFree = false;
  els.handsfree.checked = false;
  if (state.drive) state.drive.stop();
  if (state.voice) state.voice.dispose();
  state.voice = null;
  state.readback = wasDriving;
  busy(true);
  if (!wasDriving) show('panel-done');
  els['done-title'].textContent = 'Writing it up…';
  els.output.textContent = '';
  els.output.dataset.source = '';
  els['done-meta'].textContent = note || '';
  try {
    if (wasDriving) {
      state.voiceCaption = 'Writing your refined prompt';
      setVoicePhase('thinking', 'Your interview is saved. Bringing it together now.');
      await speakForVoice('Writing it up.');
      setVoicePhase('thinking');
    }
    if (deletedSessions.has(session.id)) return;
    const out = await sessionWork(session, 'synthesis', async (signal) => {
      const result = await runSynthesis(session, {
        provider: credentialEpoch === state.credentialEpoch ? provider : null, now: now(), signal,
      });
      return {
        ...result,
        session: result.ok || result.aborted ? result.session : setStatusField(result.session, 'done', now()),
      };
    });
    if (out.discarded || out.aborted || !currentView(session.id, navigation)) return;
    warn(out);
    if (!out.ok) {
      fail(out.error
        ? `The wrap-up call failed: ${out.error.message}`
        : 'The model returned no usable prompt.');
    }
    renderDone();
    if (state.readback && state.voiceMode === 'active') {
      await finishReadback();
    } else if (state.readback && ['paused', 'blocked'].includes(state.voiceMode)) {
      state.voiceCaption = 'Your refined prompt is ready';
      state.voiceDetail = 'Resume to hear it, or Exit to read and export it.';
      renderVoiceStage();
    } else {
      teardownVoice();
      show('panel-done');
    }
  } catch (error) {
    if (currentView(session.id, navigation)) fail(error);
  } finally {
    if (state.writing === session.id) state.writing = null;
    if (currentView(session.id, navigation)) busy(false);
  }
}

async function finishReadback() {
  const epoch = state.voiceEpoch;
  const id = state.session.id;
  show('panel-interview');
  renderVoiceStage();
  await speakLong(spokenResult(!!state.session.synthesis.text));
  if (epoch !== state.voiceEpoch || !currentSession(id) || state.voiceMode !== 'active') return;
  teardownVoice();
  show('panel-done');
  renderDone();
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
  if (!state.speech || !text) return;
  const epoch = state.voiceEpoch;
  for (const chunk of speechChunks(text)) {
    if (!state.speech || state.voiceMode !== 'active' || epoch !== state.voiceEpoch) return;
    await speakForVoice(chunk);
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
  const navigation = ++state.navigation;
  teardownVoice();
  say('');
  fail(null);
  if (!(await flushDraft())) return;
  if (navigation !== state.navigation) return;
  show('panel-library');
  await refreshLibrary();
  if (navigation === state.navigation) state.library.focus();
}

async function editLibrarySession(session, { name, tags }) {
  try {
    const pending = workBySession.get(session.id);
    if (pending) await pending.promise;
    const latest = await loadSession(session.id);
    if (!latest || deletedSessions.has(session.id)) return;
    const changedAt = now();
    let updated = renameSession(latest, name, changedAt);
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
    const pending = workBySession.get(session.id);
    if (pending) await pending.promise;
    const latest = await loadSession(session.id);
    if (!latest || deletedSessions.has(session.id)) return;
    const updated = archived ? archiveSession(latest, now()) : restoreSession(latest, now());
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
    deletedSessions.add(session.id);
    const pending = workBySession.get(session.id);
    if (pending) pending.controller.abort();
    await deleteSession(session.id);
    if (state.session && state.session.id === session.id) {
      teardownVoice();
      state.session = null;
      state.navigation++;
      busy(false);
    }
    say('Idea deleted from this device.');
    await refreshLibrary();
    await renderResumeList();
  } catch (error) {
    deletedSessions.delete(session.id);
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
      const navigation = ++state.navigation;
      teardownVoice();
      say('');
      fail(null);
      if (!(await flushDraft())) return;
      if (navigation !== state.navigation) return;
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

async function startInterview({ handsFree = state.wantHandsFree } = {}) {
  if (state.starting) return;
  state.starting = true;
  const navigation = ++state.navigation;
  teardownVoice();
  state.wantHandsFree = handsFree;
  savePrefs({ handsFree });
  state.voiceWrapPending = null;
  state.voiceMode = handsFree ? 'active' : 'manual';
  state.voicePhase = 'starting';
  prepareSpeechOutput();
  fail(null);
  requestPersistence();
  try {
    await buildProvider();
  } catch (e) {
    if (navigation === state.navigation) {
      teardownVoice();
      fail(e);
    }
    state.starting = false;
    return;
  }
  if (navigation !== state.navigation) { state.starting = false; return; }
  const platform = state.install ? state.install.state().platform : 'other';
  state.session = seedTurn(createSession({
    id: newSessionId(),
    now: now(),
    device: platform === 'other' ? 'desktop' : platform,
  }), { now: now() });
  busy(false);
  els.answer.value = '';
  state.capturePrefix = '';
  await persist();
  state.starting = false;
  if (navigation !== state.navigation) return;
  show('panel-interview');
  render();
  await setupVoice({ defer: !handsFree, automatic: handsFree });
  if (state.voiceMode === 'manual') els.answer.focus();
  else els['b-voice-pause'].focus();
}

async function resumeInterview(id) {
  const navigation = ++state.navigation;
  teardownVoice();
  state.wantHandsFree = loadPrefs().handsFree === true;
  prepareSpeechOutput();
  say('');
  fail(null);
  if (!(await flushDraft())) return;
  const pending = workBySession.get(id);
  let completed = null;
  if (pending) {
    say('Finishing the request already running for this idea…');
    completed = await pending.promise;
  }
  if (navigation !== state.navigation) return;
  const s = completed && !completed.discarded ? completed.session : await loadSession(id);
  if (navigation !== state.navigation) return;
  if (!s) { fail('That session was written by a newer version of IdeaForge.'); return; }
  state.session = s;
  state.voiceWrapPending = null;
  state.capturePrefix = '';
  busy(false);

  if (s.status === 'done' && !s.pending && !openTurn(s)) {
    teardownVoice();
    renderDone();
    show('panel-done');
    return;
  }

  try {
    await buildProvider();
  } catch (e) {
    if (navigation !== state.navigation) return;
    say(`Continuing without a model (${e.message}). Questions will come from the built-in checklist.`);
    state.provider = null;
  }
  if (navigation !== state.navigation) return;

  if (s.pending && s.pending.kind === 'synthesis') {
    show('panel-done');
    els['done-title'].textContent = 'Picking up the write-up…';
    els.output.textContent = '';
    els['done-meta'].textContent = '';
    const provider = state.provider;
    const out = await sessionWork(s, 'synthesis', async (signal) => {
      const result = await resumeSynthesis(s, { provider, now: now(), signal });
      return { ...result,
        session: result.ok || result.aborted ? result.session : setStatusField(result.session, 'done', now()) };
    });
    if (out.discarded || out.aborted || !currentView(id, navigation)) return;
    warn(out);
    if (!out.ok) {
      fail(out.error
        ? `The wrap-up call failed: ${out.error.message}`
        : 'The refined prompt could not be regenerated, but the interview is intact.');
    }
    renderDone();
    return;
  }

  state.voiceMode = state.wantHandsFree ? 'active' : 'manual';
  state.voicePhase = 'thinking';
  show('panel-interview');

  if (s.pending && s.pending.kind === 'turn') {
    busy(true, 'picking up where the last question left off…');
    try {
      const provider = state.provider;
      const out = await sessionWork(s, 'turn', (signal) => resumeTurn(s, { provider, now: now(), signal }));
      if (out.discarded || out.aborted || !currentView(id, navigation)) return;
      warn(out);
    } catch (e) { if (currentView(id, navigation)) fail(e); }
    finally { if (currentView(id, navigation)) busy(false); }
  }
  if (!currentView(id, navigation)) return;
  if (state.session.status === 'done' && !openTurn(state.session)) {
    renderDone();
    show('panel-done');
    return;
  }
  if (!openTurn(state.session)) {
    await nextQuestion();
    if (!currentView(id, navigation) || state.session.status === 'done') return;
  }
  render();
  els.answer.value = state.session.draftAnswer || '';
  await setupVoice({ defer: !state.wantHandsFree, automatic: state.wantHandsFree });
  if (state.voiceMode === 'manual') els.answer.focus();
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
  els['b-start'].onclick = () => startInterview({ handsFree: false }).catch(fail);
  els['b-start-voice'].onclick = () => startInterview({ handsFree: true }).catch(fail);
  els.speech.onchange = () => {
    if (state.previewSpeech) state.previewSpeech.dispose();
    state.previewSpeech = null;
    els['speech-check'].textContent = '';
    onSpeechChange();
  };
  const invalidatePreview = () => {
    if (state.previewSpeech) state.previewSpeech.dispose();
    state.previewSpeech = null;
    onSpeechChange();
  };
  els['tts-consent'].onchange = invalidatePreview;
  for (const control of [els.ttskey, els['tts-voice']]) {
    control.addEventListener('input', () => {
      invalidatePreview();
      els['speech-check'].textContent = 'Check and preview these speech settings before using them.';
    });
  }
  els['browser-voice'].onchange = () => { invalidatePreview(); saveSpeechPreferences(); };
  els['speech-rate'].oninput = () => {
    els['speech-rate-value'].value = `${Number(els['speech-rate'].value).toFixed(2)}×`;
  };
  els['speech-rate'].onchange = () => { invalidatePreview(); saveSpeechPreferences(); };
  els['b-speech-preview'].onclick = () => previewSpeech().catch(fail);
  els['b-voice-pause'].onclick = () => {
    const action = ['paused', 'blocked'].includes(state.voiceMode) ? resumeVoice() : pauseVoice();
    action.catch(fail);
  };
  els['b-voice-exit'].onclick = () => exitVoice().catch(fail);
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
    if (state.busy || !state.session || !openTurn(state.session)) return;
    if (state.voice && !els.listening.hidden) { state.voice.stop(); return; }
    setHandsFree(false);
    const navigation = state.navigation;
    const id = state.session.id;
    try {
      if (!state.voice) {
        prepareSpeechOutput();
        await setupVoice({ automatic: false });
      }
      if (!currentView(id, navigation) || !state.voice || !state.voice.available) return;
      const epoch = state.voiceEpoch;
      // autoStop false: the button is press-to-talk, so the user decides when they are
      // done. Whatever came back lands in the box for them to edit before sending.
      const heard = await listenOnce({ prompt: currentQuestion(), autoStop: false });
      if (currentView(id, navigation) && epoch === state.voiceEpoch && heard.trim()) {
        els.answer.value = heard;
        state.answerSource = 'voice';
        queueDraftSave();
      }
    } catch (e) { fail(e); }
  };
  els.handsfree.onchange = async () => {
    const on = els.handsfree.checked;
    state.wantHandsFree = on;
    savePrefs({ handsFree: on });
    if (!on) { await exitVoice(); return; }
    state.voiceEpoch++;
    state.voiceMode = 'active';
    state.voicePhase = 'starting';
    prepareSpeechOutput();
    renderVoiceStage();
    try {
      if (!state.voice) await setupVoice({ automatic: true });
      else setHandsFree(true);
    } catch (error) { fail(error); }
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
    const navigation = ++state.navigation;
    teardownVoice();
    say('');
    fail(null);
    if (!(await flushDraft())) return;
    if (navigation !== state.navigation) return;
    show('panel-setup');
    renderSpeechSettings();
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
    state.navigation++;
    teardownVoice();
    say('');
    fail(null);
    show('panel-setup');
    await renderResumeList();
  };
  els['b-reopen'].onclick = async () => {
    if (!state.session || state.busy || workBySession.has(state.session.id)
        || state.writing === state.session.id) return;
    const navigation = ++state.navigation;
    const session = state.session;
    teardownVoice();
    state.wantHandsFree = loadPrefs().handsFree === true;
    prepareSpeechOutput();
    try {
      await buildProvider();
    } catch (error) {
      say(`Continuing without a model (${error.message}). Questions will come from the built-in checklist.`);
      state.provider = null;
    }
    if (!currentView(session.id, navigation)) return;
    const reopenedAt = now();
    state.session = reopen(state.session, reopenedAt);
    if (state.session.archivedAt) state.session = restoreSession(state.session, reopenedAt);
    await persist();
    if (!currentView(session.id, navigation)) return;
    state.voiceMode = state.wantHandsFree ? 'active' : 'manual';
    show('panel-interview');
    await nextQuestion();
    if (currentView(session.id, navigation) && !els['panel-interview'].hidden) {
      await setupVoice({ defer: !state.wantHandsFree, automatic: state.wantHandsFree });
    }
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
    if (document.visibilityState !== 'hidden') return;
    if (state.voiceMode === 'active') {
      pauseVoice({ detail: 'Voice paused while this screen was away. Tap Resume to continue.' }).catch(fail);
    } else {
      flushDraft().catch(fail);
    }
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
    + '“scratch that”, “wrap it up”, “pause voice” or “exit voice”.';
  if (write) savePrefs({ trigger: state.trigger });
}

async function boot() {
  state.voiceStage = createVoiceStage(els['voice-stage']);
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
  renderSpeechSettings();
  if (window.speechSynthesis && window.speechSynthesis.addEventListener) {
    window.speechSynthesis.addEventListener('voiceschanged', renderSpeechVoices);
  }
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
