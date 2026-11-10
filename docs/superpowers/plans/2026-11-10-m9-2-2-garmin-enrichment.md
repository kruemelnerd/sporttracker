# M9.2.2 Garmin JSON Enrichment Implementation Plan

> **For agentic workers:** Use this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Persist and display confirmed Garmin training metrics on newly FIT-imported Garmin activities without changing FIT-derived measurements or historical rows.

**Architecture:** Reuse `GarminPersonalApiClient` for `/activity-service/activity/{id}`, parse only the confirmed `summaryDTO` scalar fields, and update nullable enrichment columns only after FIT import/source mapping succeeds. The existing activity-detail endpoint and view expose the values; Garmin JSON outages remain partial sync failures while committed FIT data stays intact.

**Tech Stack:** Java 25, Spring Boot, Jackson, JDBC/Flyway, Vue 3/TypeScript/Vitest.

**Spec:** User-provided M9.2.2 request; observed fields documented in `docs/research/m9.1-garmin-poc.md`.

## Global Constraints

- No historical backfill, existing-activity enrichment, or deletions.
- FIT remains authoritative for all existing FIT metrics; JSON adds only distinct training values.
- Keep Garmin credentials/tokens out of logs, tests, and Git; use mocked API responses in CI.
- Missing values stay `null`; invalid optional fields cannot fail FIT import.
- Migration V12 is additive and nullable, following the active data-management branch's V11; merge/apply data management first or renumber in the combined branch.
- Garmin sync/import code and related tests overlap with the active data-management branch and must be integrated together without dropping either behavior.
- Tests that mutate persistence use only the verified isolated E2E database.

## Review Focus

- Malformed/oversized detail JSON or invalid numeric metric: FIT persists, optional metric is null, failure is sanitized; test parser/client and sync.
- Garmin detail request fails after FIT import: mapping/FIT remain, sync reports failed/partial and does not advance complete success; test sync service.
- Repeated sync of mapped Garmin ID: neither download nor enrichment duplicates/rewrites; test service.
- Existing local/FIT measurements are unchanged by non-null Garmin metrics; test persistence integration.
- Nulls and valid measured zero survive persistence and API serialization; test repository/detail API.

---

## File Map

- Modify `GarminPersonalApiClient.java` for bounded detail GET and its compact metric record; extend client tests.
- Add V12 nullable columns plus an incomplete-enrichment marker to `activity`; extend `ImportService` only with narrow update operations and test persisted values.
- Modify `GarminSyncService.java` to enrich only after normal FIT import and source linking; extend sync tests.
- Modify `ActivityController.java`, `frontend/src/api.ts`, and `ActivityDetailView.vue`; extend backend and Vue tests.
- Update `README.md` and `docs/research/m9.1-garmin-poc.md` with confirmed fields, units/limits, failure behavior, and no-backfill policy.

## Tasks

### Task 1: Confirmed JSON contract and bounded client access

- [x] Add a failing mocked-client/parser test for `summaryDTO.trainingEffect`, `summaryDTO.anaerobicTrainingEffect`, and `summaryDTO.activityTrainingLoad`, including absent, null, zero, malformed and out-of-range values.
- [x] Add a detail method using existing authorization, response-size, timeout, redirect and sanitized-error handling; ignore malformed optional metric values without exposing payloads.
- [x] Verify the client tests pass without live Garmin access.

### Task 2: Nullable persistence and FIT-preserving sync

- [x] Add V12 nullable columns `garmin_training_effect`, `garmin_anaerobic_training_effect`, and `garmin_activity_training_load`, plus a nullable incomplete-enrichment marker, with numeric types and plausibility checks; no existing row updates.
- [x] Add failing repository/import persistence tests proving null/zero round trips and all FIT columns remain unchanged.
- [x] Extend the existing Garmin sync path to fetch detail only for newly imported/unambiguously linked activities; partial API failure increments failure state after FIT remains committed; mapped IDs still short-circuit.
- [x] Run focused backend tests against the isolated database.

### Task 3: Activity detail API and UI

- [x] Extend the existing response with one nullable Garmin training-metrics object while retaining all current fields.
- [x] Add failing Vue tests for available values, German numeric labels, null fields, and omitting the section when all are absent.
- [x] Render a compact responsive “Trainingsanalyse” panel using existing styles/components; do not alter FIT charts.
- [x] Run targeted frontend tests and typecheck.

### Task 4: Documentation, verification, and delivery

- [x] Document exactly the three imported JSON fields, units/semantics and unimplemented uncertain fields; state no backfill and intentional resync only after the separate deletion feature is integrated.
- [x] Run backend suite, frontend unit/typecheck/build, packaged backend build, isolated Playwright smoke, and `scripts/test-all.sh` if available and safe.
- [ ] Review diff, migration number, status, and staged files; commit, push, check for an existing PR, then create one against `main` without merging/deploying.
