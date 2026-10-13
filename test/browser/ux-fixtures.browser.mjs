import { assertUxGeometry, awaitUx, measureUxState, presentUxState } from './fixtures/ux.js';

export default async function run(check) {
  const fixtures = await (await fetch('/test/fixtures/ux.json')).json();
  check('the shared UX inventory includes mixed, 100-idea, long and checklist data',
    fixtures.datasets.mixed.length === 12 && fixtures.datasets.hundred.length === 100
      && fixtures.datasets.long[0].name.length === 120
      && fixtures.datasets.bank[0].turns.at(-1).questionSource === 'bank');
  let frame;
  try {
    frame = document.createElement('iframe');
    frame.width = 390;
    frame.height = 844;
    frame.style.position = 'absolute';
    frame.style.left = '-10000px';
    document.body.append(frame);
    frame.src = '/index.html';
    await awaitUx(() => frame.contentDocument?.getElementById('version')?.textContent, 'fixture app boot');
    const win = frame.contentWindow;
    const fresh = fixtures.states.find((s) => s.id === 'fresh');
    const controls = await presentUxState(win, fresh, fixtures);
    const measured = measureUxState(win, fresh.id, controls);
    check('a fixture measures actual assembled app controls, not a reference drawing',
      measured.controls.length === 4 && measured.width === 390
        && measured.controls.every((c) => c.visible && c.reachable));
    let bitten = false;
    try {
      assertUxGeometry({ ...measured, controls: [{ ...measured.controls[0], reachable: false }] });
    } catch (error) {
      bitten = /Clipped or inaccessible/.test(error.message);
    }
    check('the fixture evidence guard refuses a clipped necessary control', bitten);
    let missing = false;
    try {
      measureUxState(win, 'missing-control', ['#does-not-exist']);
    } catch (error) {
      missing = /Missing/.test(error.message);
    }
    check('a requested missing control fails rather than becoming a skipped capture', missing);
    const clip = win.document.createElement('div');
    clip.style.cssText = 'position:fixed;top:100px;left:30px;width:160px;height:28px;overflow:hidden;z-index:10000';
    const button = win.document.createElement('button');
    button.id = 'ux-partially-clipped';
    button.textContent = 'Clipped action';
    button.style.cssText = 'width:120px;height:48px;min-height:48px;padding:0;margin:0';
    clip.append(button);
    win.document.body.append(clip);
    let clipped = false;
    try {
      const box = button.getBoundingClientRect();
      check('the clipping regression has a center-hittable action',
        win.document.elementFromPoint(box.left + box.width / 2, box.top + box.height / 2) === button);
      try {
        measureUxState(win, 'partially-clipped', ['#ux-partially-clipped']);
      } catch (error) {
        clipped = /Clipped or inaccessible/.test(error.message);
      }
      check('a center hit cannot bless an ancestor-clipped action', clipped);
    } finally {
      clip.remove();
    }
    const voice = fixtures.states.find((s) => s.id === 'voice-listening');
    await presentUxState(win, voice, fixtures);
    check('an isolated voice fixture is labelled separately and reports unavailable samples',
      voice.evidence === 'isolated-component'
        && win.document.getElementById('voice-stage').dataset.hasLevel === 'false'
        && /No microphone level reading/.test(win.document.getElementById('voice-detail').textContent));
    check('isolated voice retains complete question/caption strings',
      win.document.getElementById('voice-question').textContent === fixtures.text.question
        && win.document.getElementById('voice-transcript').textContent === fixtures.text.answer);
  } finally {
    frame?.remove();
  }
}
