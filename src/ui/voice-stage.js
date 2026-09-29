const LABELS = {
  ready: 'Ready when you are',
  starting: 'Getting ready',
  speaking: 'IdeaForge is speaking',
  listening: 'Listening to you',
  transcribing: 'Turning speech into text',
  thinking: 'Thinking',
  paused: 'Paused',
  recovering: 'Trying again',
  blocked: 'Voice needs attention',
  complete: 'Conversation complete',
};

/** Null means unavailable, not silence. Ordinary microphone levels use a quarter-scale ceiling. */
export function normalizeVoiceLevel(rms) {
  return Number.isFinite(rms) ? Math.min(1, Math.max(0, rms) / .25) : null;
}

function setText(element, value) {
  const text = String(value ?? '');
  if (element.textContent !== text) element.textContent = text;
}

/**
 * Renders the static voice-stage markup. The caller owns visibility, capture and button events.
 * No timers or browser globals: only arriving microphone samples can move the meter.
 *
 * @param {Element|Document} root the stage itself or its containing DOM root
 */
export function createVoiceStage(root) {
  const stage = root?.id === 'voice-stage' ? root : root?.querySelector('#voice-stage');
  if (!stage) throw new Error('Voice stage markup is missing #voice-stage.');

  const find = (id) => {
    const element = stage.querySelector(`#${id}`);
    if (!element) throw new Error(`Voice stage markup is missing #${id}.`);
    return element;
  };
  const signal = find('voice-signal');
  const status = find('voice-status');
  const question = find('voice-question');
  const transcript = find('voice-transcript');
  const detail = find('voice-detail');
  const backend = find('voice-backend');
  const pause = find('b-voice-pause');
  const exit = find('b-voice-exit');
  const bars = [...signal.querySelectorAll('.voice-bars span')];
  if (!bars.length) throw new Error('Voice stage markup is missing its input-level bars.');

  const samples = Array(bars.length).fill(0);
  let phase = null;
  let acceptingLevels = false;
  let disposed = false;

  function clearLevels() {
    samples.fill(0);
    stage.style.setProperty('--voice-level', '0');
    stage.dataset.hasLevel = 'false';
    for (const bar of bars) bar.style.setProperty('--voice-sample', '0');
  }

  /**
   * A complete presentation snapshot. Omitted captions are cleared rather than carried into
   * another question; the live status changes only when its human-readable label changes.
   */
  function render({
    phase: nextPhase = 'ready', question: nextQuestion = '', transcript: nextTranscript = '',
    detail: nextDetail = '', backend: nextBackend = '', pending = false,
  } = {}) {
    if (disposed) return;
    if (!Object.hasOwn(LABELS, nextPhase)) {
      throw new RangeError(`Unknown voice stage phase: ${String(nextPhase)}`);
    }
    if (phase !== nextPhase) clearLevels();
    phase = nextPhase;
    acceptingLevels = phase === 'listening';
    stage.dataset.phase = phase;
    setText(status, LABELS[phase]);
    setText(question, nextQuestion);
    setText(transcript, nextTranscript);
    setText(detail, nextDetail);
    setText(backend, nextBackend);

    const resumable = phase === 'paused' || phase === 'blocked';
    setText(pause, resumable ? 'Resume' : 'Pause');
    pause.disabled = resumable && Boolean(pending);
    exit.disabled = false;
  }

  function level(rms) {
    if (disposed || !acceptingLevels) return;
    const value = normalizeVoiceLevel(rms);
    if (value === null) {
      clearLevels();
      return;
    }
    samples.shift();
    samples.push(value);
    stage.style.setProperty('--voice-level', String(value));
    stage.dataset.hasLevel = 'true';
    // Each bar is an actual recent sample, never a fabricated waveform or frequency bin.
    bars.forEach((bar, index) => bar.style.setProperty('--voice-sample', String(samples[index])));
  }

  function reset() {
    if (disposed) return;
    acceptingLevels = false;
    clearLevels();
    setText(transcript, '');
  }

  reset();
  return {
    render,
    level,
    reset,
    dispose() {
      reset();
      disposed = true;
    },
  };
}
