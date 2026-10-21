# Design tokens

IdeaForge has two front ends: the web app (`src/ui/app.css`) and the native Android app
(`android/app`, Jetpack Compose with Material 3). They share one information architecture
and one intended visual language, and this page is where the two meet. The web foundation
in [#189](https://github.com/jaypetez/ideaforge/issues/189) and native foundation in
[#190](https://github.com/jaypetez/ideaforge/issues/190) use Focus Studio. The fallback
roles agree; platform typography, measurements and native wallpaper colours deliberately
differ.

`src/ui/app.css` owns the web values. `test/browser/app.browser.mjs` checks that both themes
resolve, and `test/browser/contrast.browser.mjs` checks every colour pair in light and in
dark (see [Contrast and targets](#contrast-and-targets)).

The [Focus Studio contract](#focus-studio-contract) below is the approved target for
[the second UX pass](https://github.com/jaypetez/ideaforge/issues/186). The web and native
colour/type/spacing/shape/state foundations and adaptive web (#191) and native (#192)
shells, progressive web Start (#193), native Ideas (#198), native Interview (#200),
native voice presentation (#202) and native Start (#195), Settings (#196) and Result (#204)
are implemented here.
The guide's own Focus Studio migration is **not yet implemented**; its stylesheet keeps
its earlier values until
[#205](https://github.com/jaypetez/ideaforge/issues/205). Actual app screenshots reflect
the implemented surfaces, not the complete reference boards.

## Current shipped tokens

## Colour roles

Web values are `light-dark(light, dark)` over an explicit `color-scheme`. The Compose column
identifies the corresponding fallback role. Android 12 and later still use the platform's
wallpaper dynamic scheme without recolouring it; the native fallback table follows this one.

| Web token | Compose `ColorScheme` | Light | Dark | Used for |
|---|---|---|---|---|
| `--surface-container-low` | `surfaceContainerLow` / `background` / `surface` | `#F5F7FA` | `#11161B` | The page ground |
| `--surface-container-lowest` | `surfaceContainerLowest` | `#FFFFFF` | `#1B222A` | Foreground work and fields |
| `--surface-container` | `surfaceContainer` | `#E8EDF2` | `#27313B` | Tracks, disabled fills, quiet grouping |
| `--surface-container-high` | `surfaceContainerHigh` / `surfaceContainerHighest` | `#DEE6EE` | `#313E49` | Menus and floating status |
| `--on-surface` | `onSurface` | `#17212B` | `#EEF3F8` | Body text and headings |
| `--on-surface-variant` | `onSurfaceVariant` | `#526171` | `#B4C0CB` | Readable hints and metadata |
| `--outline` | `outline` | `#718091` | `#91A1B2` | Operative field/control edges (3:1 on every surface) |
| `--outline-variant` | `outlineVariant` | ink at 16% | ink at 16% | Restrained separators, not operative boundaries |
| `--primary` | `primary` | `#0F6963` | `#7AD5C7` | Main action, links and focus |
| `--on-primary` | `onPrimary` | `#FFFFFF` | `#0A332E` | Text on a primary fill |
| `--primary-container` | `primaryContainer` | primary at 10% | primary at 10% | Selected and hovered tonal fills |
| `--on-primary-container` | `onPrimaryContainer` | `--on-surface` | `--on-surface` | Text on a tonal fill |
| `--error` | `error` | `#983632` | `#F3AAA0` | Destructive text and failure markers |
| `--error-container` | `errorContainer` | error at 12% | error at 12% | Destructive hover and error callouts |
| `--success` | custom | `#306445` | `#A0C9A9` | Coverage reached, checks passed |
| `--success-container` | custom | success at 12% | success at 12% | Success badges/callouts |
| `--warning` | custom | `#765C20` | `#E7C882` | Pending and partial states |
| `--warning-container` | custom | warning at 12% | warning at 12% | Partial badges and warning callouts |

Translucent roles are composited over their actual ancestor surfaces. The darker web error
red is intentional: the reference `#A83C3A` fails for destructive text on a translucent
pressed/hover layer over a raised menu. Opaque token contrast alone cannot approve that
component. Callout bodies use ordinary ink and an explicit explanation; their status edge
is an additional cue, not the only one.

The v1 colour names (`--ground`, `--surface`, `--ink`, `--accent` and the rest) remain only
where unchanged screen rules still consume them. Touched shared compositions use the roles.

### Current native fallback

`android/app/src/main/kotlin/io/github/jaypetez/ideaforge/ui/theme/Theme.kt` owns these
`LightTokens`, `DarkTokens` and `ExtraColors` values. They apply below Android 12 and when
dynamic colour is disabled by an explicit composition, not a new user preference.

| Native role | Light | Dark |
|---|---|---|
| `surfaceContainerLow` / `background` / `surface` | `#F5F7FA` | `#11161B` |
| `surfaceContainerLowest` | `#FFFFFF` | `#1B222A` |
| `surfaceContainer` | `#E8EDF2` | `#27313B` |
| `surfaceContainerHigh` / `surfaceContainerHighest` | `#DEE6EE` | `#313E49` |
| `onSurface` | `#17212B` | `#EEF3F8` |
| `onSurfaceVariant` | `#526171` | `#B4C0CB` |
| `outline` | `#718091` | `#91A1B2` |
| `outlineVariant` | ink at 16% over ground | ink at 16% over ground |
| `primary` | `#0F6963` | `#7AD5C7` |
| `onPrimary` | `#FFFFFF` | `#0A332E` |
| `error` | `#983632` | `#F3AAA0` |
| `ExtraColors.success` | `#306445` | `#A0C9A9` |
| `ExtraColors.warning` | `#765C20` | `#E7C882` |

Native `primaryContainer` (10%), `errorContainer` (12%) and `outlineVariant` (ink at 16%)
are opaque `Color.copy`/`compositeOver` results over the native ground. Keep Compose's
alpha precision rather than substituting rounded hexes. `outlineVariant` is a separator,
not an operative field edge. Fallback `secondary`, `tertiary` and `surfaceTint` follow
`primary`; their container inks and `onErrorContainer` use `onSurface`. `onError` follows
`onPrimary`. Inverse roles use the opposite theme's high surface, ink and primary.

`Roles.kt` derives shared-control ink/edges from the actual `ColorScheme`, including
cards, selected/error surfaces and the 2dp dock. A preferred ink that fails the composed
state contrast uses a readable scheme ink or black/white; this does not mutate the
wallpaper scheme. Success/warning ink follows the rendered background, not merely the
system's dark-mode flag.

## Theme choice

Settings → Appearance offers System, Light or Dark. Both apps store it under the same key
and values: `theme` in `src/store/prefs.js` (`themePreference`) on the web, `Prefs.theme`
on Android. System follows the device. On the web an explicit choice sets
`<html data-theme>`, which the `color-scheme` rules in `app.css` follow, and
`src/ui/theme-boot.js` applies a stored choice before first paint. On Android `PrefsTheme`
waits for the stored value before rendering, for the same reason: no flash of the other
theme. Like every device preference, the choice is never in a backup.

The web's classic boot script also retints the chrome for an explicit capture `data-theme`
without changing or storing it, even if preference storage is blocked. `theme.js`,
`theme-boot.js`, the two `index.html` meta tags and the manifest's dark install fallback
agree with the CSS ground roles; wiring and before-paint tests hold that agreement.

## Current web shell

`app.css` owns the adaptive rail: `--rail-compact` is 96px from 600px through 1199px;
`--rail-expanded` is 208px from 1200px. It keeps one labelled navigation set, not duplicated
controls or routes. Below 600px the same buttons form bottom navigation on Ideas/Start/
Settings or the contextual Back/title/Settings bar on Interview/Result. The contextual
title uses the session reducer's display order, retains its complete literal text and
tooltip, and may ellipsize visually.

Wide rail clearance belongs to `body`, including its safe area; `.wrap` keeps its screenshot
padding contract and task-specific form/conversation/library/settings/document measure.
Voice removes the rail clearance and hides the navigation through the existing `hidden`
state. Rail overflow can scroll vertically in a short or enlarged-text window; labels
and keyboard focus are not discarded. Error toasts stay on the work side, not over the rail.
Coverage and the library detail pane reserve the same bottom thumb gap as `.wrap`,
so reading to the page end cannot push their tops out of the viewport. The library list
track is bounded by its actual remaining workspace, not enlarged rems alone, so the
detail actions and prose retain room at 200% text.
The existing Result height cap/action placement remain until #203; this is not the complete
document makeover.

`shell.browser.mjs` measures the boundary widths in both themes at 100%/200% text, preserves
the compact fixed-bar/focus tests and rejects duplicated controls or unsafe title markup.
The trusted-input keyboard/policy suite and lifecycle probe check actual focus/targets,
gesture behavior and a new idea's title after an older request settles.
The policy suite also supplies nonzero safe-area CSS insets through the browser protocol,
measures the skip link's full focus outline and the short sticky coverage panel, and
enters/exits the real wide voice flow to prove rail clearance is removed/restored.
These are controlled desktop-browser checks, not physical-phone safe-area evidence.

## Current web Start

Start keeps one shared Settings form, with labelled provider, required-fields and ready
presentations instead of a tall introduction/card stack. Provider groups and visible
fields follow the existing registry; local manual models remain editable if discovery
fails. Back and Change connection preserve entered values. Continue reuses provider
construction without persistence or network calls, while explicit Check/Start retain
the existing encrypted-keyring path.

Ready means field readiness, not a passed request. Saved/untested, checking, checked and
failed states have distinct text; only a current successful check earns the checkmark.
Viewer checks report runtime availability, not a network round trip. Relevant edits and
navigation retire pending check effects before they can overwrite newer proof or model
fields. Start with voice remains primary, Type instead stays visible, and both precede
installation/recent content. Setup alone never requests the microphone; the initiating
voice tap still primes output synchronously.
Ready also presents the unchecked, provider-named inference agreement. A check or saved
key cannot tick it. Its device-local record is independent of microphone and hosted-voice
choices, and a missing/failed acknowledgement disables both start actions with explicit
recovery. Settings Connection exposes withdrawal; local ideas remain readable/exportable.
Android uses the same policy in its onboarding details and Connection form. Provider-kind/local-address
changes retire agreement; model-only changes retain it. See the
[privacy policy](../guide/privacy-policy.html#model-requests) for storage-failure limits.
Back/Change also retire pending Start/Continue effects without discarding entered values,
completed unchanged connection proof or an already committed idea. A failed Continue
returns focus to a visible field or provider choice.

## Current web Settings

The same form appears as Connection, Voice, Appearance, Your data and About, with
current-state summaries and a quiet desktop section index. Required hosted-speech fields,
consent and Preview stay outside optional voice/pace preferences. Matching Groq/OpenAI
dictation names its inference-key reuse; another transcription service never inherits the
previous service's saved key. Connection checking does not test transcription.
The transcription input is bound to its selected service and clears on a service change,
including after a key was saved without reloading.

Appearance applies immediately and reports a failed preference write beside the control.
Credential-read/save recovery remains beside Connection; data-operation results remain
beside Your data, independently of transient notices. Imports remain additive and
copy-on-collision; backups exclude credentials and device preferences.

Forget keys uses the native HTML dialog's focus containment and top layer, with Cancel
initially focused, Escape, a labelled consequence and initiating-focus return. It changes
nothing before confirmation. Confirmed removal retires pending credential effects before
storage; serialized credential operations ensure an older encrypted write cannot restore
the payload or wrapping key afterwards. In-memory access stops even if removal fails,
with an explicit retry; field clearing happens before storage so later edits are not lost.
Unknown credential reads never become a claim that no keys exist. Independent keyring
updates merge their owned branches inside serialization; navigation cancellation also
withdraws a pending hosted check, including after its payload write has issued.
Library Delete still uses its browser confirmation
until its own redesign slice.

## Current native shell

`ui/AppShell.kt` uses the installed Material adaptive window classes: compact width keeps
one `NavigationBar`; medium and wider widths use one labelled `NavigationRail`. Both use
the same destination descriptors and callbacks around the same `NavHost`. The native
`rail` token is 80dp, enlarged with font scale, and the rail can scroll in a short window.
These are native window classes, not copies of the web's CSS breakpoint/rail table.

The Interview and Result contextual bars retain full literal `sessionDisplayTitle` text
in semantics while allowing a visual ellipsis. Back uses the existing navigation stack.
Completed ideas select Ideas; New idea opens a mode choice before a deliberate selection
mints a new id and back-stack ViewModel entry.
The existing Material adaptive Library panes and their saved selection are retained.
Onboarding still removes itself from the stack and prevents navigation before a usable
keyring is available; opening Library does not focus its search field automatically.

| Inset | Owning layer |
|---|---|
| System bars and display cutout | AppShell's outer Scaffold. Its Row applies and consumes the returned padding once for every child. |
| Compact navigation | The shell's NavigationBar owns its bottom/horizontal system clearance; Scaffold includes the actual bar height in child padding. |
| Rail and contextual bars | Inside consumed shell space; their Material `windowInsets` are zero, so they do not reserve system bars again. |
| Keyboard | Interview, Settings and onboarding consume remaining IME beyond shell clearance. Voice also respects any remaining keyboard/nav inset; Result's existing action dock stays within shell space. |

When the app-scope drive is attached to the visible interview's session, the shell hides
both navigation presentations and the manual contextual bar. Voice gains the released
workspace without adding a route or changing controller/service ownership. Exit restores
the actual manual navigation; caption/level updates do not rebuild shell chrome.
Form, work, Settings, reading and answer measures remain owned by `IdeaForgeWidths`;
Library, document metadata and voice also consume those bounds. This is shell/inset work,
not the composer, voice or Result action-order makeover.

## Current native onboarding and entry

`OnboardingScreen` keeps the two-step provider/configuration flow with compact progress
text, readable hosted/local separator rows and the existing provider-specific required
fields. A native expanded window with enough font-scaled space adds supporting provider/
privacy information beside the bounded form; otherwise the same required content and
actions remain in one scrollable column. Privacy policy stays in both steps' footer.

`OnboardingViewModel` preserves per-provider unsaved fields through Back/Change in memory,
while its existing generation guard cancels checks. Saved details, previous successful
checks and current errors are not a permanent connected verdict. Manual models, local
emulator help, unreadable-keyring confirmation and all three policy prerequisites stay.

`NewIdeaRoute` offers Voice primary (at least the native voice action height), Type instead
visible, and Cancel. Navigation/recreation/cancel allocates no id or microphone effect.
Selection uses the existing inference admission and a fresh Interview entry. The explicit
new-entry voice intent is consumed once; its denied/cancelled/interrupted setup leaves a
truthful manual notice without replacing higher-priority errors or creating a second drive.

## Current native Settings

`SettingsScreen` keeps Connection, Voice, Appearance, Your data and About in one
predictable scrollable form. Summaries derive saved provider, effective permitted speech,
theme, library availability/count and version from their owners, not a live health claim.
An expanded window with sufficient font-scaled space adds a narrow section index; direct
section navigation scrolls/focuses the heading without discarding editor state.

Hosted speech and transcription retain their separate keys/grants/checks and now retain
separate operation notices. Failures use semantic error status; cancellation is not a
passing check. Both hosted voice choices are vertical native radio targets at large fonts.
Your data separates the session-only unencrypted backup/import actions from credential
removal. Cancel/Back preserves the blob/generation and returns focus; confirmed Forget
retains its synchronous revoke and generation protection, including during other checks.

## Current native Result

`ResultScreen` keeps one selectable document canvas bounded by the reading role. Canonical
markdown headings have distinct native hierarchy; lists/quotes reflow and intrinsically
wide code/tables have local horizontal scroll rather than page overflow. The safe core
parser still supplies literal text spans, not executable HTML or a new serializer.
Actual question/coverage/source and synthesis warnings remain above the complete export.

Compact actions make Share primary/Copy visible; expanded actions make Copy primary/Save
visible. A separate secondary row retains Share/Save and Ask me more without squeezing
four equally bordered labels. Short enlarged windows keep the existing page-flow action
fallback, shell insets are not reapplied, and every native action remains reachable.

Only completed Save produces the `Saved` notice; Copy retains the SDK-dependent app or
system confirmation policy. Copy/share/save/reopen/read errors are
explicit persistent statuses; a failed read is not a missing row and offers retry.
ResultActions, exportFor, CreateDocument cancellation, FileProvider/temporary-share TTL,
clipboard bytes and same-session reopen remain their owners, including the real-provider
Windows test seam.

## Current native Ideas

`LibraryScreen.kt` keeps Material's `NavigableListDetailPaneScaffold` and one New idea
header. The list uses separator-based rows rather than repeated outlined cards: title,
status/coverage/date, at most two tag names with a remaining-tag count, and the current
action. Titles may visually ellipsize after two lines; their full literal text remains
in semantics and the detail pane. Rows grow with enlarged text rather than using a fixed
height.

In a compact window, the row opens Continue or View result directly. More holds View
details, Rename or tag, Archive/Restore and Delete. In a wider window, a row selects its
detail; the detail holds the primary Continue/View result action. Filtering hides an
unrelated selected preview immediately, while preserving search focus. A compact detail
whose idea is removed or filtered out returns through the existing pane navigator.

Search and the result count stay visible; Filters discloses tags and the archived switch.
Collapsed active filters retain a summary and Clear filters. No-match recovery clears all
filters, or offers Show archived when the only ideas are archived. Loading, no ideas and
unavailable storage keep their distinct states and Retry never deletes files. Opening
Ideas retains the pane focus-target guard instead of focusing the search editor.

Rename/tag and permanent Delete use native dialogs with Cancel/Back. They return focus
to the initiating menu control when it remains mounted, or the Ideas heading. A confirmed
deletion moves focus off the removed row. Mutations still go through LibraryViewModel,
the session reducers, per-session work lock and store deletion protection.

`LibraryPreview` renders an active idea without synthesis as **No write-up yet** plus its
literal conversation and unsent draft, keeping checklist, skipped and answer-provenance
labels. It does not manufacture a failed-generation notice. Existing write-ups and
completed ideas still use `ExportPreview`; canonical export generation is unchanged.
The existing device workflow's `evidence=ideas` selects the focused method explicitly,
reusing the general recorder for ready/empty/unavailable, normal/100-row/archived/filtered
lists, the long active preview and a real pending store read. The injected store is still
the graph's sole SessionStore, not a fake UI state. It runs in compact/expanded windows,
both themes and all three font scales.
It is a focused Ideas inventory, not a claim that the full UX matrix passed.

## Current native Interview

`InterviewScreen.kt` gives the literal current question/bridge the primary reading
hierarchy, with a quiet source/turn line and a bounded `IdeaForgeWidths.reading` column.
The composer starts with one line and expands; Send is primary, with separate Skip,
Answer by voice and Write it up actions. Suggestion chips still fill the editable draft
through the same ViewModel, preserving unchanged/edited provenance.

Coverage starts as a factual percentage and Coverage details action. The disclosure
shows dimension names and textual levels from the Kotlin contract, not confidence.
Show earlier includes the settled exchange count; Current question closes that view.
New turn results do not scroll a reader away from expanded earlier history. Thinking
retains the submitted question/answer rather than replacing all context with activity.

Short panes, enlarged text and expanded coverage use the existing single page scroller
for conversation and composer. Ordinary-height, default-text interviews retain their
lazy thread and bounded docked editor. The contextual bar still joins the scroller only
when a short window has the real keyboard open. Full labels/actions remain reachable
by scrolling rather than fitting an enlarged chip into a smaller lazy viewport.

Manual failure notices reuse the bounded native host with an indefinite duration.
The ViewModel retains the notice until Dismiss, so recomposition/rotation cannot consume
it as a one-shot event. The editor's existing relocation guard also observes the actual
notice-host height, preserving an editor that was visible before the failure without
pulling an earlier-history reader back to it. Voice work notices retain their existing
path; optional-notification status is presented separately in the stage.
No provider, turn, persistence, wrap or export policy changes.

The focused `evidence=interview` device route covers seed/long/history/coverage, checklist,
busy/save failure, actual IME, real engine wrap, Writing and failed synthesis. The wrap
uses the canonical typed walkthrough, stopping at its genuine offer; no presentation-only
wrap flag is injected. The ordinary chip walkthrough retains its export assertions.
This is a declared Interview subset, not a full-UX or physical-device result.

### Native focused voice presentation

`VoiceStage.kt` separates the idea title, factual phase, last successful output backend,
current question, spoken text and captured words. The backend is read from the controller's
actual successful `SpeakResult`, never inferred from a configured key or claimed before
output completes. Recorded fallback input reports Transcribing only around the actual
upload; completed segments become captions. Pause retains the parsed draft words or the
already-submitted caption while turn work settles, without submitting or restoring an
answered draft. Full content scrolls inside the bounded reading column; the separated
Pause/Resume and Exit dock keeps native targets at least 64dp high.

Phase changes are polite live status. Captions remain fully readable; only settled captions
are live regions, not each unstable partial or microphone sample. No sample means no bars.
There is no native-speaking output waveform, fabricated activity, confidence or streaming
claim. Finish-word and whole-utterance command help is an in-stage disclosure in every phase.

Microphone rationale is separate from optional notification guidance. Only an explicit
voice tap and Continue launch the permission request. A denied microphone stands down
without starting a recognizer or foreground service and exposes an explicit app-permission
Settings action with visible open-error recovery. Returning from Settings never resumes
automatically. Notification denial stays nonfatal and is shown in the stage; Resume does
not ask again merely because notifications remain off.

The app-scope controller, epoch retirement, draft writes, service/notification actions and
hosted consent/check/focus/cancellation rules are unchanged. Its new state observations
are presentation only, not another session or persistence schema.
`evidence=voice` selects the complete declared native phase/recovery subset: both themes
and font scales 1.0/1.3/2.0 in compact and expanded windows, plus listening/paused/blocked
at font 2.0 in a compact landscape. These are visibly labelled **isolated-component**
production UI captures with scripted semantic state, not microphone, native-recognizer,
permission-prompt or real-service validation. The native complete phase still leaves the
stage for Result. Controller/service/capture tests separately exercise sequencing;
physical behavior remains owned by #89.

`AdaptiveShellTest` exercises real activity routes, native window shapes, draft/selection
recreation, same-session Ask me more and attached voice. The managed-device shell matrix
uses MainActivity at compact, medium, expanded and compact-landscape sizes, both themes
and font scales 1.0/1.3/2.0, including an actual IME. Its paused stage uses the real
app-scope controller with scripted speech and a recorded fake service: it is layout/
identity evidence, not a microphone, permission UI or real-service certification.
Complete PNGs/manifests at the exact clean SHA remain required acceptance evidence.
Additional light/font-2 cases switch each installed three-button/gestural system-navigation
overlay and repeat Interview, actual IME, Result and paused Voice in every requested window.
Mode selection is verified against the system resource and SystemUI setting; the original
overlay enablement is restored. These are inset/layout checks, not physical gesture usability.
Short/enlarged manual windows use one page scroller for thread and composer rather than
nesting a lazy thread viewport inside it. This keeps a suggestion or turn-status label
reachable above the contextual bar; ordinary-height interviews retain their lazy thread.
With an open IME in a short window, the contextual bar joins that scroller so it cannot
leave less height than an enlarged editor. Back and the complete title remain reachable.
Back reserves the full 48dp control layout, not only an expanded hit area that could
extend above the safe viewport when the bar scrolls into view.
An opening provider failure still lands on the current question. A delayed submission
failure preserves an already visible editor without pulling an older-history reader to it.

## State, focus and elevation

| Web token | Native treatment | Web value |
|---|---|---|
| `--state-hover` | state layer, hovered | 8% of the content colour |
| `--state-focus` | Material interaction and control outline | 9% |
| `--state-pressed` | Material interaction/ripple | 9% |
| `--focus-ring` / `--focus-offset` | contrast-checked native control outline | 2px `--primary`, 3px offset |
| `--elev-0`, `--elev-2`, `--elev-3` | conceptual elevation roles | flat background/surfaces, docked composer, menus/dialogs/toasts |

Primary, secondary (`ghost`), quiet, tonal and destructive buttons share foreground-mixed
state layers. Disabled controls remain opaque and readable, use dashed edges and the native
disabled attribute; busy controls can use the native `aria-busy` attribute, a doubled edge
and an operation label. Selected destinations have an edge as well as a tonal fill.
The contrast probe rejects element/ancestor opacity rather than reporting unfaded token
contrast; the actual disabled Done menu action is checked in its open popover too.
The 9% focus/pressed mix is measured, not arbitrary: 10% took petrol quiet-button text
to 4.49:1 on the raised surface. The outline/inset edge distinguishes those states.
Fields retain visible labels/placeholders, an outline on keyboard focus and an applicable
`aria-invalid` edge; they do not acquire a second JavaScript validation system.

Settings groups use separators instead of repeated card chrome. Cards and document
surfaces are flat; genuine floating menus and errors retain their operative edge and
foreground shadow. The shared `.callout`, `.hint`, `.section-head`, `.row`, `.idea-menu`
and HTML `dialog` compositions reuse the existing classes and browser primitives. Existing
Delete/Forget confirmations remain `window.confirm`, with browser-owned appearance and
behaviour; this slice does not replace them with a new modal flow.

Native `ui/components/Controls.kt` supplies consumed Material button variants, fields,
suggestion/filter chips and `IdeaForgeStatusText`, not a UI framework. Shapes, readable
disabled ink/fills and minimum targets are shared; callbacks, native roles, selected/
disabled semantics, input, ripple and system animation scaling remain Material's.
Focused controls have a 2dp outline checked against the adjacent painted surface.
Busy controls retain the operation label and add the localized `Working` state
description. Warning/error status includes a localized textual prefix and a polite
live region; the voice phase is polite and the question is a heading, not an announcement
on every level sample.

`IdeaForgeSnackbarHost` in `ui/components/NoticeHost.kt` keeps the native queue,
transitions and original full message. Its message viewport is scrollable and bounded
by a quarter of available height, at most 128dp but never less than one body line.
A localized 48dp Dismiss action closes the actual notice. Manual Interview chooses
indefinite duration and retains ViewModel ownership until dismissal; other existing
notices keep their native timing. Interview/voice notices take
layout space rather than covering their controls. A short-height Result places the same
actions in its document scroll container; its notice reserves viewport space outside
that scroll so a delayed failure is visible even after the reader scrolls away.
Ordinary-height Result keeps its existing dock and action order.

## Contrast and targets

`test/browser/contrast.browser.mjs` measures from computed styles in both themes. It checks
the tokens, and it checks the components as drawn: a component mixes tokens, so a pair can
pass in the table above and still fail on screen. A tonal chip, for example, is `--primary`
at 10% over a card. Colours are composited over the real stack of backgrounds behind each
part.

- **Text**: 4.5:1. Large text (24px, or 18.66px at weight 700) needs 3:1. This covers every
  text role on every surface, and the text of buttons, chips, badges, tags, coverage labels,
  thread bubbles, menus, toasts, placeholders, the skip link and every voice-stage line in
  every phase. Disabled text is voluntarily held to 4.5:1 as well.
- **Non-text**: 3:1. This covers `--outline` on every surface, and the focus ring
  (`--primary`) on what sits behind each control. It also covers field, ghost-button and chip
  edges, the switch's track and thumb in both states, the overflow menu's and toasts' edges,
  the current navigation item, the selected library card, icons on their buttons, and the
  voice stage's phase glyph and button icons on their circles. The coverage meter's segments
  are measured by `coverage.browser.mjs`.
- **Exempt**: container edges that identify nothing, such as cards, the question card and
  dividers. WCAG exempts inactive components, but the web tests retain a readable disabled
  text/edge contract. The voice stage's tonal ring and the loading skeleton bars only repeat
  state that text already shows: the status
  line under the ring, the caption or title beside a skeleton, and "Loading ideas…" in the
  library count. The text is measured instead.

Chip borders stay primary at 70%, and menu/toast edges stay `--outline`: those controls
need a boundary, whereas an ordinary surface does not. The trusted-input accessibility
phase measures hover, pressed, keyboard focus, disabled, busy, field errors and textual
error/success/warning callouts on all four surfaces, including native popovers. It also
exercises an HTML dialog, 200% text/long labels at 320/360/600/840/1440, coarse targets and
forced colours where Chromium supports them. These are component/regression checks, not a
claim that new dialog workflows or the later screen compositions have been delivered.

**Targets.** Under `pointer: coarse`, every control is at least 48 by 48 CSS pixels. That
is the project's comfort target, not WCAG's minimum. Voice start/stage actions are at least
64px high. WCAG 2.5.8 asks for 24. Two exemptions apply.
A link inside a sentence is sized by its line, which is WCAG 2.5.8's inline exception: the
key link in the API key hint, and the Guide link in the version line. The desktop coverage
panel's summary is a heading there (`aria-disabled`), not a control.
`test/browser/a11y-keyboard.probe.mjs` measures every panel under real touch emulation, at
phone and desktop widths, with menus open. The same probe walks each panel with real Tab
presses and checks the reading order.

Native `FoundationThemeTest`, `FoundationControlsTest` and `FoundationPaletteTest` check
the rendered fallback mapping, all type/shape roles, consumed control paint and semantics.
Normal, disabled and busy text is checked at 4.5:1; operative edges/focus at 3:1.
The native palette checks include the composed 12% Material state layer and elevated dock,
not the web's measured 9% layer. Font-scale tests use reflow/scrolling and full painted
labels; targets remain 48dp and voice actions 64dp. The device matrices below supplement
those tests with actual rendered geometry. Representative simulated palettes are not
proof of every wallpaper or a physical-device accessibility certification.

## Type

IBM Plex Sans on the web. Weight 600 carries headline and title roles, and the screenshot
harness checks the font has loaded before it captures anything. The Android app has no
bundled or downloadable font yet, so it uses the platform default family with the same
mapped sizes, line heights and 600 weight. Native body/supporting roles are completed
explicitly rather than inheriting Material's smaller metadata defaults.

| Web token | Compose `Typography` | Size / line height |
|---|---|---|
| `--type-display-*` | `displaySmall` | 2.125rem / 1.2 |
| `--type-headline-*` | `headlineSmall` | 1.75rem / 1.25 |
| `--type-title-*` | `titleMedium` | 1.125rem / 1.35 |
| `--type-body-*` | `bodyLarge` | 1rem / 1.6 |
| `--type-label-*` | `labelLarge` | .875rem / 1.35 |
| `--type-meta-*` | web metadata | .875rem / 1.45 |

All 15 native `IdeaForgeTypography` roles use `FontFamily.Default`:

| Native roles | Size / line factor | Weight |
|---|---|---|
| `displayLarge` | 57sp / 1.2 | 600 |
| `displayMedium` | 45sp / 1.2 | 600 |
| `displaySmall` | 34sp / 1.2 | 600 |
| `headlineLarge` | 32sp / 1.25 | 600 |
| `headlineMedium`, `headlineSmall` | 28sp / 1.25 | 600 |
| `titleLarge` | 22sp / 1.35 | 600 |
| `titleMedium` | 18sp / 1.35 | 600 |
| `titleSmall` | 16sp / 1.4 | 600 |
| `bodyLarge`, `bodyMedium` | 16sp / 1.6 | 400 |
| `bodySmall`, `labelMedium`, `labelSmall` | 14sp / 1.45 | 400 body, 500 label |
| `labelLarge` | 14sp / 1.35 | 500 |

The export renderer's explicit code monospace remains; no bundled/downloadable font or
remote font provider is added to Android.

## Spacing and shape

The web's spacing steps are `--s1` … `--s8` (4, 8, 12, 16, 24, 32, 48 and 64px), its radii
`--r-sm`, `--r-md`, `--r-lg` and `--r-pill` (8px controls, 12px surfaces, 16px dialogs and a
full pill for tags/status), and its control height `--control-height` (48px).
`--voice-control-height` is 64px; `--voice-touch-height` adds room to the touch circle at
72px. The existing voice presentation keeps its circles rather than being recomposed here.
The Start buttons' intrinsic width is bounded by their available row, so doubling text
does not turn a 10rem minimum into a 320px control inside a narrower gutter. Desktop manual
errors reserve scroll space as Done already did, keeping Send clear of a long save failure.
The existing fixed phone navigation shares one font-aware height with body clearance and
reserves two complete label lines. Fractional line interpolation undercounted the wrapped
label at 175% text. Scroll clearance adds an 8px gap for the 2px focus outline and its
3px offset; shell probes check nearest scrolling at 150%, 175% and 200% in both themes.
Existing form/work/settings widths are named tokens, not new adaptive-shell breakpoints.
Native `Roles.kt` owns `IdeaForgeSpacing`: `micro`, `tight`, `related`, `content`, `section`,
`spacious`, `control` and `voice` are 4, 8, 12, 16, 24, 32, 48 and 64dp.
`IdeaForgeShape` supplies 8dp controls, 12dp surfaces, 16dp dialogs and a status circle.
`IdeaForgeShapes` maps Material's extraSmall/small to control, medium to surface and
large/extraLarge to dialog. Buttons, fields and chips consume the control shape explicitly.

`IdeaForgeWidths` bounds native form/reading to 720dp, work to 1120dp, Settings to 1000dp
and answer bubbles to 520dp. Onboarding uses a 480dp minimum form and a 280dp supporting
pane, scaled when deciding whether both fit. These are content measures, not web breakpoint copies:
onboarding, Library, manual Interview, Settings, voice and Result consume them using
native layout constraints. Enlarged action groups wrap without changing order; the
turn-status text and unreadable-key recovery can grow/scroll. This foundation does not
replace the adaptive library panes or the later screen recompositions.

| Native width role | Default bound |
|---|---|
| `rail` | 80dp |
| `form` | 720dp |
| `formMinimum` | 480dp |
| `support` | 280dp |
| `sectionIndex` | 200dp |
| `tableCell` | 200dp |
| `work` | 1120dp |
| `settings` | 1000dp |
| `reading` | 720dp |
| `answer` | 520dp |

## Motion

Interaction paint uses only the consumed 100–180ms steps. Under
`prefers-reduced-motion: reduce` every `--dur-*` token becomes `0s`, so any rule that uses a
token is reduced automatically. The
Android app follows the system's animator duration scale.

| Web token | Compose | Value |
|---|---|---|
| `--dur-short1` … `--dur-short4` | web interaction steps | 100, 120, 150, 180ms |
| `--dur-medium2`, `--dur-medium3` | web entry/state paint | 180, 150ms |
| `--dur-long2` | actual loading, not dispatch | 400ms; the skeleton pulse lasts three steps |
| `--ease-standard` | `EasingStandard` | `cubic-bezier(.2, 0, 0, 1)` |
| `--ease-emph-decel` | `EasingEmphasizedDecelerate` | `cubic-bezier(.05, .7, .1, 1)` |

Panels enter with one keyframe, `panel-in` (opacity and a `--s1` rise over `--dur-medium2`
on `--ease-emph-decel`), keyed off the `data-enter` attribute that `show()` sets in the same
task. The panel is displayed and focusable from the first frame, so focus, speech priming
and `navigator.share` never wait for it. The rule exists only under `no-preference`.

The three navigation buttons (Ideas, which is also the back arrow, New idea and Settings)
go through `navigateTo()`, which runs the change as a same-document View Transition: the
default root crossfade, over `--dur-short4`. It is skipped when the API is missing, under
reduced motion, and while voice is in use. No other control is wrapped. The browser calls
the update a frame after the click, and Start voice, Resume, Preview and Share need their
gesture inside the click. While a transition runs, a tap reaches nothing, which is why the
crossfade is short. Only the synchronous screen change runs inside it, never the storage
reads around it. A navigation that a later tap superseded is skipped. After navigating,
focus moves to the destination's heading (`tabindex="-1"`, no ring) without scrolling.
Interview, Done and the voice stage keep their own focus targets.

Loading placeholders (library cards, the skeleton question card, the write-up in `#output`)
share one `skeleton-pulse` on `--surface-container`, timed from `--dur-long2`, under
`aria-busy` on the region they stand in for; reduced motion stills them. The skeleton
question is sized in its real rows' own type (`1lh` of each, a chip's min-height), so a
typical question replaces it with no visible shift.

## Icons

Material Symbols Rounded at 24px, weight 400: the same glyphs as Compose's
`Icons.Rounded`. On the web they live in `src/ui/icons.css` as data-URI masks, painted with
`currentColor`:

```html
<button data-icon="mic">Answer out loud</button>
```

A mask on `::before` rather than an inline `<svg>` for three reasons. The CSP allows images
only from `'self'` and `data:`. `app.js` rewrites some button labels, and a pseudo-element
survives that. And the icon adds no text, so tests that find a button by its exact label
still find it.

## Focus Studio contract

Focus Studio is a quiet working space for a question, an answer and a useful document.
Navigation and metadata recede without becoming faint; separators and alignment replace
repeated card borders. No dashboard, decorative gradient, glass surface, stock illustration,
confetti, AI-confidence gauge or fabricated waveform earns a place in the interview.

Open the [static reference boards](design/focus-studio.html) from a repository server:
`npm run serve`, then
`http://127.0.0.1:8765/docs/design/focus-studio.html`. They are repository documentation,
not a production route, functional prototype, README screenshot or native-device capture.
The boards use local assets and system fonts, have no script, make no provider or media
request, and are excluded from the assembled site by `tools/assemble-site.mjs`.
Their controls are disabled drawings of the intended controls, not working app actions.
The actual web app keeps IBM Plex Sans; Android keeps its system family and wallpaper
dynamic colour. Native boards show the fallback scheme at logical compact/expanded widths,
not a pixel claim about an emulator or phone.

### Reference inventory and deliberate differences

The main web compositions have light/dark pairs at **390x844** and **1280x900**:
provider choice, ready Start, Ideas list/detail, Interview, listening/paused/recovering
voice, Result and Settings. Compact pairs also show true-empty/filtered/unavailable
Ideas, provider checking/failure, missing model discovery, unsaved Settings, busy turn,
failed save, wrap offer, blocked voice, failed synthesis and long content. Native Ideas
has compact 390 and expanded 840 logical-width compositions in both fallback themes.

These are new targets, not a repetition of the completed first pass (#60):

| Current composition | New target |
|---|---|
| Web labelled adaptive rail and task-specific width (implemented in #191) | Later screen recompositions retain this shell |
| Large repeated library cards/edit disclosures | Compact rows, direct compact Continue/View result, selected expanded detail action |
| Tall connection/hero stack | Short lead, provider/required-fields/ready stages in the existing shared form |
| Question, coverage and composer competing | Current question first, quiet history/progress, compact expanding composer |
| Result inside a 60dvh card | Page-scrolling document and direct Copy/Download or Share/Copy |
| Native shared foundation (implemented in #190) | Later adaptive shell and complete screen compositions |

Long/enlarged content is allowed to grow. Reference density is not permission to shrink text,
clip a caption or remove an action. The state matrix, source owners and actual browser/native
checks take precedence over a pleasant drawing.

### Proposed roles and measurements

`design/focus-studio.css` owns only these reference values. Web and native implementation
issues migrate their actual owners independently; Android dynamic colour remains supported.
The current web table above records actual composed-contrast adjustments rather than
pretending every reference hex can be used unchanged. The native fallback table records
the same roles with explicit Compose composites and dynamic-colour differences.

| Role | Light | Dark | Intended use |
|---|---|---|---|
| Ground | `#F5F7FA` | `#11161B` | Quiet page/workspace |
| Lowest surface | `#FFFFFF` | `#1B222A` | Foreground work, fields |
| Container | `#E8EDF2` | `#27313B` | Quiet selection/hover/grouping |
| Raised | `#DEE6EE` | `#313E49` | Menu/dialog/foreground support |
| Ink | `#17212B` | `#EEF3F8` | Body and headings |
| Secondary ink | `#526171` | `#B4C0CB` | Readable metadata/help |
| Outline | `#718091` | `#91A1B2` | Operative field/control boundaries |
| Primary | `#0F6963` | `#7AD5C7` | Main action, selection, focus |
| On primary | `#FFFFFF` | `#0A332E` | Primary-button text |
| Error | `#A83C3A` | `#F3AAA0` | Failure/destructive text |
| Warning | `#765C20` | `#E7C882` | Untested/pending/degraded text |

Opaque-pair tests require body, secondary, primary, error and warning text to reach 4.5:1
on every proposed surface, outline to reach 3:1, and on-primary text to reach 4.5:1.
This is a baseline, not proof of blended component states or native dynamic palettes.
The web foundation also uses computed/composited contrast probes; the native foundation
uses its own component, palette and device evidence described above and below.

| Role | Reference value / rule |
|---|---|
| Spacing | 4, 8, 12, 16, 24, 32, 48 and 64px/dp steps |
| Shape | 8px/dp controls, 12 surfaces, 16 dialogs; pills only for tags/status |
| Body | 16px/sp, approximately 1.6 line height, regular weight |
| Metadata | 14px/sp, approximately 1.45 line height; never lower contrast to create hierarchy |
| Titles | 24-28px/sp, 600; long titles wrap or retain accessible full text |
| Reading measure | Approximately 65-72ch for a document; 680-760px equivalent for conversation/forms |
| Primary | One strong action for the task/state; useful secondary actions remain visible |
| Focus | 2px primary outline with 3px separation; do not rely on a tonal fill alone |
| Touch | 48x48 CSS px on coarse pointers / 48dp; voice controls at least 64px/dp high |
| Motion | Optional, usually 100-180ms and at most 4px displacement; reduced/system-scaled |
| Elevation | Background is flat; menus/dialogs, not every card, get foreground treatment |

Hover/pressed/selected/busy/disabled/error/success must be distinguishable in text or
shape as well as colour. Busy controls describe the actual operation, not a made-up
progress percentage or remaining time. Motion cannot delay input, microphone permission,
speech priming or `navigator.share`.

### Layout and primary-action rules

| Surface | Compact | Expanded | Primary action |
|---|---|---|---|
| Shell | Below 600 CSS px: bottom destinations on Ideas/Start/Settings; contextual Interview/Result top bar | 600-1199: compact labelled rail; 1200+: roughly 208px rail | Destination or current task, never an extra dashboard |
| Connection | Concise provider -> required fields -> ready stages; required labels/check/recovery visible | Bounded form/support width, not stretched fields | Choose/Continue for the current required step |
| Ready Start | Voice first, Type instead visible, before optional/recent content | Same priorities with connection summary | **Start with voice** |
| Ideas | Compact title/status/metadata rows, search/filter summary, direct resume/result | List/detail from 1024px when both panes fit; selected detail holds the main action | New idea; Continue/View result for the chosen idea |
| Interview | Current question above an expanding IME-safe composer; quiet inspectable history | Bounded conversation; optional factual dimension detail | Send answer |
| Voice | Focused phase/question/caption/recovery, stable bottom controls | Constrained reading width, no unrelated navigation | Pause, or Resume after a pause |
| Result | File-capable Share .md primary with Copy visible; otherwise Copy primary | Copy markdown primary, Download .md visible, Share available | Capability/context-dependent as stated |
| Settings | Connection, Voice, Appearance, Your data, About in a predictable sequence | Quiet section index/support column and bounded form | The explicit action for the edited operation |

Native uses installed Material window classes and adaptive panes, not copied CSS breakpoints.
The native New idea/onboarding completion choice is explicitly Voice primary/Type secondary;
mere navigation starts no microphone/service. Compact native Result uses Share primary/Copy
visible; expanded uses Copy primary/Save visible, with native Share and CreateDocument intact.

The normal 390x844 library fixture should show its first row by about y=320 and three
complete short-title rows before navigation. The normal composer aims at 140-180px before
multiline growth. These are inspection targets for the fixture, not forced heights.
At 200% text or small landscape sizes, reflow/scrolling preserves the task instead.

### Source-owned state and transition matrix

This is the implementation contract, not new reducers or a second state store. The owner
column identifies existing data/effect ownership. New view-only presentation state belongs
with that UI; no credential, backup or session schema is introduced here.

| State / entry | Truthful presentation and actions | Focus / announcement / next transition | Existing owner and boundary |
|---|---|---|---|
| First visit, no provider | Concise provider/connection flow; required labels and privacy/network consequence. No mic request. | Heading/first required control; advance or Back without losing typed values. | Web `boot`, `onProviderChange`, `readRingFromForm`; native keyring gate, `OnboardingViewModel` |
| Configured, no ideas | Voice primary and Type alternative on web; native empty Library/New idea choice. Show saved key, not asserted health. | Start heading, deliberate mode tap; seed exactly one new session. | Web `connectionReady`, `startInterview`; native `AppShell`/`InterviewViewModel` |
| Existing ideas | Open Ideas; selected row/detail reflects the same session. Resume active/View result done. | Destination heading, not automatic search focus; retain valid selection on resize. | `boot`, `createLibraryView`/`syncSelection`; native `LibraryViewModel`/adaptive panes |
| Provider checking | Checking the selected provider; label busy operation, keep entered data. | Polite bounded status; success/error returns to the responsible operation. | Web check handler/provider adapter; native `ProviderCheck` |
| Key saved / check passed | Saved means local persistence only. A check passed at that attempt, not permanent connectivity. | Announce the specific operation once; after reload/provider/key change do not invent a cached health verdict. | Keyring/store plus view-local check state; no second health store |
| Provider/check failure | Specific actionable key/server/CORS/LNA recovery; no success badge. Typing remains possible when the normal start rules permit it. | Responsible field/status; reveal failed connection fields without discarding values. | Existing provider errors, `fail`, `renderConnect`; native check failure |
| Model list unavailable | Show failure and allow a known required model to be typed. Do not clear a valid manual model or fake an empty successful list. | Model field and polite status; retry or retain manual entry. | Provider model discovery and model-required guards |
| Unsaved form / Back / Change | Typed form beats older saved credentials; changing view preserves fields. Save is explicit where required. | Section/changed field; validation stays local, no secret write per keystroke. | Web shared `panel-setup`/`readRingFromForm`; native settings/onboarding state |
| Busy turn / repeated Send | Actual Thinking/processing state, current question/history and preserved answer; no guessed percentage. Answer operations remain guarded. | Polite state once; settled next question receives task focus only when appropriate. | `busy`/`sessionWork`/`currentView`; native `InterviewWork` |
| Reading old history / next turn | Keep all settled pairs and accurate Show earlier count. Do not yank a reader to the live end. | Preserve scroll/focus when inspecting; live-end users can reveal the new question. | `renderThread`; native Interview presentation over its ViewModel |
| Suggestion / typed / voice / skip | Chips fill editable text, never submit automatically; source marker remains truthful. Skip is not an answer. | Composer after a chip; submit/skip through the existing path only. | `renderChips`, `submitAnswer`, reducers/provenance/ratchet; native port |
| Offline / checklist fallback | Cached app/local ideas are not proof of model connectivity. Current checklist source and sticky degraded history are visible. | Polite source/failure status; continue/retry through normal provider policy. | SW shell/store, turn `questionSource`, `warn`/`fail`; native provider/runtime |
| Failed save | **Not saved to this device**; export before closing. Persistent until saved or deliberately dismissed, not a success toast. | Error available without covering Send/Pause/Exit/export; next turn cannot erase it. | Web `persist`/`saveFailure`/`saveWarning`; native store/work error |
| Wrap offer | Once-only Write it up versus Keep going; never disguised as an answer chip. Hands-free speaks it without a manual card. | Offer actions reachable; Keep going returns to the same question/task. | `renderWrapOffer`/`wrapOffered`, engine advisory; native core/UI |
| Writing / synthesis failed | Writing is actual work. Failure says refined prompt was not generated; transcript/open questions/coverage remain exportable. | Result heading or persistent failure; retry/Ask me more uses the same idea. | `runSynthesis`, `renderDone`/`showDone`, canonical export; native Result/InterviewWork |
| Voice permission denied | Mic permission is blocked/unavailable; Type instead/Exit available. No recognizer/service starts. Native notification denial is separate and nonfatal. | Rationale/recovery status; only explicit voice retry requests permission again. | Web probe/controller and `startInterview`; native Interview permission launchers |
| Voice listening / no samples | Actual phase/backend, current question and full caption. **No level reading** is honest, never a simulated meter. | Bounded status/captions, not an announcement per sample; Pause/Exit remain reachable. | `createVoiceStage`, supplied mic samples; native `VoiceStage`/`Recognizer.samples` |
| Voice transient recovery | Existing bounded repeat/retry/skip ladder with factual detail, not an unbounded spinner or forced tap for every miss. | Status plus captions; fatal input/output failure stands down. | `createDriveLoop`; native `DriveLoop`/`DriveController` |
| Voice Pause / Resume | Pause stops effects, preserves available draft; Resume needs a deliberate tap. Web pending transcription may settle; native preserves available caption/segments. | Paused status, Resume primary; retired effects cannot restart capture. | `pauseVoice`/`voiceEpoch`; native controller job/epoch and service |
| Voice Exit / late work | Exit returns to manual/result without skip/wrap; late work may settle only for its original idea. Cancelled unfinished audio is reported. | Appropriate manual/result task heading; no hidden resume listener. | `exitVoice`/`currentView`/`sessionWork`; native app-scope work/controller |
| Empty / filtered / unavailable Ideas | Separate No ideas yet, No ideas match (Clear filters) and Ideas unavailable (Retry) compositions. An unreadable store is never an empty library. | Heading or active filter, polite load/error/count; controls remain useful. | `createLibraryView` loaded/filter/zero states; native library/store |
| Active preview, no synthesis | Latest question/answer/draft and **No write-up yet**, not an invented generation failure. Canonical export is unchanged. | Selected idea title; Continue is the main action. | `libraryCard`/detail renderer/session status; native preview/ViewModel |
| Rename / tag / archive / restore | Explicit edit surface; archive is reversible and distinct from permanent Delete. Pending work retains newer metadata. | Return to changed row/detail, next selection when hidden by filters. | Reducers, `withLibraryMetadata`, library callbacks; native ViewModel/store |
| Delete / Cancel / Escape | Clear consequence and Cancel/destructive action. Await confirmation's boolean; cancel/Escape makes no mutation. | Dialog containment then original/next row/heading; late work cannot recreate a deleted idea. | `confirmDelete`/library callback, deleted-session guards; native confirmation/store |
| Import / collision / failure | Sessions only; collision becomes a copy. Corrupt/newer data and storage failure are explicit, not partial success. | Dialog/operation status then resulting rows; focus remains reachable. | `buildBackup`/parse/import/store; native backup jobs/store |
| Forget / delayed check | Forget revokes credentials before delayed work can restore them; ideas/preferences remain. Distinct STT/TTS/inference consents/checks stay separate. | Confirm/Cancel; status identifies what was removed, no false deletion of ideas. | Web credential epoch/keyring; native revoke/`saveIf` generation |
| Long text / large fonts / keyboard | Full question/caption/labels/document accessible by reflow/scrolling; no forced fit, clipped CTA or page-wide sideways scroll. | Fully visible focus and owned IME/safe-area lift; no speech/share delay. | `trackComposer`, shell CSS; native scaffold/inset ownership |

The current shared native inference agreement presents a URL-specific warning for
unencrypted HTTP outside loopback, without adding another saved consent. HTTPS and
loopback addresses do not show that LAN warning.
The native onboarding layout consumes #173 privacy links, #174 interview consent
and #177 LAN warnings. Reporting delivery (#175/#183), Play listing/icon/publication
(#179/#180/#171/#182), and physical recognition evidence (#89) keep their existing owners.
No design reference approves a new contact, reporting endpoint, paid call or Play URL.

### Evidence and implementation handoff

#### Development fixtures and captures

[`tools/fixtures/ux.mjs`](../tools/fixtures/ux.mjs) owns the deterministic state inventory
and reducer-built session seeds. Regenerate its cross-platform data with
`node tools\ux-fixtures.mjs`; `--check` and `test/ux-fixtures.test.mjs` reject drift.
[`test/fixtures/ux.json`](../test/fixtures/ux.json) is test/debug data, not a session schema,
credential store, production route or published asset. It includes mixed and large libraries,
maximum-length names/tags, long interviews/documents, checklist provenance and effect-failure
states. Active ideas have no synthesis; that is not a failed completed result. A descriptor
with `platform: "android"` is native-only: `voice-writing` covers native's distinct Writing
presentation, which the web voice renderer does not support. Web requests for it fail
explicitly rather than inventing a phase.

The development browser runner reuses `tools/lib/harness.mjs` and the assembled static tree:

```powershell
node tools\ux-captures.mjs --out C:\scratch\ideaforge-ux
node tools\ux-captures.mjs --out C:\scratch\ideaforge-ux-large --text-scale 2
```

The default requests every web-applicable state in both themes at 390x844 and 1280x900. Each state also
measures 320/360/600/840/1440-width reflow; small windows use a 640px height. `--states`,
`--theme` and `--viewport` narrow a run. Output must be a new development-artifact directory,
not the canonical README inventory. The manifest identifies the tested SHA, requested and
completed states, actual viewport/font size, geometry, evidence kind and artifact paths.
Large pages are tiled rather than silently cropped. A missing state, inaccessible necessary
control, wrong font, external model/media request or page-wide overflow fails the run and
leaves a failed manifest. Existing approved Google Fonts requests remain; they are not
inference or speech calls.

Clean-head evidence is the default. `--allow-dirty` is only for iteration and records
`dirty: true`; it is not final tested-commit evidence. Fresh profiles/origins and owned
scratch trees are removed when a run settles. Only the model/effect failures are scripted;
real app navigation, stores, rendering and layout run normally. Voice phase boards are
explicitly **isolated-component** evidence using shipped markup and its renderer, with no
microphone, speech service or supplied level samples. They do not demonstrate live drive
sequencing. The existing required browser/policy checks still own those regression claims.

State-settle measurements, including the large-library fixture, record their isolated
Chromium/scripted-input setup. They are lab samples, not field INP/CLS or user percentiles.
The harness deliberately refuses known-bad layouts: for example, the pre-foundation Start
buttons overflow at 320px with 200% text. A failed baseline is a defect to fix in the owning
visual slice, not permission to hide controls, weaken geometry or report a pass.

Native captures need a rendering-enabled image. The existing `pixel6Api34` ATD is useful
for semantic walkthroughs but disables drawing and omits SystemUI/IME; a successful ATD
test alone cannot supply visual or system-bar evidence. `UxCaptureDeviceTest` consumes the
same serialized inventory through debug-only `UxFixtures`. Real-app states navigate
`MainActivity` with private fixture files, a fixed injected clock, dummy encrypted
credentials and scripted effects. Voice presentations use the non-exported debug-only
`UxFixtureActivity`, visibly labelled **Isolated component** and supplying no level data.
Native ready starts at Ideas, Writing stays in Interview, voice-ready maps to Starting
and voice-complete to Result; manifests record those platform mappings rather than
claiming identical routes/phases. The native record fallback now has its own actual
Transcribing status. Static phase captures still do not perform an upload.

From `android\`, with JDK/SDK paths and an explicit owned `ANDROID_SERIAL`, request the
matrix against the tested checkout:

```powershell
$sha = git rev-parse HEAD
.\gradlew.bat :app:connectedDebugAndroidTest `
  '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true' `
  '-Pandroid.testInstrumentationRunnerArguments.class=io.github.jaypetez.ideaforge.ux.UxCaptureDeviceTest' `
  '-Pandroid.testInstrumentationRunnerArguments.uxCapture=matrix' `
  '-Pandroid.testInstrumentationRunnerArguments.uxCaptureWindow=all' `
  "-Pandroid.testInstrumentationRunnerArguments.uxCaptureSha=$sha"
```

`uxCaptureStates=voice-listening+voice-writing+result-long` selects a focused set; use `+`,
because AGP truncates comma-separated runner-property values. `uxCapture=walkthrough`
requests only the real scripted seed/chip/Result frames with the same window/theme/font
matrix; it rejects state selectors and does not certify the full stress matrix. Without capture opt-in the
ordinary connected walkthrough remains unchanged. Each requested compact/expanded window
runs light/dark at actual system font scales 1.0/1.3/2.0. The manifest records measured
pixels/dp/density, font scale, dynamic/fallback colour source, mappings, controls, PNG and
semantics paths, and the explicitly supplied SHA. Use a clean unchanged checkout for final
evidence; the caller-supplied SHA does not certify an uncommitted tree.
`uxCaptureSourceState=development-dirty` explicitly labels iteration; its `clean` default
is only valid for a genuinely committed, unchanged checkout.

Evidence lands in `connected_android_test_additional_output/` or
`managed_device_android_test_additional_output/` beneath `android/app/build/intermediates/`
or `build/outputs/`. The current managed runner uses variant/task directories under
`intermediates/`; the workflow collects both locations. Failed navigation retains an explicitly
unverified current-UI PNG; late walkthrough failures preserve earlier independent frame
records. Finalization is attempted even after cleanup failures, which are recorded in the
terminal failed manifest. Missing requested states, blank/black images, lost action labels
and clipping fail rather than becoming skipped passes. The Android-device workflow runs
the original ATD walkthrough plus rendering-enabled `pixel6Api34Visual` and
`pixelTabletApi34Visual` captures, uploading named evidence and reports after success or
failure without changing the required `ci` gate.

#### Complete native makeover acceptance

Dispatch the existing optional workflow with an explicit feature ref:

```powershell
gh workflow run android-device.yml --ref '<feature-branch>' -f evidence=makeover
```

This selects `UxCaptureDeviceTest#requestedNativeMakeoverAcceptance`, not the default
visual-test discovery path. The local equivalent, from `android\`, is:

```powershell
$sha = git rev-parse HEAD
.\gradlew.bat :app:pixel6Api34VisualDebugAndroidTest `
  '-Pandroid.enableAdditionalTestOutput=true' `
  '-Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect' `
  '-Pandroid.testInstrumentationRunnerArguments.class=io.github.jaypetez.ideaforge.ux.UxCaptureDeviceTest#requestedNativeMakeoverAcceptance' `
  '-Pandroid.testInstrumentationRunnerArguments.uxMakeoverProbe=true' `
  '-Pandroid.testInstrumentationRunnerArguments.uxCapture=matrix' `
  '-Pandroid.testInstrumentationRunnerArguments.uxCaptureWindow=compact' `
  "-Pandroid.testInstrumentationRunnerArguments.uxCaptureSha=$sha"
```

Run the expanded half sequentially with `pixelTabletApi34VisualDebugAndroidTest` and
`uxCaptureWindow=expanded`. The workflow performs the ordinary ATD walkthrough, then both
rendering-enabled halves sequentially. It adds no required CI job or protection rule.
Require nonzero execution of the selected acceptance method, not a successful build,
dispatch, unselected opt-in test or ATD image.

The fixed inventory covers compact 411x914dp, expanded 1280x900dp and short landscape
640x400dp configurations in both themes at actual font scales 1.0, 1.3 and 2.0. Those
are requested window sizes, not physical tablet certification: each record must show
achieved window dp, density, font scale, actual IME and safe bounds. The installed
Material adaptive panes and system navigation overlays are exercised, including gesture
and three-button IME cases at font scale 2.0. Long-content states cover 12/100 ideas,
no matches, selected detail, checklist source, pending work, failed storage/synthesis,
long Result and explicitly isolated paused/recovering/blocked/no-level/notification-off
voice presentations. The 12-idea library, long selection, actual IME, checklist,
failed save, long Result and no-level listening run at every requested font/window/theme.
The hundred-idea/no-match/loading/synthesis/recovery/blocked/notification-off and open
menu/delete-dialog stress cases run at font scale 2.0 in every window and theme;
the joined normal-window journey below runs at 1.0. This avoids repeating every fixture's
Cartesian product while retaining every requested state and every window/font/theme;
the existing per-surface full matrices remain available unchanged.
Required controls are measured after real scrolling, with 48dp
native touch targets and 64dp voice controls; clipped painted labels remain failures.

At font scale 1.0 in each normal window and theme, a joined real-`MainActivity` journey
completes fresh onboarding through a held check, rejected dummy key and successful retry;
Voice/Type choice and rationale cancellation; the canonical typed/chip interview and
Result; actual native Save/Share cancellation, saved canonical markdown and Copy;
same-session Ask me more and rotation; a separate canonical typed wrap followed by Skip;
theme selection, Cancel/Back from Forget, session-only backup and collision-as-copy import,
and malformed-import recovery. File actions use the real system document picker and
FileProvider, not a replacement export. A held scripted speaker and service observer
exercise real UI Pause/Exit plus explicitly controller-level Resume admission without
opening a microphone; UI Resume's permission gate is not bypassed or claimed exercised.
The existing
`AdaptiveShellTest` permission-result cases separately cover microphone denial and
nonfatal notification denial. No test key is transmitted.

Read back `android-ux-evidence-<SHA>` and `android-device-reports`. Require both
`ux-makeover/manifest-compact.json` and `manifest-expanded.json` to be passed, clean,
complete and bound to the intended tested SHA. Inspect their named app PNGs, reachability
shots and `native-system/*-system.png` picker/chooser frames. Every reached app state retains
its unmerged semantics trees, not only failures: heading/name/role/selection, factual
progress/source, polite status and question-before-editor-before-Send order have explicit
assertions. Popup/dialog geometry includes the separate window's screen offset; their
pixel oracle is the requested scheme's native menu/dialog container role, not a dimmed
Scaffold background or a relaxed brightness threshold. The safe viewport also includes
the decor window's actual screen origin: a tablet may letterbox a requested portrait
orientation. Its measured app bounds, not the full display width, govern reachability.

Cleanup closes activities, retires app/main/preference work, removes private fixture
stores and only the created documents/share files, restores the clipboard and display/
font/navigation overrides, and reinstates the original app graph. A cleanup failure
fails the manifest. Final PR evidence records actual run/attempt, source SHA, selected-test
counts, artifact hashes and personally inspected image names. Manual TalkBack,
switch access, physical recognition/microphone contention, screen-off survival and live
hosted speech remain **unperformed**; automated semantics are not screen-reader or
usability certification. Unchanged web README screenshots are not regenerated for this
native-only acceptance route.

The acceptance run exposed a normal-font short-landscape case where retained History
and a persistent notice could leave the actual current-question viewport at zero height.
`InterviewScreen` now deducts those occupied slots before choosing its existing
page-scrolling layout; it does not shrink labels or remove the composer. The focused
`InterviewLayoutTest` uses the achieved 328dp content height and explicitly verifies
normal text scale rather than accidentally inheriting the class's enlarged-text default.
Document acceptance feeds a real `sh` through Android 34's stderr-capable UiAutomation
transport: `executeShellCommand(String)` itself uses `Runtime.exec`, so quoted paths and
shell operators cannot be treated as parsed commands. Nonzero exit status/stderr fail,
and canonical file bytes plus actual removal are checked. Native root navigation waits
for the actionable Downloads drawer row, not its identical non-clickable toolbar title.
Failed journeys retain one
unverified current-UI diagnostic; unvisited states remain failed entries rather than
repeatedly waiting out the same screenshot budget.

The native baseline exposed missing action labels, compact enlarged composer clipping and
a storage-read fault presented as empty Ideas. Those prerequisites are fixed in the shipped
UI: labelled existing actions, wrapping composer actions and a bounded editor leave a real
scrollable question/chip viewport. `SessionStore.loadState` and `LibraryViewModel` distinguish
loading, fresh-empty, filtered-empty and unavailable. Failed reads publish no partial index;
Try again retries the real read without deleting corrupt/newer rows or overwriting them.
Capture tests assert that real unavailable screen, not a test-only substitute. Focused
passing cases still must not be described as a full-matrix pass. Keep actual app walkthroughs,
isolated Compose phase fixtures, dynamic/fallback colours and physical-device results
separately labelled.

The supplementary `requestedShortImeEvidenceKeepsTheEditorAndActionsUsableAboveAnOpenKeyboard`
method in `UxCaptureDeviceTest` opens the actual emulator keyboard at two short landscape
heights, both themes and all three font scales. Select that method with the instrumentation
`class` argument and add `uxShortImeProbe=true` plus the same explicit capture SHA/source
arguments. Its `ux-short-ime` artifacts are separate from the standard matrix. IME avoidance
is outside the composer height budget; short remaining panes scroll rather than collapsing
the editor. Bitmap/control safe bounds exclude the real keyboard and manifests record its
visibility/inset. This is emulator layout evidence, not physical-device input certification.

The supplementary
`requestedShortResultEvidenceKeepsTheFullDocumentAndActionsReachable` method adds
`uxShortResultProbe=true`, `uxCapture=matrix`, `uxCaptureWindow=compact` and the same
explicit SHA/source-state arguments. It records 12 real Result cases: actual 640dp-wide
landscape at 320/400dp display heights, both themes and all three font scales. The parser's
`compact` selector is not a claim that 640dp is a compact Material width class. Its
`ux-short-result/manifest-compact.json` requires a nonzero document viewport, full export
text accessible through scrolling and every original action reachable; device size,
density and font settings are restored.

The supplementary native foundation matrix selects
`requestedFoundationEvidenceSeparatesFallbackAndSimulatedPalettesFromActualWallpaper`:

```powershell
$sha = git rev-parse HEAD
.\gradlew.bat :app:connectedDebugAndroidTest `
  '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true' `
  '-Pandroid.testInstrumentationRunnerArguments.class=io.github.jaypetez.ideaforge.ux.UxCaptureDeviceTest#requestedFoundationEvidenceSeparatesFallbackAndSimulatedPalettesFromActualWallpaper' `
  '-Pandroid.testInstrumentationRunnerArguments.uxFoundationProbe=true' `
  '-Pandroid.testInstrumentationRunnerArguments.uxCapture=matrix' `
  '-Pandroid.testInstrumentationRunnerArguments.uxCaptureWindow=all' `
  "-Pandroid.testInstrumentationRunnerArguments.uxCaptureSha=$sha"
```

Its `ux-foundation` output has 36 base frames: compact/expanded, both themes, all three
font scales and three explicitly identified palettes. `focus-studio` renders the real
fallback through `IdeaForgeTheme(dynamicColor=false)`. `simulated-ocean` and
`simulated-plum` are representative simulated Material schemes, **not actual wallpaper
colours**. All are labelled isolated production-control compositions, not interviews or
voice runs. Normal real-app captures still use the actual system dynamic scheme on
Android 12+. The supplementary probe changes no wallpaper or permission.

Native manifests distinguish raw/clipped screen-space paint bounds from raw/clipped native
touch bounds, including separate popup/dialog window offsets. A stock 40dp IconButton and 52x32dp Switch can expose real 48dp touch expansion;
the target assertion measures that native hit rectangle, while separate checks retain
full painted labels and safe unclipped geometry. Root-theme evidence requires pixels
matching the requested scheme's actual surface, including labelled fallback/simulations.
Whole-image mean luma is diagnostic only: an inverse error notice can dominate it without
changing the root theme. Negative swapped-theme/blank-render guards remain.
Native root capture requires two consecutive requested-root samples within the existing
30-second capture budget. Earlier unsettled display frames remain diagnostic PNGs and
manifest attempts, not passes. The isolated fixture records its actual rendering context;
the oracle remains the requested theme's factory background, never a self-reported
rendered colour. Window PixelCopy is additional diagnostic evidence, not a substitute
for the saved display pixels.

The board checks actual controls, minimum targets and full painted labels through
scrolling; base frames include disabled/busy/selected/destructive/error states and native
menu/dialog launch controls, not additional open-popup screenshots. Selecting the method
without its explicit opt-in fails. It uses the same clean-head/source-state rules and
fail-closed recorder as the other matrices; absent or incomplete requested evidence is
not a pass.

`npm run test:docs` checks the local board inventory, links, primary actions, truthful
recovery copy, absence of scripts/remote resources and opaque palette contrast. Open the
boards in the existing discovered browser to inspect real layout, both themes and every
named viewport/state; Node cannot prove geometry. This is reference-document evidence,
not proof that the application already behaves that way.

Each screen issue updates its owning UI, related guide/README/source-map text and actual
screenshots together. Preserve stable selectors/test tags or migrate every consumer in
the same PR. Never weaken a probe, hide required controls for a capture, change the engine
to suit a walkthrough, or overwrite generated example markdown by hand.

Normal text needs 4.5:1; large/operative non-text 3:1. WCAG AA's target floor is 24 CSS px
with exceptions; the 48px/dp comfort target is a deliberate stronger choice. Full focus
visibility is also stricter than AA's "not entirely hidden" minimum. Test 200% text,
320px-equivalent reflow, keyboard/coarse pointer, reduced motion and native 1.0/1.3/2.0
font scales. Physical phones, manual screen readers, hosted voice/CORS and real-user
usability remain separately evidenced, never inferred from a static board or fake-media pass.

The research supports hierarchy, disclosure, adaptation and honest correction/recovery.
The particular palette, 65-72ch measure, rail size, density and motion are product choices,
not scientifically proven universal values. See
[Apple layout](https://developer.apple.com/design/human-interface-guidelines/layout),
[NN/g progressive disclosure](https://www.nngroup.com/articles/progressive-disclosure/),
[Android adaptive layouts](https://developer.android.com/develop/ui/compose/layouts/adaptive/canonical-layouts),
[Microsoft HAX](https://www.microsoft.com/en-us/haxtoolkit/library/) and the
[WAI contrast](https://www.w3.org/WAI/WCAG21/Understanding/contrast-minimum.html),
[reflow](https://www.w3.org/WAI/WCAG21/Understanding/reflow.html) and
[target-size](https://www.w3.org/WAI/WCAG22/Understanding/target-size-minimum.html)
explanations. Linear's [refresh](https://linear.app/now/behind-the-latest-design-refresh)
is aesthetic inspiration only, not a template or usability proof.
