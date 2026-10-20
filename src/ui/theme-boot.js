// Applies a stored light or dark choice before first paint (#112).
//
// A CLASSIC script, loaded with a plain <script src> in index.html's <head>, after the
// theme-color metas and before the stylesheets. Not a module: modules are deferred, so the
// page would paint once in the system theme and then flash to the chosen one. Not inline:
// the CSP has no 'unsafe-inline', and a same-origin file is already allowed by 'self'.
//
// Three rules, each load-bearing:
//
//   Only an explicit 'light' or 'dark' writes data-theme. 'system', nothing stored, garbage
//     and a storage error all leave it alone, so prefers-color-scheme decides.
//   It never REMOVES data-theme. tools/screenshots.mjs sets documentElement.dataset.theme
//     before any page script runs, in a fresh profile with nothing stored, to produce the
//     README's light and dark pairs. Clearing the attribute for "system" here would make
//     both runs render the OS theme: identical pairs and a green suite (CLAUDE.md, "The look").
//   Every storage touch is wrapped: a private window throws on the first one, and a theme
//     must never be the reason the page does not load.
//
// The rule is themePreference in src/store/prefs.js and the colours are THEME_COLOURS in
// src/ui/theme.js; a classic script cannot import either, so test/prefs.test.mjs runs this
// file against both.
(function () {
  if (typeof document === 'undefined') return;
  // The optional CDN stylesheet must not hold deferred modules or the window load event.
  document.addEventListener('load', function fontsLoaded(event) {
    var link = event.target;
    if (link.id !== 'font-styles') return;
    document.removeEventListener('load', fontsLoaded, true);
    link.rel = 'stylesheet';
  }, true);
  var theme;
  try {
    var raw = localStorage.getItem('ideaforge.prefs');
    theme = raw ? JSON.parse(raw).theme : '';
  } catch (e) { /* A blocked store must not prevent a harness-set theme's chrome matching. */ }
  if (theme === 'light' || theme === 'dark') document.documentElement.dataset.theme = theme;
  else theme = document.documentElement.dataset.theme;
  if (theme !== 'light' && theme !== 'dark') return;
  // The browser chrome on an installed phone follows these, not data-theme; without this a
  // dark choice on a light phone paints light bars around the dark page.
  var colour = theme === 'dark' ? '#11161b' : '#f5f7fa';
  var metas = document.querySelectorAll('meta[name="theme-color"]');
  for (var i = 0; i < metas.length; i++) metas[i].setAttribute('content', colour);
})();
