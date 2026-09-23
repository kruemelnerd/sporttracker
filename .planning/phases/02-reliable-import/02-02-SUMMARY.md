---
phase: 02-reliable-import
plan: 02
subsystem: import
tags: [spring-boot, jdbc, flyway, garmin-fit, multipart, offline-tests]
requires:
  - phase: 02-reliable-import
    plan: 01
    provides: one-file FIT decoding and normalized persistence tracer
provides:
  - bounded multi-file FIT imports with explicit batch and per-file outcomes
  - hash and session/start-time duplicate protection with database backstops
  - isolated local staging and regression coverage for storage boundaries
affects: [import-ui, dashboard-aggregation, activity-detail]
tech-stack:
  added: []
  patterns: [bounded streamed staging, REQUIRES_NEW per-file transaction, hash-addressed original retention]
key-files:
  created: [backend/src/main/java/local/runningdashboard/ImportProperties.java, backend/src/main/java/local/runningdashboard/ImportExceptionHandler.java, backend/src/main/resources/db/migration/V3__import_identity_backstops.sql]
  modified: [backend/src/main/java/local/runningdashboard/ImportService.java, backend/src/main/java/local/runningdashboard/ImportController.java, backend/src/test/java/local/runningdashboard/ImportControllerTests.java]
key-decisions:
  - "Use 10 files, 10 MiB per file, and 100 MiB per request as bound defaults."
  - "Treat hash equality or decoded running sport plus exact UTC start as duplicates; only record a re-export hash as import metadata."
  - "Keep FIT bytes in a local hash-derived file only after normalized rows commit."
metrics:
  duration: 22h 50m
  completed: 2026-09-23
  tasks: 3
commits: 3
plan_head_before: b93d82909b008eea55838bc42db198b181b62301
actuals:
  tokens: 6701
  tasks: 3
  commits: 3
status: complete
---

# Phase 02 Plan 02: Reliable Import Outcomes Summary

**The FIT pipeline now bounds local batches, isolates each file, and returns actionable duplicate, invalid, skipped, or imported outcomes.**

## Accomplishments

- Added request/count/file limits, typed `BATCH_COUNT_EXCEEDED` and `REQUEST_TOO_LARGE` batch errors, and streamed SHA-256 staging below the local data root.
- Added duplicate handling for identical hashes and decoded running sessions with the same UTC start; a database uniqueness backstop protects races.
- Kept successful neighbors committed when a malformed file fails, with post-commit-only original retention and no raw binary database column.
- Expanded local regression tests and documented every fixture and deterministic decoded-model seam.

## Task Commits

1. **Task 1: Make duplicate, non-run, malformed, and multi-candidate outcomes explicit per file** — `16d794b`
2. **Task 2: Enforce conservative limits and isolated durable transactions** — `0d9492a`
3. **Task 3: Complete the offline FIT fixture matrix and regression suite** — `2309de9`

## Verification

- `GRADLE_OPTS='-Djdk.lang.Process.launchMechanism=VFORK' ./gradlew --no-daemon test --tests '*ImportControllerTests'` — passed.
- `GRADLE_OPTS='-Djdk.lang.Process.launchMechanism=VFORK' ./gradlew --no-daemon test` — passed (11 tests).

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Kept the no-database application-context test viable**
- **Found during:** Task 3
- **Issue:** The existing context smoke test intentionally excludes JDBC, so eagerly creating the import controller/service made the complete backend suite fail.
- **Fix:** Made import components conditional on the import feature setting and disabled it in that no-database smoke context.
- **Files modified:** `ImportService.java`, `ImportController.java`, `application.yaml`, `RunningDashboardApplicationTests.java`
- **Commit:** `2309de9`

**2. [Rule 2 - Security] Added a new additive migration instead of changing an applied Flyway migration**
- **Found during:** Task 1
- **Issue:** Altering the already-applied V2 migration would break Flyway checksums for existing local databases.
- **Fix:** Added V3 for the import identity uniqueness backstops.
- **Files modified:** `backend/src/main/resources/db/migration/V3__import_identity_backstops.sql`
- **Commit:** `16d794b`

## Known Stubs

None.

## Self-Check: PASSED

- Required import source, limits, additive migration, fixture documentation, and regression tests exist.
- Task commits `16d794b`, `0d9492a`, and `2309de9` exist in git history.
