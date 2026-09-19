// A recogniser and a synthesiser that do what they are told.
//
// Chrome's --use-fake-device-for-media-stream feeds a fixed tone, not speech, so it can
// exercise the recorder and the energy gate but can never produce a transcript. Everything
// that decides what a dictated answer MEANS is therefore untestable against it. This
// replaces the two platform objects instead, which works because webspeech.js resolves the
// constructor lazily and speak.js reads window.speechSynthesis at call time — neither
// caches it at module scope, so neither notices.
//
// Deliberately NOT an ES module. The same bytes are loaded two ways: imported by a browser
// probe, and read off disk and handed to CDP's Page.addScriptToEvaluateOnNewDocument by
// tools/validate-local.mjs, which runs before the app's own modules and outside the page's
// CSP. One definition, no build step, and it keeps working when IDEAFORGE_URL points at a
// built container that does not serve /test/.
//
// The fidelity that matters is the event shape. listenViaWebSpeech walks
// `ev.resultIndex -> ev.results.length` and reads `results[i].isFinal` and
// `results[i][0].transcript`. A fake that emitted a flat {results:[{transcript}]} would
// pass happily against a completely broken accumulator, which is the one thing this is for.

(function installFakeVoice(global) {
  'use strict';

  /** One alternative, one result: the array-like shape the real event carries. */
  function makeResult(transcript, isFinal) {
    const item = [{ transcript: transcript, confidence: 0.9 }];
    item.isFinal = !!isFinal;
    return item;
  }

  const recognition = {
    startCount: 0,
    stopCount: 0,
    abortCount: 0,
    /** One entry per listening session, with the flags the caller set on it. */
    sessions: [],
    scriptRemaining: () => queue.length,
  };

  const synthesis = {
    /** Everything the app has said, in order, with when it said it. */
    spoken: [],
    cancels: 0,
    /**
     * Was an utterance in flight at this timestamp? The barge-in check.
     *
     * Strictly inside the interval: an utterance that ended on the same millisecond a
     * listening session began did not overlap it, and `>=` here reported every correctly
     * sequenced speak-then-listen as a barge-in.
     */
    speakingAt(t) {
      return synthesis.spoken.some((u) => u.startedAt < t && (u.endedAt === null || u.endedAt > t));
    },
    said(re) {
      return synthesis.spoken.some((u) => (re instanceof RegExp ? re.test(u.text) : u.text.includes(re)));
    },
  };

  /** Utterances still to be handed out, one per listening session. */
  let queue = [];
  let config = { speakMs: 20, dropUtterance: null, flushOnStop: true };
  let saved = null;

  // ────────────────────────────────────────────────── the recogniser

  function FakeRecognition() {
    this.lang = '';
    this.continuous = false;
    this.interimResults = false;
    this.maxAlternatives = 1;
    this._timers = [];
    this._results = [];
    this._pendingInterim = null;
    this._live = false;
  }

  FakeRecognition.prototype._clear = function _clear() {
    for (const t of this._timers) clearTimeout(t);
    this._timers = [];
  };

  FakeRecognition.prototype._fire = function _fire(resultIndex) {
    if (!this.onresult) return;
    this.onresult({ resultIndex: resultIndex, results: this._results });
  };

  FakeRecognition.prototype.start = function start() {
    recognition.startCount += 1;
    this._live = true;
    this._results = [];
    this._pendingInterim = null;

    const session = {
      startedAt: performance.now(),
      endedAt: null,
      flags: { lang: this.lang, continuous: this.continuous, interimResults: this.interimResults },
    };
    recognition.sessions.push(session);
    this._session = session;

    // The liveness probe must not eat an utterance. probeWebSpeech sets continuous=false
    // and interimResults=false; listenViaWebSpeech sets both true. Discriminating on that
    // also pins those assignments — change them and the fake stops feeding, loudly.
    if (!this.continuous) {
      this._timers.push(setTimeout(() => { if (this.onstart) this.onstart(); }, 5));
      return;
    }

    const steps = queue.length ? queue.shift() : [];
    this._timers.push(setTimeout(() => { if (this.onstart) this.onstart(); }, 1));

    for (const step of steps) {
      if (step.deaf) return;              // fires nothing, ever — the iPhone lie mid-stream
      this._timers.push(setTimeout(() => this._step(step), step.at || 0));
    }
  };

  FakeRecognition.prototype._step = function _step(step) {
    if (!this._live) return;

    if (step.interim != null) {
      // An interim rewrites the slot at the write cursor; it does not advance it.
      const at = this._results.length - (this._pendingInterim === null ? 0 : 1);
      this._results[at] = makeResult(step.interim, false);
      this._pendingInterim = at;
      this._fire(at);
      return;
    }

    if (step.final != null) {
      // A final writes the cursor slot and THEN advances it.
      const at = this._pendingInterim === null ? this._results.length : this._pendingInterim;
      this._results[at] = makeResult(step.final, true);
      this._pendingInterim = null;
      this._fire(at);
      return;
    }

    if (step.replay != null) {
      // Re-announce an index that has already settled. The spec permits it, and an
      // accumulator built on `+=` will count the clause twice.
      this._fire(step.replay);
      return;
    }

    if (step.error) {
      if (this.onerror) this.onerror({ error: step.error });
      return;
    }

    if (step.end) this._end();
  };

  FakeRecognition.prototype._end = function _end() {
    if (!this._live) return;
    this._live = false;
    this._clear();
    if (this._session) this._session.endedAt = performance.now();
    if (this.onend) this.onend();
  };

  /** An engine flushes what it has captured, then ends. */
  FakeRecognition.prototype.stop = function stop() {
    recognition.stopCount += 1;
    if (!this._live) return;
    this._clear();
    if (config.flushOnStop && this._pendingInterim !== null) {
      const at = this._pendingInterim;
      this._results[at] = makeResult(this._results[at][0].transcript, true);
      this._pendingInterim = null;
      this._fire(at);
    }
    setTimeout(() => this._end(), 10);
  };

  /** Throw the session away with no flush. The cancellation path depends on the difference. */
  FakeRecognition.prototype.abort = function abort() {
    recognition.abortCount += 1;
    if (!this._live) return;
    this._clear();
    this._end();
  };

  // ────────────────────────────────────────────────── the synthesiser

  function FakeUtterance(text) {
    this.text = String(text == null ? '' : text);
    this.lang = '';
    this.rate = 1;
    this.volume = 1;
  }

  const fakeSynthesis = {
    speaking: false,
    pending: false,
    paused: false,
    getVoices() {
      // Non-empty on the FIRST call, which short-circuits voicesReady's `voiceschanged`
      // wait in speak.js and exercises pickVoice's localService preference.
      return [{ name: 'Fake', lang: 'en-US', localService: true, default: true }];
    },
    addEventListener() {},
    removeEventListener() {},
    speak(u) {
      const record = { text: u.text, lang: u.lang, rate: u.rate, startedAt: performance.now(), endedAt: null };
      synthesis.spoken.push(record);
      fakeSynthesis.speaking = true;
      fakeSynthesis._live = u;
      fakeSynthesis._record = record;

      // Chrome silently drops an utterance that outlasts its ~15s watchdog and never fires
      // onend. speak.js caps its own wait for exactly this; `dropUtterance` proves the cap
      // holds rather than trusting the comment.
      if (config.dropUtterance != null && synthesis.spoken.length === config.dropUtterance) return;

      // Asynchronously, always. A synchronous onend hides ordering bugs.
      setTimeout(() => {
        record.endedAt = performance.now();
        fakeSynthesis.speaking = false;
        fakeSynthesis._live = null;
        fakeSynthesis._record = null;
        if (u.onend) u.onend({});
      }, config.speakMs);
    },
    cancel() {
      synthesis.cancels += 1;
      const u = fakeSynthesis._live;
      const record = fakeSynthesis._record;
      fakeSynthesis.speaking = false;
      fakeSynthesis._live = null;
      fakeSynthesis._record = null;
      // A cancelled utterance HAS stopped. Leaving endedAt null left it looking as though
      // the app were still talking for the rest of the run, which made every later
      // barge-in check fire — and speak.js cancels on entry to every call.
      if (record && record.endedAt === null) record.endedAt = performance.now();
      // Chrome's real behaviour: the in-flight utterance errors rather than ending.
      if (u && u.onerror) u.onerror({ error: 'interrupted' });
    },
    pause() {},
    resume() {},
  };

  // ────────────────────────────────────────────────── install / restore

  global.__FakeVoice = {
    recognition: recognition,
    synthesis: synthesis,

    /**
     * @param {Window} win the window to install into — this one, or an iframe's
     * @param {{script?: Array, speakMs?: number, dropUtterance?: number,
     *          flushOnStop?: boolean}} [opts]
     */
    install(win, opts) {
      const o = opts || {};
      config = {
        speakMs: o.speakMs == null ? 20 : o.speakMs,
        dropUtterance: o.dropUtterance == null ? null : o.dropUtterance,
        flushOnStop: o.flushOnStop !== false,
      };
      queue = (o.script || []).slice();
      recognition.startCount = 0;
      recognition.stopCount = 0;
      recognition.abortCount = 0;
      recognition.sessions.length = 0;
      synthesis.spoken.length = 0;
      synthesis.cancels = 0;

      saved = {
        win: win,
        SR: win.SpeechRecognition,
        WK: win.webkitSpeechRecognition,
        utter: win.SpeechSynthesisUtterance,
        synthDesc: Object.getOwnPropertyDescriptor(win, 'speechSynthesis'),
      };

      // SpeechRecognition and SpeechSynthesisUtterance are ordinary writable properties.
      win.SpeechRecognition = FakeRecognition;
      win.webkitSpeechRecognition = FakeRecognition;
      win.SpeechSynthesisUtterance = FakeUtterance;
      // speechSynthesis is NOT: it is a readonly WebIDL attribute on Window.prototype, and
      // module code is strict, so a plain assignment throws TypeError. Shadow it instead.
      Object.defineProperty(win, 'speechSynthesis', {
        value: fakeSynthesis, configurable: true, writable: true,
      });
      return global.__FakeVoice;
    },

    /** Hand the next listening session this utterance. */
    script(utterances) {
      queue = utterances.slice();
      return global.__FakeVoice;
    },

    restore() {
      if (!saved) return;
      const w = saved.win;
      w.SpeechRecognition = saved.SR;
      w.webkitSpeechRecognition = saved.WK;
      w.SpeechSynthesisUtterance = saved.utter;
      if (saved.synthDesc) Object.defineProperty(w, 'speechSynthesis', saved.synthDesc);
      else delete w.speechSynthesis;
      saved = null;
    },
  };
}(typeof window === 'undefined' ? globalThis : window));
