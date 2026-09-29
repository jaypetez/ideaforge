# Security

## Reporting a vulnerability

Please report privately using
[GitHub's private vulnerability reporting](https://github.com/jaypetez/ideaforge/security/advisories/new)
rather than opening a public issue.

Include what an attacker gains and how to reproduce it. I'll acknowledge within a week.
This is a personal project with no security team and no bounty, but I would much rather
know.

For user-facing storage, backup, key and cleanup guidance, see the
[privacy and security guide](https://jaypetez.github.io/ideaforge/guide/privacy-and-security.html).

## What this app is, security-wise

IdeaForge is a static page with **no server and no dependencies**. It runs entirely in
your browser. Inference, transcription and optional hosted speech requests go directly to
their selected providers, without an IdeaForge proxy. Browser recognition and network
speech voices may also send audio or spoken text to services controlled by the browser.
Neither native speech nor recognition is promised to work offline.

The app shell also loads the IBM Plex stylesheet and font files from Google Fonts; those
requests carry ordinary browser request metadata, not IdeaForge content. The static guide
uses only local assets and system fonts. There is no analytics or telemetry. Exports and
backups are created only by explicit actions; their underlying idea content can already have
been sent to a selected model, transcriber or speech service.

## Optional hosted speech

Browser speech is the default. OpenAI speech requires a **separate speech key**, consent to
metered text transfer and an AI-generated voice, and a successful in-app check and preview.
It never reuses an inference or transcription key. The preview makes a real provider
request; a failed preview cannot pass by playing browser speech instead.

`src/providers/tts.js` fixes the endpoint and permitted voices, bounds the request and audio
response, omits cookies and rejects redirects. `src/voice/output.js` handles visible browser
fallback after a hosted failure, but cancellation never starts fallback playback.
`src/voice/playback.js` holds generated audio in memory, not in sessions or a persistent
audio cache. Spoken text can include personal details from questions and the final prompt;
provider-side retention remains outside IdeaForge's control.

No authorised live speech key or spending cap was supplied for this redesign. Live hosted
CORS and voice quality remain unverified by the developer. A successful user preview is
evidence for that browser and attempt, not a general compatibility or security guarantee.

Pause releases owned microphone tracks and stops playback, but captured audio may still
finish transcription and a model request already sent may settle for its original idea.
Pausing does not recall data already transferred. Exit keeps transcribed words and reports
when an unfinished audio segment was cancelled.

## Your interviews and backups

Sessions are stored in IndexedDB on the device. They are not encrypted: the transcript has
to be available to the app for search, resume and export, and there is no account password
or server-held key in this browser-only design.

**Back up ideas** writes the full active and archived library to an unencrypted JSON file.
It deliberately excludes inference, transcription and speech keys, device preferences and
audio. Anyone who can read the backup can read the interviews, so store and share it like
any other personal document.

**Share .md** hands one finished export to the operating system share sheet after an
explicit button press. The destination the user chooses then owns that copy.

## Your API keys: what is and is not protected

The credential keyring is encrypted with AES-GCM and stored in IndexedDB. Its wrapping
`CryptoKey` is generated **non-extractable**: Web Crypto can use it for this origin but
will not export its raw bytes. The independent speech record uses this same storage boundary.

**What that provides:** the app stores ciphertext rather than a plaintext API-key file.
Non-extractable is an API restriction, not a guarantee against control of the browser,
operating system, extensions or an unlocked profile.

**What it does not defend against:** a malicious script running on this origin. Such a
script could call `decrypt()` exactly as the app does. There is no browser mechanism that
prevents this, in any application, and any product that claims otherwise is wrong.

The mitigations that actually matter here are structural:

- A strict Content-Security-Policy whose `connect-src` lists only the provider hosts, with
  no `unsafe-inline` or `unsafe-eval` in `script-src` and no third-party script origins.
  `style-src` permits inline style and permits only Google Fonts as a remote stylesheet
  origin.
- **Zero dependencies.** No npm packages, no CDN scripts, no analytics. A supply-chain
  compromise in third-party JavaScript is one route this removes; it does not make the
  browser or the system serving the page immune to compromise.

## What you should do

- Use a **scoped, expiring** key rather than your main one. Anthropic, OpenAI and Groq all
  support this. If it leaks, the blast radius is bounded and it expires anyway.
- **Do not paste an API key into a shared Claude artifact.** Anyone the artifact is shared
  with can read the page source. Inside a Claude viewer, use the built-in provider, which
  needs no key at all.
- Use **Forget my key** in Settings when you are done on a shared or borrowed device.
  It removes all saved inference, transcription and speech credentials, including the
  wrapping key. It does not revoke them at their providers or delete your interviews.
- Never paste a key into a GitHub issue. If you do, revoke it immediately.

## Scope

In scope: anything that exfiltrates a stored key, executes script in the page, tampers
with what is sent to a provider, or lets one origin read another's stored sessions.

Out of scope: the fact that a BYO-key browser app necessarily has the key in browser
memory; vulnerabilities in the providers themselves; and social engineering of the user
into pasting a key somewhere they shouldn't.
