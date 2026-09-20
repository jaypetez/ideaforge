# AGENTS.md

The shared working loop for Claude Code and GitHub Copilot (CLI and VS Code).
[CLAUDE.md](CLAUDE.md) covers *what the code is* and the invariants that matter; this file
covers *how to iterate on it and prove it works*. Read both before changing the code.

The [update-readme](.claude/skills/update-readme/SKILL.md) and
[release](.claude/skills/release/SKILL.md) skills are shared too. Keep one definition of
each in `.claude/skills`, not copies for each assistant. The release skill is explicitly
invoked; preparing its PR is not permission to merge or publish. See
[coding assistant setup](CONTRIBUTING.md#coding-assistants) for discovery and invocation.

## The short version

In a POSIX shell:

```sh
npm test                            # ~1s  — purity lint + the unit suite. Run constantly.
npm run test:graph                  # ~1s  — broken imports and unresolved module edges.
BROWSER_CHECK_REQUIRED=1 npm run test:browser
                                    # ~90s — headless Chrome on the assembled site tree.
BROWSER_CHECK_REQUIRED=1 npm run test:all
                                    # unit + graph + browser, with no silent browser skip.
npm run screenshots                 # ~35s — only when the UI or the README changes
VALIDATE_MODE=handsfree \
  npm run validate:local            # a real model, driven by a scripted voice
```

In PowerShell, set the environment variable before running the same script:

```powershell
$env:BROWSER_CHECK_REQUIRED = '1'
npm run test:all
```

The same rule applies to other environment assignments: for the hands-free validator,
use `$env:VALIDATE_MODE = 'handsfree'` before `npm run validate:local` in PowerShell.

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

**3 · Module graph — `npm run test:graph`**
```sh
npm run test:graph
```
This runs `tools/check-module-graph.mjs`: `node --check` over every file under `src/`,
plus `sw.js`, then live imports every JavaScript module except `src/ui/app.js`.
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
The rung that covers everything Node cannot see. `tools/browser-check.mjs` first calls
`assembleSite` in `tools/assemble-site.mjs`, which copies the canonical publishable tree
into a scratch directory — packaging only, not a build step — then serves that tree while
loading every `test/browser/*.browser.mjs` probe from the repository in headless Chrome
with a synthesised microphone and reporting back over HTTP.

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
`VALIDATE_TURNS` and `VALIDATE_TURN_MS` do what they look like. `VALIDATE_MIN_GBPS`
(default 100) is the speed floor and `VALIDATE_ALLOW_CPU=1` lets a machine with no GPU run
the rest of the checks anyway. To run the whole thing in containers, including the browser:

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

**It also proves the run was on the GPU, before it spends four turns not being.** The first
thing it does after finding the model is issue its own `/api/generate`, which forces the
lazy load and yields two independent readings: `size_vram` against `size` from `/api/ps`,
which contains no timing at all, and decode speed from `eval_count` / `eval_duration`. A
CPU-only server is then rejected in seconds rather than after twenty-five minutes of an
interview that was never going to mean anything. Residency is sampled again after the
wrap-up, because the synthesis is where the KV cache is largest and where an eviction and
CPU reload would otherwise pass unseen.

The speed floor is expressed as **memory bandwidth, not tokens per second**. Decode reads
essentially the whole weight set once per token, so `tok/s × weight bytes` estimates the
bandwidth the device is achieving — a property of the card rather than of the model, so one
number covers a 7B and a 14B without being retuned every time the default changes. Dual
channel DDR5 realises roughly 20-50 GB/s under llama.cpp. Measured on the machine this was
written on: 49 GB/s for a 9GB model on a CPU-only server, 390 GB/s for a 4.7GB model on an
RTX 5060 Ti. The 100 GB/s default sits in the empty band between the two populations rather
than beside either.

Before trusting a green run, make it fail on purpose. Point `OLLAMA_URL` at a dead port, or
name a model that is not installed. A validator that cannot fail is not a validator.

**6a · The same run, driven by voice** — `VALIDATE_MODE=handsfree npm run validate:local`

The recogniser and the synthesiser are scripted; the model is still the real one. What is
faked is how the answers arrive, never what the model does with them. Five extra claims, and
the one that matters is the hands-free counterpart of the POST count: **the harness never
wrote to `#answer` and never clicked `#b-send`**, asserted from a counter rather than a
comment.

Two things worth knowing before trusting it. Only one claim — that the trigger word ended
the answer rather than joining it — actually fails when the trigger is broken: the deaf
watchdog rescues the capture either way, so every answer still arrives, just slower and with
the word left on the end. And `VALIDATE_MODE=both` does not exist; run the command twice.

The fakes reach the page through `Page.addScriptToEvaluateOnNewDocument`, which runs before
the app's modules and outside the page CSP — the same trick `screenshots.mjs` uses. They are
read off disk rather than fetched, so this still works against a built container that serves
no `/test/`.

**6b · Against a paid provider** — costs money and needs a key, so it is the rung after
that. `npm run serve`, open `http://127.0.0.1:8765`, paste a key. Groq's free tier is the
cheapest way to exercise a hosted model. A full interview on Haiku with Groq dictation
should land around 10–20 cents; wildly more means something is re-sending context it should
not be.

**7 · CI** — six Node legs (Linux/Windows/macOS × Node 22/24), each running `npm test`
and `npm run test:graph`, plus the browser job. Windows is not decoration: two of this
repo's toolchain bugs were Windows-only path handling.

**`BROWSER_CHECK_REQUIRED=1` is not optional for an agent.** Without it a missing Chrome is
a **skip that exits 0** (`browser-check.mjs`), so an unattended run reports green having
checked nothing. It is the easiest false green in this repo, and CI sets it for the same
reason.

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
- **`localhost:11434` can be two different servers at once.** This is the one that will cost
  you the afternoon. A native Ollama binds `127.0.0.1:11434` while a Docker-published one
  binds `[::1]:11434`, both are "up", and which one you get depends on whose resolver is
  asking — Node's and Chrome's need not agree. So the harness can read `/api/ps` off a
  healthy GPU server while the browser interviews a CPU-only container, and every claim
  still passes. Use `127.0.0.1` explicitly, never the name, and identify a server by the
  model digest from `/api/tags` rather than by the fact that something answered.
  `validate:local` now asks every address the name resolves to and fails if they disagree.
- **A CPU-only Ollama answers every probe perfectly.** A container started without `--gpus`
  has `HostConfig.DeviceRequests: null`, logs `inference compute id=cpu library=cpu` and
  `total_vram="0 B"`, and then loads 8 GiB of weights into system RAM. Nothing errors. The
  tell from outside is exactly that: heavy RAM, no VRAM. `docker inspect <name> --format
  '{{json .HostConfig.DeviceRequests}}'` settles it in one line.
- **A GPU that is not being used does not fail, it is just slow.** Ollama falls back to CPU
  and everything still works, ten to fifty times slower, which reads as "the app is slow".
  `GET /api/ps` gives `size` and `size_vram` per loaded model; compare them. But note what
  that endpoint is evidence *about*: a **loaded model**, not a server. It is legitimately
  empty until something has asked for inference, so it cannot be a pre-flight unless you
  issue the load yourself — which is what the harness now does.
- **Do not measure tokens per second on a two-token answer.** Prompting `Say OK.` and timing
  the reply read **3 tok/s on a card that sustains 84**, because over two tokens the average
  is almost entirely first-token latency and CUDA graph capture. That is a false CPU verdict
  on a perfectly healthy GPU, which is worse than no check. Warm the model with one call and
  time a second, longer one — and remember `num_predict` is a ceiling, not a target, so the
  prompt has to be one the model will actually answer at length.
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
- **The wrap-up is the call that degrades first.** It is the only one that sends the whole
  transcript and asks for a title, a prompt, assumptions and open questions in one strict
  shape. On qwen2.5:7b it is reliable over a four-question interview and starts failing
  around eight, as the transcript grows — the same model, the same prompt, just more of it.
  Nothing is lost when it does (`runSynthesis` returns `ok: false` and the export keeps the
  transcript, the open questions and the coverage table), but a validator that judges a run
  by "did an export appear" will not notice. Check for `_Not generated._`.
- **A 7B trips the same tripwire twice running, and one bank question is the result.**
  `questionTripwire` rejects a question that repeats an earlier one by more than 60% of its
  content words, *or* that asks two things at once; `runTurn` regenerates exactly once, and a
  second offence is `unfixable repeat` or `unfixable compound` → bank fallback. Both have
  been seen. That is the app behaving correctly — it will not ask you the same thing twice,
  and it will not ask you two things at once — but it means a four-turn run against
  qwen2.5:7b is green most times and amber occasionally, on the model's luck rather than the
  code's.

  One bank question fails five claims at once, because four of them are downstream of "no
  turn fell back to the checklist", so an amber run looks far worse than it is. Roughly one
  run in six here, across both `VALIDATE_MODE`s. **Re-run before believing you broke
  something**, and read the `unfixable …` warning to tell model luck from a real regression.
- **A small model gets the coverage judgement right and the key wrong.** qwen2.5:7b returns
  `{"1": {...}}` rather than keying by dimension id. `parseTurnResult` now reads a single
  unkeyed claim as the turn's target dimension and says so in a warning; before that it
  dropped them silently, coverage never rose, and the interview ran to the hard ceiling
  before reporting 0%. If you see coverage stuck at zero against a new model, look there
  first.
- **A fake that is too forgiving passes against broken code.** The scripted recogniser in
  `test/browser/fixtures/fake-voice.js` reproduces `resultIndex`, a cumulative `results` list
  and `results[i][0].transcript` exactly, because a flat `{results:[{transcript}]}` would pass
  happily against an accumulator that was counting clauses twice — which is a bug it found.
  It also feeds an utterance only when `continuous` is true, so `probeWebSpeech` cannot eat
  one, and that pins those flag assignments as a side effect.
- **`window.speechSynthesis` cannot be assigned.** It is a readonly WebIDL attribute and
  module code is strict, so `win.speechSynthesis = fake` throws `TypeError`. Use
  `Object.defineProperty(win, 'speechSynthesis', { value, configurable: true })`.
  `SpeechRecognition` and `SpeechSynthesisUtterance` *are* assignable, and that asymmetry is
  easy to lose an hour to.
- **A scripted model that claims no coverage triggers the exhaustion exit.** `zeroGainStreak`
  reaches two after two turns, the engine offers the wrap-up — correctly — and the offer then
  consumes the next scripted utterance as its yes/no. It looks exactly like a broken loop.
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
skipped rather than passed tells you nothing. Stop at the PR unless merging was
explicitly requested; approval and a passing check are not instructions to merge.

In a POSIX shell (use the PowerShell pre-push check above on Windows):

```sh
git checkout -b some-branch
BROWSER_CHECK_REQUIRED=1 npm run test:all # before pushing, not after CI tells you
git push -u origin some-branch
gh pr create --base main --fill
gh pr checks --watch
```

Pages deployment is separate and stricter than "a merge happened". `.github/workflows/pages.yml`
only runs after a successful `CI` workflow run for a push to `main`, then refuses to publish
unless that tested SHA is still the exact tip of `main`.

Releases are tag-driven. A `v*.*.*` tag first runs a read-only `verify` job that checks the
version triplet, proves the tag is contained in `main`, and reruns the full ladder with
`npm run test:all`. Only then does the write-enabled release job build the archives with
`git archive`, plus `SHA256SUMS`.

## What a good change looks like here

- **Tests that would have caught a real bug**, not ones restating the implementation.
  Several existing tests exist because the behaviour was wrong the first time, and their
  comments say which. When a probe or a test fails, work out whether the assertion or the
  code is wrong before changing either — both have happened.
- **No new dependency and no build step.** This is load-bearing rather than minimalism: it
  is what makes the strict CSP affordable in an app that holds the user's API key.
- **Comments that say why.** The codebase is dense with them because most of its non-obvious
  decisions are defences against a specific failure that is invisible from the code alone.
