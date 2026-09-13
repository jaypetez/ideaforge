# IdeaForge

[![CI](https://github.com/jaypetez/ideaforge/actions/workflows/ci.yml/badge.svg)](https://github.com/jaypetez/ideaforge/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

An interviewer that forges a half-formed idea into a usable LLM prompt.

**→ [Try it](https://jaypetez.github.io/ideaforge/)** — it runs entirely in your browser
with your own API key. Nothing is sent anywhere but the provider you pick.

You have a vague idea. You know things about it you would never think to type — the real
numbers, the case you have in mind, the thing that would make you throw the output away.
IdeaForge asks you one question at a time until it has those, then writes the prompt you
should have given an LLM in the first place.

It stops on its own. Seven dimensions are tracked, and a dimension only counts as covered
when the model can quote you verbatim on it — so the interview ends when there is genuinely
enough to write from, not when the model feels finished.

## Running it

No build step, no dependencies. Serve the directory over HTTP and open it:

```sh
npm run serve      # then open http://127.0.0.1:8765
```

`file://` will not work — ES modules, IndexedDB and the service worker all need an origin.

Install it to your home screen or desktop from the browser menu and it runs offline, from
the built-in question bank, until a model is reachable again.

## Bringing your own key

Pick a provider and paste a key. The options:

| Provider | Cost | Notes |
|---|---|---|
| **Groq** | free tier, no card | The easiest start. |
| **Anthropic** | ~10–20¢ per interview | Haiku asks the questions, Sonnet writes the wrap-up. |
| **OpenAI** | a few cents | `gpt-5-nano` for turns. |
| **OpenRouter** | varies | Anything it fronts. |
| **Ollama / LM Studio** | free | Local, and awkward from a hosted page — see below. |
| **Claude viewer** | no key at all | Only when IdeaForge is opened inside a Claude artifact viewer. |

## Talking instead of typing

Tap **Answer out loud** to dictate one answer, or turn on **hands-free** and the app reads
each question aloud, listens, and moves on when you stop talking. Dictated answers are
marked as such: the interviewer is told to read them for intent and never to ask you to
clarify a mis-transcription, and the export records which answers you spoke.

There are two dictation backends and the app picks by *testing*, not by asking the browser
what it supports:

- **The browser's own recogniser** — free and shows words as you speak. It works in Chrome
  and in Safari proper.
- **Whisper** (Groq or OpenAI) — records and transcribes. About a penny for a whole
  interview on Groq, and noticeably better on rambling or jargon-heavy speech.

The reason for the testing is that `webkitSpeechRecognition` **exists and does nothing**
inside an installed iOS home-screen app: it constructs, `start()` returns cleanly, and no
event ever fires. Edge throws a `network` error instead, and Firefox has it off by default.
So the app starts the recogniser and requires it to prove it is alive within a second and a
half; if it does not, dictation switches to Whisper for good. **If you want to dictate on
an installed iPhone app, set a transcription key** — the browser path cannot work there.
Without one, the app says so rather than showing a mic button that hangs.

**A local model is easiest from a local page.** Ollama and LM Studio both refuse
cross-origin requests by default, so from the hosted site you would need
`OLLAMA_ORIGINS=https://jaypetez.github.io` (or LM Studio's CORS toggle) — and even then
Safari blocks an `http://localhost` request from an `https://` page outright, and Chrome
adds a Private Network Access preflight neither server answers. If you want to run against
a local model, run the app locally too: `npm run serve`.

**About the key.** It is encrypted with a non-extractable `CryptoKey` and stored in
IndexedDB on your device, and it is sent to exactly one host — the provider you picked,
which is also the only host the page's CSP permits it to talk to. What that does *not*
defend against is a malicious script running on this origin, which could use the key
exactly as the app does. The mitigations are that this app has zero dependencies and no
third-party scripts, and that you should mint a **scoped, expiring** key rather than
handing it your main one.

Do not paste an API key into a **shared** Claude artifact — anyone the artifact is shared
with can read the page. Inside a Claude viewer, use the built-in `sample` provider.

## Layout

```
src/core/       pure interview logic — no DOM, no network, no clock
src/runtime/    the turn loop; provider and clock injected, so it is testable offline
src/providers/  the only directory allowed to touch the network
src/store/      IndexedDB sessions and the encrypted key
src/voice/      dictation, transcription and reading questions aloud
src/ui/         the app shell
```

`npm test` runs the suite and `tools/lint-purity.mjs`, which fails the build if anything
under `src/core/` or `src/runtime/` so much as mentions `window`, `fetch` or `document`.
That boundary is what keeps the interview engine portable and the turn loop testable
without a network.

## What it does when things break

An interview never dead-ends. If the model is unreachable, returns nonsense twice, or you
have no key at all, the next question comes from a built-in bank of 21 and the export says
so. The transcript and your open questions survive a failed wrap-up — only the refined
prompt is lost, and you can retry it.

## Licence

MIT.
