# AGENTS.md

The working loop for an agent changing this codebase. `CLAUDE.md` covers *what the code is*
and the invariants that matter; this file covers *how to iterate on it and prove it works*.

## The short version

```sh
npm test              # ~1s   — purity lint + 126 unit tests. Run constantly.
npm run test:browser  # ~25s  — 58 checks in headless Chrome. Run before you push.
npm run test:all      # both
npm run screenshots   # ~35s  — only when the UI or the README changes
```

If you change anything under `src/voice/`, `src/store/`, `src/ui/`, `index.html`, `sw.js` or
`manifest.webmanifest`, **`npm test` cannot see your change at all.** Those live in browser
APIs that do not exist in Node. Use `npm run test:browser`.

## The ladder

Run the cheapest rung that can disprove your change, and only climb when it passes. Rungs 1–3
are sub-second; do not skip them to get to the interesting one.

**1 · Purity lint — `npm run lint:purity`**
Fails if `src/core/` or `src/runtime/` references `window`, `document`, `localStorage`,
`sessionStorage`, `navigator`, `fetch`, `alert` or `claude`. This is the architectural seam,
not a style rule: it is what keeps the whole unit suite runnable with no browser and no
network. If it fires, the fix is to move the code or inject the dependency — never to add
the identifier to the allowlist.

**2 · Unit tests — `npm test`**
```sh
node --test test/voice.test.mjs                 # one file
node --test --test-name-pattern="zero gain"     # one test, by JS regex
node --test                                     # default discovery, no glob needed
```
Everything here runs with no network and no real timers: HTTP goes through an injected
`fetch`, time through injected values, the provider through a scripted fake. If a change
seems to need a real clock or a real socket to test, that is a design signal — the thing
under test probably wants its dependency injected instead.

**3 · Module graph** — catches a broken import that no test happens to cover:
```sh
for f in $(find src -name '*.js'); do node --check "$f" || echo "FAIL $f"; done
node --input-type=module -e "await import('./src/providers/index.js'); \
  await import('./src/runtime/turn.js'); await import('./src/store/db.js'); \
  console.log('graph resolves')"
```
`src/ui/app.js` cannot be imported in Node — it touches the DOM at module scope. Rung 5
covers it.

**4 · Logic end to end, no browser** — drive a whole interview against a scripted provider
to see the loop terminate on its own. This is the fastest way to check a change to the turn
runner, the ratchet or the wrap conditions:
```js
// scratch.mjs — delete when done, do not commit
import { createSession } from './src/core/session.js';
import { seedTurn, submitAnswer, runTurn } from './src/runtime/turn.js';
const provider = { async sampleJson(parts) { /* return { json: {...} } */ } };
let s = submitAnswer(seedTurn(createSession({ id: 'x', now: 1 })), { text: 'an idea', now: 2 });
for (let i = 0; i < 30; i++) {
  const out = await runTurn(s, { provider, now: Date.now() });
  if (!out.turn) { console.log('stopped:', out.wrap); break; }
  s = submitAnswer(out.session, { text: 'a substantive answer ' + i, now: Date.now() });
}
```
A healthy run stops at `wrap: 'coverage'` around turn 12 with coverage in the 80s. Vary the
fake's `coverage` claims to exercise the ratchet; give it repetitive question wording to
exercise the tripwires.

**5 · Browser checks — `npm run test:browser`**
The rung that covers everything Node cannot see. `tools/browser-check.mjs` serves the repo,
runs every `test/browser/*.browser.mjs` probe in headless Chrome with a synthesised
microphone, and reports back over HTTP.

Add a probe by dropping a file in `test/browser/`:
```js
export default async function run(check, { subpath }) {
  check('a label that reads as a claim', someCondition, 'optional detail');
}
```
`check(label, ok, detail)` records; a thrown error is caught and recorded as a failure. The
harness exits non-zero if any check fails.

Current coverage: the encrypted key store (ciphertext at rest, non-extractable wrapping key,
deletion), session persistence through IndexedDB, the recorder and silence gate against real
audio, every Web Speech failure mode, and the app booting cleanly at both the origin root and
a Pages-style subpath with no CSP violations.

**6 · Against a real provider** — costs money and needs a key, so it is the last rung, not
the first. `npm run serve`, open `http://127.0.0.1:8765`, paste a key. Groq's free tier is
the cheapest way to exercise a real model. A full interview on Haiku with Groq dictation
should land around 10–20 cents; wildly more means something is re-sending context it should
not be.

**7 · CI** — six unit legs (Linux/Windows/macOS × Node 22/24) plus the browser job. Windows
is not decoration: two of this repo's toolchain bugs were Windows-only path handling.

## Traps that will cost you an hour

Every one of these has already bitten someone here.

- **`--dump-dom` lies about async work.** It snapshots before IndexedDB or `fetch` settles,
  so a page that works fine reports empty. Have the page POST its results instead — which is
  what `tools/browser-check.mjs` does.
- **`--virtual-time-budget` lies harder.** It fast-forwards timers while real I/O still takes
  real time, silently truncating the run. Never use it for anything touching storage or
  media. A "boot produced nothing" result from it is almost always the flag, not your code.
- **Cache and service-worker state is per *origin*, not per page.** A root-scoped worker
  registered by one iframe pollutes what the next one sees. Unregister and delete caches
  between phases.
- **A CSP violation throws nothing and logs nothing to the page.** It fires
  `securitypolicyviolation` and otherwise looks exactly like success. Listen for it.
- **Windows holds the Chrome profile directory** for a moment after `kill()`. Deleting it is
  best-effort; never let cleanup fail the run.
- **Python on Windows cannot resolve Git Bash's `/tmp`.** It writes to `C:\tmp` instead and
  the file you expected is silently empty. Use an absolute Windows path or the scratchpad.
- **Heredocs plus escaping.** A quoted heredoc keeps backslashes, but piping content through
  another interpreter can eat them — a `\n` in generated source becoming a literal newline
  produces a syntax error far from the cause. For files with template literals, regexes or
  nested quotes, write them with a file tool rather than a shell heredoc.
- **CRLF.** `.gitattributes` forces LF, and `lint-purity.mjs` splits on `/\r?\n/` because `.`
  does not match `\r`. A tool that splits on `'\n'` will misbehave on a Windows checkout in
  ways that pass locally and fail in CI.

## Landing a change

`main` requires a PR and a green `ci` check. You have an admin bypass — use it for genuine
emergencies, not for routine work.

```sh
git checkout -b some-branch
npm run test:all                       # before pushing, not after CI tells you
git push -u origin some-branch
gh pr create --base main --fill
gh pr checks --watch
gh pr merge --squash --delete-branch
```

Releases are tag-driven: `v*.*.*` builds archives with `git archive`, plus `SHA256SUMS`. The
workflow refuses to publish if the tag, `package.json` and `src/version.js` disagree, so bump
all three together.

## What a good change looks like here

- **Tests that would have caught a real bug**, not ones restating the implementation.
  Several existing tests exist because the behaviour was wrong the first time, and their
  comments say which. When a probe or a test fails, work out whether the assertion or the
  code is wrong before changing either — both have happened.
- **No new dependency and no build step.** This is load-bearing rather than minimalism: it
  is what makes the strict CSP affordable in an app that holds the user's API key.
- **Comments that say why.** The codebase is dense with them because most of its non-obvious
  decisions are defences against a specific failure that is invisible from the code alone.
