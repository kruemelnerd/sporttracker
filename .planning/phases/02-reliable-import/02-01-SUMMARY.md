---
phase: 02-reliable-import
plan: 01
subsystem: import
tags: [spring-boot, postgresql, jdbc, garmin-fit, vue, playwright]
requires:
  - phase: 01-foundation-and-one-real-fit
    provides: Garmin FIT adapter, typed frontend API boundary, and committed running fixture
provides:
  - Single-file FIT import endpoint with normalized PostgreSQL activity aggregate
  - Browser import route and Compose-backed tracer smoke test
  - Semantic persistence coverage for UTC, GPS, laps, and nullable sensor values
affects: [reliable-import, dashboard, activity-detail]
actuals:
  tokens: 7245
  tasks: 2
  commits: 4
plan_head_before: 78f83bc1b21b96580d99b87e0b2de3f3fa1183df
tech-stack:
  added: []
  patterns: [hash-derived original retention, explicit JDBC timestamp binding, semantic import integration tests]
key-files:
  created: [backend/src/main/resources/db/migration/V2__activity_import.sql, backend/src/main/java/local/runningdashboard/ImportController.java, frontend/src/views/ImportView.vue]
  modified: [backend/src/main/java/local/runningdashboard/ImportService.java, backend/src/test/java/local/runningdashboard/ImportControllerTests.java, frontend/scripts/phase2-import-smoke.sh]
key-decisions:
  - "Bind Java Instants as SQL Timestamps at the JDBC boundary to preserve UTC timestamptz values."
  - "Use the committed real FIT fixture as the browser tracer and persistence-contract fixture."
patterns-established:
  - "Import tests remove only the fixture hash aggregate, leaving unrelated local activities untouched."
requirements-completed: [IMPT-01, DATA-01, DATA-02, DATA-03, DATA-04, DATA-05, DATA-06]
coverage:
  - id: D1
    description: Browser FIT upload persists an imported running activity and retains its hash-derived original locally.
    requirement: IMPT-01
    verification:
      - kind: e2e
        ref: frontend/scripts/phase2-import-smoke.sh --tracer
        status: pass
    human_judgment: false
  - id: D2
    description: Imported aggregate retains UTC, records, laps, coordinate precision, and nullable sensor values.
    requirement: DATA-01
    verification:
      - kind: integration
        ref: backend/src/test/java/local/runningdashboard/ImportControllerTests.java#importsOneFitFileWithCanonicalPersistedValues
        status: pass
    human_judgment: false
duration: 25min
completed: 2026-09-22
status: complete
---

# Phase 02 Plan 01: One-file FIT import tracer Summary

**A local Vue upload route now imports one Garmin FIT run into normalized PostgreSQL rows and a post-commit hash-addressed original.**

## Performance

- **Duration:** 25min
- **Started:** 2026-09-22T09:09:51Z
- **Completed:** 2026-09-22T09:34:51Z
- **Tasks:** 2/2
- **Files modified:** 11

## Accomplishments

- Added the end-to-end `/import` → `POST /api/imports` → Garmin decoder → JDBC aggregate path.
- Persisted UTC timestamps, sparse nullable records, ordered laps, and precise GPS coordinates without FIT bytes in PostgreSQL.
- Proved the slice with the Compose-backed Playwright tracer and database-backed semantic integration tests.

## Task Commits

1. **Task 1: Import one selected running FIT through UI, API, decoder, and PostgreSQL** — `b1d3f79`, `2fc6538`, `95ed534`
2. **Task 2: Lock the tracer's canonical persistence and transport contract with semantic tests** — `4496044`

## Files Created/Modified

- `backend/src/main/java/local/runningdashboard/ImportService.java` — binds `Instant` values safely for PostgreSQL JDBC persistence.
- `backend/src/test/java/local/runningdashboard/ImportControllerTests.java` — verifies stored summary, rows, laps, UTC, nullable fields, and coordinate conversion.
- `frontend/scripts/phase2-import-smoke.sh` — starts an isolated Gradle backend process for the tracer.

## Decisions Made

- Use `Timestamp.from(Instant)` at JDBC writes because PostgreSQL JDBC cannot infer an `Instant` parameter type.
- Preserve a measured zero cadence separately from absent altitude sensor data in the semantic fixture assertions.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Fixed PostgreSQL binding of Java Instants**
- **Found during:** Task 1
- **Issue:** The first real tracer upload returned `FAILED` because JDBC could not infer the SQL type for `Instant` parameters.
- **Fix:** Bound activity and record timestamps as `Timestamp` values while retaining UTC instants in the decoded model.
- **Files modified:** `backend/src/main/java/local/runningdashboard/ImportService.java`
- **Verification:** `frontend/scripts/phase2-import-smoke.sh --tracer` passed.
- **Committed in:** `95ed534`

**2. [Rule 3 - Blocking] Made the local Gradle tracer startup reliable**
- **Found during:** Task 1
- **Issue:** This environment's persistent Gradle daemon could not spawn the backend JVM.
- **Fix:** The smoke script runs the backend with a single-use daemon and the JDK VFORK launcher setting.
- **Files modified:** `frontend/scripts/phase2-import-smoke.sh`
- **Verification:** tracer passed end-to-end.
- **Committed in:** `95ed534`

## Verification

- `GRADLE_OPTS='-Djdk.lang.Process.launchMechanism=VFORK' ./gradlew --no-daemon test --tests '*ImportControllerTests'` — passed.
- `frontend/scripts/phase2-import-smoke.sh --tracer` — passed.

## Known Stubs

None.

## Next Phase Readiness

The one-file running import tracer is ready for batch outcomes, duplicate handling, and non-running classifications.

## Self-Check: PASSED

- Required import source, migration, browser route, smoke script, and semantic test exist.
- Task commits `b1d3f79`, `2fc6538`, `95ed534`, and `4496044` exist in git history.
