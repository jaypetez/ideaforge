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

function checkPhoneOpening(check, doc, label) {
  const viewport = doc.documentElement;
  const visible = (bounds) => bounds.width > 0 && bounds.height > 0
    && bounds.top >= 0 && bounds.bottom <= viewport.clientHeight
    && bounds.left >= 0 && bounds.right <= viewport.clientWidth;
  const heading = doc.querySelector('main h1').getBoundingClientRect();
  const intro = doc.querySelector('main .lede').getBoundingClientRect();
  const actions = doc.querySelector('main .hero-actions');
  check(`${label}: the heading is fully visible on initial 390x844 load`,
    doc.defaultView.scrollY === 0 && visible(heading),
    `heading ${heading.top}-${heading.bottom}; viewport ${viewport.clientHeight}`);
  check(`${label}: the introduction is fully visible before scrolling or closing navigation`,
    visible(intro), `introduction ${intro.top}-${intro.bottom}; viewport ${viewport.clientHeight}`);
  if (actions) {
    const bounds = actions.getBoundingClientRect();
    check(`${label}: the main actions are visible on initial phone load`,
      visible(bounds), `actions ${bounds.top}-${bounds.bottom}; viewport ${viewport.clientHeight}`);
  }
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
  const desktopList = guideDoc.querySelector('.docs-nav ul');
  check('desktop navigation keeps the full list without the mobile scroll cue',
    desktopList.scrollHeight === desktopList.clientHeight
      && !guideDoc.querySelector('.nav-hint').getClientRects().length);

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

  for (const page of GUIDE_PAGES) {
    const narrow = await loadFrame(`/guide/${page}`, { width: 390, height: 844 });
    try {
      const doc = narrow.contentDocument;
      checkPhoneOpening(check, doc, page);
      narrow.width = 320;
      await settle(100);
      check(`${page} has no page overflow at 320 CSS pixels`,
        doc.documentElement.scrollWidth <= doc.documentElement.clientWidth,
        `${doc.documentElement.scrollWidth}/${doc.documentElement.clientWidth}`);
      check(`${page} keeps local styling and no guide scripts`,
        doc.scripts.length === 0
          && [...doc.styleSheets].some((sheet) =>
            sheet.href && new URL(sheet.href).pathname === '/guide/assets/guide.css'));
      check(`${page} has usable narrow navigation targets`,
        [...doc.querySelectorAll('.docs-nav a')].every((link) =>
          link.getBoundingClientRect().height >= 44));
    } finally {
      narrow.remove();
    }
  }

  for (const base of ['/', subpath]) {
    const voice = await loadFrame(`${base}guide/mobile-and-voice.html`, { width: 390, height: 844 });
    try {
      const doc = voice.contentDocument;
      checkPhoneOpening(check, doc, `${base}mobile-and-voice.html`);
      for (const stem of ['08-voice-listening', '09-voice-paused']) {
        const image = doc.querySelector(`img[src$="${stem}.light.png"]`);
        let error = '';
        if (image) {
          image.loading = 'eager';
          try { await image.decode(); } catch (err) { error = err.message; }
        }
        check(`${base}guide renders the ${stem} capture`,
          !!image && !error && image.naturalWidth > 0
            && new URL(image.currentSrc).pathname.startsWith(`${base}docs/screenshots/`),
          error || image?.currentSrc || 'image missing');
        if (image?.naturalWidth) {
          const bounds = image.getBoundingClientRect();
          check(`${stem} keeps its aspect ratio at phone width under ${base}`,
            bounds.width <= doc.documentElement.clientWidth
              && Math.abs(bounds.width / bounds.height - image.naturalWidth / image.naturalHeight) < .02);
        }
      }
      const list = doc.querySelector('.docs-nav ul');
      list.focus();
      check(`${base}phone navigation's scroll region is keyboard focusable`,
        doc.activeElement === list && list.scrollHeight > list.clientHeight);
      const last = list.querySelector('li:last-child a');
      last.focus();
      await settle(100);
      const listBounds = list.getBoundingClientRect();
      const lastBounds = last.getBoundingClientRect();
      check(`${base}focusing the last navigation link scrolls it fully into view`,
        doc.activeElement === last && list.scrollTop > 0
          && lastBounds.top >= listBounds.top && lastBounds.bottom <= listBounds.bottom
          && lastBounds.top >= 0 && lastBounds.bottom <= doc.documentElement.clientHeight,
        `link ${lastBounds.top}-${lastBounds.bottom}; list ${listBounds.top}-${listBounds.bottom}`);
    } finally {
      voice.remove();
    }
  }

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

  const contents = guideDoc.querySelector('.docs-nav details');
  const summary = contents.querySelector('summary');
  summary.click();
  check('the narrow guide navigation collapses without page JavaScript',
    !contents.open && !contents.querySelector('ul').getClientRects().length
      && !contents.querySelector('.nav-hint').getClientRects().length);
  guide.width = 900;
  await settle(100);
  check('collapsed navigation remains operable after widening the page',
    summary.getBoundingClientRect().height > 0);
  summary.click();
  check('the guide navigation can be reopened without page JavaScript',
    contents.open && contents.querySelector('ul').getClientRects().length > 0);

  const skip = guideDoc.querySelector('.skip-link');
  skip.focus();
  check('the focused skip link is visible above the header',
    skip.getBoundingClientRect().top >= 0
      && Number(guide.contentWindow.getComputedStyle(skip).zIndex)
        > Number(guide.contentWindow.getComputedStyle(guideDoc.querySelector('.site-header')).zIndex));

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
