import test from 'node:test';
import assert from 'node:assert/strict';

import {
  createTTSProvider, TTS_PRESETS, MAX_TTS_INPUT_CHARS, MAX_TTS_AUDIO_BYTES,
  MAX_TTS_AUDIO_SECONDS, TTS_DEADLINE_MS, validateSpeechAudio,
} from '../src/providers/tts.js';
import { splitSpeechText } from '../src/voice/speak.js';
import { ProviderError } from '../src/providers/errors.js';

const KEY = 'test-only-not-a-real-speech-key';
const flush = async () => { for (let i = 0; i < 30; i++) await Promise.resolve(); };
const deferred = () => {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
};

function wav(samples = 240, sampleRate = 24000) {
  const bytes = new ArrayBuffer(44 + samples * 2);
  const view = new DataView(bytes);
  const tag = (at, text) => [...text].forEach((c, n) => view.setUint8(at + n, c.charCodeAt(0)));
  tag(0, 'RIFF'); view.setUint32(4, bytes.byteLength - 8, true); tag(8, 'WAVE');
  tag(12, 'fmt '); view.setUint32(16, 16, true); view.setUint16(20, 1, true);
  view.setUint16(22, 1, true); view.setUint32(24, sampleRate, true);
  view.setUint32(28, sampleRate * 2, true); view.setUint16(32, 2, true);
  view.setUint16(34, 16, true); tag(36, 'data'); view.setUint32(40, samples * 2, true);
  return bytes;
}

const audioResponse = (bytes = wav(), headers = {}) => new Response(bytes, {
  headers: { 'content-type': 'audio/wav', ...headers },
});
const provider = (fetch, options = {}) => createTTSProvider({ apiKey: KEY, fetch, ...options });
const code = (expected) => (error) => error instanceof ProviderError && error.code === expected;

function streamingWav(audio = wav(), { riff = true, data = true, dataSizeOffset = 40 } = {}) {
  const bytes = audio.slice(0);
  const view = new DataView(bytes);
  if (riff) view.setUint32(4, 0xffffffff, true);
  if (data) view.setUint32(dataSizeOffset, 0xffffffff, true);
  return bytes;
}

test.beforeEach((t) => {
  t.mock.method(globalThis, 'fetch', () => { throw new Error('A speech test attempted real HTTP.'); });
});

test('speech posts only the allowlisted model, voice, format and bearer credential', async () => {
  const calls = [];
  const p = provider(async (url, init) => {
    calls.push({ url, init });
    return audioResponse();
  }, { voice: 'cedar', rate: 0.75, baseUrl: 'https://not-allowed.invalid', model: 'not-allowed' });
  const out = await p.synthesize('A short speech preview.');
  assert.equal(calls.length, 1);
  const { url, init } = calls[0];
  assert.equal(url, 'https://api.openai.com/v1/audio/speech');
  assert.equal(init.method, 'POST');
  assert.deepEqual(init.headers, {
    'content-type': 'application/json', accept: 'audio/wav', authorization: `Bearer ${KEY}`,
  });
  assert.equal(init.credentials, 'omit');
  assert.equal(init.mode, 'cors');
  assert.equal(init.cache, 'no-store');
  assert.equal(init.redirect, 'error');
  assert.ok(init.signal instanceof AbortSignal);
  assert.deepEqual(JSON.parse(init.body), {
    model: 'gpt-4o-mini-tts', input: 'A short speech preview.', voice: 'cedar',
    response_format: 'wav', speed: 0.75,
  });
  assert.equal(out.contentType, 'audio/wav');
  assert.equal(out.duration, 0.01);
  assert.equal(out.audio.byteLength, 524);
  assert.deepEqual(TTS_PRESETS.openai.voices, ['marin', 'cedar']);
  assert.equal(TTS_PRESETS.openai.maxChars, 4096);
  assert.equal(new URL(TTS_PRESETS.openai.baseUrl).origin, new URL(url).origin);
});

test('configuration requires a dedicated key, a curated voice and a finite supported rate', () => {
  for (const apiKey of ['', '   ', null, 123]) {
    assert.throws(() => createTTSProvider({ apiKey }), code('config'));
  }
  for (const voice of ['alloy', 'custom', '', null]) {
    assert.throws(() => provider(() => {}, { voice }), code('config'));
  }
  for (const rate of [0, -1, 0.249, 4.01, NaN, Infinity, '1', null]) {
    assert.throws(() => provider(() => {}, { rate }), code('config'));
  }
  for (const deadlineMs of [0, -1, 120001, NaN, 1.5]) {
    assert.throws(() => provider(() => {}, { deadlineMs }), code('config'));
  }
});

test('the API input ceiling is enforced at 4096, without silently trimming oversized input', async () => {
  const inputs = [];
  const p = provider(async (_, init) => {
    inputs.push(JSON.parse(init.body).input);
    return audioResponse();
  });
  const exactly = 'x'.repeat(MAX_TTS_INPUT_CHARS);
  await p.synthesize(exactly);
  assert.equal(inputs[0], exactly);
  for (const invalid of ['', '  ', null, {}, 'x'.repeat(MAX_TTS_INPUT_CHARS + 1)]) {
    await assert.rejects(p.synthesize(invalid), code('config'));
  }
  await assert.rejects(p.synthesize('Hello', { rate: 5 }), code('config'));
  assert.equal(inputs.length, 1, 'invalid input must not make a paid request');
});

test('long unbroken Unicode text is fully split into legal requests without broken surrogates', async () => {
  const text = 'a'.repeat(4095) + '\u{1f642}'.repeat(4200) + 'last';
  const chunks = splitSpeechText(text, { maxChars: MAX_TTS_INPUT_CHARS });
  assert.equal(chunks.join(''), text);
  const sent = [];
  const p = provider(async (_, init) => {
    sent.push(JSON.parse(init.body).input);
    return audioResponse();
  });
  for (const chunk of chunks) {
    assert.ok(chunk.length <= MAX_TTS_INPUT_CHARS);
    assert.equal(chunk.isWellFormed(), true);
    await p.synthesize(chunk);
  }
  assert.equal(sent.join(''), text);
});

test('vendor status and retry-after survive without exposing untrusted error detail or retrying', async () => {
  for (const [status, expected] of [[401, 'auth'], [403, 'auth'], [429, 'rate_limit'],
    [400, 'bad_response'], [500, 'overloaded'], [503, 'overloaded']]) {
    let calls = 0;
    const p = provider(async () => {
      calls++;
      return new Response(JSON.stringify({ error: { message: 'vendor detail' } }), {
        status, headers: { 'content-type': 'application/json', 'retry-after': '2' },
      });
    });
    await assert.rejects(p.synthesize('Hello'), (error) => {
      assert.ok(code(expected)(error));
      assert.equal(error.status, status);
      assert.equal(error.retryAfterMs, 2000);
      assert.match(error.message, /speech request failed/);
      assert.doesNotMatch(error.message, /vendor detail/);
      if (expected === 'auth') assert.match(error.message, /speech key/);
      return true;
    });
    assert.equal(calls, 1);
  }
});

test('speech errors do not expose credentials echoed by a provider body or status text', async () => {
  const key = 'fake-speech-key/for+unit-tests=only';
  const encodings = [key, encodeURIComponent(key), Buffer.from(key).toString('base64')];
  for (const detail of encodings) {
    for (const field of ['message', 'code']) {
      const p = provider(async () => new Response(JSON.stringify({ error: { [field]: `Rejected ${detail}` } }), {
        status: 401, statusText: `Rejected ${detail}`, headers: { 'content-type': 'application/json' },
      }), { apiKey: `  ${key}  ` });
      await assert.rejects(p.synthesize('Preview.'), (error) => {
        assert.ok(code('auth')(error));
        const rendered = [String(error), error.stack, JSON.stringify(error)].join('\n');
        for (const secret of encodings) {
          assert.equal(rendered.includes(secret), false, 'errors must not retain echoed credentials');
        }
        assert.match(error.message, /speech key/);
        return true;
      });
    }
  }
});

test('malformed, empty and oversized error bodies still report their HTTP failure', async () => {
  for (const body of ['<h1>Denied</h1>', '', 'x'.repeat(65537)]) {
    await assert.rejects(provider(async () => new Response(body, { status: 401 }))
      .synthesize('Hello'), code('auth'));
  }
});

test('opaque transport errors do not claim that CORS or a key was actually verified', async () => {
  let calls = 0;
  await assert.rejects(provider(async () => {
    calls++;
    throw new TypeError('Failed to fetch');
  }).synthesize('Hello'), (error) => {
    assert.ok(code('network')(error));
    assert.match(error.message, /key.*connection.*browser compatibility.*CORS/);
    return true;
  });
  assert.equal(calls, 1);
});

test('pre-cancelled requests never send text, even with a custom abort reason', async () => {
  const caller = new AbortController();
  caller.abort(new Error('pause'));
  let calls = 0;
  await assert.rejects(provider(async () => { calls++; return audioResponse(); })
    .synthesize('Hello', { signal: caller.signal }), code('aborted'));
  assert.equal(calls, 0);
});

test('caller cancellation wins over a transport that ignores abort and completes late', async () => {
  const caller = new AbortController();
  const late = deferred();
  let requestSignal;
  const pending = provider((_, init) => {
    requestSignal = init.signal;
    return late.promise;
  }).synthesize('Hello', { signal: caller.signal });
  caller.abort(new Error('exit'));
  await assert.rejects(pending, code('aborted'));
  assert.equal(requestSignal.aborted, true);
  late.resolve(audioResponse());
  await flush();
});

test('a deadline is a timeout, not cancellation, and bounds a hung fetch without retrying', async () => {
  const timer = new AbortController();
  let calls = 0;
  let requestSignal;
  const p = provider((_, init) => {
    calls++;
    requestSignal = init.signal;
    return new Promise(() => {});
  }, { deadline(signal, ms) {
    assert.equal(ms, TTS_DEADLINE_MS);
    return AbortSignal.any([signal, timer.signal]);
  } });
  const pending = p.synthesize('Hello');
  timer.abort(new DOMException('deadline', 'TimeoutError'));
  await assert.rejects(pending, code('timeout'));
  assert.equal(calls, 1);
  assert.equal(requestSignal.aborted, true);
});

test('the first abort cause distinguishes a deadline from a later user cancellation', async () => {
  const timer = new AbortController();
  const caller = new AbortController();
  const p = provider(() => new Promise(() => {}), {
    deadline: (signal) => AbortSignal.any([signal, timer.signal]),
  });
  const pending = p.synthesize('Hello', { signal: caller.signal });
  timer.abort(new DOMException('deadline', 'TimeoutError'));
  caller.abort();
  await assert.rejects(pending, code('timeout'));
});

test('a caller using a TimeoutError reason is still cancellation, not a retryable failure', async () => {
  const caller = new AbortController();
  const pending = provider(() => new Promise(() => {})).synthesize('Hello', { signal: caller.signal });
  caller.abort(new DOMException('caller deadline', 'TimeoutError'));
  await assert.rejects(pending, code('aborted'));
});

test('the deadline includes a stalled response body and cancels the reader', async () => {
  const timer = new AbortController();
  let cancelled = 0;
  let response;
  const p = provider(async () => {
    response = new Response(new ReadableStream({ cancel() { cancelled++; } }), {
      headers: { 'content-type': 'audio/wav' },
    });
    return response;
  }, { deadline: (signal) => AbortSignal.any([signal, timer.signal]) });
  const pending = p.synthesize('Hello');
  await flush();
  timer.abort(new DOMException('deadline', 'TimeoutError'));
  await assert.rejects(pending, code('timeout'));
  assert.equal(cancelled, 1);
  assert.equal(response.body.locked, false);
});

test('body cancellation remains distinguishable from decoding or vendor failures', async () => {
  const caller = new AbortController();
  let cancelled = 0;
  const p = provider(async () => new Response(new ReadableStream({
    cancel() { cancelled++; },
  }), { headers: { 'content-type': 'audio/wav' } }));
  const pending = p.synthesize('Hello', { signal: caller.signal });
  await flush();
  caller.abort();
  await assert.rejects(pending, code('aborted'));
  assert.equal(cancelled, 1);
});

test('empty, mislabeled, malformed and truncated audio fail explicitly', async () => {
  const complete = wav();
  for (const response of [
    audioResponse(new ArrayBuffer(0)),
    audioResponse(new TextEncoder().encode('{"error":"not audio"}')),
    audioResponse(complete, { 'content-type': 'audio/mpeg' }),
    audioResponse(complete, { 'content-length': String(complete.byteLength + 1) }),
    audioResponse(complete.slice(0, -2)),
    audioResponse(complete, { 'content-length': 'nonsense' }),
    new Response(null, { headers: { 'content-type': 'audio/wav' } }),
  ]) {
    await assert.rejects(provider(async () => response).synthesize('Hello'), code('bad_response'));
  }
});

test('declared oversize responses fail before buffering their payload', async () => {
  let reads = 0;
  const response = {
    ok: true,
    headers: new Headers({ 'content-type': 'audio/wav', 'content-length': String(MAX_TTS_AUDIO_BYTES + 1) }),
    body: { getReader() { reads++; throw new Error('must not buffer this body'); } },
  };
  await assert.rejects(provider(async () => response).synthesize('Hello'), code('bad_response'));
  assert.equal(reads, 0);
});

test('actual streamed size is bounded even when content-length is missing', async () => {
  let cancelled = 0;
  const stream = new ReadableStream({
    start(controller) { controller.enqueue(new Uint8Array(MAX_TTS_AUDIO_BYTES + 1)); },
    cancel() { cancelled++; },
  });
  await assert.rejects(provider(async () => new Response(stream, {
    headers: { 'content-type': 'audio/wav' },
  })).synthesize('Hello'), code('bad_response'));
  assert.equal(cancelled, 1);
});

test('strict WAV validation rejects unknown-length, compressed, misaligned and empty files', () => {
  const mutations = [
    (view) => view.setUint32(4, 0xffffffff, true),
    (view) => view.setUint16(20, 3, true),
    (view) => view.setUint16(22, 3, true),
    (view) => view.setUint32(28, 1, true),
    (view) => view.setUint16(32, 1, true),
    (view) => view.setUint16(34, 7, true),
    (view) => view.setUint32(40, 100000, true),
    (view) => view.setUint8(36, 'X'.charCodeAt(0)),
  ];
  for (const mutate of mutations) {
    const bytes = wav();
    mutate(new DataView(bytes));
    assert.throws(() => validateSpeechAudio(bytes), code('bad_response'));
  }
  assert.throws(() => validateSpeechAudio(wav(0)), code('bad_response'));
  assert.throws(() => validateSpeechAudio(wav((MAX_TTS_AUDIO_SECONDS + 1) * 24000)), code('bad_response'));
  assert.throws(() => validateSpeechAudio(new ArrayBuffer(MAX_TTS_AUDIO_BYTES + 1)), code('bad_response'));
});

test('well-formed WAV survives missing content-length and permitted binary content types', async () => {
  for (const type of ['audio/wav; codecs=1', 'audio/x-wav', 'application/octet-stream']) {
    const out = await provider(async () => audioResponse(wav(), { 'content-type': type })).synthesize('Hello');
    assert.deepEqual(validateSpeechAudio(out.audio), { channels: 1, sampleRate: 24000, duration: 0.01 });
  }
});

test('completed HTTP audio normalizes only explicit RIFF/data streaming size sentinels', async () => {
  const sized = wav();
  for (const sizes of [{ riff: true, data: true }, { riff: true, data: false },
    { riff: false, data: true }]) {
    const streamed = streamingWav(sized, sizes);
    assert.throws(() => validateSpeechAudio(streamed), code('bad_response'),
      'the public validator must not assume a byte buffer came from a completed HTTP body');
    const out = await provider(async () => audioResponse(streamed)).synthesize('Preview.');
    assert.deepEqual(new Uint8Array(out.audio), new Uint8Array(sized));
    assert.equal(out.duration, 0.01);
    assert.deepEqual(validateSpeechAudio(out.audio), { duration: 0.01, sampleRate: 24000, channels: 1 });
    assert.equal(new DataView(streamed).getUint32(sizes.riff ? 4 : 40, true), 0xffffffff,
      'normalization must only mutate the adapter-owned response buffer');
  }
});

test('streaming WAV normalization waits for successful EOF, not the arrival of complete sample bytes', async () => {
  const sized = wav();
  const streamed = new Uint8Array(streamingWav(sized));
  let stream;
  const body = new ReadableStream({ start(controller) { stream = controller; } });
  const p = provider(async () => new Response(body, { headers: { 'content-type': 'audio/wav' } }));
  let settled = false;
  const pending = p.synthesize('Preview.').finally(() => { settled = true; });
  stream.enqueue(streamed.subarray(0, 17));
  stream.enqueue(streamed.subarray(17, 43));
  stream.enqueue(streamed.subarray(43));
  await flush();
  assert.equal(settled, false);
  stream.close();
  const out = await pending;
  assert.deepEqual(new Uint8Array(out.audio), new Uint8Array(sized));
});

test('streaming data may follow ordinary bounded metadata chunks rather than a fixed 44-byte header', async () => {
  const sized = wav();
  const extended = new Uint8Array(sized.byteLength + 12);
  extended.set(new Uint8Array(sized, 0, 36));
  extended.set(new TextEncoder().encode('JUNK'), 36);
  const view = new DataView(extended.buffer);
  view.setUint32(40, 4, true);
  extended.set(new Uint8Array(sized, 36), 48);
  view.setUint32(4, extended.byteLength - 8, true);
  const streamed = streamingWav(extended.buffer, { dataSizeOffset: 52 });
  const out = await provider(async () => audioResponse(streamed)).synthesize('Preview.');
  assert.deepEqual(new Uint8Array(out.audio), extended);
  assert.equal(out.duration, 0.01);
});

test('streaming size sentinels never authorize partial PCM frames or ordinary length mismatches', async () => {
  const stereo = wav();
  const format = new DataView(stereo);
  format.setUint16(22, 2, true);
  format.setUint32(28, 96000, true);
  format.setUint16(32, 4, true);
  const sentinelStereo = streamingWav(stereo);
  for (const bytes of [
    streamingWav().slice(0, -1),
    sentinelStereo.slice(0, -2),
    wav().slice(0, -2),
    streamingWav(wav(), { riff: true, data: false }).slice(0, -2),
    streamingWav(wav(), { riff: false, data: true }).slice(0, -2),
    streamingWav(wav(0)),
  ]) {
    await assert.rejects(provider(async () => audioResponse(bytes)).synthesize('Preview.'), code('bad_response'));
  }
});

test('streaming size sentinels do not relax content-length or failed transport completion', async () => {
  const streamed = streamingWav();
  await assert.rejects(provider(async () => audioResponse(streamed, {
    'content-length': String(streamed.byteLength + 2),
  })).synthesize('Preview.'), code('bad_response'));

  let stream;
  const body = new ReadableStream({ start(controller) { stream = controller; } });
  const pending = provider(async () => new Response(body, { headers: { 'content-type': 'audio/wav' } }))
    .synthesize('Preview.');
  stream.enqueue(new Uint8Array(streamed));
  await flush();
  stream.error(new TypeError('HTTP body ended without complete transfer framing'));
  await assert.rejects(pending, code('network'));
});

test('only sanctioned size fields may be normalized and all format and duration limits remain enforced', async () => {
  const invalid = [
    [4, 0xfffffffe],
    [40, 0xfffffffe],
    [16, 0xffffffff],
    [28, 1],
    [22, 3],
  ];
  for (const [offset, value] of invalid) {
    const streamed = streamingWav();
    new DataView(streamed).setUint32(offset, value, true);
    await assert.rejects(provider(async () => audioResponse(streamed)).synthesize('Preview.'), code('bad_response'));
  }
  const tooLong = streamingWav(wav((MAX_TTS_AUDIO_SECONDS + 1) * 24000));
  await assert.rejects(provider(async () => audioResponse(tooLong)).synthesize('Preview.'), code('bad_response'));
  const tooLarge = streamingWav(new ArrayBuffer(MAX_TTS_AUDIO_BYTES + 1));
  await assert.rejects(provider(async () => audioResponse(tooLarge)).synthesize('Preview.'), code('bad_response'));
});
