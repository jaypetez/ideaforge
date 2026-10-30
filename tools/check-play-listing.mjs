#!/usr/bin/env node

import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { inflateSync } from 'node:zlib';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const METADATA = join(ROOT, 'android', 'fastlane', 'metadata', 'android', 'en-US');
const SIGNATURE = Buffer.from('89504e470d0a1a0a', 'hex');
const ICON_MAX_BYTES = 1024 * 1024;

export function validateListingText({ title, shortDescription, fullDescription, changelog }) {
  for (const [name, text] of Object.entries({ title, shortDescription, fullDescription, changelog })) {
    assert.ok(typeof text === 'string' && text.length > 0 && text === text.trim(), `${name}: nonempty text without edge whitespace required`);
  }
  assert.ok([...title].length <= 30 && !/[\r\n]/.test(title), 'title: at most 30 characters on one line');
  assert.ok([...shortDescription].length <= 80 && !/[\r\n]/.test(shortDescription), 'short description: at most 80 characters on one line');
  assert.ok(!/\b(?:free|best|new)\b|#1\b/i.test(shortDescription), 'short description: no free/best/#1/new claims');
  assert.ok(!/[\p{Extended_Pictographic}\p{Regional_Indicator}\p{Emoji_Modifier}]|[0-9#*]\uFE0F?\u20E3/u.test(shortDescription),
    'short description: no emoji');
  assert.ok([...fullDescription].length <= 4000, 'full description: at most 4000 characters');
  assert.match(fullDescription, /\bnot affiliated with\b/i, 'full description: retain the non-affiliation notice');
  assert.ok(!/<\/?[a-z][^>]*>|\[[^\]]*]\([^)]*\)/i.test(fullDescription),
    'full description: use plain text, not provider HTML, images or Markdown links');
}

function png(data, label) {
  assert.ok(Buffer.isBuffer(data) && data.length >= 33 && data.subarray(0, 8).equals(SIGNATURE), `${label}: PNG required`);
  let header;
  let transparency = false;
  let ended = false;
  const idat = [];
  for (let offset = 8; offset < data.length;) {
    assert.ok(offset + 12 <= data.length, `${label}: truncated PNG chunk`);
    const length = data.readUInt32BE(offset);
    const type = data.toString('ascii', offset + 4, offset + 8);
    const end = offset + 12 + length;
    assert.ok(end <= data.length, `${label}: truncated ${type} chunk`);
    const content = data.subarray(offset + 8, end - 4);
    if (!header) assert.equal(type, 'IHDR', `${label}: IHDR must be first`);
    if (type === 'IHDR') {
      assert.ok(!header && length === 13, `${label}: exactly one 13-byte IHDR required`);
      header = {
        width: content.readUInt32BE(0), height: content.readUInt32BE(4),
        depth: content[8], colour: content[9],
      };
      assert.ok(content[10] === 0 && content[11] === 0 && content[12] === 0,
        `${label}: standard non-interlaced PNG required`);
    } else if (type === 'tRNS') {
      transparency = true;
    } else if (type === 'IDAT') {
      idat.push(content);
    } else if (type === 'IEND') {
      assert.ok(length === 0 && end === data.length, `${label}: invalid IEND or trailing PNG data`);
      ended = true;
    }
    offset = end;
  }
  assert.ok(header && ended && idat.some((part) => part.length > 0), `${label}: incomplete PNG`);
  return { ...header, transparency, idat };
}

function validateImage(data, label, icon = false, feature = false) {
  if (icon) assert.ok(data.length <= ICON_MAX_BYTES, `${label}: icon exceeds 1 MiB`);
  const info = png(data, label);
  if (icon || feature) {
    assert.ok(info.width === (icon ? 512 : 1024) && info.height === (icon ? 512 : 500),
      `${label}: ${icon ? '512x512' : '1024x500'} required`);
  } else {
    assert.ok(info.width >= 1080 && info.height >= 1920, `${label}: at least 1080x1920 required`);
    assert.ok(info.width <= 3840 && info.height <= 3840, `${label}: maximum dimension is 3840`);
    assert.equal(info.width * 16, info.height * 9, `${label}: portrait 9:16 required, without stretching or cropping`);
  }
  assert.ok(info.depth === 8 && info.colour === (icon ? 6 : 2) && !info.transparency,
    `${label}: ${icon ? '32-bit RGBA' : '24-bit RGB without alpha'} required`);
  // Bound decompression by the already checked dimensions; a header alone is not an image.
  const stride = info.width * (icon ? 4 : 3) + 1;
  const expected = stride * info.height;
  const pixels = inflateSync(Buffer.concat(info.idat), { maxOutputLength: expected });
  assert.equal(pixels.length, expected, `${label}: incomplete PNG pixel data`);
  for (let offset = 0; offset < pixels.length; offset += stride) {
    assert.ok(pixels[offset] <= 4, `${label}: invalid PNG row filter`);
  }
}

export function validateListingImages({ icon, featureGraphic, screenshots }) {
  validateImage(icon, 'icon.png', true);
  validateImage(featureGraphic, 'featureGraphic.png', false, true);
  assert.ok(Array.isArray(screenshots) && screenshots.length >= 4 && screenshots.length <= 8,
    'phone screenshots: between 4 and 8 required');
  assert.equal(new Set(screenshots.map(({ name }) => name)).size, screenshots.length, 'phone screenshots: filenames must be unique');
  for (const theme of ['light', 'dark']) {
    assert.ok(screenshots.some(({ name }) => new RegExp(`(?:^|[.-])${theme}(?:[.-]|$)`).test(name)),
      `phone screenshots: missing ${theme} theme`);
  }
  for (const { name, data } of screenshots) {
    assert.match(name, /\.png$/, 'phone screenshots: PNG files required');
    validateImage(data, name);
  }
}

export function checkPlayListing(metadata = METADATA) {
  const [major, minor, patch] = JSON.parse(readFileSync(join(ROOT, 'package.json'), 'utf8')).version.split('.').map(Number);
  assert.ok(minor < 100 && patch < 100, 'versionCode requires minor and patch below 100');
  const versionCode = major * 10_000 + minor * 100 + patch;
  const text = (file) => readFileSync(join(metadata, file), 'utf8').replace(/\r?\n$/, '');
  validateListingText({
    title: text('title.txt'), shortDescription: text('short_description.txt'),
    fullDescription: text('full_description.txt'), changelog: text(join('changelogs', `${versionCode}.txt`)),
  });
  const images = join(metadata, 'images');
  const screenshots = readdirSync(join(images, 'phoneScreenshots')).sort().map((name) => ({
    name, data: readFileSync(join(images, 'phoneScreenshots', name)),
  }));
  validateListingImages({
    icon: readFileSync(join(images, 'icon.png')), featureGraphic: readFileSync(join(images, 'featureGraphic.png')), screenshots,
  });
  return { versionCode, screenshots: screenshots.length };
}

function main(argv) {
  if (argv.length > 1) {
    console.error('usage: node tools/check-play-listing.mjs [metadata-directory]');
    return 2;
  }
  const result = checkPlayListing(argv[0] && resolve(argv[0]));
  console.log(`Play listing limits passed: versionCode ${result.versionCode}, ${result.screenshots} phone screenshots.`);
  return 0;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  process.exitCode = main(process.argv.slice(2));
}
