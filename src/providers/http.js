// The shared HTTP spine: how a credential is attached, what counts as "on this machine",
// and how a failed response becomes a ProviderError.
//
// It exists because the answer to "how does this provider authenticate?" used to be "read
// whichever factory createProvider happened to dispatch to". Three call sites had three
// hardcoded header shapes — `authorization: Bearer` here, `x-api-key` there, a third copy
// in src/voice/transcribe.js — and two of them disagreed about what localhost means.
//
// The disagreement was not theoretical. Both old checks were prefix matches on the raw
// string, so `http://localhost.evil.com/v1` satisfied them: a remote host the app would
// have treated as trusted-local, requiring no key and blaming its failures on CORS. It was
// unreachable only because the Base URL field was dead UI. Making that field reachable is
// what this change does, so the check had to start parsing URLs first.

import { ProviderError, codeForStatus, retryAfterMs } from './errors.js';

/**
 * How a credential rides on a request. Everything that differs between providers is data
 * in here; nothing about it is implicit in which factory built the adapter.
 *
 * @typedef {object} AuthDescriptor
 * @property {'none'|'bearer'|'header'|'query'} scheme
 * @property {string} [header] header name, for scheme 'header' (e.g. 'x-api-key')
 * @property {string} [prefix] value prefix; 'Bearer ' by default for scheme 'bearer'
 * @property {string} [param]  query parameter name, for scheme 'query'
 * @property {Record<string,string>} [extraHeaders] sent every time, credential or not
 */

/** @type {AuthDescriptor} */
export const AUTH_NONE = { scheme: 'none' };
/** @type {AuthDescriptor} */
export const AUTH_BEARER = { scheme: 'bearer' };

/**
 * Attach the credential to exactly one request.
 *
 * Returns a url as well as headers because 'query' puts the credential in the URL and the
 * other three do not — and a call site that has to know which is a call site that has
 * learned the scheme all over again.
 *
 * Deliberately does NOT set content-type. The transcription endpoint posts FormData and
 * must let the browser write its own multipart boundary, so a helper that forced JSON
 * would be unusable at the one call site that proves this generalises past this directory.
 */
export function applyAuth(url, { auth = AUTH_NONE, apiKey = '', headers = {} } = {}) {
  const a = auth || AUTH_NONE;
  const out = { ...headers, ...(a.extraHeaders || {}) };
  const key = String(apiKey || '');
  let target = String(url);

  if (key) {
    if (a.scheme === 'bearer') {
      out.authorization = `${a.prefix || 'Bearer '}${key}`;
    } else if (a.scheme === 'header' && a.header) {
      out[a.header.toLowerCase()] = `${a.prefix || ''}${key}`;
    } else if (a.scheme === 'query' && a.param) {
      const u = new URL(target);
      u.searchParams.set(a.param, key);
      target = u.toString();
    }
  }
  return { url: target, headers: out };
}

// Loopback, and nothing else. 0.0.0.0 is deliberately absent: it is not a loopback
// address, and Chrome blocks it as a private-network target however many people type it.
const LOOPBACK = /^(localhost|\[::1\]|127(?:\.\d{1,3}){3})$/i;

/**
 * The one definition of "this is on my machine", shared by the adapters, the settings
 * screen and the comment above the CSP. Parses the URL rather than matching its prefix,
 * which is what makes `localhost.evil.com` remote again.
 */
export function isLoopback(url) {
  try {
    const h = new URL(String(url)).hostname;   // '[::1]' keeps its brackets here
    return LOOPBACK.test(h) || /\.localhost$/i.test(h);
  } catch {
    return false;
  }
}

/**
 * A base URL the user typed may only ever be on their own machine.
 *
 * This is policy, not mechanism. The CSP allowlist in index.html is the thing standing
 * between the stored API key and an injected script that wants to post it somewhere, and a
 * free-text remote host hands that away. Loopback is safe to widen because anyone who can
 * receive a request there is already on the victim's machine.
 */
export function assertLoopback(url) {
  if (isLoopback(url)) return;
  throw new ProviderError('config',
    `${url} is not on this machine. A server you type in has to be at localhost or ` +
    '127.0.0.1 — this page will only talk to the providers in its allowlist plus your ' +
    'own machine, which is what stops a stolen key being posted anywhere.');
}

/**
 * Chrome 142 gates a request from a PUBLIC page to a loopback address behind a user
 * permission prompt, and `targetAddressSpace: 'local'` is what asks for it. It is also
 * what exempts the request from mixed-content blocking, which is otherwise what kills an
 * http://localhost call from an https:// page. The older design, where the server answered
 * a preflight with Access-Control-Allow-Private-Network, was abandoned — no local server
 * needs to send that header any more.
 *
 * Chrome-only; an unknown fetch option is ignored everywhere else. Still gated on the page
 * not already being local, because localhost -> localhost is not gated at all and asking
 * for a permission nobody needs is a user-visible interruption.
 */
export function localFetchOptions(url) {
  return isLoopback(url) && pageIsPublic() ? { targetAddressSpace: 'local' } : {};
}

function pageIsPublic() {
  if (typeof location === 'undefined') return false;   // Node, i.e. a test
  return location.protocol !== 'file:' && !isLoopback(location.origin);
}

/**
 * Every adapter's failed response becomes the same four fields. This was copied out three
 * times, and the copies had already drifted apart in what they read off the error body.
 */
export async function httpError(res, { label, keyHint = 'check the API key in Settings' }) {
  let detail = '';
  try {
    const body = await res.json();
    detail = (body && body.error && (body.error.message || body.error.code)) || '';
  } catch { /* a non-JSON error body is still an error */ }
  const code = codeForStatus(res.status);
  const hint = code === 'auth' ? ` — ${keyHint}` : '';
  return new ProviderError(code, `${label} ${res.status}: ${detail || res.statusText}${hint}`, {
    status: res.status,
    retryAfterMs: retryAfterMs(res.headers),
  });
}

export const trimSlash = (s) => String(s || '').replace(/\/+$/, '');
export const hostOf = (u) => { try { return new URL(u).host; } catch { return u; } };
