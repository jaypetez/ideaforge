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
  `test/browser/voice.browser.mjs`, and `test/browser/fixtures/fake-voice.js`

Keep matching and state transitions pure. Browser APIs belong in `src/voice/` or `src/ui/`;
`src/core/` and `src/runtime/` still receive dependencies and time.

## 2. Preserve the sequencing guarantees

- The hands-free loop must never come to rest waiting for a tap.
- Empty capture, transient error, and a recogniser that goes silent all continue or stand down
  explicitly; they do not strand the interview.
- A trigger word is terminal-only. A command must occupy the whole utterance.
- A matched command carries empty answer text so it cannot reach `submitAnswer`.
- An interim trigger arms settlement; it does not discard the clause or stop immediately.
- Long speech goes through `speechChunks`; one oversized utterance can disappear without an
  error.
- The Web Speech probe requires proof of life. Presence, construction, and a clean `start()`
  are not support.

Do not "simplify" cumulative Web Speech results, `resultIndex`, interim/final handling, the
deaf watchdog, or the running-minimum noise floor without a failing behavioral test.

## 3. Make the fake as strict as the platform

The scripted recogniser must preserve the browser's cumulative `results` shape and only feed
utterances under the same flags the production recogniser uses. A forgiving fake can prove
broken code correct.

`window.speechSynthesis` is readonly in module code. Replace it with
`Object.defineProperty`, not assignment.

## 4. Test the pure behavior first

Choose the smallest focused command:

```sh
node --test test/driving.test.mjs test/drive-loop.test.mjs test/voice.test.mjs
```

Add tests that assert ordering and user-visible recovery, not implementation details. The
most important failure assertion is that no recovery path asks a hands-free user to tap or
type.

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
