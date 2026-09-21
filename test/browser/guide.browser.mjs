const BOOT_MS = 4000;
const GUIDE_PAGES = [
  'index.html',
  'getting-started.html',
  'interviews-and-coverage.html',
  'ideas-library.html',
  'mobile-and-voice.html',
  'providers.html',
  'local-models-and-docker.html',
  'privacy-and-security.html',
  'troubleshooting.html',
  'development-and-delivery.html',
];

const settle = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function loadFrame(src, { width = 900, height = 900, onWindow = null } = {}) {
  return new Promise((resolve, reject) => {
    const frame = document.createElement('iframe');
    frame.width = width;
    frame.height = height;
    frame.style.position = 'absolute';
    frame.style.left = '-10000px';
    frame.onload = () => {
      if (frame.contentWindow.location.href === 'about:blank') return;
      resolve(frame);
    };
    frame.onerror = () => reject(new Error(`iframe failed to load ${src}`));
    document.body.append(frame);
    if (onWindow) onWindow(frame.contentWindow);
    frame.src = src;
  });
}

export default async function run(check, { subpath }) {
  // Register and activate the app worker first. The guide lives under that root scope, so
  // this is the only proof that the explicit bypass wins over the app-shell fallback.
  const app = await loadFrame('/index.html');
  await settle(BOOT_MS);
  const registrations = await navigator.serviceWorker.getRegistrations();
  check('the app worker is registered before guide navigation', registrations.length > 0);

  const guideErrors = [];
  const guideViolations = [];
  const guide = await loadFrame('/guide/', {
    onWindow(win) {
      win.addEventListener('error', (event) =>
        guideErrors.push(String(event.message || event)));
      win.addEventListener('securitypolicyviolation', (event) =>
        guideViolations.push(`${event.violatedDirective} blocked ${event.blockedURI}`));
    },
  });
  const guideDoc = guide.contentDocument;
  await settle(500);

  check('the guide loads its own document rather than the app shell',
    guideDoc.title === 'IdeaForge guide'
      && Boolean(guideDoc.querySelector('main#main'))
      && !guideDoc.getElementById('provider'),
    guideDoc.title);
  check('the guide has no uncaught error', guideErrors.length === 0, guideErrors.join('; '));
  check('the guide CSP blocks nothing it needs',
    guideViolations.length === 0, guideViolations.join('; '));

  const navLinks = [...guideDoc.querySelectorAll('.docs-nav a')];
  check('the guide navigation lists every documentation page',
    navLinks.length === GUIDE_PAGES.length, `${navLinks.length} links`);

  const responses = await Promise.all(GUIDE_PAGES.map(async (page) => {
    const response = await fetch(`/guide/${page}`);
    return { page, ok: response.ok, text: await response.text() };
  }));
  check('every guide page ships in the assembled site',
    responses.every((result) => result.ok),
    responses.filter((result) => !result.ok).map((result) => result.page).join(', '));
  check('no guide route resolves to the application document',
    responses.every((result) => /<html lang="en">/.test(result.text)
      && !result.text.includes('id="panel-setup"')));

  const cacheNames = await caches.keys();
  const cachedGuide = [];
  for (const name of cacheNames) {
    const cache = await caches.open(name);
    for (const request of await cache.keys()) {
      const path = new URL(request.url).pathname;
      if (path.includes('/guide/') || path.includes('/docs/screenshots/')) {
        cachedGuide.push(request.url);
      }
    }
  }
  check('the app service worker does not cache guide pages or screenshots',
    cachedGuide.length === 0, cachedGuide.join(', '));

  guide.width = 390;
  await settle(100);
  check('the guide has no horizontal overflow at a phone width',
    guideDoc.documentElement.scrollWidth <= guideDoc.documentElement.clientWidth,
    `${guideDoc.documentElement.scrollWidth}/${guideDoc.documentElement.clientWidth}`);

  const subViolations = [];
  const subGuide = await loadFrame(`${subpath}guide/`, {
    onWindow(win) {
      win.addEventListener('securitypolicyviolation', (event) =>
        subViolations.push(`${event.violatedDirective} blocked ${event.blockedURI}`));
    },
  });
  const subDoc = subGuide.contentDocument;
  await settle(500);
  check('the guide boots on a Pages-style subpath', subDoc.title === 'IdeaForge guide');
  check('the guide stylesheet resolves below the subpath',
    [...subDoc.styleSheets].some((sheet) =>
      new URL(sheet.href).pathname.startsWith(`${subpath}guide/assets/`)));
  check('the guide screenshot resolves below the subpath',
    [...subDoc.images].some((image) =>
      new URL(image.currentSrc || image.src).pathname.startsWith(`${subpath}docs/screenshots/`)));
  check('the subpath guide CSP blocks nothing it needs',
    subViolations.length === 0, subViolations.join('; '));

  const subApp = await loadFrame(`${subpath}index.html`);
  await settle(BOOT_MS);
  check('the app still boots after visiting the guide',
    subApp.contentDocument.getElementById('provider').options.length >= 6);

  for (const frame of [app, guide, subGuide, subApp]) frame.remove();
  for (const registration of await navigator.serviceWorker.getRegistrations()) {
    await registration.unregister();
  }
  for (const name of await caches.keys()) await caches.delete(name);
}
