---
name: add-provider
description: Change a provider without breaking browser CORS, CSP, auth, or fallback behavior.
---

# Adding or changing a provider

Use this skill for provider presets, adapters, authentication, model discovery, output
budgets, transcription endpoints, or provider-facing browser errors. Do not use it merely to
change interview prompts.

## 1. Read the owning code first

Read:

- `CONTRIBUTING.md`, "Adding a provider"
- [references/browser-contracts.md](references/browser-contracts.md)
- `src/providers/http.js`, `src/providers/errors.js`, and `src/providers/index.js`
- `src/providers/openaiCompat.js` or the closest dedicated adapter
- `src/voice/transcribe.js` and the dictation options in `index.html` for transcription
- `test/providers.test.mjs`, `test/voice.test.mjs`, `test/wiring.test.mjs`, and the relevant
  browser probes

The browser is the product boundary. A request that works from Node or curl can still fail
because of CORS, Local Network Access, mixed content, or the CSP.

## 2. Choose the smallest extension seam

- An OpenAI-shaped chat endpoint belongs in `OPENAI_COMPAT_PRESETS`.
- Authentication is an `auth` descriptor, not a new conditional branch.
- A non-OpenAI chat shape gets a dedicated adapter implementing `sample`, `sampleJson`,
  `listModels`, and `validateKey`.
- A transcription endpoint belongs in `STT_PRESETS` in `src/voice/transcribe.js`; add its
  explicit `<option>` in `index.html`, because that selector is not data-driven.
- Every surfaced failure must be a `ProviderError` code. Do not return a plain `Error`.
- A local provider must pass the shared parsed-URL loopback check. Never add a second
  definition of "local".

Do not add a dependency, proxy, server component, arbitrary remote base URL, wildcard remote
`connect-src`, or credentials mode.

## 3. Keep the registries aligned

Update the provider preset or adapter, then:

1. Add any new remote origin to the exact `connect-src` allowlist in `index.html`.
2. Add a new source file to `sw.js`'s `SHELL`.
3. Let `test/wiring.test.mjs` prove both registries remain complete.
4. Update chat labels or model tiers from their provider data. Update transcription labels in
   both `STT_PRESETS` and the explicit dictation selector.

Never loosen the CSP to make a test pass. A provider that cannot support a strict browser
client is not compatible.

## 4. Prove the failure modes

Add focused tests for:

- request URL, headers, auth, and vendor-specific output-budget fields;
- multipart transcription fields, codec-derived filenames, size limits, and prompt priming;
- malformed, refused, empty, truncated, and reasoning-prefixed responses;
- status and transport errors mapping to the correct taxonomy;
- invalid-key behavior, including opaque CORS failures;
- deadlines, caller cancellation, retry eligibility, and model-list shapes;
- CSP and service-worker wiring.

HTTP tests use injected `fetch`; they must not reach the network.

## 5. Check a real browser deliberately

Run:

```sh
npm test
npm run test:browser:required
```

For a hosted provider, use an attended browser check only when the user explicitly supplies
their own scoped key. Never read, echo, log, commit, or persist a key outside the app. Verify
both a valid request and the invalid-key message, and name the browser in the PR.

For a local provider, use `127.0.0.1`, verify the browser permission/CORS behavior, and use
the `validate-local-model` skill when the real-model harness is part of the task.

## 6. Finish the integration

If the provider table or README claims changed, invoke `update-readme`. Before pushing, run
`npm run test:all` and describe the tested provider, browser, and failure paths in the PR.
