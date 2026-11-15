# M9.5 Running Dynamics and Intervals Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Add data-backed FIT running-dynamics charts and structured workout intervals to activity detail.

**Architecture:** Extend the existing Garmin FIT adapter and relational persistence, then expose the added fields through the existing activity-detail API. Keep ECharts and make the time/distance axis, workout bands, and zoom shared across charts; preserve ordinary laps and pauses as separate concepts.

**Tech Stack:** Java 25, Garmin FIT SDK 21.221.0, Spring JDBC, PostgreSQL/Flyway, Vue 3/TypeScript, ECharts 6, Vitest, Playwright.

**Spec:** `docs/superpowers/specs/2026-11-15-m9-5-running-dynamics-intervals-design.md`

## Global Constraints

- Preserve local-first FIT/GPS privacy; do not transmit fixture or personal activity data.
- Never mass re-import; use additive, duplicate-safe update behavior only when a FIT is explicitly supplied again.
- Do not overwrite existing Garmin enrichment or non-null stored values with missing FIT fields.
- Do not classify walking/running from speed thresholds.
- Keep existing chart, lap, map, dashboard, and training-plan behavior.
- No new dependency or automatic background service.

## Review Focus

- FIT sentinel/null values and unusual/out-of-range developer fields: ignored, not converted to zero (adapter tests).
- Smart-recording gaps, pause timestamps and monotonic-distance edge cases: do not interpolate sensor values or move boundaries (series/interval tests).
- Workout repeat blocks, missing step indices and ordinary/manual/automatic laps: never promote unlinked laps to workout phases (parser tests).
- Duplicate or changed-byte re-import: do not duplicate records/laps or erase enrichment (integration tests).
- Long records and mobile chart layout: bounded render data and usable responsive tables/charts (unit/E2E tests).

---

### Task 1: FIT decoding and interval metadata

**Files:**
- Modify: `backend/src/main/java/local/runningdashboard/GarminFitFixtureAdapter.java`
- Test: `backend/src/test/java/local/runningdashboard/GarminFitFixtureAdapterTests.java`
- Test: `backend/src/test/java/local/runningdashboard/FitDataFoundationTests.java`

**Interfaces:**
- Consumes: Garmin FIT SDK message getters.
- Produces: expanded `DecodedRecord`, `DecodedLap`, `DecodedActivity` with native and verified developer metrics, WorkoutSteps and timer events.

- [x] Test native Record-field mapping, SDK scaling, sentinels, optional values and cadence preservation.
- [x] Test developer-field matching by native override/name plus units; reject unknown/incompatible fields.
- [x] Test workout/workout-step/event decoding, lap `wkt_step_index`, `repetition_num`, lap trigger and timer start/stop.
- [x] Run targeted Gradle tests and verify the failure is expected before implementation.
- [x] Implement decoder listeners and only the validated mappings; preserve record ordering and UTC timestamps.
- [x] Re-run targeted tests.

### Task 2: Additive persistence and explicit FIT refresh

**Files:**
- Create: `backend/src/main/resources/db/migration/V14__running_dynamics_intervals.sql`
- Modify: `backend/src/main/java/local/runningdashboard/ImportService.java`
- Modify: `backend/src/main/java/local/runningdashboard/ActivityController.java`
- Test: `backend/src/test/java/local/runningdashboard/ImportControllerTests.java`
- Test: `backend/src/test/java/local/runningdashboard/ImportIdentityTests.java`

**Interfaces:**
- Consumes: expanded decoded records, laps, workout steps and timer events from Task 1.
- Produces: additive tables/columns and transactional detail reads/writes; existing duplicate identity remains stable.

- [x] Add assertions for new fields, interval/link persistence, timer pauses, re-import merge, and preservation of Garmin enrichment.
- [x] Verify the tests fail against the pre-change API/schema.
- [x] Add only required nullable fields/tables/indexes; backfill nothing and keep foreign keys cascading from activity.
- [x] Persist new FIT rows/values transactionally; for an explicit duplicate re-upload, update only missing/new FIT-derived values and never duplicate laps/records or clear Garmin columns.
- [x] Verify migration and tests on the isolated test DB.

### Task 3: API and chart-series preparation

**Files:**
- Modify: `backend/src/main/java/local/runningdashboard/ActivityController.java`
- Modify: `frontend/src/api.ts`
- Modify: `frontend/src/activityDetail.ts`
- Test: `frontend/src/activityDetail.test.ts`

**Interfaces:**
- Consumes: Task 2 activity-detail records, workout phases and timer pauses.
- Produces: typed API fields and bounded chart series on shared elapsed-time/distance coordinates, preserving gaps and interval boundaries.

- [x] Test axis coordinate preparation, unit conversions, null gaps, monotonic-distance safeguards, and retention of interval boundaries when reducing long series.
- [x] Verify frontend tests fail before implementation.
- [x] Add series only for metrics with measured points; use FIT speed for both speed and pace and convert cadence exactly once.
- [x] Re-run unit tests and typecheck.

### Task 4: Activity-detail charts and interval table

**Files:**
- Modify: `frontend/src/views/ActivityDetailView.vue`
- Modify: `frontend/src/views/ActivityDetailView.test.ts`
- Modify: `frontend/tests/e2e/dashboard.spec.ts`

**Interfaces:**
- Consumes: Task 3 chart series and interval API data.
- Produces: conditional metric charts, clear workout phases, distinct laps/pauses, aligned `markArea` bands, connected ECharts interactions.

- [x] Add UI tests for chart presence/absence, interval/round distinction, phase styling, tooltip units and pause rendering.
- [x] Verify tests fail for the missing UI.
- [x] Add concise responsive interval table and metric charts using existing ECharts, synchronized x-axis/dataZoom and interval areas.
- [x] Add E2E coverage for mobile widths and a long activity; preserve map behavior.
- [x] Run targeted frontend tests and production build.

### Task 5: Full verification and delivery

**Files:**
- No product files beyond Tasks 1–4.

- [x] Run `./scripts/test-all.sh`.
- [x] Run `./gradlew bootJar`.
- [x] Run `git diff --check`.
- [x] Review changed files and verify no FIT/private activity data or credentials were added.
- [x] Commit and push the feature branch, check for an existing PR, and create a PR targeting `main`; do not merge.
