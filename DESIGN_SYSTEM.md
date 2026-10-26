# Pixel Signal — Notify Shubbyy Design System

A 90s arcade-cabinet + CRT design language for the web trigger and the Android
receiver. The goal is atmosphere without sacrificing clarity: neon phosphor,
hard pixel shadows, chunky borders, monospace type, and tactile "cabinet button"
interactions.

---

## 1. Concept

| Principle | Rule |
| --- | --- |
| Cabinet, not card | Surfaces are layered arcade panels with 3px borders and hard offset shadows. No soft blurry cards. |
| Phosphor color | One dominant neon color per state; secondary neons are accents only. |
| Pixel type | Display = Press Start 2P. UI labels = Silkscreen. Reading = VT323. |
| Tactile motion | Controls move in steps, press into the surface, and use hard shadows. Never ease like glass. |
| Readable first | Pixel styling never reduces contrast below WCAG AA for body copy. |

---

## 2. Color tokens

All tokens live in `design-system.css` under `:root`.

### Surfaces
| Token | Hex | Use |
| --- | --- | --- |
| `--ink-900` | `#07030f` | Page / screen background |
| `--ink-700` | `#120a26` | Panel surface |
| `--ink-600` | `#1b1038` | Raised surface, terminal header |
| `--ink-500` | `#27174f` | Disabled surface |
| `--ink-400` | `#3a266c` | Default border |
| `--ink-300` | `#55409a` | Strong border / outline |

### Accents
| Token | Hex | Meaning |
| --- | --- | --- |
| `--neon-green` | `#39ff88` | Online / primary action / Beep |
| `--neon-pink` | `#ff2e88` | Siren / destructive / urgency |
| `--neon-cyan` | `#3ee6ff` | Info / Vibrate / channel codes |
| `--neon-yellow` | `#ffd23f` | Focus, badges, warnings |
| `--neon-purple` | `#a06bff` | Secondary decoration |
| `--danger` | `#ff4d6d` | Offline / errors |

### Text
`--text` `#e9e4ff` (primary) · `--text-dim` `#b9afd8` · `--text-muted` `#8b7fb5`

Android equivalents are in `android/app/src/main/res/values/colors.xml`.

---

## 3. Typography

Loaded from Google Fonts on web and bundled as TTF resources on Android.

| Role | Family | Size | Notes |
| --- | --- | --- | --- |
| Display / logo | Press Start 2P | 18–54px | Uppercase, wide tracking, 1.5 line-height |
| UI labels / buttons | Silkscreen | 10–12px | Uppercase, `0.1em` tracking |
| Body / logs | VT323 | 15–20px | Monospace, large x-height, best at 16px+ |
| Channel codes | VT323 | 14px | Uppercase is not required |

Rules:
- Never set Press Start 2P below 10px.
- Never use Press Start 2P for paragraphs.
- Use `.px-title`, `.px-subtitle`, `.px-label`, `.px-body`, `.px-code`.

---

## 4. Spacing & grid

4px base scale: `4 · 8 · 12 · 16 · 24 · 32 · 48 · 64`.
Web layout maxes at `1080px`; stage + terminal split at `900px`.

---

## 5. Shape & elevation

- Border width: `3px` main, `2px` inner/inset.
- Corners: `2px` (nearly square); round only for circular arcade buttons.
- Elevation = hard offset: `4px 4px 0 #000` (lg: `6px 6px 0 #000`).
- Panels also get an inner black ring: `inset 0 0 0 3px #000`.
- Active buttons translate `4px, 4px` and drop their shadow.

---

## 6. Components

### Web (`design-system.css`)
- `.px-panel`, `.px-panel--raised`, `.px-panel--green`, `.px-panel--pink`
- `.px-inset`, `.px-dither`
- `.px-btn`, variants `--ghost --cyan --pink --yellow --danger --sm --block --icon`
- `.px-chip`, `.led`, `.led--ok / --warn / --off`, `.px-badge`
- `.px-tabs`, `.px-tab`
- `.px-terminal`, `.px-log-line`
- `.px-progress`
- `.px-toast`, `.px-modal`
- `.px-scroll`, `.sr-only`

Markup example:

```html
<button class="px-btn px-btn--cyan">
  <svg viewBox="0 0 24 24"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/></svg>
  Fire
</button>

<div class="px-chip px-chip--ok">
  <span class="led led--ok"></span> Online
</div>
```

### Android
Bundled fonts: `res/font/press_start_2p.ttf`, `res/font/vt323.ttf`.
Drawables: `bg_card.xml` (panel), `bg_pill.xml` (chip), `bg_inset.xml` (log),
`bg_arcade_button.xml` (primary hard-shadow button), `bg_screen.xml` (starfield).
Use `app:fontFamily="@font/vt323"` in XML (AppCompat backport) and
`ResourcesCompat.getFont` when setting typefaces in code.

---

## 7. Motion

| Token | Value | Use |
| --- | --- | --- |
| `--dur-fast` | 90ms | Press / hover |
| `--dur-med` | 220ms | Panels, toasts, modals |
| `--ease-step` | `steps(4, end)` | Pixel snaps |
| `--ease-out` | `cubic-bezier(.2,0,0,1)` | Entrances |

Ambient effects: LED blink (`px-blink`), aura pulse, login typing, scanline
overlay. All ambient motion stops under `prefers-reduced-motion: reduce`.

---

## 8. Accessibility

- Body text ≥ 4.5:1 contrast on `--ink-700` and `--ink-900`.
- Focus uses a yellow 2px outline with 3px offset, visible on all surfaces.
- The trigger is a native `<button>`; Space/Enter work.
- Status changes and delivery ACKs are announced via `aria-live`.
- Motion respects `prefers-reduced-motion`.
- Hit targets are at least 44×44px on touch.

---

## 9. Sound

The interface uses a Web Audio chiptune synth (no assets): a square-wave
"coin" chirp for Beep, a sawtooth wail for Siren, and haptics for Vibrate.
A persisted mute control lives in the header. Android uses its own synthesized
PCM alert engine in `AlertSoundPlayer.kt`.
