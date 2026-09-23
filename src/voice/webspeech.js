// The browser's own recogniser, where it genuinely works.
//
// When it works it is the best option available: free, instant, and it shows words as you
// say them. The problem is that feature detection is a lie on the exact platform this app
// most needs — inside an installed iOS home-screen app, `webkitSpeechRecognition` exists,
// constructs, and `start()` returns without throwing, and then nothing ever happens. No
// error, no result, no end event.
//
// So the only trustworthy test is behavioural: start it, and require the engine to prove
// it is alive by firing `start` or `audiostart` within a short window. Anything else —
// silence, or Edge's immediate `network` error, or Firefox's disabled-by-default pref —
// is treated as unavailable and the recorder path takes over for good.
//
// Android Chrome keeps neither promise `continuous` makes. It still ends a session every
// few seconds, so a long dictated answer arrives as several sessions and we restart until the
// caller says stop rather than trusting the flag. And while a session runs it reports every
// in-progress guess as a FINAL result at a new index, usually with confidence 0 — so on that
// platform "final" does not mean settled, and a new index does not mean new words.
// `assembleTranscript` is what turns that stream back into what was actually said.

const VERDICT_KEY = 'ideaforge.webspeech';
/**
 * Bumped when the MEANING of a stored verdict changes, so every value written under the old
 * meaning is discarded rather than believed. v1 cached a 'dead' that a pending microphone
 * prompt had caused — a verdict about a human reading a dialog, not about an engine — and
 * because a cached verdict short-circuits the probe, that was permanent per origin.
 */
const VERDICT_VERSION = '2';
const PROBE_MS = 1500;
/**
 * The deadline when the browser is about to ask for the microphone.
 *
 * `rec.start()` is what RAISES that prompt, so on a first run the probe is not timing an
 * engine, it is timing a person finding and tapping Allow. Nobody does that in PROBE_MS, so
 * the old budget declared every first run dead. Generous rather than precise: setupVoice
 * runs after the interview panel is already on screen, so waiting costs a late microphone
 * button and nothing else. A refusal does not wait it out — Chrome fires `not-allowed` the
 * moment they decline.
 */
const PROMPT_MS = 20000;
/**
 * How long a dictation session may go with no event of any kind before we give up on it.
 *
 * Generous, because it is a last resort and not a turn timer: a speaker thinking in silence
 * produces no events either, and cutting them off would be a worse bug than the one this
 * prevents. Lives here rather than with the driving-mode tunables because it defends
 * press-to-talk just as much — the engine does not know which button started it.
 */
const DEAF_MS = 6000;

const Impl = () => (typeof window === 'undefined'
  ? null
  : window.SpeechRecognition || window.webkitSpeechRecognition || null);

export function webSpeechPresent() {
  return !!Impl();
}

/** The remembered result of a previous probe: 'alive' | 'dead' | null. */
export function cachedVerdict() {
  try {
    const raw = localStorage.getItem(VERDICT_KEY);
    if (!raw) return null;
    const at = raw.indexOf(':');
    // An unversioned value was written by v1 and is not trusted; see VERDICT_VERSION.
    if (at < 0 || raw.slice(0, at) !== VERDICT_VERSION) return null;
    const verdict = raw.slice(at + 1);
    return verdict === 'alive' || verdict === 'dead' ? verdict : null;
  } catch { return null; }
}
function remember(verdict) {
  try {
    localStorage.setItem(VERDICT_KEY, `${VERDICT_VERSION}:${verdict}`);
  } catch { /* private window */ }
}

export function forgetVerdict() {
  try { localStorage.removeItem(VERDICT_KEY); } catch { /* private window */ }
}

/**
 * Whether the browser is about to ask for the microphone, so the probe can tell a broken
 * engine from an unanswered question.
 *
 * Firefox and Safari do not accept 'microphone' here and throw, which is why the unknown
 * case must behave exactly as before: those are the browsers the short budget and the
 * cached verdict were written for.
 */
export async function micPermissionState() {
  try {
    if (!navigator.permissions || !navigator.permissions.query) return 'unknown';
    const status = await navigator.permissions.query({ name: 'microphone' });
    return (status && status.state) || 'unknown';
  } catch {
    return 'unknown';
  }
}

/**
 * Prove the engine is alive, rather than merely present.
 *
 * @param {{lang?: string, force?: boolean, probeMs?: number, promptMs?: number}} [opts]
 *   force re-probes past a cached verdict; the two budgets are injectable for the same
 *   reason `deafMs` is — the twenty-second one is otherwise untestable in any suite anyone
 *   would be willing to wait for.
 * @returns {Promise<'alive'|'dead'>}
 */
export async function probeWebSpeech({
  lang = 'en-US', force = false, probeMs = PROBE_MS, promptMs = PROMPT_MS,
} = {}) {
  const SR = Impl();
  if (!SR) return 'dead';
  if (!force) {
    const cached = cachedVerdict();
    if (cached === 'alive' || cached === 'dead') return cached;
  }

  // A permission decision is not an engine verdict, and conflating the two is what made a
  // first run on Android permanent: the prompt went up, PROBE_MS expired while it was still
  // on screen, and 'dead' was cached for the origin for ever. So when a prompt is pending,
  // the probe waits for a person rather than for an engine, and any 'dead' it reaches is
  // reported but NOT remembered — the answer can change the next time we ask.
  const permission = await micPermissionState();
  const pending = permission === 'prompt';

  return new Promise((resolve) => {
    let settled = false;
    let rec;
    const done = (verdict, { cache = true } = {}) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      try { if (rec) { rec.onend = null; rec.abort(); } } catch { /* nothing to abort */ }
      if (cache) remember(verdict);
      resolve(verdict);
    };

    const timer = setTimeout(() => done('dead', { cache: !pending }), pending ? promptMs : probeMs);

    try {
      rec = new SR();
    } catch {
      done('dead');
      return;
    }
    rec.lang = lang;
    rec.interimResults = false;
    rec.continuous = false;
    // Either of these proves the engine actually engaged the microphone pipeline.
    rec.onstart = () => done('alive');
    rec.onaudiostart = () => done('alive');
    rec.onresult = () => done('alive');
    rec.onerror = (e) => {
      const kind = e && e.error;
      // 'no-speech' and 'aborted' mean it ran, which is all we asked.
      if (kind === 'no-speech' || kind === 'aborted') { done('alive'); return; }
      // Edge's 'network' is the engine failing and is worth remembering. A refusal is not:
      // the user can grant the microphone later from site settings, and an engine written
      // off over a permission would never be tried again to find out.
      const refused = kind === 'not-allowed' || kind === 'service-not-allowed';
      done('dead', { cache: !refused && !pending });
    };
    rec.onend = () => done('dead', { cache: !pending });

    try { rec.start(); } catch { done('dead', { cache: !pending }); }
  });
}

// ─────────────────────────────────────────────── assembling what was said
//
// Reported from Android Chrome over a car's Bluetooth: an answer came back as "I I want I
// want to I want to … I want to create a game like Tetris". Every guess the recogniser made
// on the way to the sentence had arrived at its own index, already final, and joining every
// final kept every draft. Nothing was re-announced — every index was new — so the guard
// against a replayed index could not see it.
//
// Pure, and exported, so the rule can be held to a corpus in `node --test` without a
// recogniser. It compares WHOLE results and never touches the words inside one: "very very"
// and "no no no" are what the speaker said. A result is only ever replaced by a LATER one.
//
// Which finals are drafts is decided by two facts about the engine, not by guessing from the
// words. A draft has no confidence behind it, and it comes from a session that has never sent
// an interim: Chromium on Android turns every partial into a final, so such a session never
// sends one, while desktop Chrome, Edge and Safari send interims and finalise clauses. Only
// drafts are ever revised or swept, so an engine that merely reports confidence 0 for its
// ordinary finals keeps every sentence.
//
//   Absorb. A result that repeats the newest kept result(s) word for word, and perhaps adds
//   more, replaces them. That is a growing draft, an exact re-send, or a restarted session
//   replaying the phrase it last gave — the same shape whichever engine sent it.
//
//   Revise. A draft that arrives changing a word or two of the draft before it, in the same
//   recogniser session ("…a gay" → "…a game like", "skip the" → "skip this", "…like Tet
//   risk" → "…like Tetris", "I" → "I'm"), replaces it. Never across a restart, and not when
//   it got there by absorbing a sentence begun again: drafts that start again from "it" and
//   grow into "it should be cheap" are a new sentence after "it should be fast".
//
//   Sweep. The confirmed final that closes an Android session is the recogniser's last word
//   on that session's audio, so it replaces the session's trailing drafts even where it
//   respells them ("one hundred and twenty" → "120", "I am done" → "I'm done") — but not
//   words a draft carried in from an earlier session, unless the final opens with them too.
//
// Deliberately absent: dropping a result for being shorter. A prefix of the last result
// looks like a stale draft, but it is also what "over" looks like said on its own after
// "over the years we grew" — and discarding it would discard the one word that ends the
// answer.

/**
 * Words for COMPARING two results, never for output.
 *
 * Android capitalises and punctuates one draft differently from the next. Letters are
 * `\p{L}` rather than `[a-z]` because names and borrowed words ("café", "naïve") are not
 * ASCII, and a segment that normalised to nothing would be a prefix of everything.
 */
function wordsOf(text) {
  return String(text == null ? '' : text).normalize('NFKC').toLowerCase()
    .replace(/['’]/g, '')
    .replace(/[^\p{L}\p{M}\p{N}]+/gu, ' ')
    .trim()
    .split(' ')
    .filter(Boolean);
}

/** Does `words` begin with every word of `lead`, in order? Equal lists count. */
function opensWith(words, lead) {
  if (lead.length > words.length) return false;
  for (let i = 0; i < lead.length; i++) if (words[i] !== lead[i]) return false;
  return true;
}

/**
 * How many words the two lists share in order — a revision keeps most of them.
 *
 * The earlier draft's LAST word may be cut short: the recogniser writes "I" or "Tet" before
 * it has heard "I'm" or "Tetris".
 */
function sharedWords(was, now) {
  const same = (i, j) => was[i] === now[j] || (i === was.length - 1 && now[j].startsWith(was[i]));
  let prev = new Array(now.length + 1).fill(0);
  for (let i = 1; i <= was.length; i++) {
    const row = [0];
    for (let j = 1; j <= now.length; j++) {
      row[j] = same(i - 1, j - 1) ? prev[j - 1] + 1 : Math.max(prev[j], row[j - 1]);
    }
    prev = row;
  }
  return prev[now.length];
}

/** How many of the newest kept results `seg` repeats in full, widest first; 0 if none. */
function restated(kept, seg) {
  let widest = 0;
  let span = [];
  for (let k = 1; k <= kept.length; k++) {
    span = kept[kept.length - k].words.concat(span);
    if (span.length > seg.words.length) break;
    if (opensWith(seg.words, span)) widest = k;
  }
  return widest;
}

/**
 * Is `seg` the next draft of `top`, with a word or two changed, rather than a new sentence?
 *
 * Google's recogniser keeps the opening of a hypothesis and rewrites its tail — including
 * merging two words into one — so: the same recogniser session, no more than a word shorter,
 * at most two of the earlier draft's words missing, and the opening still standing. That is the same first word, or a
 * one-word draft it completes ("I" → "I'm"), or a corrected first word with the second
 * unchanged ("hi want" → "I want"). "I think so" then "so what else" shares a word, but not
 * an opening, and stays two things said.
 */
function revises(seg, top) {
  if (!seg.draft || !top.draft || seg.session !== top.session) return false;
  const was = top.words;
  const now = seg.words;
  if (now.length < was.length - 1) return false;
  const opening = now[0] === was[0]
    || (was.length === 1 ? now[0].startsWith(was[0]) : now[1] === was[1]);
  if (!opening) return false;
  return sharedWords(was, now) >= Math.max(1, was.length - 2);
}

/** `seg` takes the place of what it restates, and with it any words those held from
 *  earlier sessions. Repeated: a draft that grows past a shrunken one also grows past the
 *  longer draft kept beneath it. */
function absorb(kept, seg) {
  let grew = false;
  for (let k = restated(kept, seg); k; k = restated(kept, seg)) {
    let at = 0;
    for (const gone of kept.splice(kept.length - k, k)) {
      const earlier = gone.session < seg.session ? gone.words.length : gone.carried;
      seg.carried = Math.max(seg.carried, at + earlier);
      at += gone.words.length;
    }
    grew = true;
  }
  return grew;
}

/** Fold results into the sentence they add up to. */
function fold(segments) {
  const kept = [];
  for (const result of segments) {
    // `carried`: how many of its leading words came from an earlier session's results.
    const seg = { ...result, carried: 0 };
    const grew = absorb(kept, seg);
    const top = kept[kept.length - 1];
    if (!grew && top && revises(seg, top)) {
      seg.carried = Math.max(seg.carried, top.carried);
      kept.pop();
      absorb(kept, seg);
    }
    if (seg.final && seg.confidence > 0) {
      for (let t = kept[kept.length - 1];
        t && t.draft && t.session === seg.session
          && opensWith(seg.words, t.words.slice(0, t.carried));
        t = kept[kept.length - 1]) {
        kept.pop();
      }
      absorb(kept, seg);
    }
    kept.push(seg);
  }
  return kept.map((s) => s.text).join(' ');
}

/**
 * What a capture adds up to so far.
 *
 * @param {Array<{transcript?: string, isFinal: boolean, confidence?: number, session?: number,
 *                interims?: boolean}>} [results]
 *   the whole capture in order: every final of earlier sessions, then this session's list.
 *   `interims` marks a result whose session has sent an interim at any point — which the
 *   caller has to remember, because once every interim has been finalised the list itself
 *   no longer shows one.
 * @returns {{finals: string, text: string, settled: boolean}}
 *   finals  — final results only; what the capture resolves with
 *   text    — finals with the live interim merged in; what is shown, and what isComplete judges
 *   settled — no interim, and the last result is a final the engine stands behind. On Android
 *             every draft is final, so `isFinal` alone would end "we went over budget" at
 *             "we went over".
 *
 * A DRAFT is a final with no confidence behind it, from a session that has never sent an
 * interim. Only a draft is ever revised or swept, and only by a later result standing for
 * the same speech; confidence never removes a word on its own. Desktop finals carry
 * confidence and their sessions send interims, so none of the draft handling reaches them.
 */
export function assembleTranscript(results = []) {
  const rows = (results || []).filter(Boolean);
  const chatty = new Set(rows.filter((r) => !r.isFinal || r.interims).map((r) => r.session ?? 0));
  const finals = [];
  let live = '';
  let liveSession = 0;
  for (const r of rows) {
    const raw = r.transcript == null ? '' : String(r.transcript);
    const session = r.session ?? 0;
    if (!r.isFinal) { live += raw; liveSession = session; continue; }
    const words = wordsOf(raw);
    if (!words.length) continue;
    const draft = !(r.confidence > 0) && !chatty.has(session);
    finals.push({ text: raw.trim(), words, final: true, confidence: r.confidence, session, draft });
  }
  const said = fold(finals);
  const liveWords = wordsOf(live);
  const text = liveWords.length
    ? fold(finals.concat({
      text: live.trim(), words: liveWords, final: false, session: liveSession, draft: false,
    }))
    : said;
  const last = rows[rows.length - 1];
  const settled = !live.trim() && !!last && !!last.isFinal && last.confidence > 0;
  return { finals: said, text, settled };
}

/**
 * A dictation session over the Web Speech API.
 *
 * `autoStop` ends the answer at the engine's own endpoint — the natural pause after a
 * sentence — which is what hands-free mode needs. Without it the session is restarted
 * until the caller says stop, which is what a press-to-talk button needs, and which is
 * also what makes Android usable: it ends the session every few seconds regardless of
 * `continuous`, so a restart is the only way to hear a long answer.
 *
 * `deafMs` is the stream's answer to what `probeWebSpeech` does for the start. The probe
 * proves the engine was alive when it began; nothing proved it stayed that way, and an
 * engine that emits one interim and then goes silent — no result, no end, no error — left
 * this promise unsettled for ever, with nothing on screen and nothing in the console. That
 * is the same installed-iOS failure the file header describes, arriving a few seconds later
 * than the probe can see it.
 *
 * `isComplete` is how driving mode ends an answer on a word rather than on a pause. It is a
 * CALLBACK rather than a trigger string so the rule itself stays in src/core/driving.js,
 * where it is pure and unit-tested; this file only decides WHEN to consult it. The text it
 * receives is raw, trigger included, and so is the text resolved — all stripping happens
 * above, which keeps one definition of the rule and gives the recorder path the same one.
 *
 * @param {{lang?: string, onInterim?: Function, autoStop?: boolean, deafMs?: number,
 *          isComplete?: (text: string) => boolean, settleMs?: number}} opts
 * @returns {{promise: Promise<string>, stop: Function, abort: Function}}
 */
export function listenViaWebSpeech({
  lang = 'en-US', onInterim, autoStop = false, deafMs = DEAF_MS,
  isComplete = null, settleMs = 600,
} = {}) {
  const SR = Impl();
  if (!SR) throw new Error('no speech recognition in this browser');

  const rec = new SR();
  rec.lang = lang;
  rec.interimResults = true;
  rec.continuous = true;              // honoured on desktop; on Android, drafts arrive as finals
  if ('maxAlternatives' in rec) rec.maxAlternatives = 1;

  let settled = false;
  let deaf = null;
  let settling = null;

  /** Resolve or reject exactly once, and stop watching. */
  function settle(fn) {
    if (settled) return;
    settled = true;
    clearTimeout(deaf);
    clearTimeout(settling);
    fn();
  }

  /**
   * Any event at all is proof of life; the deadline restarts from it.
   *
   * Giving up RESOLVES with whatever was heard rather than rejecting, because a dead engine
   * is a small loss and not an error — the same judgement speak.js makes about an utterance
   * Chrome drops. The caller re-asks; it does not need a stack trace.
   */
  function alive() {
    if (settled || !deafMs) return;
    clearTimeout(deaf);
    deaf = setTimeout(() => {
      try { rec.onend = null; rec.abort(); } catch { /* nothing to abort */ }
      settle(() => resolve(heard()));
    }, deafMs);
  }

  // Finals from EARLIER sessions of this same capture, kept apart from the current
  // session's. Android ends a session every few seconds whatever `continuous` says, so one
  // answer routinely spans several, and the carry is what stitches them back together. Kept
  // as results rather than as a string: a restarted session can open by replaying the phrase
  // the last one ended on, and only as a result can that replay be recognised as one.
  let carried = [];
  let session = [];
  let sessionNo = 0;
  let sessionSentInterim = false;
  let wantMore = true;
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => { resolve = res; reject = rej; });

  const now = () => assembleTranscript(carried.concat(session));
  const heard = () => now().finals;

  /**
   * Recomputed from the whole result list every time rather than appended to.
   *
   * `results` is cumulative for the session, and the spec lets an engine re-announce an
   * index that has already settled. Appending on each event counted such a clause twice and
   * put the user's own words back to them stuttered, in the export, with nothing on screen
   * to explain it.
   *
   * Recomputing was not enough on its own. Android puts every draft of a sentence at a NEW
   * index, already final, so the list itself holds "I", "I want", "I want to"… and joining its
   * finals put all of them in the answer. The list is copied out as plain data and read as
   * drafts by `assembleTranscript`.
   */
  rec.onresult = (ev) => {
    // A result after the capture settled is too late to change the answer, and onInterim
    // would write it back into an answer box the caller has already cleared.
    if (settled) return;
    for (let i = 0; i < ev.results.length; i++) {
      if (!ev.results[i].isFinal) sessionSentInterim = true;
    }
    session = [];
    for (let i = 0; i < ev.results.length; i++) {
      const r = ev.results[i];
      const alt = r[0] || {};
      session.push({
        transcript: alt.transcript, isFinal: !!r.isFinal, confidence: alt.confidence,
        session: sessionNo, interims: sessionSentInterim,
      });
    }
    alive();
    const so = now();
    if (onInterim) onInterim(so.text);
    if (isComplete) judge(so.text, so.settled);
  };

  /**
   * Decide whether that was the end of the answer.
   *
   * A final that completes it ends the capture immediately. An interim only ARMS a timer,
   * for two reasons that happen to want the same delay:
   *
   *   The word might not be the end. "We went over budget" reads as complete for as long as
   *   it takes the speaker to reach "budget", and firing on the first terminal-looking
   *   interim truncates every answer containing the trigger mid-sentence — invisibly, to
   *   someone watching the road.
   *
   *   And `stop()` resolves with settled finals only. Ending the instant a trigger appears
   *   in an interim throws away the clause it appeared in, because the engine has not
   *   finalised it yet. The wait is what lets it.
   *
   * "Final" here means a final the engine stands behind — confidence > 0, no interim
   * pending (`settled`). Any other final, an Android draft above all, is judged exactly like
   * an interim. Treated as settled, "we went over budget" ended at its draft "we went over",
   * and the first draft of "repeat customers…" was taken as the command "repeat".
   */
  function judge(text, settled) {
    clearTimeout(settling);
    if (!isComplete(text)) return;
    if (settled) { wantMore = false; finishNow(); return; }
    settling = setTimeout(() => { wantMore = false; finishNow(); }, settleMs);
  }

  /** End the capture, keeping whatever the engine has flushed by the time it stops. */
  function finishNow() {
    try { rec.stop(); } catch { /* already stopped; onend will settle it */ }
    // A stop that produces no onend must not strand the answer.
    setTimeout(() => settle(() => resolve(heard())), 400);
  }

  rec.onerror = (ev) => {
    alive();
    const kind = ev && ev.error;
    if (kind === 'no-speech' || kind === 'aborted') return;   // onend will settle it
    wantMore = false;
    // A mid-session failure on an engine that had been working: keep whatever was heard
    // rather than throwing away a half-finished answer.
    if (heard()) return;
    settle(() => reject(new Error(kind === 'not-allowed'
      ? 'Microphone access was denied.'
      : `Speech recognition failed (${kind || 'unknown'}).`)));
  };

  rec.onstart = alive;
  rec.onaudiostart = alive;

  rec.onend = () => {
    // In hands-free mode the engine's endpoint IS the end of the answer — but only once
    // it has actually heard something, otherwise Android's habit of ending every few
    // seconds would return an empty answer before the user finished thinking.
    if (autoStop && heard()) { settle(() => resolve(heard())); return; }
    if (wantMore) {
      carried = carried.concat(session.filter((r) => r.isFinal));
      session = [];
      sessionNo += 1;
      sessionSentInterim = false;
      try { rec.start(); alive(); return; } catch { /* fall through and settle */ }
    }
    settle(() => resolve(heard()));
  };

  try { rec.start(); } catch (e) { settle(() => reject(new Error(`Could not start dictation: ${e.message}`))); }
  alive();

  return {
    promise,
    stop() { wantMore = false; try { rec.stop(); } catch { /* already stopped */ } },
    abort() { wantMore = false; try { rec.abort(); } catch { /* already stopped */ } },
  };
}
