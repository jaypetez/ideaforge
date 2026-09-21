---
name: validate-local-model
description: Run or diagnose the Ollama validator across GPU, browser, fallback, and voice checks.
---

# Validating against a local model

Use this skill when running `npm run validate:local`, diagnosing an Ollama validation failure,
checking the containerized validator, or proving the typed or hands-free interview reached a
real local model.

This workflow can start containers, pull a model, use substantial GPU time, and exercise a
real browser. Inspect what is already running first. Do not stop unrelated processes, replace
an existing model store, expose Ollama beyond loopback, or use a paid provider.

## 1. Identify the exact server

Use `127.0.0.1`, not `localhost`, unless the task is specifically testing resolver behavior.
Set `OLLAMA_URL` to an explicit numeric loopback address when more than one server is present.
Version, model names, and model digests are useful consistency checks, but two independent
servers can expose identical metadata.

If the repository's container is intended:

```sh
docker compose -f docker/compose.yml up -d ollama
docker compose -f docker/compose.yml exec ollama ollama list
```

Pull the configured model only when it is missing and the user has authorized the download:

```sh
docker compose -f docker/compose.yml exec ollama ollama pull qwen2.5:7b-instruct
```

Do not bind-mount the Windows host model store into the container.

## 2. Run the typed validator

```sh
npm run validate:local
```

Useful inputs are `OLLAMA_URL`, `IDEAFORGE_MODEL`, `IDEAFORGE_URL`, `VALIDATE_TURNS`,
`VALIDATE_TURN_MS`, `VALIDATE_MIN_GBPS`, and `VALIDATE_ALLOW_CPU=1`.

In PowerShell, set each value before the command:

```powershell
$env:OLLAMA_URL = 'http://127.0.0.1:11434'
$env:IDEAFORGE_MODEL = 'qwen2.5:7b-instruct'
npm run validate:local
```

A valid run proves more than an export appeared: every post-seed question came from the
model, requests left the browser, coverage changed, synthesis succeeded, and the CSP stayed
quiet. Unless `VALIDATE_ALLOW_CPU=1` is set, the preflight also proves the model loaded mostly
into VRAM. Decode speed is skipped when the sample is too short to measure, and a missing
post-wrap `/api/ps` row is reported rather than treated as proof of residency.

## 3. Run hands-free separately

There is no `both` mode:

```sh
VALIDATE_MODE=handsfree npm run validate:local
```

PowerShell:

```powershell
$env:VALIDATE_MODE = 'handsfree'
npm run validate:local
```

The recogniser and synthesiser are scripted; model inference, HTTP, CSP, storage, and the UI
remain real. The run must prove the harness never wrote to the answer field or clicked Send.

## 4. Interpret failures before changing code

Read [references/troubleshooting.md](references/troubleshooting.md). In particular:

- a bank fallback can make several downstream claims fail at once;
- one amber run from an unfixable compound or repeated question can be model variability;
- CPU fallback answers correctly but fails residency or bandwidth checks;
- `/api/ps` says nothing until a model has been loaded;
- the wrap-up call is the first one small models tend to lose.

Re-run a model-variability failure once before editing. Do not dismiss deterministic address,
GPU, network-count, CSP, storage, or keyboard-interaction failures as flakiness.

## 5. Prove the validator can fail

Before relying on a new or materially changed validator, perform one negative control with a
dead `OLLAMA_URL` or a model name that is not installed. Restore the environment afterwards.
A validator that cannot fail is not evidence.

## 6. Containerized validation

To validate the complete container path:

```sh
docker compose -f docker/compose.yml -f docker/compose.validate.yml run --rm validate
```

The harness shares the Ollama network namespace because the application correctly refuses
non-loopback local-model endpoints.
