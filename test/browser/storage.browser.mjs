// Storage and crypto. None of this is reachable from Node: IndexedDB does not exist there,
// and the whole point of the key store is a non-extractable CryptoKey, which is a browser
// primitive with no Node equivalent that behaves the same way.

import { saveCredentials, loadCredentials, clearCredentials, maskKey } from '../../src/store/secrets.js';
import { saveSession, loadSession, listSessions, newSessionId } from '../../src/store/sessions.js';
import { createSession } from '../../src/core/session.js';
import { seedTurn, submitAnswer, runTurn } from '../../src/runtime/turn.js';
import { runSynthesis } from '../../src/runtime/synthesize.js';
import { buildExport } from '../../src/core/markdown.js';
import { loadPrefs, savePrefs } from '../../src/store/prefs.js';

const SECRET = 'gsk_this_value_must_never_appear_at_rest';

/** Read a raw record straight out of IndexedDB, behind the store module's back. */
function rawRecord(key) {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open('ideaforge');
    req.onsuccess = () => {
      const tx = req.result.transaction('secrets', 'readonly');
      const get = tx.objectStore('secrets').get(key);
      get.onsuccess = () => resolve(get.result);
      get.onerror = () => reject(get.error);
    };
    req.onerror = () => reject(req.error);
  });
}

export default async function run(check) {
  // ── the key at rest ────────────────────────────────────────────────────────
  await saveCredentials({ kind: 'groq', apiKey: SECRET, sttKind: 'groq', sttKey: SECRET });
  const back = await loadCredentials();
  // A v1 blob on the way in, a keyring on the way out: the only end-to-end proof the
  // migration survives real AES-GCM and real IndexedDB rather than a fake of both.
  check('v1 credentials migrate into a keyring through WebCrypto + IndexedDB',
    back && back.version === 2 && back.byKind.groq.apiKey === SECRET);
  check('…and the dictation key comes across with them',
    back && back.stt && back.stt.apiKey === SECRET);
  check('maskKey never reveals the middle', !maskKey(SECRET).includes('must_never'), maskKey(SECRET));

  const blob = await rawRecord('credentials');
  const atRest = new TextDecoder().decode(new Uint8Array(blob.ciphertext));
  check('the key is ciphertext at rest, never plaintext', !atRest.includes(SECRET));
  check('an IV is stored alongside it', blob.iv && blob.iv.length === 12);

  const wrapping = await rawRecord('wrapping-key');
  check('the wrapping key is a CryptoKey', wrapping && wrapping.type === 'secret');
  // This is the property the whole threat model rests on: XSS can use the key but cannot
  // export it, so it can never leave the origin.
  check('the wrapping key is NON-extractable', wrapping && wrapping.extractable === false);

  // ── sessions survive a reload ──────────────────────────────────────────────
  let asked = 0;
  const questions = [
    'Walk me through the last one you did by hand.',
    'Which part of that would you refuse to automate?',
    'What made the previous attempt unusable?',
  ];
  const provider = {
    async sampleJson(parts) {
      if (parts.system.startsWith('You are the synthesist')) {
        return { modelTierApplied: 'fake', json: {
          title: 'Storage probe', prompt: '## Task\nDo the thing.',
          assumptions: [], open_questions: [],
        } };
      }
      const question = questions[asked % questions.length];
      asked++;
      return { modelTierApplied: 'fake', json: {
        bridge: null, question, move: 'concretize', dimension: 'substance', chips: [],
        new_facts: [{ dimension: 'substance', fact: 'fact ' + asked }],
        coverage: { substance: { level: 'partial', gap: 'g' } },
        suggest_wrap: false, wrap_reason: null,
      } };
    },
  };

  const id = newSessionId();
  let s = submitAnswer(seedTurn(createSession({ id, now: Date.now() })), {
    text: 'a storage probe idea', now: Date.now(),
  });
  await saveSession(s);
  for (let i = 0; i < 2; i++) {
    const out = await runTurn(s, { provider, now: Date.now() });
    s = out.session;
    await saveSession(s);
    if (!out.turn) break;
    s = submitAnswer(s, {
      text: 'a properly substantive answer number ' + i,
      source: i ? 'voice' : 'typed', now: Date.now(),
    });
    await saveSession(s);
  }

  const reloaded = await loadSession(id);
  check('a session survives a write/read cycle',
    reloaded && reloaded.turns.length === s.turns.length, s.turns.length + ' turns');
  check('migrate() rehydrates every dimension',
    reloaded && Object.keys(reloaded.coverage).length === 7);
  check('listSessions returns the session', (await listSessions()).some((r) => r.id === id));

  const syn = await runSynthesis(s, { provider, now: Date.now() });
  check('synthesis writes session.synthesis', syn.ok && !!syn.session.synthesis.text);
  const md = buildExport(syn.session);
  check('the export carries the refined prompt', md.includes('## Refined prompt'));
  check('dictated answers stay marked in the export', md.includes('_(dictated)_'));

  // ── forgetting the key actually forgets it ────────────────────────────────
  await clearCredentials();
  check('loadCredentials is null after clearing', (await loadCredentials()) === null);
  check('the ciphertext record is gone, not merely undecryptable',
    (await rawRecord('credentials')) === undefined);
  check('the wrapping key is gone too', (await rawRecord('wrapping-key')) === undefined);

  // ── device preferences ───────────────────────────────────────────────────
  // Deliberately NOT in the encrypted keyring: "Forget my key" clears that, and silently
  // resetting the driver's trigger word is a bug nobody would connect to the button.

  const beforePrefs = loadPrefs();
  savePrefs({ trigger: 'finished' });
  check('a changed finish word survives a reload', loadPrefs().trigger === 'finished',
    loadPrefs().trigger);
  check('and does not disturb the other preferences',
    loadPrefs().handsFree === beforePrefs.handsFree);

  savePrefs({ handsFree: true });
  check('hands-free is remembered between trips', loadPrefs().handsFree === true);
  check('...without losing the finish word', loadPrefs().trigger === 'finished');

  await clearCredentials();
  check('forgetting the API key leaves the finish word alone',
    loadPrefs().trigger === 'finished', loadPrefs().trigger);

  try { localStorage.removeItem('ideaforge.prefs'); } catch { /* nothing to clean */ }
  check('an unset preference falls back rather than coming back undefined',
    loadPrefs().trigger === '' && loadPrefs().handsFree === false);
}
