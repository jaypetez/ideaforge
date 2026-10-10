// The result screen (#106): one primary action, everything else in an overflow menu.
//
// The primary is Share .md where the share sheet takes a markdown file and Copy markdown
// everywhere else, so this probe drives the same finished idea through three browsers —
// file sharing, text-only sharing, and no share sheet at all — by redefining navigator.share
// and navigator.canShare between visits. Esc closing the menu is browser behaviour that a
// synthetic KeyboardEvent never triggers; app-audio-policy.probe.mjs checks it with trusted
// keyboard input.
import { createSession, setSynthesis, setTitle } from '../../src/core/session.js';
import { seedTurn, submitAnswer } from '../../src/runtime/turn.js';
import { deleteSession, saveSession } from '../../src/store/sessions.js';

const ID = 's_done_screen';
const settle = (ms = 150) => new Promise((resolve) => setTimeout(resolve, ms));

function loadFrame(src) {
  return new Promise((resolve, reject) => {
    const frame = document.createElement('iframe');
    frame.width = 900;
    frame.height = 800;
    frame.style.position = 'absolute';
    frame.style.left = '-10000px';
    frame.onload = () => resolve(frame);
    frame.onerror = () => reject(new Error(`iframe failed to load ${src}`));
    frame.src = src;
    document.body.append(frame);
  });
}

function finished(now) {
  let session = seedTurn(createSession({ id: ID, now }), { now });
  session = submitAnswer(session, { text: 'A helper that remembers names at conferences', now: now + 1 });
  session = setSynthesis(session, {
    text: '## Task\nBuild a helper that remembers names at conferences.',
    assumptions: ['It runs on a phone'], openQuestions: ['Which badge formats?'], now: now + 2,
  });
  return setTitle(session, 'Conference name helper', now + 3);
}

export default async function run(check) {
  await saveSession(finished(Date.now()));
  const frame = await loadFrame('/index.html');
  const win = frame.contentWindow;
  const doc = frame.contentDocument;
  const $ = (id) => doc.getElementById(id);
  const shown = (el) => !!el && el.getClientRects().length > 0;
  const labels = (root) => [...root.querySelectorAll('button')].filter(shown)
    .map((button) => button.textContent);
  const errors = [];
  win.addEventListener('error', (event) => errors.push(event.message));
  win.addEventListener('unhandledrejection', (event) => errors.push(String(event.reason)));
  try {
    for (let waited = 0; waited < 5000 && !$('version').textContent; waited += 50) await settle(50);

    /** The browser this visit pretends to be, then the idea's result, opened from its card. */
    async function visit(share, canShare) {
      for (const [name, value] of [['share', share], ['canShare', canShare]]) {
        Object.defineProperty(win.navigator, name, { configurable: true, value });
      }
      $('b-library').click();
      await settle(300);
      const card = $('library-rows').querySelector(`[data-session-id="${ID}"]`);
      card.querySelector('.idea-actions > button:not(.idea-more)').click();
      for (let waited = 0; waited < 3000 && !shown($('panel-done')); waited += 50) await settle(50);
      return shown($('panel-done')) && !!$('output').dataset.source;
    }
    const primary = () => $('done-actions').querySelector('button:not(.done-more)');
    const clipboard = [];
    Object.defineProperty(win.navigator, 'clipboard', {
      configurable: true, value: { writeText: async (text) => { clipboard.push(text); } },
    });

    // ── A browser whose share sheet takes a markdown file.
    const shared = [];
    let inClick = false;
    const during = [];
    const fileShare = async (payload) => { shared.push(payload); during.push(inClick); };
    check('a finished idea opens on Done', await visit(fileShare,
      ({ files }) => files && files[0] && files[0].type === 'text/markdown'));
    const source = $('output').dataset.source;
    check('where files can be shared, Share .md is the one primary action',
      primary() === $('b-share') && primary().textContent === 'Share .md'
        && !primary().classList.contains('quiet')
        && labels($('done-actions')).length === 2 && shown($('b-done-more')),
      JSON.stringify(labels($('done-actions'))));
    check('…and Copy, Download, Ask me more and New idea wait in the closed menu',
      ['b-copy', 'b-download', 'b-reopen', 'b-new']
        .every((id) => $('done-menu').contains($(id)) && !shown($(id))));
    const cap = parseFloat(win.getComputedStyle($('output')).maxHeight);
    check('#output keeps its 60dvh height cap', Math.abs(cap - win.innerHeight * 0.6) < 1,
      `${cap}px of ${win.innerHeight}px`);

    // Mobile share sheets need the click's user activation, so navigator.share has to be called
    // before the handler yields. click() dispatches synchronously: a call recorded while this
    // flag is still set happened inside the click, with no await in front of it.
    inClick = true;
    $('b-share').click();
    inClick = false;
    await settle(150);
    check('Share .md calls navigator.share synchronously inside the click',
      during.length === 1 && during[0] === true, JSON.stringify(during));
    check('…with exactly the export, as a markdown file',
      shared.length === 1 && shared[0].files[0].name.endsWith('.md')
        && (await shared[0].files[0].text()) === source);

    // The overflow menu: focus in when it opens, back to the trigger when it closes.
    $('b-done-more').focus();
    $('b-done-more').click();
    await settle(100);
    check('the overflow trigger opens the menu and moves focus into it',
      $('done-menu').matches(':popover-open') && $('done-menu').contains(doc.activeElement),
      doc.activeElement && doc.activeElement.textContent);
    check('the open menu holds Copy, Download, Ask me more and New idea by their exact labels',
      JSON.stringify(labels($('done-menu')))
        === JSON.stringify(['Copy markdown', 'Download .md', 'Ask me more', 'New idea']),
      JSON.stringify(labels($('done-menu'))));
    $('done-menu').hidePopover();
    await settle(50);
    check('closing the menu returns focus to its trigger', doc.activeElement === $('b-done-more'),
      doc.activeElement && (doc.activeElement.id || doc.activeElement.tagName));

    $('b-done-more').click();
    await settle(100);
    $('b-copy').click();
    await settle(100);
    check('Copy markdown from the menu closes it and copies exactly the export',
      !$('done-menu').matches(':popover-open') && clipboard.length === 1 && clipboard[0] === source,
      `${clipboard.length} writes`);

    // ── A phone. No sideways scroll, and the open menu stays on screen.
    frame.width = 390;
    await settle(150);
    const width = doc.documentElement.clientWidth;
    check('Done has no horizontal overflow at 390px',
      doc.documentElement.scrollWidth <= width, `${doc.documentElement.scrollWidth}/${width}`);
    check('…and both of its actions fit inside it',
      [primary(), $('b-done-more')].every((el) => shown(el) && el.getBoundingClientRect().right <= width),
      [primary(), $('b-done-more')].map((el) => Math.round(el.getBoundingClientRect().right)).join(','));
    $('b-done-more').click();
    await settle(100);
    const menu = $('done-menu').getBoundingClientRect();
    check('the open menu stays inside a phone-width viewport',
      $('done-menu').matches(':popover-open') && menu.left >= 0 && menu.right <= width
        && doc.documentElement.scrollWidth <= width,
      `${Math.round(menu.left)}–${Math.round(menu.right)} of ${width}`);
    frame.width = 900;
    await settle(150);
    check('resizing the window closes the open menu', !$('done-menu').matches(':popover-open'));

    // ── A browser with a share sheet that takes only text.
    check('Done opens again in a text-only share browser', await visit(fileShare, () => false));
    check('where files cannot be shared, Copy markdown is the primary action',
      primary() === $('b-copy') && !primary().classList.contains('quiet')
        && labels($('done-actions')).length === 2,
      JSON.stringify(labels($('done-actions'))));
    $('b-done-more').click();
    await settle(100);
    check('…and Share .md, which can still send text, leads the menu',
      JSON.stringify(labels($('done-menu')))
        === JSON.stringify(['Share .md', 'Download .md', 'Ask me more', 'New idea']),
      JSON.stringify(labels($('done-menu'))));
    $('done-menu').hidePopover();

    // ── A browser with no share sheet at all.
    check('Done opens again in a browser with no share sheet', await visit(undefined, undefined));
    check('with no share sheet, Copy markdown is the primary action',
      primary() === $('b-copy') && primary().textContent === 'Copy markdown',
      JSON.stringify(labels($('done-actions'))));
    $('b-done-more').click();
    await settle(100);
    check('…and Share .md, which could only download, is gone rather than in the menu',
      JSON.stringify(labels($('done-menu')))
        === JSON.stringify(['Download .md', 'Ask me more', 'New idea']),
      JSON.stringify(labels($('done-menu'))));
    $('done-menu').hidePopover();
    clipboard.length = 0;
    $('b-copy').click();
    await settle(100);
    check('the Copy primary puts exactly the export on the clipboard',
      clipboard.length === 1 && clipboard[0] === source,
      JSON.stringify((clipboard[0] || '').slice(0, 60)));

    // New idea from the menu leaves Done for Start, and takes the menu with it.
    $('b-done-more').click();
    await settle(100);
    $('b-new').click();
    await settle(300);
    check('New idea in the menu closes it and opens Start',
      !$('done-menu').matches(':popover-open') && shown($('panel-setup')) && !shown($('panel-done'))
        && $('panel-setup').dataset.view === 'start');
    check('Done: no uncaught error', errors.length === 0, errors.join('\n'));
  } finally {
    frame.remove();
    await deleteSession(ID);
  }
}
