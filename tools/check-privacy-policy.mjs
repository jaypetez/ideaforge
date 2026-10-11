#!/usr/bin/env node
// A guide footer or example address must not turn a draft privacy policy into a releasable one.

import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

/** The policy's explicitly confirmed contact, or an actionable release refusal. */
export function checkPrivacyPolicy(html) {
  const contacts = [...String(html ?? '').matchAll(/<address\b([^>]*)>([\s\S]*?)<\/address\s*>/gi)]
    .filter((match) => /(?:^|\s)id\s*=\s*(['"])privacy-contact\1(?=\s|$)/.test(match[1]));
  if (contacts.length !== 1) {
    return { ok: false, message: 'Privacy policy needs exactly one address with id="privacy-contact".' };
  }
  const [, attributes, content] = contacts[0];
  if (!/(?:^|\s)data-status\s*=\s*(['"])ready\1(?=\s|$)/.test(attributes)
    || /PENDING maintainer confirmation/i.test(content)) {
    return { ok: false, message: 'Privacy contact is PENDING: the maintainer must confirm it before releasing.' };
  }
  const links = [...content.matchAll(/<a\b[^>]*\shref\s*=\s*(['"])mailto:([^"'\s?<>]+)\1[^>]*>([^<]+)<\/a\s*>/gi)];
  if (links.length !== 1) {
    return { ok: false, message: 'Confirmed privacy contact needs exactly one visible mailto address.' };
  }
  const [, , email, label] = links[0];
  if (!/^[^@\s<>]+@[^@\s<>]+\.[^@\s<>]+$/.test(email) || label.trim() !== email) {
    return { ok: false, message: 'Privacy contact must display the same valid address as its mailto link.' };
  }
  const domain = email.slice(email.lastIndexOf('@') + 1);
  if (/(?:^|\.)example\.(?:com|net|org)$|(?:^|\.)(?:example|invalid|test|localhost)$/i.test(domain)) {
    return { ok: false, message: 'Privacy contact is an example address: supply the real public contact.' };
  }
  return { ok: true };
}

function main(argv) {
  if (argv.length > 1) {
    console.error('usage: node tools/check-privacy-policy.mjs [policy.html|-]');
    return 2;
  }
  const [file = 'guide/privacy-policy.html'] = argv;
  const result = checkPrivacyPolicy(readFileSync(file === '-' ? 0 : file, 'utf8'));
  if (!result.ok) {
    console.error(`::error::${result.message}`);
    return 1;
  }
  console.log('Privacy policy has a confirmed public contact.');
  return 0;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  process.exitCode = main(process.argv.slice(2));
}
