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
- **Design contract and reference-only boards.** Sources:
  [`docs/DESIGN.md`](../../../../docs/DESIGN.md) distinguishes current shipped values
  from the remaining Focus Studio screen/layout targets. #189 and #190 implement the web
  and native foundations, and #191/#192 implement the adaptive web/native shells, not the full screen
  redesign; guide CSS migration belongs to #205;
  [`docs/design/focus-studio.html`](../../../../docs/design/focus-studio.html) and its
  [local stylesheet](../../../../docs/design/focus-studio.css) are static repository
  references, not app/README/native screenshots or a production route.
  [`test/design-reference.test.mjs`](../../../../test/design-reference.test.mjs) checks
  inventory, local-only/CSP structure, priorities, recovery copy and proposed opaque contrast;
  `test:docs` in `package.json` includes it. Re-check the design state/transition matrix
  against the owning UI/store/provider/voice symbols before changing a screen.
  Native drawings use a system family and fallback colours; actual dynamic colour,
  device/IME/permission behavior and running-app accessibility need separate evidence.
  `tools/assemble-site.mjs` excludes `docs/design/` from published assets. Do not refresh
  canonical README screenshots to make an unimplemented screen appear shipped.
- **Native adaptive shell and inset ownership.** Sources:
  [`AppShell.kt`](../../../../android/app/src/main/kotlin/io/github/jaypetez/ideaforge/ui/AppShell.kt)
  uses installed Material window classes and one NavHost/destination descriptor set.
  It owns system-bar/display-cutout padding and consumes it once for all children;
  [`InterviewScreen.kt`](../../../../android/app/src/main/kotlin/io/github/jaypetez/ideaforge/ui/interview/InterviewScreen.kt)
  and [`ResultScreen.kt`](../../../../android/app/src/main/kotlin/io/github/jaypetez/ideaforge/ui/result/ResultScreen.kt)
  own zero-extra-inset contextual bars with complete literal display titles.
  Editing screens and [`VoiceStage.kt`](../../../../android/app/src/main/kotlin/io/github/jaypetez/ideaforge/ui/voice/VoiceStage.kt)
  own only remaining keyboard/navigation clearance. `Roles.kt` owns the font-scaled rail
  and task measures. In a short Interview with the IME open, its contextual bar shares the
  page scroller rather than clipping the enlarged editor. Library retains its installed
  Material adaptive panes.
  [`AdaptiveShellTest.kt`](../../../../android/app/src/testDebug/kotlin/io/github/jaypetez/ideaforge/ui/AdaptiveShellTest.kt)
  and the opt-in shell method in `UxCaptureDeviceTest` own route/resize/voice/inset proof,
  including bounded enlarged-text cases for installed system-navigation modes.
  Re-check DESIGN, the Android guide, architecture, full title semantics, no
  navigation-triggered microphone/service and actual exact-SHA pixel manifests.
  Scripted controller speech/service in paused-stage evidence is not physical input
  or real foreground-service proof.
- **Web visual foundation and chrome.** Sources:
  [`src/ui/app.css`](../../../../src/ui/app.css) owns semantic surfaces/text, composed
  control states, type, spacing, shape, separators and reduced/forced-colour rules.
  [`theme.js`](../../../../src/ui/theme.js), [`theme-boot.js`](../../../../src/ui/theme-boot.js),
  [`index.html`](../../../../index.html) and
  [`manifest.webmanifest`](../../../../manifest.webmanifest) own preference/first-paint
  and browser/install chrome. [`test/wiring.test.mjs`](../../../../test/wiring.test.mjs)
  holds those colours to the CSS ground roles.
  [`contrast.browser.mjs`](../../../../test/browser/contrast.browser.mjs) and
  [`a11y-keyboard.probe.mjs`](../../../../test/browser/a11y-keyboard.probe.mjs) measure
  actual composited controls, trusted hover/press/focus, selected/disabled/busy/status
  cues, native popover/HTML dialog primitives, 200% text and coarse targets. Re-check the
  current web table in DESIGN, Appearance guidance and generated real screenshots.
  HTML dialog styling does not mean the existing browser-owned `window.confirm` flows
  have been replaced; later settings/recovery issues own those compositions.
- **Web Settings and safe recovery.** Sources:
  [`index.html`](../../../../index.html) and
  [`app.js`](../../../../src/ui/app.js) own the shared five-section form, current-state
  summaries, operation-local errors, explicit matching-provider STT key reuse and
  always-visible required hosted-speech fields.
  [`dialog.js`](../../../../src/ui/dialog.js) owns the native awaited boolean confirmation;
  [`secrets.js`](../../../../src/store/secrets.js) serializes encrypted credential work
  against confirmed Forget, merges independently owned branches through `updateCredentials`
  and reports unreadable/unknown-key recovery to the UI. Connection/Start owns its current
  inference and STT submission; independent hosted-speech verification must survive it.
  [`settings.browser.mjs`](../../../../test/browser/settings.browser.mjs), the real-input
  policy probe and voice-stage tests own cancellation/focus, storage failures, delayed
  writes and hosted verification. Re-check README/guide section names, data-action labels,
  backup/import semantics, canonical screenshots and Library's still-synchronous Delete.
  [`sw.js`](../../../../sw.js) and [`test/sw.test.mjs`](../../../../test/sw.test.mjs)
  own response cloning before consumption, refresh-event lifetime, reported cache-write
  failures and offline shell/module fallbacks. Keep guide/provider bypasses intact.
- **Native visual foundation.** Sources:
  [`Theme.kt`](../../../../android/app/src/main/kotlin/io/github/jaypetez/ideaforge/ui/theme/Theme.kt)
  owns fallback `LightTokens`/`DarkTokens`, opaque composites, all 15 system-family type
  roles, real wallpaper dynamic schemes and `PrefsTheme`'s before-render gate.
  [`Roles.kt`](../../../../android/app/src/main/kotlin/io/github/jaypetez/ideaforge/ui/theme/Roles.kt)
  owns spacing, shapes, content measures and composed readable control/status ink;
  [`Controls.kt`](../../../../android/app/src/main/kotlin/io/github/jaypetez/ideaforge/ui/components/Controls.kt)
  consumes them through Material buttons, fields, chips and localized status.
  [`NoticeHost.kt`](../../../../android/app/src/main/kotlin/io/github/jaypetez/ideaforge/ui/components/NoticeHost.kt)
  bounds the scrollable full native Snackbar message without replacing its host state.
  [`ResultScreen.kt`](../../../../android/app/src/main/kotlin/io/github/jaypetez/ideaforge/ui/result/ResultScreen.kt)
  owns the font-aware short-height inline-action fallback; normal-height docking and
  export/action routines stay unchanged.
  [`FoundationThemeTest.kt`](../../../../android/app/src/test/kotlin/io/github/jaypetez/ideaforge/ui/theme/FoundationThemeTest.kt),
  [`FoundationControlsTest.kt`](../../../../android/app/src/test/kotlin/io/github/jaypetez/ideaforge/ui/components/FoundationControlsTest.kt)
  and [`FoundationPaletteTest.kt`](../../../../android/app/src/testDebug/kotlin/io/github/jaypetez/ideaforge/ux/FoundationPaletteTest.kt)
  own rendered-role, composed-state, target and paint checks. Re-check DESIGN's native
  table and explicit platform differences, guide Appearance wording and actual device
  evidence. A fallback table does not describe every dynamic wallpaper scheme.
- **Development UX fixtures and evidence.** Sources:
  [`tools/fixtures/ux.mjs`](../../../../tools/fixtures/ux.mjs) and generated
  [`test/fixtures/ux.json`](../../../../test/fixtures/ux.json) own the shared deterministic
  state inventory and reducer-built sessions; [`tools/ux-fixtures.mjs`](../../../../tools/ux-fixtures.mjs)
  and [`test/ux-fixtures.test.mjs`](../../../../test/ux-fixtures.test.mjs) reject drift.
  [`tools/ux-captures.mjs`](../../../../tools/ux-captures.mjs) and
  [`test/browser/fixtures/ux.js`](../../../../test/browser/fixtures/ux.js) use the existing
  assembled-site/browser harness and fail on missing state, inaccessible controls or overflow.
  Native [`UxFixtures.kt`](../../../../android/app/src/debug/kotlin/io/github/jaypetez/ideaforge/ux/UxFixtures.kt),
  [`UxFixtureActivity.kt`](../../../../android/app/src/debug/kotlin/io/github/jaypetez/ideaforge/ux/UxFixtureActivity.kt)
  and [`UxCaptureDeviceTest.kt`](../../../../android/app/src/androidTest/kotlin/io/github/jaypetez/ideaforge/ux/UxCaptureDeviceTest.kt)
  own shared-data loading, labelled isolated phases and real navigation.
  [`UxCaptureRecorder.kt`](../../../../android/app/src/androidTest/kotlin/io/github/jaypetez/ideaforge/ux/UxCaptureRecorder.kt)
  owns PNG/semantics/manifests; [`UxDeviceEnvironment.kt`](../../../../android/app/src/androidTest/kotlin/io/github/jaypetez/ideaforge/ux/UxDeviceEnvironment.kt)
  restores actual display/font settings and changed system-navigation overlays.
  Re-check platform-specific phases, explicit caller
  SHA versus clean-tree evidence, focused versus complete matrix results, and known red
  baselines against those owners.
  Native [`SessionStore.kt`](../../../../android/app/src/main/kotlin/io/github/jaypetez/ideaforge/data/SessionStore.kt)
  (`loadState`) and [`LibraryViewModel.kt`](../../../../android/app/src/main/kotlin/io/github/jaypetez/ideaforge/ui/library/LibraryViewModel.kt)
  own real loading/empty/filtered/unavailable reads and retry; failed reads never publish a
  partial successful index. Re-check the Android guide and DESIGN's prerequisite account.
  [`android-device.yml`](../../../../.github/workflows/android-device.yml) owns standard
  visual managed devices and always-upload evidence, not the required `ci` gate.
  Its method-focused inference-consent route records Settings Connection, provider
  selection and hosted/local details. A before phase requires absent agreement controls
  and unchanged pre-integration application sources; after requires the real labelled
  control. Require actual nonzero selected-test execution and the complete matrix,
  never infer visual acceptance from a successful build or an ATD-only run.
  [`android/app/build.gradle.kts`](../../../../android/app/build.gradle.kts) and
  [`ScriptedProviderIsDebugOnlyTest.kt`](../../../../android/app/src/testDebug/kotlin/io/github/jaypetez/ideaforge/ScriptedProviderIsDebugOnlyTest.kt)
  reject fixture JSON, activities and retained debug-only classes in release packaging.
  Re-check DESIGN/contributor capture commands, manifests, actual font/text scaling,
  clean tested SHA versus labelled dirty iteration, real-app versus isolated phase evidence
  and lab-versus-field timing claims. Fixture code/data must stay out of the assembled site
  and release APK. ATD semantic tests are not visual/system-bar evidence: drawing/SystemUI
  are disabled there. Preserve the canonical README screenshot/example inventory.
  [`UxFoundation.kt`](../../../../android/app/src/debug/kotlin/io/github/jaypetez/ideaforge/ux/UxFoundation.kt)
  and the opt-in `uxFoundationProbe` method on `UxCaptureDeviceTest` own isolated production
  controls at real fallback and two labelled simulated schemes. Re-check palette provenance,
  minimum targets/full painted labels and complete expected matrices; simulated schemes
  are never actual wallpaper evidence.
  Re-check native `touchBoundsInRoot` versus paint bounds, requested root RGB pixel matches
  versus mean brightness, and the separate opt-in 12-case short-Result matrix. Target and
  theme claims require the actual measured requirement, not an undersized painted glyph
  or an inverse notice's average luma.
- **Public guide shell and navigation.** Sources:
  [`guide/index.html`](../../../../guide/index.html) and
  [`guide/assets/guide.css`](../../../../guide/assets/guide.css). Re-check every guide page.
  The guide keeps its earlier palette until #205; #189 changes only the app foundation in
  [`src/ui/app.css`](../../../../src/ui/app.css). Guide fonts remain system fonts, with no
  remote assets or guide JavaScript.
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
  [`tools/apk-cert-pin.mjs`](../../../../tools/apk-cert-pin.mjs) with the signer pin in
  [`SECURITY.md`](../../../../SECURITY.md#release-signing-certificate) (the signed APK),
  [`tools/check-privacy-policy.mjs`](../../../../tools/check-privacy-policy.mjs) with
  [`guide/privacy-policy.html`](../../../../guide/privacy-policy.html) (the confirmed contact),
  [`package.json`](../../../../package.json), and
  [`src/version.js`](../../../../src/version.js). Re-check `CONTRIBUTING.md`, the development
  guide, and [`release`](../../release/SKILL.md).
- **Security and privacy.** Sources:
  [`SECURITY.md`](../../../../SECURITY.md), [`index.html`](../../../../index.html),
  `src/store/`, `src/providers/`, `src/voice/`, and the Android
  [`AppGraph.kt`](../../../../android/app/src/main/kotlin/io/github/jaypetez/ideaforge/AppGraph.kt),
  [`KeyringStore.kt`](../../../../android/app/src/main/kotlin/io/github/jaypetez/ideaforge/data/KeyringStore.kt),
  [`PrefsStore.kt`](../../../../android/app/src/main/kotlin/io/github/jaypetez/ideaforge/data/PrefsStore.kt),
  [`AndroidManifest.xml`](../../../../android/app/src/main/AndroidManifest.xml),
  [`data_extraction_rules.xml`](../../../../android/app/src/main/res/xml/data_extraction_rules.xml),
  [`ProviderHttp.kt`](../../../../android/providers/src/main/kotlin/io/github/jaypetez/ideaforge/providers/ProviderHttp.kt),
  [`LocalNetworkPolicy.kt`](../../../../android/providers/src/main/kotlin/io/github/jaypetez/ideaforge/providers/LocalNetworkPolicy.kt),
  the speech/transcription consent gates in
  [`HostedSpeech.kt`](../../../../android/app/src/main/kotlin/io/github/jaypetez/ideaforge/voice/HostedSpeech.kt),
  and [`ResultActions.kt`](../../../../android/app/src/main/kotlin/io/github/jaypetez/ideaforge/ui/result/ResultActions.kt)
  for temporary shared files. Re-check the README, privacy policy, security guide and
  provider guide. An unresolved privacy contact blocks release through
  `tools/check-privacy-policy.mjs`; only the maintainer confirms the public address.
  Web local authentication follows `readRingFromForm` in
  [`src/ui/app.js`](../../../../src/ui/app.js) and `applyAuth` in
  [`src/providers/http.js`](../../../../src/providers/http.js); do not describe it as
  Android's enforced key omission. Web Pause in
  [`src/voice/index.js`](../../../../src/voice/index.js) may finish pending transcription;
  Android `pauseRun` in
  [`DriveController.kt`](../../../../android/app/src/main/kotlin/io/github/jaypetez/ideaforge/voice/DriveController.kt)
  cancels capture work and preserves only the caption/segments already available.
- **Privacy-policy entry points.** Sources:
  [`PrivacyPolicyLink.kt`](../../../../android/app/src/main/kotlin/io/github/jaypetez/ideaforge/ui/PrivacyPolicyLink.kt)
  owns the one native policy URL, localized external action and explicit open-error recovery.
  Settings About and the common onboarding footer reuse it. Native screen intent tests and
  `PrivacyPolicyLinkTest` prove the destination and known handler failures;
  `PrivacyPolicyRecoveryScreenTest` checks full auto-revealed feedback at narrow/enlarged
  real AppShell surfaces, including another rejected tap. The method-focused
  privacy route in `UxCaptureDeviceTest` and `android-device.yml` owns actual before/after
  pixels, full labels and target/inset checks. A before run requires absent links and an
  unchanged pre-integration application tree; it is not positive acceptance evidence.
  [`index.html`](../../../../index.html) owns the relative shared web footer link.
  `app.browser.mjs` exercises real root/subpath navigation and the offline shell;
  `a11y-keyboard.probe.mjs` verifies trusted Tab and enlarged-text reachability.
  Re-check README/guide footer labels, the Android guide, architecture and generated
  Start/Settings screenshots. Keep the confirmed policy contact unchanged.
- **Affirmative inference agreement.** Sources:
  [`inference-consent.js`](../../../../src/store/inference-consent.js) and
  [`prefs.js`](../../../../src/store/prefs.js), plus native
  [`InferenceConsent.kt`](../../../../android/app/src/main/kotlin/io/github/jaypetez/ideaforge/data/InferenceConsent.kt)
  and `PrefsStore.kt`, own destination-bound non-secret grants and strict preference reads.
  Provider registries own validated destination identity; `app.js`, native onboarding/
  Settings, `AppShell`, `InterviewWork`/`InterviewViewModel` and `DriveController` own
  session/effect admission. Adapter `beforeSample` hooks recheck bound permits per attempt,
  independently of connection checks. `inference-consent.test.mjs`, the matching browser
  probe and native consent/work/drive/adapter tests own denial, reload, switching,
  failed-storage and retired-work proof. Positive scripted actors affirm explicitly through
  UI or the actual preference owner; unconsented countercases must remain.
  Re-check README, getting-started/providers/mobile-and-voice/Android/privacy guidance,
  SECURITY, architecture, DESIGN, canonical images and cache retirement. Failed withdrawal
  cannot promise cross-restart revocation when storage rejects the change. Local read/export
  and independent microphone/STT/TTS checks remain available.
- **Setup disclosures.** Sources: [`index.html`](../../../../index.html) (`model-settings`,
  `speech-options`) and [`src/ui/app.js`](../../../../src/ui/app.js) (`onProviderChange`,
  `onSpeechChange`). Re-check setup instructions against the actual summary labels.
  Model options opens for local providers; Voice and speaking pace opens for OpenAI
  speech. Primary connection, input/output choices and start actions remain outside them.
- **Start and Settings views.** Sources: [`index.html`](../../../../index.html)
  (`#panel-setup` `data-view`, `.start-only`, `.settings-only`, `#connect`) and
  [`src/ui/app.js`](../../../../src/ui/app.js) (`show`, `connectionReady`, `renderConnect`).
  Start uses presentation-only provider/fields/ready phases over the same controls.
  `advanceConnection` reuses `buildProvider` without persistence or a network request;
  `connectionContext`/`ownsConnection` share configuration/presentation revisions,
  navigation and credential-generation guards across construction, Start, Continue and
  Check. Back/Change retire pending Start and its owned output as well as Check/Continue;
  Start identity prevents an older completion clearing a newer run's duplicate guard.
  `refreshModels` guards catalogue/field writes; failed Continue focuses only a visible
  invalid field or Provider.
  Speech withdrawal clears retired busy work without erasing completed unchanged
  inference proof. Ready is field readiness, never a successful-request gate.
  Saved keys are explicitly untested on a new visit; viewer availability is not a
  network check. `sw.js`'s cache generation changes with the shared markup/handler shape;
  `app.browser.mjs` proves the prior cache is retired and matching assets are precached.
  Start actions precede installation/recent content. Settings holds the five
  groups named by `#settings-index`: Connection, Voice, Appearance, Your data (backup,
  import and Forget keys) and About. Re-check the getting-started, providers and library guides, the
  README, `progressive-start.browser.mjs`, trusted-input policy cases and the
  01-setup / 10-settings captures. `test/docs.test.mjs` ties the group names
  and their count to the README and the getting-started guide.
- **App shell, navigation and opening destination.** Sources:
  [`index.html`](../../../../index.html) (`.bar`, `#b-library`, `#b-nav-new`,
  `#b-settings`) and [`src/ui/app.js`](../../../../src/ui/app.js) (`show`, `navigateTo`,
  `boot`, `renderShell`) with the rail tokens/queries in `app.css`. Below 600px destinations
  use bottom navigation or a contextual detail bar; from 600px a 96px labelled rail
  expands to 208px at 1200px. The title is complete literal `sessionDisplayTitle` text,
  allowed to ellipsize visually; voice hides the rail and clears its body padding.
  The app opens on Ideas once any idea is stored, otherwise on Start. Re-check getting
  started, the library guide, DESIGN, the README and actual shell/lifecycle evidence.
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
  position must fit the screen it describes. `app.css` reserves Setup's persistent-error
  clearance in the page bottom and focus scrollport; the real-input keyboard probe checks
  rejected-key recovery at enlarged text, narrow widths and landscape. Re-check
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
  `privacy-policy.html` (web and Android data handling and the public contact),
  `privacy-and-security.html`, `troubleshooting.html` and `development-and-delivery.html`.
  `test/docs.test.mjs` fails when a page is missing from this list.
- **Native Android app.** Sources:
  [`android/app/build.gradle.kts`](../../../../android/app/build.gradle.kts) (SDK levels,
  application id, `.debug` suffix, version from `package.json`, release signing from the
  environment or `android/keystore.properties`, and `verifyReleaseApk`),
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
  drifts. A signed APK exists only for tags cut after SECURITY.md pins a real fingerprint
  instead of `PENDING`: say so rather than describing plans as features.
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
- **Lean coding and assistant processes.** Sources:
  [`AGENTS.md`](../../../../AGENTS.md) (`Lean coding`),
  [`tools/assistant-process.mjs`](../../../../tools/assistant-process.mjs)
  (`createGuidance`, `readMode`, `buildMap`, `scanDebt`, `measureGain`),
  [`tools/assistant.mjs`](../../../../tools/assistant.mjs) (`runAssistant`),
  [Claude hook configuration](../../../../.claude/settings.json), and
  [Copilot command hooks](../../../../.github/hooks/ideaforge-assist.json) plus the
  [foreground SDK entry](../../../../.github/extensions/ideaforge-assist/extension.mjs).
  Re-check [`docs/ASSISTANT-PROCESS.md`](../../../../docs/ASSISTANT-PROCESS.md),
  `CONTRIBUTING.md`, the README assistant paragraph, the development guide,
  the five assist/audit/debt/gain/help skills, and `test/agent-support.test.mjs`.
  The host supplies Copilot's SDK; this is not an app dependency. Local mode state never
  belongs in the published tree. Native loading/protocol proof and empirical coding
  benefit remain separate claims.

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
