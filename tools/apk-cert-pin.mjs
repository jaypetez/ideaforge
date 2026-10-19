#!/usr/bin/env node
// Compares the certificate that signed a release APK with the fingerprint pinned in
// SECURITY.md (#95). The release workflow runs it after `apksigner verify --print-certs`, so a
// release can only ship an APK signed by the key its users were told to expect.
//
// Why a pin at all, when apksigner already verified the signature: a valid signature only
// proves *some* key signed the APK. A swapped ANDROID_KEYSTORE_B64 secret, or a debug key
// sneaking in, produces a perfectly valid APK that no existing install will accept as an
// update, and that nobody can check against anything. The pin turns "signed" into "signed by
// the key we published".
//
//   node tools/apk-cert-pin.mjs <apksigner-output.txt> [SECURITY.md]
//   node tools/apk-cert-pin.mjs --upload-key <certificate-sha256> [SECURITY.md]
//
// Zero dependencies, like everything else here. Exits non-zero, with a GitHub `::error::`
// annotation, on any mismatch or on anything it cannot read unambiguously.

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';

export const SECTION = '## Release signing certificate';
// The one line in that section the workflow reads. Keep the wording in SECURITY.md in step.
const PIN_LINE = /^Signer certificate SHA-256:\s*`([^`]*)`/m;
// Capture unknown labels too, so a new format cannot hide another app certificate.
const SIGNER_LINE = /^([^\r\n]+) certificate SHA-256 digest:[ \t]*([^\r\n]*)$/gm;
const NUMBERED_SIGNER = /^Signer #\d+$/;
const SDK_SIGNER = /^Signer \(minSdkVersion=\d+(?: \(dev release=true\))?, maxSdkVersion=\d+\)$/;

/** Lowercase hex with colons and whitespace removed, or null if it is not a SHA-256. */
export function normalizeFingerprint(text) {
  const hex = String(text ?? '').replace(/[\s:]/g, '').toLowerCase();
  return /^[0-9a-f]{64}$/.test(hex) ? hex : null;
}

/** Every signer's certificate SHA-256 from `apksigner verify --print-certs` output. */
export function parseSigners(output) {
  return signerEntries(output).map((entry) => entry.fingerprint);
}

function signerEntries(output) {
  return [...String(output ?? '').matchAll(SIGNER_LINE)]
    .map((match) => ({ label: match[1], fingerprint: match[2].trim() }))
    .filter((entry) => entry.label !== 'Source Stamp Signer');
}

/**
 * The pinned value from SECURITY.md's release-signing section: `{ pin }` with the normalised
 * fingerprint, or `{ error }`. Only that section is read, so a hash quoted elsewhere in the
 * file (an example, an old key) can never be mistaken for the pin.
 */
export function readPin(securityMd) {
  const text = String(securityMd ?? '').replace(/\r\n/g, '\n');
  const start = text.indexOf(`${SECTION}\n`);
  if (start < 0) return { error: `SECURITY.md has no "${SECTION}" section` };
  const rest = text.slice(start + SECTION.length);
  const next = rest.search(/\n## /);
  const section = next < 0 ? rest : rest.slice(0, next);
  const raw = section.match(PIN_LINE)?.[1];
  if (raw === undefined) return { error: 'SECURITY.md pins no "Signer certificate SHA-256:" line' };
  if (/pending/i.test(raw)) {
    return { error: 'SECURITY.md still says PENDING: pin the release key fingerprint before releasing' };
  }
  const pin = normalizeFingerprint(raw);
  if (!pin) return { error: `SECURITY.md pins "${raw}", which is not a SHA-256 fingerprint` };
  return { pin };
}

/** `{ ok: true, fingerprint }` or `{ ok: false, message }`. */
export function checkPin({ apksignerOutput, securityMd }) {
  const pinned = readPin(securityMd);
  if (pinned.error) return { ok: false, message: pinned.error };
  const entries = signerEntries(apksignerOutput);
  if (entries.length === 0) return { ok: false, message: 'apksigner printed no signer certificate' };
  const numbered = entries.every((entry) => NUMBERED_SIGNER.test(entry.label));
  const v2 = entries.every((entry) => entry.label === 'V2 Signer:');
  if (!numbered && !v2 && !entries.every((entry) => SDK_SIGNER.test(entry.label))) {
    return { ok: false, message: 'apksigner printed unknown or mixed signer labels' };
  }
  // V3.1 prints one certificate per SDK range. All must use the same app key;
  // numbered multi-signers and rotations to a different certificate still fail.
  if ((numbered || v2) && entries.length > 1) {
    return { ok: false, message: `the APK has ${entries.length} signers; a release must have exactly one` };
  }
  const fingerprints = entries.map((entry) => normalizeFingerprint(entry.fingerprint));
  if (fingerprints.some((fingerprint) => fingerprint === null)) {
    return { ok: false, message: 'apksigner printed a malformed signer certificate SHA-256' };
  }
  const unique = [...new Set(fingerprints)];
  if (unique.length !== 1) {
    return { ok: false, message: `the APK uses ${unique.length} certificates across signers or SDK ranges; a release must use one app-signing key` };
  }
  const actual = unique[0];
  if (actual !== pinned.pin) {
    return {
      ok: false,
      message: `the APK is signed by ${actual}, but SECURITY.md pins ${pinned.pin}`,
    };
  }
  return { ok: true, fingerprint: actual };
}

/** The Play upload certificate must not be the app-signing certificate. */
export function checkUploadKey({ fingerprint, securityMd }) {
  const pinned = readPin(securityMd);
  if (pinned.error) return { ok: false, message: pinned.error };
  const actual = normalizeFingerprint(fingerprint);
  if (!actual) return { ok: false, message: 'the upload certificate is not a SHA-256 fingerprint' };
  if (actual === pinned.pin) {
    return { ok: false, message: 'the upload key must be different from the pinned app-signing key' };
  }
  return { ok: true, fingerprint: actual };
}

function main(argv) {
  const upload = argv[0] === '--upload-key';
  const [source, securityFile = 'SECURITY.md'] = upload ? argv.slice(1) : argv;
  if (!source) {
    console.error('usage: node tools/apk-cert-pin.mjs <apksigner-output.txt> [SECURITY.md]\n'
      + '       node tools/apk-cert-pin.mjs --upload-key <certificate-sha256> [SECURITY.md]');
    return 2;
  }
  const securityMd = readFileSync(securityFile, 'utf8');
  const result = upload
    ? checkUploadKey({ fingerprint: source, securityMd })
    : checkPin({ apksignerOutput: readFileSync(source, 'utf8'), securityMd });
  if (!result.ok) {
    console.log(`::error::${result.message}`);
    return 1;
  }
  console.log(upload
    ? `upload certificate SHA-256 ${result.fingerprint} differs from the pinned app-signing key`
    : `signer certificate SHA-256 ${result.fingerprint} matches SECURITY.md`);
  return 0;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  process.exitCode = main(process.argv.slice(2));
}
