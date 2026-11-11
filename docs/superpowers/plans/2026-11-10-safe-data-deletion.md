# Sichere Datenverwaltung Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Provide previewed, explicitly confirmed and protected deletion of provider-linked activities, all activities, or all domain data, while preserving user data, file safety, and persistent sync pauses.

**Architecture:** Additive Flyway state records provenance, provider pauses, and durable file cleanup jobs. One shared write coordinator serializes imports, complete sync runs and deletes. A separate deletion service computes a current preview, validates a one-use confirmation against a state fingerprint, performs database work transactionally, then resumes idempotent file cleanup after commit. HTTP routes are opt-in and absent from the production `container` profile; the Vue import view consumes only the backend status/API.

**Tech Stack:** Java 21, Spring Boot/JDBC/transactions, PostgreSQL/Flyway, Java NIO, Vue 3/TypeScript/Vitest, existing Playwright and Docker Compose test pipeline.

**Spec:** `docs/superpowers/specs/2026-11-10-data-management-safe-deletion-design.md`

## Global Constraints

- Default `DATA_DELETION_ENABLED=false`; the production `container` profile must not register destructive routes.
- Never use `DROP DATABASE`, `TRUNCATE`, Flyway clean, or Docker volume deletion against user data.
- New migration only; preserve current activity identity, FK relationships, plans, Flyway history and config/secrets.
- Existing/manual/unknown/shared-source activities are protected by provider-specific deletion.
- All source writes, manual FIT batches, sync runs, and deletion share one exclusive coordinator.
- Persisted sync pauses are effective before startup auto-sync triggers; reset actions leave automatic sync paused.
- FIT files are removed only after DB commit, through a persistent idempotent queue constrained to the configured data directory.
- Never follow symlinks or delete paths outside the configured data directory.
- Destructive tests use only the verified isolated E2E/test DB and synthetic FIT fixtures.
- Preserve the original checkout’s `.opencode/` modifications; stage only files on this feature branch.
- No merge or production deployment.

## Review Focus

- Legacy/manual/unknown provenance is ambiguous and must be retained: assert preview splits deletable/protected and confirm deletes only eligible rows.
- A stale preview or any concurrent writer must prevent deletion: assert 409 and no DB or filesystem mutation.
- Crash immediately before/after a file deletion must be recoverable: run cleanup repeatedly and assert no outside path is touched and pending work drains.
- Startup auto-sync must consult persisted pause before it can invoke a source: assert after restart and at `ApplicationReady`.
- Environment override must not re-enable deletion in production `container`: assert feature status false and destructive controller/routes unavailable.

---

## File Map

- `backend/src/main/resources/db/migration/V11__safe_data_management.sql` — add import provenance, per-provider pause state, and durable original-file deletion jobs.
- `backend/src/main/java/local/runningdashboard/ImportService.java`, `GarminSyncService.java`, `IntervalsIcuSyncService.java`, `GarminSyncTriggers.java`, `IntervalsIcuSyncTriggers.java` — share writer coordination, pass source provenance, and gate sync starts on persistent pause.
- `backend/src/main/java/local/runningdashboard/DataMutationCoordinator.java` — shared exclusive lock / non-blocking acquisition for imports, complete sync operations, and deletes.
- `backend/src/main/java/local/runningdashboard/DataManagementRepository.java` — previews, current-state fingerprint, targeted deletes, pause CRUD, deletion-job CRUD and counts.
- `backend/src/main/java/local/runningdashboard/DataManagementService.java` — preview tokens, rules for eligible/protected activities, transaction boundary, action counts, post-commit file queue processing and resume.
- `backend/src/main/java/local/runningdashboard/OriginalFileDeletionWorker.java` — validate safe paths, preserve referenced files, process/retry durable jobs, resume pending jobs at startup.
- `backend/src/main/java/local/runningdashboard/DataManagementController.java` — opt-in status/preview/confirm/pause-release routes and request-origin checks; unavailable in `container` profile.
- `backend/src/main/resources/application.yaml`, `application-container.yaml`, `dev.sh`, `.env.example` or README local setup — default-off config, local opt-in, hard production-profile-off.
- `backend/src/test/java/local/runningdashboard/DataManagementIntegrationTests.java`, `DataManagementSecurityTests.java`, and focused unit tests — relational, filesystem, concurrency, pause, HTTP, and reset behavior.
- `frontend/src/api.ts`, `frontend/src/views/ImportView.vue`, `frontend/src/views/ImportView.test.ts` — typed management API, preview/confirmation UI, pause release, result and refresh.
- `README.md` — document local opt-in, destructive behavior and test isolation.
- `docs/superpowers/specs/2026-11-10-data-management-safe-deletion-design.md` — approved design including amendments.

## Contracts

- Provider names in DB/API: `GARMIN`, `INTERVALS_ICU`; import provenance values: `MANUAL`, `GARMIN`, `INTERVALS_ICU`, legacy `NULL` treated as unknown.
- Actions: `DELETE_GARMIN`, `DELETE_INTERVALS_ICU`, `DELETE_ACTIVITIES`, `RESET_APPLICATION_DATA`.
- `DataMutationCoordinator.tryAcquire()` returns an `AutoCloseable` lease or empty; imports/sync acquire the same coordinator for their complete DB write operation; delete does not wait behind active work.
- `DataManagementService.preview(Action) -> Preview` is read-only and issues a short-lived single-use opaque token bound to action, counts and database-state fingerprint.
- `DataManagementService.confirm(Action, token, phrase) -> Result` revalidates all three while holding the coordinator; mismatch returns conflict without mutation.
- Phrase constants are action-specific (`GARMIN LÖSCHEN`, `INTERVALS. ICU LÖSCHEN`, `ALLE AKTIVITÄTEN LÖSCHEN`, `ALLE ANWENDUNGSDATEN LÖSCHEN`).
- Management status includes `enabled`, per-provider `paused`, and pending file-cleanup count. Preview/result include activity deleted/protected/ambiguous counts, removed source mappings, removed records/laps/import rows/plans/phases/sessions, files deleted/pending/failed, and preserved-data explanation.
- API: `GET /api/data-management/status`; `POST /api/data-management/preview`; `POST /api/data-management/confirm`; `POST /api/data-management/sync/{provider}/resume`. Disabled/production routes are absent (404); active writer or stale preview is 409; invalid phrase/token is 400/410; success is 200 with actual result.

---

### Task 1: Add additive persistence and shared write coordinator

**Files:**
- Create migration `backend/src/main/resources/db/migration/V11__safe_data_management.sql`.
- Create `backend/src/main/java/local/runningdashboard/DataMutationCoordinator.java`.
- Create `backend/src/main/java/local/runningdashboard/SyncPauseRepository.java`.
- Modify `backend/src/main/java/local/runningdashboard/ImportService.java`.
- Test `backend/src/test/java/local/runningdashboard/DataMutationCoordinatorTests.java`, `SyncPauseRepositoryTests.java` and `ImportControllerTests.java`.

- [x] **Step 1: Test legacy and new import provenance, and lock exclusion/reentrancy.** Verify each persisted import gets `MANUAL` by default; two write leases cannot overlap; a nested call from a sync-held lease does not deadlock.
- [x] **Step 2: Run focused tests and confirm failure.** Run `cd backend && ./gradlew test --tests '*DataMutationCoordinatorTests' --tests '*ImportControllerTests'`; expect missing schema/coordination behavior.
- [x] **Step 3: Add V11 structures.** `import_file.source` is nullable for legacy rows; add one pause row per provider; add durable cleanup table keyed by normalized relative path with retryable status/error timestamps and no FK to deleted activities.
- [x] **Step 4: Implement coordinator, `SyncPauseRepository.isPaused(provider)`, `pause(provider)`, and `resume(provider)`, then make each manual FIT batch acquire one exclusive lease.** Add the overload accepting origin while preserving `importFit(InputStream)` as `MANUAL`; provider-specific calls use explicit provenance in Task 2.
- [x] **Step 5: Run focused tests.** Expect tests pass and migration validation against the isolated test DB.

### Task 2: Persist source pauses and guard complete sync runs

**Files:**
- Modify `GarminSyncService.java`, `IntervalsIcuSyncService.java`, `GarminSyncTriggers.java`, `IntervalsIcuSyncTriggers.java`, `GarminSyncController.java`, `IntervalsIcuSyncController.java`.
- Create/modify pause repository alongside `DataManagementRepository.java` in Task 3.
- Test `GarminSyncServiceTests.java`, `IntervalsIcuSyncServiceTests.java`, and new trigger restart tests.

- [x] **Step 1: Add tests for paused startup trigger, manual start, persisted read after service reconstruction, complete sync lock, and shared write conflict.** Include both provider services.
- [x] **Step 2: Run affected service/controller tests and verify failure.**
- [x] **Step 3: Gate every automatic and manual start on persisted provider pause before executor submission or remote source access.** The `ApplicationReady` trigger must read pause state before starting. Hold the common write lease over the entire remote sync and all FIT persistence/mapping writes.
- [x] **Step 4: Pass `GARMIN` / `INTERVALS_ICU` origin to FIT import without converting duplicate/manual activities into provider-origin records.** Keep existing duplicate and mapping behavior.
- [x] **Step 5: Run affected tests; assert paused services perform zero source calls and an unpaused, configured Garmin sync still imports and links normally.**

### Task 3: Implement preview classification and transactional deletion

**Files:**
- Create `DataManagementRepository.java` and `DataManagementService.java`.
- Test `backend/src/test/java/local/runningdashboard/DataManagementIntegrationTests.java`.

- [x] **Step 1: Write DB integration tests** for Garmin and Intervals links, multiple external IDs, target-only deletion, manual/unknown/shared-provider protection, counts, stale preview, all-activity reset preserving training plans, and full reset deleting plans/phases/sessions while preserving schema/migration rows.
- [x] **Step 2: Run tests only against verified isolated test DB and confirm they fail.**
- [x] **Step 3: Implement read-only preview with deletable/protected/ambiguous counts and action-scoped state fingerprint.** Legacy null provenance, manual imports, missing source rows, and any non-target source are protected.
- [x] **Step 4: Implement single-use expiring preview tokens and transactional confirm under the coordinator.** Verify action, phrase, token and recomputed fingerprint before any mutation. Source actions remove target links even when activity is protected; eligible activities are then deleted through FK cascades. Reset actions pause both providers first and clear activity sync snapshots; full reset additionally deletes `training_plan` rows.
- [x] **Step 5: Assert rollback leaves activities, mappings, plans, pause state, cleanup jobs and files unchanged after an injected DB failure.**
- [x] **Step 6: Run focused integration tests; verify actual deletion counts and retained rows.**

### Task 4: Make original-file deletion safe, durable and restartable

**Files:**
- Create `OriginalFileDeletionWorker.java`.
- Modify `DataManagementRepository.java`, `DataManagementService.java`, and migration only if an additive follow-up schema change is required (prefer V11 complete before tests).
- Extend `DataManagementIntegrationTests.java`.

- [x] **Step 1: Test commit ordering and filesystem cases.** Cover successful removal, shared path retained, already-missing file, injected I/O/database interruption, traversal, absolute path, symlink inside/outside root, interruption after file unlink but before queue completion, and repeated retry.
- [x] **Step 2: Run focused tests and confirm failure.**
- [x] **Step 3: In the same DB transaction as row deletion, enqueue unique relative paths before cascading `import_file` rows.** Never touch filesystem in the transaction. On rollback no file operation or committed job is observable.
- [x] **Step 4: After commit, and on application startup, process pending jobs idempotently.** Resolve under the real data root, reject symlink path components and non-regular files, recheck live `import_file` references before deletion, delete through `SecureDirectoryStream`, then mark job done. Persist failure state/message for reporting and retry; missing files count as successful cleanup.
- [x] **Step 5: Run tests for rollback, partial failure result, safe retry after simulated process interruption, and no deletion outside the managed root.**

### Task 5: Add opt-in, same-origin API and production profile guard

**Files:**
- Create `DataManagementController.java` and focused request guard if needed.
- Modify `backend/src/main/resources/application.yaml`, create/update `application-container.yaml`, `dev.sh` / `.env.example`, and `README.md`.
- Test `DataManagementSecurityTests.java` and `ContainerConfigurationTests.java`.

- [x] **Step 1: Add HTTP tests** for disabled default, environment enabled outside container, always unavailable in `container` even with `DATA_DELETION_ENABLED=true`, same-origin POST accepted, missing/cross-site Origin rejected, non-JSON content type rejected, no CORS path, invalid phrase and stale preview response codes.
- [x] **Step 2: Run tests and confirm failure.**
- [x] **Step 3: Register controller/service only when enabled and outside `container`.** Default property is `false`; production profile explicitly disables it, and profile-scoped bean exclusion is the overriding guard.
- [x] **Step 4: Implement status, preview, confirm, resume routes.** Validate `Origin` equals the request origin, reject `Sec-Fetch-Site: cross-site` (and `none`), require JSON on POST, and never include sensitive config or tokens in logs.
- [x] **Step 5: Document setting `DATA_DELETION_ENABLED=true` in local ignored `.env` only; do not modify or read a real `.env`.** Explain container-profile hard disable and that loopback/origin checks are not authentication.
- [x] **Step 6: Run security/configuration tests and confirm no destructive controller exists in `container` context.**

### Task 6: Add preview-confirm UI and explicit sync release

**Files:**
- Modify `frontend/src/api.ts`, `frontend/src/views/ImportView.vue`, `frontend/src/views/ImportView.test.ts`.
- Test `frontend/tests/e2e/data-management.spec.ts` if existing smoke architecture supports it.

- [x] **Step 1: Add Vitest cases** for hidden controls while disabled, all four preview actions, deleted vs protected counts, preserved-data and irreversible warning, action phrase required, cancel with no mutation, disabled submit while pending, failed/stale preview, partial file failure result, successful result and status refresh, explicit provider resume.
- [x] **Step 2: Run `cd frontend && npm run test:unit`; confirm new cases fail.**
- [x] **Step 3: Add typed API methods and render a visually distinct “Daten verwalten” card below FIT import.** Preview must precede confirmation; phrase must match server phrase; no destructive action on one click. Keep accessible labels/status/errors and clear result display.
- [x] **Step 4: Disable management controls while any upload, sync, preview or deletion is running.** Refresh provider status after deletion/resume and ensure activity routes re-fetch on navigation; do not use immediate page reload that hides the result.
- [x] **Step 5: Run frontend unit tests, typecheck and production build.**

### Task 7: Full verification, security review, commit and PR

**Files:**
- Review all feature changes; update README and design/plan status if implementation decisions differ.
- No unrelated `.opencode/` files or local data.

- [x] **Step 1: Run database migration/persistence integration tests** only against the verified isolated E2E/test DB; use synthetic FIT fixtures only.
- [x] **Step 2: Run full required pipeline** `bash scripts/test-all.sh`; it provisions a uniquely named isolated test database, validates identity and removes only run-owned resources. Also run/check `cd backend && ./gradlew bootJar`, frontend typecheck/unit/build, and Playwright as covered by the pipeline; report each result.
- [x] **Step 3: Perform a targeted security review** of deletion routes, origin validation, profile/property conditions, confirmation token lifecycle, SQL predicates and filesystem path handling. Fix and test any in-scope finding.
- [ ] **Step 4: Review `git diff`, verify `.opencode/` and data directories are absent, run `git diff --check`, stage only feature files, and commit after mandatory checks pass.**
- [ ] **Step 5: Push branch without force, verify remote SHA, check for existing PR, then create one targeting `main`.** Include behavior, safeguards, tests and risks. Do not merge/deploy; check CI status and report PR URL.

---

## Coverage Self-Review

- UI actions, previews, typed phrase, actual results, busy state, errors and explicit resume: Task 6.
- Every named activity/import/source/training table and correct reset boundaries: Tasks 1 and 3.
- Ambiguous and shared provenance, multiple provider IDs, accurate counts: Task 3.
- FIT original files, path validation, symlinks, shared references, missing files, failure and crash restart: Task 4.
- Import/sync/delete races and pause across restart before startup trigger: Tasks 1 and 2.
- Default-off/API protection/production profile/CSRF and confirmation token: Task 5.
- Isolated test DB, synthetic FIT files, full pipeline, PR without merge/deploy: Task 7.
