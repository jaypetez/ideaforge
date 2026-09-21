// The service worker exists for two reasons: it makes the app installable, and it means a
// half-finished interview still opens on a train with no signal. The interview then runs
// from the static question bank — which is exactly the degraded path runTurn already has.
//
// Strategy is deliberately network-first for the shell. Cache-first would be faster, but
// this app has no build step and no content hashing, so a stale cache-first shell is a
// deploy that never reaches anyone. Stale-while-revalidate is the compromise: serve the
// cache instantly, fetch in the background, use it next time.

// Bumped whenever the shell's modules change shape together. Stale-while-revalidate
// caches per request, so without a rename a returning user can load a NEW app.js against
// a CACHED old secrets.js that knows nothing about the keyring — a mixed module graph
// that fails in ways neither version would on its own. Renaming makes `activate` drop the
// whole old cache at once.
const CACHE = 'ideaforge-v6';
const GUIDE_PATH = new URL('./guide/', self.location.href).pathname;
const GUIDE_SCREENSHOTS_PATH = new URL('./docs/screenshots/', self.location.href).pathname;

const SHELL = [
  './',
  './index.html',
  './manifest.webmanifest',
  './src/version.js',
  './src/ui/app.css',
  './src/ui/app.js',
  './src/ui/install.js',
  './src/ui/library.js',
  './src/ui/share.js',
  './src/ui/icon.svg',
  './src/ui/icon-maskable.svg',
  './src/ui/icon-192.png',
  './src/ui/icon-512.png',
  './src/ui/icon-maskable-512.png',
  './src/ui/apple-touch-icon.png',
  './src/core/backup.js',
  './src/core/dimensions.js',
  './src/core/driving.js',
  './src/core/library.js',
  './src/core/session.js',
  './src/core/engine.js',
  './src/core/digest.js',
  './src/core/markdown.js',
  './src/core/synthesis.js',
  './src/runtime/turn.js',
  './src/runtime/drive.js',
  './src/runtime/gain.js',
  './src/runtime/synthesize.js',
  './src/providers/index.js',
  './src/providers/http.js',
  './src/providers/errors.js',
  './src/providers/json.js',
  './src/providers/anthropic.js',
  './src/providers/openaiCompat.js',
  './src/providers/artifact.js',
  './src/store/db.js',
  './src/store/prefs.js',
  './src/store/secrets.js',
  './src/store/sessions.js',
  './src/voice/index.js',
  './src/voice/vad.js',
  './src/voice/recorder.js',
  './src/voice/webspeech.js',
  './src/voice/transcribe.js',
  './src/voice/speak.js',
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE)
      // addAll is all-or-nothing, and one 404 would leave the app with no cache at all.
      .then((c) => Promise.all(SHELL.map((url) => c.add(url).catch(() => null))))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (event) => {
  const { request } = event;
  const url = new URL(request.url);
  // Only ever touch our own GETs. Provider calls carry the user's API key and must not
  // pass through a cache, an inspectable one least of all.
  if (request.method !== 'GET') return;
  if (url.origin !== self.location.origin) return;
  // The public guide is a separate static surface under this worker's root scope. Let the
  // browser fetch it normally so a failed guide navigation can never turn into the app shell.
  if (url.pathname.startsWith(GUIDE_PATH)
      || url.pathname.startsWith(GUIDE_SCREENSHOTS_PATH)) return;

  event.respondWith((async () => {
    const cached = await caches.match(request);
    const network = fetch(request).then((res) => {
      if (res && res.ok) caches.open(CACHE).then((c) => c.put(request, res.clone()));
      return res;
    }).catch(() => null);

    // Stale-while-revalidate, with a navigation falling back to the cached shell so a
    // cold offline launch still opens the app rather than the browser's error page.
    return cached
      || (await network)
      || (request.mode === 'navigate' ? caches.match('./index.html') : undefined)
      || new Response('offline', { status: 503, statusText: 'offline' });
  })());
});
