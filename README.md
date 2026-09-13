# IdeaForge

An interviewer that forges a half-formed idea into a usable LLM prompt.

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
| **Ollama / LM Studio** | free | Local. Needs `OLLAMA_ORIGINS` set to this app's origin, or LM Studio's CORS toggle. |
| **Claude viewer** | no key at all | Only when IdeaForge is opened inside a Claude artifact viewer. |

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
