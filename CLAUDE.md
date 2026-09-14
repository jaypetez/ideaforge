# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

IdeaForge interviews a user about a half-formed idea — one question at a time, each
follow-up grounded in the last answer — and emits a markdown file containing an LLM-ready
refined prompt, the open questions they left, and the full transcript. It runs entirely in
the browser with the user's own API key.

## Commands

```sh
npm test                 # lint:purity, then the full suite (150 tests)
npm run lint:purity      # the architecture gate alone
npm run serve            # http://127.0.0.1:8765  (file:// will NOT work)
npm run screenshots      # regenerate docs/ — the README's images and worked example

node --test test/voice.test.mjs                        # one file
node --test --test-name-pattern="zero gain"            # one test, by name (a JS regex)
node --test                                            # default discovery, no glob needed
```

There is **no install step** — zero dependencies, not even dev dependencies. Node 22+.

`npm run serve` needs `python` on PATH. Any static server works; ES modules, IndexedDB and
the service worker all need a real origin.

`npm run screenshots` needs Chrome. It drives the real app against the scripted interview in
`tools/fixtures/walkthrough.mjs` and rewrites every image in `docs/` plus
`docs/examples/remember-names.md`, so the README shows the app rather than a drawing of it.
`.claude/skills/update-readme/SKILL.md` is the full procedure for updating the README,
including which constants have to be re-derived from source.

## The seam, and why it is enforced

```
src/core/       pure: no DOM, no network, no clock, no randomness
src/runtime/    orchestration: provider and `now` are INJECTED, never reached for
src/providers/  the only directory allowed to touch the network
src/store/      IndexedDB sessions + the encrypted API key
src/voice/      microphone, transcription, speech synthesis
src/ui/         the app shell (the only place that touches the DOM)
```

A new file under `src/` must also be added to `sw.js`'s `SHELL`; `test/wiring.test.mjs`
enforces that, and the CSP allowlist with it.

`tools/lint-purity.mjs` fails the build if anything under `src/core/` **or `src/runtime/`**
mentions `window`, `document`, `localStorage`, `sessionStorage`, `navigator`, `fetch`,
`alert` or `claude`. It runs as part of `npm test`.

This is why the whole suite runs in under a second with no mocking framework and no
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
- **Never trust model output.** `parseTurnResult` and `parseSynthesisResult` assume the
  model lies about shape; every field is optional. Add validation there, not at call sites.
- **`source: 'voice'` exempts an answer from the terse and dodge thresholds**, because
  dictation rambles. Chip and unedited-draft answers are capped at `partial` — those are
  Claude's words, not the user's.
- **`runTurn` bumps `rev` five or six times.** Persist the object it returns, once.

## Voice: probe by behaviour, never by feature detection

Inside an installed iOS home-screen app, `webkitSpeechRecognition` **exists, constructs,
and `start()` returns cleanly — then no event ever fires.** The platform that most needs
voice is the one where feature detection lies. Edge throws `network`; Firefox ships it off.

`probeWebSpeech` (`src/voice/webspeech.js`) starts the engine and requires proof of life
within 1.5s, caching the verdict per origin. Anything else falls through to
record-and-transcribe permanently. Do not "simplify" this into an `in window` check.

`src/voice/vad.js` (silence detection) is pure and clock-injected specifically so it can be
tested without a microphone. Its noise floor is a running minimum, which works because
speech has gaps at word boundaries — that is what lets a recording opening mid-sentence
correct itself rather than staying deaf.

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

## Tests

`node:test` + `node:assert/strict`, flat tests, no `describe`. Every test runs with **no
network and no real timers** — HTTP via an injected `fetch`, time via injected values.

Prefer a test that would have caught a real bug over one restating the implementation.
Several tests exist because the behaviour was wrong the first time, and the comments say so.

## Things not to change

- **No dependencies and no build step.** This is load-bearing, not minimalism: it is what
  makes the strict CSP affordable and keeps a supply chain out of an app that holds the
  user's API key in browser memory.
- **No hash routing in the UI.** WebKit re-prompts for the microphone on hash change in a
  standalone home-screen app; panels are toggled with `hidden` instead.
- **The version lives in two places** — `package.json` and `src/version.js` — because a
  browser ES module can't import JSON without an import attribute and there is no build
  step. A test asserts they match, and `release.yml` refuses to publish if the tag disagrees.
- **`ci` is the one required status check**, an aggregating gate job in
  `.github/workflows/ci.yml`. Never add `paths:` filters to that workflow: a required check
  that gets skipped never reports, and every PR then hangs at "Expected" forever.
- Line endings are forced to LF by `.gitattributes`. `lint-purity.mjs` splits on `/\r?\n/`
  because `.` does not match `\r` — a CRLF checkout otherwise made it report every banned
  word appearing in a comment.
