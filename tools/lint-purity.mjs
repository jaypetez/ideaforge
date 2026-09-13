// src/core/ must stay platform-free so the Phase-2 PWA port is a copy, not a rewrite.
// Without this check, core/ acquires a `document.` reference within a week.
//
// src/runtime/ is scanned for the same reason with a different payoff: the turn runner
// takes its provider and its clock as arguments, and linting it is what keeps it that
// way — the first `Date.now()` someone adds inside runTurn is the end of resumability.
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

// fileURLToPath, not .pathname: on Windows .pathname yields '/C:/...' and readdirSync
// then resolves it to 'C:\C:\...' and throws ENOENT.
const dirOf = (rel) => fileURLToPath(new URL(rel, import.meta.url));

// src/providers/ is deliberately absent. It is the platform boundary: exactly one
// directory in this repo may say `fetch` or `claude`, and that is the point of it.
const SCANNED = [
  { rel: '../src/core/', label: 'src/core/' },
  { rel: '../src/runtime/', label: 'src/runtime/' },
];

const BANNED = [
  'window', 'document', 'localStorage', 'sessionStorage',
  'claude', 'navigator', 'fetch', 'alert',
];

let failures = 0;
for (const { rel, label } of SCANNED) {
  const DIR = dirOf(rel);
  for (const file of readdirSync(DIR).filter((f) => f.endsWith('.js'))) {
    scan(`${label}${file}`, readFileSync(join(DIR, file), 'utf8'));
  }
}

function scan(name, src) {
  // Split on \r?\n, not '\n'. A CRLF checkout otherwise leaves a trailing \r on every
  // line, and `.` does not match \r — so `//.*$` never matches, no comment is stripped,
  // and every banned word inside a comment is reported as a real reference. This passed
  // on a Windows laptop with an LF checkout and failed on the Windows CI runner, which
  // checks out CRLF by default.
  src.split(/\r?\n/).forEach((line, i) => {
    // The ban is on real references, so strip comments and string literals first —
    // otherwise the word "claude" inside a mode name or a label trips it.
    const code = line
      .replace(/\/\/.*$/, '')
      .replace(/\/\*[\s\S]*?\*\//g, '')
      .replace(/'(?:[^'\\]|\\.)*'/g, "''")
      .replace(/"(?:[^"\\]|\\.)*"/g, '""')
      // Template literals keep their interpolations: blanking the whole literal would
      // hide `${document.title}`, which is a real reference wearing a string's clothes.
      // (A `}` nested inside an interpolation defeats this; a nested one is a smell anyway.)
      .replace(/`(?:[^`\\]|\\.)*`/g, (lit) => '``' + (lit.match(/\$\{[^}]*\}/g) || []).join(''));
    for (const bad of BANNED) {
      if (new RegExp('\\b' + bad + '\\b').test(code)) {
        console.error(`${name}:${i + 1}  banned reference "${bad}"\n    ${line.trim()}`);
        failures++;
      }
    }
  });
}

const where = SCANNED.map((s) => s.label).join(' and ');
if (failures) {
  console.error(`\nlint:purity FAILED — ${failures} platform reference(s) in ${where}.`);
  process.exit(1);
}
console.log(`lint:purity ok — ${where} are platform-free.`);
