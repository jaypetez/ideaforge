// A very small IndexedDB wrapper. Two stores: sessions, and the credential blob.
//
// IndexedDB rather than localStorage for one specific reason — it can hold a
// non-extractable CryptoKey *object*, which is what makes the key storage in secrets.js
// meaningfully better than writing the API key to disk in the clear.
//
// Everything here returns a promise and swallows nothing: a quota error or a private
// window with storage disabled must reach the UI, because the honest answer is "your
// session was not saved", not a silent loss.

const DB_NAME = 'ideaforge';
const DB_VERSION = 1;
export const SESSIONS = 'sessions';
export const SECRETS = 'secrets';

let dbPromise = null;

export function openDb() {
  if (dbPromise) return dbPromise;
  const promise = new Promise((resolve, reject) => {
    let settled = false;
    const req = indexedDB.open(DB_NAME, DB_VERSION);
    const fail = (err) => {
      if (settled) return;
      settled = true;
      if (dbPromise === promise) dbPromise = null;
      reject(err);
    };
    req.onupgradeneeded = () => {
      const db = req.result;
      if (!db.objectStoreNames.contains(SESSIONS)) db.createObjectStore(SESSIONS, { keyPath: 'id' });
      if (!db.objectStoreNames.contains(SECRETS)) db.createObjectStore(SECRETS);
    };
    req.onsuccess = () => {
      const db = req.result;
      // An upgrader elsewhere is waiting for this connection to go away. Close it, and
      // forget the memoized handle too: returning a dead connection on the next call is
      // worse than re-opening.
      db.onversionchange = () => {
        db.close();
        if (dbPromise === promise) dbPromise = null;
      };
      if (settled) { db.close(); return; }
      settled = true;
      resolve(db);
    };
    req.onerror = () => fail(req.error);
    req.onblocked = () => fail(new Error('another tab is holding an older version of the database'));
  });
  dbPromise = promise;
  return promise;
}

async function tx(store, mode, run) {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const t = db.transaction(store, mode);
    const req = run(t.objectStore(store));
    t.onabort = () => reject(t.error || new Error('transaction aborted'));
    t.onerror = () => reject(t.error);
    t.oncomplete = () => resolve(req ? req.result : undefined);
  });
}

export const put = (store, value, key) => tx(store, 'readwrite', (s) => s.put(value, key));
export const get = (store, key) => tx(store, 'readonly', (s) => s.get(key));
export const del = (store, key) => tx(store, 'readwrite', (s) => s.delete(key));
export const all = (store) => tx(store, 'readonly', (s) => s.getAll());

/**
 * Ask for storage that survives eviction. WebKit weighs "is this installed to the home
 * screen" heavily when deciding, so this is worth calling even though it often says no,
 * and worth calling again after the user installs.
 */
export async function requestPersistence() {
  if (!navigator.storage || !navigator.storage.persist) return false;
  try {
    if (await navigator.storage.persisted()) return true;
    return await navigator.storage.persist();
  } catch {
    return false;
  }
}
