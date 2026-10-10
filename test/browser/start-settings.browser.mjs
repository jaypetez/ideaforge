// Start and Settings: one #panel-setup, two views (#101).
//
// The view switch is presentation over one set of fields, and the claim that matters most
// is the one nothing on screen shows: a field the view hides must change nothing that
// readRingFromForm reads. #baseurl is exactly such a field. onProviderChange fills it with
// the preset's address for every OpenAI-compatible provider, hosted ones included, and a
// read that followed visibility rather than what the provider declares sent that address to
// the loopback assertion once already (CLAUDE.md, "A hidden settings field still holds a
// value"). So this types a key in Settings, starts from Start with the connection card
// folded, and reads back what was actually stored.

import { clearCredentials, credsFor, loadCredentials } from '../../src/store/secrets.js';
import { deleteSession, listSessions } from '../../src/store/sessions.js';

const BOOT_MS = 4000;
const settle = (ms = 250) => new Promise((resolve) => setTimeout(resolve, ms));

function loadFrame(src, width) {
  return new Promise((resolve, reject) => {
    const frame = document.createElement('iframe');
    frame.width = width;
    frame.height = 800;
    frame.style.position = 'absolute';
    frame.style.left = '-10000px';
    frame.onload = () => resolve(frame);
    frame.onerror = () => reject(new Error(`iframe failed to load ${src}`));
    frame.src = src;
    document.body.append(frame);
  });
}

async function until(predicate, ms = 4000) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    if (predicate()) return true;
    await settle(40);
  }
  return predicate();
}

export default async function run(check) {
  await clearCredentials();
  const before = new Set((await listSessions()).map((s) => s.id));
  const frame = await loadFrame('/index.html', 900);
  const win = frame.contentWindow;
  const doc = frame.contentDocument;
  try {
    await settle(BOOT_MS);
    const $ = (id) => doc.getElementById(id);
    const shown = (id) => $(id).getClientRects().length > 0;
    const view = () => $('panel-setup').dataset.view;
    const current = () => ['b-library', 'b-nav-new', 'b-settings']
      .filter((id) => $(id).getAttribute('aria-current') === 'page').join(',');
    const startOnly = ['b-start-voice', 'b-start'];
    const settingsOnly = ['stt', 'stopword', 'speech', 'b-backup', 'b-import'];

    // ── a fresh profile opens on Start, with the connection card open ────────
    check('a fresh profile boots on the Start view', view() === 'start', String(view()));
    check('Start shows its start controls, the version and the connection fields',
      [...startOnly, 'version', 'provider'].every(shown),
      [...startOnly, 'version', 'provider'].filter((id) => !shown(id)).join(', '));
    check('Start shows none of the Settings-only controls', settingsOnly.every((id) => !shown(id)),
      settingsOnly.filter(shown).join(', '));
    check('with nothing working yet, the connection card is not folded',
      !shown('connect-summary') && !$('connect').hasAttribute('data-collapsed'));

    // ── the bottom-nav Settings destination is the settings view ─────────────
    $('b-settings').click();
    await until(() => view() === 'settings');
    check('the Settings destination shows the settings view',
      view() === 'settings' && !$('panel-setup').hidden, String(view()));
    check('…and marks Settings, not New idea, as current', current() === 'b-settings', current());
    check('Settings shows dictation, finish word, spoken replies, backup and import',
      settingsOnly.every(shown), settingsOnly.filter((id) => !shown(id)).join(', '));
    check('Settings shows the connection fields and the version',
      ['provider', 'version'].every(shown));
    check('Settings hides the start controls and recent ideas',
      startOnly.every((id) => !shown(id)) && !shown('resume'));
    const groups = [...$('panel-setup').querySelectorAll('.group')]
      .filter((el) => el.getClientRects().length > 0)
      .map((el) => el.querySelector(':scope > h2').innerText.trim());
    check('Settings is grouped as Connection, Listen and speak, Data, About',
      groups.join('|') === 'Connection|Listen and speak|Data|About', groups.join('|'));
    check('the disclosure summaries are unchanged plain text',
      $('model-settings').querySelector('summary').textContent === 'Model options'
        && $('speech-options').querySelector('summary').textContent === 'Voice and speaking pace');

    // ── a hidden field changes nothing readRingFromForm reads ────────────────
    // A key typed in Settings, then Start from the Start view. fetch is stubbed so nothing
    // leaves the machine; turn 1 is the hardcoded seed question and needs no call anyway.
    const realFetch = win.fetch;
    win.fetch = () => Promise.reject(new TypeError('Failed to fetch'));
    try {
      $('provider').value = 'openai';
      $('provider').dispatchEvent(new win.Event('change'));
      $('stt').value = 'off';
      $('stt').dispatchEvent(new win.Event('change'));
      $('apikey').value = 'sk-probe-start-settings-0001';
      $('apikey').dispatchEvent(new win.Event('input'));
      check('a hosted provider still carries its preset address in the hidden #baseurl',
        !shown('field-base') && $('baseurl').value.startsWith('https://'), $('baseurl').value);
      $('b-nav-new').click();
      await until(() => view() === 'start');
      check('New idea returns to the Start view', view() === 'start' && current() === 'b-nav-new',
        `${view()} / ${current()}`);
      $('b-start').click();
      await until(() => !$('panel-interview').hidden);
      check('Type instead starts the interview from Start', !$('panel-interview').hidden,
        $('err').textContent);
      check('…without refusing the provider over an address nobody typed',
        !/not on this machine/.test($('err').textContent), $('err').textContent);
      const stored = credsFor(await loadCredentials(), 'openai');
      check('the key typed in Settings is what Start stored',
        stored.apiKey === 'sk-probe-start-settings-0001', stored.apiKey ? 'a different key' : 'no key');
      check('the hidden server address was not stored for a hosted provider',
        stored.baseUrl === '', stored.baseUrl);
    } finally {
      win.fetch = realFetch;
    }

    // ── Start folds the card once the key is saved; Change unfolds it ────────
    $('b-nav-new').click();
    await until(() => view() === 'start' && !$('panel-setup').hidden);
    check('with a saved key, Start folds the connection card to one row',
      shown('connect-summary') && !shown('provider') && !shown('apikey'),
      `summary ${shown('connect-summary')}, provider ${shown('provider')}`);
    const summary = $('connect-summary-text').textContent;
    check('the summary names the provider and a masked key, never the key',
      /OpenAI/.test(summary) && /key saved/.test(summary)
        && !summary.includes('sk-probe-start-settings-0001'), summary);
    const row = $('connect-summary').getBoundingClientRect();
    check('the folded summary is a single row', row.height < 80, `${Math.round(row.height)}px`);
    check('the start controls stay visible below the folded card', startOnly.every(shown));
    $('b-connect-change').click();
    await settle(50);
    check('Change unfolds the connection fields',
      shown('provider') && shown('apikey') && !shown('connect-summary'));
    check('…and puts focus on the provider', doc.activeElement === $('provider'),
      doc.activeElement && doc.activeElement.id);

    // Settings never folds: it is where the connection is edited.
    $('b-library').click();
    await settle();
    $('b-nav-new').click();
    await until(() => view() === 'start' && shown('connect-summary'));
    check('returning to Start folds the card again', shown('connect-summary'));
    $('b-settings').click();
    await until(() => view() === 'settings');
    check('Settings always shows the connection fields, folded on Start or not',
      shown('provider') && shown('apikey') && !shown('connect-summary'));

    // ── narrow phones ────────────────────────────────────────────────────────
    frame.width = 360;
    await settle();
    const root = doc.documentElement;
    check('Settings has no horizontal overflow at 360px', root.scrollWidth <= root.clientWidth,
      `${root.scrollWidth}/${root.clientWidth}`);
    $('b-nav-new').click();
    await until(() => view() === 'start');
    check('Start (folded) has no horizontal overflow at 360px', root.scrollWidth <= root.clientWidth,
      `${root.scrollWidth}/${root.clientWidth}`);
    $('b-connect-change').click();
    await settle(50);
    check('Start (open) has no horizontal overflow at 360px', root.scrollWidth <= root.clientWidth,
      `${root.scrollWidth}/${root.clientWidth}`);
  } finally {
    frame.remove();
    await clearCredentials();
    for (const s of await listSessions()) if (!before.has(s.id)) await deleteSession(s.id);
    for (const r of await navigator.serviceWorker.getRegistrations()) await r.unregister();
  }
}
