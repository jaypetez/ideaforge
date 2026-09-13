// IdeaForge Day-0 runtime probe.
// If this file executes at all, Spike 1 has passed: the `files` map serves
// relative ES modules and no bundler is needed.

const lines = [];
const logEl = document.getElementById('log');
const $ = (id) => document.getElementById(id);

function log(s = '') {
  lines.push(s);
  logEl.textContent = lines.join('\n');
  logEl.scrollTop = logEl.scrollHeight;
}
function head(s) { log(''); log('── ' + s + ' ' + '─'.repeat(Math.max(0, 52 - s.length))); }

// ---------------------------------------------------------------- Spike 1
$('modverdict').className = 'verdict pass';
$('modhead').textContent = 'probe.js loaded as an ES module';
$('modbody').innerHTML =
  'A relative <code>&lt;script type="module" src="./probe.js"&gt;</code> resolved from the ' +
  'artifact origin. <strong>No build step needed</strong> — author plain ES modules and publish ' +
  'them with the <code>files</code> map.';

$('stamp').textContent =
  new Date().toISOString().replace('T', ' ').slice(0, 19) + ' · ' +
  (window.isSecureContext ? 'secure context' : 'INSECURE CONTEXT') + ' · ' +
  Math.round(window.innerWidth) + '×' + Math.round(window.innerHeight);

lines.length = 0;
log('IdeaForge runtime probe');
log('ua        ' + navigator.userAgent);
log('secure    ' + window.isSecureContext);
log('claude    ' + (window.claude ? 'present (' + Object.keys(window.claude).join(',') + ')'
                                  : 'ABSENT — not inside a Claude viewer'));
log('module    LOADED  <- Spike 1 PASS');

// ------------------------------------------------- capability resolution
const CAPS = ['sample', 'db', 'downloads', 'permissions', 'artifact', 'assets', 'room', 'user'];
const capsEl = $('caps');
const state = {};

function capRow(name) {
  const row = document.createElement('div');
  row.className = 'row';
  row.innerHTML =
    '<span class="name">' + name + '</span>' +
    '<span class="chip pend" data-chip>pending</span>' +
    '<span class="ms" data-ms>—</span>' +
    '<span class="detail" data-detail></span>';
  capsEl.appendChild(row);
  return {
    set(cls, text, ms, detail) {
      const c = row.querySelector('[data-chip]');
      c.className = 'chip ' + cls;
      c.textContent = text;
      row.querySelector('[data-ms]').textContent = ms == null ? '—' : ms + ' ms';
      if (detail) row.querySelector('[data-detail]').textContent = detail;
    }
  };
}

async function probeCaps() {
  head('claude.use() resolution');
  if (!window.claude || typeof window.claude.use !== 'function') {
    log('window.claude.use is not a function — nothing else can be probed.');
    CAPS.forEach((n) => capRow(n).set('fail', 'no runtime', null, ''));
    return;
  }
  await Promise.all(CAPS.map(async (name) => {
    const ui = capRow(name);
    const t0 = performance.now();
    try {
      const ns = await window.claude.use(name);
      const ms = Math.round(performance.now() - t0);
      state[name] = ns;
      if (ns) {
        const members = (typeof ns === 'function')
          ? 'function + [' + Object.keys(ns).join(',') + ']'
          : '[' + Object.keys(ns).join(',') + ']';
        ui.set('ok', 'available', ms, members);
        log(pad(name) + 'AVAILABLE   ' + ms + 'ms   ' + members);
      } else {
        ui.set('fail', 'null', ms, 'not served, not granted, or failed to load');
        log(pad(name) + 'null        ' + ms + 'ms');
      }
    } catch (e) {
      const ms = Math.round(performance.now() - t0);
      ui.set('fail', 'threw', ms, String(e && e.message || e));
      log(pad(name) + 'THREW       ' + ms + 'ms   ' + (e && e.message));
    }
  }));

  if (state.permissions) {
    head('permissions.state()  (never prompts)');
    try { log(JSON.stringify(await state.permissions.state(), null, 2)); }
    catch (e) { log('state() failed: ' + (e && e.message)); }
  }
  if (state.sample && state.sample.limits) {
    head('sample.limits()');
    try { log(JSON.stringify(await state.sample.limits(), null, 2)); }
    catch (e) { log('limits() failed: ' + (e && e.code || e)); }
  }
  refreshDump();
}
const pad = (s) => (s + '            ').slice(0, 12);

// ------------------------------------------- Spike 4: model tier latency
// The single most decision-relevant measurement: `default` writes good
// interview questions but may think for 5-60s first. Measure it for real.
$('b-tiers').onclick = async () => {
  const btn = $('b-tiers');
  btn.disabled = true;
  head('Model tier latency  (3 paid calls)');
  if (!state.sample) { log('sample unavailable — skipped.'); btn.disabled = false; return; }

  // A realistic stand-in for one interview turn: short JSON, needs judgement.
  const PROMPT =
    'You are interviewing someone about an idea. They said: "a tool that interviews ' +
    'you to turn a vague idea into a good LLM prompt". Ask ONE short follow-up question ' +
    '(max 25 words) that they could only answer from their own head. ' +
    'Reply with only {"question": string}.';

  for (const tier of ['quick', 'default', 'complex']) {
    const t0 = performance.now();
    let tFirst = null;
    try {
      const r = await state.sample(PROMPT, {
        modelTier: tier,
        cache: false,
        onText: () => { if (tFirst === null) tFirst = Math.round(performance.now() - t0); }
      });
      const total = Math.round(performance.now() - t0);
      log(pad(tier) + 'first-text ' + String(tFirst).padStart(6) + ' ms' +
          '   total ' + String(total).padStart(6) + ' ms' +
          '   served-as ' + r.modelTierApplied);
      log('            ' + r.text.replace(/\s+/g, ' ').trim().slice(0, 160));
    } catch (e) {
      log(pad(tier) + 'FAILED  code=' + (e && e.code) + '  ' + (e && e.message || ''));
      if (e && e.code === 'not_granted') break;
    }
  }
  log('');
  log('Decision: if `default` first-text is consistently > ~15s, the interview will');
  log('feel ponderous and Resolution 1 needs revisiting.');
  btn.disabled = false;
  refreshDump();
};

// ------------------------------------------------ Spike 2: microphone
$('b-mic').onclick = () => {
  head('Microphone / SpeechRecognition');
  const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
  log('SpeechRecognition   ' + (SR ? 'present' : 'ABSENT'));
  log('webkit-prefixed     ' + (window.webkitSpeechRecognition ? 'yes' : 'no'));
  log('isSecureContext     ' + window.isSecureContext);
  log('mediaDevices        ' + (navigator.mediaDevices ? 'present' : 'ABSENT'));
  if (!SR) { log('=> Hide the mic button. Keyboard dictation key still works.'); refreshDump(); return; }

  let rec;
  try { rec = new SR(); } catch (e) { log('constructor threw: ' + e.message); refreshDump(); return; }
  rec.continuous = false; rec.interimResults = true; rec.lang = 'en-US';
  rec.onstart  = () => log('onstart   — MIC GRANTED inside the iframe. Say something.');
  rec.onresult = (ev) => {
    let s = '';
    for (let i = ev.resultIndex; i < ev.results.length; i++) s += ev.results[i][0].transcript;
    log('onresult  "' + s.trim() + '"' + (ev.results[ev.results.length - 1].isFinal ? '  [final]' : '  [interim]'));
  };
  rec.onerror = (ev) => {
    log('onerror   ' + ev.error);
    if (ev.error === 'not-allowed' || ev.error === 'service-not-allowed')
      log('=> The claude.ai iframe does not grant the mic. Drop the in-page button.');
    refreshDump();
  };
  rec.onend = () => { log('onend'); refreshDump(); };
  try { rec.start(); log('start() did not throw synchronously'); }
  catch (e) { log('start() THREW synchronously: ' + e.message); refreshDump(); }
};

// ------------------------------------------ Spike 3: downloads.save(.md)
$('b-dl').onclick = async () => {
  head('downloads.save() with a .md');
  if (!state.downloads) { log('downloads unavailable (use() returned null).'); refreshDump(); return; }
  const md = '# IdeaForge probe\n\nIf you are reading this as a saved file, ' +
             '`downloads.save` accepts `.md` on this device.\n\n- saved: ' +
             new Date().toISOString() + '\n';
  try {
    const r = await state.downloads.save({ filename: 'ideaforge-probe.md', data: md });
    log('save() resolved: ' + JSON.stringify(r));
    log('=> Check whether a file actually landed (or a share sheet opened).');
  } catch (e) {
    log('save() rejected: code=' + (e && e.code) + '  ' + (e && e.message || ''));
  }
  refreshDump();
};

// ---------------------------------------------------- db round trip
$('b-db').onclick = async () => {
  head('db round trip');
  if (!state.db) { log('db unavailable (use() returned null).'); refreshDump(); return; }
  const id = 'probe_' + Math.random().toString(36).slice(2, 10);
  try {
    const t0 = performance.now();
    await state.db.doc('probe/' + id).set({ hello: 'world', at: Date.now() });
    const tw = Math.round(performance.now() - t0);
    const snap = await state.db.doc('probe/' + id).get();
    const tr = Math.round(performance.now() - t0) - tw;
    log('write ' + tw + ' ms   read ' + tr + ' ms   exists=' + snap.exists);
    log('data  ' + JSON.stringify(snap.data()));
    const all = await state.db.collection('probe').limit(20).get();
    log('collection enumerates ' + all.docs.length + ' doc(s) — confirms no per-viewer privacy.');
    await state.db.doc('probe/' + id).delete();
    log('cleaned up.');
  } catch (e) {
    log('db failed: code=' + (e && e.code) + '  ' + (e && e.message || e));
  }
  refreshDump();
};

// ------------------------------------------------------------- output
function refreshDump() { $('dump').value = lines.join('\n'); }

$('b-copy').onclick = async () => {
  refreshDump();
  try {
    await navigator.clipboard.writeText(lines.join('\n'));
    $('b-copy').textContent = 'Copied';
    setTimeout(() => { $('b-copy').textContent = 'Copy results'; }, 1500);
  } catch (e) {
    log('');
    log('clipboard.writeText failed (' + (e && e.name) + ') — use the text box below.');
    $('dump').hidden = false;
    $('dump').focus();
    $('dump').select();
  }
};
$('b-show').onclick = () => {
  refreshDump();
  $('dump').hidden = !$('dump').hidden;
  if (!$('dump').hidden) { $('dump').focus(); $('dump').select(); }
};

probeCaps();
