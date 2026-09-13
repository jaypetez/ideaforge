// One scripted interview, replayed through the real app.
//
// tools/screenshots.mjs installs `window.claude.use('sample')` from this file, which makes
// src/providers/artifact.js a real provider with no key and no network. Everything above it —
// the turn loop, the ratchet, the tripwires, the export — runs for real. Only the model is
// scripted, so the screenshots and docs/examples/remember-names.md are the genuine output of
// the genuine engine rather than prose written by hand to look like it.
//
// The engine, not this file, decides which dimension each turn targets. Replies are keyed by
// turn number and carry the dimension they were WRITTEN for; the harness warns when
// selectNextDimension disagrees, which is the signal to rewrite the question rather than to
// paper over it. The order below — substance, bar, constraints, audience, voice, references,
// references, audience — is what the engine actually picks given these coverage claims.
//
// Four rules from src/core/ that this fixture has to respect, or the screenshots quietly
// contradict the prose:
//
//   An answer of 15 words or more that shares fewer than TWO content words with the question
//   is classified as a `dodge`, and a dodge caps the dimension that answer served at
//   `partial` (engine.js). Real answers echo the question; invented ones forget to, and the
//   first draft of this file had seven dodges in nine answers.
//
//   `covered` needs `evidence` — a verbatim user quote — or applyCoverage demotes it to
//   `partial` (session.js).
//
//   At most two dimensions may rise, by at most one level, per turn (session.js).
//
//   Two question marks, or two interrogatives joined by and/or, trips the compound tripwire
//   and the question is thrown away for a bank one (engine.js).

/** The answers the harness types, in order. ANSWERS[0] replies to SEED_QUESTION. */
export const ANSWERS = [
  'An app that helps me remember the names of people I meet at conferences.',

  'Probably thirty names over three days. The ones I lose are from the evening receptions, '
  + 'and by the next morning they are completely gone.',

  'I would delete it in the first week if it made me type while someone was still talking '
  + 'to me.',

  'Three seconds, one thumb, and it has to work with no signal. Those are hard limits — the '
  + 'conference wifi is gone by the afternoon.',

  'Honestly, just for me. Maybe the two others on my team who work the same circuit, but '
  + 'nobody else would use it.',

  'On screen I want one line: Sarah Chen, Vertex, the API panel on Tuesday, her kid plays '
  + 'clarinet. That last bit is what I would actually say to her.',

  'The closest existing thing is the back of a business card, not LinkedIn. LinkedIn gives '
  + 'me a job title when I need the person I spoke to.',

  'A card cannot record my voice, and it does not know when I met you. That comparison '
  + 'stops being true the moment I want to hear myself.',
];

/**
 * Scripted replies, keyed by the `Turn number:` line in the prompt.
 *
 * The `coverage` block in reply N judges answer N-1 — the thing that is easiest to get
 * backwards, and which src/runtime/turn.js has a dedicated test for. Reply 3 grades the
 * answer to question 2, and question 2 was about substance, so reply 3 grades substance.
 */
export const TURNS = {
  2: {
    forDimension: 'substance',
    bridge: 'Conferences, so a lot of names in a short window.',
    question: 'At the last conference, how many names did you actually lose?',
    move: 'concretize',
    dimension: 'substance',
    chips: [
      'maybe thirty names over three days',
      'honestly I stopped counting',
      'only a handful, but the ones that mattered',
      'I never learned them in the first place',
    ],
    new_facts: [
      { dimension: 'outcome', fact: 'A phone app for recalling names met at conferences.' },
    ],
    coverage: {
      outcome: { level: 'partial', gap: 'no sense of what the app hands back', evidence: null },
    },
    suggest_wrap: false,
    wrap_reason: null,
  },

  3: {
    forDimension: 'bar',
    bridge: 'Thirty names, and the evening ones go first.',
    question: 'What would make you delete this in the first week?',
    move: 'anti_goal',
    dimension: 'bar',
    chips: [
      'if I had to type during a conversation',
      'if it was just another contacts list',
      'if it took more than five seconds',
      'honestly, if I forgot it was there',
    ],
    new_facts: [
      { dimension: 'substance', fact: 'Roughly thirty names lost over a three-day conference.' },
      { dimension: 'substance', fact: 'The losses cluster around the evening receptions.' },
    ],
    coverage: {
      substance: {
        level: 'partial',
        gap: 'the numbers are there, but not one real encounter',
        evidence: null,
      },
    },
    suggest_wrap: false,
    wrap_reason: null,
  },

  4: {
    forDimension: 'constraints',
    bridge: 'So nothing that puts a screen between you and the person.',
    question: 'What are the hard limits on time and signal here?',
    move: 'boundary',
    dimension: 'constraints',
    chips: [
      'three seconds and one thumb',
      'it has to work with no signal',
      'anything under a minute is fine',
      'no limit, I just need it to exist',
    ],
    new_facts: [
      { dimension: 'constraints', fact: 'Cannot require typing while someone is still talking.' },
    ],
    coverage: {
      bar: {
        level: 'partial',
        gap: 'one way to fail it, but no sense of a good save',
        evidence: null,
      },
      constraints: {
        level: 'partial',
        gap: 'no limit named for time, platform or budget',
        evidence: null,
      },
    },
    suggest_wrap: false,
    wrap_reason: null,
  },

  5: {
    forDimension: 'audience',
    bridge: 'Three seconds, one thumb, and no signal.',
    question: 'Who else would use this, if anyone?',
    move: 'who_else',
    dimension: 'audience',
    chips: [
      'honestly, just for me',
      'the two others on my team',
      'anyone who works a booth',
      'nobody, it is a personal fix',
    ],
    new_facts: [
      { dimension: 'constraints', fact: 'Three seconds, one thumb, and it must work offline.' },
    ],
    coverage: {
      constraints: {
        level: 'covered',
        gap: null,
        evidence: 'Three seconds, one thumb, and it has to work with no signal',
      },
      substance: {
        level: 'covered',
        gap: null,
        evidence: 'Probably thirty names over three days',
      },
    },
    suggest_wrap: false,
    wrap_reason: null,
  },

  6: {
    forDimension: 'voice',
    bridge: 'A personal tool for three people on one circuit.',
    question: 'Write the line you would want on screen when she walks up.',
    move: 'sample_output',
    dimension: 'voice',
    chips: [
      'Sarah Chen, Vertex, API panel Tuesday',
      'Sarah, Vertex. Kid plays clarinet.',
      'just the name, nothing else',
      'a photo, and I will do the rest',
    ],
    new_facts: [
      { dimension: 'audience', fact: 'The author plus two teammates on the same circuit.' },
    ],
    coverage: {
      audience: {
        level: 'partial',
        gap: 'nothing yet on what they already know',
        evidence: null,
      },
    },
    suggest_wrap: false,
    wrap_reason: null,
  },

  7: {
    forDimension: 'references',
    bridge: 'The clarinet does more work than the job title.',
    question: 'What existing thing is this closest to?',
    move: 'analogue',
    dimension: 'references',
    chips: [
      'like a business card with a memory',
      'like LinkedIn, but only people I met',
      'like the notes app, but faster',
      'nothing really, that is the problem',
    ],
    new_facts: [
      { dimension: 'voice', fact: 'Wants the personal hook ahead of the job title.' },
    ],
    coverage: {
      voice: {
        level: 'partial',
        gap: 'one sample line, but no rule for what earns a place',
        evidence: null,
      },
      outcome: {
        level: 'covered',
        gap: null,
        evidence: 'Sarah Chen, Vertex, the API panel on Tuesday',
      },
    },
    suggest_wrap: false,
    wrap_reason: null,
  },

  8: {
    forDimension: 'references',
    bridge: 'Not LinkedIn, then.',
    question: 'Where does the business-card comparison stop being true?',
    move: 'define_term',
    dimension: 'references',
    chips: [
      'a card cannot record my voice',
      'I never look at cards again',
      'cards do not know when I met you',
      'it does not, that is exactly it',
    ],
    new_facts: [
      { dimension: 'references', fact: 'Like the back of a business card; explicitly not LinkedIn.' },
    ],
    coverage: {
      references: {
        level: 'partial',
        gap: 'the analogue is named but not where it stops holding',
        evidence: null,
      },
      bar: {
        level: 'covered',
        gap: null,
        evidence: 'if it made me type while someone was still talking to me',
      },
    },
    suggest_wrap: false,
    wrap_reason: null,
  },

  9: {
    forDimension: 'audience',
    bridge: 'A card that cannot hold your voice.',
    question: 'What would annoy your two teammates enough to stop?',
    move: 'boundary',
    dimension: 'audience',
    chips: [
      'being asked to fill in fields',
      'anything with a sync button',
      'having to tag people afterwards',
      'nothing, they would just use it',
    ],
    new_facts: [
      { dimension: 'references', fact: 'A card holds no voice and no record of when you met.' },
    ],
    coverage: {
      references: {
        level: 'covered',
        gap: null,
        evidence: 'A card cannot record my voice',
      },
    },
    suggest_wrap: true,
    wrap_reason: 'every dimension now has the user’s own specifics behind it',
  },
};

/** The single `modelTier: 'complex'` call, made when the interview wraps. */
export const SYNTHESIS = {
  title: 'Conference name recall app',
  // Paragraphs, not hard-wrapped lines. `.output` is `white-space: pre-wrap`, so baked-in
  // newlines wrap a second time and the export renders as ragged half-lines.
  prompt: [
    '## Task',

    'Write a product brief for a phone app that helps me recall the names of people I meet '
    + 'at conferences. It is a personal tool for me and two teammates on the same circuit, '
    + 'not a product to sell.',

    '## What it needs to cover',

    'The failure it exists to fix: "probably thirty names over three days", concentrated in '
    + 'the evening receptions — by the next morning they are gone. Capture has to happen in '
    + 'the moment, with the phone barely out of a pocket.',

    '## What good looks like',

    'A good recall surfaces the human hook rather than the job title: "Sarah Chen, Vertex, '
    + 'the API panel on Tuesday, her kid plays clarinet." The last clause is the point — it '
    + 'is what the user would actually say to her. It fails on sight "if it made me type '
    + 'while someone was still talking to me".',

    '## Constraints',

    '"Three seconds, one thumb, and it has to work with no signal." The conference wifi is '
    + 'gone by the afternoon, so working offline is a requirement rather than a '
    + 'nice-to-have. Nothing that puts a screen between the user and the person in front of '
    + 'them.',

    '## Context',

    'The readers are the author and two teammates working the same circuit. They already '
    + 'know the industry, so nothing needs to explain what a company does.',

    '## Voice and form',

    'One line, card-shaped, in the register of "Sarah, Vertex. Kid plays clarinet." Personal '
    + 'hook first, credentials second or not at all.',

    '## Reference points',

    'Like the back of a business card, and deliberately not like LinkedIn: "LinkedIn gives '
    + 'me a job title when I need the person I spoke to." Unlike a card, it has to hold the '
    + 'user’s own voice and know when and where they met.',
  ].join('\n\n'),
  assumptions: [
    'Assumed the three-second limit applies to capture, not to looking a name back up.',
    'Assumed captured audio stays on the device, since it has to work with no signal.',
  ],
  open_questions: [
    {
      dimension: 'audience',
      question: 'Do your two teammates see your notes, or only their own?',
      why_it_matters: 'Shared notes turn a personal tool into something with a permission model.',
    },
    {
      dimension: 'voice',
      question: 'What earns a place on the card besides the one personal hook?',
      why_it_matters: 'Without a rule the card fills up and stops being readable in three seconds.',
    },
  ],
};
