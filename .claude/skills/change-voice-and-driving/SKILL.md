---
name: change-voice-and-driving
description: Change voice or hands-free behavior while preserving sequencing and browser recovery.
---

# Changing voice or driving mode

Use this skill for `src/voice/`, `src/core/driving.js`, `src/runtime/drive.js`, their UI
wiring, or their browser fixtures. Voice behavior is proven by events and sequencing, not by
feature detection.

## 1. Load the invariants

Read:

- `CLAUDE.md`, "Voice: probe by behaviour" and "Driving mode"
- `src/core/driving.js`, `src/runtime/drive.js`, and the relevant `src/voice/` module
- `test/driving.test.mjs`, `test/drive-loop.test.mjs`, `test/voice.test.mjs`
- `test/browser/dictation.browser.mjs`, `test/browser/driving.browser.mjs`,
  `test/browser/voice.browser.mjs`, `test/browser/voice-stage.browser.mjs`,
  `test/browser/speech-output.browser.mjs`, and `test/browser/fixtures/fake-voice.js`

Keep matching and state transitions pure. Browser APIs belong in `src/voice/` or `src/ui/`;
`src/core/` and `src/runtime/` still receive dependencies and time.

## 2. Preserve the sequencing guarantees

- Transient misses recover without a tap. Empty capture, transient error, and a recogniser
  that goes silent continue through the bounded recovery ladder or stand down explicitly.
- Deliberate Pause, Exit, and fatal input/output failures are explicit stops, not transient
  retries. Pause releases the microphone and retains the draft; Resume requires a fresh tap.
  Never leave a hidden microphone listening for a resume command.
- Cancelling setup must stop the initial recognition probe as well as active capture.
- Resume reads wrap eligibility from the session, not from the lifetime of a fresh loop.
- A bare finish word can send a retained draft without inventing new answer text or changing
  its typed/chip provenance. Repeated misses must not silently skip and erase retained words.
- Manual dictation releases its input after that capture. Reuse between active hands-free
  turns is a separate lifetime, never permission to leave the manual microphone open.
- A trigger word is terminal-only. A command must occupy the whole utterance.
- A matched command carries empty answer text so it cannot reach `submitAnswer`.
- A trigger in an interim, or in a final without confidence, arms settlement; it does not
  discard the clause or stop immediately.
- Long speech goes through `speechChunks`; one oversized utterance can disappear without an
  error.
- The Web Speech probe requires proof of life. Presence, construction, and a clean `start()`
  are not support.

Do not "simplify" rebuilding from the whole cumulative `results` list on every event (never
walking from `resultIndex`), interim/final handling,
`assembleTranscript`'s absorb/revise/sweep rules and its refusal to drop a shorter result,
the `settled` test that makes an unconfirmed final wait like an interim, the deaf watchdog,
or the running-minimum noise floor without a failing behavioral test.

## 3. Make the fake as strict as the platform

The scripted recogniser must preserve the browser's cumulative `results` shape and only feed
utterances under the same flags the production recogniser uses. A forgiving fake can prove
broken code correct. That includes Android's shape: each growing guess at its own index,
already final, with confidence 0, and no interim anywhere in that session — script it as
consecutive `final` steps with `confidence: 0` and no `interim` steps.

`window.speechSynthesis` is readonly in module code. Replace it with
`Object.defineProperty`, not assignment. Speech fixtures must emit asynchronous start and end
events, and late cancelled events must not change a newer utterance's state.

## 4. Test the pure behavior first

Choose the smallest focused command:

```sh
node --test test/driving.test.mjs test/drive-loop.test.mjs test/voice.test.mjs
```

Add tests that assert ordering and user-visible recovery, not implementation details.
Transient recovery must not ask for a tap or typed answer. Explicit Pause/Exit/fatal stops
must not restart secretly, and a fresh Resume must preserve the session and unsent draft.

## 5. Prove browser behavior

Add or update a `test/browser/*.browser.mjs` probe whenever the behavior depends on real
media, Web Speech, IndexedDB, WebIDL properties, the CSP, or UI sequencing.

```sh
npm run test:browser:required
```

Do not use `--dump-dom` or `--virtual-time-budget`; both can report false failures for async
storage and media work. Listen for `securitypolicyviolation`, and clean service workers and
caches between origin-sensitive phases.

## 6. Finish

Run `npm run test:all`. If the UI or README changed, invoke `update-readme`. Name the tested
browser and whether the path used Web Speech or record-and-transcribe in the PR.
