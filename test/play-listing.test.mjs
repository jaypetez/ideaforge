import test from 'node:test';
import assert from 'node:assert/strict';
import { deflateSync } from 'node:zlib';
import { validateListingText, validateListingImages } from '../tools/check-play-listing.mjs';

function chunk(type, data) {
  const content = Buffer.concat([Buffer.from(type), data]);
  let crc = 0xffffffff;
  for (const byte of content) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ (crc & 1 ? 0xedb88320 : 0);
  }
  const size = Buffer.alloc(4);
  size.writeUInt32BE(data.length);
  const sum = Buffer.alloc(4);
  sum.writeUInt32BE((crc ^ 0xffffffff) >>> 0);
  return Buffer.concat([size, content, sum]);
}

function image(width, height, { alpha = false, depth = 8, transparency = false, padding, shade = 200, rows = height, filter = 0 } = {}) {
  const header = Buffer.alloc(13);
  header.writeUInt32BE(width, 0);
  header.writeUInt32BE(height, 4);
  header[8] = depth;
  header[9] = alpha ? 6 : 2;
  const stride = width * (alpha ? 4 : 3) * (depth / 8) + 1;
  const pixels = Buffer.alloc(stride * rows, shade);
  for (let row = 0; row < rows; row++) pixels[row * stride] = filter;
  return Buffer.concat([
    Buffer.from('89504e470d0a1a0a', 'hex'),
    chunk('IHDR', header),
    ...(transparency ? [chunk('tRNS', Buffer.alloc(6))] : []),
    chunk('IDAT', deflateSync(pixels)),
    ...(padding === undefined ? [] : [chunk('npAD', Buffer.alloc(padding))]),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

const text = {
  title: 'IdeaForge',
  shortDescription: 'Shape an idea into a clear prompt, one question at a time',
  fullDescription: 'Connect to Groq. IdeaForge is not affiliated with Groq.',
  changelog: 'Improve the Android interview.',
};
const photos = ['01-light-seed.png', '02-light-result.png', '03-dark-seed.png', '04-dark-result.png']
  .map((name) => ({ name, data: image(1080, 1920, { shade: name.includes('dark') ? 20 : 200 }) }));
const images = {
  icon: image(512, 512, { alpha: true }), featureGraphic: image(1024, 500), screenshots: photos,
};

test('listing text accepts exact Unicode character boundaries and ordinary non-promotional words', () => {
  validateListingText({ ...text, title: '語'.repeat(30), shortDescription: 'a'.repeat(80) });
  validateListingText({ ...text, fullDescription: text.fullDescription.padEnd(4000, 'x') });
  validateListingText({ ...text, shortDescription: 'Renew ideas in 1 question with freedom to think' });
});

test('listing text rejects every over-limit field, missing content and multiline short fields', () => {
  for (const [field, value, reason] of [
    ['title', 'a'.repeat(31), /30 characters/],
    ['shortDescription', 'a'.repeat(81), /80 characters/],
    ['fullDescription', text.fullDescription.padEnd(4001, 'x'), /4000 characters/],
    ['changelog', '', /changelog/],
    ['title', 'Two\nlines', /one line/],
    ['shortDescription', 'Two\r\nlines', /one line/],
  ]) {
    assert.throws(() => validateListingText({ ...text, [field]: value }), reason, field);
  }
});

test('short listing text refuses ranking, pricing, novelty, pictographs, flags and keycap emoji', () => {
  for (const value of ['Free prompts', 'The BEST interview', 'Your #1 choice', 'NEW today']) {
    assert.throws(() => validateListingText({ ...text, shortDescription: value }), /no free\/best\/#1\/new/);
  }
  for (const value of ['Think 💡', 'Think 🇺🇸', 'Think 1️⃣', 'Think \u{1F3FB}']) {
    assert.throws(() => validateListingText({ ...text, shortDescription: value }), /no emoji/);
  }
});

test('provider copy retains its disclaimer and refuses HTML or linked provider branding', () => {
  assert.throws(() => validateListingText({ ...text, fullDescription: 'Connect to Groq.' }), /non-affiliation/);
  for (const fullDescription of [
    `${text.fullDescription} <b>Groq</b>`, `${text.fullDescription} [Groq](https://groq.com)`,
    `${text.fullDescription} ![logo](logo.png)`,
  ]) assert.throws(() => validateListingText({ ...text, fullDescription }), /plain text/);
});

test('listing PNGs accept exact icon byte limits and the minimum and maximum 9:16 phone sizes', () => {
  validateListingImages(images);
  const padding = 1024 * 1024 - images.icon.length - 12;
  const icon = image(512, 512, { alpha: true, padding });
  assert.equal(icon.length, 1024 * 1024);
  validateListingImages({ ...images, icon, screenshots: [
    ...photos.slice(0, 3), { name: photos[3].name, data: image(2160, 3840) },
  ] });
});

test('listing icon and feature art reject each wrong dimension, bit depth, alpha and oversize icon', () => {
  for (const [field, data, reason] of [
    ['icon', image(511, 512, { alpha: true }), /512x512/],
    ['icon', image(512, 513, { alpha: true }), /512x512/],
    ['icon', image(512, 512), /32-bit RGBA/],
    ['icon', image(512, 512, { alpha: true, depth: 16 }), /32-bit RGBA/],
    ['icon', image(512, 512, { alpha: true, padding: 1024 * 1024 - images.icon.length - 11 }), /exceeds 1 MiB/],
    ['featureGraphic', image(1023, 500), /1024x500/],
    ['featureGraphic', image(1024, 501), /1024x500/],
    ['featureGraphic', image(1024, 500, { alpha: true }), /without alpha/],
    ['featureGraphic', image(1024, 500, { transparency: true }), /without alpha/],
  ]) assert.throws(() => validateListingImages({ ...images, [field]: data }), reason, field);
});

test('phone assets reject missing themes, insufficient or duplicate frames and more than eight', () => {
  for (const [screenshots, reason] of [
    [photos.slice(0, 3), /between 4 and 8/],
    [[...photos, ...photos, { name: '09-dark.png', data: photos[0].data }], /between 4 and 8/],
    [photos.map((shot) => ({ ...shot, name: shot.name.replace('dark', 'light') })), /missing dark/],
    [[...photos.slice(0, 3), photos[0]], /filenames must be unique/],
  ]) assert.throws(() => validateListingImages({ ...images, screenshots }), reason);
});

test('phone PNGs reject undersize, oversize, wrong aspect, alpha and truncated pixel data', () => {
  for (const [data, reason] of [
    [image(1079, 1920), /at least 1080x1920/],
    [image(1080, 1919), /at least 1080x1920/],
    [image(2169, 3856), /maximum dimension/],
    [image(1080, 2400), /portrait 9:16/],
    [image(1080, 1920, { alpha: true }), /without alpha/],
    [image(1080, 1920, { transparency: true }), /without alpha/],
    [image(1080, 1920, { rows: 1919 }), /incomplete PNG pixel data/],
    [image(1080, 1920, { filter: 5 }), /invalid PNG row filter/],
    [photos[0].data.subarray(0, photos[0].data.length - 4), /truncated/],
  ]) {
    const screenshots = [{ name: photos[0].name, data }, ...photos.slice(1)];
    assert.throws(() => validateListingImages({ ...images, screenshots }), reason);
  }
});
