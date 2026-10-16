/** A scripted, affirmative user action for fixtures that intentionally run an interview. */
export async function acknowledgeInference(win) {
  const doc = win.document;
  const $ = (id) => doc.getElementById(id);
  const until = async (predicate, label) => {
    for (const end = performance.now() + 5000; performance.now() < end;) {
      if (predicate()) return;
      await new Promise((resolve) => setTimeout(resolve, 25));
    }
    throw new Error(`Fixture agreement did not reach ${label}`);
  };
  const settled = () => !doc.documentElement.matches(':active-view-transition');
  if ($('inference-consent')?.checked) return until(settled, 'settled navigation');
  const panel = doc.body.dataset.panel;
  const view = $('panel-setup').dataset.view;
  const step = $('connect').dataset.step;
  if (!['setup', 'library'].includes(panel)) {
    throw new Error('Fixture agreement must precede entering an interview');
  }
  if (!$('inference-consent')?.getClientRects().length) {
    $('b-settings').click();
    await until(() => !$('panel-setup').hidden && doc.body.dataset.panel === 'setup'
      && $('panel-setup').dataset.view === 'settings', 'Settings');
  }
  const checkbox = $('inference-consent');
  if (!checkbox || checkbox.disabled || !checkbox.getClientRects().length) {
    throw new Error('Fixture has no visible, valid inference agreement');
  }
  checkbox.checked = true;
  checkbox.dispatchEvent(new win.Event('change', { bubbles: true }));
  if (!checkbox.checked) throw new Error('Fixture inference agreement was not persisted');
  if (panel === 'library') {
    $('b-library').click();
    await until(() => doc.body.dataset.panel === 'library', 'original Ideas');
  } else if (view === 'start' && $('panel-setup').dataset.view !== 'start') {
    $('b-nav-new').click();
    await until(() => $('panel-setup').dataset.view === 'start', 'original Start');
    if (step !== 'ready' && $('connect').dataset.step === 'ready') $('b-connect-change').click();
    if (step === 'provider' && $('connect').dataset.step === 'fields') $('b-connect-back').click();
  }
  await until(settled, 'settled navigation');
}
