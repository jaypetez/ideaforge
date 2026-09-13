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

`file://` will not work: ES modules, IndexedDB and the service worker all need an origin.

## The one rule that is enforced mechanically

```
src/core/       pure interview logic — no DOM, no network, no clock, no randomness
src/runtime/    the turn loop — provider and clock are injected
src/providers/  the only place allowed to touch the network
src/store/      IndexedDB and the encrypted key
src/voice/      microphone, transcription, speech
src/ui/         the app shell
```

`tools/lint-purity.mjs` fails the build if anything under `src/core/` or `src/runtime/`
mentions `window`, `document`, `localStorage`, `sessionStorage`, `navigator`, `fetch`,
`alert` or `claude`. It runs as part of `npm test`.

This is not stylistic. It is what lets the interview engine be tested without a browser
and the turn loop be driven without a network, and it is why the whole test suite runs in
under a second with no mocking framework. If you need a platform API, it belongs in one of
the other directories, and the thing that needs it should take it as an argument.

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

## Tests

`node:test` and `node:assert/strict`, flat tests, no `describe`. Every test must run with
no network and no real timers — HTTP is tested with an injected `fetch`, and the silence
detector is clock-injected precisely so it can be tested without a microphone.

Prefer a test that would have caught a real bug over one that restates the implementation.
Several tests here exist because the behaviour they check was wrong the first time; the
comments say which.

## Pull requests

Small and focused is easier to merge. CI runs the suite on Linux, Windows and macOS across
Node 22 and 24 — Windows especially, because two toolchain bugs in this repo's short life
were Windows-only path handling.

Say how you verified it. If it touches voice, storage or a provider, name the browser.
