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

**6 · Against a real model, locally — `npm run validate:local`**
The rung that costs nothing, needs no key, and needs nobody watching. It drives the real UI
in headless Chrome against a real Ollama: real HTTP, real CSP, real IndexedDB, no stubs
anywhere. One line per claim, non-zero exit on any failure.

```sh
docker compose -f docker/compose.yml up -d ollama
docker compose -f docker/compose.yml exec ollama ollama pull qwen2.5:7b-instruct
npm run validate:local
```

`IDEAFORGE_URL` points it at an origin that is already serving — which is how the built
container gets validated rather than merely built. `IDEAFORGE_MODEL`, `OLLAMA_URL`,
`VALIDATE_TURNS` and `VALIDATE_TURN_MS` do what they look like. To run the whole thing in
containers, including the browser:

```sh
docker compose -f docker/compose.yml -f docker/compose.validate.yml run --rm validate
```

**What it is really checking is that the run was real.** A failed model call is answered
from the static question bank and the interview carries on, so an interview can complete
end to end having never once reached the model and still look perfectly healthy on screen.
Three independent signals catch that: the `questionSource` the app recorded in IndexedDB,
the absence of the checklist suffix on `#turnline` and `#done-meta`, and — the only one the
app's own bookkeeping cannot fake — a count of the POSTs that actually left the browser,
taken off the CDP network feed.

Before trusting a green run, make it fail on purpose. Point `OLLAMA_URL` at a dead port, or
name a model that is not installed. A validator that cannot fail is not a validator.

**6b · Against a paid provider** — costs money and needs a key, so it is the rung after
that. `npm run serve`, open `http://127.0.0.1:8765`, paste a key. Groq's free tier is the
cheapest way to exercise a hosted model. A full interview on Haiku with Groq dictation
should land around 10–20 cents; wildly more means something is re-sending context it should
not be.

**7 · CI** — six unit legs (Linux/Windows/macOS × Node 22/24) plus the browser job. Windows
is not decoration: two of this repo's toolchain bugs were Windows-only path handling.

## Traps that will cost you an hour

- **A new file under `src/` is invisible to the service worker.** `sw.js`'s `SHELL` list is
  hand-maintained, and a module missing from it fails only on a *cold* offline start —
  after any online visit the stale-while-revalidate handler has cached it anyway, so the
  bug hides from every test you would think to run. `src/version.js` was missing from it,
  and `src/ui/app.js` imports that at module scope. `test/wiring.test.mjs` asserts the list
  is complete; nothing else catches it.

Every one of these has already bitten someone here.

### Running against a local model

- **A degraded interview looks like a healthy one.** This is the big one. When a model call
  fails, `runTurn` falls back to the static question bank and the interview continues —
  correct behaviour, because it never dead-ends, and completely invisible unless you look.
  The live tell is an eleven-word suffix on `#turnline` that describes only the *current*
  question; the sticky one is `#done-meta`. Judging a run by whether it produced an export
  tells you nothing: the single wrap-up call can succeed while every turn failed.
- **A GPU that is not being used does not fail, it is just slow.** Ollama falls back to CPU
  and everything still works, ten to fifty times slower, which reads as "the app is slow".
  `GET /api/ps` gives `size` and `size_vram` per loaded model; compare them. Note the model
  loads lazily, so `/api/ps` is legitimately empty until the first real request.
- **Two Ollamas on one GPU deadlock each other in slow motion.** A native install and a
  container both holding a 7B leave neither enough VRAM, and the second one's load crawls
  past any sane deadline. If the native one is running, `IDEAFORGE_OLLAMA_PORT` moves the
  container's published port, but they still share the card — unload one first
  (`POST /api/generate` with `keep_alive: 0`).
- **Do not bind-mount the host's model store on Windows.** Pointing the container at
  `%USERPROFILE%\.ollama` to avoid re-downloading looks clever and costs 3m28s to load a
  4.7GB model instead of seconds, because every tensor crosses the filesystem bridge. The
  named volume in `docker/compose.yml` lives inside the VM. Re-pulling is faster than the
  shortcut.
- **`ollama/ollama:latest` may be a stale image you already have.** A local `latest` sat at
  v0.23.2 while the host was on v0.34.0, which is an easy hour of blaming the app for a
  fixed bug. `docker pull` first.
- **The app refuses a model endpoint that is not loopback**, which is why the containerised
  harness uses `network_mode: "service:ollama"` rather than reaching `http://ollama:11434`.
  Sharing the network namespace makes `localhost` genuinely loopback inside the container,
  so the run exercises the real rule rather than a hole cut in it. A service using
  `network_mode: service:` may not declare its own `ports:` or `networks:`.
- **A small model gets the coverage judgement right and the key wrong.** qwen2.5:7b returns
  `{"1": {...}}` rather than keying by dimension id. `parseTurnResult` now reads a single
  unkeyed claim as the turn's target dimension and says so in a warning; before that it
  dropped them silently, coverage never rose, and the interview ran to the hard ceiling
  before reporting 0%. If you see coverage stuck at zero against a new model, look there
  first.
- **`AbortSignal.timeout` does not hold Node's event loop open.** A test whose only pending
  work is that timer never sees it fire, and reports "promise resolution is still pending"
  instead of the timeout it was checking for. Hold the loop with an interval. Browsers have
  no such rule.


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

`main` requires a PR and a green `ci` check. Do not merge around it — a check that was
skipped rather than passed tells you nothing.

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
