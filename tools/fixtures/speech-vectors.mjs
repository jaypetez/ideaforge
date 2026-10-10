// Hosted speech vector families: `transcribe` (src/voice/transcribe.js) and `tts`
// (src/providers/tts.js), replayed by android/providers (SpeechVectorTest) for #80.
//
// Both record whole HTTP exchanges from a stubbed fetch, as the `providers` family does, so
// the Kotlin clients are held to the exact request the web sends and the exact outcome it
// reaches for a scripted reply. Keys are fixed fake strings, never real ones.
//
// Multipart boundaries are random, so a transcription request is recorded as its parsed
// parts: name, filename, content type and either the text value or the file's size and
// SHA-256 (a 25 MB recording does not belong in a JSON file). Large bodies are described,
// not inlined: `{prefix, size}` is the prefix bytes (base64) followed by zeros up to `size`.

import { createHash } from 'node:crypto';

import { createTranscriber, MAX_AUDIO_BYTES, STT_PRESETS } from '../../src/voice/transcribe.js';
import {
  createTTSProvider, validateSpeechAudio, MAX_TTS_INPUT_CHARS, MAX_TTS_AUDIO_BYTES,
  MAX_TTS_AUDIO_SECONDS, TTS_DEADLINE_MS, TTS_PRESETS,
} from '../../src/providers/tts.js';

const plain = (v) => (v === undefined ? null : JSON.parse(JSON.stringify(v)));
const sha256 = (bytes) => createHash('sha256').update(bytes).digest('hex');
const b64 = (bytes) => Buffer.from(bytes).toString('base64');

/** Rebuild a described body: `{base64}` or `{prefix, size}` (prefix then zeros). */
export function bytesOf(desc) {
  if ('base64' in desc) return new Uint8Array(Buffer.from(desc.base64, 'base64'));
  const out = new Uint8Array(desc.size);
  out.set(Buffer.from(desc.prefix || '', 'base64'));
  return out;
}

/** A recorded failure: code and message, plus what the error carries for a retry. */
const failure = (err) => ({
  code: err && err.code ? err.code : null,
  message: String((err && err.message) || err),
  ...(err && err.status != null ? { status: err.status } : {}),
  ...(err && err.retryAfterMs != null ? { retryAfterMs: err.retryAfterMs } : {}),
});

function constant(name, value) {
  return { name: `constant ${name}`, fn: 'constant', args: [name], expect: { value: plain(value) } };
}

/** A scripted reply as a fetch Response would carry it. */
function replyOf(r) {
  const headers = new Headers(r.headers || {});
  const body = r.body ? bytesOf(r.body) : new Uint8Array(0);
  return new Response(r.status === 204 ? null : body, {
    status: r.status, statusText: r.statusText || '', headers,
  });
}

// ─────────────────────────────────────────────────────────────── transcribe

const AUDIO = { base64: b64(new TextEncoder().encode('OggS fake opus frames')) };
const ok = (text) => ({
  status: 200, statusText: 'OK', headers: { 'content-type': 'application/json' },
  body: { base64: b64(new TextEncoder().encode(JSON.stringify(text))) },
});
const jsonReply = (status, statusText, body, headers = {}) => ({
  status, statusText, headers: { 'content-type': 'application/json', ...headers },
  body: { base64: b64(new TextEncoder().encode(typeof body === 'string' ? body : JSON.stringify(body))) },
});

async function partsOf(form) {
  const parts = [];
  for (const [name, value] of form) {
    if (typeof value === 'string') {
      parts.push({ name, value });
    } else {
      const bytes = new Uint8Array(await value.arrayBuffer());
      // What undici writes for a file part: its own type, else octet-stream.
      parts.push({
        name, filename: value.name, contentType: value.type || 'application/octet-stream',
        size: bytes.byteLength, sha256: sha256(bytes),
      });
    }
  }
  return parts;
}

/**
 * transcribe cases: `{fn: 'transcribe', input: {config, audio: {type, body}, replies},
 * expect: {requests, value} | {requests, throws}}`. `replies` answer the requests in order
 * (withRetry may send up to three); every request the JS made is recorded.
 */
async function transcribeScenario(name, config, audio, replies) {
  const realFetch = globalThis.fetch;
  const requests = [];
  globalThis.fetch = async (url, init = {}) => {
    requests.push({
      url: String(url), method: init.method || 'GET',
      headers: { ...(init.headers || {}) }, parts: await partsOf(init.body),
    });
    const r = replies[requests.length - 1];
    if (!r) throw new TypeError('no scripted reply');
    return replyOf(r);
  };
  let expect;
  try {
    const t = createTranscriber({ ...config });
    const blob = new Blob([bytesOf(audio.body)], { type: audio.type });
    expect = { requests, value: await t.transcribe(blob) };
  } catch (err) {
    expect = { requests, throws: failure(err) };
  } finally {
    globalThis.fetch = realFetch;
  }
  return { name, fn: 'transcribe', input: { config, audio, replies }, expect: plain(expect) };
}

export async function transcribeFamily() {
  const cases = [];
  cases.push(constant('MAX_AUDIO_BYTES', MAX_AUDIO_BYTES));
  cases.push(constant('STT_PRESETS', STT_PRESETS));

  const groq = { kind: 'groq', apiKey: 'stt-test-key' };
  const openai = { kind: 'openai', apiKey: 'stt-test-key' };
  const webm = { type: 'audio/webm;codecs=opus', body: AUDIO };
  cases.push(await transcribeScenario('groq webm', groq, webm, [ok({ text: 'hello world' })]));
  cases.push(await transcribeScenario('openai webm', openai, webm, [ok({ text: 'hello world' })]));
  cases.push(await transcribeScenario('openai with language and prompt',
    { ...openai, language: 'en', prompt: 'What should it remember?' }, webm, [ok({ text: ' names ' })]));
  cases.push(await transcribeScenario('model override', { ...groq, model: 'whisper-large-v3' }, webm, [ok({ text: 'x' })]));

  // Whisper dispatches on the extension, so every codec spelling must map to the right one.
  for (const type of ['audio/ogg;codecs=opus', 'audio/mp4', 'audio/x-m4a', 'audio/aac', 'audio/wav',
    'audio/mpeg', 'audio/mp3', 'audio/webm', '', 'AUDIO/OGG', 'video/mp4']) {
    cases.push(await transcribeScenario(`filename for ${JSON.stringify(type)}`, groq, { type, body: AUDIO }, [ok({ text: 'ok' })]));
  }

  // The prompt is cut to 800 UTF-16 units. A cut through a surrogate pair leaves a lone
  // surrogate, which FormData turns into U+FFFD before it is sent.
  cases.push(await transcribeScenario('prompt over 800 chars', { ...groq, prompt: 'p'.repeat(900) }, webm, [ok({ text: 'ok' })]));
  cases.push(await transcribeScenario('prompt cut through a surrogate pair',
    { ...groq, prompt: `${'a'.repeat(799)}🌌 more` }, webm, [ok({ text: 'ok' })]));
  cases.push(await transcribeScenario('prompt of exactly 800 chars', { ...groq, prompt: 'q'.repeat(800) }, webm, [ok({ text: 'ok' })]));
  cases.push(await transcribeScenario('empty language and prompt are not sent', { ...groq, language: '', prompt: '' }, webm, [ok({ text: 'ok' })]));

  // Replies: the transcript is trimmed; anything without text is silence.
  for (const [i, body] of [{ text: '  padded\n' }, {}, { text: null }, { text: 7 }, { text: '' }, { text: true }].entries()) {
    cases.push(await transcribeScenario(`reply ${i}`, groq, webm, [ok(body)]));
  }

  // Bounds: checked before any request, at exactly the documented limit.
  cases.push(await transcribeScenario('audio at the 25 MB limit', groq,
    { type: 'audio/webm', body: { prefix: AUDIO.base64, size: MAX_AUDIO_BYTES } }, [ok({ text: 'long' })]));
  cases.push(await transcribeScenario('audio one byte over the limit', groq,
    { type: 'audio/webm', body: { prefix: AUDIO.base64, size: MAX_AUDIO_BYTES + 1 } }, [ok({ text: 'never' })]));
  cases.push(await transcribeScenario('audio far over the limit', groq,
    { type: 'audio/webm', body: { size: 30 * 1024 * 1024 + 123456 } }, [ok({ text: 'never' })]));

  // Configuration refused before anything is built.
  cases.push(await transcribeScenario('no key', { kind: 'groq', apiKey: '' }, webm, [ok({ text: 'never' })]));
  cases.push(await transcribeScenario('unknown kind', { kind: 'nope', apiKey: 'stt-test-key' }, webm, [ok({ text: 'never' })]));
  cases.push(await transcribeScenario('no kind and no key', { apiKey: '' }, webm, [ok({ text: 'never' })]));

  // Errors: the transcription key hint on auth; retryable codes retried up to three times.
  cases.push(await transcribeScenario('401 names the transcription key', groq, webm,
    [jsonReply(401, 'Unauthorized', { error: { message: 'Invalid API Key' } })]));
  cases.push(await transcribeScenario('403 with only an error code', openai, webm,
    [jsonReply(403, 'Forbidden', { error: { code: 'unsupported_country' } })]));
  cases.push(await transcribeScenario('400 with a non-JSON body', groq, webm,
    [jsonReply(400, 'Bad Request', 'not json')]));
  cases.push(await transcribeScenario('413 is a bad response', groq, webm,
    [jsonReply(413, 'Payload Too Large', { error: { message: 'file too large' } })]));
  cases.push(await transcribeScenario('429 three times', groq, webm, [
    jsonReply(429, 'Too Many Requests', { error: { message: 'slow down' } }, { 'retry-after': '0' }),
    jsonReply(429, 'Too Many Requests', { error: { message: 'slow down' } }, { 'retry-after': '0' }),
    jsonReply(429, 'Too Many Requests', { error: { message: 'still slow' } }, { 'retry-after': '0' }),
  ]));
  cases.push(await transcribeScenario('503 then success', groq, webm, [
    jsonReply(503, 'Service Unavailable', {}, { 'retry-after': '0' }), ok({ text: 'second time' }),
  ]));
  return { source: 'src/voice/transcribe.js', cases };
}

// ─────────────────────────────────────────────────────────────── tts

const u32 = (n) => { const b = new Uint8Array(4); new DataView(b.buffer).setUint32(0, n >>> 0, true); return b; };
const u16 = (n) => { const b = new Uint8Array(2); new DataView(b.buffer).setUint16(0, n, true); return b; };
const ascii = (s) => new TextEncoder().encode(s);
const cat = (...parts) => {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let at = 0;
  for (const p of parts) { out.set(p, at); at += p.length; }
  return out;
};

function fmtChunk({ encoding = 1, channels = 1, rate = 24000, bits = 16, blockAlign, byteRate, size = 16 } = {}) {
  const align = blockAlign ?? channels * bits / 8;
  const body = cat(u16(encoding), u16(channels), u32(rate), u32(byteRate ?? rate * align), u16(align), u16(bits),
    new Uint8Array(size - 16));
  return cat(ascii('fmt '), u32(size), body);
}
const chunk = (name, bytes, declared = bytes.length) => cat(ascii(name), u32(declared), bytes, new Uint8Array(bytes.length % 2));
/** RIFF/WAVE around the given chunks, with a correct size unless one is given. */
const riff = (chunks, size) => cat(ascii('RIFF'), u32(size ?? chunks.length + 4), ascii('WAVE'), chunks);

const pcm = (n, seed = 1) => Uint8Array.from({ length: n }, (_, i) => (i * 31 + seed) & 0xff);

const WAV_OK = riff(cat(fmtChunk(), chunk('data', pcm(480))));
const WAV_STEREO = riff(cat(fmtChunk({ channels: 2, rate: 48000 }), chunk('LIST', ascii('INFOx')), chunk('data', pcm(1920, 3))));
// A nonseekable encoder's stream: both sizes left at the sentinel, sized only at EOF.
const WAV_STREAMING = cat(ascii('RIFF'), u32(0xffffffff), ascii('WAVE'), fmtChunk(), ascii('data'), u32(0xffffffff), pcm(960, 5));

const wavReply = (bytes, headers = {}) => ({
  status: 200, statusText: 'OK', headers: { 'content-type': 'audio/wav', ...headers }, body: { base64: b64(bytes) },
});

/**
 * synthesize cases: `{fn: 'synthesize', input: {config, text, options, reply}, expect:
 * {request, value} | {request, throws}}`. `request` is null when the JS refused before
 * sending; `value` carries the audio's size and SHA-256 rather than its bytes.
 */
async function ttsScenario(name, config, text, options, reply) {
  let request = null;
  const fetchImpl = async (url, init = {}) => {
    if (request) throw new Error('speech sent two requests');
    request = {
      url: String(url), method: init.method, headers: { ...init.headers },
      redirect: init.redirect, body: JSON.parse(init.body),
    };
    return replyOf(reply);
  };
  let expect;
  try {
    const provider = createTTSProvider({ ...config, fetch: fetchImpl });
    const out = await provider.synthesize(text, { ...options });
    const bytes = new Uint8Array(out.audio);
    expect = {
      request,
      value: {
        contentType: out.contentType, duration: out.duration, sampleRate: out.sampleRate,
        channels: out.channels, size: bytes.byteLength, sha256: sha256(bytes),
      },
    };
  } catch (err) {
    expect = { request, throws: failure(err) };
  }
  return { name, fn: 'synthesize', input: { config, text, options, reply }, expect: plain(expect) };
}

function validateCase(name, bytes) {
  const args = [b64(bytes)];
  try {
    return { name, fn: 'validateSpeechAudio', args, expect: { value: plain(validateSpeechAudio(bytes.slice().buffer)) } };
  } catch (err) {
    return { name, fn: 'validateSpeechAudio', args, expect: { throws: String(err.message) } };
  }
}

export async function ttsFamily() {
  const cases = [];
  for (const [n, v] of Object.entries({
    MAX_TTS_INPUT_CHARS, MAX_TTS_AUDIO_BYTES, MAX_TTS_AUDIO_SECONDS, TTS_DEADLINE_MS, TTS_PRESETS,
  })) cases.push(constant(n, v));

  const key = { apiKey: 'tts-test-key' };
  const say = 'What should it remember?';
  cases.push(await ttsScenario('default voice and rate', key, say, {}, wavReply(WAV_OK)));
  cases.push(await ttsScenario('cedar at 1.25', { ...key, voice: 'cedar', rate: 1.25 }, say, {}, wavReply(WAV_OK)));
  cases.push(await ttsScenario('per-call rate overrides', { ...key, rate: 1.5 }, say, { rate: 0.5 }, wavReply(WAV_STEREO)));
  cases.push(await ttsScenario('key is trimmed', { apiKey: '  tts-test-key \n' }, say, {}, wavReply(WAV_OK)));
  cases.push(await ttsScenario('input is sent untrimmed', key, '  padded words  ', {}, wavReply(WAV_OK)));
  cases.push(await ttsScenario('rate bounds are inclusive', { ...key, rate: 0.25 }, say, { rate: 4 }, wavReply(WAV_OK)));
  cases.push(await ttsScenario('input at the 4096-char limit', key, 'w'.repeat(MAX_TTS_INPUT_CHARS), {}, wavReply(WAV_OK)));
  cases.push(await ttsScenario('streaming sizes are rewritten after EOF', key, say, {}, wavReply(WAV_STREAMING)));
  cases.push(await ttsScenario('content length that matches', key, say, {}, wavReply(WAV_OK, { 'content-length': String(WAV_OK.length) })));

  // Configuration and input refused before any request.
  for (const [i, config] of [
    { apiKey: '' }, { apiKey: '   ' }, {}, { ...key, voice: 'alloy' }, { ...key, voice: 'Marin' },
    { ...key, rate: 0.24 }, { ...key, rate: 4.01 }, { ...key, rate: '1' },
    { ...key, deadlineMs: 0 }, { ...key, deadlineMs: 120001 }, { ...key, deadlineMs: 1.5 },
  ].entries()) cases.push(await ttsScenario(`config refused ${i}`, config, say, {}, wavReply(WAV_OK)));
  for (const [i, [text, options]] of [
    ['', {}], ['   \n', {}], ['w'.repeat(MAX_TTS_INPUT_CHARS + 1), {}], [say, { rate: 5 }], [say, { rate: 0 }],
  ].entries()) cases.push(await ttsScenario(`input refused ${i}`, key, text, options, wavReply(WAV_OK)));

  // Errors: one request, never retried, never echoing the provider's body or reason phrase.
  const echo = { base64: b64(ascii(JSON.stringify({ error: { message: 'Incorrect API key provided: tts-test-key' } }))) };
  for (const [status, headers] of [[401, {}], [403, {}], [429, { 'retry-after': '7' }], [500, {}], [400, {}], [404, {}]]) {
    cases.push(await ttsScenario(`status ${status}`, key, say, {},
      { status, statusText: 'Reason phrase with tts-test-key', headers: { 'content-type': 'application/json', ...headers }, body: echo }));
  }

  // The response: type, declared length, actual length, then the WAV itself.
  for (const [i, type] of ['audio/wav; codecs=1', 'AUDIO/X-WAV', 'audio/wave', 'audio/vnd.wave', 'application/octet-stream',
    'audio/mpeg', 'text/html', ''].entries()) {
    cases.push(await ttsScenario(`content type ${i}`, key, say, {}, { ...wavReply(WAV_OK), headers: type ? { 'content-type': type } : {} }));
  }
  cases.push(await ttsScenario('declared length over the limit', key, say, {}, wavReply(WAV_OK, { 'content-length': String(MAX_TTS_AUDIO_BYTES + 1) })));
  cases.push(await ttsScenario('declared length not a number', key, say, {}, wavReply(WAV_OK, { 'content-length': '12x' })));
  cases.push(await ttsScenario('declared length longer than the body', key, say, {}, wavReply(WAV_OK, { 'content-length': String(WAV_OK.length + 10) })));
  cases.push(await ttsScenario('empty body', key, say, {}, wavReply(new Uint8Array(0), { 'content-length': '0' })));
  const big = riff(cat(fmtChunk({ rate: 8000, bits: 8 })), MAX_TTS_AUDIO_BYTES - 8);
  cases.push(await ttsScenario('body over the limit with no declared length', key, say, {},
    { status: 200, statusText: 'OK', headers: { 'content-type': 'audio/wav' }, body: { prefix: b64(cat(big, ascii('data'), u32(MAX_TTS_AUDIO_BYTES - 44 + 1))), size: MAX_TTS_AUDIO_BYTES + 1 } }));
  const atLimitData = MAX_TTS_AUDIO_BYTES - 44;
  cases.push(await ttsScenario('body at the size limit', key, say, {},
    { status: 200, statusText: 'OK', headers: { 'content-type': 'audio/wav' }, body: { prefix: b64(cat(riff(fmtChunk({ rate: 96000, bits: 8 }), MAX_TTS_AUDIO_BYTES - 8), ascii('data'), u32(atLimitData))), size: MAX_TTS_AUDIO_BYTES } }));
  // 8 kHz, 8-bit mono is 8000 bytes a second: 120 s is allowed, 120 s and one frame is not.
  for (const [label, seconds, extra] of [['at 120 s', 120, 0], ['one frame over 120 s', 120, 1]]) {
    const data = 8000 * seconds + extra;
    const head = cat(ascii('RIFF'), u32(36 + data + (data % 2)), ascii('WAVE'), fmtChunk({ rate: 8000, bits: 8 }), ascii('data'), u32(data));
    cases.push(await ttsScenario(`duration ${label}`, key, say, {},
      { status: 200, statusText: 'OK', headers: { 'content-type': 'audio/wav' }, body: { prefix: b64(head), size: head.length + data + (data % 2) } }));
  }

  // The WAV checks, through the same request path and directly.
  const wavs = {
    'valid mono': WAV_OK,
    'valid stereo with a LIST chunk': WAV_STEREO,
    'streaming sentinel outside the adapter': WAV_STREAMING,
    'too short': WAV_OK.slice(0, 43),
    'not RIFF': cat(ascii('RIFX'), WAV_OK.slice(4)),
    'not WAVE': cat(WAV_OK.slice(0, 8), ascii('AVI '), WAV_OK.slice(12)),
    'RIFF size off by two': riff(cat(fmtChunk(), chunk('data', pcm(480))), 480 + 8 + 24 + 4 + 2),
    'no fmt chunk': riff(cat(chunk('data', pcm(480)), chunk('junk', pcm(30)))),
    'no data chunk': riff(cat(fmtChunk(), chunk('LIST', pcm(40)))),
    'empty data chunk': riff(cat(fmtChunk(), chunk('data', new Uint8Array(0)))),
    'two fmt chunks': riff(cat(fmtChunk(), fmtChunk(), chunk('data', pcm(480)))),
    'two data chunks': riff(cat(fmtChunk(), chunk('data', pcm(480)), chunk('data', pcm(480)))),
    'fmt too small': riff(cat(ascii('fmt '), u32(14), new Uint8Array(14), chunk('data', pcm(480)))),
    'extended fmt': riff(cat(fmtChunk({ size: 18 }), chunk('data', pcm(480)))),
    'float encoding': riff(cat(fmtChunk({ encoding: 3, bits: 32 }), chunk('data', pcm(480)))),
    'three channels': riff(cat(fmtChunk({ channels: 3 }), chunk('data', pcm(480)))),
    '12-bit samples': riff(cat(fmtChunk({ bits: 12, blockAlign: 2, byteRate: 48000 }), chunk('data', pcm(480)))),
    'sample rate 7999': riff(cat(fmtChunk({ rate: 7999 }), chunk('data', pcm(480)))),
    'sample rate 96001': riff(cat(fmtChunk({ rate: 96001 }), chunk('data', pcm(480)))),
    'sample rate 96000': riff(cat(fmtChunk({ rate: 96000 }), chunk('data', pcm(480)))),
    'wrong block align': riff(cat(fmtChunk({ blockAlign: 4 }), chunk('data', pcm(480)))),
    'wrong byte rate': riff(cat(fmtChunk({ byteRate: 1000 }), chunk('data', pcm(480)))),
    'partial frame': riff(cat(fmtChunk(), chunk('data', pcm(481)))),
    'odd chunk is padded': riff(cat(fmtChunk(), chunk('odd ', pcm(3)), chunk('data', pcm(480)))),
    'chunk runs past the end': cat(riff(cat(fmtChunk(), ascii('data'), u32(500), pcm(480)))),
    'truncated chunk header': riff(cat(fmtChunk(), chunk('data', pcm(480)), ascii('LI'))),
  };
  for (const [label, bytes] of Object.entries(wavs)) {
    cases.push(validateCase(`validate ${label}`, bytes));
    cases.push(await ttsScenario(`wav ${label}`, key, say, {}, wavReply(bytes)));
  }
  return { source: 'src/providers/tts.js', cases };
}
