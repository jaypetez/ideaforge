# Documentation source map

Use this map to find the owner of a claim before changing its wording. The nearest executable
source wins over prose, and a generated artifact wins over a description of that artifact.

## Canonical repository references

- **Working and validation loop.** Sources:
  [`AGENTS.md`](../../../../AGENTS.md) and
  [`package.json`](../../../../package.json). Re-check `README.md`, `CONTRIBUTING.md`, the
  development guide, and skills.
- **Implementation invariants.** Sources:
  [`CLAUDE.md`](../../../../CLAUDE.md), the owning modules, and their tests. Re-check
  `docs/ARCHITECTURE.md`, focused skills, and the development guide.
- **Architecture and extension seams.** Sources:
  [`docs/ARCHITECTURE.md`](../../../../docs/ARCHITECTURE.md) and `src/`. Re-check
  `CONTRIBUTING.md` and the development guide.
- **Purity boundary.** Source:
  [`tools/lint-purity.mjs`](../../../../tools/lint-purity.mjs). Re-check `AGENTS.md`,
  `CLAUDE.md`, `CONTRIBUTING.md`, and the development guide.
- **Available commands.** Source:
  [`package.json`](../../../../package.json). Re-check every command block in repository
  documentation and skills.
- **Public guide shell and navigation.** Sources:
  [`guide/index.html`](../../../../guide/index.html) and
  [`guide/assets/guide.css`](../../../../guide/assets/guide.css). Re-check every guide page.
  Colours follow [`src/ui/app.css`](../../../../src/ui/app.css); guide fonts remain system
  fonts, with no remote assets or guide JavaScript.
  Mobile navigation keeps the native disclosure with a bounded, focusable scrolling list;
  desktop keeps the full sidebar. The guide browser probe checks initial phone heading,
  introduction and action geometry before interacting with navigation.
- **Public guide runtime contract.** Sources:
  [`test/browser/guide.browser.mjs`](../../../../test/browser/guide.browser.mjs),
  [`tools/assemble-site.mjs`](../../../../tools/assemble-site.mjs), and
  [`sw.js`](../../../../sw.js). Re-check the page list, relative assets, CSP, and
  service-worker wording.
- **CI gate.** Source:
  [`.github/workflows/ci.yml`](../../../../.github/workflows/ci.yml). Re-check `AGENTS.md`,
  `CONTRIBUTING.md`, the development guide, and the release procedure.
- **Pages deployment.** Sources:
  [`.github/workflows/pages.yml`](../../../../.github/workflows/pages.yml) and
  [`tools/assemble-site.mjs`](../../../../tools/assemble-site.mjs). Re-check the development
  guide, architecture, and release procedure.
- **Release publication.** Sources:
  [`.github/workflows/release.yml`](../../../../.github/workflows/release.yml),
  [release-note configuration](../../../../.github/release.yml),
  [`package.json`](../../../../package.json), and
  [`src/version.js`](../../../../src/version.js). Re-check `CONTRIBUTING.md`, the development
  guide, and [`release`](../../release/SKILL.md).
- **Security and privacy.** Sources:
  [`SECURITY.md`](../../../../SECURITY.md), [`index.html`](../../../../index.html),
  `src/store/`, and `src/providers/`. Re-check the README, privacy guide, and provider guide.
- **Setup disclosures.** Sources: [`index.html`](../../../../index.html) (`model-settings`,
  `speech-options`) and [`src/ui/app.js`](../../../../src/ui/app.js) (`onProviderChange`,
  `onSpeechChange`). Re-check setup instructions against the actual summary labels.
  Model options opens for local providers; Voice and speaking pace opens for OpenAI
  speech. Primary connection, input/output choices and start actions remain outside them.
- **Start and Settings views.** Sources: [`index.html`](../../../../index.html)
  (`#panel-setup` `data-view`, `.start-only`, `.settings-only`, `#connect`) and
  [`src/ui/app.js`](../../../../src/ui/app.js) (`show`, `connectionReady`, `renderConnect`).
  Start holds the connection card, start actions and recent ideas; Settings holds the five
  groups named by `#settings-index`: Connection, Listen and speak, Appearance, Data (backup
  and import) and About. Re-check the getting-started, providers and library guides, the
  README and the 01-setup / 10-settings captures. `test/docs.test.mjs` ties the group names
  and their count to the README and the getting-started guide.
- **App shell, navigation and opening destination.** Sources:
  [`index.html`](../../../../index.html) (`.bar`, `#b-library`, `#b-nav-new`,
  `#b-settings`) and [`src/ui/app.js`](../../../../src/ui/app.js) (`show`, `navigateTo`,
  `boot`). The three destinations are a top bar on a wide screen, a bottom bar on a phone,
  and a back arrow on Interview and Done. The app opens on Ideas once any idea is stored,
  otherwise on Start. Re-check getting started, the library guide and the README.
- **Theme choice.** Sources: [`src/store/prefs.js`](../../../../src/store/prefs.js)
  (`themePreference`), [`src/ui/theme.js`](../../../../src/ui/theme.js) and
  [`src/ui/theme-boot.js`](../../../../src/ui/theme-boot.js). System, Light or Dark under
  Appearance; a device preference, never in backups. Re-check getting started, privacy and
  `docs/DESIGN.md`.
- **Interview presentation.** Sources: [`index.html`](../../../../index.html) (`#thread`,
  `#question-card`, `#wrap-offer`, `#composer`, `#meter`, `details.cov`) and
  [`src/ui/app.js`](../../../../src/ui/app.js) (`renderThread`, `renderWrapOffer`,
  `renderMeter`). The wrap-up offer is a card above the composer shown once; the composer's
  Write it up appears after four questions. Re-check getting started, interviews and
  coverage, the README and its 02-05 alt text.
- **Messages.** Sources: `#toasts` in [`index.html`](../../../../index.html) and `fail` /
  `dismissError` in [`src/ui/app.js`](../../../../src/ui/app.js). Notes hide themselves
  and errors stay until dismissed, retried or the screen changes (`show()` clears both). Both sit at the bottom of Start, Settings and Ideas but
  at the top of a phone's Interview and of the voice stage, so guide wording that names a
  position must fit the screen it describes. Re-check
  troubleshooting, interviews and the library guide's import steps.
- **Library cards and the result screen.** Sources:
  [`src/ui/library.js`](../../../../src/ui/library.js) (`renderActions`, `renderCard`, the
  detail pane), [`src/ui/menu.js`](../../../../src/ui/menu.js) and `layoutDoneActions` in
  [`src/ui/app.js`](../../../../src/ui/app.js). One main button per card and on Done, with
  the rest in a More actions menu. Re-check the library guide, getting started, interviews
  and the README's 06/07/11 alt text.
- **Voice stage, entry and lifecycle.** Sources:
  [`index.html`](../../../../index.html),
  [`src/ui/app.js`](../../../../src/ui/app.js) (`startInterview`, `pauseVoice`, `resumeVoice`,
  `exitVoice`, `sessionWork`), and
  [`src/ui/voice-stage.js`](../../../../src/ui/voice-stage.js). Re-check getting started,
  mobile and voice, troubleshooting, the README and architecture. The stage is in-place;
  Pause/Exit are not interview reducers.
- **Commands, turn-taking and real levels.** Sources:
  [`src/core/driving.js`](../../../../src/core/driving.js) (`COMMANDS`, `parseSpeech`),
  [`src/runtime/drive.js`](../../../../src/runtime/drive.js),
  [`src/voice/index.js`](../../../../src/voice/index.js),
  [`src/voice/mic-meter.js`](../../../../src/voice/mic-meter.js), and
  [`src/voice/recorder.js`](../../../../src/voice/recorder.js). Re-check the mobile/voice
  guide and the no-barge-in, unavailable-meter and draft-retention wording.
- **Spoken output and its enablement gate.** Sources:
  [`src/voice/speak.js`](../../../../src/voice/speak.js),
  [`src/voice/output.js`](../../../../src/voice/output.js),
  [`src/voice/playback.js`](../../../../src/voice/playback.js),
  [`src/providers/tts.js`](../../../../src/providers/tts.js) (`TTS_PRESETS`), and
  [`src/ui/app.js`](../../../../src/ui/app.js) (`previewSpeech`, `readSpeechFromForm`).
  The UI owns consent; output owns the no-fallback check. Re-check the README, providers,
  privacy and troubleshooting. Registry entries, mock tests and an individual user
  preview do not establish universal CORS, voice quality or device compatibility.
- **Separate speech credentials and preferences.** Sources:
  [`src/store/secrets.js`](../../../../src/store/secrets.js) (`withTts`, `hasAnyKey`),
  [`src/store/prefs.js`](../../../../src/store/prefs.js) (`speechPreferences`), and
  [`src/core/backup.js`](../../../../src/core/backup.js). Re-check key reuse, Forget,
  encryption limits, local/network labels and the absence of keys/audio/prefs in backups.
- **Browser policy evidence.** Sources:
  [`tools/browser-check.mjs`](../../../../tools/browser-check.mjs),
  [`tools/browser-policy-check.mjs`](../../../../tools/browser-policy-check.mjs), and
  [`test/browser/audio-policy.probe.mjs`](../../../../test/browser/audio-policy.probe.mjs),
  plus the accessibility phase in
  [`test/browser/a11y-keyboard.probe.mjs`](../../../../test/browser/a11y-keyboard.probe.mjs)
  and [`test/browser/contrast.browser.mjs`](../../../../test/browser/contrast.browser.mjs).
  Re-check delivery guidance: real Web Audio/trusted-gesture checks are separate from the
  synthetic-media/autoplay-bypassed suite, and neither is physical-mobile or live-provider
  evidence. The main browser harness reports assertions through a CDP binding rather than
  page fetch, so the reporting channel is independent of the service worker under test.
  Missing or malformed reporting still fails the run.
- **Generated screenshot inventory.** Sources:
  [`tools/screenshots.mjs`](../../../../tools/screenshots.mjs) (every `app.shot` call) and
  [`test/docs.test.mjs`](../../../../test/docs.test.mjs), which fails when the harness's
  shot list, the expected inventory and this list disagree. Every stem is a light/dark pair:

  | Stem | What it captures | Shown in |
  |---|---|---|
  | `01-setup` | Start on a first visit, connection card unfolded | README, getting started |
  | `02-first-question` | the seed question in the typed interview | README, getting started |
  | `03-mid-interview` | thread, bridge, chips, meter and composer | README, guide index |
  | `04-coverage` | the same turn with the Coverage list open | README, interviews |
  | `05-ready-to-wrap` | the inline wrap-up card | README, interviews |
  | `06-refined-prompt` | Done: rendered result, Share .md and More actions | README, interviews |
  | `07-ideas-library` | the library at a narrow width, one card | README, Ideas library |
  | `08-voice-listening` | the voice stage listening, phone width | README, mobile and voice |
  | `09-voice-paused` | the voice stage paused, phone width | README, mobile and voice |
  | `10-settings` | Settings, all five groups | README, getting started |
  | `11-library-wide` | the list/detail library at a fixed 1280x800 viewport | README, Ideas library |

  `11-library-wide` is not fitted, so no clip gate guards it. Both voice states belong in
  the README and mobile/voice guide, with descriptive alt text. Regenerate; never hand-edit
  PNGs or the worked example. The setup capture's bounds check must retain both start
  controls and the footer and keep Start within 1100px, and the Settings capture must keep
  its Data and About groups; fix layout in the app, not by hiding controls for shots.
- **Guide page inventory.** Sources: `GUIDE_PAGES` in
  [`test/docs.test.mjs`](../../../../test/docs.test.mjs) and the page list in
  [`test/browser/guide.browser.mjs`](../../../../test/browser/guide.browser.mjs). The pages,
  in navigation order: `index.html` (overview), `getting-started.html` (Start, Settings,
  first interview, result), `interviews-and-coverage.html` (engine, meter, wrap-up,
  fallback), `ideas-library.html` (library, backup, import, sharing),
  `mobile-and-voice.html` (voice stage, installation, dictation, hands-free),
  `android.html` (native Android alpha: sideloading, providers, backups, status),
  `providers.html` (inference, transcription, speech), `local-models-and-docker.html`,
  `privacy-and-security.html`, `troubleshooting.html` and `development-and-delivery.html`.
  `test/docs.test.mjs` fails when a page is missing from this list.
- **Native Android app.** Sources:
  [`android/app/build.gradle.kts`](../../../../android/app/build.gradle.kts) (SDK levels,
  application id, `.debug` suffix, version from `package.json`),
  [`android/gradle/libs.versions.toml`](../../../../android/gradle/libs.versions.toml) (AGP,
  Kotlin and every library version),
  [`android/gradle/wrapper/gradle-wrapper.properties`](../../../../android/gradle/wrapper/gradle-wrapper.properties),
  [`AndroidManifest.xml`](../../../../android/app/src/main/AndroidManifest.xml) (permissions,
  backup off), the onboarding and settings strings in
  [`strings.xml`](../../../../android/app/src/main/res/values/strings.xml), the `android` job in
  [`ci.yml`](../../../../.github/workflows/ci.yml), the separate, non-required
  [`android-device.yml`](../../../../.github/workflows/android-device.yml) (the instrumented
  walkthrough on the managed device declared in `app/build.gradle.kts`), and the open Android
  issues for what is
  not built yet. Re-check `guide/android.html`, the README's Android section,
  `CONTRIBUTING.md#the-android-app`, `docs/ARCHITECTURE.md`, CLAUDE.md's Android invariants
  and [`android-verify`](../../android-verify/SKILL.md). `test/docs.test.mjs` fails when a
  stated SDK level, AGP, Kotlin, Gradle or JDK version, the debug id or the CI artifact
  drifts. Voice input and signed releases are not shipped: say so rather than describing
  plans as features.
- **Assistant discovery.** Sources:
  [Copilot instructions](../../../../.github/copilot-instructions.md),
  [`AGENTS.md`](../../../../AGENTS.md), [`CLAUDE.md`](../../../../CLAUDE.md), and
  [`CONTRIBUTING.md`](../../../../CONTRIBUTING.md). Re-check the development guide, skills,
  and review adapters.
- **Optional browser MCP.** Source:
  [`.mcp.json`](../../../../.mcp.json) and
  [`tools/playwright-mcp.mjs`](../../../../tools/playwright-mcp.mjs). Re-check the
  Copilot instructions, `CONTRIBUTING.md`, and `test/agent-support.test.mjs`.
- **Skill and agent structure.** Source:
  [`test/agent-support.test.mjs`](../../../../test/agent-support.test.mjs), plus
  `.claude/skills/`, `.claude/agents/`, and `.github/agents/`. Re-check assistant guidance
  and adapters.

## Change triggers

- Changes to `src/core/dimensions.js`, `src/core/engine.js`, coverage, or synthesis require a
  re-check of README facts and example, the interviews guide, and architecture.
- Changes to `src/providers/`, provider CSP origins, transcription or speech presets require a
  re-check of the README provider table, providers and privacy guides, contributor provider
  instructions, and [`add-provider`](../../add-provider/SKILL.md).
- Changes to `src/voice/`, driving commands, or browser voice fixtures require a re-check of
  the mobile and voice guide, output consent/privacy, screenshots, troubleshooting, architecture, and
  [`change-voice-and-driving`](../../change-voice-and-driving/SKILL.md).
- Changes to `src/store/`, backup, sharing, or key handling require a re-check of the ideas
  guide, privacy guide, `SECURITY.md`, and architecture.
- Changes to `src/ui/`, `index.html`, `sw.js`, or `manifest.webmanifest` require a re-check of
  relevant guide pages, screenshots and worked example, and architecture.
- Changes to `package.json` or `tools/` commands require a re-check of the README, `AGENTS.md`,
  `CONTRIBUTING.md`, the development guide, and affected skills.
- Changes to browser policy fixtures require the same re-check, keeping automated,
  live-provider and physically tested claims separate.
- Changes to CI, Pages, release, Docker, or site assembly require a re-check of the
  development guide, architecture, contributor guidance, and release guidance.
- Changes under `android/`, or to `test/vectors/` and `test/fixtures/backup-*.json`, require a
  re-check of the Android guide, the README's Android section, the Android parts of
  `CONTRIBUTING.md`, `docs/ARCHITECTURE.md` and `CLAUDE.md`, and `android-verify`.
- Changes to instructions, skills, agents, or discovery behaviour require a re-check of
  `CONTRIBUTING.md`, Copilot instructions, the development guide, and agent-support
  expectations.

## Generated and hand-authored boundaries

- [`README.md`](../../../../README.md), screenshots, and the worked example follow the
  [`update-readme`](../../update-readme/SKILL.md) procedure. The images and example are
  generated; refresh them through the harness rather than editing them by hand.
- The public guide is hand-authored but shares one shell. Copy structural markup from the
  index instead of reconstructing it from memory.
- `docs/ARCHITECTURE.md` maps ownership and extension seams. Long invariants belong in
  `CLAUDE.md`; iteration and proof belong in `AGENTS.md`.
- Review agents are adapters, not second skill definitions. Their body should link locally to
  the shared skill and add only client-specific discovery or read-only restrictions.

## Stable-writing rules

- Derive facts from source on every update; never copy a number from the previous document.
- Prefer a symbol or workflow name over a line number.
- State which component owns behaviour and which check proves it.
- Keep historical rationale only when it explains a current constraint.
- Do not copy the full internal trap catalogue into the public guide. Link maintainers to the
  canonical repository references instead.
