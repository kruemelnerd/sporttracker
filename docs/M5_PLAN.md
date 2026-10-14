# Sporttracker M5 UI/UX Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Modernise the existing running dashboard into the approved calm-but-energetic Hybrid design while retaining every import, dashboard, detail, map, chart, lap, API, and data-integrity behaviour.

**Architecture:** Keep Vue views as route-level data owners and preserve the existing `api.ts`, `dashboard.ts`, and `activityDetail.ts` contracts. Add a small app shell, central CSS tokens, and only reusable presentational components; view-specific layouts remain in their existing views. The dashboard’s six-week visualisation uses accessible HTML/CSS; existing ECharts and Leaflet remain in the detail view and receive presentation/responsiveness improvements only.

**Tech Stack:** Vue 3 Composition API + TypeScript, Vue Router, CSS, ECharts 6, Leaflet 1.9, Vitest/Vue Test Utils, Playwright. No new dependency.

**Spec:** `design-system/sporttracker/MASTER.md`

## Global Constraints

- Preserve all backend endpoints, frontend DTOs, calculations, timestamps, FIT files, database schema, and persisted data exactly as they are.
- Implement only the approved Hybrid Running Analytics UI: calm data grid, Electric Mint, and local SVG activity icons; no social, coaching, AI, Garmin-sync, dark-mode, or training-planning feature.
- Use the central tokens defined in `design-system/sporttracker/MASTER.md`; no remote font or UI framework.
- Keep `SummaryView` and `/fixture-summary` functional for existing tests, but do not present it as product navigation.
- Use native semantic controls and one consistent local SVG icon treatment; never emoji for UI meaning.
- Support 375px, 768px, 1024px, and 1440px with no horizontal page scroll; preserve table overflow deliberately inside its labelled wrapper.
- Ensure visible keyboard focus, `role="status"` for loading, `role="alert"` for errors, and `prefers-reduced-motion` support.
- Run the relevant test cycle after every task; do not alter FIT fixtures or production data while validating UI work.

## Review Focus

1. **Missing optional metrics/sensors:** tiles, activity rows, charts, and laps must show `–`/their existing empty state rather than `NaN`, null, or a broken layout; covered in Tasks 3 and 5.
2. **Import outcome mixes:** imported, duplicate, skipped, invalid, failed, batch-limit, retry, and network-error states must remain distinguishable by text and accessible status treatment; covered in Task 4.
3. **Narrow layouts:** 375px navigation, KPI grid, long activity values, chart canvas, map, and split table must not clip or induce page-level horizontal scroll; covered in Tasks 2, 3, 5, and 6.
4. **Chart lifecycle:** route changes and container resizing must not leave stale ECharts/Leaflet instances or canvases; covered in Task 5.
5. **Keyboard/non-colour users:** current navigation, drop-zone/file selection, error states, icons, and focus must remain understandable without mouse, animation, or colour perception; covered in Tasks 2, 4, and 6.

---

## 1. Bestandsaufnahme

### Nachweisbare Befunde

| Bereich | Befund | Nachweis |
|---|---|---|
| Application shell/navigation | `App.vue` renders only a `main`, `h1`, and `RouterView`; there is no persistent product navigation, skip link, or route indication. | `frontend/src/App.vue:1-6` |
| Styling | Global tokens/base styles do not exist. Dashboard and detail duplicate container, metric-grid, card-border, and mobile rules in separate scoped blocks. | `DashboardView.vue:93-112`, `ActivityPlaceholderView.vue:173-185` |
| Dashboard hierarchy | Six metric cards, weeks, and activity rows have equal visual weight; the weeks are textual cards rather than a quick trend scan. | `DashboardView.vue:12-44` |
| Mobile activity list | A six-column desktop row switches to unlabeled two-column cells at 600px, which makes metric meaning harder to scan. | `DashboardView.vue:104-110` |
| Import consistency | Import copy is English, while dashboard/detail copy is primarily German; outcomes are plain text queue rows with no semantic visual grouping. | `ImportView.vue:3-31` |
| Detail hierarchy | The route is named `ActivityPlaceholderView` although it is the live detail page; metrics, map, all charts, and laps are one long undifferentiated column. | `ActivityPlaceholderView.vue:1-54` |
| Detail charts | ECharts has sampling, linked charts, and cleanup already, but no chart-container resize handler. | `ActivityPlaceholderView.vue:128-164` |
| Tests | Unit and E2E tests cover metrics, empty/error/import outcomes, charts, GPS, and laps; no mobile viewport or keyboard-navigation coverage is configured. | `frontend/src/**/*.test.ts`, `frontend/tests/e2e/*.spec.ts`, `playwright.config.ts:3-25` |

### Begründete, zu validierende Risiken

- **Responsive clipping:** no rendered viewport checks currently prove that tables, chart canvases, long labels, and maps remain usable at the target widths. M5 adds those checks before calling this a defect.
- **Resize rendering:** ECharts typically needs `resize()` after its container changes; current source does not register one. Validate by viewport changes in Playwright and add a `ResizeObserver` only if that test demonstrates it.
- **Dashboard rendering cost:** current six-week dashboard uses only text and DOM; adding an ECharts dashboard chart would be needless bundle/instance overhead. Use CSS bars instead.
- **Map tiles:** Leaflet still requests the configured OSM tile source. M5 does not change provider, cache, or GPS handling; those are deployment/privacy decisions outside UI polish.

## 2. Approved UI/UX concept

### Visual language

- **Direction:** approved Hybrid — a quiet, professional analytical foundation with Electric Mint as the continuous brand accent and small local SVG activity icons in the recent-activity list.
- **Avoid:** Strava-like orange, large gamified rings, decorative tabs, emojis, heavy shadows, count-up animations, and adding data that the API does not provide.
- **Content order:** orientation and action first; key metrics second; six-week trend third; recent activities fourth. On an activity page: identity/summary, key metrics, map, linked metric charts, then splits.
- **Navigation:** Dashboard and Import are first-class. The activity detail retains a contextual back link/breadcrumb; routes must be deep-linkable.

### Dashboard wireframe

```text
+--------------------------------------------------------------------+
| PACEBASE          Übersicht*   Aktivitäten   Import               |
+--------------------------------------------------------------------+
| Deine Entwicklung                    [ FIT-Dateien importieren ]  |
| Letzte sechs Wochen                                                |
| [Distanz] [Läufe] [Ø Pace] [Höhenmeter]                           |
|                                                                    |
| Wochenvolumen (Mint bars/line)       Läufe pro Woche (Mint bars)  |
|                                                                    |
| Letzte Aktivitäten                                                 |
| [SVG] Tempolauf       12. Okt · 10,2 km    Bestzeit · 4:58/km     |
| [SVG] Lockerer Lauf   10. Okt · 7,1 km               5:43/km     |
+--------------------------------------------------------------------+
```

- **Desktop:** four-to-six metric cards; two analysis panels; full-width linked activity rows.
- **Mobile:** compact navigation; two metric columns; analysis panels stack; each activity row retains icon, name/date/distance, and pace in a defined two-line arrangement.
- **Data:** retain existing six-week calculation, ordering, activity links, values, and test IDs. The visual weekly chart is derived only from `summary.weeks` and duplicates each bar’s value in accessible text.

### FIT-import wireframe

```text
+-------------------- Import FIT-Dateien ---------------------------+
| Zur Übersicht                                                     |
| [ Drop zone / Datei auswählen ]  .fit · mehrere Dateien möglich  |
| [ Ausgewählt: 3 ]                       [ Import starten ]        |
|                                                                    |
| ✓ imported.fit     Importiert                                     |
| ○ existing.fit     Bereits importiert                             |
| ! broken.fit       Ungültige FIT-Datei      [ Erneut versuchen ]  |
+--------------------------------------------------------------------+
```

- **Desktop/mobile:** one column; controls retain at least 44px targets; drop zone is keyboard operable; queue rows never depend only on colour.
- **States:** preserve exact backend outcome semantics. Translate visible German copy consistently but do not change status types/codes or retry flow.
- **Feedback:** selection count uses polite live text, batch and item errors use alerts, and processing uses a stable row/status treatment rather than layout movement.

### Activity-detail wireframe

```text
< Zur Übersicht
12. Oktober 2026 · Tempolauf              10,2 km · 50:48 · 4:59/km
[Ø HF] [Max HF] [Aufstieg] [Abstieg] [Kadenz] [Bewegungszeit] ...

[ Laufstrecke / Leaflet map                                  ]

Trainingsverlauf
[ Pace (linked zoom)                                         ]
[ Herzfrequenz (linked zoom)                                 ]
[ Höhenprofil (linked zoom)                                  ]
[ Kadenz, wenn vorhanden                                     ]

Runden
[ responsive, horizontally scrollable semantic table         ]
```

- **Desktop:** constrained readable content width; metrics wrap cleanly; map and charts are full content width; laps are a table.
- **Mobile:** two metric columns; map 18rem minimum; charts 15rem minimum; chart controls/labels remain readable; table wrapper scrolls deliberately with an accessible label.
- **Data/visualisation:** retain existing map GPS logic, `chartSeries` sampling and null gaps, inverted pace axis, ECharts `connect`, zoom, tooltip units, optional cadence, recorded/derived laps, and empty states.

## 3. Planned file structure

| File | Responsibility |
|---|---|
| `frontend/src/styles/tokens.css` | CSS custom properties from the design-system master plus reduced-motion rule. |
| `frontend/src/styles/base.css` | global reset, type, focus, container, and screen-reader-only utilities. |
| `frontend/src/main.ts` | imports the two global stylesheets once. |
| `frontend/src/App.vue` | app shell, skip link, persistent product navigation, and router view. |
| `frontend/src/components/AppNavigation.vue` | responsive dashboard/import navigation with current-route semantics. |
| `frontend/src/components/MetricCard.vue` | presentational metric tile (`label`, `value`, optional `emphasis`). |
| `frontend/src/components/ActivityTypeIcon.vue` | local decorative/accessibly labelled SVG activity icon. |
| `frontend/src/components/ImportOutcomeBadge.vue` | maps existing `ImportOutcome['status']` to icon/text/semantic class without changing API data. |
| `frontend/src/views/DashboardView.vue` | approved dashboard layout, accessible CSS week bars, and activity-row hierarchy. |
| `frontend/src/views/ImportView.vue` | German import hierarchy and status treatment, preserving upload behaviour. |
| `frontend/src/views/ActivityPlaceholderView.vue` | renamed in a separate mechanical task to `ActivityDetailView.vue`; polished detail layout and verified resize handling. |
| `frontend/src/router.ts` | uses the renamed detail component without changing route paths/names. |
| Existing unit/E2E test files | assert retained behaviour plus new semantics, responsive layout, and keyboard/reduced-motion contracts. |

## 4. Implementation plan

### Task 1: Establish global design tokens and the application shell

**Files:**
- Create: `frontend/src/styles/tokens.css`
- Create: `frontend/src/styles/base.css`
- Create: `frontend/src/components/AppNavigation.vue`
- Modify: `frontend/src/main.ts`, `frontend/src/App.vue`
- Test: `frontend/src/App.test.ts` (create)

**Interfaces:**
- Produces: global CSS variables from the master; `AppNavigation` has no props and derives the active route through `useRoute()`.
- Consumes: existing route names `dashboard` and `import`.

- [ ] Write failing `App.test.ts` assertions for a `main` landmark, skip link target, dashboard/import navigation, and `aria-current="page"` on the active dashboard link.
- [ ] Run `npm run test:unit -- App.test.ts`; verify the shell/navigation assertions fail.
- [ ] Add token/base styles and `AppNavigation.vue`; update `main.ts` and `App.vue` to render one shell without changing routes or API calls.
- [ ] Add responsive navigation and `prefers-reduced-motion` rules; verify the 375px layout has no page-level horizontal overflow manually or in the later E2E task.
- [ ] Run `npm run typecheck && npm run test:unit -- App.test.ts`; expect pass.
- [ ] Commit only the task files: `feat: add app shell and design tokens`.

### Task 2: Add the smallest shared display primitives

**Files:**
- Create: `frontend/src/components/MetricCard.vue`
- Create: `frontend/src/components/ActivityTypeIcon.vue`
- Create: `frontend/src/components/ImportOutcomeBadge.vue`
- Test: `frontend/src/components/MetricCard.test.ts`, `frontend/src/components/ActivityTypeIcon.test.ts`, `frontend/src/components/ImportOutcomeBadge.test.ts`

**Interfaces:**
- `MetricCard`: props `{ label: string; value: string; emphasis?: boolean }`.
- `ActivityTypeIcon`: props `{ subSport: string | null }`; renders inline SVG, with a decorative accessible contract documented by its test.
- `ImportOutcomeBadge`: prop `{ status: ImportOutcome['status'] }`; renders status-specific German text and a semantic class while leaving `status` unchanged.

- [ ] Write parameterised failing tests for all five import statuses, null/known activity types, and metric accessible label/value output.
- [ ] Run the three focused Vitest files; verify failures before component implementation.
- [ ] Implement the three presentational components using local SVG and CSS tokens; do not add an icon package.
- [ ] Run the focused tests and `npm run typecheck`; expect pass.
- [ ] Commit only primitives/tests: `feat: add reusable M5 display primitives`.

### Task 3: Redesign the six-week dashboard without changing calculations

**Files:**
- Modify: `frontend/src/views/DashboardView.vue`
- Modify: `frontend/src/views/DashboardView.test.ts`
- Modify: `frontend/tests/e2e/dashboard.spec.ts`

**Interfaces:**
- Consumes unchanged `getActivities`, `summarizeActivities`, and `Activity` fields.
- Produces unchanged activity links and existing dashboard test IDs; may add stable IDs/labels for the week chart and activity metadata.

- [ ] Add failing unit assertions that the loading/error/empty states retain their roles, metric values are rendered through `MetricCard`, each six-week visual bar exposes its text value, and activity rows retain a named link and icon-plus-text.
- [ ] Run `npm run test:unit -- DashboardView.test.ts`; verify failure.
- [ ] Rebuild markup/styles into the approved hierarchy: header/action, metric cards, accessible six-week CSS chart, and semantically labelled recent activity rows. Use `ActivityTypeIcon`; retain all calculation and formatter code unless a mechanical extraction is tested.
- [ ] Add Playwright assertions for meaningful desktop layout landmarks and the 375px activity row/no-horizontal-page-scroll contract.
- [ ] Run focused Vitest and `npx playwright test tests/e2e/dashboard.spec.ts`; expect existing numerical, link, empty-state, and new responsive assertions to pass.
- [ ] Commit only dashboard/view tests: `feat: redesign six-week dashboard`.

### Task 4: Redesign FIT import feedback while preserving the queue contract

**Files:**
- Modify: `frontend/src/views/ImportView.vue`
- Modify: `frontend/src/views/ImportView.test.ts`
- Modify: `frontend/tests/e2e/fit-upload.spec.ts`, `frontend/tests/e2e/import-tracer.spec.ts`

**Interfaces:**
- Consumes unchanged `importFits`, `ImportBatchError`, `ImportOutcome`, queue state union, and data test IDs.
- Produces the same selected/upload/retry outcomes with German presentation copy and `ImportOutcomeBadge`.

- [ ] Extend tests first for the labelled file control/drop-zone keyboard activation, German status message per existing outcome, live selection count, alert roles for batch errors, and retry stability.
- [ ] Run `npm run test:unit -- ImportView.test.ts`; verify expected failures.
- [ ] Apply the one-column import layout, compact guidance, outcome badges, queue rows, and button states. Do not change validation, FormData construction, endpoint, order, or retry logic.
- [ ] Update E2E expectations to use accessible names rather than brittle copy where copy intentionally changes; retain tests that prove duplicate/mixed-batch behaviour.
- [ ] Run focused Vitest and both import E2E specs; expect pass.
- [ ] Commit only import view/tests: `feat: polish FIT import feedback`.

### Task 5: Polish activity detail and validate visual lifecycles

**Files:**
- Move: `frontend/src/views/ActivityPlaceholderView.vue` → `frontend/src/views/ActivityDetailView.vue`
- Modify: `frontend/src/router.ts`, `frontend/src/activityDetail.test.ts`, `frontend/tests/e2e/dashboard.spec.ts`
- Create: `frontend/src/views/ActivityDetailView.test.ts` (migrate/adapt any view-level tests if introduced)

**Interfaces:**
- Consumes unchanged `getActivity`, `ActivityDetail`, Leaflet logic, `chartSeries`, `lapRows`, ECharts group connection, and route `/activities/:id`.
- Produces the same route name `activity`, route path, test IDs, chart ARIA labels, map/lap behaviours, and cleanup guarantees.

- [ ] Write/migrate failing view tests for titled metric groups, no-GPS/no-record/no-lap states, optional cadence, and an accessible labelled table wrapper.
- [ ] Run focused unit tests; verify failures.
- [ ] Perform the mechanical component rename and router import update, then apply the detail hierarchy/tokens. Keep map and chart data paths untouched.
- [ ] Add a single `ResizeObserver` lifecycle only if the responsive E2E test proves chart resizing is needed; it must observe the chart container, call `resize()` for live instances, and disconnect before disposal. Do not introduce resize polling or another library.
- [ ] Add Playwright coverage at 375px and 1024px for map/chart/table visibility, route navigation, and retained chart/map DOM; retain existing GPS and lap assertions.
- [ ] Run `npm run test:unit`, then `npx playwright test tests/e2e/dashboard.spec.ts`; expect pass.
- [ ] Commit only detail/router/tests: `feat: modernize activity detail presentation`.

### Task 6: Run the M5 regression and accessibility/responsive gates

**Files:**
- Modify only if test gaps require it: `frontend/tests/e2e/*.spec.ts`, `frontend/playwright.config.ts`
- Do not change production code in this task unless a test identifies a real regression; fix it in the owning task/commit instead.

**Interfaces:**
- Consumes every route and test contract created above.
- Produces objective M5 verification evidence.

- [ ] Add Playwright projects or explicit viewport loops for 375, 768, 1024, and 1440px; do not change the existing backend setup or fixture data.
- [ ] Add keyboard tests for skip link/navigation, import activation, visible focus, and error/empty-state roles; test reduced motion by emulating it for the no-animation contract.
- [ ] Run `npm run typecheck`, `npm run test:unit`, `npm run build`, `npx playwright test`, and `cd ../backend && ./gradlew test`.
- [ ] Manually smoke-test dashboard → import → dashboard → activity detail in each target viewport and record any unresolved visual issue in the PR, not in FIT data.
- [ ] Commit only test/config changes when present: `test: cover M5 responsive accessibility flows`.

## 5. Atomic commit order

### Planning branch commits (this planning phase)

1. `docs: add M5 design system` — `design-system/sporttracker/MASTER.md` only.
2. `docs: add M5 implementation plan` — `docs/M5_PLAN.md` only.

### Implementation commits (after explicit plan approval)

1. `feat: add app shell and design tokens`
2. `feat: add reusable M5 display primitives`
3. `feat: redesign six-week dashboard`
4. `feat: polish FIT import feedback`
5. `feat: modernize activity detail presentation`
6. `test: cover M5 responsive accessibility flows` (only if it contains independent test/config changes)

Stage exact paths for every commit; never use `git add .`, never stage unrelated worktree files, and push each successful commit immediately. The existing current-worktree modifications to `.opencode/opencode.json`, `.opencode/plugins/graphify.js`, and `AGENTS.md` are outside this branch/worktree and must never be touched by M5.

## 6. Test strategy and completion criteria

| Layer | Required evidence |
|---|---|
| Vue unit | Existing dashboard calculations/formatters, import selection/batch/retry outcomes, missing optional detail metrics, activity icon/badge semantics, shell/navigation roles. |
| Type/build | `npm run typecheck` and `npm run build` pass with no new dependency. |
| Backend regression | `./gradlew test` passes unchanged; no migration, entity, controller, or FIT fixture changes are allowed. |
| E2E | Dashboard/import/detail journeys retain numerical values, duplicate handling, GPS/non-GPS, charts, and laps. |
| Responsive | 375, 768, 1024, 1440px: no page-level horizontal overflow; content is readable; table overflow is intentional and labelled. |
| Accessibility | Keyboard navigation/focus, current route, labelled controls, `status`/`alert`, text-plus-colour statuses, SVG icon contract, and reduced-motion rule. |

M5 is complete only when all commands in Task 6 pass, all existing test IDs/route paths/API DTOs still work, all target viewports meet the documented behaviour, and a manual smoke pass confirms that no FIT data was edited or re-imported as part of UI work.

## 7. Risks and open decisions

| Item | Decision / mitigation |
|---|---|
| Current worktree has user changes | Work occurs in isolated `milestone/05-mvp-polish`; do not rebase, reset, or stage the original worktree. |
| Worktree creation initially produced detached HEAD | Corrected before committing by creating local branch `milestone/05-mvp-polish`; it tracks `origin/milestone/05-mvp-polish` after first push. |
| Generated base recommendation did not match review | `ui-ux-pro-max` generated a blue/amber/Fira system; the persisted master explicitly records the reviewed Electric-Mint Hybrid override. |
| ECharts responsiveness | Diagnose with viewport tests first; add `ResizeObserver` only when evidence shows a need. |
| External map tiles | Existing OSM tile request remains unchanged; provider/privacy hardening is not silently included in M5. |
| Icon source | Use minimal local inline SVGs in the shared component; avoid adding an icon package for this small surface. |
| Activity labels | Existing FIT `subSport` is inconsistent/free-form enough that icon mapping must gracefully fall back to a generic running icon and visible text. |

## 8. Planning-phase Git status

- Starting branch: `main` tracking `origin/main`; original worktree had uncommitted changes in `.opencode/opencode.json`, `.opencode/plugins/graphify.js`, and `AGENTS.md`.
- Planning worktree: isolated at `/home/philipp/.local/share/opencode/worktree/03dc73/milestone/05-mvp-polish`.
- Branch: `milestone/05-mvp-polish`.
- Remote: `origin` → `git@github.com:kruemelnerd/sporttracker.git` (fetch/push).
- This plan does not merge to `main` and stops before implementation.
