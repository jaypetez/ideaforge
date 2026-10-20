export function completedAudio(source) {
  if (!source?.started || !source.ended || !source.trustedEnd || source.stops !== 0
      || !source.destination || !source.disconnected) return false;
  const { startClock, endClock, duration, sampleRate, renderQuantumSize } = source;
  if (![startClock, endClock, duration, sampleRate].every(Number.isFinite)
      || startClock < 0 || endClock < startClock || duration <= 0 || sampleRate <= 0
      || !Number.isInteger(renderQuantumSize) || renderQuantumSize <= 0) return false;
  // Chromium posts natural ended while rendering the last block, before advancing
  // currentTime. Admit that block, never an earlier one or arbitrary millisecond slack.
  // https://webaudio.github.io/web-audio-api/#dom-baseaudiocontext-currenttime
  // Both probes use default contexts: before renderQuantumSize existed, the quantum
  // was fixed at 128 frames (their instrumentation's fallback).
  const lastFrame = Math.round(startClock * sampleRate) + Math.round(duration * sampleRate) - 1;
  const finalQuantum = Math.floor(lastFrame / renderQuantumSize) * renderQuantumSize;
  return Math.round(endClock * sampleRate) >= finalQuantum;
}
