// tools/apk-cert-pin.mjs is the last gate before a signed APK is attached to a public
// release (#95). Every case here is a way a wrong APK could otherwise ship looking right.

import test from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  checkPin, checkUploadKey, normalizeFingerprint, parseSigners, readPin, SECTION,
} from '../tools/apk-cert-pin.mjs';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const SCRIPT = join(ROOT, 'tools', 'apk-cert-pin.mjs');

const FP = 'e08dab553a514ce7602c66a91ecbbaef9a9e0497a60f5a6bdedfd96b565e6cdc';
const OTHER = 'a'.repeat(64);
const colons = (hex) => hex.toUpperCase().match(/../g).join(':');

// The shape apksigner 36 prints for `verify --print-certs` on a v2/v3-signed APK.
const signerBlock = (n, hex) => [
  `Signer #${n} certificate DN: CN=IdeaForge`,
  `Signer #${n} certificate SHA-256 digest: ${hex}`,
  `Signer #${n} certificate SHA-1 digest: 89ad8d7bc9fbeb8fda3ac38c81c6f7760289fd38`,
  `Signer #${n} certificate MD5 digest: edfdd88dfffea69b5f7e7847038f7896`,
].join('\n');
const apksigner = (...hexes) => `${hexes.map((h, i) => signerBlock(i + 1, h)).join('\n')}\n`;
const sdkSigner = (min, max, hex, dev = false) =>
  `Signer (minSdkVersion=${min}${dev ? ' (dev release=true)' : ''}, maxSdkVersion=${max}) certificate SHA-256 digest: ${hex}\n`;
const v2Signer = (hex) => `V2 Signer: certificate SHA-256 digest: ${hex}\n`;

const security = (pin, { before = '', after = '' } = {}) => [
  '# Security', '', before, '',
  SECTION, '', `Signer certificate SHA-256: \`${pin}\``, '', 'How to verify...', '',
  '## Scope', '', after, '',
].join('\n');

test('a matching signer passes, whatever case or colons either side uses', () => {
  for (const [pinned, printed] of [[FP, FP], [colons(FP), FP], [FP.toUpperCase(), colons(FP)]]) {
    const result = checkPin({ apksignerOutput: apksigner(printed), securityMd: security(pinned) });
    assert.deepEqual(result, { ok: true, fingerprint: FP }, `${pinned} vs ${printed}`);
  }
});

test('a different signer fails and names both fingerprints', () => {
  const result = checkPin({ apksignerOutput: apksigner(OTHER), securityMd: security(FP) });
  assert.equal(result.ok, false);
  assert.match(result.message, new RegExp(OTHER));
  assert.match(result.message, new RegExp(FP));
});

test('SDK-labelled apksigner output accepts one certificate across platform ranges', () => {
  for (const output of [
    sdkSigner(28, 2147483647, FP),
    sdkSigner(33, 2147483647, colons(FP)) + sdkSigner(28, 32, FP),
    sdkSigner(33, 2147483647, colons(FP)).replace(/\n/g, '\r\n'),
  ]) {
    assert.deepEqual(checkPin({ apksignerOutput: output, securityMd: security(FP) }),
      { ok: true, fingerprint: FP });
  }
});

test('V2-labelled apksigner output accepts the pinned certificate', () => {
  for (const output of [
    v2Signer(FP),
    v2Signer(colons(FP)).replace(/\n/g, '\r\n'),
    v2Signer(FP) + `Source Stamp Signer certificate SHA-256 digest: ${OTHER}\n`,
  ]) {
    assert.deepEqual(checkPin({ apksignerOutput: output, securityMd: security(FP) }),
      { ok: true, fingerprint: FP });
  }
  assert.deepEqual(parseSigners(v2Signer(FP)), [FP]);
});

test('V2-labelled reports cannot hide another signer or malformed and mixed labels', () => {
  for (const output of [
    v2Signer(OTHER),
    v2Signer(''),
    v2Signer('malformed'),
    v2Signer(FP) + v2Signer(FP),
    v2Signer(FP) + v2Signer(OTHER),
    v2Signer(OTHER) + apksigner(FP),
    apksigner(FP) + v2Signer(OTHER),
    v2Signer(FP) + sdkSigner(28, 2147483647, FP),
    v2Signer(FP) + `V9 Signer: certificate SHA-256 digest: ${FP}\n`,
    apksigner(FP) + `V9 Signer: certificate SHA-256 digest: ${OTHER}\n`,
    apksigner(FP) + `Future Signer: certificate SHA-256 digest: ${OTHER}\n`,
  ]) {
    assert.equal(checkPin({ apksignerOutput: output, securityMd: security(FP) }).ok, false, output);
  }
});

test('SDK-labelled rotations cannot hide another certificate, including a development range', () => {
  for (const output of [
    sdkSigner(33, 2147483647, OTHER) + sdkSigner(28, 32, FP),
    sdkSigner(33, 2147483647, FP) + sdkSigner(28, 32, OTHER),
    sdkSigner(33, 2147483647, OTHER, true) + sdkSigner(28, 32, FP),
    sdkSigner(33, 2147483647, 'malformed') + sdkSigner(28, 32, FP),
    sdkSigner(33, 2147483647, FP) + sdkSigner(28, 32, 'malformed'),
    sdkSigner(33, 2147483647, '') + sdkSigner(28, 32, FP),
    sdkSigner(33, 2147483647, FP) + sdkSigner(28, 32, ''),
  ]) {
    assert.equal(checkPin({ apksignerOutput: output, securityMd: security(FP) }).ok, false);
  }
});

test('unknown or mixed signer labels fail closed instead of dropping an unrecognized signer', () => {
  for (const output of [
    sdkSigner(28, 2147483647, FP) + `Signer (future-format) certificate SHA-256 digest: ${OTHER}\n`,
    sdkSigner(28, 2147483647, FP) + `Signer (future-format) certificate SHA-256 digest: ${FP}\n`,
    `Signer #1 in lineage certificate SHA-256 digest: ${FP}\n`,
    sdkSigner(28, 2147483647, FP) + apksigner(FP),
    apksigner(FP, FP),
  ]) {
    assert.equal(checkPin({ apksignerOutput: output, securityMd: security(FP) }).ok, false);
  }
});

test('the Play upload key must differ from the normalized app-signing pin', () => {
  assert.deepEqual(checkUploadKey({ fingerprint: colons(OTHER), securityMd: security(FP) }),
    { ok: true, fingerprint: OTHER });
  for (const fingerprint of [FP, FP.toUpperCase(), colons(FP)]) {
    const result = checkUploadKey({ fingerprint, securityMd: security(colons(FP)) });
    assert.equal(result.ok, false, fingerprint);
    assert.match(result.message, /must be different/);
  }
});

test('upload signing rejects a missing policy, a pending pin and a malformed fingerprint', () => {
  for (const securityMd of ['', security('PENDING'), security('abc123')]) {
    assert.equal(checkUploadKey({ fingerprint: OTHER, securityMd }).ok, false);
  }
  for (const fingerprint of [undefined, '', 'abc123', `${OTHER}00`]) {
    assert.equal(checkUploadKey({ fingerprint, securityMd: security(FP) }).ok, false);
  }
});

test('a PENDING pin is refused, so no release ships before the key is published', () => {
  for (const pin of ['PENDING', 'pending', 'PENDING (maintainer to replace)']) {
    const result = checkPin({ apksignerOutput: apksigner(FP), securityMd: security(pin) });
    assert.equal(result.ok, false, pin);
    assert.match(result.message, /PENDING/);
  }
});

test('more than one signer is refused rather than matched against the first', () => {
  const result = checkPin({ apksignerOutput: apksigner(FP, OTHER), securityMd: security(FP) });
  assert.equal(result.ok, false);
  assert.match(result.message, /2 signers/);
  // The pinned key second is no better: which one installs would update is not this tool's guess.
  assert.equal(checkPin({ apksignerOutput: apksigner(OTHER, FP), securityMd: security(FP) }).ok, false);
});

test('no signer, a malformed digest or a malformed pin all fail', () => {
  assert.equal(checkPin({ apksignerOutput: '', securityMd: security(FP) }).ok, false);
  assert.equal(checkPin({ apksignerOutput: apksigner('abc123'), securityMd: security(FP) }).ok, false);
  assert.equal(checkPin({ apksignerOutput: apksigner(FP), securityMd: security(FP.slice(1)) }).ok, false);
  assert.equal(checkPin({ apksignerOutput: apksigner(FP), securityMd: security(`${FP}00`) }).ok, false);
});

test('only the release-signing section is read, never a hash quoted elsewhere', () => {
  // A fingerprint before or after the section (an example, a retired key) is not the pin.
  const md = security('PENDING', {
    before: `Signer certificate SHA-256: \`${FP}\``,
    after: `Signer certificate SHA-256: \`${FP}\``,
  });
  assert.match(readPin(md).error, /PENDING/);
  assert.match(readPin('# Security\n\nnothing here\n').error, /no "## Release signing certificate"/);
  assert.match(readPin(`${SECTION}\n\nno pin line\n`).error, /pins no/);
  // CRLF checkouts read the same.
  assert.deepEqual(readPin(security(FP).replace(/\n/g, '\r\n')), { pin: FP });
});

test('signer lines are matched at line start, so a source-stamp signer is not counted', () => {
  const out = `${apksigner(FP)}Source Stamp Signer certificate SHA-256 digest: ${OTHER}\n`;
  assert.deepEqual(parseSigners(out), [FP]);
  assert.equal(normalizeFingerprint(` ${colons(FP)} `), FP);
  assert.equal(normalizeFingerprint('PENDING'), null);
});

test('the CLI exits 0 on a match and 1 with a GitHub error annotation otherwise', () => {
  const dir = mkdtempSync(join(tmpdir(), 'ideaforge-pin-'));
  try {
    const out = join(dir, 'apksigner.txt');
    const md = join(dir, 'SECURITY.md');
    writeFileSync(out, apksigner(FP));
    const run = () => spawnSync(process.execPath, [SCRIPT, out, md], { encoding: 'utf8' });

    writeFileSync(md, security(colons(FP)));
    let r = run();
    assert.equal(r.status, 0, r.stdout + r.stderr);

    writeFileSync(out, sdkSigner(33, 2147483647, FP) + sdkSigner(28, 32, colons(FP)));
    r = run();
    assert.equal(r.status, 0, r.stdout + r.stderr);

    writeFileSync(out, v2Signer(colons(FP)));
    r = run();
    assert.equal(r.status, 0, r.stdout + r.stderr);

    writeFileSync(md, security(OTHER));
    r = run();
    assert.equal(r.status, 1);
    assert.match(r.stdout, /^::error::/m);

    writeFileSync(md, security('PENDING'));
    r = run();
    assert.equal(r.status, 1);
    assert.match(r.stdout, /::error::.*PENDING/);

    r = spawnSync(process.execPath, [SCRIPT], { encoding: 'utf8' });
    assert.equal(r.status, 2, 'no arguments is a usage error, not a pass');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('SECURITY.md carries a pin line the release workflow can read', () => {
  const md = readFileSync(join(ROOT, 'SECURITY.md'), 'utf8');
  const pinned = readPin(md);
  // Either a real fingerprint, or PENDING until the maintainer creates the key. Anything
  // else (a typo, a deleted line, a renamed heading) would fail the next release instead.
  assert.ok(pinned.pin || /PENDING/.test(pinned.error ?? ''), JSON.stringify(pinned));
});

test('the upload-key CLI rejects key reuse rather than blessing an otherwise valid certificate', () => {
  const dir = mkdtempSync(join(tmpdir(), 'ideaforge-upload-pin-'));
  try {
    const md = join(dir, 'SECURITY.md');
    writeFileSync(md, security(FP));
    const run = (fingerprint) => spawnSync(process.execPath,
      [SCRIPT, '--upload-key', fingerprint, md], { encoding: 'utf8' });
    const distinct = run(colons(OTHER));
    assert.equal(distinct.status, 0, distinct.stdout + distinct.stderr);
    const reused = run(colons(FP));
    assert.equal(reused.status, 1, reused.stdout + reused.stderr);
    assert.match(reused.stdout, /::error::.*must be different/);
    writeFileSync(md, security('PENDING'));
    const pending = run(OTHER);
    assert.equal(pending.status, 1);
    assert.match(pending.stdout, /::error::.*PENDING/);
    const missing = spawnSync(process.execPath, [SCRIPT, '--upload-key'], { encoding: 'utf8' });
    assert.equal(missing.status, 2);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
