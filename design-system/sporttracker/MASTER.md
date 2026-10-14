# Sporttracker Design System — M5

> **Source of truth for M5 implementation.** This reviewed master replaces the initial `ui-ux-pro-max --design-system --persist` recommendation where it conflicted with the approved Hybrid direction. Page overrides belong in `design-system/sporttracker/pages/<page>.md`; none are required for M5.

## Product direction

**Hybrid Running Analytics:** a calm, professional data grid with a small amount of sporting energy. Keep the six-week overview and activity detail immediately scannable. Use **Electric Mint** for selected, primary, and performance-highlighted content; reserve colourful activity icons for the recent-activities list. Do not mimic Strava orange, add gamification, use a social feed, or introduce coaching/AI functionality.

## Foundations

### Colour tokens

| Token | Value | Use |
|---|---:|---|
| `--color-canvas` | `#F7F9FC` | page background |
| `--color-surface` | `#FFFFFF` | cards, inputs, tables |
| `--color-ink` | `#10233F` | navigation, headings, primary text |
| `--color-text` | `#172033` | body text |
| `--color-muted` | `#52657B` | labels and secondary copy |
| `--color-border` | `#DDE5ED` | boundaries and dividers |
| `--color-accent` | `#00D6A2` | chart highlight, active navigation, selected state |
| `--color-accent-strong` | `#007A5D` | primary button and accent text on light surfaces |
| `--color-accent-subtle` | `#E4FFF7` | selected/card-highlight background |
| `--color-info` | `#1E73BE` | informational state |
| `--color-success` | `#13795B` | completed import only |
| `--color-warning` | `#9A6200` | skipped/attention state |
| `--color-danger` | `#B9383D` | errors and failed import only |

Use the accent only as a brand/selection signal, never as the only carrier of meaning. `--color-accent-strong` on white and white on `--color-accent-strong` must satisfy WCAG AA; `--color-accent` is for non-text decoration and chart series, not small light-background text.

### Typography and spacing

- Use the system stack: `Inter, ui-sans-serif, system-ui, -apple-system, "Segoe UI", sans-serif`; do **not** load a remote font.
- Type scale: page title `clamp(1.5rem, 3vw, 2rem)/1.2/700`; section title `1.125rem/1.3/700`; metric value `1.5rem/1.15/700`; body `1rem/1.5/400`; label `0.8125rem/1.35/600`.
- Spacing scale: `4, 8, 12, 16, 24, 32, 48px`. Use `16px` card padding on mobile and `24px` from tablet upward.
- Radius: `8px` controls, `12px` cards, `999px` small badges. Shadows are limited to hoverable interactive cards and overlays; ordinary panels use a border.

### Layout and responsive rules

- Content container: `min(100% - 2rem, 72rem)`; page side padding is `16px` at 375px and `24px` from 768px.
- Desktop navigation is a dark top bar; mobile is a compact header plus an accessible menu/primary-navigation control. The current route is indicated by text and `aria-current="page"`, not colour alone.
- Breakpoints: **0–639px** one content column; **640–767px** two KPI columns; **768–1023px** two-to-three KPI columns and desktop header; **1024px+** four-to-six KPI columns and dashboard analysis grid. Test at 375, 768, 1024, and 1440px without horizontal scrolling.
- Tables remain semantic tables inside a labelled horizontal-scroll region on small screens; do not collapse columns into ambiguous cards.

## Component specifications

| Component | Required behaviour |
|---|---|
| App shell/navigation | Contains skip link, visible focus treatment, dashboard/import links, and a stable main-content landmark. The fixture-summary route remains available but is not product navigation. |
| Card / metric card | Static metrics are not clickable. Activity rows/cards are links with full-row focus and hover treatment; hover must not move layout. |
| Button | Minimum 44×44px hit target where practical. Primary uses `--color-accent-strong`; disabled remains visibly disabled; a spinner never changes the button width. |
| Status message | Loading uses `role="status"`; errors use `role="alert"`; result states pair icon, text, and semantic colour. |
| Activity icon | Inline SVG from one local, consistent icon set. It supplements the activity name/type and has an accessible name or is decorative with `aria-hidden="true"`. Never use emoji. |
| Import drop zone | Is a real labelled control or has keyboard-equivalent activation, visible focus, and clear accepted-file guidance. |
| Chart container | Has a visible heading, text alternative/summary, a fixed minimum height, and resizes its ECharts canvas with its container. Respect reduced motion. |

## Data visualisation

| Data | Pattern | Colour |
|---|---|---:|
| weekly distance | six-column bar chart with textual values | Electric Mint |
| pace over distance | linked ECharts line, inverted y-axis, no smoothing | `#00A982` |
| heart rate | linked ECharts line | `#D85A63` |
| elevation | linked ECharts line/area only if legible | `#9A6A35` |
| cadence | optional linked ECharts line | `#6D5CE7` |

Do not use pie/donut charts, artificial progress scores, animated count-ups, or extra data series. Tooltips retain unit and distance, null values remain gaps, and charts stay linked through the existing ECharts group. The dashboard’s six weekly values use simple accessible HTML/CSS bars; do not add a dashboard chart dependency.

## Interaction and accessibility

- Use semantic `nav`, `main`, `header`, `section`, `button`, `table`, `time`, `dl`, and native file input controls.
- Keyboard focus is a 3px Electric-Mint outline with an ink offset/background that remains visible on every surface.
- Interactions use colour/opacity transitions of 150–200ms only; turn them off under `prefers-reduced-motion: reduce`.
- All errors are announced, empty states explain the next action, and loading states reserve layout space to prevent jumps.
- Icons, map markers, chart colours, and import statuses are not the sole way to understand information.

## Implementation boundary

M5 centralises these tokens in frontend CSS and applies them to the existing Vue views. It introduces no UI framework, remote font, API change, data migration, FIT-data rewrite, social feature, training plan, AI feature, or Garmin integration.
