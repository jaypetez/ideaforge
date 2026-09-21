// Where the user's API key lives.
//
// Be honest about what this does and does not buy. There is no secure secret storage in a
// browser. What is achievable is this: the key is never written to disk in plaintext, and
// the AES-GCM key that decrypts it is generated non-extractable, so it can be *used* from
// this origin but its raw bytes cannot be read out — not by console, not by an injected
// script, not by anything that copies IndexedDB off the disk.
//
// So the realistic threat model:
//   - device theft / disk copy / a curious person at the keyboard  -> defended
//   - a malicious script running on this origin                    -> NOT defended; it can
//     call decrypt() exactly as we do. The defence there is the strict CSP in index.html
//     and having no third-party scripts and no dependencies at all.
//
// Which is why the settings screen tells the user to mint a scoped, expiring key.

import { SECRETS, get, put, del } from './db.js';

const CRYPTO_KEY = 'wrapping-key';
const PAYLOAD = 'credentials';

async function wrappingKey() {
  const existing = await get(SECRETS, CRYPTO_KEY);
  if (existing) return existing;
  const key = await crypto.subtle.generateKey(
    { name: 'AES-GCM', length: 256 },
    false,                      // non-extractable: the whole point
    ['encrypt', 'decrypt']
  );
  await put(SECRETS, key, CRYPTO_KEY);
  return key;
}

/**
 * @param {object} ring a keyring, or a v1 blob, which is migrated on the way in so an old
 *                      call site cannot write a shape the loader would have to guess at.
 */
export async function saveCredentials(ring) {
  const key = await wrappingKey();
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const payload = ring && ring.version === CREDENTIALS_VERSION ? ring : migrateCredentials(ring);
  const plaintext = new TextEncoder().encode(JSON.stringify(payload));
  const ciphertext = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, plaintext);
  await put(SECRETS, { iv, ciphertext }, PAYLOAD);
}

/** @returns {Promise<object|null>} null when nothing is stored or it cannot be read. */
export async function loadCredentials() {
  const blob = await get(SECRETS, PAYLOAD);
  if (!blob) return null;
  try {
    const key = await wrappingKey();
    const plaintext = await crypto.subtle.decrypt(
      { name: 'AES-GCM', iv: blob.iv }, key, blob.ciphertext
    );
    return migrateCredentials(JSON.parse(new TextDecoder().decode(plaintext)));
  } catch {
    // The wrapping key was evicted independently of the payload, so the ciphertext is
    // now undecryptable rubbish. Clear it and make the user paste the key again — that
    // is a far better outcome than an infinite loop of failed decryptions.
    await del(SECRETS, PAYLOAD);
    return null;
  }
}

export async function clearCredentials() {
  await del(SECRETS, PAYLOAD);
  await del(SECRETS, CRYPTO_KEY);
}

// ── the keyring ────────────────────────────────────────────────────────────────────────
//
// v1 stored ONE record — {kind, apiKey, baseUrl, model, sttKind, sttKey} — so choosing a
// different provider in Settings overwrote the previous provider's key and it was simply
// gone. Switch Groq -> OpenAI -> Groq and you were pasting the Groq key again.
//
// v2 keeps a record per provider id inside the same encrypted blob. The encryption is
// untouched: same AES-GCM, same non-extractable wrapping key, same single record.

export const CREDENTIALS_VERSION = 2;

export function emptyKeyring() {
  return {
    version: CREDENTIALS_VERSION,
    active: null,
    byKind: {},
    stt: { kind: null, apiKey: '' },
  };
}

/**
 * Applied on read rather than at upgrade time, the way src/store/sessions.js applies
 * migrate(). Turning the boot-time read into a write would add a failure mode — a private
 * window with storage blocked — to the one path that currently cannot fail for the user.
 * The next saveCredentials upgrades the blob at rest anyway.
 *
 * Anything unrecognisable becomes an empty keyring instead of throwing, because the
 * alternative is an app that will not boot over a credential it could simply re-ask for.
 */
export function migrateCredentials(raw) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return emptyKeyring();
  if (raw.version === CREDENTIALS_VERSION) return normalise(raw);

  const ring = emptyKeyring();
  const kind = str(raw.kind);
  if (kind) {
    ring.active = kind;
    // No baseUrl is carried across: in v1 the Base URL field was unreachable dead UI, so
    // no v1 blob can hold one. Dropping it is also what keeps this file from needing to
    // know what loopback means.
    ring.byKind[kind] = record({ apiKey: raw.apiKey, model: raw.model });
  }
  if (str(raw.sttKind)) ring.stt = { kind: str(raw.sttKind), apiKey: str(raw.sttKey) };
  return ring;
}

function normalise(raw) {
  const ring = emptyKeyring();
  ring.active = str(raw.active) || null;
  const by = raw.byKind && typeof raw.byKind === 'object' ? raw.byKind : {};
  for (const [kind, rec] of Object.entries(by)) {
    if (rec && typeof rec === 'object') ring.byKind[kind] = record(rec);
  }
  if (raw.stt && typeof raw.stt === 'object') {
    ring.stt = { kind: str(raw.stt.kind) || null, apiKey: str(raw.stt.apiKey) };
  }
  return ring;
}

// `model` is the QUESTIONS model and keeps its old name on purpose: it predates the
// split, and renaming it would silently drop the model every existing local-server user
// has already chosen. `wrapModel` is additive, so CREDENTIALS_VERSION does not move — a
// blob written before this field existed reads back '', which means "use the preset's
// strong tier", which is exactly right for everyone who has never seen the second box.
const record = (r) => ({
  apiKey: str(r.apiKey), baseUrl: str(r.baseUrl),
  model: str(r.model), wrapModel: str(r.wrapModel),
});
const str = (v) => (typeof v === 'string' ? v.trim() : '');

/** One provider's record, always an object, so no call site needs a guard. */
export function credsFor(ring, kind) {
  const r = (ring && ring.byKind && ring.byKind[kind]) || {};
  return {
    kind, apiKey: r.apiKey || '', baseUrl: r.baseUrl || '',
    model: r.model || '', wrapModel: r.wrapModel || '',
  };
}

/**
 * A new ring with one provider replaced and made active. Every other provider's record
 * survives, which is the entire point and the bug this replaces.
 */
export function withCreds(ring, kind, rec) {
  const base = ring && ring.version === CREDENTIALS_VERSION ? ring : emptyKeyring();
  return { ...base, active: kind, byKind: { ...base.byKind, [kind]: record(rec || {}) } };
}

export function withStt(ring, stt) {
  const base = ring && ring.version === CREDENTIALS_VERSION ? ring : emptyKeyring();
  return { ...base, stt: { kind: str(stt && stt.kind) || null, apiKey: str(stt && stt.apiKey) } };
}

/**
 * True when the ring holds a secret at all — what the Forget button keys off. A ring
 * holding only {ollama: {baseUrl, model}} has nothing to forget, and offering to forget
 * nothing is worse than not offering.
 */
export function hasAnyKey(ring) {
  if (!ring) return false;
  if (ring.stt && ring.stt.apiKey) return true;
  return Object.values(ring.byKind || {}).some((r) => r && r.apiKey);
}

/** Never render a key in full; the user only needs enough to recognise which one it is. */
export function maskKey(key) {
  const k = String(key || '');
  if (k.length <= 12) return '••••';
  return `${k.slice(0, 6)}…${k.slice(-4)}`;
}
