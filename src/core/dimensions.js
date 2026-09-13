// The coverage-map spine. Seven dimensions, each mapping 1:1 to a section of
// the exported document — that mapping is what makes "covered" mean something
// testable ("could I write this section without inventing anything?") rather
// than a vibe.
//
// Tuned for the LLM-prompt / agent-instruction domain.

export const LEVELS = ['thin', 'partial', 'covered'];
export const LEVEL_RANK = { thin: 0, partial: 1, covered: 2 };

/** Orthogonal to level. `waived` and `deferred` stop a dimension being asked about. */
export const STATUSES = ['probing', 'waived', 'deferred'];

export const DIMENSIONS = [
  {
    id: 'outcome',
    label: 'What we’re making',
    weight: 1.0,
    section: 'Task',
    covered:
      'I know the artifact type, its rough size and shape, and what state the world is ' +
      'in when it is done. Not "a plan" — "a 2-page memo my CTO reads in 5 minutes and ' +
      'replies yes/no to."',
    bank: [
      'What’s the actual thing you want back — an email, a script, a plan, a list? Be as concrete as you can.',
      'Roughly how long should the output be — a sentence, a page, ten pages?',
      'Where does the output go once you have it: pasted somewhere, sent to someone, used as a first draft?',
    ],
  },
  {
    id: 'substance',
    label: 'What’s actually in it',
    weight: 1.0,
    section: 'What it needs to cover',
    covered:
      'I have the domain specifics only this user has: the real numbers, names, mechanics, ' +
      'steps and edge cases. This is the dimension they would never think to type unprompted.',
    bank: [
      'What specific facts, names, numbers or rules would the model have to know to get this right?',
      'Is there a real case you have in mind? Walk through it.',
      'What’s the awkward edge case a generic answer would get wrong?',
    ],
  },
  {
    id: 'bar',
    label: 'What good looks like',
    weight: 0.9,
    section: 'What good looks like',
    covered:
      'I know how the user will judge the output in the first ten seconds, and at least one ' +
      'specific failure that makes them throw it away.',
    bank: [
      'It’s six weeks later and you’ve stopped using this. What went wrong?',
      'What would make you reject the output on sight?',
      'How will you actually judge whether it worked?',
    ],
  },
  {
    id: 'constraints',
    label: 'Hard limits',
    weight: 0.85,
    section: 'Constraints',
    covered:
      'I know the non-negotiables and the banned moves: length, format, stack, budget, ' +
      'deadline, things that must not appear, decisions already locked.',
    bank: [
      'What must NOT appear in the output — anything banned, off-limits, or already decided?',
      'Any hard limits on length, format, tools, platform or deadline?',
      'What have you already ruled out, and why?',
    ],
  },
  {
    id: 'audience',
    label: 'Who it’s for',
    weight: 0.8,
    section: 'Context',
    covered:
      'I know who consumes it, what they already know, what they will do with it, and one ' +
      'thing that would annoy them. A reader, not a "market".',
    bank: [
      'Who reads or uses this output — you, or someone else? If someone else, who specifically?',
      'What do they already know, so the output doesn’t have to explain it?',
      'What’s one thing that would annoy them or make them stop reading?',
    ],
  },
  {
    id: 'voice',
    label: 'Tone & form',
    weight: 0.6,
    section: 'Voice and form',
    covered:
      'I know the register and the structure, ideally via a sample fragment the user wrote ' +
      'or a quoted example of right and wrong.',
    bank: [
      'What register should this be in — formal, casual, blunt, warm? Name something that sounds right.',
      'Write the first line the way you’d want it to sound, even roughly.',
      'How should it be structured — prose, bullets, headings, a table?',
    ],
  },
  {
    id: 'references',
    label: 'Like / not like',
    weight: 0.5,
    section: 'Reference points',
    covered:
      'At least one concrete analogue AND where the analogue breaks. ' +
      '"Like Stripe’s docs, but not that chatty."',
    bank: [
      'What existing thing is this most like?',
      'Where does that resemblance break down — what should be different?',
      'What’s an example of someone doing this badly that you want to avoid?',
    ],
  },
];

export const DIMENSION_IDS = DIMENSIONS.map((d) => d.id);
const BY_ID = new Map(DIMENSIONS.map((d) => [d.id, d]));

export function getDimension(id) {
  const d = BY_ID.get(id);
  if (!d) throw new Error(`unknown dimension: ${id}`);
  return d;
}

/** The opening question. Hardcoded so the composer works at t=0, before any capability resolves. */
export const SEED_QUESTION = 'In a sentence or two — what’s the idea?';

/**
 * The twelve interview moves. The model picks one and NAMES it, which is what
 * lets the engine enforce no-repeat rules without parsing the question text.
 */
export const MOVES = {
  concretize:   'Ask them to walk through ONE real, specific instance end to end.',
  tradeoff:     'Name two things they clearly both want, and make them choose. No straw men.',
  premortem:    'It’s some weeks later and the thing failed. Ask them why. Use a concrete horizon.',
  scope_cut:    'Force a brutal reduction: one screen, one paragraph, 200 words. What survives?',
  analogue:     'What existing thing is this most like — AND where does the resemblance break?',
  challenge:    'Quote them, offer a plausible counter-position, ask them to defend or revise.',
  anti_goal:    'Ask what would make them reject the output on sight.',
  sample_output:'Ask them to write a fragment of the actual thing in the voice they want.',
  define_term:  'They used a loaded word ("clean", "simple"). Quote it back; ask what it means here.',
  boundary:     'Probe a specific edge case and ask what should happen there.',
  who_else:     'Ask who else has a say, or who has to be able to live with this.',
  menu:         'Give 3–4 concrete options, ask which is closest. A RESCUE move, not a default.',
};
export const MOVE_IDS = Object.keys(MOVES);
