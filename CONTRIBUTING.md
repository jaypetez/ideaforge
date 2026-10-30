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
- `review-ideaforge-change` for a high-confidence read-only review;
- `review-ideaforge-before-pr` for the adversarial review that must pass before a PR;
- `release` for explicitly requested preparation, publication, or verification;
- `work-issues` for explicitly invoked backlog work, one issue at a time, merging only on
  a green `ci` check.
- `ideaforge-assist` for explicit Lite, Full, Ultra and Off session/default controls;
- `audit-ideaforge-codebase` for an explicitly requested, read-only map-first audit;
- `audit-android-app` for an explicitly requested native bug audit;
- `ideaforge-debt` for a read-only shortcut/upgrade-trigger ledger;
- `ideaforge-gain` for counted diff/debt facts, not invented savings;
- `ideaforge-assist-help` for the source-linked process reference.

Invoke [`/audit-android-app`](.claude/skills/audit-android-app/SKILL.md) for a main-only,
report-only Android scan using source review, existing tests and available device evidence.
It reuses the shared audit criteria and `android-verify`, reports every confirmed,
deduplicated root cause without a cap, and discloses missing runtime/physical evidence.
It does not fix the app, file issues or resume backlog work. The general audit's existing
20-finding cap is unchanged.

The review agents are thin client adapters around the two shared review skills. They
deliberately have no shell or edit tool, so supply an attached/pasted diff or readable
PR/source-control context. Their product-qualified names avoid relying on undocumented
precedence where VS Code discovers both agent directories.

Before any PR, the [pre-PR review](.claude/skills/review-ideaforge-before-pr/SKILL.md)
writes the exact branch diff to a file those agents can read
(`node tools/assistant.mjs review prepare`), has two of them review it blind (plus the
documentation reviewer when documentation changed), and has a fresh one challenge each
finding. Confirmed findings are fixed and the new HEAD reviewed
again, for at most three rounds. A passing round is recorded per HEAD
(`node tools/assistant.mjs review record --head <sha> --result clean`, naming the commit
that was reviewed); a waiver needs the user's
explicit request and reason. Claude Code's `PreToolUse` and Copilot CLI's `preToolUse`
hooks refuse `gh pr create` and `gh pr new` until that record matches a clean HEAD.

The [release skill](.claude/skills/release/SKILL.md) is explicitly invoked, not selected
automatically. It separates release preparation from publication and verification; see
[cutting a release](#cutting-a-release). Preparing a PR is not permission to merge it
or push a production tag.

The [work-issues skill](.claude/skills/work-issues/SKILL.md) is explicitly invoked too. It
takes the next unblocked `agent-ready` issue, proves each acceptance criterion, runs the
adversarial pre-PR review on the exact diff, and squash-merges only when `ci` is green on
the tested head. It never tags, releases, or touches settings, and it labels anything that
needs a device, a secret, paid usage or a decision `needs-human`.

Single-issue work stays direct. An explicitly requested multi-issue run uses a lean
coordinator and one fresh sequential implementation worker per issue through the client's
ordinary task tool, not fleet mode or a new custom agent. The worker owns implementation,
reviews, final exact-head evidence, draft PR and actual acceptance; the coordinator verifies
the compact handoff and real results, then owns the unchanged guarded merge and closeout.
Same-issue corrections return to the same worker; the next starts only on verified green
main within the requested queue/dependencies. See the skill for the canonical protocol and
the [child guidance limits](docs/ASSISTANT-PROCESS.md#child-inheritance-limits) for unsupported
lifecycle hooks. This changes neither model settings nor emulator concurrency.

The skill separates focused development proof from one clean-head final validation.
It records the reviewed diff, review/fix round and tested SHA so resume does not restart
completed work; three unsuccessful rounds escalate rather than loop. Native-only changes
use native visual evidence, not regeneration of unchanged web screenshots. Full pre-push
checks and the exact-head `ci` gate still apply; see the skill for evidence invalidation
and the [shared test ladder](AGENTS.md#the-ladder) for focused commands.

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
URLs or secrets to enable it. These adapters read one canonical rule section and keep
mode metadata in the ignored `.ideaforge-assist/` directory, isolated by worktree, client
and native session. Pre-PR review diffs and records live there too, per worktree and
commit and shared by every session. They make no model calls and request no secrets. The pre-PR review gate is their only tool decision, and it only ever refuses.
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
Forward the injected `beforeSample` authorisation hook before every inference attempt,
including internal retries, but not static key checks or model-list reads. A `consent`
error must propagate rather than become checklist fallback. The app binds the original
destination/grant permit; an adapter must not acquire a fresh agreement for old work.
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
  '-Pandroid.enableAdditionalTestOutput=true' `
  '-Pandroid.testInstrumentationRunnerArguments.class=io.github.jaypetez.ideaforge.ux.UxCaptureDeviceTest#requestedUxEvidenceIsRenderedCompleteAndReachable' `
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

The optional workflow's default/full route explicitly selects that full-inventory method
on both visual devices, without an unused empty shell argument. After each Gradle command,
`tools/check-android-device-results.py` requires the actual selected passing test in that
device's JUnit XML; zero discovery, a different method, failures and skips fail the step
even if Gradle exited successfully. Run its focused regressions from the repository root
with `python test\android-device-results.test.py`. The workflow also checks the clean
checkout SHA before and after captures. Complete requested manifests and inspected original
PNGs in `android-ux-evidence-<SHA>` are still required; a nonzero test count alone is not
acceptance.
Default/full has a bounded 60-minute overall job budget: a verified complete compact run
took over 21 minutes and left too little of the old budget for expanded evidence.
Focused routes retain 40 minutes, and per-capture/test deadlines are unchanged.

Use a standard rendering-enabled image, not ATD, for pixels. Real navigation and isolated
Compose voice fixtures are explicitly distinguished; native-only `voice-writing` is rejected
by the web runner. [DESIGN](docs/DESIGN.md#development-fixtures-and-captures) documents native
phase/route mappings and the prerequisite fixes the matrix exposed. A focused green run
is not a green full matrix; inaccessible actions, an unavailable read presented as empty,
and black PNGs must remain failures.

For complete native makeover acceptance, dispatch `android-device.yml` with
`-f evidence=makeover` on the intended clean feature ref. This explicitly selects
`UxCaptureDeviceTest#requestedNativeMakeoverAcceptance` with `uxMakeoverProbe=true`.
It runs sequential rendering-enabled phone/tablet tasks; their joined canonical journeys
avoid a duplicate ATD boot within the unchanged 40-minute job budget. Ordinary/default
and nightly ATD coverage is unchanged. It adds compact/expanded/short-landscape geometry at font scales 1.0/1.3/2.0,
both themes, actual IME and installed system-navigation modes. The joined journey
uses real onboarding/navigation, canonical answers/wrap, native document/share actions,
Copy, same-session reopen/rotation, Settings and backup/import recovery.
Only model/check/speech/service effects are scripted; voice phase fixtures are labelled
isolated and OS permissions are not changed. The existing scripted permission-result
tests cover microphone denial and nonfatal notification denial, not physical recognition.
See [DESIGN's exact commands and evidence contract](docs/DESIGN.md#complete-native-makeover-acceptance).
Require actual selected-test execution and both passed clean-SHA `ux-makeover` manifests;
read back artifacts and inspect app, native-system and reachability PNGs. Cleanup also
restores the clipboard and removes only owned documents/share files. Manual TalkBack,
switch access, physical voice and live hosted speech remain explicitly unperformed.

Play listing source lives in `android/fastlane/metadata/android/en-US/`. Keep its text
plain, name compatible providers without their logos, and retain the non-affiliation
notice. The changelog filename follows `versionCodeOf` in `android/app/build.gradle.kts`,
which reads the version from `package.json`; this is not permission to publish to Play.

Generate listing artwork and phone screenshots on a feature ref with:

```powershell
gh workflow run android-device.yml --ref '<feature-branch>' -f evidence=play-listing
```

Unlike the full UX mode, this explicit selection runs only the rendering-enabled
`pixel2Api34Visual` managed device (standard AOSP API 34, Pixel 2). It retains the native
1080x1920 display rather than resizing the existing UX profiles. The opt-in
`UxCaptureDeviceTest#requestedPlayListingAssets` reuses the real debug scripted
walkthrough and recorder for seed, selected-suggestion and Result frames in both themes,
at font scale 1.0. It retains geometry/theme/rendering checks and requires every frame.
Whole scripted answers use the real editor's SetText action, with empty-draft, exact-text
and enabled-action checks; they do not certify physical typing or IME quality.
No model key, real provider request or microphone is used. This is emulator capture,
not physical-device or full-matrix acceptance.

To run the same task with a suitable local managed-emulator setup, from `android\`:

```powershell
$sha = git rev-parse HEAD
.\gradlew.bat :app:pixel2Api34VisualDebugAndroidTest `
  '-Pandroid.enableAdditionalTestOutput=true' `
  '-Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect' `
  '-Pandroid.testInstrumentationRunnerArguments.class=io.github.jaypetez.ideaforge.ux.UxCaptureDeviceTest#requestedPlayListingAssets' `
  '-Pandroid.testInstrumentationRunnerArguments.playListingProbe=true' `
  '-Pandroid.testInstrumentationRunnerArguments.uxCapture=walkthrough' `
  '-Pandroid.testInstrumentationRunnerArguments.uxCaptureWindow=compact' `
  "-Pandroid.testInstrumentationRunnerArguments.uxCaptureSha=$sha"
```

Read back the workflow's `android-ux-evidence-<SHA>` and `android-device-reports`
artifacts: require actual execution of the selected test with no failure, a passed
`play-listing/manifest-compact.json` with the intended clean SHA and every requested frame,
and inspect the light/dark PNGs. `play-listing/images/` holds the store copies:
`icon.png` (512x512 RGBA), `featureGraphic.png` (1024x500 opaque RGB), and numbered
`phoneScreenshots/` PNGs (1080x1920 opaque RGB). The native `PlayListingAssets.kt`
renders the installed adaptive icon's background and foreground at Android's layer bounds,
without the device mask (Play supplies its own); phone conversion only removes
an unused alpha channel, with no resize, crop or replacement of app pixels. Preserve the
original recorder images and manifest as evidence, then copy only the generated `images/`
tree into the metadata directory. Never promote a failed or development-dirty run.

After copying, from the repository root run `node tools\check-play-listing.mjs`.
The checker rejects absent assets, out-of-limit text and PNG dimensions, wrong bit depth
or transparency, missing themes and incomplete image data. It checks the current
versionCode's changelog, not a stale version's file. Keep the generated assets with the
listing source; `npm test` runs the same checker against the committed current-version
inventory as well as adversarial boundary fixtures. A passing header/limit check never
replaces the native manifest and visual inspection.

For launcher-only changes, use the same workflow with `-f evidence=launcher`. This selects
`LauncherIconDeviceTest#requestedLauncherShowsMaskedAndThemedInstalledIcon` on
`pixel2Api34Visual`, not ATD or the full app matrix. It uses the actual AOSP Launcher3 home
screen, places the installed app there if needed, and reads/changes/restores the launcher's
own themed-icons setting through its customization provider. This route is for an owned
managed emulator, not a shared launcher or a physical phone. It captures full, unmodified
display frames with the device mask and with themed icons enabled; it is not a drawn
contact sheet or a general launcher-compatibility claim.

Require a passed `launcher-icon/manifest-compact.json`, the exact clean tested SHA, actual
selected-test execution, and inspect `launcher-masked.png` and `launcher-themed.png`.
The probe rejects a missing monochrome layer, an unmasked square or an unchanged brand
background after theming. It also writes the two launcher-derived Play artwork PNGs under
`launcher-icon/images/`. After inspecting and checking them, copy only `icon.png` and
`featureGraphic.png` into the metadata images directory when they changed. Preserve the
existing lossless phone frames when no app-screen input changed; launcher artwork does
not require recapturing the interview. Re-run the listing checker on the complete inventory.

For the Play microphone foreground-service declaration, dispatch the opt-in native recording:

```powershell
gh workflow run android-device.yml --ref '<feature-branch>' -f evidence=microphone-demo
```

`MicrophoneDemoDeviceTest#requestedMicrophoneForegroundServiceVideo` uses the existing
private-store fixture and debug scripted interview model, not a real inference key.
`pixel2Api34Voice` is a standard Google APIs API 34 image; the test reports installed TTS
and recognizer capabilities and fails if they are unavailable. Through the existing graph
test seam it explicitly selects `androidRecognizerFactory(..., RecognizerMode.DEFAULT)`:
the **real Android default recognition service**, not a scripted recognizer. This image's
advertised on-device engine failed its real en-US start; this recording does
not certify that preferred on-device branch. Native TTS, recognition/audio callbacks,
permission dialogs, controller, foreground service and notification actions remain real.
Use only a fresh owned managed emulator: the test requires ungranted microphone and
notification permissions and clicks their actual system Allow actions. It does not revoke
permissions, wipe data or uninstall an app on a shared device.

Gradle owns the ephemeral device, so the recording invokes the **same on-device
`screenrecord` utility as `adb shell screenrecord`** through
`UiAutomation.executeShellCommand`, with `--time-limit 55 --bit-rate 4000000`. It records
the real Interview → Hands-free interview → Allow → native hands-free listening → system
notification with Pause/Exit → notification Exit → same typed idea. Assertions require
actual non-silenced Android audio recording, recognizer audio callbacks, a real foreground
`DriveService` and notification, then their teardown with no answer or wrap-up submitted.
The private pre-agreed setup and scripted model are fixtures; default-service selection
is test-only, with no release demo mode or production voice-policy change.

Read back `android-microphone-demo-<SHA>` and `android-device-reports`. Require actual
nonzero selected-test execution, a passed `microphone-demo/manifest.json` with the exact
clean checkout SHA/profile, and the uploaded MP4's hash/bytes, codec, dimensions and actual
30–60 s duration. Android's `MediaMetadataRetriever`/`MediaExtractor` verify the actual
recording and decode the `video-*.png` frames at the recorded event times. Open those
frames **and watch the clip**, checking that both real Allow dialogs, the listening stage,
notification actions and same-idea Exit are visible. The `listening-system.txt` and
`exited-system.txt` snapshots retain actual service/audio/app-op/notification state.
An artifact or shell exit alone is not acceptance. This does not certify physical phones,
speech quality, screen-off survival, other profiles or Play approval.

Declaration copy is in the [Android guide](guide/android.html#foreground-service-declaration)
and [publishing walkthrough #171](https://github.com/jaypetez/ideaforge/issues/171).
Artifacts expire after 14 days; the maintainer retains the reviewed MP4 and uploads it to
unlisted YouTube, then supplies the durable video URL to Play Console. Neither this
workflow nor the agent publishes to YouTube or Play.

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
The paused capture waits for the injected service's admitted start before Pause: an
immediate Pause can legitimately cancel a still-pending Start. The controller uses
scripted speech and a fake service; it proves layout/identity,
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
The after matrix also includes `settings-http-lan` and `onboarding-http-lan`: real
form edits to a private HTTP server, with the warning and separate agreement visible.
These are scripted form captures, not a live LAN connection or physical-device result.
`-f evidence=consent-before` requires the agreement controls to be absent and records
the existing actions instead; use it only on the unchanged pre-consent application.
The recorder requires the complete requested matrix and actual checkout SHA.
Neither a focused result nor zero discovered visual tests certifies the default inventory.

For native onboarding/entry, dispatch `-f evidence=onboarding` on the frozen feature ref.
It selects `UxCaptureDeviceTest#requestedNativeOnboardingAndEntryKeepIntentAndRecovery`
with `uxOnboardingProbe=true`. Its fixed ten frames cover provider/hosted/LAN setup,
Voice/Type choice, permission-free typing, app-rationale cancellation, failed/pending
checks, manual-model recovery and unreadable keys across both windows/themes and
font scales. Actual XML, complete literal-SHA manifests and all PNGs are required.
The recovery capture requires an ungranted permission, never changes permission state,
and cancels before an OS request; output/service/checks are scripted, not phone proof.

For native Settings, `-f evidence=settings` selects
`UxCaptureDeviceTest#requestedNativeSettingsKeepSectionsAndDataBoundaries` with
`uxSettingsProbe=true`. The fixed matrix records all five sections, separate hosted
consents, unchanged Cancel/Forget data, actual held/refused scripted connection checks
and a labelled scripted backup-job failure. No file picker, hosted preview or real
provider is invoked. Require actual selected-method XML and complete clean-SHA
compact/expanded, light/dark, enlarged manifests/PNGs; it is not the full inventory.

For device voice selection, dispatch `-f evidence=device-voices` on the exact feature ref.
It selects the same Settings method with `uxSettingsProbe=true` and
`uxDeviceVoicesProbe=true`, reusing the private graph and strict recorder rather than a
new runner. The fixed seven states are engine default, long installed choice, actual
selection/recreation, pending engine read, error, empty list and retained missing choice.
Both rendering-enabled profiles run light/dark at actual font scales 1.0/1.3/2.0.
Require actual passing selected-method XML, both complete clean-SHA `ux-device-voices`
manifests and all original/reachability PNGs. The controlled engine also asserts subsequent
native speaker configuration/fallback. An eighth, explicitly isolated voice-stage frame
shows both the reported missing-device-voice warning and hosted fallback. These captures do not play real speech, request
microphone permission or prove physical voice quality, offline compatibility or TalkBack.

For native Result, `-f evidence=result` selects
`UxCaptureDeviceTest#requestedNativeResultKeepsDocumentAndCanonicalActions` with
`uxResultProbe=true`. Its fixed six-frame matrix covers long/scrolled/short documents,
checklist provenance, scripted failed synthesis and a genuinely pending session-lock
read. It captures the real MainActivity, not an isolated Result drawing, and never opens
the chooser, clipboard or document picker. Require all 72 compact/expanded/theme/font
records, actual selected-method XML, exact clean SHA and all PNGs before accepting it.
Canonical output/picker cancellation and IO failures still need their action/VM tests.

For native Ideas, dispatch `-f evidence=ideas` on the exact feature ref. It selects
`UxCaptureDeviceTest#requestedIdeasAreCompactReachableAndTruthful`, reusing the existing
recorder for nine declared states: ready/empty, unavailable, normal/100-row/archived/
filtered lists, long active detail and an actual pending SessionStore read. The injected
store remains the graph's only store; the read is released during cleanup, not replaced
with a fabricated loading flag. Compact/expanded, light/dark and font scales 1.0/1.3/2.0
require 54 accepted capture records per window. Inspect the selected test's nonzero XML
execution and complete SHA-matching manifests/PNGs; this is not the full UX inventory.

For native Interview, dispatch `-f evidence=interview` on the exact feature ref. It selects
`UxCaptureDeviceTest#requestedInterviewIsQuestionFirstKeyboardSafeAndTruthful` with
`uxInterviewProbe=true`. Eleven states cover seed/long/history/coverage, checklist,
busy/save failure, the actual open IME, an actual engine wrap offer, Writing and failed
synthesis. The wrap reuses the canonical typed walkthrough without fabricating UI state;
the ordinary chip walkthrough still verifies its canonical export. Both windows,
themes and three font scales require 66 accepted records per window. Inspect actual
selected-method XML, every requested manifest record and real PNGs. The focused subset
does not prove the full UX inventory, physical recognition or real permission UI.

For native voice presentation, dispatch `-f evidence=voice` on the exact feature ref.
It selects `UxCaptureDeviceTest#requestedVoicePhasesKeepCaptionsRecoveryAndControlsHonest`
with `uxVoiceProbe=true`. The fixed fourteen-state phase/recovery subset includes native
Writing, last-output fallback, optional-notification status and the real command disclosure.
Both windows/themes and three font scales require 84 records per window; compact also
includes six font-2 listening/paused/blocked landscape records, for 174 total.
The production rationale dialog and real permission-result callbacks have separate native
UI tests, not a single-window phase screenshot. All phase captures are labelled isolated-component/scripted semantic
UI, including the native completion-to-Result mapping. No microphone, actual permission
prompt, hosted request or service is started. Inspect actual selected-method execution,
complete exact-SHA manifests and PNGs; #89 remains the physical-device owner.
The isolated fixture's capture readiness also measures actual SystemUI clock and status
glyph pixels against its requested background. Both clusters need 4.5:1 core ink contrast
in two consecutive app-root samples, within the existing capture budget. Earlier attempts,
their original display PNGs and requested window flags remain diagnostics; flags or an
app-window PixelCopy alone cannot establish SystemUI paint. This does not certify production
system bars or physical phones.

For manual dictation, dispatch `-f evidence=dictation` on the exact feature ref. It selects
`UxCaptureDeviceTest#requestedManualDictationKeepsLiteralDraftAndExplicitSend` with
`uxDictationProbe=true`, reusing the existing private graphs and strict recorder.
The fixed seven states are ready, listening, stopped draft, engine error, long literal text,
report cancellation and Settings without the old global switch. Both rendering-enabled
profiles run light/dark at actual font scales 1.0/1.3/2.0: 42 records per window.
The real manual controller and `PRESS_TO_TALK` recognizer consume controlled callbacks.
On these owned managed devices only, a missing microphone grant is accepted through the
actual system Allow action. No notification permission, real recognizer, audio recording,
spoken output, service or provider request runs. Require actual passing selected-method
XML, complete clean-SHA `ux-dictation` manifests and inspected original/reachability PNGs.
This is neither full-inventory acceptance nor physical #89, voice quality or manual
TalkBack proof; native/Whisper eligibility, original grants and stale callbacks have
separate focused tests.

For native AI-output reporting, dispatch `-f evidence=reporting` on the exact feature ref.
It selects `UxCaptureDeviceTest#requestedContentReportsKeepLocalPayloadAndQuestionIdentity`
with `uxReportingProbe=true`, using the existing private-store routes and strict recorder.
The fixed journey covers generated questions/suggestions, the localized dialog, a genuinely
held session-lock save, a refused report write with retry, local export, no-answer
replacement, earlier history, Result, the real voice stage and a Back-to-Ideas report
that pauses and refreshes the surviving app-graph drive. It uses compact/expanded
windows, both themes and font scales 1.0/1.3/2.0.
Require the selected passing JUnit method, complete clean-SHA `ux-reporting` manifests
and inspected original/reachability PNGs from `android-ux-evidence-<SHA>`. Voice
speech/service are scripted; no recognizer, live provider or email composer is invoked.
This is neither full UX acceptance nor Play approval, physical voice or manual TalkBack
evidence. The optional email action has separate intent/payload tests and uses only the
canonical policy's confirmed contact; no publication/account setup is needed.

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
interview with tap-start/tap-stop dictation into an editable draft, plus a hands-free
voice interview: native speech capture (#91) and the voice stage,
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

Native model-catalogue checks in `OpenAiCompatProvider` require well-formed JSON and a
supported array, `data` or `models` envelope before `ProviderCheck` can report success.
A valid empty catalogue is still a successful read; name filtering remains the shared
`parseModelList` contract. Catalogue nesting is bounded so an excessively deep response
becomes `bad_response` rather than exhausting a native worker's stack.

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

`PlayRequirementsTest` reads the effective debug and release merged manifests and fails if
either targets below API 36. It uses Gradle artifact inputs, not a source-text SDK check.

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
`:app:bundleRelease` uses the same signing inputs and versionCode, and is finalised by
`:app:verifyReleaseBundle` with the same checks on the actual AAB. The release workflow
maps separate upload-key secrets into these existing Gradle inputs for the bundle; it
does not change which certificate signs the APK.

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
After building, `tools/test-android-apk-alignment.py` runs SDK
`zipalign -c -P 16 -v 4` on the actual APK. It appends a copy of a real arm64 library as
`unaligned.so` in a separate APK, four-byte aligned but not 16 KB aligned, and requires
`zipalign` to reject that entry. The original signed APK is untouched and the planted copy
is deleted. Run the same check from the repository root with SDK Build-Tools 35 or newer:

```sh
python3 tools/test-android-apk-alignment.py "$ANDROID_HOME/build-tools/<version>/zipalign" android/app/build/outputs/apk/debug/app-debug.apk
```

This guards ZIP placement of native libraries, not ELF segment alignment or runtime behavior
on a 16 KB device. The existing release-manifest test still refuses debug-only permissions
and probe components.
The same job runs `bash tools/test-android-packages.sh` with two generated disposable keys:
the same alignment check and planted control on the actual signed, minified release APK,
actual APK/AAB signing, pinned bundletool validation, matching versionCode, and rejection of
missing upload secrets, reused keys, unsigned bundles and a corrupt tool download.
It neither reads production signing secrets nor publishes these test packages.

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

The shared `launchChrome` in `tools/lib/harness.mjs` uses `--disable-extensions` and
`--disable-component-extensions-with-background-pages` for its temporary profiles. A fresh
profile alone can still receive managed content scripts. These launch flags do not change
installed browser settings; autoplay, microphone and trusted-gesture policy still belong
to each calling harness.

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
in the workflow that references the four app-signing secrets (`ANDROID_KEYSTORE_B64`,
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`). It fails rather
than skips when one is missing, and `tools/apk-cert-pin.mjs` fails it unless
`apksigner verify --print-certs` shows one app-signing certificate across all reported signers and SDK
ranges, whose SHA-256 equals the fingerprint
pinned in [SECURITY.md](SECURITY.md#release-signing-certificate). While that pin reads
`PENDING`, every release fails at this job and nothing at all is published, by design.

The separate read-only `android-aab` job receives only `ANDROID_UPLOAD_KEYSTORE_B64`,
`ANDROID_UPLOAD_KEYSTORE_PASSWORD`, `ANDROID_UPLOAD_KEY_ALIAS` and
`ANDROID_UPLOAD_KEY_PASSWORD` from that same tag-restricted environment. It signs
`:app:bundleRelease` with the upload key, checks the actual package for debug-only code
and fixtures, validates it with checksum-pinned bundletool, verifies its signature and
refuses an upload certificate equal to the published app-signing pin. It stages the
bundle's own R8 `mapping.txt`, not a debug or APK mapping. Missing upload secrets fail
loudly; there is no fallback to the app-signing key.

The workflow text alone does not keep the secrets in that job; the repository settings do.
Before the first signed tag the maintainer:

- creates the release key and stores the four values as **environment secrets on the
  `release` environment**, never as repository secrets (a repository secret is readable by
  any job in any workflow that names it);
- creates a different Play upload key and stores its four `ANDROID_UPLOAD_*` values in
  that environment; enrols the app-signing key with Play separately if both distribution
  paths must use the same certificate;
- restricts the `release` environment's deployment branches and tags to tags matching
  `v*.*.*`, so no branch, pull request or other workflow can run a job in it (a required
  reviewer is optional);
- replaces `PENDING` in SECURITY.md with the key's certificate SHA-256, through a PR.

Prerelease tags (`v1.2.3-rc.1`) are not supported while the APK is required: the Android
build reads `package.json`'s version and accepts only plain `MAJOR.MINOR.PATCH`, so such a
version would already have turned CI's `android` job red on `main`. There is no skip for
them.

The tag produces two things: a GitHub release with `.tar.gz`, `.zip`, the signed
`ideaforge-VERSION.apk`, upload-signed `ideaforge-VERSION.aab`, its `mapping.txt`, and
`SHA256SUMS` covering all five payloads, the archives built straight from
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
