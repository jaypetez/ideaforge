# CLAUDE.md

The shared implementation reference for Claude Code and GitHub Copilot. The historical
filename is also Claude Code's entry point. Load the shared working loop alongside it:

@AGENTS.md

The [lean coding section](AGENTS.md#lean-coding) is the single owner of optional process
guidance. Project-native hooks read it; mode controls never supersede the invariants below.
See [assistant processes](docs/ASSISTANT-PROCESS.md) for state, discovery and client limits.

## What this is

IdeaForge interviews a user about a half-formed idea — one question at a time, each
follow-up grounded in the last answer — and emits a markdown file containing an LLM-ready
refined prompt, the open questions they left, and the full transcript. It runs entirely in
the browser with the user's own API key.

## Commands

```sh
npm test                 # lint:purity, then the unit suite
npm run test:docs        # public guide/README facts, links and assistant wiring
npm run test:graph       # syntax + import-graph checks over the shipped modules
npm run test:browser:required
                         # headless Chrome on the assembled publishable tree, fail-closed
npm run test:all         # unit + graph + browser
npm run lint:purity      # the architecture gate alone
npm run serve            # http://127.0.0.1:8765  (file:// will NOT work)
npm run screenshots      # regenerate docs/ — the README's images and worked example
npm run vectors          # regenerate test/vectors/ — the golden vectors android/ replays

node --test test/voice.test.mjs                        # one file
node --test --test-name-pattern="zero gain" "test/**/*.test.mjs"
node --test "test/**/*.test.mjs"                       # unit files, not browser probes
```

There is **no install step** — zero dependencies, not even dev dependencies. Node 22+.
That is a rule for the web app and everything the site serves. The native Android app in
`android/` is a separate Gradle project with its own dependencies; none of it is published
by `tools/assemble-site.mjs`, and a test pins that.

`npm run serve` needs `python` on PATH. Any static server works; ES modules, IndexedDB and
the service worker all need a real origin.

`npm run test:graph` is `tools/check-module-graph.mjs`. `npm run test:all` is exactly
`npm test`, then `npm run test:graph`, then the required-browser variant of
`npm run test:browser:required`; a machine without Chrome fails rather than reporting a skip.

`npm run screenshots` needs Chrome. It drives the real app against the scripted interview in
`tools/fixtures/walkthrough.mjs` and rewrites every image in `docs/` plus
`docs/examples/remember-names.md`, so the README shows the app rather than a drawing of it.
`.claude/skills/update-readme/SKILL.md` is the full procedure for updating the README,
including which constants have to be re-derived from source.

`tools/assemble-site.mjs` is packaging, not a build step: it copies the publishable tree for
browser checks and Pages, and leaves the probes on the repository tree.

## The seam, and why it is enforced

```
src/core/       pure: no DOM, no network, no clock, no randomness
src/runtime/    orchestration: provider and `now` are INJECTED, never reached for
src/providers/  inference and hosted speech requests, shared HTTP policy
src/store/      IndexedDB sessions + the encrypted API key
src/voice/      microphone, transcription requests, native speech and playback
src/ui/         the app shell (the only place that touches the DOM)
guide/          static public documentation, published beside the app
```

A new file under `src/` must also be added to `sw.js`'s `SHELL`; `test/wiring.test.mjs`
enforces that, and the CSP allowlist with it.

`tools/lint-purity.mjs` fails the build if anything under `src/core/` **or `src/runtime/`**
mentions `window`, `document`, `localStorage`, `sessionStorage`, `navigator`, `fetch`,
`alert` or `claude`. It runs as part of `npm test`.

This is why the whole suite runs in a couple of seconds with no mocking framework and no
network. If new code needs a platform API, it belongs in one of the other directories and
the thing that needs it takes it as an argument.

## One turn, end to end

```
seedTurn()          turn 1 is hardcoded SEED_QUESTION — no model call, cannot fail
submitAnswer()      classifies the answer (runtime does this, not the UI)
runTurn()           selectNextDimension → legalMoves → buildTurnPromptParts
                    → provider.sampleJson → parseTurnResult → questionTripwire
                    → applyCoverage + addFacts + setZeroGainStreak → askQuestion
shouldOfferWrap()   'coverage' | 'soft_ceiling' | 'hard_ceiling' | 'exhausted' | null
runSynthesis()      one call → parseSynthesisResult → setSynthesis
buildExport()       markdown, and it works even if synthesis never ran
```

`src/runtime/turn.js` is the conductor; everything above it is pure.

## Invariants that will bite you

These are load-bearing and most of them fail *silently* if broken.

- **The turn prompt must be a pure function of session state.** No timestamps, no
  randomness. An interrupted turn is recovered by re-issuing the byte-identical prompt and
  letting the provider's cache replay it. Two tests assert this
  (`test/core.test.mjs`, "leaks no timestamp").
- **`system + '\n' + prefix + '\n' + tail` is the one definition of "the prompt".** The
  same join is used by `buildTurnPrompt`, by `promptHash`, and by the artifact adapter. If
  they ever diverge the cache silently never hits and recovery quietly costs a second call.
- **The coverage ratchet is the product** (`src/core/session.js`, `applyCoverage`). Coverage
  never falls, rises at most one level per dimension per turn, at most two dimensions per
  turn, and `covered` is refused without a verbatim user quote. Loosening any of these
  makes the interview stop early and produce a useless prompt.
- **`lowConfidenceDimension` comes from the PREVIOUS turn's dimension, not the target.** The
  coverage claim judges the answer just read, so the cap belongs to the dimension *that
  answer served*. This is the single easiest thing to get backwards; there is a dedicated
  test for it.
- **Zero gain is measured after the ratchet, never on the model's claim.** A model asserting
  `covered` three turns running while the ratchet clamps every one of them is making no
  progress — that is exactly what the exhaustion exit exists to catch.
- **Bank-question turns leave `zeroGainStreak` untouched**, in both directions. Incrementing
  would let two network blips offer the exit for a reason that never happened.
- **The golden vectors are the Kotlin port's contract.** `tools/vectors.mjs` records real
  calls into the JS core as `test/vectors/*.json`, and `test/vectors.test.mjs` fails when they
  are stale. A change that alters behaviour regenerates them (`npm run vectors`) in the same
  PR, and the `android` CI job then fails until `android/` follows. Never edit a vector by
  hand or loosen a Kotlin consumer to make it pass.
- **Never trust model output.** `parseTurnResult` and `parseSynthesisResult` assume the
  model lies about shape; every field is optional. Add validation there, not at call sites.
- **Inference agreement is not a key check or microphone permission.**
  `src/store/inference-consent.js` and Android's `data/InferenceConsent.kt` own the
  non-secret destination/grant preferences. Default/legacy/read-failed records deny;
  the web grant has a consent-owned atomic storage entry so an ordinary preference write
  in another tab cannot resurrect it. Provider-kind switches (including back) and validated
  local-address changes retire the grant,
  while model-only edits keep it. Bind cached providers, retries and permission callbacks
  to their original permit. `consent` errors propagate through the runtimes rather than
  becoming bank fallback or a failed-but-finished synthesis. Gate session/capture/service
  effects, but keep local reading/export available. A failed withdrawal blocks the current
  page/process and reports that persistence failed; never claim the old record was erased.
- **`source: 'voice'` exempts an answer from the terse and dodge thresholds**, because
  dictation rambles. Chip and unedited-draft answers are capped at `partial` — those are
  Claude's words, not the user's.
- **`runTurn` bumps `rev` five or six times.** Persist the object it returns, once.
- **The answer-box draft is the exception to settled-turn persistence.** Debounce
  `setDraftAnswer`; never persist each keystroke, and clear the queued write before submit
  or skip so an old timer cannot put answered text back into the next question.
- **One session is one library idea.** `name` is the user's override, `title` is the model's
  suggestion, and display/export order is `name → title → opening`. Tags and archive state
  migrate with the session; they are not a second store.
- **Backups contain sessions and nothing else.** Never add the credential keyring or device
  preferences to `buildBackup`. An imported id collision becomes a named copy; silently
  overwriting the local row is data loss.
- **Call `navigator.share` before yielding the click gesture.** File construction and
  `canShare` checks are synchronous; adding an unrelated `await` before the call makes the
  mobile share sheet fail with `NotAllowedError`.

## The look: tokens, and two ways the theme dies quietly

`src/ui/app.css` is the design system — spacing, radius, elevation, type and colour are all
tokens, and nothing hardcodes a pixel value it could name instead.

- **Theming is `light-dark()` over an explicit `color-scheme`, and `[data-theme]` is
  load-bearing.** `tools/screenshots.mjs` produces the light and dark README pairs by setting
  `documentElement.dataset.theme` — it does **not** emulate `prefers-color-scheme`. Delete
  `:root[data-theme="dark"] { color-scheme: dark }` and both runs render identically, shipping
  light/dark PNG pairs that are the same image twice with a green suite. `test/browser/app.browser.mjs`
  now asserts the two themes compute different backgrounds; that is the only thing catching it.
- **Nothing at boot may clear `data-theme`.** The Appearance choice (`theme` in
  `src/store/prefs.js`: `system` | `light` | `dark`, the Android app's key and values) is
  applied before first paint by the classic script `src/ui/theme-boot.js`, which only ever
  *sets* an explicit light or dark. `app.js` applies the choice only when the user changes
  it. Applying the default `system` at boot would delete the attribute `screenshots.mjs`
  set in its fresh profile; `test/browser/theme.browser.mjs` catches that.
- **`color-scheme` is not decoration.** Without it the native select, the scrollbars and the
  file picker stay light inside a dark page.
- **`[hidden] { display: none !important }` stays.** Five probe checks and the screenshot
  fitter depend on it beating any author `display` written later.
- **`#output` renders markdown, so its `textContent` is no longer the export.** The source
  lives on `dataset.source`, which is what `screenshots.mjs` writes `docs/examples/` from;
  Copy, Download and Share go through `exportFor()`. Reading the export off the DOM rewrote
  the whole worked example as one unbroken line the first time, and `test/docs.test.mjs`
  caught it. `renderBlocks` in `src/core/markdown.js` returns data rather than DOM because
  `src/core/` is purity-linted — the UI builds the elements.
- **`screenshots.mjs` hardcodes `.wrap`, `details.cov`, `.idea-card`, `#output`'s height cap,
  `#library-detail .library-preview` (the 1280px `11-library-wide` shot)
  and `.wrap`'s 72px bottom padding**, plus about thirty element ids (`test/docs.test.mjs`
  checks each one still exists in `index.html`, which fails in a second where the harness
  can hang), and `library.browser.mjs` finds card buttons by their visible text. Restyle
  them freely; renaming any of them breaks the harness, sometimes by hanging rather than
  failing.
- **The wrap-up offer is the `#wrap-offer` card, not `#note`.** `nextQuestion` sets
  `state.wrapOffer` only in manual mode, on the turn `wrapAdvisory` returns text, and the
  card is cleared by answering, skipping, Keep going or a write-up; `session.wrapOffered`
  is what stops the engine offering again (#12). `screenshots.mjs` waits for the card to
  capture 05 and `test/browser/wrap-offer.browser.mjs` pins the once-only rule, including
  that hands-free still speaks the offer and never shows the card.
- **`#note` and `#err` are toasts, scoped to a screen.** `show()` clears both when the
  panel (or Start/Settings destination) actually changes, so a `say()` or `fail()` meant
  for a new screen must come *after* the `show()` that opens it, or it is wiped unread;
  `showDone()` carries a voice-stage wrap-up failure onto Done. Notes auto-hide after
  `#toasts`' `data-note-ms` (probes shorten it there) and take no pointer events; the error
  toast does, so `.toasts` is placed clear of Send (top on a phone's Interview), Pause/Exit
  (top on the voice stage) and the bottom navigation, and Done's body grows by the
  error's maximum height while one shows (never `.wrap`'s 72px). Setup reserves error
  clearance in both its focus scrollport and page bottom, including the phone navigation,
  so a keyboard retry at enlarged text cannot land under the persistent error. A failed save is an
  error, not a note, held in `state.saveFailure`: `fail(null)` shows it again on that
  idea's Interview and Done until it saves or is dismissed, so neither a turn nor the
  move to Done can drop it. `test/browser/toasts.browser.mjs` hit-tests those controls
  (Done scrolled to the end), and the real-app policy probe refuses any trusted click a
  raised toast would take.
- **Setup disclosures are real UI, not screenshot overrides.** `Model options` opens for
  local providers through `onProviderChange`; `Voice and speaking pace` opens when OpenAI
  speech is selected through `onSpeechChange`. Both live in `index.html`; the provider/key,
  spoken-replies choice, dictation, finish word and start actions stay outside them.
  Required hosted-speech keys/consent and Preview also stay outside optional disclosures.
  `screenshots.mjs` rejects setup captures that clip the start controls or footer. Fix the
  app layout when that gate fails; do not hide required controls just for a capture.
- **Settings recovery belongs to its operation.** The same form has five sections:
  Connection, Voice, Appearance, Your data and About. Credential errors persist beside
  Connection and data results beside Your data; screen toasts are not their only copy.
  `confirmAction` uses a native dialog and returns an awaited boolean, with Cancel focused.
  Confirmed Forget retires effects before storage; `secrets.js` serializes credential
  operations so an older encrypted write cannot survive a completed clear. Failed
  removal keeps an explicit retry, not the old in-memory provider. Clear fields
  before awaiting removal so a late completion cannot erase new edits. A hosted
  preview cancelled during persistence queues revocation, never successful verification.
  Teardown/navigation use the same cancellation path. `updateCredentials` merges an
  operation's owned branch against the latest durable ring inside the queue; serializing
  stale whole-ring snapshots alone still loses independent updates. Connection/Start owns
  its submitted inference and STT branches; TTS verification has an independent writer.
  A retired durable STT put must not override the next Start's explicit dictation choice.
  Library's current `confirmDelete` is synchronous: its future dialog integration must
  await the result, not treat a Promise as permission.
- **Start and Settings are two views of `#panel-setup`**, picked by `data-view` from the
  destination passed to `show()`; app.css hides `.start-only` / `.settings-only` from that
  attribute alone. Every id stays in the one section. Start's provider/fields/ready phases
  reuse those controls; `connectionReady()` follows actual fields and saved-key fallback,
  not network success. `advanceConnection` reuses the factory without saving or making
  requests. The ready summary distinguishes saved/unchecked/current checked/failed proof;
  `connectionContext`/`ownsConnection` share configuration/presentation revisions, navigation
  and credential-generation guards with Start/Continue construction. Back/Change retire
  pending Start/output as well as Check/Continue; Start identity protects a newer run's
  duplicate guard. Speech withdrawal clears busy work, not completed unchanged inference
  proof. Failed Continue focuses a visible invalid field or Provider, never a hidden input.
  A failed start reveals its fields; Settings never folds. A field hidden by the
  view still holds its value, and `readRingFromForm` must keep deciding from what the
  provider declares — `test/browser/start-settings.browser.mjs` pins it. The 01-setup capture
  must stay within 1100px.
- **The adaptive shell is laid out from `body[data-panel]`, which `show()` sets.** Below 600px
  `.bar-right` is a fixed bottom navigation bar on `setup`/`library` and a top app
  bar on `interview`/`done` (back arrow, title, Settings, plus the meter on Interview only).
  `index.html` ships `<body data-panel="setup">` so the first paint already matches. The
  room under the fixed bar is `body`'s `padding-bottom: var(--nav-bar-height)`, never
  `.wrap`'s, because `screenshots.mjs` measures and crops `.wrap`'s 72px; `html` gets the
  same `scroll-padding-bottom` so keyboard focus never scrolls a control under the bar.
  From 600px the same controls become a 96px labelled rail, expanding to 208px at 1200px.
  Rail/safe-area clearance belongs to `body`'s inline padding and disappears in voice mode;
  errors stay on the work side. `renderShell()` uses `sessionDisplayTitle` and `textContent`,
  preserving the full contextual name even when it ellipsizes. `shell.browser.mjs` measures
  these boundaries and enlarged labels, while its compact checks retain fixed-bar/focus
  clearance and the lifecycle probe rejects a retired idea's title.
- **`#answer` lives inside the docked `#composer`.** On phones the composer is sticky and
  lifted by `--keyboard-inset` (`trackComposer` in `src/ui/app.js`, from `visualViewport`);
  `--composer-block` feeds `scroll-padding-bottom`. Docking only `.interview-actions` again
  lets the bar sit over the answer box once a thread makes the page scroll —
  `test/browser/thread.browser.mjs` fails on exactly that. `renderThread` builds every
  question and answer with `textContent`; both are someone else's words.
- **The font stack must keep `"IBM Plex Sans"` at 600.** `screenshots.mjs` checks for it and
  warns forever otherwise, and a run that warns about fonts is a run to throw away.

## Voice: probe by behaviour, never by feature detection

Inside an installed iOS home-screen app, `webkitSpeechRecognition` **exists, constructs,
and `start()` returns cleanly — then no event ever fires.** The platform that most needs
voice is the one where feature detection lies. Edge throws `network`; Firefox ships it off.

`probeWebSpeech` (`src/voice/webspeech.js`) starts the engine and requires proof of life
within 1.5s, caching the verdict per origin. Anything else falls through to
record-and-transcribe permanently. Do not "simplify" this into an `in window` check.

**A permission decision is not an engine verdict, and `rec.start()` is what raises the
prompt.** So on a first run the probe is timing a person finding Allow, not an engine — and
1.5s cached `dead` for the origin for ever, which is how Android Chrome lost dictation
permanently on the very first interview. While a prompt is pending the budget is
`PROMPT_MS`, and a `dead` reached that way, or from `not-allowed`, is reported but never
remembered. Only a verdict that blames the *engine* is cacheable. `VERDICT_VERSION` exists
to throw away the values written before that distinction, and a blocked microphone is
reported as blocked rather than as the installed-iPhone bug it looks identical to.

**On Android, `isFinal` does not mean settled, and a new index does not mean new words.**
With `continuous` on, Chrome there reports every in-progress guess as a final result at its
own index, usually with confidence 0 (`SpeechRecognitionImpl.java` says as much). Joining
every final put "I I want I want to … I want to create a game like Tetris" into a driver's
answer, and the replayed-index guard could not see it because every index was new.
`assembleTranscript` (`src/voice/webspeech.js`, pure, tested in `test/voice.test.mjs`) folds
the capture instead, and a result is only ever replaced by a *later* one. **A draft is a
final with no confidence from a session that has never sent an interim.** Chromium on
Android turns every partial into a final, so its sessions never send one, while desktop
Chrome, Edge and Safari do. `listenViaWebSpeech` has to remember that per session
(`interims` on each result), because once the last interim is finalised the list no longer
shows it. Confidence alone is not enough: an engine that reports 0 for ordinary finals
would otherwise lose "we need a website" to "we need a logo".
- A result that repeats the newest kept result(s) word for word **absorbs** them.
- A draft that arrives changing a word or two of the draft before it, in the same recogniser
  session, **revises** it ("skip the" → "skip this", "…like Tet risk" → "…like Tetris", "I" →
  "I'm"), provided its opening still stands. A revision never crosses a restart, and it does
  not happen when the draft got there by absorbing a sentence begun again: drafts that start
  again from "it" and grow into "it should be cheap" are a new sentence after "it should be
  fast".
- A confirmed final **sweeps** away its session's trailing drafts, even where it respells
  them ("one hundred and twenty" → "120", "I am done" → "I'm done"). It sweeps words that a
  draft carried in from an earlier session only if the final opens with them too.

Words inside one result are never touched. **Nothing is dropped for being shorter**: a
prefix of the last result is also what "over" looks like said alone after "over the years
we grew", and discarding it strands driving mode. The carry across restarts is a list of
results, not a string, so a replayed phrase at the start of a new session is recognisable.
Confidence never removes a word on its own. Desktop sessions send interims, so none of
their finals is ever a draft; an engine that also reports confidence 0 only has its trigger
words wait out `settleMs` (see `settled` below).

`src/voice/vad.js` (silence detection) is pure and clock-injected specifically so it can be
tested without a microphone. Its noise floor is a running minimum, which works because
speech has gaps at word boundaries — that is what lets a recording opening mid-sentence
correct itself rather than staying deaf.

Use the `change-voice-and-driving` skill for the complete change and validation procedure.

## Voice presentation and spoken output

- **The voice stage is presentation, not another route or interview.**
  `createVoiceStage` in `src/ui/voice-stage.js` renders the in-place `#voice-stage`;
  `src/ui/app.js` owns visibility and hides the manual composer, coverage and navigation.
  The renderer has no timers. Its bars move only from microphone samples supplied by
  `src/voice/index.js`; missing levels are reported, not fabricated. A native speaking
  indicator is not an output waveform.
- **Pause stops effects, not history.** `pauseVoice` releases microphone tracks, cancels
  speech and preserves captured draft text. Pending transcription may finish for that
  draft; an already-submitted model request may settle for its originating idea. Neither
  may start another capture while paused. Resume needs a deliberate tap, not a hidden
  listener. Exit returns to the manual interview, or the document view when writing/done,
  without skipping or requesting a wrap-up; cancelled unfinished audio is reported.
- **A busy flag is not an identity guard.** `sessionWork`, `currentView` and `voiceEpoch`
  in `src/ui/app.js` separate session work, navigation and retired voice runs. Delayed
  effects must not write another idea's draft or restart a cancelled conversation.
- **Native speech remains the default.** `src/voice/speak.js` resolves current voices,
  applies the selected identifier and pace, and reports a missing selection or failed
  utterance. The browser's local/network label is not a quality ranking or an offline
  guarantee. Keep full captions; do not require word-boundary events.
- **Hosted speech needs its own permission and secret.** `withTts` in
  `src/store/secrets.js` keeps its key, voice and successful-check status independent of
  inference and STT. `src/store/prefs.js` owns non-secret browser voice and pace. Preserve
  speech records through keyring normalisation and include them in Forget, never backups.
- **A fallback cannot pass the hosted check.** `previewSpeech` in `src/ui/app.js` requires
  a separate key and consent to synthetic speech, text transfer and metered usage.
  `createSpeechOutput.check` in `src/voice/output.js` must complete on the selected backend
  before `readSpeechFromForm` permits hosted replies. Key or hosted-voice changes require
  a new check. A live failure can visibly fall back to browser speech; cancellation cannot.
- **Prime output before yielding the gesture.** Start, Resume and Preview prime speech
  synchronously. `src/voice/playback.js` owns the bounded Web Audio context and buffered
  audio lifetime; `src/providers/tts.js` owns the fixed endpoint and bounded requests.
  No streaming claim, persistent audio cache, background wake word or voice barge-in.

The optional hosted path has no developer-verified live CORS or voice-quality result for
this redesign: no authorised key or spending cap was supplied. Its preview gate tests a
particular browser attempt, not general compatibility. Physical Android/iPhone checks
remain unperformed. The separate real-audio policy checks in
`tools/browser-policy-check.mjs` supplement scripted media tests; they do not prove native
recognition contention, real permission UI or physical-device behaviour.

## Driving mode: the rules that fail silently

The hands-free loop lives in `src/runtime/drive.js`, with the matching in
`src/core/driving.js`. Both are platform-free and lint-enforced to stay that way, because
the loop's correctness is about *sequencing* and sequencing tested through a browser is slow
enough that the eighth failure case never gets written.

- **Speaking, listening and processing are serial until an explicit stop.**
  Transient misses use bounded retry/repeat/skip recovery without unnecessary taps.
  Pause, Exit, cancellation or a fatal input/output failure stands down observably;
  no retired run may start another effect. `createDriveLoop.stop()` invalidates progression,
  while the UI owns resource shutdown. Resume constructs a new run after a tap.
- **The trigger word is terminal-only, and a command must be the whole utterance.** "Over"
  is an ordinary word — *over budget*, *over the years* — but almost never the last one.
  Matched anywhere it truncates answers invisibly. A command matched *inside* a sentence eats
  the whole answer, so `"I'd skip this one if I could"` has to stay an answer.
- **`parseSpeech` returns empty text for a command**, which is what makes it structurally
  impossible for one to reach `submitAnswer` — where `RE_REFUSAL` in `engine.js` already
  matches "skip this" and would cap that dimension's coverage. The command would appear to
  work and quietly damage the interview.
- **`settleMs` is doing two jobs.** A trigger seen in an interim only arms a timer: it lets
  "we went over budget" extend itself and prove it was not the end, *and* it gives the engine
  time to finalise, because `stop()` resolves with settled finals only and would otherwise
  discard the clause the trigger appeared in. The two probe cases either side of it in
  `test/browser/dictation.browser.mjs` are the sharpest tests in the suite. **A final is
  only "settled" when the engine stands behind it** (`confidence > 0`, no interim pending):
  an Android draft is final with no confidence, and judging it as settled ended "we went
  over budget" at "we went over" and took the first draft of "repeat customers…" as the
  command `repeat`.
- **The question and its suggestions are two utterances, deliberately.** `spokenExamples`
  in `src/core/driving.js` builds the second one and trims to a word budget rather than a chip
  count. Joining them into one string is the obvious simplification and reintroduces the bug
  below: four fourteen-word chips plus a question clears the watchdog on its own, and the
  whole turn goes silent with no error.
- **`speak.js` caps its own wait.** `splitSpeechText` builds on `speechChunks` and enforces
  character boundaries even on long unbroken text; native output also sizes chunks for
  the selected rate. Never bypass that path with a whole prompt in one utterance.
- **The deaf watchdog in `webspeech.js` resolves, never rejects.** An engine that emits one
  interim and then goes silent used to leave the promise unsettled for ever. It is the same
  installed-iOS failure `probeWebSpeech` guards the *start* against, arriving later than the
  probe can see. Any recogniser event is proof of life for it. It cannot also bound the
  wait for a *first* word: Android's deaf engine starts, reports `no-speech` and ends every
  few seconds, so it is never silent and every restart is a legitimate event. That bound is
  `firstWordMs` in `listenViaWebSpeech`, hands-free only, taken from the gate's
  `noSpeechMs` and **not reset by restarts**; when it expires with no word the capture ends
  as a miss. Press-to-talk passes none, because it promises unlimited thinking time before
  the first word. After a word, a pause never ends a hands-free answer — only the trigger
  (or the watchdog on a truly silent engine) does, since ending there would submit half an
  answer without its trigger.
- **On Android the level meter can starve recognition, silently.** A page-held
  `getUserMedia` track moves the bars while the recogniser hears nothing and raises no
  `audio-capture`. `meterStarvesRecognition` (`src/voice/vad.js`) judges it by behaviour,
  at two points: mid-capture after `STARVED_SPEECH_MS` of metered speech with no word, and
  at the end of a wordless capture after `STARVED_AT_END_MS`. The second exists because
  `firstWordMs` can end the capture before the first threshold is reached, and that race
  must not leave the meter on. Either verdict releases the meter for the rest of the page's
  life (not persisted; a new controller on Resume or press-to-talk reuses it). **What was
  said while starved is gone**, so hands-free resolves `''` — a miss through the drive
  loop's recovery ladder, then the whole answer again — instead of retrying into the second
  half of a sentence; only press-to-talk retries, once, without the meter. A hands-free
  false positive costs the bars for the page and one spoken miss. The fake's `starveWhile`
  in `test/browser/fixtures/fake-voice.js` drops starved words for the same reason and is
  the only reproduction; no physical Android run has confirmed the mechanism.
- **`DRIVING_GATE` numbers are first guesses against an imagined car.** The tests assert the
  *direction* of each change from `DEFAULTS`, never the value. Only a real drive settles them.

## Providers: browser CORS facts, verified against the live APIs

- **Anthropic**: the preflight only succeeds if `anthropic-dangerous-direct-browser-access`
  appears among the *requested header names*. Omit it and you get a 400 with no CORS
  headers, so the real error is invisible. Never set `credentials: 'include'`.
- **OpenAI**: sends **no CORS headers on an invalid-key 401**, so a bad key reaches the page
  as an opaque `TypeError`. The adapter reports that as a probable key problem rather than
  "you're offline", and validates keys against `GET /models`, which does answer with CORS.
- **Local models (Ollama, LM Studio)**: two independent gates that fail identically, so
  diagnose both. *CORS* is the server's: Ollama allows `localhost` and `127.0.0.1` on any
  port by default and answers any other origin with no CORS headers at all, so a hosted
  page needs `OLLAMA_ORIGINS` set **and the server restarted** — it reads that at startup.
  *Local Network Access* is the browser's: since Chrome 142 a request from a public page to
  a loopback address is gated on a user permission prompt. The older Private Network Access
  design, where the server answered a preflight with `Access-Control-Allow-Private-Network`,
  was abandoned — nothing needs that header now. `targetAddressSpace: 'local'` declares the
  intent and is also what exempts the request from mixed-content blocking, the only way an
  `https://` page may reach `http://localhost` at all. Safari implements none of it.
- **CSP has no IPv6-literal form.** `http://[::1]:*` in `connect-src` is dropped silently —
  no error, no violation event, a directive that permits less than it reads as. Verified by
  probe in `test/browser/app.browser.mjs`. Loopback is wildcarded as `http://localhost:*`
  and `http://127.0.0.1:*`, and the settings screen steers anyone typing the bracket form.
- **A question-model override must never reach the `complex` tier.** `resolveTiers`
  (`src/providers/index.js`) folds the user's two model choices into a preset's tier map,
  and prefers the preset's strong tier over the question model on purpose. Invert those two
  and every wrap-up quietly runs on whatever cheap model was picked for the dozen turns — a
  worse export, with nothing anywhere reporting that anything happened. `createProvider`
  also stops forwarding the `model` scalar once it has resolved tiers, because the adapters
  read `model || tiers[modelTier]` and would otherwise hand the override straight back.
- **A hidden settings field still holds a value, and `readRingFromForm` still reads it.**
  Two shipped bugs came from that single fact. `#baseurl` is filled with the preset's own
  address for every OpenAI-compatible provider, hosted included, so reading it back sent
  `https://api.openai.com/v1` to `assertLoopback` and made OpenAI, Groq and OpenRouter
  unusable — an error about an address the user never typed and could not see. The form now
  reads each field only when the chosen provider declares it (`local`, `picksModel`).
- **An own property holding `undefined` still wins a spread.** `buildProvider` sends
  `modelRequired: requireModel ? undefined : false`, which overwrote the `true` that used to
  sit ahead of `...config` in `createProvider` — so "Another local server" with an empty
  model box built happily and POSTed a body with no `model` key. It is `?? true` after the
  spread now, which still honours the deliberate `false` the model-list read passes.
- **Auth is a descriptor, not a branch.** `src/providers/http.js` owns `applyAuth`,
  `isLoopback` and one `httpError`; a preset says how it signs requests. It also owns the
  only definition of "local" — there were three, and two of them called
  `localhost.evil.com` local because they matched a prefix instead of parsing a URL.
- **The output-budget parameter is per-vendor.** Every GPT-5-era model rejects `max_tokens`
  outright and wants `max_completion_tokens`; Groq, Ollama and LM Studio only know the old
  name. Renaming it is half the fix — a reasoning model can spend the whole budget before
  writing a character and come back empty with `finish_reason: 'length'`.
- **GitHub Models is retired** (410 since 2026-07-30) and the **Copilot chat endpoint is not
  a general-purpose inference API** — its only sanctioned route was sunset in Nov 2025 and
  GitHub's terms name proxy usage as grounds for disabling Copilot access. Groq's free tier
  fills that slot. The reasoning is recorded in `src/providers/index.js`; don't re-add them.

Use the `add-provider` skill when changing an inference or transcription provider.

## Android invariants

`android/` is the native app: `:core` (Kotlin port of `src/core` + `src/runtime`),
`:providers` (port of `src/providers`, on OkHttp) and `:app` (Compose). Run
`./gradlew check :app:assembleDebug` from `android/`; the `android-verify` skill is the full
ladder, including the emulator smoke run. Most of these rules fail silently too.

- **Native chrome must not recreate the interview.** `AppShell.kt` adapts one NavHost
  from installed window classes, never by minting a session on resize. Its Row applies
  and consumes system/cutout Scaffold padding once for every child; rail/contextual
  bars add no system insets, and editing/voice own only remaining IME. An attached
  drive hides chrome only for its matching visible interview session. Keep the
  entry-scoped ViewModels, library pane navigator and new-id/Ask-me-more distinction.

- **The vectors are the contract, and the JavaScript is the reference.** Behaviour changes
  land in JS, `npm run vectors` regenerates `test/vectors/` in the same PR, and the Kotlin
  replays (`Consumers.kt`, with `VectorCoverageTest` failing on an unconsumed family) go red
  until the port follows. Never hand-edit a vector, loosen a consumer's comparison, or move a
  function back to `pending` to get green: #76 did that by accident and the markdown vectors
  were recorded and never replayed until #85. Porting a function moves it from `pending` to
  `ported` in the same PR as the Kotlin.
- **`:core` is pure, guarded twice.** It is a plain JVM module (nothing Android is on the
  classpath, which `ModuleBoundaryTest` asserts) and `CorePurityTest` bans clocks,
  randomness, `UUID`, files, `java.net`, `okhttp` and `Dispatchers.IO` from its sources. Wall
  time and ids come from `:app`'s `Clock` and `IdGen` in `AppGraph`, injected as arguments.
  The byte-identical turn prompt depends on it, exactly as on the web. `:providers` may use
  `:core`, never the reverse.
- **The network policy is code, not `network_security_config.xml`.** Cleartext is allowed
  there because a LAN model server speaks plain http; widening it is not the gate.
  `NetworkPolicy.Hosted` is a preset's fixed `https` origin on a `MODERN_TLS`-only client,
  and nothing the user types reaches it. `NetworkPolicy.Local` is a private network
  (loopback, RFC 1918, CGNAT, link-local, ULA, `localhost`/`*.localhost`/`*.local`), never
  carries a key, and uses no proxy. It is enforced by `LocalNetworkDns`, which refuses the
  whole lookup if any resolved address is public, so the check and the connection use the
  same address list and DNS rebinding has nothing to race; IP literals skip `Dns`, so
  `LocalNetworkPolicy.check` validates them and refuses odd numeric spellings (`127.1`).
  Redirects are never followed: OkHttp keeps `x-api-key` across hosts, and a redirect to a
  public literal would bypass the Dns guard. This deliberately differs from the web's
  loopback-only rule (`assertLoopback`), whose reason is the CSP.
- **The keyring is Keystore-encrypted, two-slot, and never in a backup.** `KeyringStore`
  seals the web's v2 keyring JSON with AES-256-GCM under a non-exportable Android Keystore key
  (no user authentication, so it works mid-drive) and writes `[format][slot][IV][ct]` to
  `noBackupFilesDir`, outside the sessions directory. The header names the slot; a
  permanently invalidated key is replaced under the *other* slot, and the old key is not
  deleted until the new blob is durable. An unreadable blob reads as empty with status
  `UNREADABLE` and is kept, not deleted, because a Keystore hiccup looks identical to a lost
  key. `allowBackup="false"` and `data_extraction_rules.xml` (enforced by `BackupRulesTest`)
  keep it, and everything else, out of Android backup; `buildBackup` never sees it.
- **`SessionStore` never loses or clobbers a row.** One `<id>.json` per session, written by
  `writeAtomically` (temp file, `fsync`, atomic replace, then `fsync` of the directory, not
  androidx `AtomicFile`, which swallows a failed rename). It saves exactly the object it is
  given, as `runTurn`'s caller expects. A file `migrate` rejects (newer schema, corrupt) is
  skipped, never deleted and never overwritten, and `save` refuses its id; deleted ids are
  remembered for the process, so an in-flight turn cannot write a deleted idea back; imports
  go through `insert`, which refuses any id held, skipped, deleted or already on disk.
- **The scripted provider is debug-only.** `adb shell am start … --es provider scripted`
  swaps in `ScriptedProvider`, which replays the `walkthrough` vector that the build copies
  into the *debug* assets. The release source set's `DebugProviders` returns null and
  packages neither the script nor the provider. It replaces only the interview model; it
  does not skip onboarding.
- **New idea is an explicit mode choice, not a microphone request.** `NewIdeaRoute` opens
  before an id is allocated; Voice/Type selection alone creates a fresh Interview entry,
  guarded by the originating entry and admission revision. The new-entry voice flag is
  consumed once in entry/UI saved state and uses the visible Interview's existing
  permission/controller path. Stop/disposal retires pending setup and explains the typed
  fallback; a stale Continue/result cannot borrow another request. Active drive work
  stays in the app graph. Onboarding's per-provider drafts live only in its ViewModel,
  not another keyring or backup; saved details are not a current health check.
- **The hands-free drive lives in the app graph, not the screen, and the service only keeps
  it hearing.** `DriveController` (`voice/`) runs `DriveLoop` on the main thread and writes
  turns through `InterviewWork`, the same path as a typed answer. `DriveService` is a
  `microphone` foreground service: with the screen off and no such service, recognition goes
  deaf with no error (#89). It is started only by `start`/`resume`, which are taps on the
  visible app, is `START_NOT_STICKY`, and stops when the controller has no run. Its
  notification actions call the same `pause`/`resume`/`exit` as the stage. The `epoch` is the
  identity guard: Pause cancels the run's job (and with it the capture and speech) but never
  the turn work in the app scope, and Resume reads the idea back under its lock. The pause
  draft is the recogniser's caption read *before* the cancel, because on-device recognition
  discards the utterance on stop, and it is written only while its question is still open.
  The stage's bars come from `Recognizer.samples` (numbered, so repeated -2.0 readings are
  still samples); no reading means "no level", never an animation.
- **Hosted speech on Android is gated exactly as on the web, and the gate is read per
  speak.** `HostedSpeaker` (`voice/HostedSpeech.kt`) uses OpenAI only while
  `hostedSpeechReady` holds: `tts.kind` is `openai`, `tts.verified` (written only by a passing
  Settings check, through `withTts`) and `hostedSpeechAllowed`. The check runs on the hosted
  backend alone, so a fallback can never pass it; a key or voice change clears `verified` at
  once. A live failure falls back to the native `Speaker` with `SpeakResult.fallback` set,
  which the voice stage shows; cancellation and lost focus never fall back. The gate is
  re-read before every chunk. Forget calls `KeyringStore.revoke()` before anything suspends,
  and a Settings check or transcription save writes only through `saveIf` with the
  generation it started under, so Forget mid-check cannot put a deleted key back.
- **`WhisperMicrophone.kt` is the only file allowed an `AudioRecord`** (`RecognizerAudioTest`
  names it). The Whisper fallback runs only when `isRecognitionAvailable` is false or a
  capture fails `LanguageUnavailable`, after the recogniser is released, and only with
  `hostedTranscriptionAllowed` and the keyring's own `stt` key, re-checked before every upload.
  `recognizerUnavailable` resets on every drive, and Pause/Exit keep Whisper's transcribed
  segments as the draft. Forget clears both consents.
- **Windows test hosts have two known quirks; do not loosen a test around them.**
  Defender and the indexer briefly lock fresh files, so `writeAtomicallyWith` retries a
  transient rename failure on Windows only (six attempts, backed off; one attempt
  elsewhere), and skips the directory `fsync` there because a Windows JVM cannot open a
  directory as a channel. FileProvider finds no root under Robolectric on Windows, so
  `ResultActions.contentUri` is a seam `ResultScreenTest` swaps only after a real-provider
  probe fails, and only on Windows.
- **The release keystore never enters the repository, and the signer is pinned.**
  `app/build.gradle.kts` signs `release` from `ANDROID_KEYSTORE_PATH`,
  `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD`, else from the
  gitignored `android/keystore.properties`; with neither the APK is unsigned and the CI gate
  never needs a configured key, and a half-filled source is an error rather than a quiet unsigned build.
  `*.jks`, `*.keystore` and `keystore.properties` are ignored and `test/wiring.test.mjs` pins
  that none is published. `release.yml`'s read-only `android-apk` job alone references
  app-signing secrets; `android-aab` receives only separate upload-key secrets. Both stay
  there only because they are environment secrets on
  the `release` environment, whose deployments are restricted to `v*.*.*` tags (repository
  settings, not workflow text). It fails (never skips) when one is missing, and refuses the APK unless
  `tools/apk-cert-pin.mjs` finds one app-signing certificate across all reported SDK ranges
  equal to the SHA-256 pinned in
  `SECURITY.md`; while that reads `PENDING`, every release fails there by design. Never
  loosen the pin to get a release out: an APK signed by another key cannot update existing
  installs. `verifyReleaseApk` (finalising `assembleRelease`) and `verifyReleaseBundle`
  (finalising `bundleRelease`) use `VerifyReleasePackage` to read the shipped containers and
  R8 mapping for the walkthrough script and every class declared only under `src/debug`.
  The bundle uses the same Gradle signing inputs/versionCode, but its job maps upload
  secrets into those inputs. `check-android-bundle.sh` validates pinned bundletool bytes,
  the actual upload signature and a certificate different from the app-signing pin.
  Required CI generates only disposable signing keys; production secrets never enter it. R8
  needs no keep rules today (the libraries ship their own); add one only for a crash
  reproduced in a signed release build, with its reason.

## Tests

`node:test` + `node:assert/strict`, flat tests, no `describe`. Every test runs with **no
network and no real timers** — HTTP via an injected `fetch`, time via injected values.

Prefer a test that would have caught a real bug over one restating the implementation.
Several tests exist because the behaviour was wrong the first time, and the comments say so.

## Things not to change

- **No dependencies and no build step** for the web app. This is load-bearing, not
  minimalism: it is what makes the strict CSP affordable and keeps a supply chain out of an
  app that holds the user's API key in browser memory. `android/` is the one exception, and
  only because it never reaches the browser: nothing under it may be imported by, copied
  into, or served beside the web app.
- **No hash routing in the UI.** WebKit re-prompts for the microphone on hash change in a
  standalone home-screen app; panels are toggled with `hidden` instead.
- **The public guide is static and outside the app shell.** It lives under `guide/`, has no
  runtime dependency or build step, and is published beside the app. The root-scoped
  service worker must bypass `/guide/`; a failed documentation navigation must never fall
  through to cached `index.html`.
- **The version lives in two places** — `package.json` and `src/version.js` — because a
  browser ES module can't import JSON without an import attribute and there is no build
  step. A test asserts they match, and `release.yml`'s read-only `verify` job also checks the
  tag, `main` ancestry and `npm run test:all` before the write-enabled release job can
  publish.
- **`ci` is the one required status check**, an aggregating gate job in
  `.github/workflows/ci.yml`. Never add `paths:` filters to that workflow: a required check
  that gets skipped never reports, and every PR then hangs at "Expected" forever. Pages
  deployment keys off that `CI` workflow run and refuses a stale `main` tip for the same
  reason.
- Line endings are forced to LF by `.gitattributes`. `lint-purity.mjs` splits on `/\r?\n/`
  because `.` does not match `\r` — a CRLF checkout otherwise made it report every banned
  word appearing in a comment.
