// The same interview, said out loud.
//
// A different shape from validate-local.mjs's ANSWERS, and kept separate rather than
// overloading them: an utterance is a timeline of recogniser events, not a string, and it
// has to carry the trigger word that ends it.
//
// The timings are deliberately generous relative to the settle window. An interim that ends
// in the trigger only arms a timer, and a final resolves at once, so the pattern below —
// two interims, then a final carrying the trigger — exercises the real path rather than
// short-circuiting it on the first event.

/** The trigger this script speaks. Must match what the app is configured with. */
export const TRIGGER = 'over';

const SPOKEN = [
  'A tool that helps me remember the names of people I meet at conferences, because I lose '
  + 'them within about a minute of the handshake and it is embarrassing',
  'At the last conference I met maybe forty people over two days and could name four of them '
  + 'by the evening. The ones I lost were the ones I met in corridors, standing up',
  'Good looks like walking into the second day and greeting six people by name without '
  + 'checking anything, and never once calling somebody by the wrong name',
  'It has to work in three seconds with one thumb, standing up, holding a drink, with no '
  + 'signal in a conference centre basement',
  'The people who need it most are the ones who go to conferences alone and find the whole '
  + 'thing exhausting rather than energising',
  'It should feel like a friend leaning over and telling you the name, not like a database '
  + 'you are querying under time pressure',
];

/**
 * One utterance: a couple of interims while the sentence is still being said, then a final
 * carrying the trigger word.
 *
 * The middle interim deliberately contains the word "over" mid-sentence in the second
 * answer, which is the case that truncates every real answer if the settle window is wrong.
 */
function utterance(text) {
  const half = text.slice(0, Math.max(12, Math.floor(text.length / 2)));
  return [
    { at: 120, interim: half },
    { at: 380, interim: text },
    { at: 620, final: `${text} ${TRIGGER}` },
  ];
}

/** @param {number} n how many answers the run needs */
export function spokenAnswers(n) {
  return Array.from({ length: n }, (_, i) => utterance(SPOKEN[i % SPOKEN.length]));
}

/** The yes that accepts the wrap-up offer. */
export const YES = [{ at: 120, interim: 'yes' }, { at: 300, final: 'yes' }];
