# Design tokens

IdeaForge has two front ends: the web app (`src/ui/app.css`) and the native Android app
(`android/app`, Jetpack Compose with Material 3). They share one information architecture
and one visual language, and this page is where the two meet. Every web token below names
the Material 3 role the Android theme uses for the same job. When you change a value on one
side, change its twin, or the apps drift apart a shade at a time.

`src/ui/app.css` owns the web values, and `test/browser/app.browser.mjs` checks them. It
asserts that both themes resolve, and that every text role meets WCAG AA (4.5:1) and every
outline 3:1 against every surface, in light and in dark.

## Colour roles

Web values are `light-dark(light, dark)` over an explicit `color-scheme`. On Android 12 and
later the app uses dynamic colour from the wallpaper, and these values are the fallback
scheme.

| Web token | Compose `ColorScheme` | Light | Dark | Used for |
|---|---|---|---|---|
| `--surface-container-low` | `surfaceContainerLow` / `background` | `#f5f6f2` | `#15191a` | The page ground |
| `--surface-container-lowest` | `surfaceContainerLowest` | `#fdfefb` | `#1d2324` | Cards, fields, grouped settings |
| `--surface-container` | `surfaceContainer` | `#e9eeea` | `#293132` | Tracks, disabled fills, quiet hover |
| `--surface-container-high` | `surfaceContainerHigh` | `#dfe6e1` | `#313a3b` | Raised sheets and menus |
| `--on-surface` | `onSurface` | `#202c2b` | `#eef0e9` | Body text and headings |
| `--on-surface-variant` | `onSurfaceVariant` | `#566562` | `#abbab5` | Hints, metadata, secondary text |
| `--outline` | `outline` | `#73847d` | `#72867f` | Field and chip borders (3:1 on every surface) |
| `--outline-variant` | `outlineVariant` | `#d4ded8` | `#364140` | Dividers and card edges |
| `--primary` | `primary` | `#24665e` | `#9ecdc3` | The one primary action, links, focus |
| `--on-primary` | `onPrimary` | `#ffffff` | `#142e29` | Text on a primary fill |
| `--primary-container` | `primaryContainer` | primary at 10% | primary at 10% | Selected and hovered tonal fills |
| `--error` | `error` | `#9e3e37` | `#e8a396` | Destructive actions and failures |
| `--error-container` | `errorContainer` | error at 12% | error at 12% | Destructive hover |
| `--success` | custom | `#306445` | `#a0c9a9` | Coverage reached, checks passed |
| `--warning` | custom | `#786120` | `#cfbd8a` | Pending and partial states |

The v1 names (`--ground`, `--surface`, `--ink`, `--accent` and the rest) are aliases of
these roles while the redesign migrates rule by rule. New rules use the role names.

## State, focus and elevation

| Web token | Compose | Value |
|---|---|---|
| `--state-hover` | state layer, hovered | 8% of the content colour |
| `--state-focus` | state layer, focused | 10% |
| `--state-pressed` | state layer, pressed | 10% |
| `--focus-ring` / `--focus-offset` | focus indication | 2px `--primary`, 3px offset |
| `--elev-0` … `--elev-3` | `tonalElevation` / shadow levels 0–3 | none, card, raised, dialog |

## Type

IBM Plex Sans throughout. Weight 600 carries headline and title roles, and the screenshot
harness checks the font has loaded before it captures anything.

| Web token | Compose `Typography` | Size / line height |
|---|---|---|
| `--type-display-*` | `displaySmall` | 2.125rem / 1.2 |
| `--type-headline-*` | `headlineSmall` | 1.75rem / 1.25 |
| `--type-title-*` | `titleMedium` | 1.125rem / 1.35 |
| `--type-body-*` | `bodyLarge` | 1rem / 1.6 |
| `--type-label-*` | `labelLarge` | .875rem / 1.35 |

## Motion

The durations are Material 3's steps. Under `prefers-reduced-motion: reduce` every
`--dur-*` token becomes `0s`, so any rule that uses a token is reduced automatically. The
Android app follows the system's animator duration scale.

| Web token | Compose | Value |
|---|---|---|
| `--dur-short1` … `--dur-short4` | `MotionTokens.DurationShort1…4` | 50, 100, 150, 200ms |
| `--dur-medium1` … `--dur-medium4` | `MotionTokens.DurationMedium1…4` | 250, 300, 350, 400ms |
| `--dur-long2` | `MotionTokens.DurationLong2` | 500ms |
| `--ease-standard` | `EasingStandard` | `cubic-bezier(.2, 0, 0, 1)` |
| `--ease-emph-decel` | `EasingEmphasizedDecelerate` | `cubic-bezier(.05, .7, .1, 1)` |
| `--ease-emph-accel` | `EasingEmphasizedAccelerate` | `cubic-bezier(.3, 0, .8, .15)` |

Loading placeholders (library cards, the skeleton question card, the write-up in `#output`)
share one `skeleton-pulse` on `--surface-container`, timed from `--dur-long2`, under
`aria-busy` on the region they stand in for; reduced motion stills them. The skeleton
question is sized in its real rows' own type (`1lh` of each, a chip's min-height), so a
typical question replaces it with no visible shift.

## Icons

Material Symbols Rounded at 24px, weight 400: the same glyphs as Compose's
`Icons.Rounded`. On the web they live in `src/ui/icons.css` as data-URI masks, painted with
`currentColor`:

```html
<button data-icon="mic">Answer out loud</button>
```

A mask on `::before` rather than an inline `<svg>` for three reasons. The CSP allows images
only from `'self'` and `data:`. `app.js` rewrites some button labels, and a pseudo-element
survives that. And the icon adds no text, so tests that find a button by its exact label
still find it.
