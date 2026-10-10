// The theme choice in Settings (#112). theme-boot.js applies a stored choice before first
// paint; this applies a choice made on the page, and only then.
//
// app.js never calls applyTheme at boot. In a fresh profile the stored choice is 'system',
// and applying 'system' removes data-theme — which would undo what tools/screenshots.mjs set
// to capture the dark README pair (CLAUDE.md, "[data-theme] is load-bearing").

import { themePreference } from '../store/prefs.js';

/** The --surface-container-low ground in each theme, matching index.html's theme-color metas. */
export const THEME_COLOURS = { light: '#f5f6f2', dark: '#15191a' };

/**
 * Set or clear data-theme and point the browser-chrome colour at the same theme.
 * @param {Document} doc
 * @param {unknown} choice  anything; it is validated here
 * @returns {'system'|'light'|'dark'} what was applied
 */
export function applyTheme(doc, choice) {
  const theme = themePreference(choice);
  const root = doc.documentElement;
  if (theme === 'system') delete root.dataset.theme;
  else root.dataset.theme = theme;
  for (const meta of doc.querySelectorAll('meta[name="theme-color"]')) {
    // For System each meta goes back to the colour its own media query names.
    const own = /dark/.test(meta.getAttribute('media') || '') ? 'dark' : 'light';
    meta.setAttribute('content', THEME_COLOURS[theme === 'system' ? own : theme]);
  }
  return theme;
}
