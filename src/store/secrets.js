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

/** @param {object} creds e.g. {kind, apiKey, baseUrl, model, sttKind, sttKey} */
export async function saveCredentials(creds) {
  const key = await wrappingKey();
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const plaintext = new TextEncoder().encode(JSON.stringify(creds));
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
    return JSON.parse(new TextDecoder().decode(plaintext));
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

/** Never render a key in full; the user only needs enough to recognise which one it is. */
export function maskKey(key) {
  const k = String(key || '');
  if (k.length <= 12) return '••••';
  return `${k.slice(0, 6)}…${k.slice(-4)}`;
}
