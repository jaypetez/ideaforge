import test from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { checkPrivacyPolicy } from '../tools/check-privacy-policy.mjs';

const TOOL = fileURLToPath(new URL('../tools/check-privacy-policy.mjs', import.meta.url));
const EMAIL = 'privacy@ideaforge.dev';
const READY = `<address id="privacy-contact" data-status="ready"><a href="mailto:${EMAIL}">${EMAIL}</a></address>`;

test('a confirmed real contact passes privacy release preflight', () => {
  assert.deepEqual(checkPrivacyPolicy(READY), { ok: true });
  assert.deepEqual(checkPrivacyPolicy(READY.replaceAll('"', "'")), { ok: true });
  assert.deepEqual(checkPrivacyPolicy(READY.replace('id="privacy-contact" data-status="ready"',
    'data-status="ready"\r\nid="privacy-contact"')), { ok: true });
});

test('a pending privacy contact refuses release even if an email was filled in', () => {
  const result = checkPrivacyPolicy(READY.replace('ready', 'pending'));
  assert.equal(result.ok, false);
  assert.match(result.message, /PENDING.*maintainer/);
});

test('a missing confirmation marker cannot silently become ready', () => {
  assert.equal(checkPrivacyPolicy(READY.replace(' data-status="ready"', '')).ok, false);
});

test('changing the marker cannot hide the remaining contact placeholder', () => {
  const html = READY.replace('><a', '>PENDING maintainer confirmation.<a');
  assert.equal(checkPrivacyPolicy(html).ok, false);
});

test('a footer address cannot substitute for the policy contact', () => {
  assert.equal(checkPrivacyPolicy(`<footer>${READY.replace('privacy-contact', 'footer-contact')}</footer>`).ok, false);
  assert.equal(checkPrivacyPolicy(READY.replace('id=', 'data-id=')).ok, false);
  assert.equal(checkPrivacyPolicy('').ok, false);
});

test('duplicate privacy contacts refuse release rather than choosing one', () => {
  const result = checkPrivacyPolicy(`${READY}${READY}`);
  assert.equal(result.ok, false);
  assert.match(result.message, /exactly one address/);
});

test('a confirmed marker without a visible email refuses release', () => {
  const contact = '<address id="privacy-contact" data-status="ready">Contact pending.</address>';
  assert.equal(checkPrivacyPolicy(contact).ok, false);
  assert.equal(checkPrivacyPolicy(READY.replace(EMAIL + '</a>', 'Email us</a>')).ok, false);
  assert.equal(checkPrivacyPolicy(READY.replaceAll(EMAIL, 'not-an-address')).ok, false);
  assert.equal(checkPrivacyPolicy(READY.replace('</address>', `<a href="mailto:${EMAIL}">${EMAIL}</a></address>`)).ok, false);
});

test('reserved example domains cannot pass as the public privacy contact', () => {
  for (const domain of ['example.com', 'mail.example.org', 'example.net', 'placeholder.invalid',
    'example.test', 'localhost', 'mail.localhost', 'contact.example']) {
    assert.equal(checkPrivacyPolicy(READY.replaceAll(EMAIL, `privacy@${domain}`)).ok, false, domain);
  }
});

test('the privacy guard CLI refuses pending stdin and accepts confirmed stdin', () => {
  const blocked = spawnSync(process.execPath, [TOOL, '-'], {
    input: READY.replace('ready', 'pending'), encoding: 'utf8',
  });
  assert.equal(blocked.status, 1, blocked.stderr);
  assert.match(blocked.stderr, /::error::.*PENDING/);
  const ready = spawnSync(process.execPath, [TOOL, '-'], { input: READY, encoding: 'utf8' });
  assert.equal(ready.status, 0, ready.stderr);
  assert.match(ready.stdout, /confirmed public contact/);
});

test('a policy the CLI cannot read fails explicitly', () => {
  const result = spawnSync(process.execPath, [TOOL, `${TOOL}.missing`], { encoding: 'utf8' });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /ENOENT/);
});
