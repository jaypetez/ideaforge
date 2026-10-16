# Contributing

Thanks for looking. This is a small project with a couple of deliberate constraints, and
knowing them up front will save you time.

## Getting it running

```sh
git clone https://github.com/jaypetez/ideaforge.git
cd ideaforge
npm test        # no install step — there is nothing to install
npm run serve   # then open http://127.0.0.1:8765
```

Node 22 or newer. There is no build step, no bundler, and no dependencies — not even dev
dependencies. `npm test` runs the purity lint and then Node's built-in test runner.
`npm run test:graph` checks the import graph, `npm run test:browser:required` covers the
browser-only surfaces, and `npm run test:all` runs all three with Chrome required.

`file://` will not work: ES modules, IndexedDB and the service worker all need an origin.

## Coding assistants

Claude Code and GitHub Copilot CLI/VS Code use the same working rules and project skills.
Open the repository root, not just `src/`, so the clients can discover them.
There is nothing to install into the project to enable this support.

| Client | Repository instructions | Shared skills | Review agents |
|---|---|---|---|
| Claude Code | `CLAUDE.md`, importing `AGENTS.md` | `.claude/skills/` | `ideaforge-review-claude`, `ideaforge-docs-review-claude` |
| Copilot CLI | Copilot + model instructions | the same directory | `ideaforge-review-copilot`, `ideaforge-docs-review-copilot` |
| Copilot in VS Code | Copilot + model instructions | the same directory | all repository adapters |

[AGENTS.md](AGENTS.md) owns the working loop, including the required browser checks and
PowerShell equivalents. [CLAUDE.md](CLAUDE.md) owns the implementation invariants.
The Copilot entry point directs the agent to both; it does not maintain a competing
rulebook. A link is also useful for navigation, but is not a guarantee that a client has
automatically loaded the linked file.

The shared skills are:

- `add-provider` for inference, transcription and hosted speech endpoints;
- `change-voice-and-driving` for microphone, speech, and hands-free behavior;
- `validate-local-model` for the real Ollama/GPU harness;
- `android-verify` for the native Android build, golden vectors and emulator smoke run;
- `update-documentation` for the public guide and every source-derived documentation surface;
- `update-readme` for generated screenshots, the worked example, and source-derived claims;
- `review-ideaforge-documentation` for a high-confidence read-only documentation review;
- `review-ideaforge-change` for a high-confidence read-only review.
- `release` for explicitly requested preparation, publication, or verification;
- `work-issues` for explicitly invoked backlog work, one issue at a time, merging only on
  a green `ci` check.
- `ideaforge-assist` for explicit Lite, Full, Ultra and Off session/default controls;
- `audit-ideaforge-codebase` for an explicitly requested, read-only map-first audit;
- `ideaforge-debt` for a read-only shortcut/upgrade-trigger ledger;
- `ideaforge-gain` for counted diff/debt facts, not invented savings;
- `ideaforge-assist-help` for the source-linked process reference.

The review agents are thin client adapters around the two shared review skills. They
deliberately have no shell or edit tool, so supply an attached/pasted diff or readable
PR/source-control context. Their product-qualified names avoid relying on undocumented
precedence where VS Code discovers both agent directories.

The [release skill](.claude/skills/release/SKILL.md) is explicitly invoked, not selected
automatically. It separates release preparation from publication and verification; see
[cutting a release](#cutting-a-release). Preparing a PR is not permission to merge it
or push a production tag.

The [work-issues skill](.claude/skills/work-issues/SKILL.md) is explicitly invoked too. It
takes the next unblocked `agent-ready` issue, proves each acceptance criterion, has the
read-only review agent check the exact diff, and squash-merges only when `ci` is green on
the tested head. It never tags, releases, or touches settings, and it labels anything that
needs a device, a secret, paid usage or a decision `needs-human`.

The [lean coding contract](AGENTS.md#lean-coding) is adapted from Ponytail without copying
its many-client distribution. Full guidance is automatic in the two CLI clients after
normal workspace trust; `/ideaforge-assist lite`, `full`, `ultra` or `off` selects the
current native session's level. Off never turns off the repository's baseline rules.
Defaults and reset are explicit actions; the other process skills are read-only one-shots.
The [process reference](docs/ASSISTANT-PROCESS.md) owns the commands, local configuration,
client limits, optional statusline setup and evidence boundaries.

Claude consumes `.claude/settings.json` lifecycle hooks. Copilot CLI consumes
`.github/hooks/ideaforge-assist.json` for startup/child context and explicit state changes.
Its `.github/extensions/ideaforge-assist/extension.mjs` supplements prompt/compaction
context in a foreground client with extension support; the SDK is supplied by the client,
not by an npm dependency. Command-hook prompt output is ignored by Copilot, so without
the supplement use the control skill's explicit helper confirmation rather than claim
same-turn automatic refresh. A foreground CLI may ask to let `project:ideaforge-assist`
register hooks after normal workspace trust; approve only if you trust this reviewed
project code. A session-only choice is sufficient; do not grant unrelated tools, paths,
URLs or secrets to enable it. These adapters read one canonical rule section and keep only
mode metadata in the ignored `.ideaforge-assist/` directory, isolated by worktree, client
and native session. They make no model calls, request no secrets, and change no permissions.
VS Code still discovers the shared skills; these CLI lifecycle adapters are not a claim of
automatic mode/context support in the editor.

The bounded map, debt and gain helpers use Node and Git. They do not replace source
inspection, LSP, a supplied exact review diff, or a correctness test. Gain means measured
facts about the named diff and markers, never an estimate of code or money that was saved.
Any upstream benchmark evidence is explicitly not an IdeaForge measurement.

**Check what was discovered before relying on it.** In a terminal at the repository root:

```sh
copilot instruction list --json
copilot skill list --json
```

The instruction listing should include the three Copilot entry points above. The skill
listing should show every shared skill listed above, from this repository's `.claude`
directory, not unrelated personal or plugin skills with the same names. Start a fresh
session after changing instructions or agents. In Copilot CLI, `/env`, `/instructions`,
`/skills`, and `/agent` inspect the loaded configuration.

In VS Code, open the Chat customization view or its Diagnostics view and inspect the
instruction, skill, and agent sources. In Claude Code, `/memory` and `/skills` show the
loaded instructions and skills; project agents live in `.claude/agents`.

If something is missing, check the workspace root and whether customizations have been
disabled, then check the client's own documentation and version. Respect workspace trust
and tool-approval prompts; do not fix discovery by disabling those controls or overwriting
someone's global settings. File checks in `npm test` catch broken references and duplicate
skills, but only the client can prove what it actually loaded.

The shared location is deliberate: both
[Copilot CLI](https://docs.github.com/en/copilot/reference/copilot-cli-reference/cli-command-reference)
and [VS Code](https://code.visualstudio.com/docs/agent-customization/agent-skills) support
`.claude/skills`, which is [Claude Code's native project location](https://code.claude.com/docs/en/skills).
Do not create a second copy, a symlink, or a synchronisation script in `.github/skills`.
For instruction discovery, see the
[VS Code guidance](https://code.visualstudio.com/docs/agent-customization/custom-instructions)
and [Claude's import documentation](https://code.claude.com/docs/en/memory).

This covers local development with the two assistants, not Copilot cloud-agent setup or
an additional model provider in the app. Open a PR for review and leave merging to the
reviewer unless you were explicitly asked to merge.

The optional [Playwright MCP server](.mcp.json) supports exploratory browser work in
Copilot CLI and VS Code. `tools/playwright-mcp.mjs` finds the same Chrome or Edge
executable as the browser tests (or uses `CHROME_PATH`) and runs a pinned package
via `npx` in an isolated, headless profile: first use downloads the tool, but
adds no app dependency or build step. Tool output goes to a temporary directory
and is removed when the MCP server exits. Check `copilot mcp list --json` or VS
Code's MCP server view after trusting the workspace; approve the executable
when prompted. This supplements, not replaces, `npm run test:browser:required`.
The optional browser MCP needs no plugin installation. The lean-process adapters above
are project-native hooks/extension files; use a client with those capabilities, inspect
their fresh-session loading, and preserve normal trust/approval prompts. A custom mode
badge is opt-in and must not overwrite an existing personal statusline. No global
assistant installation or configuration change is part of repository setup.

## The one rule that is enforced mechanically

```
src/core/       pure interview logic — no DOM, no network, no clock, no randomness
src/runtime/    the turn loop — provider and clock are injected
src/providers/  inference and hosted speech requests, shared HTTP policy
src/store/      IndexedDB and the encrypted key
src/voice/      microphone, transcription requests, native speech and playback
src/ui/         the app shell
```

`tools/lint-purity.mjs` fails the build if anything under `src/core/` or `src/runtime/`
mentions `window`, `document`, `localStorage`, `sessionStorage`, `navigator`, `fetch`,
`alert` or `claude`. It runs as part of `npm test`.

This is not stylistic. It is what lets the interview engine be tested without a browser
and the turn loop be driven without a network, and it is why the whole test suite runs in
under a second with no mocking framework. If you need a platform API, it belongs in one of
the other directories, and the thing that needs it should take it as an argument.

## Adding a provider

Most providers are an OpenAI-shaped `/chat/completions` endpoint, and those are a preset
rather than code. Four files, and the fourth is the one people forget:

1. `src/providers/openaiCompat.js` — an entry in `OPENAI_COMPAT_PRESETS`: a label, a base
   URL, how it authenticates, what its output-budget parameter is called, and either a
   `tiers` map of model ids or `local: true` and a model the user picks. Set
   `discoverModels` when `GET /models` answers a browser with CORS headers, which is what
   lets the settings screen offer the real catalogue instead of the preset's defaults.
   `PROVIDER_CHOICES` derives itself from that object, so `src/providers/index.js` needs
   no edit at all.
2. `index.html` — the origin goes in `connect-src`. That list is an allowlist and the app
   talks to nothing outside it. A missing host throws nothing and logs nothing to the
   page: the request is simply blocked, the app carries on as though the network were
   down, and you lose an afternoon.
3. `sw.js` — only if you added a *file*. `SHELL` is hand-maintained, and a module missing
   from it fails on a cold offline start and nowhere else, because any online visit caches
   it anyway.
4. `test/wiring.test.mjs` — nothing to write. It already asserts every preset's origin is
   in the CSP and every file under `src/` is in `SHELL`, so steps 2 and 3 fail the build
   instead of failing on someone's phone. That test exists because `src/version.js` was
   missing from `SHELL` and broke cold offline launches for months without a symptom
   anyone could see.

How a credential is attached is data, not code. `src/providers/http.js` has the descriptor
— `bearer`, a named `header`, a `query` parameter, or `none` — so a provider that signs
requests differently is a preset field rather than a new adapter.

A provider that is not OpenAI-shaped needs its own adapter beside
`src/providers/anthropic.js` and a branch in `createProvider`. Whatever you write exposes
`sample`, `sampleJson`, `listModels` and `validateKey`, and throws `ProviderError` with one
of the codes in `src/providers/errors.js` — the turn loop reads that code to choose between
retrying, falling back to the question bank, and telling the user their key is wrong.
Returning a plain `Error` makes every failure look like the same failure.

Two things to check by hand, because `npm test` cannot see either: that a *bad* key
produces a useful message rather than an opaque `TypeError` — browsers strip CORS headers
from some 401s, so the real error never reaches the page — and that the preflight survives
a real browser. `npm run serve`, paste a key, and name the browser in the PR.

Hosted speech is a separate seam: `src/providers/tts.js` owns its bounded requests and
`src/voice/output.js` owns output selection and fallback. Preserve the separate encrypted
speech key, explicit consent and no-fallback preview gate in the UI. A model key check
does not prove speech compatibility. Do not run live paid checks without an explicitly
authorised key and spending cap; use injected transport and local audio fixtures otherwise.

## Where everything else goes

`docs/ARCHITECTURE.md` is the map: what owns the session, what happens when a turn fails,
where to cut in for a new dimension or panel, and which costs this codebase has knowingly
taken on. Worth ten minutes before a first change of any size.

`guide/` is the public documentation site published beside the app. Use the shared
`update-documentation` skill for public behavior, provider, mobile, storage, privacy,
security, packaging, command or workflow changes. Run `npm run test:docs` while editing;
new pages must update the guide navigation, docs inventory and Pages/container packaging
together. The README remains the landing page, not a second copy of the full guide.

## Development UX evidence

The [design state inventory](docs/DESIGN.md#development-fixtures-and-captures) has
deterministic, secret-free stress fixtures shared by browser and native tests. Generate and
check their reducer-built data from the repository root:

```powershell
node tools\ux-fixtures.mjs
node tools\ux-fixtures.mjs --check
node tools\ux-captures.mjs --out C:\scratch\ideaforge-ux
```

The capture runner uses the existing discovered browser/server/CDP harness on the assembled
site. It requests both themes and compact/expanded windows, checks narrow reflow, and writes
PNG tiles plus a state/geometry/font-scale/tested-SHA manifest. `--states fresh,result-long`
narrows the state set; `--theme light`, `--viewport 390x844` and `--text-scale 2` narrow or
enlarge a run. Output goes to a new development-artifact directory, not `docs/screenshots/`.
Missing states, clipped actions, fallback fonts and forbidden external model/media requests
fail explicitly. The app's existing Google Fonts requests are allowed and recorded.

Final evidence needs a clean head; `--allow-dirty` labels temporary development evidence.
Failures leave their manifest rather than claiming a skipped pass. Real-app and isolated
voice renderer captures are distinguished, with unavailable samples never drawn as a live
meter. Lab response samples are not field Web Vitals or a usability study. The canonical
README walkthrough still uses `npm run screenshots`, with its byte-stable export.

Native `UxCaptureDeviceTest` reads the same debug-only data. From `android\`, with an
explicit owned emulator serial and JDK/SDK environment:

```powershell
$sha = git rev-parse HEAD
.\gradlew.bat :app:connectedDebugAndroidTest `
  '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true' `
  '-Pandroid.testInstrumentationRunnerArguments.class=io.github.jaypetez.ideaforge.ux.UxCaptureDeviceTest' `
  '-Pandroid.testInstrumentationRunnerArguments.uxCapture=matrix' `
  '-Pandroid.testInstrumentationRunnerArguments.uxCaptureWindow=all' `
  "-Pandroid.testInstrumentationRunnerArguments.uxCaptureSha=$sha"
```

The all-window matrix uses compact/expanded, light/dark and actual font scales 1.0/1.3/2.0.
Add `'-Pandroid.testInstrumentationRunnerArguments.uxCaptureStates=voice-listening+result-long'`
for a focused run; `+` avoids AGP's comma truncation. Final evidence needs a clean unchanged
checkout, not merely a supplied SHA. `uxCapture=walkthrough` instead captures only the real
scripted seed/chip/Result frames and rejects state selectors; it is not a full-matrix pass.
Use `uxCaptureSourceState=development-dirty` for uncommitted iteration; its default `clean`
does not replace checking that the supplied SHA and worktree are actually unchanged.
PNGs, reachability shots, semantics and manifests land in additional-output directories
under `android/app/build/intermediates/` or `build/outputs/`, named
`connected_android_test_additional_output/` or `managed_device_android_test_additional_output/`.
The current managed runner uses variant/task directories under `intermediates/`.
The Android-device workflow collects both locations, along with reports, even after failure.

Use a standard rendering-enabled image, not ATD, for pixels. Real navigation and isolated
Compose voice fixtures are explicitly distinguished; native-only `voice-writing` is rejected
by the web runner. [DESIGN](docs/DESIGN.md#development-fixtures-and-captures) documents native
phase/route mappings and the prerequisite fixes the matrix exposed. A focused green run
is not a green full matrix; inaccessible actions, an unavailable read presented as empty,
and black PNGs must remain failures.

For the adaptive native shell, dispatch the existing workflow on the exact feature ref:

```powershell
gh workflow run android-device.yml --ref '<feature-branch>' -f evidence=shell
```

This selects `UxCaptureDeviceTest#requestedShellEvidenceKeepsInsetsAndIdentity` with
explicit SHA provenance on both standard visual managed devices. It covers compact,
medium, expanded and compact-landscape windows, both themes and font scales 1.0/1.3/2.0,
actual IME, resize/recreation, same-session Ask me more and in-place paused voice.
Additional light/font-2 cases repeat Interview/IME, Result and paused Voice with each
installed three-button/gestural system-navigation overlay, verifying selection and
restoring the original overlay enablement. They prove inset layout, not gesture usability.
The paused controller uses scripted speech and a fake service; it proves layout/identity,
not real recognition, permission prompts or service behavior. The default `evidence=full`
retains the shared full inventory; a focused shell pass is not a full-matrix pass.

For privacy-link integration, `-f evidence=privacy` selects
`UxCaptureDeviceTest#requestedPrivacyPolicyLinksAreReachable` with `uxPrivacyProbe=after`.
It captures Settings About and both onboarding steps on the compact and expanded visual
devices, in both themes at font scales 1.0/1.3/2.0. Each link must be reachable, labelled,
fully painted and at least 48dp. `-f evidence=privacy-before` instead requires those links
to be absent and records the baseline's existing controls; use it only on an unchanged
pre-integration application tree. The manifests label before/after and the actual checkout
SHA. Require nonzero execution of the selected test and the complete accepted manifests;
a successful Gradle command alone is not visual proof or a full-inventory pass.

For inference-disclosure integration, `-f evidence=consent` selects
`UxCaptureDeviceTest#requestedInferenceConsentIsProminent` with `uxInferenceProbe=after`.
It requests Settings Connection, provider selection and hosted/local provider details
on both visual devices, in both themes at font scales 1.0/1.3/2.0.
`-f evidence=consent-before` requires the agreement controls to be absent and records
the existing actions instead; use it only on the unchanged pre-consent application.
The recorder requires the complete requested matrix and actual checkout SHA.
Neither a focused result nor zero discovered visual tests certifies the default inventory.

For the native shared-control foundation, select
`UxCaptureDeviceTest#requestedFoundationEvidenceSeparatesFallbackAndSimulatedPalettesFromActualWallpaper`
with the same provenance/window arguments and add `uxFoundationProbe=true`.
Its separate `ux-foundation` manifest covers the real fallback plus two labelled simulated
schemes at both windows/themes and font scales 1.0/1.3/2.0. These are isolated production
controls; simulations do not certify actual wallpaper colours or microphone behavior.
The normal real-app matrix continues to use system dynamic colours. See
[DESIGN's exact command and evidence boundaries](docs/DESIGN.md#development-fixtures-and-captures).

## The Android app

`android/` is a native Kotlin and Jetpack Compose app, and the one place in the repository
with real dependencies. It is a separate Gradle project: nothing under it is imported by,
copied into or served beside the web app, and `test/wiring.test.mjs` pins that
`tools/assemble-site.mjs` never publishes it. The app is an alpha that runs the typed
interview and a hands-free voice interview: native speech capture (#91) and the voice stage,
kept hearing by a microphone foreground service (#92). CI uploads a debug APK; the release
workflow attaches a signed one (see [cutting a release](#cutting-a-release)).

```
android/core/       pure Kotlin/JVM port of src/core + src/runtime (no Android, no clock, no network)
android/providers/  pure Kotlin/JVM port of src/providers: HTTP policy and adapters, on OkHttp
android/app/        the Compose application: stores, keyring, screens, native speech output
```

The module boundaries are enforced, not agreed. `:core` and `:providers` are plain JVM
modules, so nothing Android can be imported there, and `:providers` may use `:core`, never the
reverse. `CorePurityTest` is the Kotlin twin of `tools/lint-purity.mjs` and bans clocks,
randomness, files and the network from `:core`; `ModuleBoundaryTest` fails if the Android
framework reaches its classpath, and checks that the session schema number matches
`src/core/session.js`. `:app` injects wall time and ids (`Clock` and `IdGen`, built in
`AppGraph`), just as the web UI injects them into the runtime.

The build's facts live in Gradle, and `test/docs.test.mjs` fails if this table drifts from them:

| Fact | Value | Owner |
|---|---|---|
| Minimum SDK | minSdk 29 | `android/app/build.gradle.kts` |
| Target SDK | targetSdk 36 | `android/app/build.gradle.kts` |
| Compile SDK | compileSdk 37 | `android/app/build.gradle.kts` |
| Android Gradle plugin | AGP 9.4.1 | `android/gradle/libs.versions.toml` |
| Kotlin | Kotlin 2.4.21 | `android/gradle/libs.versions.toml` |
| Gradle wrapper | Gradle 9.8.1 | `android/gradle/wrapper/gradle-wrapper.properties` |
| Toolchain | JDK 21 | every module's `jvmToolchain` |

Every library version is in the catalog; bump it there, never inline in a build script.
`versionName` is read from `package.json`, so the release triplet stays in two files, not
three. The debug build adds `.debug` to the application id and installs beside a release.

The release build is signed only when a keystore is configured: the environment variables
`ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and
`ANDROID_KEY_PASSWORD`, or else the gitignored `android/keystore.properties` with
`storeFile` (relative to `android/`), `storePassword`, `keyAlias` and `keyPassword`. With
neither, `:app:assembleRelease` produces `app-release-unsigned.apk`; a half-filled source is
an error. `assembleRelease` is finalised by `:app:verifyReleaseApk`, which fails if the APK
packages the walkthrough script or R8's mapping lists any debug-only class. Never commit a
keystore or its passwords.

```sh
cd android
./gradlew check :app:assembleDebug   # what CI runs; gradlew.bat on Windows
./gradlew :core:test                 # one module at a time while iterating
```

**The golden vectors are the contract.** The JavaScript core is the reference.
`npm run vectors` records real calls into it as `test/vectors/*.json`, and
`node tools/vectors.mjs --check` (run by `test/vectors.test.mjs`) fails when they are stale.
The Kotlin tests replay every family through the consumers registered in
`android/core/src/test/kotlin/io/github/jaypetez/ideaforge/core/vectors/Consumers.kt`, and
`VectorCoverageTest` fails when a family on disk has no consumer. A JavaScript change that
alters behaviour regenerates the vectors in the same PR, and the `android` job then stays red
until the port follows. Never edit a vector by hand or loosen a Kotlin consumer to make it
pass. Backups are a second contract: `test/fixtures/backup-from-web.json` and
`test/fixtures/backup-from-android.json` are each imported by the other platform's tests.

**CI.** The `android` job in `.github/workflows/ci.yml` runs on Ubuntu with Temurin JDK 21,
validates the Gradle wrapper, runs `./gradlew --no-daemon check :app:assembleDebug`, and
uploads the debug APK as the `ideaforge-debug-apk` artifact. It feeds the single required `ci`
check.

**On a device.** `android/app/src/androidTest/` holds an instrumented walkthrough: it launches
the debug build with its scripted provider, answers the interview through the screen (typed
answers and a chip), presses Write it up and checks the Result screen shows the export. The
scripted provider exists only in the debug source set, which `ScriptedProviderIsDebugOnlyTest`
asserts. `.github/workflows/android-device.yml` runs it on the Gradle managed device
`pixel6Api34` (an ATD image), followed by UX captures on the rendering-enabled
`pixel6Api34Visual` and `pixelTabletApi34Visual`, on every push to `main`, nightly and on demand
(`workflow_dispatch`). It is not part of the required `ci` check. Locally, against an emulator
that is already running (without the `leaveApksInstalledAfterRun` property the task uninstalls
the app afterwards, wiping its keyring and ideas):

```sh
ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest   -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
./gradlew pixel6Api34DebugAndroidTest   # downloads and boots its own emulator
```

In PowerShell set `$env:ANDROID_SERIAL` explicitly and quote dotted Gradle properties:

```powershell
$env:ANDROID_SERIAL = 'emulator-5554'
.\gradlew.bat connectedDebugAndroidTest '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true'
```

Without those quotes PowerShell can split the property into an unintended task name.
The ATD image disables drawing and omits SystemUI/IME, so use a standard rendering-enabled
image for UX screenshots, visual inspection and system-bar evidence. Do not relabel a green
semantic ATD walkthrough or a black PNG as visual evidence.

The shared [`android-verify`](.claude/skills/android-verify/SKILL.md) skill is the full build,
vector and emulator procedure, including planting a fault to prove a test bites. The Android
invariants are in [CLAUDE.md](CLAUDE.md#android-invariants). Installing the app is in the
[Android guide](guide/android.html).

## Things worth knowing before you change them

- **The turn prompt must stay a pure function of session state.** No timestamps, no
  randomness. An interrupted turn is recovered by re-issuing the byte-identical prompt;
  two tests assert this, and if it breaks the failure is silent.
- **Never trust model output.** `parseTurnResult` and `parseSynthesisResult` assume the
  model lies about shape, and every field is optional. Add validation there, not at the
  call site.
- **The coverage ratchet is the product.** Coverage never falls, never rises more than one
  level per turn, caps at two dimensions per turn, and refuses `covered` without a
  verbatim user quote. Loosening it makes the interview stop early and feel useless.
- **Voice answers are a distinct provenance.** `source: 'voice'` exempts an answer from the
  terse and dodge thresholds because dictation rambles. Chip and unedited-draft answers are
  capped at `partial` because they are the model's words, not the user's.
- **Voice takes turns; cancellation stops progression.** `src/runtime/drive.js` serialises
  speaking, listening and submission, with bounded no-tap recovery for transient misses.
  Explicit Pause or a blocked input/output path stands down; Resume requires a tap.
  The UI releases capture/playback and preserves the originating session's draft or
  already-submitted work. A spoken command belongs in `src/core/driving.js` and the loop,
  and must yield empty answer text rather than becoming an answer.

## Tests

`node:test` and `node:assert/strict`, flat tests, no `describe`. Every test must run with
no network and no real timers — HTTP is tested with an injected `fetch`, and the silence
detector is clock-injected precisely so it can be tested without a microphone.

Prefer a test that would have caught a real bug over one that restates the implementation.
Several tests here exist because the behaviour they check was wrong the first time; the
comments say which.

For public documentation work, run `npm run test:docs` while editing. Guide routing,
service-worker, screenshot or app-link changes also need `npm run test:browser:required`;
the all-up pre-push gate remains `npm run test:all`.

The required browser command also runs `tools/browser-policy-check.mjs`: fresh-profile,
real Web Audio checks with trusted gestures, separate from the synthetic microphone suite's
autoplay bypass. Keep those evidence classes separate in a PR. Neither replaces real
permission UI, native recogniser contention, physical Android/iPhone checks or a live
hosted-speech audition. Record untested cases explicitly, including tab versus installed
mode. No live hosted CORS/quality or physical Android/iPhone result is recorded for this
redesign; its per-browser preview gate must not be described as general compatibility.

The same runner also owns the accessibility phase (`test/browser/a11y-keyboard.probe.mjs`):
real Tab, Enter and Escape keys on every panel, and 48px targets under emulated touch. Run it
alone with `node tools/browser-policy-check.mjs --a11y-only`. A new control has to fit the
reading order and the coarse-pointer target size. A new colour pair belongs in
`test/browser/contrast.browser.mjs`.

## Pull requests

Small and focused is easier to merge. CI runs the suite on Linux, Windows and macOS across
Node 22 and 24 — Windows especially, because two toolchain bugs in this repo's short life
were Windows-only path handling.

Say how you verified it. If it touches voice, storage or a provider, name the browser.

`main` only changes through a PR with a green `ci` check, and a PR needs one approving
review from a maintainer before it merges. Two rulesets enforce that, mirrored in
`.github/ruleset.json` (PR, `ci`, no force-push or deletion — applies to everyone) and
`.github/ruleset-review.json` (the approval — maintainers may merge their own PRs without
it, but never without `ci`).

Label the PR before it merges. Release notes are generated from labels — `providers`,
`voice`, `bug`, `enhancement`, `documentation` — and an unlabelled PR lands under
"Everything else" for good. `.github/release.yml` has the full list.

## Cutting a release

Use the shared [release skill](.claude/skills/release/SKILL.md). It owns the detailed
preflight, publication and verification procedure; do not maintain a second checklist here.

The [privacy policy](guide/privacy-policy.html) must have a confirmed public contact
before a release is prepared or tagged. `node tools/check-privacy-policy.mjs`
refuses an unresolved placeholder, missing contact or example address; the release
workflow runs the same guard. Only the maintainer supplies and confirms the address.

| Explicit invocation | What it does |
|---|---|
| `/release prepare <version>` | Updates both version files, validates the change, and opens a new PR. It does not publish. |
| `/release publish <version> <merged-pr>` | For an authorised upstream writer, verifies the merged commit and green CI before pushing its tag. |
| `/release verify <version>` | Checks the actual workflow, release assets, checksums, archive versions and public image. |

Use a confirmed stable version without `v`; the Git tag adds that prefix, the container
tag does not. The version lives in **two** files, `package.json` and `src/version.js`,
because a browser ES module cannot import JSON without an import attribute and this project
has no build step to inline it. They must change together.

If a maintainer owns publication, hand them the preparation PR and leave review, merge
and tagging to them. The tag must point to the PR's actual merged commit, with successful
upstream CI, never to an unvalidated `HEAD` or the pre-merge branch. Then invoke verification.
Do not move an existing tag, overwrite assets, publish from the fork, or claim a ready PR
is a released version.

The tag is the whole trigger — there is no manual release button, deliberately.
`.github/workflows/release.yml` is split in two on purpose: a read-only `verify` job checks
that the tag, `package.json` and `src/version.js` agree, proves the tag points into `main`,
and reruns the full ladder with `npm run test:all`. Only then does the write-enabled
`release` job publish anything, because a release whose archive reports a different version
than its tag is a support problem forever afterwards.

The APK comes from a separate `android-apk` job in the `release` environment, the only job
in the workflow that references the four keystore secrets (`ANDROID_KEYSTORE_B64`,
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`). It fails rather
than skips when one is missing, and `tools/apk-cert-pin.mjs` fails it unless
`apksigner verify --print-certs` shows exactly one signer whose SHA-256 equals the fingerprint
pinned in [SECURITY.md](SECURITY.md#release-signing-certificate). While that pin reads
`PENDING`, every release fails at this job and nothing at all is published, by design.

The workflow text alone does not keep the secrets in that job; the repository settings do.
Before the first signed tag the maintainer:

- creates the release key and stores the four values as **environment secrets on the
  `release` environment**, never as repository secrets (a repository secret is readable by
  any job in any workflow that names it);
- restricts the `release` environment's deployment branches and tags to tags matching
  `v*.*.*`, so no branch, pull request or other workflow can run a job in it (a required
  reviewer is optional);
- replaces `PENDING` in SECURITY.md with the key's certificate SHA-256, through a PR.

Prerelease tags (`v1.2.3-rc.1`) are not supported while the APK is required: the Android
build reads `package.json`'s version and accepts only plain `MAJOR.MINOR.PATCH`, so such a
version would already have turned CI's `android` job red on `main`. There is no skip for
them.

The tag produces two things: a GitHub release with `.tar.gz`, `.zip`, the signed
`ideaforge-VERSION.apk` and `SHA256SUMS` covering all three, the archives built straight from
the tagged tree with `git archive`; and a container image at
`ghcr.io/jaypetez/ideaforge`, tagged with the version and — unless the tag is a prerelease —
`latest`.

Pages deploy is a different pipeline. `.github/workflows/pages.yml` runs only after a
successful `CI` workflow run for a push to `main`, checks that the `ci` aggregator succeeded
for that SHA, refuses to deploy if that SHA is no longer the exact tip of `main`, and uploads
the output of `node tools/assemble-site.mjs dist` — copied publishable files, not a build.

**Check the package is publicly pullable after the first release.** Publishing from a public
repository with `GITHUB_TOKEN` made it public here without anyone touching a setting — an
anonymous token against `ghcr.io/v2/jaypetez/ideaforge/manifests/<version>` answered 200 —
but that is a registry default rather than a promise. If it is private, a maintainer must
correct the package visibility before announcing a public image. The skill verifies it
with anonymous Registry HTTP requests, or an empty, isolated Docker configuration when
Docker is available. Never log out of the user's normal registry session to perform this
check. Verify the expected platforms, version and source revision, not merely an HTTP 200.
