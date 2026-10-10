// The theme choice (#112): System / Light / Dark in Settings, stored in prefs and applied
// before first paint by src/ui/theme-boot.js.
//
// The claim that matters most is the one that would fail silently: with nothing stored, the
// page must never touch data-theme, because tools/screenshots.mjs sets it in a fresh profile
// to shoot the README's light and dark pairs. A theme-boot that cleared it for "system", or
// an app that applied the default at boot, would make both runs photograph the OS theme —
// the same image twice, with every other check green (CLAUDE.md, "The look").

const BOOT_MS = 4000;
const KEY = 'ideaforge.prefs';
const settle = (ms = 250) => new Promise((resolve) => setTimeout(resolve, ms));

async function until(predicate, ms = 4000) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    if (predicate()) return true;
    await settle(40);
  }
  return predicate();
}

/**
 * Load the app in an iframe, calling `early(doc)` at the first moment the probe can see the
 * new document — usually mid-<head>, before theme-boot.js has run, which is as close as a
 * probe gets to the harness's addScriptToEvaluateOnNewDocument — and `withBody(doc)` at the
 * first moment it has a <body>, the earliest anything could be painted.
 */
function loadFrame(src, early = () => {}, withBody = () => {}) {
  return new Promise((resolve, reject) => {
    const frame = document.createElement('iframe');
    frame.width = 900;
    frame.height = 800;
    frame.style.position = 'absolute';
    frame.style.left = '-10000px';
    let seen = false;
    let bodied = false;
    const watch = () => {
      const doc = frame.contentDocument;
      if (doc && doc.documentElement && /index\.html$/.test(doc.URL)) {
        if (!seen) { seen = true; early(doc); }
        if (!bodied && doc.body) { bodied = true; withBody(doc); }
      }
      if (!bodied) setTimeout(watch, 0);
    };
    frame.onload = () => { if (!bodied) watch(); resolve(frame); };
    frame.onerror = () => reject(new Error(`iframe failed to load ${src}`));
    frame.src = src;
    document.body.append(frame);
    watch();
  });
}

const store = (prefs) => {
  if (prefs === null) localStorage.removeItem(KEY);
  else localStorage.setItem(KEY, typeof prefs === 'string' ? prefs : JSON.stringify(prefs));
};
const storedTheme = () => {
  try { return JSON.parse(localStorage.getItem(KEY) || '{}').theme; } catch { return undefined; }
};

export default async function run(check) {
  const original = localStorage.getItem(KEY);
  try {
    await harnessUntouched(check);
    await chooseInSettings(check);
    await reloadsDark(check);
    await garbageIsSystem(check);
  } finally {
    if (original === null) localStorage.removeItem(KEY);
    else localStorage.setItem(KEY, original);
  }
}

/** What tools/screenshots.mjs does: set dataset.theme first, in a profile with nothing stored. */
async function harnessUntouched(check) {
  for (const preset of ['dark', 'light']) {
    store(null);
    const frame = await loadFrame('/index.html', (doc) => { doc.documentElement.dataset.theme = preset; });
    const doc = frame.contentDocument;
    try {
      await settle(BOOT_MS);
      check(`with nothing stored, the app leaves a harness-set ${preset} theme alone through boot`,
        doc.documentElement.dataset.theme === preset, String(doc.documentElement.dataset.theme));

      // And theme-boot itself, run again now the attribute is certainly set first.
      const other = preset === 'dark' ? 'light' : 'dark';
      doc.documentElement.dataset.theme = other;
      const script = doc.createElement('script');
      script.src = './src/ui/theme-boot.js?again';
      await new Promise((resolve, reject) => {
        script.onload = resolve;
        script.onerror = () => reject(new Error('theme-boot.js did not load'));
        doc.head.append(script);
      });
      check(`…and theme-boot.js, run with nothing stored, never overrides it (${other})`,
        doc.documentElement.dataset.theme === other, String(doc.documentElement.dataset.theme));
      check('…nor writes a preference of its own', localStorage.getItem(KEY) === null,
        String(localStorage.getItem(KEY)));
    } finally {
      frame.remove();
    }
  }
}

async function chooseInSettings(check) {
  store(null);
  const frame = await loadFrame('/index.html');
  const win = frame.contentWindow;
  const doc = frame.contentDocument;
  try {
    await settle(BOOT_MS);
    const $ = (id) => doc.getElementById(id);
    const radio = (value) => doc.querySelector(`#theme-choice input[name="theme"][value="${value}"]`);
    const root = doc.documentElement;
    const scheme = () => win.getComputedStyle(root).colorScheme;
    const ground = () => win.getComputedStyle(doc.body).backgroundColor;
    const metaColours = () => [...doc.querySelectorAll('meta[name="theme-color"]')]
      .map((m) => m.getAttribute('content')).join();

    check('a fresh profile has no data-theme, so the OS preference decides',
      !('theme' in root.dataset), String(root.dataset.theme));
    check('…and Settings shows System chosen', radio('system') && radio('system').checked);

    $('b-settings').click();
    await until(() => $('panel-setup').dataset.view === 'settings');
    const index = [...$('settings-index').querySelectorAll('button')].map((b) => b.dataset.target);
    check('Settings has an Appearance group, listed in its index',
      $('settings-appearance').getClientRects().length > 0 && index.includes('settings-appearance'),
      index.join());
    check('…with System, Light and Dark as one radio group under a legend',
      [...doc.querySelectorAll('#theme-choice input[name="theme"]')].map((r) => `${r.type}:${r.value}`).join()
        === 'radio:system,radio:light,radio:dark'
        && $('theme-choice').querySelector('legend').textContent.trim() === 'Theme');

    radio('light').click();
    const lightGround = ground();
    check('choosing Light applies it at once', root.dataset.theme === 'light' && scheme() === 'light',
      `${root.dataset.theme} / ${scheme()}`);

    radio('dark').click();
    check('choosing Dark applies it at once', root.dataset.theme === 'dark', String(root.dataset.theme));
    check('…sets color-scheme to dark, so native controls follow', scheme() === 'dark', scheme());
    check('…changes what the page paints', ground() !== lightGround, `${lightGround} vs ${ground()}`);
    check('…stores it', storedTheme() === 'dark', String(storedTheme()));
    check('…and retints the browser chrome', metaColours() === '#15191a,#15191a', metaColours());

    radio('system').click();
    check('choosing System removes data-theme', !('theme' in root.dataset), String(root.dataset.theme));
    check('…leaves color-scheme following the OS', scheme() === 'dark light', scheme());
    check('…stores system', storedTheme() === 'system', String(storedTheme()));
    check('…and gives each theme-color meta its own colour back', metaColours() === '#f5f6f2,#15191a',
      metaColours());
  } finally {
    frame.remove();
  }
}

async function reloadsDark(check) {
  store({ theme: 'dark' });
  let early = null;
  const frame = await loadFrame('/index.html', () => {}, (doc) => {
    early = { theme: doc.documentElement.dataset.theme, state: doc.readyState };
  });
  const win = frame.contentWindow;
  const doc = frame.contentDocument;
  try {
    // theme-boot.js is a blocking classic script in <head>, so by the time any of the body
    // exists the attribute must already be there. A module or a boot-time apply would set it
    // after parsing, and a body-bearing early look would catch the light frame in between.
    check('a stored Dark is already applied the moment the page has a body to paint',
      Boolean(early) && early.theme === 'dark',
      JSON.stringify(early));
    await settle(BOOT_MS);
    check('…persists across a reload', doc.documentElement.dataset.theme === 'dark',
      String(doc.documentElement.dataset.theme));
    check('…with color-scheme dark', win.getComputedStyle(doc.documentElement).colorScheme === 'dark',
      win.getComputedStyle(doc.documentElement).colorScheme);
    const radio = doc.querySelector('#theme-choice input[value="dark"]');
    check('…and Settings shows Dark chosen', Boolean(radio && radio.checked));
  } finally {
    frame.remove();
  }
}

async function garbageIsSystem(check) {
  for (const stored of [{ theme: 'garbage' }, 'not json']) {
    store(stored);
    const frame = await loadFrame('/index.html');
    const doc = frame.contentDocument;
    try {
      await settle(BOOT_MS);
      const radio = doc.querySelector('#theme-choice input[value="system"]');
      check(`a stored ${JSON.stringify(stored)} falls back to System`,
        !('theme' in doc.documentElement.dataset) && Boolean(radio && radio.checked),
        `${doc.documentElement.dataset.theme} / system checked: ${radio && radio.checked}`);
    } finally {
      frame.remove();
    }
  }
}
