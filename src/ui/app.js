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
  createProvider, PROVIDER_CHOICES, defaultProviderKind, isLoopback, presetModels, inferenceDestination,
} from '../providers/index.js';
import { ProviderError } from '../providers/errors.js';
import {
  deleteSession, importSessions, saveSession, loadSession, listSessions, newSessionId,
} from '../store/sessions.js';
import {
  updateCredentials, loadCredentials, clearCredentials, maskKey,
  emptyKeyring, credsFor, withCreds, withStt, withTts, hasAnyKey,
} from '../store/secrets.js';
import { requestPersistence } from '../store/db.js';
import { loadPrefs, savePrefs, speechPreferences, PREFS_KEY } from '../store/prefs.js';
import { createInferenceConsent } from '../store/inference-consent.js';
import { applyTheme } from './theme.js';
import { createVoice, STT_PRESETS, forgetVerdict, ttsSupported } from '../voice/index.js';
import { createSpeechOutput } from '../voice/output.js';
import { createVoiceStage } from './voice-stage.js';
import { DRIVING_GATE, CONFIRM_GATE } from '../voice/vad.js';
import {
  backupFilename, buildBackup, MAX_BACKUP_BYTES, parseBackup,
} from '../core/backup.js';
import { createInstallController } from './install.js';
import { createLibraryView } from './library.js';
import { canShareFile, canShareText, downloadText, shareTextFile } from './share.js';
import { wireOverflowMenu } from './menu.js';
import { confirmAction, cancelConfirmation } from './dialog.js';
import { VERSION } from '../version.js';

const $ = (id) => document.getElementById(id);
const els = {};
for (const id of [
  'meter', 'meter-fill', 'meter-label', 'b-library', 'b-nav-new', 'b-settings',
  'panel-setup', 'provider', 'provider-note', 'field-key', 'apikey', 'keylink',
  'field-base', 'baseurl', 'base-note', 'field-model', 'model', 'model-list', 'model-note',
  'field-wrapmodel', 'wrapmodel', 'model-settings',
  'start-note', 'connect', 'connect-summary', 'connect-summary-text', 'b-connect-change', 'connect-body',
  'connect-heading', 'connect-status', 'b-connect-next', 'b-connect-back', 'key-error',
  'key-required', 'model-required',
  'settings-connection-summary', 'connection-error', 'credential-error',
  'inference-agreement', 'inference-disclosure', 'inference-consent', 'inference-label', 'inference-status',
  'settings-voice-summary', 'settings-appearance-summary', 'settings-data-summary',
  'appearance-status', 'voice-preferences-status', 'data-status', 'keys-summary', 'browser-voice-note',
  'install-card', 'install-title', 'install-note', 'b-install',
  'b-start', 'b-start-voice', 'b-check', 'resume', 'resume-rows', 'b-view-all',
  'stt', 'stt-note', 'field-sttkey', 'sttkey', 'sttkey-note', 'b-forget', 'version',
  'field-stopword', 'stopword', 'stopword-note',
  'speech', 'speech-note', 'browser-voice', 'field-browser-voice', 'speech-rate',
  'speech-rate-value', 'field-tts', 'ttskey', 'tts-voice', 'tts-consent',
  'b-speech-preview', 'speech-check', 'speech-options',
  'panel-interview', 'question-card', 'bridge', 'question', 'asking', 'asking-label', 'chips', 'answer',
  'manual-interview', 'voice-stage', 'voice-status', 'voice-signal', 'voice-question',
  'voice-transcript', 'voice-detail', 'voice-backend', 'b-voice-pause', 'b-voice-exit',
  'b-send', 'b-mic', 'b-skip', 'b-wrap', 'turnline', 'coverage',
  'wrap-offer', 'wrap-offer-text', 'b-wrap-offer', 'b-wrap-keep',
  'listening', 'listening-label', 'pulse', 'handsfree', 'handsfree-wrap',
  'thread-wrap', 'thread', 'b-thread-more', 'composer',
  'panel-library', 'library-search', 'library-status', 'library-tag',
  'library-rows', 'library-empty', 'library-zero', 'b-library-zero-new', 'library-count',
  'b-library-new', 'b-backup', 'b-import', 'backup-file', 'library-detail',
  'settings-index', 'theme-choice',
  'panel-done', 'done-title', 'done-meta', 'output',
  'done-actions', 'b-done-more', 'done-menu',
  'b-share', 'b-copy', 'b-download', 'b-reopen', 'b-new', 'note', 'err',
  'main', 'toasts', 'err-toast', 'b-err-dismiss',
]) els[id] = $(id);

const state = {
  session: null,
  provider: null,
  providerPermit: null,
  providerRevision: null,
  voicePermit: null,
  inferenceDestination: null,
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
  voiceMeterWarning: '',
  voiceBackend: 'Browser voice',
  capturePrefix: '',
  capturePrefixSource: 'typed',
  capturePending: false,
  captureConfirm: false,
  readback: false,
  writing: null,
  /**
   * A failed wrap-up waiting for the Done panel: `{ id, message }` or null. A spoken
   * write-up fails on the voice stage, and the move to Done afterwards is a panel change
   * that clears #err — so showDone() puts the failure back where it is read. Dropped when
   * the app leaves for Start, Settings or Ideas, where it would be stale.
   */
  doneFailure: null,
  /** The last failed save, `{ id, message, dismissed }`, until that idea saves again. */
  saveFailure: null,
  credentialEpoch: 0,
  credentialError: '',
  keysMayRemain: false,
  forgetting: false,
  wakeLock: null,
  wakeLockRequest: null,
  speechRevoked: false,
  /** The context owning Start; retired completion cannot clear a newer Start. */
  starting: null,
  voiceWrapPending: null,
  /**
   * The wrap-up offer on screen: `{ id, text }` for the idea it was made to, or null.
   * `session.wrapOffered` is what stops the engine offering twice; this is only whether
   * the card is still showing, and it never outlives the turn it was offered on.
   */
  wrapOffer: null,
  /** The idea the thread was last drawn for, and whether its older pairs are unfolded. */
  threadFor: null,
  threadExpanded: false,
  connectCheck: null,
  connectAdvance: null,
  connectRevision: 0,
  connectPresentation: 0,
  connectStep: 'provider',
};

const now = () => Date.now();
const workBySession = new Map();
const deletedSessions = new Set();
const inference = createInferenceConsent({ onRetire: retireInference });
const barActions = document.querySelector('.bar-right');
const barTitle = document.querySelector('.bar h1');

// ─────────────────────────────────────────────────────────────── plumbing

/**
 * Which top-level destination each nav button stands for. Start and Settings share
 * #panel-setup, so the panel alone cannot say which of the two is current: the destination
 * also picks #panel-setup's data-view.
 */
const DESTINATIONS = { ideas: 'b-library', start: 'b-nav-new', settings: 'b-settings' };

/**
 * Toggle panels with `hidden` — never a route — and tell the shell which one is showing.
 *
 * Below 600px navigation is a bottom bar or a contextual detail bar; wider windows use
 * the same controls in a rail. Detail screens have no current top-level destination.
 */
function show(panel, destination) {
  for (const p of ['panel-setup', 'panel-interview', 'panel-library', 'panel-done']) {
    els[p].hidden = p !== panel;
    if (p !== panel) delete els[p].dataset.enter;
  }
  const name = panel.replace(/^panel-/, '');
  document.body.dataset.panel = name;
  const current = destination
    || (name === 'setup' ? 'start' : name === 'library' ? 'ideas' : null);
  // A toast belongs to the screen it was raised on. "Enough here to write up" outlived the
  // interview and sat on the finished document; a key error followed the user into the
  // library. Only a real change of screen clears them: wrapUp shows Done twice, and its
  // failure, raised in between, must survive the second call. So every say() or fail()
  // meant for a new screen comes AFTER the show() that opens it.
  const view = `${name}:${current || ''}`;
  if (name === 'setup') {
    // The view is the destination, never a separate toggle: one attribute, read by app.css.
    els['panel-setup'].dataset.view = current === 'settings' ? 'settings' : 'start';
    if (current === 'start' && view !== shownView) {
      state.connectStep = connectionReady() ? 'ready'
        : state.creds?.byKind?.[currentChoice().id] || els.apikey.value.trim()
          || currentChoice().local ? 'fields' : 'provider';
    }
    renderConnect();
  }
  if (view !== shownView) {
    cancelConfirmation();
    shownView = view;
    say('');
    fail(null);
    enterPanel(els[panel]);
  }
  if (name === 'setup' || name === 'library') state.doneFailure = null;
  for (const [key, id] of Object.entries(DESTINATIONS)) {
    if (key === current) els[id].setAttribute('aria-current', 'page');
    else els[id].removeAttribute('aria-current');
  }
  // The compact detail bar draws Ideas as a back arrow; keep its destination name explicit.
  if (current) els['b-library'].removeAttribute('aria-label');
  else els['b-library'].setAttribute('aria-label', 'Back to ideas');
  // Interview only: Done prints the final figure in its own meta line.
  if (panel !== 'panel-interview') els.meter.hidden = true;
  if (panel === 'panel-done') layoutDoneActions();
  else closeDoneMenu();
  // A loading state belongs to the screen showing it. The call it stood for may settle
  // after navigation, when its own cleanup is (rightly) guarded off the screen it left.
  if (panel !== 'panel-interview') questionBusy(false);
  if (panel !== 'panel-done') outputBusy(false);
  renderShell();
  renderVoiceStage();
}

function renderShell() {
  const detail = ['interview', 'done'].includes(document.body.dataset.panel);
  const title = detail && state.session ? sessionDisplayTitle(state.session) : 'IdeaForge';
  if (barTitle.textContent !== title) barTitle.textContent = title;
  if (detail) barTitle.title = title;
  else barTitle.removeAttribute('title');
}

/** The screen show() last drew, as `panel:destination`, so re-showing it clears nothing. */
let shownView = null;

/**
 * The panel's entry animation, started by the attribute app.css keys `panel-in` off.
 *
 * Set synchronously inside show(), so it can never delay what show()'s caller does next:
 * focus, a speech prime or navigator.share all happen in the same task, on a panel that is
 * already displayed and focusable while its opacity eases in. Start and Settings share
 * #panel-setup, which stays displayed between the two, so the attribute is cleared and the
 * style flushed to restart it. Skipped while a View Transition is drawing the change: that
 * crossfade is the motion, and a slide inside it would be a second one. Reduced motion is
 * app.css's business — the rule only exists under `prefers-reduced-motion: no-preference`.
 */
function enterPanel(panel) {
  if (transitioning) return;
  if ('enter' in panel.dataset) {
    delete panel.dataset.enter;
    void panel.offsetWidth;
  }
  panel.dataset.enter = '';
}

/** True while a navigateTo() View Transition's update is running. */
let transitioning = false;

/**
 * Whether a navigation may run as a same-document View Transition.
 *
 * Every condition is a reason the transition would do harm, not a nicety. Missing API:
 * nothing to call. Reduced motion: the crossfade is exactly the motion asked away. Voice:
 * the update callback runs a frame AFTER the click, with rendering paused until it settles,
 * so the gesture that primes speech output or holds the microphone is gone by then — and a
 * spoken interview has nothing to look at changing anyway. The nav buttons are hidden on
 * the voice stage; this also covers a run still starting, a hands-free loop, a press-to-talk
 * capture and a speech preview.
 */
function transitionAllowed() {
  if (typeof document.startViewTransition !== 'function') return false;
  const media = typeof window.matchMedia === 'function'
    && window.matchMedia('(prefers-reduced-motion: reduce)');
  if (media && media.matches) return false;
  const voiceRun = state.voiceMode !== 'manual' || !!state.drive || !!state.voiceSetup
    || state.capturePending || !!state.previewSpeech || !els['voice-stage'].hidden;
  return !voiceRun;
}

/**
 * Run a navigation, as a View Transition when transitionAllowed() says it is safe.
 *
 * Only the three navigation buttons call this (Ideas, which is also the back arrow on a
 * detail screen, New idea and Settings). Never wrap a handler that needs its gesture —
 * Start voice, Resume and Preview prime speech synchronously, Share calls navigator.share
 * before its first await — because startViewTransition defers its update past the click.
 *
 * `open` is called at once, inside the click, so it claims state.navigation before anything
 * else can: a later tap — Continue, Start, another nav button — bumps the count and wins,
 * exactly as without a transition. The guard is read here too, before open's own
 * teardownVoice() would make a voice run look finished. What it is handed is `swap`, the
 * one moment the screen changes; see transitionSwap.
 */
function navigateTo(open) {
  return open(transitionAllowed() ? transitionSwap : directSwap);
}

/** A screen change with no transition: just make it. */
function directSwap(change) {
  return change();
}

/** `swap` if it is one of the two above; a click event or library callback argument is not. */
function swapWith(swap, change) {
  return (swap === transitionSwap ? transitionSwap : directSwap)(change);
}

/**
 * Make one synchronous screen change inside a View Transition, and resolve with what
 * `change` returned once it has run.
 *
 * Only the swap is inside: the IndexedDB write before it and the reads after it are not.
 * While an update is pending the browser holds a frozen frame and every tap reaches nothing,
 * so wrapping a whole navigation would hide the library's skeletons and swallow taps for as
 * long as storage took. `change` returns false when its navigation was superseded in the
 * frame before the update ran; the transition is skipped then, since an overlay with
 * nothing to animate would still swallow taps for its length.
 *
 * `change` runs exactly once whatever happens to the transition: skipped, aborted or
 * refused outright, the browser still calls the update, and a refusal falls back to a plain
 * call. So app state never depends on the animation. The transition's ready/finished
 * rejections are only about the animation and are swallowed.
 */
function transitionSwap(change) {
  let ran = false;
  let result;
  let transition;
  const update = () => {
    ran = true;
    transitioning = true;
    try {
      result = change();
    } finally {
      transitioning = false;
    }
    if (result === false && transition) transition.skipTransition();
  };
  try {
    transition = document.startViewTransition(update);
  } catch {
    return ran ? result : change();
  }
  if (!transition) return ran ? result : change();
  for (const done of [transition.ready, transition.finished]) {
    if (done && typeof done.catch === 'function') done.catch(() => {});
  }
  return Promise.resolve(transition.updateCallbackDone).then(() => (ran ? result : change()));
}

/**
 * After a navigation, put focus on the destination's heading, so a screen reader announces
 * where the button went and the next Tab starts at the top of the new screen rather than
 * back in the navigation bar. Unless focus is already somewhere inside the destination:
 * someone who tapped into the library search while the list was loading keeps it.
 * Interview and Done are reached by other routes and keep their own deliberate targets
 * (#answer, the Done primary, the voice stage's Pause).
 */
function focusHeading(panel) {
  const root = els[panel];
  if (root.hidden) return;
  // Start and Settings share #panel-setup, so the heading of the view just left is "inside"
  // too — and is now display:none, which has already dropped focus to <body>.
  const active = document.activeElement;
  if (root.contains(active) && active.getClientRects().length > 0
      && !active.matches('h2[tabindex="-1"]')) return;
  const heading = panel === 'panel-setup'
    ? root.querySelector(root.dataset.view === 'settings'
      ? ':scope > h2.settings-only' : '.start-hero h2')
    : root.querySelector('h2[tabindex="-1"]');
  // preventScroll: this runs after the destination's data has loaded, by which time the
  // person may already be scrolling or reaching for a control. Moving focus must not move
  // the page out from under that tap.
  if (heading) heading.focus({ preventScroll: true });
}

/**
 * Done has one primary action, and it is the one this browser can actually do best.
 *
 * Share .md where the share sheet takes a file; everywhere else Copy markdown, with Share
 * moved into the overflow menu if the sheet can at least take text, and gone if there is no
 * sheet at all — it would only download, and Download is already in the menu. The decision
 * is synchronous and made as the panel is drawn, so the Share click itself still calls
 * navigator.share before anything can await. Buttons are moved, never copied: every id is
 * one element, which is what the probes and screenshots find.
 */
function layoutDoneActions() {
  const files = canShareFile({ filename: 'idea.md', type: 'text/markdown' });
  const primary = files ? els['b-share'] : els['b-copy'];
  const secondary = files ? els['b-copy'] : els['b-share'];
  // Move only what is out of place: a manual write-up lays Done out twice (writing, then
  // written), and prepend re-inserts even a node already first, dropping a focused button's
  // focus to <body> and stranding an open menu's.
  primary.className = '';
  if (els['done-actions'].firstElementChild !== primary) els['done-actions'].prepend(primary);
  secondary.className = 'quiet idea-menu-item';
  if (els['done-menu'].firstElementChild !== secondary) els['done-menu'].prepend(secondary);
  els['b-share'].hidden = !files && !canShareText();
  els['done-actions'].dataset.primary = files ? 'share' : 'copy';
}

/** Done's primary action as last laid out: where focus lands when Done is reached by voice. */
function donePrimary() {
  return els['done-actions'].dataset.primary === 'copy' ? els['b-copy'] : els['b-share'];
}

let closeDoneMenu = () => {};

/**
 * A note is a toast: it hides itself after #toasts' data-note-ms (6s; a probe shortens it
 * there rather than waiting), and a new note restarts the clock rather than inheriting the
 * old one's remaining time. A note takes no pointer events — it must never be what a tap
 * meant for Send or Download lands on — so it cannot be held by hovering; a long one
 * stays up longer instead, at about 240 words a minute.
 */
const NOTE_MS = 6000;
let noteTimer = null;

function noteMs() {
  const ms = Number(els.toasts.dataset.noteMs);
  return Number.isFinite(ms) && ms > 0 ? ms : NOTE_MS;
}

function armNoteTimer() {
  clearTimeout(noteTimer);
  noteTimer = null;
  if (els.note.hidden) return;
  const words = els.note.textContent.split(/\s+/).filter(Boolean).length;
  noteTimer = setTimeout(() => { noteTimer = null; say(''); }, Math.max(noteMs(), words * 250));
}

function say(msg) {
  els.note.textContent = msg || '';
  els.note.hidden = !msg;
  armNoteTimer();
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
  const raised = err && err.message ? err.message : String(err || '');
  const msg = raised || saveWarning();
  // Unchanged text is left alone, so a standing warning is not re-announced on every turn.
  if (els.err.textContent !== msg || els.err.hidden !== !msg) els.err.textContent = msg;
  els.err.hidden = !msg;
  els['err-toast'].hidden = !msg;
  if (raised) console.error(err);
}

/** Errors persist until read: the only ways out are this button, a retry, or a new screen. */
function dismissError() {
  const hadFocus = els['err-toast'].contains(document.activeElement);
  if (state.saveFailure && els.err.textContent === state.saveFailure.message) {
    state.saveFailure.dismissed = true;
  }
  fail(null);
  // The button just vanished under the keyboard; land somewhere that still exists.
  if (hadFocus) els.main.focus({ preventScroll: true });
}

/** Done, with a wrap-up failure raised elsewhere (the voice stage) put back on it. */
function showDone() {
  show('panel-done');
  const failure = state.doneFailure;
  state.doneFailure = null;
  if (failure && state.session && failure.id === state.session.id) fail(failure.message);
}

function busy(on, label) {
  state.busy = on;
  const hasQuestion = !!(state.session && openTurn(state.session));
  els['b-send'].disabled = on || !hasQuestion;
  els['b-skip'].disabled = on || !hasQuestion;
  els['b-mic'].disabled = on || !hasQuestion;
  els.answer.disabled = on || !hasQuestion;
  els['b-wrap'].disabled = on;
  els['b-wrap-offer'].disabled = on;
  els['b-reopen'].disabled = on || !!(state.session
    && (workBySession.has(state.session.id) || state.writing === state.session.id));
  questionBusy(on, label);
  renderInferenceControls();
  if (on && state.voiceMode === 'active') setVoicePhase('thinking');
}

/**
 * The question area's loading state (#107): #asking is the skeleton card, and aria-busy on
 * the card is what app.css swaps the real rows out on. The two move together, always —
 * tools/screenshots.mjs and several probes read "#asking is hidden" as "the turn settled",
 * and a card left busy would hide a perfectly good question behind a skeleton.
 */
function questionBusy(on, label) {
  els.asking.hidden = !on;
  if (on) els['question-card'].setAttribute('aria-busy', 'true');
  else els['question-card'].removeAttribute('aria-busy');
  if (label) els['asking-label'].textContent = label;
}

/**
 * The write-up's loading state (#107): placeholder lines in #output under aria-busy. Every
 * way out removes both — drawExport() for a result, wrapUp's finally for a failure or an
 * abort, show() for leaving Done — so a skeleton never outlives the call it stood in for.
 */
function outputBusy(on) {
  const out = els.output;
  if (!on) {
    out.removeAttribute('aria-busy');
    out.querySelector('.output-skeleton')?.remove();
    return;
  }
  out.textContent = '';
  out.dataset.source = '';
  out.setAttribute('aria-busy', 'true');
  const skeleton = document.createElement('div');
  skeleton.className = 'output-skeleton';
  skeleton.setAttribute('aria-hidden', 'true');
  for (const part of ['heading', 'w1', 'w2', 'w3', 'gap w2', 'w1', 'w3']) {
    const bar = document.createElement('span');
    bar.className = part.split(' ').map((p) => `sk-${p}`).join(' ');
    skeleton.append(bar);
  }
  out.append(skeleton);
}

/** Persist once per settled turn — runTurn bumps `rev` five or six times internally. */
async function persist(session = state.session) {
  if (!session || deletedSessions.has(session.id)) return false;
  try {
    await saveSession(session);
    if (state.saveFailure && state.saveFailure.id === session.id) {
      const cleared = state.saveFailure.message;
      state.saveFailure = null;
      if (els.err.textContent === cleared) fail(null);
    }
    return true;
  } catch (e) {
    // An error, not a note: a note hides itself after six seconds, and a driver looking at
    // the road would never see that their answers exist only in this tab.
    state.saveFailure = {
      id: session.id, dismissed: false,
      message: `This session could not be saved to this device (${e.message}). Export before you close the tab.`,
    };
    if (state.session && state.session.id === session.id) fail(state.saveFailure.message);
    return false;
  }
}

/**
 * The unsaved-session warning, while it still stands: shown by fail(null) in place of an
 * empty #err on that idea's Interview and Done, so a turn clearing its own error, the panel
 * change into Done, or dismissing some other error cannot make it vanish. Only a later
 * successful save of that idea or the user's own dismissal ends it.
 */
function saveWarning() {
  const f = state.saveFailure;
  const panel = document.body.dataset.panel;
  return f && !f.dismissed && state.session && f.id === state.session.id
    && (panel === 'interview' || panel === 'done') ? f.message : '';
}

function withoutModelNote(error) {
  return `Continuing without a model (${error.message}). Questions will come from the built-in checklist.`;
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

function settingsFeedback(id, message = '', status = '') {
  const element = els[id];
  element.textContent = message;
  if (status) element.dataset.status = status;
  else delete element.dataset.status;
}

function credentialProblem(message) {
  state.credentialError = message;
  settingsFeedback('credential-error', message, 'error');
}

async function updateKeyring(update, isCurrent) {
  try {
    const ring = await updateCredentials(update, { isCurrent });
    if (isCurrent()) {
      state.creds = ring;
      state.keysMayRemain = false;
      credentialProblem('');
    }
    return ring;
  } catch (error) {
    if (isCurrent()) credentialProblem(`Key settings could not be saved. Try again: ${error.message}`);
    throw error;
  }
}

function renderSettingsSummaries() {
  const preferences = loadPrefs();
  const speech = els.speech.value === 'openai'
    ? readSpeechFromForm().verified ? 'OpenAI speech checked' : 'OpenAI speech needs a check'
    : els['browser-voice'].selectedOptions[0]?.textContent || 'Browser voice';
  const dictation = STT_PRESETS[els.stt.value]?.label
    || (els.stt.value === 'off' ? 'Dictation off' : 'Browser dictation');
  els['settings-voice-summary'].textContent = `${speech} · ${dictation}`;
  const selected = els['theme-choice'].querySelector('input:checked')?.value || preferences.theme;
  els['settings-appearance-summary'].textContent = selected === 'system'
    ? 'System · follows this device' : `${selected === 'dark' ? 'Dark' : 'Light'} appearance`;
  els['keys-summary'].textContent = state.keysMayRemain
    ? state.forgetting === 'clearing' ? 'Removing the previously saved keys…'
      : state.credentialError || 'Saved keys may remain on this device. Use Forget keys to remove them.'
    : hasAnyKey(state.creds)
      ? 'Keys saved encrypted on this device. Checks apply only to the operation that passed.'
      : 'No keys saved on this device.';
  els['b-forget'].hidden = !hasAnyKey(state.creds) && !state.keysMayRemain;
  els['b-forget'].disabled = state.forgetting === 'clearing';
}

function renderProviderChoices() {
  els.provider.innerHTML = '';
  for (const local of [false, true]) {
    const group = document.createElement('optgroup');
    group.label = local ? 'Local on this device' : 'Hosted providers and Claude viewer';
    for (const c of PROVIDER_CHOICES.filter((choice) => !!choice.local === local)) {
      const o = document.createElement('option');
      o.value = c.id;
      o.textContent = c.label;
      group.append(o);
    }
    els.provider.append(group);
  }
  els.provider.value = (state.creds && state.creds.active) || defaultProviderKind();
  onProviderChange();
}

function currentChoice() {
  return PROVIDER_CHOICES.find((c) => c.id === els.provider.value) || PROVIDER_CHOICES[0];
}

function formInferenceDestination() {
  const choice = currentChoice();
  if (choice.local && els['base-note'].textContent.trim()) {
    throw new ProviderError('config', els['base-note'].textContent.trim());
  }
  return inferenceDestination({
    kind: choice.id, baseUrl: choice.local ? els.baseurl.value.trim() : undefined,
  });
}

function requireInference(expected) {
  const destination = formInferenceDestination();
  return inference.require(destination.id, expected);
}

function retireInference(message) {
  if (state.starting) retireStart(state.starting);
  if (state.voiceSetup) state.voiceSetup.abort();
  state.voiceSetup = null;
  if (state.voiceMode !== 'manual') {
    pauseVoice({ blocked: true, detail: message }).catch(fail);
  } else {
    teardownVoice({ preservePreview: true });
    flushDraft().catch(fail);
  }
}

function renderInferenceControls() {
  const destination = state.inferenceDestination;
  const allowed = !!destination && inference.status(destination.id).allowed;
  const hasQuestion = !!(state.session && openTurn(state.session));
  for (const id of ['b-send', 'b-skip', 'b-mic']) {
    els[id].disabled = state.busy || !hasQuestion || !allowed;
  }
  for (const id of ['b-wrap', 'b-wrap-offer']) els[id].disabled = state.busy || !allowed;
  els.handsfree.disabled = state.capturePending || !allowed;
  els['b-reopen'].disabled = state.busy || !allowed || !!(state.session
    && (workBySession.has(state.session.id) || state.writing === state.session.id));
}

function renderInferenceAgreement() {
  const choice = currentChoice();
  let destination = null;
  let invalid = '';
  try {
    destination = formInferenceDestination();
  } catch (error) {
    if (!(error instanceof ProviderError)) throw error;
    invalid = error.message;
  }
  inference.select(choice.id, destination?.id);
  state.inferenceDestination = destination;
  const current = inference.status(destination?.id);
  els['inference-agreement'].hidden = els['panel-setup'].dataset.view !== 'settings'
    && state.connectStep !== 'ready';
  els['inference-disclosure'].textContent = destination?.local
    ? `IdeaForge sends your idea and answers to ${choice.label} at ${destination.address} to generate questions and your write-up. `
      + 'That server’s operator controls what it keeps. Optional hosted transcription and speech use separate services and choices.'
    : `IdeaForge sends your idea and answers to ${choice.label} to generate questions and your write-up. `
      + `${choice.label}’s terms govern what it keeps.`;
  els['inference-label'].textContent = `I agree to send interview text to ${choice.label}.`;
  els['inference-consent'].checked = current.allowed;
  els['inference-consent'].disabled = !!invalid || !!current.error || state.forgetting === 'clearing';
  els['inference-status'].textContent = invalid || current.problem
    || (current.allowed ? 'Agreed on this device. Uncheck to withdraw; connection checks are separate.'
      : 'Agreement is required for interview requests, separately from microphone and hosted-voice choices.');
  els['inference-status'].dataset.status = invalid || current.problem || current.error ? 'error' : '';
  const blocked = !current.allowed || !!invalid || state.forgetting === 'clearing';
  els['b-start'].disabled = blocked;
  els['b-start-voice'].disabled = blocked || els.stt.value === 'off';
  renderStartNote();
  renderInferenceControls();
}

function cachedInferenceProvider(permit) {
  requireInference(permit);
  if (state.providerPermit?.destination === permit.destination
      && state.providerPermit.grant === permit.grant && state.providerPermit.epoch === permit.epoch
      && state.providerRevision === state.connectRevision) return state.provider;
  return undefined;
}

async function ensureInferenceProvider(permit) {
  const cached = cachedInferenceProvider(permit);
  if (cached !== undefined) return cached;
  const provider = await buildProvider();
  requireInference(permit);
  return provider;
}

function onProviderChange() {
  invalidateConnection();
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
  const transfer = c.local ? 'Answers go to this local server. '
    : c.needsKey ? `Answers go directly to ${c.label}; a provider key is required. ` : '';
  els['provider-note'].textContent = transfer + (c.note || '') + (c.local && onPhone
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
  onBaseChange();
}

/** Field readiness is presentation, not a new successful-network-request gate. */
function connectionReady() {
  const c = currentChoice();
  if (els['base-note'].textContent.trim()) return false;
  if (c.needsKey && !(els.apikey.value.trim() || credsFor(state.creds, c.id).apiKey)) return false;
  if (c.local && !els.baseurl.value.trim()) return false;
  return !c.picksModel || !!(els.model.value.trim() || c.models?.default);
}

function connectionContext() {
  return { revision: state.connectRevision, navigation: state.navigation,
    credentialEpoch: state.credentialEpoch, presentation: state.connectPresentation };
}

function ownsConnection(context) {
  return context.revision === state.connectRevision && context.navigation === state.navigation
    && context.credentialEpoch === state.credentialEpoch
    && context.presentation === state.connectPresentation;
}

function retireStart(context) {
  if (state.starting !== context) return;
  state.starting = null;
  if (context.navigation === state.navigation) teardownVoice();
}

function invalidateConnection() {
  state.connectRevision++;
  state.connectCheck = null;
  state.connectAdvance = null;
  els['key-error'].textContent = '';
  settingsFeedback('connection-error');
  els.apikey.removeAttribute('aria-invalid');
  els.model.removeAttribute('aria-invalid');
  renderConnect();
}

function renderConnect() {
  if (state.starting && !ownsConnection(state.starting)) retireStart(state.starting);
  if (state.connectCheck?.status === 'checking' && !ownsConnection(state.connectCheck)) {
    state.connectCheck = null;
  }
  if (state.connectAdvance && !ownsConnection(state.connectAdvance)) state.connectAdvance = null;
  const c = currentChoice();
  const saved = credsFor(state.creds, c.id);
  const current = state.connectCheck?.revision === state.connectRevision ? state.connectCheck : null;
  const checked = current?.status === 'checked';
  const checking = current?.status === 'checking';
  els.connect.dataset.step = state.connectStep;
  els.connect.toggleAttribute('data-collapsed', state.connectStep === 'ready');
  els['connect-heading'].textContent = state.connectStep === 'provider'
    ? '1. Choose a provider' : '2. Connection details';
  const savedKey = saved.apiKey && (!els.apikey.value.trim() || els.apikey.value.trim() === saved.apiKey);
  const viewer = !c.local && !c.needsKey;
  const status = checked ? (viewer ? 'viewer available' : 'checked this visit')
    : savedKey ? `key saved ${maskKey(saved.apiKey)}` : 'not checked';
  els['connect-summary-text'].textContent =
    [c.label, status, c.picksModel && els.model.value.trim()].filter(Boolean).join(' · ');
  els['settings-connection-summary'].textContent = els['connect-summary-text'].textContent;
  if (checked) els['connect-summary-text'].dataset.icon = 'check';
  else delete els['connect-summary-text'].dataset.icon;
  els['connect-status'].textContent = checking ? `Checking ${c.label}…`
    : checked ? (viewer ? 'Claude viewer available this visit; no network request was tested.'
      : 'Connection checked this visit. Editing the connection needs a new check.')
    : current?.status === 'failed' ? (current.action === 'check'
      ? 'Check failed. Review the connection fields or try again. Checking is optional.'
      : 'Connection needs attention. Review the fields and try again.')
    : state.connectStep === 'provider' && els['panel-setup'].dataset.view === 'start' ? ''
    : `${savedKey ? 'Key saved on this device; n' : 'N'}ot checked this visit. Checking is optional.`;
  els['b-check'].disabled = checking || state.forgetting === 'clearing' || !!els['base-note'].textContent.trim();
  els['b-check'].setAttribute('aria-busy', String(checking));
  els['b-check'].textContent = checking ? 'Checking…'
    : c.needsKey ? 'Check the key' : 'Check the connection';
  els['b-connect-next'].disabled = !!state.connectAdvance;
  els['b-connect-next'].setAttribute('aria-busy', String(!!state.connectAdvance));
  els['b-connect-next'].textContent = state.connectAdvance ? 'Preparing connection…'
    : state.connectStep === 'provider' ? 'Continue' : 'Use this connection';
  els.apikey.required = !!c.needsKey && !saved.apiKey;
  els['key-required'].hidden = !els.apikey.required;
  els.baseurl.required = !!c.local;
  els.model.required = !!c.picksModel && !c.models?.default;
  els['model-required'].hidden = !els.model.required;
  renderSettingsSummaries();
  renderInferenceAgreement();
}

function connectionFailure(error, action) {
  settingsFeedback('connection-error', error.message || String(error), 'error');
  state.connectCheck = { revision: state.connectRevision, status: 'failed', action };
  state.connectStep = 'fields';
  const c = currentChoice();
  if (c.needsKey && (error.code === 'auth'
      || !(els.apikey.value.trim() || credsFor(state.creds, c.id).apiKey))) {
    els['key-error'].textContent = error.code === 'auth'
      ? 'This provider rejected the key. Replace it or check its access.'
      : 'Paste an API key for this provider.';
    els.apikey.setAttribute('aria-invalid', 'true');
  } else if (c.local && (!els.baseurl.value.trim()
      || els.baseurl.getAttribute('aria-invalid') === 'true')) {
    if (!els.baseurl.value.trim()) {
      els['base-note'].textContent = 'Enter the local server address, using localhost or 127.0.0.1.';
    }
    els.baseurl.setAttribute('aria-invalid', 'true');
  } else if (c.picksModel && !(els.model.value.trim() || c.models?.default)) {
    els['model-settings'].open = true;
    els.model.setAttribute('aria-invalid', 'true');
    if (action !== 'check' || !els['model-note'].textContent.trim()) {
      els['model-note'].textContent = 'Enter a known model name, or use Check to read the model list.';
    }
  }
  renderConnect();
  fail(error);
}

function readyConnection(restoreFocus = false) {
  const moving = els['connect-body'].contains(document.activeElement)
    || (restoreFocus && document.activeElement === document.body);
  state.connectAdvance = null;
  state.connectStep = 'ready';
  renderConnect();
  if (moving) els['b-connect-change'].focus();
}

async function advanceConnection() {
  if (state.connectStep === 'provider') {
    state.connectStep = 'fields';
    renderConnect();
    const c = currentChoice();
    els[c.needsKey ? 'apikey' : c.local ? 'baseurl' : 'b-connect-next'].focus();
    return;
  }
  if (state.connectAdvance) return;
  const focused = document.activeElement === els['b-connect-next'];
  const attempt = connectionContext();
  state.connectAdvance = attempt;
  const owns = () => state.connectAdvance === attempt && ownsConnection(attempt);
  renderConnect();
  try {
    // Reuse the actual factory's validation, without saving or making a model request.
    await buildProvider({ persist: false });
    if (owns()) {
      fail(null);
      readyConnection(focused);
    }
  } catch (error) {
    if (!owns()) return;
    connectionFailure(error, 'continue');
    if (focused && (document.activeElement === document.body
        || document.activeElement === els['b-connect-next'])) {
      const field = [els.apikey, els.baseurl, els.model].find((control) =>
        control.getAttribute('aria-invalid') === 'true' && control.getClientRects().length > 0);
      (field || els.provider).focus();
    }
  } finally {
    if (state.connectAdvance === attempt) {
      state.connectAdvance = null;
      renderConnect();
    }
  }
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
    els.baseurl.removeAttribute('aria-invalid');
    els['b-start'].disabled = state.forgetting === 'clearing';
    els['b-start-voice'].disabled = state.forgetting === 'clearing' || els.stt.value === 'off';
    renderStartNote();
    renderConnect();
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
  els.baseurl.setAttribute('aria-invalid', String(bad || ipv6));
  els['b-start'].disabled = bad || ipv6 || state.forgetting === 'clearing';
  els['b-start-voice'].disabled = bad || ipv6 || state.forgetting === 'clearing' || els.stt.value === 'off';
  renderStartNote();
  renderConnect();
}

/**
 * Why a start button is disabled, said beside it. Dictation and the finish word live in
 * Settings now, so on Start a greyed-out Start with voice had no visible reason at all.
 */
function renderStartNote() {
  els['start-note'].textContent = state.forgetting === 'clearing'
    ? 'Saved keys are being removed. Wait for that to finish before starting.'
    : els['base-note'].textContent.trim()
    ? 'Fix the server address above before starting.'
    : !state.inferenceDestination || !inference.status(state.inferenceDestination.id).allowed
      ? state.connectStep === 'ready'
        ? 'Review the interview-text agreement above before starting.'
        : 'Finish the connection details and review the interview-text agreement before starting.'
    : els.stt.value === 'off'
      ? 'Dictation is off — turn it on in Settings to start with voice.'
      : '';
}

/**
 * Ask the server what it actually has, instead of guessing on its behalf.
 *
 * Only ever on an explicit press. From a hosted page this request is what triggers
 * Chrome's local-network permission prompt, and firing a permission prompt off a dropdown
 * change is how you teach someone to deny it.
 */
async function refreshModels(owns) {
  const c = currentChoice();
  if (!c.discoverModels) return;
  els['model-note'].textContent = 'Reading the model list…';
  try {
    const provider = await buildProvider({ requireModel: false });
    if (!owns()) return;
    const names = await provider.listModels();
    if (!owns()) return;
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
    if (owns()) els['model-note'].textContent = `${err && err.message ? err.message : err} ` +
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
  if (state.forgetting) return;
  state.forgetting = 'confirming';
  const navigation = state.navigation;
  let confirmed = false;
  try {
    confirmed = await confirmAction({
      title: 'Forget all saved keys?',
      message: 'Remove the inference, transcription and speech keys saved on this device. '
        + 'Your ideas and backups are kept. This does not revoke keys at the provider.',
      action: 'Forget keys',
    });
    if (!confirmed || navigation !== state.navigation) return;
    state.forgetting = 'clearing';
    state.credentialEpoch++;
    state.speechRevoked = true;
    const allowedSaved = savePrefs({ hostedSpeechAllowed: false });
    if (state.previewSpeech) state.previewSpeech.output.dispose();
    state.previewSpeech = null;
    teardownVoice();
    for (const job of workBySession.values()) job.controller.abort();
    state.keysMayRemain = true;
    state.creds = emptyKeyring();
    state.provider = null;
    els.apikey.value = '';
    els.sttkey.value = '';
    els.ttskey.value = '';
    els.model.value = '';
    els.wrapmodel.value = '';
    els['model-list'].innerHTML = '';
    state.connectStep = 'provider';
    onProviderChange();
    onSttChange();
    renderSpeechSettings();
    settingsFeedback('data-status', 'Removing saved keys…');
    await clearCredentials();
    state.keysMayRemain = false;
    credentialProblem('');
    settingsFeedback('connection-error');
    fail(null);
    settingsFeedback('data-status', allowedSaved
      ? 'All saved keys were removed from this device. Your ideas are unchanged.'
      : 'All saved keys were removed. The speech preference could not be saved; no speech key remains.',
    allowedSaved ? 'success' : 'warning');
  } catch (error) {
    credentialProblem(`Keys could not be removed completely. Try Forget keys again: ${error.message}`);
    settingsFeedback('data-status', state.credentialError, 'error');
    fail(error);
  } finally {
    state.forgetting = false;
    onBaseChange();
    onSpeechChange();
    if (confirmed && navigation === state.navigation
        && els['panel-setup'].dataset.view === 'settings' && !els['panel-setup'].hidden
        && (document.activeElement === document.body || document.activeElement === els['b-forget'])) {
      const heading = $('settings-data').querySelector('h2');
      heading.tabIndex = -1;
      heading.focus({ preventScroll: true });
    }
  }
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
  // The Start/Settings split hides fields by view as well, and must change nothing here:
  // Start's folded card and Settings' hidden start actions are presentation, not input.
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
      : ((els.sttkey.dataset.kind === sttKind ? els.sttkey.value.trim() : '')
        || (state.creds?.stt?.kind === sttKind ? state.creds.stt.apiKey : '') || ''),
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
    verified: saved.verified && !state.speechRevoked && loadPrefs().hostedSpeechAllowed
      && saved.apiKey === apiKey && saved.voice === voice && els['tts-consent'].checked,
  };
}

function saveSpeechPreferences() {
  const preferences = speechPreferences({
    speechVoice: els['browser-voice'].value,
    speechRate: Number(els['speech-rate'].value),
  });
  const saved = savePrefs(preferences);
  settingsFeedback('voice-preferences-status', saved ? 'Voice preference saved on this device.'
    : 'Voice preference could not be saved. It applies to this preview only; try again.',
  saved ? 'success' : 'error');
  els['speech-rate-value'].value = `${preferences.speechRate.toFixed(2)}×`;
  renderSettingsSummaries();
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
  settingsFeedback('browser-voice-note',
    selected && !voices.some((voice) => (voice.voiceURI || voice.name) === selected)
      ? 'The saved browser voice is unavailable. Automatic voice will be used; choose another voice to replace it.'
      : 'The available voices come from this browser. Network voices may send text off this device.',
    selected && !voices.some((voice) => (voice.voiceURI || voice.name) === selected) ? 'warning' : '');
  renderSettingsSummaries();
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
  els['b-speech-preview'].disabled = state.forgetting === 'clearing' || (hosted && !els['tts-consent'].checked);
  renderSettingsSummaries();
}

function renderSpeechSettings() {
  const saved = (state.creds && state.creds.tts) || emptyKeyring().tts;
  const preferences = loadPrefs();
  els.speech.value = saved.kind;
  els['tts-voice'].value = saved.voice;
  els['tts-consent'].checked = saved.verified && preferences.hostedSpeechAllowed && !state.speechRevoked;
  els.ttskey.placeholder = saved.apiKey ? `saved: ${maskKey(saved.apiKey)}` : 'paste a scoped OpenAI key';
  els['speech-rate'].value = String(preferences.speechRate);
  els['speech-rate-value'].value = `${preferences.speechRate.toFixed(2)}×`;
  renderSpeechVoices();
  onSpeechChange();
}

async function previewSpeech() {
  if (state.forgetting === 'clearing') {
    settingsFeedback('speech-check', 'Wait for Forget keys to finish before checking speech.', 'warning');
    return;
  }
  if (state.previewSpeech) {
    await cancelSpeechPreview();
    return;
  }
  const config = readSpeechFromForm();
  if (config.kind === 'openai' && (!config.apiKey || !els['tts-consent'].checked)) {
    settingsFeedback('speech-check', 'Enter a separate speech key and confirm metered text transfer first.', 'error');
    return;
  }
  const preferences = saveSpeechPreferences();
  const credentialEpoch = state.credentialEpoch;
  const preview = createSpeechOutput({
    ...config, browserVoice: preferences.speechVoice, rate: preferences.speechRate,
  });
  const attempt = { output: preview, kind: config.kind };
  state.previewSpeech = attempt;
  const owns = () => state.previewSpeech === attempt && credentialEpoch === state.credentialEpoch;
  settingsFeedback('speech-check');
  els['speech-check'].textContent = config.kind === 'openai'
    ? 'Checking one short, metered speech sample…' : 'Preparing the browser voice…';
  els['b-speech-preview'].textContent = 'Cancel preview';
  try {
    const primed = preview.prime();
    await primed;
    const result = await preview.check(
      'IdeaForge is ready. Tell me what you have in mind, and we will take it one question at a time.',
    );
    if (!owns()) return;
    if (!result || result.status !== 'spoken') {
      settingsFeedback('speech-check', 'The preview did not finish. Speech has not been enabled.', 'error');
      return;
    }
    settingsFeedback('speech-check', 'Preview played. Saving the speech check…');
    const checked = await updateKeyring((ring) => withTts(ring, {
      ...config, verified: config.kind === 'openai' ? true : config.verified,
    }), owns);
    if (!owns()) return;
    if (config.kind === 'openai') {
      if (!savePrefs({ hostedSpeechAllowed: true })) {
        state.speechRevoked = true;
        state.creds = withTts(checked, { ...checked.tts, verified: false });
        await updateKeyring((ring) => withTts(ring, { ...ring.tts, verified: false }), owns);
        settingsFeedback('speech-check', 'The preview played, but permission could not be saved. Browser speech remains active.', 'error');
        return;
      }
      state.speechRevoked = false;
    }
    els.ttskey.value = '';
    settingsFeedback('speech-check', config.kind === 'openai'
      ? 'OpenAI speech played successfully in this browser. Enhanced speech is enabled.'
      : 'Browser voice preview complete.', 'success');
    renderSpeechSettings();
  } catch (error) {
    if (state.previewSpeech === attempt) {
      settingsFeedback('speech-check', `The voice check failed: ${error.message}`, 'error');
    }
  } finally {
    preview.dispose();
    if (state.previewSpeech === attempt) {
      state.previewSpeech = null;
      onSpeechChange();
    }
  }
}

function cancelSpeechPreview() {
  const attempt = state.previewSpeech;
  if (!attempt) return Promise.resolve(false);
  state.previewSpeech = null;
  attempt.output.dispose();
  if (attempt.kind === 'openai') return revokeHostedSpeech({ notice: 'Preview cancelled. ' });
  settingsFeedback('speech-check', 'Preview cancelled.');
  onSpeechChange();
  return Promise.resolve(true);
}

async function revokeHostedSpeech({ notice = '' } = {}) {
  state.speechRevoked = true;
  state.credentialEpoch++;
  const epoch = state.credentialEpoch;
  renderConnect();
  if (state.voiceMode === 'active') {
    pauseVoice({ detail: 'Hosted speech is disabled. Resume to continue with the browser voice.' }).catch(fail);
  }
  if (state.previewSpeech) state.previewSpeech.output.dispose();
  state.previewSpeech = null;
  const saved = (state.creds && state.creds.tts) || emptyKeyring().tts;
  const allowedSaved = savePrefs({ hostedSpeechAllowed: false });
  state.creds = withTts(state.creds || emptyKeyring(), {
    ...saved, kind: els.speech.value === 'openai' ? 'openai' : 'browser', verified: false,
  });
  onSpeechChange();
  els['speech-check'].textContent = `${notice}Disabling hosted speech…`;
  try {
    const kind = els.speech.value === 'openai' ? 'openai' : 'browser';
    await updateKeyring((ring) => withTts(ring, { ...ring.tts, kind, verified: false }),
      () => epoch === state.credentialEpoch);
    if (epoch !== state.credentialEpoch) return false;
    settingsFeedback('speech-check', notice + (allowedSaved
      ? 'Hosted speech is disabled. A new check and preview is needed to enable it.'
      : 'Hosted speech is disabled in the saved keyring, but its permission preference could not be updated. A new check is required.'),
    allowedSaved ? '' : 'warning');
    renderSettingsSummaries();
    return allowedSaved;
  } catch (error) {
    if (epoch !== state.credentialEpoch) return false;
    els['speech-check'].textContent = allowedSaved
      ? `Hosted speech is disabled, but its credential settings could not be updated: ${error.message}`
      : 'Hosted speech is stopped for this page, but the withdrawal could not be saved. Forget the speech key before closing.';
    fail(error);
    els['speech-check'].dataset.status = 'error';
    return false;
  }
}

function onSttChange() {
  const kind = els.stt.value;
  if (els.sttkey.dataset.kind && els.sttkey.dataset.kind !== kind) els.sttkey.value = '';
  els.sttkey.dataset.kind = kind;
  const preset = STT_PRESETS[kind];
  const sharesKey = kind === currentChoice().id;
  els['field-sttkey'].hidden = !preset || sharesKey;
  els['sttkey-note'].textContent = preset ? `Sent only to ${new URL(preset.baseUrl).host}.` : '';
  const saved = state.creds?.stt?.kind === kind ? state.creds.stt.apiKey : '';
  els.sttkey.placeholder = saved ? `saved: ${maskKey(saved)}` : 'paste your transcription key';
  els['stt-note'].textContent =
    kind === 'browser'
      ? 'Free live dictation where supported. Installed iPhone apps, Edge and Firefox may need hosted transcription instead.'
      : kind === 'off' ? ''
      : `${preset.label} receives recorded audio and question text; transcription is metered. ${preset.note}`
        + (sharesKey ? ` Uses the ${currentChoice().label} inference key shown in Connection; no separate key is needed.`
          : ' Enter a separate transcription key below.')
        + ' Dictation choices save when you check the connection or start an idea; that does not test transcription.';
  renderSettingsSummaries();
  onBaseChange();
}

async function buildProvider({ requireModel = true, persist: write = true } = {}) {
  if (state.forgetting === 'clearing') throw new Error('Wait for Forget keys to finish before setting up a connection.');
  const context = connectionContext();
  const c = currentChoice();
  const ring = readRingFromForm();
  const before = state.creds || emptyKeyring();
  const creds = credsFor(ring, c.id);
  if (c.needsKey && !creds.apiKey) throw new Error('This provider needs an API key.');

  const destination = formInferenceDestination();
  const permit = inference.status(destination.id).permit;
  const provider = await createProvider({
    kind: c.id,
    apiKey: creds.apiKey,
    baseUrl: creds.baseUrl || undefined,
    model: creds.model || undefined,
    wrapModel: creds.wrapModel || undefined,
    // Reading the model list is how you find out what to put in the model box, so that
    // one call cannot be the thing that insists the box is already filled.
    modelRequired: requireModel ? undefined : false,
    beforeSample: () => inference.require(destination.id, permit),
  });

  if (!ownsConnection(context)) {
    throw new Error('Provider setup was cancelled.');
  }
  // Always, not only when there is a key. Guarding this on `apiKey` meant choosing a local
  // provider was never persisted at all: pick Ollama, reload, and the choice was gone.
  if (write) {
    await updateKeyring((latest) => {
      // This submission owns dictation too, even if a retired put changed it on disk.
      let next = withStt(withCreds(latest, c.id, creds), ring.stt);
      if (['kind', 'apiKey', 'voice', 'verified'].every((key) => latest.tts[key] === before.tts[key])) {
        next = withTts(next, ring.tts);
      }
      return next;
    }, () => ownsConnection(context));
    if (!ownsConnection(context)) throw new Error('Provider setup was cancelled.');
    state.provider = provider;
    state.providerPermit = permit;
    state.providerRevision = state.connectRevision;
  }
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

/** What a segment shows: the ratchet's level while a dimension is asked about, else set aside. */
function segmentLevel(c) {
  return c.status === 'probing' ? c.level : 'skipped';
}

/**
 * The meter: one segment per dimension, in DIMENSIONS order, and the number beside them.
 *
 * aria-valuenow is coveragePercent and nothing else — the same figure the export, the library
 * card and the done line print, so a screen reader never hears a second, rounder opinion.
 * aria-valuetext adds the count a sighted person reads off the segments. Waived and deferred
 * dimensions leave the percentage (coveragePercent excludes them), so the count does too and
 * says how many were set aside instead of calling them uncovered.
 */
function renderMeter(s) {
  const pct = coveragePercent(s);
  const track = els['meter-fill'];
  if (track.children.length !== DIMENSIONS.length) {
    track.replaceChildren(...DIMENSIONS.map((d) => {
      const seg = document.createElement('span');
      seg.className = 'meter-seg';
      seg.dataset.dimension = d.id;
      return seg;
    }));
  }
  let covered = 0;
  let asked = 0;
  DIMENSIONS.forEach((d, i) => {
    const c = s.coverage[d.id];
    const level = segmentLevel(c);
    if (level !== 'skipped') asked++;
    if (level === 'covered') covered++;
    const seg = track.children[i];
    seg.dataset.level = level;
    seg.title = `${d.label}: ${level === 'skipped' ? (c.status === 'waived' ? 'not relevant' : c.status) : level}`;
  });
  const aside = DIMENSIONS.length - asked;
  els['meter-label'].textContent = `${pct}%`;
  els.meter.setAttribute('aria-valuenow', String(pct));
  els.meter.setAttribute('aria-valuetext', `${pct}% — ${covered} of ${asked} covered`
    + (aside ? `, ${aside} set aside` : ''));
}

function render() {
  const s = state.session;
  renderShell();
  // Shown here, but renderVoiceStage() (called last) hides it again in voice mode, and show()
  // hides it on every other panel: Interview is the only screen it belongs to.
  els.meter.hidden = false;
  renderMeter(s);

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
  renderWrapOffer(s, open);
  renderThread(s);
  els['b-send'].disabled = state.busy || !open;
  els['b-skip'].disabled = state.busy || !open;
  els.answer.disabled = state.busy || !open;
  renderCoverage();
  renderVoiceStage();
  renderInferenceControls();
}

/**
 * The card belongs to one idea and one open question. Answering, skipping, Keep going, a
 * write-up and opening another idea all clear `state.wrapOffer`; the id check is the
 * backstop, so a delayed render can never show one idea's offer over another's question.
 */
function renderWrapOffer(s = state.session, open = s && openTurn(s)) {
  const offer = state.wrapOffer;
  const visible = !!(offer && s && open && offer.id === s.id);
  els['wrap-offer'].hidden = !visible;
  els['wrap-offer-text'].textContent = visible ? offer.text : '';
  els['b-wrap-offer'].disabled = state.busy || state.voiceMode !== 'manual';
}

function dismissWrapOffer() {
  state.wrapOffer = null;
  renderWrapOffer();
}

/**
 * The pairs already settled, oldest first: answered or skipped, never the open question.
 * Only the newest THREAD_VISIBLE show until the toggle opens the rest, so a long interview
 * does not push the question it is asking off the top of a phone. Every string goes in
 * through textContent: the questions are model output and the answers are the person's,
 * and either one can contain markup that must stay text.
 */
const THREAD_VISIBLE = 2;
function renderThread(s = state.session) {
  if (!s) return;
  if (state.threadFor !== s.id) {
    state.threadFor = s.id;
    state.threadExpanded = false;
  }
  const settled = s.turns.filter((t) => t.answer || t.skipped);
  const folded = state.threadExpanded ? 0 : Math.max(0, settled.length - THREAD_VISIBLE);
  const items = settled.slice(folded).map((t) => {
    const li = document.createElement('li');
    li.className = 'thread-turn';
    const q = document.createElement('p');
    q.className = 'thread-q';
    q.textContent = t.question;
    const a = document.createElement('div');
    a.className = t.skipped ? 'thread-a skipped' : 'thread-a';
    const text = document.createElement('p');
    text.className = 'thread-a-text';
    text.textContent = t.skipped ? 'Skipped' : t.answer;
    a.append(text);
    // The same provenance the ratchet reads: a chip sent as offered is Claude's wording,
    // and the transcript should not let it pass for the person's own.
    if (!t.skipped && t.answerSource === 'chip') {
      const tag = document.createElement('span');
      tag.className = 'thread-tag';
      tag.textContent = 'suggestion';
      tag.title = 'A suggested answer, sent as offered';
      a.append(tag);
    }
    li.append(q, a);
    return li;
  });
  els.thread.replaceChildren(...items);
  els['thread-wrap'].hidden = settled.length === 0;
  const more = els['b-thread-more'];
  const hiddenCount = settled.length - THREAD_VISIBLE;
  more.hidden = hiddenCount <= 0;
  more.setAttribute('aria-expanded', String(!!state.threadExpanded));
  more.textContent = state.threadExpanded
    ? 'Hide earlier answers'
    : `Show ${hiddenCount} earlier`;
}

function renderChips(chips) {
  els.chips.replaceChildren();
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
  fromVoice = state.voiceMode !== 'manual', permit = state.providerPermit,
) {
  if (!session || deletedSessions.has(session.id)) return;
  try {
    requireInference(permit);
  } catch (error) {
    if (currentView(session.id, navigation)) { busy(false); fail(error); }
    return;
  }
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
    requireInference(permit);
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
    if (out.advisory) state.wrapOffer = { id: session.id, text: out.advisory };
    render();
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
  let permit;
  let provider;
  const id = state.session.id;
  const navigation = state.navigation;
  try {
    permit = requireInference();
    busy(true);
    const cached = cachedInferenceProvider(permit);
    provider = cached === undefined ? await ensureInferenceProvider(permit) : cached;
  } catch (error) {
    if (currentView(id, navigation)) { busy(false); fail(error); }
    return;
  }
  if (!currentView(id, navigation)) return;
  busy(true);
  cancelDraftSave();
  state.session = skipQuestion(state.session, { now: now() });
  dismissWrapOffer();
  // The settled pair joins the thread now, above the skeleton, rather than when the next
  // question arrives — that late insert was the layout shift (#107).
  renderThread(state.session);
  const session = state.session;
  const credentialEpoch = state.credentialEpoch;
  const fromVoice = state.voiceMode !== 'manual';
  els.answer.value = '';
  state.answerSource = 'typed';
  say('');
  await persist(session);
  await nextQuestion(session, credentialEpoch === state.credentialEpoch ? provider : null, navigation, fromVoice, permit);
}

/** Record an answer and ask the next question. The hands-free loop calls this directly. */
async function submitAndAdvance(text, source) {
  if (state.busy || !state.session || !openTurn(state.session)) return;
  let permit;
  let provider;
  const id = state.session.id;
  const navigation = state.navigation;
  try {
    permit = requireInference();
    busy(true);
    const cached = cachedInferenceProvider(permit);
    provider = cached === undefined ? await ensureInferenceProvider(permit) : cached;
  } catch (error) {
    if (currentView(id, navigation)) { busy(false); fail(error); }
    return;
  }
  if (!currentView(id, navigation)) return;
  busy(true);
  cancelDraftSave();
  state.session = submitAnswer(state.session, { text, source, now: now() });
  dismissWrapOffer();
  renderThread(state.session);
  const session = state.session;
  const credentialEpoch = state.credentialEpoch;
  const fromVoice = state.voiceMode !== 'manual';
  els.answer.value = '';
  state.answerSource = 'typed';
  say('');
  await persist(session);
  await nextQuestion(session, credentialEpoch === state.credentialEpoch ? provider : null, navigation, fromVoice, permit);
}

async function send(text, source) {
  const body = String(text == null ? els.answer.value : text).trim();
  if (!body || state.busy) return;
  if (state.voiceMode === 'manual' && state.voice && !els.listening.hidden) state.voice.abort();
  try {
    await submitAndAdvance(body, source || state.answerSource);
  } catch (error) { fail(error); }
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
  els['b-wrap-offer'].disabled = visible || state.busy;
  els.answer.disabled = visible || state.busy || !hasQuestion;
  if (!visible) return;
  state.voiceStage.render({
    phase: state.voiceMode === 'paused' || state.voiceMode === 'blocked'
      ? state.voiceMode : state.voicePhase,
    question: state.voiceCaption || currentQuestion(),
    transcript: state.voiceTranscript === null ? els.answer.value : state.voiceTranscript,
    detail: [state.voiceMode === 'active' ? state.voiceMeterWarning : '', state.voiceDetail]
      .filter(Boolean).join(' ') || (state.voicePhase === 'listening'
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
  state.voiceMeterWarning = '';
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
  requireInference(state.voicePermit);
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
  state.wakeLockRequest = null;
  const lock = state.wakeLock;
  state.wakeLock = null;
  if (lock && !lock.released) {
    lock.release().catch((error) => console.warn('Could not release the screen wake lock:', error.name));
  }
}

async function keepScreenAwake() {
  if (!navigator.wakeLock || document.hidden || state.voiceMode !== 'active'
      || state.wakeLock || state.wakeLockRequest) return;
  const epoch = state.voiceEpoch;
  const request = {};
  state.wakeLockRequest = request;
  try {
    const lock = await navigator.wakeLock.request('screen');
    if (epoch !== state.voiceEpoch || state.wakeLockRequest !== request
        || state.voiceMode !== 'active' || document.hidden) {
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
  } finally {
    if (state.wakeLockRequest === request) state.wakeLockRequest = null;
  }
}

async function setupVoice({ defer = false, automatic = state.wantHandsFree } = {}) {
  const { kind: sttKind, apiKey: sttKey } = (state.creds && state.creds.stt) || {};
  if (sttKind === 'off') {
    if (automatic) {
      setHandsFree(false);
      say('Dictation is off. Continue by typing, or enable dictation in Settings.');
    }
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
  requireInference(state.voicePermit);
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
    try {
      requireInference(state.voicePermit);
    } catch (error) {
      voice.dispose();
      throw error;
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
  requireInference(state.voicePermit || state.providerPermit);
  const voice = state.voice;
  const epoch = state.voiceEpoch;
  const navigation = state.navigation;
  const sessionId = state.session.id;
  const turnId = openTurn(state.session).id;
  const ownsTurn = () => currentView(sessionId, navigation) && epoch === state.voiceEpoch
    && openTurn(state.session)?.id === turnId;
  els.handsfree.disabled = true;
  els.listening.hidden = false;
  els['listening-label'].textContent = 'Getting the microphone ready…';
  els['b-mic'].textContent = 'Stop listening';
  try {
    return await voice.listen({
      prompt,
      autoStop,
      onPhase: (phase) => {
        if (!ownsTurn()) return;
        els['listening-label'].textContent = phase === 'transcribing'
          ? 'Turning speech into text…'
          : voice.mode === 'recorder' ? 'Listening — stop talking when you’re done.' : 'Listening…';
      },
      onInterim: (t) => {
        if (!ownsTurn()) return;
        els.answer.value = t;
        state.answerSource = 'voice';
        queueDraftSave();
      },
      onLevel: (rms) => {
        if (!ownsTurn()) return;
        els.pulse.style.setProperty('--level', String(0.6 + Math.min(1.7, rms * 16)));
      },
      onLevelUnavailable: (reason) => {
        if (!ownsTurn()) return;
        els.pulse.style.removeProperty('--level');
        say(reason);
      },
    });
  } catch (error) {
    if (error.code === 'audio-capture' && error.fatal && typeof error.draft === 'string'
        && error.draft.trim() && ownsTurn()) {
      els.answer.value = error.draft;
      state.answerSource = 'voice';
      await flushDraft();
    }
    throw error;
  } finally {
    if (state.voice === voice) {
      await Promise.resolve(voice.dispose()).catch((error) => {
        say(`The microphone could not finish closing: ${error.message}`);
      });
      if (state.voice === voice) state.voice = null;
    }
    if (epoch === state.voiceEpoch && navigation === state.navigation) {
      els.listening.hidden = true;
      els.handsfree.disabled = false;
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
  try { requireInference(state.voicePermit); }
  catch (error) { await pauseVoice({ blocked: true, detail: error.message }); return; }
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
      ? submitAndAdvance([state.capturePrefix, text].filter(Boolean).join(' '),
        text.trim() ? 'voice' : state.capturePrefixSource) : undefined,
    skip: () => running() ? doSkip() : undefined,
    wrap: () => running() ? wrapUp('Wrapped up by voice.') : undefined,
    offerWrap: () => running() ? shouldOfferWrap(state.session) : null,
    answeredCount: () => state.session.turns.filter((turn) => turn.answer || turn.skipped).length,
    hasDraft: () => !!els.answer.value.trim(),
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
  requireInference(state.voicePermit);
  const prefix = els.answer.value.trim();
  state.capturePrefix = prefix;
  state.capturePrefixSource = state.answerSource;
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
      onLevelUnavailable: (reason) => {
        if (!running()) return;
        state.voiceMeterWarning = String(reason || 'Microphone levels are unavailable.');
        state.voiceStage.reset();
        els.pulse.style.removeProperty('--level');
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
      state.answerSource = state.capturePrefixSource;
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

function teardownVoice({ preservePreview = false } = {}) {
  if (state.voiceSetup) state.voiceSetup.abort();
  state.voiceSetup = null;
  if (!preservePreview) cancelSpeechPreview().catch(fail);
  setHandsFree(false);
  if (state.voice) state.voice.dispose();
  if (state.speech) state.speech.dispose();
  state.voice = null;
  state.speech = null;
  state.drive = null;
  state.cycling = false;
  state.capturePending = false;
  state.captureConfirm = false;
  state.voiceMeterWarning = '';
  state.voiceTranscript = null;
  state.readback = false;
  state.voicePermit = null;
  els['b-mic'].hidden = true;
  els['handsfree-wrap'].hidden = true;
  els.handsfree.disabled = false;
  els.listening.hidden = true;
}

function setHandsFree(on) {
  if (on) requireInference(state.voicePermit || state.providerPermit);
  if (on && (!state.session || state.voiceWrapPending
      || (!state.busy && !openTurn(state.session)))) {
    on = false;
    state.wantHandsFree = false;
    savePrefs({ handsFree: false });
    say('There are no more questions. Choose the write-up when you are ready.');
  }
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
  const prefixSource = state.capturePrefixSource;
  const captured = capturedDraftText(prefix);
  if (command || confirmation) els.answer.value = prefix;
  else if (wasCapturing) els.answer.value = retainedDraft(prefix, captured);
  if (els.answer.value === prefix) state.answerSource = prefixSource;
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
        state.answerSource = els.answer.value === prefix ? prefixSource : 'voice';
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
  const permit = requireInference();
  state.voiceEpoch++;
  state.voiceMode = 'active';
  state.voicePhase = 'starting';
  state.voiceDetail = '';
  state.voicePermit = permit;
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
  await ensureInferenceProvider(permit);
  requireInference(permit);
  forgetVerdict();
  await setupVoice({ automatic: true });
}

async function exitVoice({ command = false } = {}) {
  if (state.voiceMode === 'manual') return;
  if (command) els.answer.value = state.capturePrefix;
  else if (!state.captureConfirm && ['listening', 'transcribing'].includes(state.voicePhase)) {
    els.answer.value = retainedDraft(state.capturePrefix, capturedDraftText(state.capturePrefix));
  }
  if (els.answer.value === state.capturePrefix) state.answerSource = state.capturePrefixSource;
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
    showDone();
    if (state.session.status === 'done') renderDone();
    donePrimary().focus();
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
  const permit = requireInference();
  const cached = cachedInferenceProvider(permit);
  const provider = cached === undefined ? await ensureInferenceProvider(permit) : cached;
  requireInference(permit);
  if (!currentView(session.id, navigation) || state.writing === session.id) return;
  const credentialEpoch = state.credentialEpoch;
  const wasDriving = state.voiceMode === 'active';
  state.writing = session.id;
  state.voiceWrapPending = null;
  dismissWrapOffer();
  state.handsFree = false;
  els.handsfree.checked = false;
  if (state.drive) state.drive.stop();
  if (state.voice) state.voice.dispose();
  state.voice = null;
  state.readback = wasDriving;
  busy(true);
  if (!wasDriving) show('panel-done');
  els['done-title'].textContent = 'Writing it up…';
  if (wasDriving) {
    els.output.textContent = '';
    els.output.dataset.source = '';
  } else {
    outputBusy(true);
  }
  els['done-meta'].textContent = note || '';
  try {
    if (wasDriving) {
      state.voiceCaption = 'Writing your refined prompt';
      setVoicePhase('thinking', 'Bringing your answers together. The result will appear here.');
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
    requireInference(permit);
    warn(out);
    if (!out.ok) {
      const message = out.error
        ? `The wrap-up call failed: ${out.error.message}`
        : 'The model returned no usable prompt.';
      fail(message);
      // A spoken write-up reaches Done later, through a panel change that clears #err.
      state.doneFailure = { id: session.id, message };
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
      showDone();
    }
  } catch (error) {
    if (currentView(session.id, navigation)) fail(error);
  } finally {
    if (state.writing === session.id) state.writing = null;
    if (currentView(session.id, navigation)) {
      busy(false);
      outputBusy(false);
    }
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
  showDone();
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
  renderShell();
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
  outputBusy(false);
  els.output.textContent = '';
  // The rendered view and the bytes it was rendered from, together. `textContent` used to BE
  // the markdown and is now flattened prose with every marker gone, so anything reading the
  // artifact off the page — tools/screenshots.mjs writes docs/examples/ from here — needs the
  // source kept somewhere. Copy, Download and Share go through exportFor() instead, which is
  // the same bytes without depending on the DOM at all.
  els.output.dataset.source = markdown;
  renderMarkdown(els.output, markdown);
}

/**
 * Append the rendered blocks of `markdown` to `into`. Shared by Done's #output and the
 * library's desktop preview, so both read the same and both stay textContent-only.
 */
function renderMarkdown(into, markdown) {
  const spans = (target, list) => {
    for (const s of list || []) {
      const tag = s.code ? 'code' : s.bold ? 'strong' : s.italic ? 'em' : null;
      if (!tag) { target.append(s.text); continue; }
      const el = document.createElement(tag);
      el.textContent = s.text;
      target.append(el);
    }
  };

  for (const block of renderBlocks(markdown)) {
    if (block.type === 'hr') { into.append(document.createElement('hr')); continue; }

    if (block.type === 'pre') {
      const pre = document.createElement('pre');
      pre.textContent = block.text;
      into.append(pre);
      continue;
    }

    if (block.type === 'list') {
      const list = document.createElement(block.ordered ? 'ol' : 'ul');
      for (const item of block.items) {
        const li = document.createElement('li');
        spans(li, item);
        list.append(li);
      }
      into.append(list);
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
      into.append(table);
      continue;
    }

    const el = document.createElement(
      block.type === 'quote' ? 'blockquote'
        : /^h[123]$/.test(block.type) ? block.type
          : 'p');
    if (block.meta) el.className = 'meta';
    spans(el, block.spans);
    into.append(el);
  }
}

function download() {
  downloadSession(state.session);
}

async function refreshLibrary() {
  try {
    state.library.render(await listSessions());
  } catch (error) {
    // Never leave the skeletons spinning over a failed read: show what was there before.
    state.library.settle();
    fail(error);
  }
}

/**
 * The Ideas destination. `swap` makes the screen change (navigateTo passes a View
 * Transition's); anything else — a click event, nothing — means a plain change.
 */
async function openLibrary(swap) {
  const navigation = ++state.navigation;
  teardownVoice();
  say('');
  fail(null);
  if (!(await flushDraft())) return;
  if (navigation !== state.navigation) return;
  const shown = await swapWith(swap, () => {
    if (navigation !== state.navigation) return false;
    show('panel-library');
    state.library.loading();
    return true;
  });
  if (!shown) return;
  await refreshLibrary();
  if (navigation === state.navigation) focusHeading('panel-library');
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
      outputBusy(false);
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
  fail(null);
  settingsFeedback('data-status', 'Preparing a backup of the saved ideas…');
  try {
    const sessions = await listSessions();
    downloadText(buildBackup(sessions, { now: now() }), backupFilename(now()), {
      type: 'application/json',
    });
    say(`Backed up ${sessions.length} idea${sessions.length === 1 ? '' : 's'}.`);
    settingsFeedback('data-status',
      `Backup download started for ${sessions.length} idea${sessions.length === 1 ? '' : 's'}.`,
      'success');
  } catch (error) {
    settingsFeedback('data-status', `Backup could not be prepared. Try again: ${error.message}`, 'error');
    fail(error);
  }
}

async function importLibraryBackup(file) {
  fail(null);
  settingsFeedback('data-status', 'Reading the ideas in this backup…');
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
    settingsFeedback('data-status', parts.join(' · '), 'success');
    await refreshLibrary();
    await renderResumeList();
  } catch (error) {
    settingsFeedback('data-status', `Import could not finish. Check the backup and try again: ${error.message}`, 'error');
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
      zero: els['library-zero'],
      zeroNew: els['b-library-zero-new'],
      count: els['library-count'],
      backup: els['b-backup'],
      importButton: els['b-import'],
      importFile: els['backup-file'],
      newButton: els['b-library-new'],
      detail: els['library-detail'],
    },
    renderPreview: (into, session) => renderMarkdown(into, exportFor(session)),
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
    onNew: () => openStart(),
  });
}

/**
 * Settings' desktop index (≥1024px; app.css hides it below): one button per group, which
 * brings the group into view and moves focus to its heading. Buttons rather than links on
 * purpose: a fragment would change location.hash, and WebKit re-prompts for the microphone
 * on a hash change in a standalone home-screen app (CLAUDE.md, "No hash routing").
 */
function setupSettingsIndex() {
  const index = els['settings-index'];
  if (!index) return;
  const entries = [...index.querySelectorAll('button[data-target]')];
  for (const entry of entries) {
    entry.addEventListener('click', () => {
      const group = $(entry.dataset.target);
      if (!group) return;
      const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;
      group.scrollIntoView({ block: 'start', behavior: reduce ? 'auto' : 'smooth' });
      const heading = group.querySelector('h2');
      if (heading) {
        heading.tabIndex = -1;
        heading.focus({ preventScroll: true });
      }
      for (const other of entries) {
        if (other === entry) other.setAttribute('aria-current', 'true');
        else other.removeAttribute('aria-current');
      }
    });
  }
}

/**
 * The Appearance group's System / Light / Dark radios (#112). Applied the moment one is
 * chosen, and stored for theme-boot.js to apply before first paint next time.
 *
 * Deliberately no applyTheme() here at boot: theme-boot.js already applied any explicit
 * choice, and applying the default 'system' would REMOVE the data-theme that
 * tools/screenshots.mjs set in its fresh profile, so both README runs would shoot one theme.
 */
function setupTheme() {
  const group = els['theme-choice'];
  if (!group) return;
  const stored = loadPrefs().theme;
  for (const radio of group.querySelectorAll('input[name="theme"]')) {
    radio.checked = radio.value === stored;
    radio.addEventListener('change', () => {
      if (!radio.checked) return;
      // Applied even if the write fails (a private window): the page still honours it.
      const saved = savePrefs({ theme: applyTheme(document, radio.value) });
      settingsFeedback('appearance-status', saved ? 'Appearance saved on this device.'
        : 'Appearance could not be saved. This page still uses your choice; try again.',
      saved ? 'success' : 'error');
      renderSettingsSummaries();
    });
  }
}

/** The New idea destination: the start screen, from anywhere. */
async function openStart(swap) {
  const navigation = ++state.navigation;
  teardownVoice();
  say('');
  fail(null);
  if (!(await flushDraft())) return;
  if (navigation !== state.navigation) return;
  const shown = await swapWith(swap, () => {
    if (navigation !== state.navigation) return false;
    show('panel-setup', 'start');
    return true;
  });
  if (!shown) return;
  await renderResumeList();
  if (navigation === state.navigation) focusHeading('panel-setup');
}

/** The Settings destination. */
async function openSettings(swap) {
  const navigation = ++state.navigation;
  teardownVoice();
  say('');
  fail(null);
  if (!(await flushDraft())) return;
  if (navigation !== state.navigation) return;
  const shown = await swapWith(swap, () => {
    if (navigation !== state.navigation) return false;
    show('panel-setup', 'settings');
    renderSpeechSettings();
    return true;
  });
  if (!shown) return;
  await renderResumeList();
  if (navigation === state.navigation) focusHeading('panel-setup');
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
      + 'For dictation in the installed app, choose Groq or OpenAI Whisper in Settings, under Dictation.';
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
  const permit = requireInference();
  state.navigation++;
  teardownVoice();
  state.voicePermit = permit;
  const context = connectionContext();
  state.starting = context;
  const owns = () => state.starting === context && ownsConnection(context);
  state.wantHandsFree = handsFree;
  savePrefs({ handsFree });
  state.voiceWrapPending = null;
  state.wrapOffer = null;
  state.voiceMode = handsFree ? 'active' : 'manual';
  state.voicePhase = 'starting';
  prepareSpeechOutput();
  renderConnect();
  fail(null);
  requestPersistence();
  try {
    await buildProvider();
  } catch (e) {
    const current = owns();
    retireStart(context);
    if (current) connectionFailure(e, 'start');
    return;
  }
  if (!owns()) {
    retireStart(context);
    return;
  }
  try { requireInference(permit); }
  catch (error) {
    retireStart(context);
    connectionFailure(error, 'start');
    return;
  }
  const platform = state.install ? state.install.state().platform : 'other';
  state.session = seedTurn(createSession({
    id: newSessionId(),
    now: now(),
    device: platform === 'other' ? 'desktop' : platform,
  }), { now: now() });
  busy(false);
  els.answer.value = '';
  state.answerSource = 'typed';
  state.capturePrefix = '';
  await persist();
  if (!owns()) {
    retireStart(context);
    return;
  }
  state.starting = null;
  show('panel-interview');
  render();
  await setupVoice({ defer: !handsFree, automatic: handsFree });
  if (state.voiceMode === 'manual') els.answer.focus();
  else els['b-voice-pause'].focus();
}

async function resumeInterview(id) {
  const navigation = ++state.navigation;
  teardownVoice();
  state.wantHandsFree = loadPrefs().handsFree === true && els.stt.value !== 'off';
  let permit = null;
  let agreementError = null;
  try {
    permit = requireInference();
    state.voicePermit = permit;
    prepareSpeechOutput();
  } catch (error) { agreementError = error; }
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
  state.wrapOffer = null;
  state.capturePrefix = '';
  busy(false);

  if (s.status === 'done' && !s.pending && !openTurn(s)) {
    teardownVoice();
    renderDone();
    show('panel-done');
    return;
  }
  if (agreementError) {
    showUnconsentedIdea(s, agreementError);
    return;
  }

  // Said after the show() below, never here: the panel change would clear it unread.
  let providerNote = '';
  try {
    await buildProvider();
    requireInference(permit);
  } catch (e) {
    if (navigation !== state.navigation) return;
    if (e.code === 'consent') {
      showUnconsentedIdea(s, e);
      return;
    }
    providerNote = withoutModelNote(e);
    state.provider = null;
    state.providerPermit = permit;
    state.providerRevision = state.connectRevision;
  }
  if (navigation !== state.navigation) return;

  if (s.pending && s.pending.kind === 'synthesis') {
    show('panel-done');
    say(providerNote);
    els['done-title'].textContent = 'Picking up the write-up…';
    outputBusy(true);
    els['done-meta'].textContent = '';
    const provider = state.provider;
    let out;
    try {
      out = await sessionWork(s, 'synthesis', async (signal) => {
        requireInference(permit);
        const result = await resumeSynthesis(s, { provider, now: now(), signal });
        return { ...result,
          session: result.ok || result.aborted ? result.session : setStatusField(result.session, 'done', now()) };
      });
    } finally {
      if (currentView(id, navigation)) outputBusy(false);
    }
    if (out.discarded || out.aborted || !currentView(id, navigation)) return;
    requireInference(permit);
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
  say(providerNote);

  if (s.pending && s.pending.kind === 'turn') {
    busy(true, 'picking up where the last question left off…');
    try {
      const provider = state.provider;
      const out = await sessionWork(s, 'turn', (signal) => {
        requireInference(permit);
        return resumeTurn(s, { provider, now: now(), signal });
      });
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
  state.answerSource = (openTurn(state.session)?.chips || []).some((chip) => chip === els.answer.value)
    ? 'chip' : 'typed';
  await setupVoice({ defer: !state.wantHandsFree, automatic: state.wantHandsFree });
  if (state.voiceMode === 'manual') els.answer.focus();
}

function showUnconsentedIdea(session, error) {
  teardownVoice({ preservePreview: true });
  state.provider = null;
  state.providerPermit = null;
  if (openTurn(session)) {
    show('panel-interview');
    render();
    els.answer.value = session.draftAnswer || '';
    state.answerSource = 'typed';
  } else {
    renderDone();
    show('panel-done');
  }
  fail(error);
  renderInferenceControls();
}

/** `stored` is a list boot already read, so a cold start reads the sessions once. */
async function renderResumeList(stored) {
  let rows = stored;
  if (!rows) {
    try { rows = await listSessions(); } catch (error) {
      settingsFeedback('settings-data-summary',
        `Saved ideas could not be read. Reopen Ideas to retry: ${error.message}`, 'error');
      return;
    }
  }
  settingsFeedback('settings-data-summary',
    `${rows.length} idea${rows.length === 1 ? '' : 's'} saved on this device · backups contain ideas only`);
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

/**
 * Two numbers app.css needs and cannot measure for itself.
 *
 * --keyboard-inset is how much of the layout viewport the on-screen keyboard covers. iOS
 * Safari, and Android Chrome since 108, shrink only the visual viewport when it opens, so a
 * composer docked with `bottom: 0` is docked behind the keyboard — over the very box being
 * typed into. Ignored while pinch-zoomed, where a smaller visual viewport is not a keyboard.
 *
 * --composer-block is the composer's own height, which scroll-padding uses so moving focus
 * never scrolls a chip or the coverage list in underneath the dock.
 */
function trackComposer() {
  const root = document.documentElement;
  const vv = window.visualViewport;
  let frame = 0;
  const update = () => {
    frame = 0;
    const inset = vv && vv.scale <= 1.01
      ? Math.max(0, Math.round(window.innerHeight - vv.height - vv.offsetTop)) : 0;
    root.style.setProperty('--keyboard-inset', `${inset}px`);
  };
  const queue = () => { if (!frame) frame = requestAnimationFrame(update); };
  if (vv) {
    vv.addEventListener('resize', queue);
    vv.addEventListener('scroll', queue);
  }
  if (typeof ResizeObserver === 'function') {
    new ResizeObserver(() => {
      root.style.setProperty('--composer-block', `${Math.ceil(els.composer.offsetHeight)}px`);
    }).observe(els.composer);
  }
  update();
}

/**
 * At 1024px and wider the Coverage disclosure is a side panel beside the conversation
 * (app.css lays it out): always open, and its summary is a heading that no longer toggles.
 * The same <details class="cov"> either way — tools/screenshots.mjs and the probes find it by
 * that selector — so this only forces `open` while the media query holds, and gives back
 * whatever the person had chosen when the window narrows again.
 */
const SIDE_PANEL_QUERY = '(min-width: 1024px)';
function bindCoveragePanel() {
  const cov = document.querySelector('details.cov');
  const summary = cov.querySelector('summary');
  const wide = window.matchMedia(SIDE_PANEL_QUERY);
  let chosen = cov.open;
  const apply = () => {
    if (wide.matches) {
      if (cov.dataset.side !== 'true') chosen = cov.open;
      cov.dataset.side = 'true';
      cov.open = true;
      summary.setAttribute('aria-disabled', 'true');
    } else {
      if (cov.dataset.side === 'true') cov.open = chosen;
      delete cov.dataset.side;
      summary.removeAttribute('aria-disabled');
    }
  };
  // The summary's default action is the toggle; cancelling the click (mouse, Enter, Space)
  // is what keeps the panel open, rather than reopening it after a flash of closing.
  summary.addEventListener('click', (event) => { if (wide.matches) event.preventDefault(); });
  wide.addEventListener('change', apply);
  apply();
}

function bind() {
  trackComposer();
  bindCoveragePanel();
  // The skip link moves focus itself (#111). Following its href would set location.hash to
  // #main, and a hash change re-prompts for the microphone in an installed iOS app — the
  // reason the app has no hash routing. The href stays for a page whose script never ran.
  document.querySelector('.skip-link').addEventListener('click', (event) => {
    event.preventDefault();
    els.main.focus();
  });
  els.provider.onchange = () => {
    state.connectStep = 'fields';
    onProviderChange();
    onSttChange();
  };
  els.stt.onchange = onSttChange;
  els['inference-consent'].onchange = () => {
    try {
      const destination = formInferenceDestination();
      if (els['inference-consent'].checked) inference.accept(destination.id);
      else inference.withdraw();
      fail(null);
    } catch (error) { fail(error); }
    renderConnect();
  };
  // On change, not on input: normalising every keystroke fights whoever is typing.
  els.stopword.onchange = () => applyTrigger(els.stopword.value);
  els['b-start'].onclick = () => startInterview({ handsFree: false }).catch(fail);
  els['b-start-voice'].onclick = () => startInterview({ handsFree: true }).catch(fail);
  els.speech.onchange = () => {
    const cancellingHosted = state.previewSpeech?.kind === 'openai';
    cancelSpeechPreview().catch(fail);
    els['speech-check'].textContent = '';
    onSpeechChange();
    if (els.speech.value !== 'openai' && !cancellingHosted) revokeHostedSpeech().catch(fail);
  };
  const invalidatePreview = () => {
    cancelSpeechPreview().catch(fail);
    onSpeechChange();
  };
  els['tts-consent'].onchange = () => {
    invalidatePreview();
    if (!els['tts-consent'].checked) revokeHostedSpeech().catch(fail);
  };
  for (const control of [els.ttskey, els['tts-voice']]) {
    control.addEventListener('input', () => {
      invalidatePreview();
      if (!state.speechRevoked && state.creds?.tts?.verified) revokeHostedSpeech().catch(fail);
      if (els['speech-check'].dataset.status !== 'error') {
        settingsFeedback('speech-check', 'Check and preview these speech settings before using them.');
      }
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
    try { state.voicePermit = requireInference(); }
    catch (error) { fail(error); return; }
    setHandsFree(false);
    const navigation = state.navigation;
    const id = state.session.id;
    const epoch = state.voiceEpoch;
    const turnId = openTurn(state.session).id;
    const ownsTurn = () => currentView(id, navigation) && epoch === state.voiceEpoch
      && openTurn(state.session)?.id === turnId;
    try {
      if (!state.voice) {
        prepareSpeechOutput();
        await setupVoice({ automatic: false });
      }
      if (!ownsTurn() || !state.voice || !state.voice.available) return;
      // autoStop false: the button is press-to-talk, so the user decides when they are
      // done. Whatever came back lands in the box for them to edit before sending.
      const heard = await listenOnce({ prompt: currentQuestion(), autoStop: false });
      if (ownsTurn() && heard.trim()) {
        els.answer.value = heard;
        state.answerSource = 'voice';
        queueDraftSave();
      }
    } catch (e) {
      if (ownsTurn()) {
        fail(e.code === 'audio-capture' ? e.message : e);
      }
    }
  };
  els.handsfree.onchange = async () => {
    const on = els.handsfree.checked;
    if (on) {
      try { state.voicePermit = requireInference(); }
      catch (error) { els.handsfree.checked = false; fail(error); return; }
    }
    if (on && state.voiceMode === 'manual' && !els.listening.hidden) {
      els.handsfree.checked = false;
      say('Stop the current recording before turning on hands-free.');
      return;
    }
    if (!on) { await exitVoice(); return; }
    if (state.voiceWrapPending || (!state.busy && !openTurn(state.session))) {
      setHandsFree(true);
      return;
    }
    state.wantHandsFree = true;
    savePrefs({ handsFree: true });
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
  els.baseurl.oninput = () => { invalidateConnection(); onBaseChange(); };
  for (const control of [els.apikey, els.model, els.wrapmodel]) {
    control.addEventListener('input', invalidateConnection);
  }
  els['b-check'].onclick = async () => {
    if (state.connectCheck?.status === 'checking') return;
    const focused = document.activeElement === els['b-check'];
    const restoreFocus = () => {
      if (focused && document.activeElement === document.body) {
        els[state.connectStep === 'ready' && els['panel-setup'].dataset.view === 'start'
          ? 'b-connect-change' : 'b-check'].focus();
      }
    };
    fail(null); say('Checking…');
    settingsFeedback('connection-error');
    const c = currentChoice();
    const attempt = { ...connectionContext(), status: 'checking' };
    state.connectCheck = attempt;
    const owns = () => state.connectCheck === attempt && ownsConnection(attempt);
    renderConnect();
    try {
      if (c.discoverModels) {
        // For a local server "does the key work" is the wrong question — it has no key.
        // What you actually want to know is whether it answers, and what it can run. For a
        // hosted one the list read IS the key check: listModels and validateKey probe the
        // same /models endpoint, so one call answers both and the catalogue arrives in the
        // model boxes as a side effect of asking.
        await refreshModels(owns);
      } else {
        if (c.id === 'artifact' && defaultProviderKind() !== 'artifact') {
          throw new Error('The Claude viewer is unavailable here. Choose a hosted or local provider.');
        }
        const p = await buildProvider();
        if (!owns()) return;
        await p.validateKey();
      }
      if (!owns()) return;
      attempt.status = 'checked';
      say(c.needsKey ? 'That key works.' : c.local ? 'That server answered.' : 'The Claude viewer is available.');
      // A key check leaves nothing more to do in the card, so Start folds it. A check that
      // just read the model list stays open: that list is what the person is about to use.
      const folding = !c.discoverModels && els['panel-setup'].dataset.view === 'start';
      if (folding) readyConnection(focused);
      else renderConnect();
      restoreFocus();
    } catch (e) {
      if (!owns()) return;
      say('');
      connectionFailure(e, 'check');
      restoreFocus();
    }
  };
  els['b-connect-change'].onclick = () => {
    state.connectPresentation++;
    state.connectStep = 'fields';
    renderConnect();
    els.provider.focus();
  };
  els['b-connect-next'].onclick = () => advanceConnection().catch(fail);
  els['b-connect-back'].onclick = () => {
    state.connectPresentation++;
    state.connectStep = 'provider';
    renderConnect();
    els.provider.focus();
  };
  els['b-forget'].onclick = () => forgetKey().catch(fail);
  els['b-send'].onclick = () => send();
  els['b-skip'].onclick = async () => {
    if (state.busy || !openTurn(state.session)) return;
    if (state.voice) state.voice.abort();      // a tap takes the wheel back mid-listen
    await doSkip();
  };
  els['b-thread-more'].onclick = () => {
    state.threadExpanded = !state.threadExpanded;
    renderThread();
  };
  els['b-wrap'].onclick = () => wrapUp('Wrapped up early, at your request.').catch(fail);
  els['b-wrap-offer'].onclick = () => {
    if (state.busy || state.voiceMode !== 'manual') return;
    return wrapUp('Wrapped up at your request.').catch(fail);
  };
  // Dismissing is the whole of it: session.wrapOffered is already persisted, so the engine
  // will not offer again, and the next answer goes ahead as if the card was never there.
  els['b-wrap-keep'].onclick = () => {
    dismissWrapOffer();
    if (!els.answer.disabled) els.answer.focus();
  };
  // The navigation bar, and only it, may animate as a View Transition (see navigateTo).
  els['b-library'].onclick = () => navigateTo(openLibrary);
  els['b-nav-new'].onclick = () => Promise.resolve(navigateTo(openStart)).catch(fail);
  els['b-settings'].onclick = () => navigateTo(openSettings);
  els['b-view-all'].onclick = () => openLibrary();
  // Every Done action closes the overflow menu first, synchronously: focus goes back to the
  // trigger before the panel can change, and Share still reaches navigator.share inside the
  // click — closeDoneMenu() is hidePopover(), and shareSession() calls shareTextFile(), which
  // calls navigator.share, before its first await.
  closeDoneMenu = wireOverflowMenu(els['done-menu'], els['b-done-more']);
  els['b-share'].onclick = () => {
    closeDoneMenu();
    return shareSession(state.session);
  };
  els['b-copy'].onclick = async () => {
    closeDoneMenu();
    try {
      // exportFor, not the DOM. #output renders the markdown now, so reading its textContent
      // back would hand over the prose with every marker stripped — which is not the thing
      // anyone is copying it for. One source of bytes for Copy, Download and Share.
      await navigator.clipboard.writeText(exportFor(state.session));
      say('Copied.');
    } catch { say('Could not reach the clipboard — select the text above instead.'); }
  };
  els['b-download'].onclick = () => {
    closeDoneMenu();
    return download();
  };
  els['b-err-dismiss'].onclick = dismissError;
  els['b-new'].onclick = async () => {
    closeDoneMenu();
    state.navigation++;
    teardownVoice();
    say('');
    fail(null);
    show('panel-setup');
    await renderResumeList();
  };
  els['b-reopen'].onclick = async () => {
    closeDoneMenu();
    if (!state.session || state.busy || workBySession.has(state.session.id)
        || state.writing === state.session.id) return;
    let permit;
    try { permit = requireInference(); }
    catch (error) { fail(error); return; }
    const navigation = ++state.navigation;
    const session = state.session;
    teardownVoice();
    state.voicePermit = permit;
    state.wantHandsFree = loadPrefs().handsFree === true && els.stt.value !== 'off';
    prepareSpeechOutput();
    // Said after show('panel-interview'), which would otherwise clear it. A failed save
    // needs no such care: show()'s fail(null) puts saveWarning() back.
    let providerNote = '';
    try {
      await buildProvider();
      requireInference(permit);
    } catch (error) {
      if (error.code === 'consent') { fail(error); return; }
      providerNote = withoutModelNote(error);
      state.provider = null;
      state.providerPermit = permit;
      state.providerRevision = state.connectRevision;
    }
    if (!currentView(session.id, navigation)) return;
    const reopenedAt = now();
    state.session = reopen(state.session, reopenedAt);
    if (state.session.archivedAt) state.session = restoreSession(state.session, reopenedAt);
    await persist();
    if (!currentView(session.id, navigation)) return;
    state.voiceMode = state.wantHandsFree ? 'active' : 'manual';
    show('panel-interview');
    say(providerNote);
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
  window.addEventListener('storage', (event) => {
    if (event.key !== PREFS_KEY && event.key !== null) return;
    const permit = state.voicePermit || state.providerPermit;
    if (permit) {
      try { inference.require(permit.destination, permit); }
      catch (error) {
        retireInference(error.message);
        if (state.session) fail(error);
      }
    }
    renderConnect();
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
  if (write) {
    const saved = savePrefs({ trigger: state.trigger });
    settingsFeedback('voice-preferences-status', saved ? 'Finish word saved on this device.'
      : 'Finish word could not be saved. It works on this page only; try again.',
    saved ? 'success' : 'error');
    renderSettingsSummaries();
  }
}

async function boot() {
  state.voiceStage = createVoiceStage(els['voice-stage']);
  bind();
  setupLibrary();
  setupSettingsIndex();
  setupTheme();
  state.install = createInstallController({
    onChange: renderInstall,
  });
  renderInstall();
  const prefs = loadPrefs();
  // Synchronously, before anything can start an interview: the loop needs the word to
  // build its matcher, and the keyring below is loaded asynchronously.
  applyTrigger(prefs.trigger, { persist: false });
  state.wantHandsFree = prefs.handsFree;
  // Where to open (#103): on Ideas when this device holds any idea, on Start when it holds
  // none. "Any" is what the library itself counts, archived ideas included — its default
  // filter shows them, so an archived-only device opens on a populated list, never on
  // "No ideas yet". The read starts here, beside the keyring's, so it overlaps that read
  // rather than queueing behind it (the switch still waits for the keyring to decrypt);
  // index.html already paints Start, which a fresh profile keeps.
  const navigation = state.navigation;
  const stored = listSessions().then((rows) => ({ rows }), (error) => ({ error }));
  const credentialEpoch = state.credentialEpoch;
  try {
    const loaded = await loadCredentials({ onUnreadable() {
      if (credentialEpoch === state.credentialEpoch) {
        credentialProblem('Stored keys could not be read and were cleared. Paste your keys again. Your ideas were not changed.');
      }
    } });
    if (credentialEpoch === state.credentialEpoch) state.creds = loaded;
  } catch (error) {
    if (credentialEpoch === state.credentialEpoch) {
      state.keysMayRemain = true;
      credentialProblem(`Saved keys could not be read and may remain on this device. Check site storage permissions and reload to retry, or use Your data → Forget keys: ${error.message}`);
      console.error(error);
    }
  }
  if (state.creds && state.creds.stt && state.creds.stt.kind) {
    els.stt.value = state.creds.stt.kind;
  }
  renderProviderChoices();
  onSttChange();
  renderSpeechSettings();
  if (window.speechSynthesis && window.speechSynthesis.addEventListener) {
    window.speechSynthesis.addEventListener('voiceschanged', renderSpeechVoices);
  }
  const { rows, error } = await stored;
  // Someone who tapped a destination while storage answered has already chosen a screen.
  if (navigation === state.navigation) {
    if (rows && rows.length > 0) {
      show('panel-library');
      state.library.render(rows);
    } else {
      show('panel-setup');
      // A failed read is not an empty device: open where a new idea can still start, and
      // say why the ideas are not showing. After show(), which clears toasts.
      if (error) {
        const reason = error && error.message ? ` (${error.message.replace(/\.+$/, '')})` : '';
        fail(new Error(`Saved ideas could not be read on this device${reason}. `
          + 'You can still start a new one.', { cause: error }));
      }
    }
  }
  await renderResumeList(rows);
  els.version.textContent = `IdeaForge v${VERSION}`;

  // Installability and an offline cold start. Needs a secure context, so it simply does
  // not register on a file:// open — which is fine, the app still runs.
  if ('serviceWorker' in navigator && location.protocol !== 'file:') {
    navigator.serviceWorker.register('./sw.js').catch(() => { /* not fatal */ });
  }
}

boot();
