# Local-model validation troubleshooting

## False greens and endpoint identity

- A degraded interview can look healthy because `runTurn` falls back to the static question
  bank. Check the recorded `questionSource`, the checklist note in the UI/export, and the CDP
  count of requests that actually left the browser.
- `localhost:11434` can resolve to two servers. A native Ollama may own `127.0.0.1` while a
  container owns `[::1]`; Node and Chrome need not choose the same address. Prefer
  `127.0.0.1`; matching version, model-list, or digest metadata is not a unique server
  identity.
- A local endpoint must remain loopback. The containerized harness uses
  `network_mode: "service:ollama"` so `localhost` remains honest inside the validator.

## CPU fallback and GPU evidence

- A CPU-only Ollama answers correctly. In Docker, inspect the exact container's
  `HostConfig.DeviceRequests`; `null` means no GPU was passed through.
- `/api/ps` reports loaded models, not server capability. It is empty until inference forces
  a load.
- Compare `size_vram` with `size` after loading. The validator also measures decode bandwidth,
  because short token-per-second probes mostly measure first-token latency and CUDA setup.
- Do not benchmark a two-token answer. Warm the model, then use a response long enough to
  measure sustained decode.
- Two Ollamas can contend for the same GPU. Unload one before diagnosing the other rather
  than waiting through a slow mutual eviction.

## Docker and Windows

- Pull `ollama/ollama:latest` before blaming the app for behavior fixed in a newer image.
- Do not bind-mount `%USERPROFILE%\.ollama` into the Linux container. The filesystem bridge
  can make loading slower than downloading into the named volume.
- Changing the published port avoids a socket collision but not GPU contention.
- On Windows, use absolute paths. Python does not interpret Git Bash's `/tmp` as the same
  directory.

## Model behavior versus application regressions

- The wrap-up is the largest strict-shape request and usually fails before ordinary turns.
  Check for `_Not generated._`; an export can still exist after synthesis failed.
- A small model can return one coverage claim keyed by position instead of dimension id. The
  parser accepts one unambiguous claim for the requested target and warns; multiple ambiguous
  claims are dropped.
- A repeated or compound question gets one regeneration. A second offense falls back to the
  bank. One fallback cascades into several validator failures, so read the warning before
  deciding the application regressed.
- A scripted model that claims no coverage reaches the zero-gain exit and consumes the next
  scripted utterance as the wrap decision. That is correct exhaustion behavior, not a stalled
  loop.

## Browser and harness traps

- `--dump-dom` snapshots before IndexedDB and fetch settle. Results must be posted back over
  HTTP.
- `--virtual-time-budget` advances timers while storage and media I/O still take real time,
  which can truncate a valid run.
- A CSP violation throws no page exception. Listen for `securitypolicyviolation`.
- Service workers and caches belong to the origin, not the page. Clear them between phases.
- Chrome may retain a profile directory briefly after exit; cleanup is best-effort.
- `AbortSignal.timeout` does not keep Node's event loop alive. Tests waiting only on it need
  another live handle.
- For scripted recogniser and speech-synthesis details, use the
  `change-voice-and-driving` skill.
