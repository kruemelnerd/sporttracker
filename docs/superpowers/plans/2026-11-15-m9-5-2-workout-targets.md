# M9.5.2 Workout Targets and Interactive Intervals Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Format FIT workout goals as runner-friendly pace/units and link table selections, chart overlays, and synchronized chart zoom.

**Architecture:** Reuse the M9.5.1 activity-detail API and ECharts charts. Add pure target/interval/zoom helpers, then render their outputs in the existing view; chart clicks use ECharts pixel-to-axis conversion and selection uses stable executed-interval IDs.

**Tech Stack:** Vue 3, TypeScript, ECharts 6, Vitest, Playwright.

**Spec:** User-provided “M9.5.2 – Trainingsziele und interaktive Intervalle” brief.

## Global Constraints

- Do not add dependencies or backend/database changes unless the current target API is proven insufficient.
- Do not re-scale decoded FIT values in the frontend; speed target values in the API are meters per second.
- Never invent zone boundaries; chart target marks require resolvable absolute values.
- Keep phase mark areas, measured lines, existing chart zoom, and no-workout activities working.
- Keep selection stable across table sorting and keep repeated executions distinct.
- Do not modify maps, Garmin sync, or training-plan management.
- Finish by running `./scripts/test-all.sh`, `cd backend && ./gradlew bootJar`, and `git diff --check`; do not merge or deploy.

## Review Focus

- Reversed, zero, non-finite, or single-sided speed targets: format only valid positive speeds and sort pace bounds ascending.
- FIT zones and relative HR/power targets: show zone/relative labels but do not draw absolute chart lines without known bounds.
- Repeated step executions and multi-lap phases: use stable IDs and group only contiguous laps with reliable execution identity.
- Intervals touching at a shared x coordinate: select the later interval at its start; never let target marks bridge an interval boundary.
- Zoom at activity start/end and under manual sorting: clamp the margin to the activity extent and resolve selection by interval ID.

---

### Task 1: Normalize targets and executed-interval identity

**Files:**
- Modify: `frontend/src/workoutIntervals.ts`
- Test: `frontend/src/workoutIntervals.test.ts`

**Interfaces:**
- Consumes: existing `WorkoutStep` target fields (`targetType`, `targetZone`, `targetLow`, `targetHigh`, `targetUnit`, duration type/value) and lap links/repetition fields.
- Produces: `normalizeWorkoutTarget(step)` returning a display label plus a chart metric and absolute numeric bounds when resolvable; executed intervals with stable `id`, complete lap boundary lists, and planned FIT duration/distance.

- [ ] Test `3.077–3.175 m/s` formats exactly as `Pace: 5:15–5:25 min/km`; test single, reversed, zero, non-finite, and one-sided values.
- [ ] Test HR, power, cadence, FIT zones without known boundaries, duration (`min:ss`/`h:mm:ss`), and planned distance formatting.
- [ ] Test repeated executions stay distinct and contiguous laps group only when step/repetition metadata proves one execution.
- [ ] Run the focused Vitest file and confirm expected failures.
- [ ] Implement target normalization without re-scaling API values; return no chart bounds for zones, relative units, unknown types, or invalid values.
- [ ] Re-run focused tests.

### Task 2: Build interval-scoped chart marks and zoom extents

**Files:**
- Modify: `frontend/src/workoutIntervals.ts`
- Test: `frontend/src/workoutIntervals.test.ts`

**Interfaces:**
- Consumes: Task 1 normalized targets and stable interval IDs.
- Produces: `workoutTargetMarks(intervals, axis, metric)` returning interval-limited ranges/lines, and `workoutZoomExtent(interval, axis, domain)` returning a clamped start/end with a small interval-relative margin.

- [ ] Test per-interval pace/HR/power/cadence marks on elapsed-time and distance axes, including mixed targets, missing values, unresolved zones, and no cross-interval connections.
- [ ] Test zoom margins, boundary clamping, and null bounds.
- [ ] Run focused tests and confirm expected failures.
- [ ] Implement marks as existing ECharts markArea/markLine coordinate pairs; return only valid absolute targets.
- [ ] Re-run focused tests.

### Task 3: Wire accessible selection, chart tooltips, and synchronized zoom

**Files:**
- Modify: `frontend/src/views/ActivityDetailView.vue`
- Test: `frontend/src/views/ActivityDetailView.test.ts`
- Test: `frontend/tests/e2e/dashboard.spec.ts`

**Interfaces:**
- Consumes: Task 1 target formatter/interval IDs and Task 2 marks/zoom extents.
- Produces: target legends and tooltip values, keyboard/clickable table selection, chart-coordinate selection, selected-region highlight, synchronized chart zoom, and “Gesamtes Training anzeigen”.

- [ ] Test the table’s target labels, selection/pressed state, sort-independent stable selection, and reset behavior.
- [ ] Add E2E assertions for chart click selecting the corresponding row, synchronized zoom, and full-training reset.
- [ ] Run focused frontend tests and confirm expected failures.
- [ ] Render dashed target limits and translucent per-interval bands only on Pace, HR, Power, and Cadence charts; preserve phase bands and measured series.
- [ ] Use chart-grid pixel-to-x conversion for chart selection; dispatch zoom to every chart and keep table selection accessible by keyboard.
- [ ] Re-run component tests, E2E coverage, typecheck, and production build.

### Task 4: Full verification and delivery

**Files:**
- No product files beyond Tasks 1–3.

- [ ] Run `./scripts/test-all.sh`.
- [ ] Run `cd backend && ./gradlew bootJar`.
- [ ] Run `git diff --check` and review the full staged diff for secrets or activity data.
- [ ] Commit and push `feat/m9-5-2-workout-targets`; check for an existing PR and create one against `main`, without merging.
- [ ] Check GitHub Actions status.
- [ ] Start the final dev server with the requested Garmin integration and deletion-management flags; verify health and report any automatic sync result without exposing credentials or personal activity details.
