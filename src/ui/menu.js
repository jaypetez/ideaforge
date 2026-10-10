// The overflow menu, shared by the library cards and the Done screen.
//
// A native popover="auto": Esc and light dismiss close it, and focus goes back to the
// trigger, with no script of ours to get wrong. What the platform does not do is place it,
// keep it placed, or put focus inside it, and those are what this module adds.

/**
 * Wire `menu` (an element with popover="auto") to open under `trigger`.
 *
 * Returns `close`, which hides the menu synchronously if it is open. Every action in a
 * menu calls it first, before doing anything else, and before any await: it hands focus
 * back to the trigger before the screen can re-render, and Share must still reach
 * navigator.share inside the click gesture.
 */
export function wireOverflowMenu(menu, trigger, { windowRef = window } = {}) {
  trigger.setAttribute('popovertarget', menu.id);
  const close = () => { if (menu.matches(':popover-open')) menu.hidePopover(); };

  // Positioned against its trigger by hand rather than with CSS anchor positioning, which
  // Safari and Firefox do not all have. Absolute in the top layer means document
  // coordinates, so the menu scrolls with its trigger instead of floating over something else.
  menu.addEventListener('beforetoggle', (event) => {
    if (event.newState !== 'open') return;
    const rect = trigger.getBoundingClientRect();
    const root = windowRef.document.documentElement;
    menu.style.top = `${rect.bottom + windowRef.scrollY + 4}px`;
    menu.style.right = `${Math.max(8, root.clientWidth - rect.right)}px`;
  });
  // The position is measured once, so a resize or rotation would leave the menu pointing
  // at nothing. Close it instead; it reopens in the right place from its trigger.
  const closeOnResize = () => close();
  menu.addEventListener('toggle', (event) => {
    if (event.newState !== 'open') {
      windowRef.removeEventListener('resize', closeOnResize);
      return;
    }
    windowRef.addEventListener('resize', closeOnResize);
    // Flip above the trigger when the menu would run off the bottom of the viewport.
    const rect = trigger.getBoundingClientRect();
    const height = menu.getBoundingClientRect().height;
    if (rect.bottom + 4 + height > windowRef.innerHeight && rect.top - 4 - height > 0) {
      menu.style.top = `${rect.top + windowRef.scrollY - 4 - height}px`;
    }
    // Focus inside the menu is also what makes the browser return it to the trigger on
    // Esc: popover focus restoration only applies when focus was in the popover.
    const first = [...menu.querySelectorAll('button')]
      .find((button) => !button.disabled && !button.hidden);
    if (first) first.focus();
  });
  return close;
}
