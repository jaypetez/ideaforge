# Architecture

Where a change goes, and what it will cost you.

This does not restate the rules that fail silently when broken — those live in
[CLAUDE.md](../CLAUDE.md) and are linked rather than copied, because a rule written down
twice is a rule that will disagree with itself. [AGENTS.md](../AGENTS.md) covers how to
prove a change works. This file is the map in between.

Four rules keep it from rotting, and they are worth honouring in any edit:

1. **No number source owns.** No line counts, no test counts, no ceilings. "`app.js` owns
   the active session" is a useful claim; "`app.js` is 625 lines" is not.
2. **Cite `file.js` and a symbol name, never a line number.** Line numbers are the
   fastest-rotting citation there is.
3. **No invariant `CLAUDE.md` owns.** Link to it.
4. **The costs section names triggers, not plans.** A plan needs editing whenever
   priorities move; a trigger does not.

`test/docs.test.mjs` asserts every source path named below actually exists. That is the
only mechanical check on this file, and deliberately so — a doc test that polices wording
becomes a tax on every PR and gets deleted.

## The shape in one screen

```
src/core/       pure interview logic — no DOM, no network, no clock, no randomness
src/runtime/    the turn loop — provider and clock are injected
src/providers/  inference and hosted speech requests, shared HTTP policy
src/store/      IndexedDB sessions, backup import and the encrypted keyring
src/voice/      microphone, transcription requests, native speech and audio playback
src/ui/         the app shell and browser adapters — the only place that touches the DOM
guide/          dependency-free public documentation published beside the app
```

Dependencies run one way: `ui → runtime → core`, and `ui → providers | store | voice`.
Nothing under `core/` imports anything outside `core/`. That direction is not an agreement,
it is enforced — `tools/lint-purity.mjs` fails the build if `src/core/` or `src/runtime/`
so much as names a platform global, and it runs before the tests do. Its `BANNED` array is
the authoritative copy of that list; the prose copies in `CLAUDE.md` and `CONTRIBUTING.md`
are summaries.

Three things are deliberately absent, and each has a reason that is easy to undo by
accident:

- **No dependencies and no lockfile.** This is what makes the strict CSP affordable and
  keeps a supply chain out of an app holding the user's API key. `.github/workflows/ci.yml`
  has no install step for the same reason: the npm registry is not in this pipeline's
  trust boundary.
- **No build step.** The tree that ships is the tree in git. `assembleSite` in
  `tools/assemble-site.mjs` only copies the publishable files into a directory for browser
  tests and Pages; it does not compile, bundle or rewrite anything. That is what lets
  `.github/workflows/release.yml` produce a byte-stable archive anyone can reproduce.
  - **No documentation build.** `guide/` is committed semantic HTML and CSS. The Pages
    artifact places it at `/guide/` and publishes the generated app screenshots it references;
    there is no Markdown renderer, template engine or runtime CDN.
- **No `CHANGELOG.md`.** Release notes are generated from PR labels via
  `.github/release.yml`, so an unlabelled PR lands under "Everything else" for good.

That split is deliberate in the delivery pipeline too. `serve` in `tools/browser-check.mjs`
mounts the assembled site as the app under test and serves `test/browser/` from the
repository root, so probes stay test-only while the browser sees the same tree Pages will
publish.

The publishing workflows defend against shipping the wrong tree. `.github/workflows/pages.yml`
only runs after a successful `CI` workflow run for a push to `main`, then re-checks that the
tested SHA is still the exact tip of `main` before deploying. `.github/workflows/release.yml`
puts a read-only `verify` job — version triplet, `main` ancestry, a confirmed privacy contact
through `tools/check-privacy-policy.mjs`, full `npm run test:all` ladder — in front of the
write-enabled release job. Its read-only `android-apk` job alone receives the app-signing
secrets; it signs the APK and fails unless `tools/apk-cert-pin.mjs` finds
one app-signing certificate across every signer report and SDK range matching the pin in `SECURITY.md`, so the release job only
ever handles finished, verified packages. The separate `android-aab` job receives only the
upload-key secrets and builds the bundle and its R8 mapping. `tools/check-android-bundle.sh`
checks pinned bundletool bytes, bundle structure, actual signatures and a certificate
different from the app-signing pin. `VerifyReleasePackage` in
`android/app/build.gradle.kts` checks both shipped containers for debug-only code/fixtures;
`tools/test-android-packages.sh` proves the two signing roles and shared versionCode with
disposable keys in required Android CI. `PlayRequirementsTest` reads the effective debug
and release merged manifests to enforce the API 36 target floor without replacing the
release debug-permission guard. `tools/test-android-apk-alignment.py` uses SDK `zipalign` on
the actual debug and disposable signed release APKs, with a copied, deliberately misaligned
native ZIP entry proving rejection. That is ZIP packaging evidence, not ELF or physical
16 KB-device certification. Publication receives neither key and checksums every attached
payload.

The Pages artifact has two static surfaces: the app at its root and the public guide under
`guide/`. The root-scoped service worker in `sw.js` deliberately ignores guide requests,
including another site prefix on the same origin before its own worker is installed.
Without that boundary, a failed guide navigation could fall through to cached `index.html`
and render the app where documentation was requested.

For app assets, `sw.js` retains background network/cache refresh in the fetch event's
lifetime and clones a response before its consumer can use the body. `test/sw.test.mjs`
drives delayed cache storage, reported write failures and offline fallbacks; the browser
suite covers the actual assembled shell.

The app's Focus Studio foundation lives in `src/ui/app.css`; `guide/assets/guide.css` still
has the earlier palette until the separate guide migration (#205). The guide uses system
fonts and no remote assets. Its HTML stays script-free. `test/docs.test.mjs` checks page and
screenshot inventories; `test/browser/guide.browser.mjs` checks the assembled pages, narrow
layout, image loading and subpath links. Screenshots are generated by `tools/screenshots.mjs`,
not hand-authored replacements for the app.

The app's optional Google Fonts stylesheet is preloaded without blocking scripts or the
window load event. The classic `src/ui/theme-boot.js` installs its activation listener
before that link is parsed and waits for both the preload and page load before activation,
so an early font response cannot acquire a load-blocking CSS dependency.
App readiness does not imply font readiness: the screenshot
harness separately waits for stylesheet activation and the real IBM Plex faces.

## What owns the session

The active interview record is `state.session` in `src/ui/app.js`. Voice presentation and
in-flight effects are separate UI state, not additions to the session format.

Every legal mutation is a named reducer in `src/core/session.js`. They are pure, they take
the session and return a **new** one, and they bump `rev`. Nothing outside that file
mutates a session, which is what makes the whole interview engine testable with no browser.

Three consequences worth knowing before you touch any of it:

- **Persist once per settled turn.** `runTurn` in `src/runtime/turn.js` bumps `rev` five or
  six times internally as it applies coverage, adds facts and asks the question. Saving
  each intermediate object is wasted writes and a torn session if one fails. `persist()` in
  `src/ui/app.js` is called once on the settled result through `sessionWork`. The answer box is a
  separate user-editing path: it debounces `setDraftAnswer` so unfinished prose survives a
  reload without turning every keystroke into an IndexedDB write.
- **`rev` is a mutation counter, not a mechanism.** It exists so accidental in-place
  mutation is detectable and so a future reconciliation has something to compare. Nothing
  reads it: `saveSession` in `src/store/sessions.js` does an unconditional put, and two
  tabs on one session are last-write-wins with no merge.
- **Schema migration happens on read, not on upgrade.** `migrate` in `src/core/session.js`
  is applied by `src/store/sessions.js` as rows come back, and it **refuses** a session
  whose `schema` is newer than this build rather than guessing at it. The credential
  keyring in `src/store/secrets.js` follows the same pattern for the same reason.

The library is still made of sessions rather than a second database model. One interview is
one idea; `name`, `tags` and `archivedAt` are ordinary versioned session fields.
`src/core/library.js` owns search/filter semantics, while `src/ui/library.js` owns the DOM.
At 1024px and wider `src/ui/library.js` also fills the list/detail pane (`#library-detail`):
the selection lives there, the preview is drawn by the same `renderMarkdown` in `app.js` that
draws Done's `#output`, and the pane stays empty below that width, with no card marked
selected. Each card still carries a Details button there (hidden by CSS, `aria-pressed`
false), so narrow screens have no second copy of any action but not a byte-identical DOM.

`src/core/backup.js` is the portability boundary. It serializes sessions only, never the
encrypted keyring or device preferences, and validates every imported row through `migrate`.
An id collision is kept as a visibly named copy rather than overwriting local work.
The Android app reads and writes the same file. Two checked-in fixtures pin both
directions: `test/fixtures/backup-from-web.json` (written by `tools/backup-fixtures.mjs`)
is imported by the Android tests, and `test/fixtures/backup-from-android.json` (written by
the Android export) is imported by `test/backup.test.mjs`.

`contentReports` is optional, additive session metadata, owned by `addContentReport`,
`mergeContentReports`, `replaceReportedQuestion` and `reportableOutput` in
`src/core/session.js`, with matching reducers in Android's `core/Session.kt`.
Migration validates the report records rather than trusting imported shape; no-report
exports keep their canonical bytes. A report holds an id, time, category, optional note
and an immutable generated-output snapshot, never a whole session or credential record.
`buildExport` appends a clearly local report section only when reports exist; backup
fixtures and golden vectors pin both directions. Prompt builders read no report metadata.

Android's `ui/reporting/ContentReportController.kt` binds the displayed output and origin
idea before queuing a local read/reduce/save under `SessionWorker`. Typed drafts and
`InterviewWork` preserve durable reports when their snapshot is older. Deletion still
retires queued work and cannot resurrect an idea. Replacement edits the current open turn
in place with a distinct same-dimension checklist question; it never re-enters `runTurn`
or reapplies coverage. `ContentReportDialog.kt` is wired to current questions, suggestions,
history, Ideas previews, Result and the real voice stage. Opening it during voice pauses
the existing controller; a saved replacement refreshes only a paused/blocked stage.
All report surfaces pause a matching app-graph drive before editing or saving, including
one that survived Back to Ideas or Result. A different idea's drive is not paused.
There is no automatic report transport. An optional, explicit email-composer handoff
uses the canonical privacy policy's confirmed contact, bound by a docs check, without
claiming receipt. Android-only UI is the agreed native scope; web imports/export retain
the shared data, not new web reporting controls.

`sessionWork` owns model work by session id rather than whichever idea happens to be open
when a promise resolves. `currentView` also checks the navigation generation before a
continuation updates presentation. Pausing voice can let a submitted request settle for
its original idea without starting another capture; changing voice presentation does not
replace the session reducer or persistence rules.

## Inference authorisation

`src/store/inference-consent.js` owns the device-local grant, using the strict `readPrefs`
result rather than ordinary preference defaults. `inferenceDestination` in the provider
registry derives the non-secret identity through the existing URL policy. The web grant
uses one consent-owned atomic localStorage entry, never the ordinary preference writer:
a stale Appearance/voice snapshot in another tab cannot restore a withdrawn grant. Provider-kind
switches and validated local-destination changes retire the grant; model-only changes do
not. `app.js` binds immutable grant/epoch permits to constructed providers and voice work,
and checks them before new sessions, resumed/pending work, capture and each model attempt.
The adapters' injected `beforeSample` hook includes internal retries but not key/catalogue
checks. No preference API enters `src/core/` or `src/runtime/`.

Android's `data/InferenceConsent.kt` owns the matching PrefsStore grant/revision policy.
`AppShell`, onboarding/Settings, `InterviewViewModel`, shared `InterviewWork`,
`ProviderSource` and `DriveController` use it before session/service/capture effects.
Pending work and permission callbacks cannot borrow a newer grant. Local read/export
does not require a provider. Failed acceptance is explicit and denied; failed withdrawal
blocks the current process but cannot promise that an unwritable stored grant was erased.
The [privacy policy](../guide/privacy-policy.html#model-requests) owns that user-facing limit.

## When a turn fails

The happy path is in [CLAUDE.md](../CLAUDE.md). This is the other one, and it is the
property most worth not breaking: **a provider outage never dead-ends an authorised interview.**

| What went wrong | Where it is handled | What the user sees |
|---|---|---|
| Inference is not authorised | UI/store admission, per-attempt provider hooks and explicit `consent` propagation in both runtimes | Review agreement; local text remains, with no checklist substitute for refusal |
| The provider threw | `bankFallback` in `src/runtime/turn.js` | A question from the built-in bank; the turnline says so |
| The model returned unparseable JSON | One repair directive, then a regeneration, in `runTurn` | Nothing |
| The question tripped a tripwire | `questionTripwire` in `src/core/engine.js`; one regeneration, then the bank | Nothing |
| There is no provider at all | `runTurn`'s null-provider branch | Checklist mode, no calls made |
| A call was interrupted | `resumeTurn` in `src/runtime/turn.js` | The interview picks up where it stopped |
| The wrap-up was interrupted | `resumeSynthesis` in `src/runtime/synthesize.js` | The write-up resumes, or the intact transcript is shown |
| The wrap-up call failed | `runSynthesis` in `src/runtime/synthesize.js`, then `buildExport` in `src/core/markdown.js` | The whole document, minus the refined prompt |
| Nothing usable was heard, hands-free | `createDriveLoop` in `src/runtime/drive.js` | It says so, listens again, and eventually moves on |
| Input or playback cannot recover | The voice lifecycle in `src/ui/app.js` | A blocked stage with Resume and Exit; the interview remains |
| Hosted speech failed during a reply | `createSpeechOutput` in `src/voice/output.js` | A visible explanation and browser-voice fallback |

Two subtleties inside that table. In `resumeTurn`, the turn id is the *correctness*
guarantee — it is what stops the same turn being created twice — while the prompt hash is
only a cache optimisation, and a miss is routine rather than a bug. And a bank fallback
deliberately leaves `zeroGainStreak` alone in both directions: incrementing it would let
two network blips offer the wrap-up exit for a reason that never happened.

Provider adapters translate HTTP failures into `ProviderError` codes from
`src/providers/errors.js`; the UI does not parse provider HTTP responses. Browser input and
playback failures are also surfaced by the voice layer rather than reported as success.

## What owns voice

The stage is an in-place view of `panel-interview`, not a new route or an always-listening
service. The ownership split is:

| Surface | Owner |
|---|---|
| Whole-utterance commands and the terminal finish word | `parseSpeech` in `src/core/driving.js` |
| Serial speak/listen/submit effects, bounded recovery and cancellation | `createDriveLoop` in `src/runtime/drive.js`; a resumed run gets a new instance |
| Start, Pause, Resume, Exit, session guards, wake lock and settings | `src/ui/app.js`; `voiceEpoch` separates retired voice effects from the current run |
| Captions, status, control labels and sample-driven bars | `createVoiceStage` in `src/ui/voice-stage.js`; it owns no capture or timers |
| Capture lifetime and draft-preserving pause | `createVoice` in `src/voice/index.js`, using `src/voice/recorder.js` or `src/voice/webspeech.js` |
| Genuine input levels alongside native recognition | `createMicMeter` in `src/voice/mic-meter.js`; recorder levels come from its own analyser |
| Current browser voices, selection fallback, pace and bounded native speech | `src/voice/speak.js` |
| Selected output, no-fallback preview and visible hosted degradation | `createSpeechOutput` in `src/voice/output.js` |
| Fixed, bounded hosted speech requests | `TTS_PRESETS` and `createTTSProvider` in `src/providers/tts.js` |
| Gesture-primed, in-memory buffered audio | `createAudioPlayback` in `src/voice/playback.js` |

Browser output is the default. Consent and the successful hosted-preview gate belong to
`previewSpeech` and `readSpeechFromForm` in `src/ui/app.js`, not to the provider constructor.
`withTts` in `src/store/secrets.js` keeps speech credentials independent of inference and
STT; `speechPreferences` in `src/store/prefs.js` holds the non-secret browser voice and pace.
Neither speech credentials nor generated audio enters session backups.
The separate `hostedSpeechAllowed` device flag defaults off. Withdrawal clears it
synchronously and revokes the keyring verification; only a successful explicit preview
enables both gates again.

The UI owns cancellation before a voice controller exists too: setup passes a signal through
`createVoice` to the behavioural recognition probe. A retired setup must not start a
recogniser when a delayed permission query returns. The stage enters listening only on an
actual capture-start notification, not merely because the loop requested a capture.

A fresh drive loop reads wrap eligibility through its injected `answeredCount` callback.
Pause/Resume must not reset the progress already recorded in the session. Turn and synthesis
work cannot share a pending promise with a different result shape; a retired voice turn may
settle after Exit, but it must not start synthesis without a new request.
The `hasDraft` callback lets a bare finish word submit retained words and prevents the
miss-recovery ladder from silently skipping them. Manual dictation releases its input when
that capture ends; active hands-free capture can still reuse its microphone between turns.

The behavioural rules live in [CLAUDE.md](../CLAUDE.md#voice-presentation-and-spoken-output).
The evidence boundary matters just as much: `tools/browser-check.mjs` includes separate
`tools/browser-policy-check.mjs` runs with real Web Audio and trusted input gestures, beside
the original synthetic-media/autoplay-bypassed suite. Its real-app phase drives the actual
Preview, Start and Resume handlers with trusted clicks and real decoded audio, while
inference, recognition and speech HTTP are scripted. Neither proves real permission UI,
native recognition contention or physical mobile behaviour. The optional hosted adapter
has no developer-verified live CORS or quality result for this redesign; its in-app preview
is a local enablement gate, not a universal compatibility claim.

The same policy runner also owns the accessibility phase
(`test/browser/a11y-keyboard.probe.mjs`), because its claims need input that the in-page
suite cannot send. Real Tab, Enter and Escape keys walk every panel at phone and desktop
widths, and touch emulation makes `pointer: coarse` really match while it measures target
sizes. Colour contrast for every token and component pair, in both themes, is the in-page
`test/browser/contrast.browser.mjs`. The trusted-input phase also composes the shared controls
on every surface through hover/press/focus, selected/disabled/busy/status cues, enlarged text
and forced colours. [DESIGN.md](DESIGN.md#contrast-and-targets) lists the measured contracts
and their limits; the native foundation has separate composed-control and device evidence,
not a claim that desktop browser tests prove Compose.

## The native Android app

`android/` is a separate Gradle project with three modules, and the same one-way shape:

```
android/core/       Kotlin port of src/core + src/runtime — pure JVM, no Android, no clock
android/providers/  Kotlin port of src/providers — HTTP policy and adapters on OkHttp
android/app/        Compose UI, stores, keyring and native speech; the only Android module
```

`:app → :providers → :core`, never the reverse. The first fence is that `:core` and
`:providers` are plain JVM modules, so Android cannot be imported at all; the second is
`android/core/src/test/kotlin/io/github/jaypetez/ideaforge/core/CorePurityTest.kt`, the twin of
`tools/lint-purity.mjs`. Nothing in `android/` reaches the published site; `assembleSite`
never copies it and `test/wiring.test.mjs` asserts so.

**Native visual ownership.** `ui/theme/Theme.kt` owns the Focus Studio fallback scheme,
all system-family typography, wallpaper dynamic-colour selection and the preferences-first
render gate. `ui/theme/Roles.kt` owns named spacing, shapes, content widths and composed
control/status ink; `ui/components/Controls.kt` consumes them through Material controls.
Existing screens use these shared primitives without a second state/navigation framework.
`MainActivity` still owns system bars and the app graph still owns effects. Theme/component
unit checks and the debug-only UX capture seam provide native evidence; isolated simulated
palettes are labelled separately from actual wallpaper colours.

**Launcher artwork.** `res/mipmap-anydpi-v26/ic_launcher.xml` is the manifest's normal and
round icon, supported across the app's API 29+ range. Its full-bleed brand background and
transparent, safe-zone anvil foreground let Android apply the device mask; Android 13+
launchers that support themed icons can tint the same foreground through the monochrome
layer. `PlayListingAssets.kt` draws the installed adaptive layers without that mask for
Play artwork. The opt-in `evidence=launcher` route in `android-device.yml` records actual
AOSP Launcher3 masked/themed display frames and the artwork, not an app-window simulation.

**Native shell and insets.** `ui/AppShell.kt` keeps one type-safe NavHost and chooses the
installed Material bottom bar or rail from the native window class. New idea opens
`NewIdeaRoute` without minting an id. A deliberate Voice/Type selection, guarded by the
origin entry and inference-admission revision, creates a fresh Interview entry; rotation/
resize does not perform another navigation action. `voiceOnEntry` is consumed once through
entry saved state and the visible Interview permission owner, not another drive. Pending
setup is retired on Stop/disposal; stale Continue/results cannot start later effects.
Interview and Result own their contextual title/Back presentation.
AppShell applies and consumes system-bar/display-cutout scaffold padding once before
all children; rail and contextual bars use zero additional Material insets. Editing
screens own only remaining IME, and Result actions remain inside the shell clearance.
In a short Interview with the IME open, its contextual bar shares the page scroller
instead of reducing the remaining viewport below one enlarged editor.
An attached app-scope drive hides navigation only for its visible interview session;
Voice stays in that destination and respects only remaining insets. Caption/level state
is not a reason to rebuild the shell. Content measures stay in `ui/theme/Roles.kt`, and
Library still uses `NavigableListDetailPaneScaffold` rather than a second breakpoint grid.

`OnboardingViewModel` retains per-provider field drafts only in its existing in-memory
setup lifetime. Its check generation still retires cancelled work, and its saved/check/
failure states are distinct. `OnboardingScreen` uses native window classes plus actual
available/font-scaled space for a bounded form and supporting pane; required fields and
privacy/agreement/recovery controls remain in the scrollable form.

**Native Settings.** `SettingsScreen.kt` keeps one five-section scrolling form and uses
the same native window/font-fit decision for its narrow section index. Scroll/focus
navigation leaves editor state intact. Summaries read the Settings/keyring/prefs/library
owners; saved configuration is not a connectivity or voice-quality verdict.
`HostedSpeechViewModel` keeps speech and transcription notices separate without changing
their keyring or preference shapes, and errors cannot be replaced by success after a
refused credential write. BackupJobs remains app-scoped; Forget keeps synchronous revoke
and saveIf generation checks. Native dialogs preserve cancellation/focus and data boundaries.
`DeviceVoiceSettings.kt` selects the existing `Prefs.speechVoice`; `SettingsViewModel`
loads metadata without writing a preference or playing a preview. `AppGraph.nativeTtsEngine`
is the one lazy engine shared with `TextToSpeechSpeaker`. Its serialized initialization
prevents a cancelled catalogue read from dropping a concurrent speaker's engine.
`AndroidTtsEngine.voiceDetails` reads names, locales and network flags, not a quality score.
Loading, engine failures, an empty list and a retained missing choice stay distinct; reload
is explicit. Each subsequent native speak re-reads prefs and retains the existing reported
default fallback. Hosted speech/key/verification/consents and inference remain independent.
`VoiceStage` renders the retained successful speech result's missing-voice warning alongside
any hosted-fallback reason, without changing the saved choice or the speech backend.

**Native Result and export.** `ResultScreen.kt` owns responsive action priority, one
selectable reading canvas and persistent operation errors. `ResultViewModel` distinguishes
load failure from a missing row and retains a retry; `exportFor` remains the canonical
string for all output paths. `ExportPreview` only presents safe core blocks with native
heading/type and local code/table scroll. It never serializes a replacement export.
FileProvider temporary-share identity/TTL, CreateDocument and same-session reopen stay
with ResultActions/the existing ViewModel and guards.

**Native Ideas export.** `ui/library/LibraryExports.kt` captures `exportFor`, the canonical
filename and clicked idea identity before yielding to Android. `LibraryScreen.kt` offers
Share/Save in row overflow and selected detail, leaving Continue/View result primary.
The single pending picker/write cannot borrow a newer selection; cancellation and
named errors remain separate from mutation notices and do not request focus. The
controller reads the existing store but never writes a session or constructs a provider.
Both it and Result use AppGraph's `documentSink`/`ContentResolverSink`; shared files still
use ResultActions' existing FileProvider/URI grants and independent temporary directories.

**Privacy-policy entry points.** `ui/PrivacyPolicyLink.kt` owns the native policy URL
and the shared localized action in Settings About and the common onboarding footer.
It uses the platform URI handler and reports a missing/blocked external handler inline
with the existing status primitive, including the destination and a retry. Each failed
tap brings the feedback into view using the existing native relocation API.
`SettingsScreenTest` and `OnboardingFlowTest` assert actual view intents against the
literal published URL; `PrivacyPolicyLinkTest` covers open failures and recovery.
`PrivacyPolicyRecoveryScreenTest` checks complete feedback visibility in the actual
enlarged, narrow AppShell, including another failure after scrolling back to the action.
The web link belongs to `index.html`'s shared Start/Settings About group, not a second route.

**The JavaScript is the reference, and parity is measured, not reviewed.** `tools/vectors.mjs`
records real calls into the JS core and runtime as JSON files under `test/vectors/`. The Kotlin tests
replay them through the consumers registered in
`android/core/src/test/kotlin/io/github/jaypetez/ideaforge/core/vectors/Consumers.kt`;
`VectorCoverageTest.kt` beside it fails when a family has no consumer. The provider and
speech families are replayed in `:providers`, and the walkthrough family also runs through
the interview view model in `:app`. So a behaviour change lands in JavaScript first, the
vectors are regenerated in the same PR, and the `android` CI job stays red until the port
follows. The contract rules are in [CLAUDE.md](../CLAUDE.md#android-invariants).

**What `:app` owns.** `android/app/src/main/kotlin/io/github/jaypetez/ideaforge/AppGraph.kt` is
manual dependency injection: the one place wall time is read (`Clock`) and ids are minted
(`IdGen`), and the owner of every store. Sessions are one JSON file each in `SessionStore.kt`,
kept as raw JSON objects so fields a newer build wrote round-trip untouched. The keyring is
encrypted under an Android Keystore key in `KeyringStore.kt`, outside the sessions directory
so no backup can sweep it up. The screens are view models over those stores, and the
debug-only scripted provider is wired in at launch by `MainActivity.kt`.

**Where native and web differ on purpose.** The browser's "local means loopback" rule exists
because the CSP is the key-exfiltration boundary. On a phone the model server is usually on
another machine, so `android/providers/src/main/kotlin/io/github/jaypetez/ideaforge/providers/LocalNetworkPolicy.kt`
allows private networks instead, and enforces it at DNS resolution rather than by reading
the URL. Android cloud backup is off, so the only backup is the user's own export, which is
the same file the web app reads and writes.

[`NativeTranscript`](../android/core/src/main/kotlin/io/github/jaypetez/ideaforge/core/NativeTranscript.kt)
is also native-specific: Android's whole partial hypotheses and final callbacks differ from
the browser's per-index drafts. Restart replay compares normalized words, but splices at
original-text boundaries so new words, spelling and leading punctuation survive. A Unicode
normalization expansion crossing that boundary stays intact rather than being partly repeated.
`NativeTranscriptTest` and `RecognizerTest` protect this behavior outside the shared JS
vector families.

**On a device.** Tap-start/tap-stop manual dictation and the hands-free voice stage
with its drive loop running as a foreground service, live under
`android/app/src/main/kotlin/io/github/jaypetez/ideaforge/voice/`. An instrumented
walkthrough in `android/app/src/androidTest/` drives the debug build's scripted interview to
the Result screen; the separate, non-required
[`android-device.yml`](../.github/workflows/android-device.yml) workflow runs it on the Gradle
managed emulator `pixel6Api34` on every push to `main`, nightly and on demand.
Default/full then explicitly runs `UxCaptureDeviceTest#requestedUxEvidenceIsRenderedCompleteAndReachable`
on both standard AOSP visual profiles, with clean checkout checks.
`tools/check-android-device-results.py` rejects zero, wrong-method or unsuccessful visual
JUnit results independently of Gradle's exit status; the recorder still requires complete
manifests, real rendered pixels and reachable controls. The separate shell route keeps its
own explicit method and opt-in.
`ManualDictation` uses the existing recognizer's `PRESS_TO_TALK` mode, not the drive's
command parser: results are literal draft text and only Send advances. `InterviewViewModel`
binds permission and result continuations to the original permit, visible entry, question,
draft revision and session-work tag, and rechecks durable question/draft identity.
The editor's observed durable draft is the baseline, not a newer row read at Start.
The graph shares input-intent revisions and audio ownership with the drive, including
pending authorization/permission. Manual selection retires older hands-free setup and
pauses a surviving drive; drive Start/Resume retires manual intent before output.
The paused drive surrenders its recognizer so its later Exit cannot release newer input.
Whisper supplies an immediate idempotent recording-release handle for blocked-read handoff.
Navigation, disposal, edits
and Report cancel unfinished input. The existing Whisper fallback remains restricted to
native unavailability and its own current key/audio consent; manual Stop releases recording
before its bounded transcription. `Prefs.handsFree` round-trips legacy storage but has no
UI or capture decision. The finish word still belongs only to hands-free.
`evidence=dictation` selects the real manual journey with controlled recognizer callbacks
on both visual profiles, both themes and actual font scales 1.0/1.3/2.0, with the same
selected-test, clean-SHA, rendered-pixel and reachability guards.
Its opt-in `evidence=microphone-demo` route instead uses the standard Google APIs
`pixel2Api34Voice` image and `MicrophoneDemoDeviceTest`: private setup stores and the debug
scripted model, but real native speech/recognition, runtime Allow dialogs, microphone,
`DriveService`, notification actions and Exit. The existing graph seam explicitly selects
the native default recognizer, not the stock image's unusable on-device English engine.
The actual device `screenrecord` binary runs
through UiAutomation's shell transport; native media APIs verify/decode the MP4 in the
workflow artifact. This is declaration evidence on that emulator, not physical-device or
speech-quality certification; the maintainer owns a durable video upload.

**Not built yet.** Hosted speech in the app and a signed release. The hosted transcription
and speech clients in `:providers` exist, with tests, ahead of the screens that will use them.

## Extension seams

**A provider.** See the walkthrough in [CONTRIBUTING.md](../CONTRIBUTING.md) — it is the
one place that list lives, because it spans four files and forgetting the fourth fails
invisibly.

**A dimension.** Append to `DIMENSIONS` in `src/core/dimensions.js`; coverage init,
selection, the prompt's coverage block, the synthesis section list and the export table all
derive from it. Then three things that do not: `legalMoves` in `src/core/engine.js` has a
per-dimension `switch` a new entry falls through to a sane default of, `docs/hero.svg`
hardcodes the seven labels, and `test/docs.test.mjs` will fail on the README still saying
"seven dimensions" — which is the system working.

**A UI panel.** A `<section>` in `index.html`, its ids added to the `els` list in
`src/ui/app.js`, and the panel id added to `show()`. `show()` also writes the panel's name to
`body[data-panel]`, which is what `src/ui/app.css` lays the shell out from: below 600px, a
fixed bottom navigation bar for the top-level destinations (Ideas, New idea, Settings) and a
top app bar with a back arrow for detail screens (Interview, Done). Decide which kind the new
panel is and add it to those selectors. From 600px the same destinations form a compact
labelled rail; at 1200px it expands. `renderShell()` derives the contextual title from
`sessionDisplayTitle`, with literal full text and a visual ellipsis, not a second title
store. Body padding reserves rail/safe-area space and is removed while voice owns the
screen. Start and Settings are not two panels: they are
two `data-view` values of `#panel-setup`, so a new settings group is a `.settings-only`
block plus a button in `#settings-index`, not a panel. `boot` in `src/ui/app.js` picks the
opening panel (Ideas when any session is stored, Start otherwise), and `show()` clears the
`#toasts` messages whenever the screen changes, so a message meant for the new screen is
raised after it. The toast rules are in [CLAUDE.md](../CLAUDE.md). Panel-specific DOM belongs in a module such as
`src/ui/library.js`; `app.js` keeps ownership of the active session and navigation.

**A dictation backend.** `STT_PRESETS` in `src/voice/transcribe.js`, plus an `<option>` in
`index.html` — the select is not data-driven. Read the voice section of
[CLAUDE.md](../CLAUDE.md) first: this is the subsystem where feature detection lies.

**A spoken command.** A phrase list in `COMMANDS` in `src/core/driving.js`, and a branch in
`createDriveLoop` in `src/runtime/drive.js`. The loop's injected `io` turns the branch into
an effect, so matching and sequencing can be tested in Node. Wire a new browser effect in
`src/ui/app.js` when needed, as Pause and Exit do. The split mirrors
`core/synthesis.js` ↔ `runtime/synthesize.js` — pure material in `core/`, the orchestration
that consumes it in `runtime/`.

**A speech-output backend.** Keep network policy in `src/providers/`, browser playback in
`src/voice/`, and the `speak` effect injected into the drive loop. Wire consent, a separate
credential record, preview, failure indication and deletion in the UI; a provider returning
audio is not proof it can be read and played by a browser. The implementation must not
silently borrow a model key or widen the remote CSP allowlist.

## The costs we have accepted

Each of these is a deliberate debt with a trigger for paying it down, not an oversight.

**`src/ui/app.js` still owns navigation and the active session.** The fourth panel paid down
the old all-in-one rendering cost: the library DOM lives in `src/ui/library.js`, installation
events in `src/ui/install.js`, outbound files in `src/ui/share.js`, the overflow menu shared
by library cards and Done in `src/ui/menu.js`, the theme choice in `src/ui/theme.js` (with
`src/ui/theme-boot.js` applying a stored one before first paint), and voice-stage rendering
in `src/ui/voice-stage.js`. The coordinator is still a shared edit point, but a new panel
no longer has to put its rendering logic there.
*Trigger: navigation itself needs history, deep links or independently mounted screens.*

**One interview profile is assumed throughout.** `DIMENSIONS`, `SEED_QUESTION` and the two
prompt rule blocks in `src/core/engine.js` and `src/core/synthesis.js` are module-level
singletons reached directly by most of `core/`. A second interview type means threading a
profile object through `createSession`, `buildTurnPromptParts` and
`buildSynthesisPromptParts`. The dependency-injection discipline already in place makes it
mechanical rather than a rewrite, but it touches nearly everything.
*Trigger: anyone asks for a second interview type. This is the highest-leverage next
investment.*

**No multi-tab reconciliation.** Two tabs on one session, last write wins, no merge. The
cheap version — read before write, keep the higher `rev` — is worse than nothing, because
silently returning a different session while the UI still holds the old one loses work more
confusingly than overwriting does. A correct version needs a conflict policy and a story
for "another tab moved this".
*Trigger: someone reports losing work.*

**Three hand-maintained registries** — the CSP `connect-src` in `index.html`, the `SHELL`
list in `sw.js`, and the provider presets — with no single source of truth, all of which
fail invisibly. `test/wiring.test.mjs` now asserts the first two stay in step with the
third, which is how a missing `src/version.js` in `SHELL` was found: it broke cold offline
launches and nothing else, so nothing caught it.
*Trigger: a fourth registry appears, or the test starts needing exceptions.*
