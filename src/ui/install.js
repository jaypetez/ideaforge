// Installation is browser UI, not an interview concern. Chromium exposes a deferred prompt;
// iOS deliberately does not, so the useful fallback there is a short, exact instruction.

export function mobilePlatform(nav = navigator) {
  const ua = String(nav && nav.userAgent || '');
  const platform = String(nav && nav.platform || '');
  const touchMac = platform === 'MacIntel' && Number(nav && nav.maxTouchPoints) > 1;
  if (/iPhone|iPad|iPod/i.test(ua) || touchMac) return 'ios';
  if (/Android/i.test(ua)) return 'android';
  return 'other';
}

export function standaloneMode(win = window, nav = navigator) {
  return Boolean((win.matchMedia && win.matchMedia('(display-mode: standalone)').matches)
    || (nav && nav.standalone));
}

export function createInstallController({
  win = window,
  nav = navigator,
  onChange = () => {},
} = {}) {
  let deferred = null;
  let installed = standaloneMode(win, nav);
  const platform = mobilePlatform(nav);

  const snapshot = () => ({
    platform,
    installed,
    canPrompt: Boolean(deferred),
  });

  const changed = () => onChange(snapshot());
  const onPrompt = (event) => {
    event.preventDefault();
    deferred = event;
    changed();
  };
  const onInstalled = () => {
    deferred = null;
    installed = true;
    changed();
  };

  win.addEventListener('beforeinstallprompt', onPrompt);
  win.addEventListener('appinstalled', onInstalled);

  return {
    state: snapshot,
    async install() {
      if (!deferred) return { outcome: 'unavailable' };
      const prompt = deferred;
      deferred = null;
      await prompt.prompt();
      const choice = await prompt.userChoice;
      if (choice && choice.outcome === 'accepted') installed = true;
      changed();
      return choice || { outcome: 'dismissed' };
    },
    dispose() {
      win.removeEventListener('beforeinstallprompt', onPrompt);
      win.removeEventListener('appinstalled', onInstalled);
    },
  };
}
