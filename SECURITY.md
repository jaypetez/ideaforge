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
your browser. The only requests carrying an API key, transcript or idea content go to the
inference and transcription provider you choose. The app shell also loads the IBM Plex
stylesheet and font files from Google Fonts; those requests carry ordinary browser request
metadata, not IdeaForge content. There is no analytics or telemetry. A prompt or backup only
leaves the app when you explicitly share or export it.

## Your interviews and backups

Sessions are stored in IndexedDB on the device. They are not encrypted: the transcript has
to be available to the app for search, resume and export, and there is no account password
or server-held key in this browser-only design.

**Back up ideas** writes the full active and archived library to an unencrypted JSON file.
It deliberately excludes API keys and device preferences. Anyone who can read the backup
can read the interviews, so store and share it like any other personal document.

**Share .md** hands one finished export to the operating system share sheet after an
explicit button press. The destination the user chooses then owns that copy.

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
  no `unsafe-inline` or `unsafe-eval` in `script-src` and no third-party script origins.
  `style-src` permits inline style and permits only Google Fonts as a remote stylesheet
  origin.
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
