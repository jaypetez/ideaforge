# Provider browser contracts

These are verified browser/API facts, not general provider folklore.

- **Anthropic:** the preflight requires `anthropic-dangerous-direct-browser-access` among the
  requested header names. Omitting it produces a 400 without useful CORS headers. Never set
  `credentials: 'include'`.
- **OpenAI:** an invalid-key 401 can omit CORS headers, so the page sees an opaque
  `TypeError`. Report that as a probable key problem and validate keys through the CORS-safe
  models endpoint.
- **Local models:** CORS and Local Network Access are independent gates with similar
  symptoms. Ollama reads `OLLAMA_ORIGINS` at startup. Chrome requires local-network
  permission, and `targetAddressSpace: 'local'` also makes an HTTPS page's loopback HTTP
  request eligible for the browser exemption. Safari provides no equivalent.
- **IPv6 literals:** CSP has no usable `http://[::1]:*` host-source form; browsers drop it
  silently. Support `localhost` and `127.0.0.1`, and steer users away from the bracket form.
- **Authentication:** `src/providers/http.js` owns `applyAuth`, `isLoopback`, and the shared
  HTTP error path. Add data to the auth descriptor rather than branching in adapters.
- **Output budgets:** GPT-5-era OpenAI models require `max_completion_tokens`; Groq, Ollama,
  and LM Studio use `max_tokens`. Reasoning models can consume the budget before emitting
  visible text, so an empty length-limited reply is an error.
- **Retired endpoints:** GitHub Models and the former Copilot chat route are not supported
  general-purpose inference providers. Do not re-add them.
