// Reading the question aloud, so an interview can be done without looking at the screen.
//
// speechSynthesis is the one voice API that works essentially everywhere, but it has two
// habits worth knowing: iOS refuses to speak unless the first utterance is triggered
// inside a user gesture, and voices load asynchronously, so asking for them on page load
// returns an empty list.

let primed = false;

export function ttsSupported() {
  return typeof window !== 'undefined' && 'speechSynthesis' in window;
}

/**
 * Unlock speech. Must be called synchronously inside a real user gesture — the tap that
 * starts the interview — or iOS silently ignores every later `speak`.
 */
export function primeSpeech() {
  if (primed || !ttsSupported()) return;
  try {
    const u = new SpeechSynthesisUtterance(' ');
    u.volume = 0;
    window.speechSynthesis.speak(u);
    primed = true;
  } catch { /* nothing to unlock */ }
}

/** Voices arrive asynchronously; resolve once they do, or give up and take the default. */
function voicesReady(timeoutMs = 1000) {
  return new Promise((resolve) => {
    const now = window.speechSynthesis.getVoices();
    if (now && now.length) return resolve(now);
    const t = setTimeout(() => resolve(window.speechSynthesis.getVoices() || []), timeoutMs);
    window.speechSynthesis.addEventListener('voiceschanged', () => {
      clearTimeout(t);
      resolve(window.speechSynthesis.getVoices() || []);
    }, { once: true });
  });
}

function pickVoice(voices, lang) {
  if (!voices || !voices.length) return null;
  const base = String(lang || 'en-US').slice(0, 2).toLowerCase();
  const matching = voices.filter((v) => String(v.lang || '').toLowerCase().startsWith(base));
  // A local voice starts speaking immediately; a network voice can lag by a second or
  // more, which reads as the app having hung.
  return matching.find((v) => v.localService) || matching[0] || null;
}

/**
 * Speak text and resolve when it finishes.
 *
 * Never rejects: a question that failed to be read aloud is a small loss, and it must not
 * take the interview down with it — the text is on screen either way.
 *
 * @returns {Promise<void>}
 */
export async function speak(text, { lang = 'en-US', rate = 1.02, signal } = {}) {
  if (!ttsSupported() || !String(text || '').trim()) return;
  const synth = window.speechSynthesis;
  cancelSpeech();

  const voices = await voicesReady();
  if (signal && signal.aborted) return;

  await new Promise((resolve) => {
    let settled = false;
    const finish = () => { if (!settled) { settled = true; resolve(); } };

    const u = new SpeechSynthesisUtterance(String(text));
    u.lang = lang;
    u.rate = rate;
    const v = pickVoice(voices, lang);
    if (v) u.voice = v;
    u.onend = finish;
    u.onerror = finish;

    if (signal) signal.addEventListener('abort', () => { cancelSpeech(); finish(); }, { once: true });

    // Chrome drops utterances that outlast an internal ~15s watchdog and simply never
    // fires onend. Cap the wait so hands-free mode cannot deadlock on a long question.
    const words = String(text).trim().split(/\s+/).length;
    setTimeout(finish, Math.min(30000, 2000 + (words / 2.6) * 1000));

    synth.speak(u);
  });
}

export function cancelSpeech() {
  if (!ttsSupported()) return;
  try { window.speechSynthesis.cancel(); } catch { /* nothing queued */ }
}
