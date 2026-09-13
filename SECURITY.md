# Security

## Reporting a vulnerability

Please report privately using
[GitHub's private vulnerability reporting](https://github.com/jaypetez/ideaforge/security/advisories/new)
rather than opening a public issue.

Include what an attacker gains and how to reproduce it. I'll acknowledge within a week.
This is a personal project with no security team and no bounty, but I would much rather
know.

## What this app is, security-wise

IdeaForge is a static page with **no server and no dependencies**. It runs entirely in
your browser, and the only network requests it makes are to the inference and
transcription provider you choose. There is no analytics, no telemetry, and nothing is
sent anywhere else.

## Your API key: what is and is not protected

The key you paste is encrypted with AES-GCM and stored in IndexedDB. The key that
decrypts it is generated **non-extractable**, so it can be used from this origin but its
raw bytes cannot be read out — not from the console, not by an extension reading the
profile directory, not from a copied disk.

**What that defends against:** someone with your device or a copy of its disk. The API key
is never written in plaintext.

**What it does not defend against:** a malicious script running on this origin. Such a
script could call `decrypt()` exactly as the app does. There is no browser mechanism that
prevents this, in any application, and any product that claims otherwise is wrong.

The mitigations that actually matter here are structural:

- A strict Content-Security-Policy whose `connect-src` lists only the provider hosts, with
  no `unsafe-inline`, no `unsafe-eval` and no third-party script origins.
- **Zero dependencies.** No npm packages, no CDN scripts, no analytics. A supply-chain
  compromise is the realistic way a script ends up on a page like this one, and this
  project has no supply chain to compromise.

## What you should do

- Use a **scoped, expiring** key rather than your main one. Anthropic, OpenAI and Groq all
  support this. If it leaks, the blast radius is bounded and it expires anyway.
- **Do not paste an API key into a shared Claude artifact.** Anyone the artifact is shared
  with can read the page source. Inside a Claude viewer, use the built-in provider, which
  needs no key at all.
- Use **Forget my key** in Settings when you are done on a shared or borrowed device.
- Never paste a key into a GitHub issue. If you do, revoke it immediately.

## Scope

In scope: anything that exfiltrates a stored key, executes script in the page, tampers
with what is sent to a provider, or lets one origin read another's stored sessions.

Out of scope: the fact that a BYO-key browser app necessarily has the key in browser
memory; vulnerabilities in the providers themselves; and social engineering of the user
into pasting a key somewhere they shouldn't.
