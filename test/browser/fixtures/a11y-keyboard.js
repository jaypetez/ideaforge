// Injected before index.html for the real-input accessibility phase (#111), after
// fake-voice.js. Not an ES module, for the same reason fake-voice.js is not: CDP hands the
// bytes to Page.addScriptToEvaluateOnNewDocument, which runs before the app and outside its
// CSP. It scripts the recogniser, the synthesiser and the model, and adds read-only helpers
// that report what has focus and how big each control is. It never moves focus itself:
// every focus change in this phase comes from a real key press sent through CDP.
(() => {
  'use strict';
  window.__FakeVoice.install(window, { script: [] });
  window.claude = {
    async use() {
      return async () => ({ modelTierApplied: 'a11y-fixture', text: JSON.stringify({
        question: 'Who would open this checklist first on a busy morning?',
        move: 'concretize', chips: ['The duty manager', 'Whoever opens up'], facts: [],
        coverage: { outcome: { level: 'partial', gap: 'needs detail' } },
      }) });
    },
  };

  /** Rendered and visible, and not inside anything hidden, inert or aria-hidden. */
  function shown(el) {
    if (!el || el.getClientRects().length === 0) return false;
    if (el.closest('[hidden], [inert], [aria-hidden="true"]')) return false;
    for (let node = el; node && node.nodeType === 1; node = node.parentElement) {
      const style = getComputedStyle(node);
      if (style.visibility === 'hidden' || style.display === 'none' || Number(style.opacity) === 0) {
        return false;
      }
    }
    const rect = el.getBoundingClientRect();
    return rect.width > 0 && rect.height > 0;
  }

  function name(el) {
    const text = (el.getAttribute('aria-label') || el.textContent || el.value || '')
      .replace(/\s+/g, ' ').trim().slice(0, 32);
    return `${el.tagName.toLowerCase()}${el.id ? `#${el.id}` : ''}${text ? ` "${text}"` : ''}`;
  }

  /** The control a pointer actually hits for `el`: a radio or checkbox's whole label. */
  function target(el) {
    if (el.matches('input[type="checkbox"], input[type="radio"]')) return el.closest('label') || el;
    return el;
  }

  window.__a11y = {
    /** What has focus now, measured in the viewport, and which region it belongs to. */
    focus(regions) {
      const el = document.activeElement;
      if (!el || el === document.body || el === document.documentElement) return { end: true };
      const rect = el.getBoundingClientRect();
      const cx = Math.min(Math.max(rect.left + rect.width / 2, 0), innerWidth - 1);
      const cy = Math.min(Math.max(rect.top + rect.height / 2, 0), innerHeight - 1);
      const hit = document.elementFromPoint(cx, cy);
      const owner = target(el);
      const covered = !hit || !(hit === el || el.contains(hit) || owner.contains(hit)
        || (el.labels && [...el.labels].some((label) => label.contains(hit))));
      const region = regions.findIndex((selector) => el.closest(selector));
      return {
        end: false,
        name: name(el),
        shown: shown(el),
        inView: rect.bottom > 0 && rect.top < innerHeight && rect.right > 0 && rect.left < innerWidth,
        covered: covered ? (hit ? name(hit) : 'nothing') : '',
        region,
        box: { top: rect.top + scrollY, left: rect.left + scrollX, width: rect.width, height: rect.height },
      };
    },

    /** Every control a pointer could aim at right now, with its target size. */
    targets() {
      const selector = 'a[href], button, input:not([type="hidden"]), select, textarea, summary, '
        + '[tabindex]:not([tabindex="-1"]), [role="switch"]';
      const seen = new Set();
      const out = [];
      for (const el of document.querySelectorAll(selector)) {
        if (!shown(el) && !el.matches('.skip-link')) continue;
        if (el.matches('.skip-link') && el.getClientRects().length === 0) continue;
        const hit = target(el);
        if (seen.has(hit)) continue;
        seen.add(hit);
        const rect = hit.getBoundingClientRect();
        let exempt = '';
        // WCAG 2.5.8's inline exception: a link inside a sentence is sized by its line.
        if (el.matches('a') && el.closest('p')) exempt = 'inline link in text';
        // The desktop coverage panel's summary is its heading, not a toggle (aria-disabled).
        if (el.getAttribute('aria-disabled') === 'true') exempt = 'not operable (aria-disabled)';
        out.push({
          name: name(el), width: rect.width, height: rect.height, exempt,
          minimumHeight: el.matches('button') && el.closest('.voice-actions, .start-actions') ? 64 : 48,
        });
      }
      return out;
    },
  };
})();
