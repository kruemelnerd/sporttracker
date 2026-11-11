# M9.4 Garmin Activity Details Implementation Plan

> **For agentic workers:** Use this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Display and persist the supported Garmin activity-detail metrics while preserving FIT import, charts, real laps, and M9.3 export.

**Architecture:** Extend the bounded Garmin detail DTO and existing enrichment update path, migrate only confirmed scalar fields, expose the fields through the existing detail API, and render compact optional sections in the current Vue view. FIT remains the source for records, GPS, and laps; Garmin JSON enriches only supported summary metrics.

**Tech Stack:** Java 25, Spring Boot, Jackson, JDBC/Flyway, Vue 3, TypeScript, Vitest, Playwright.

**Spec:** User-provided “M9.4 – Garmin Activity Details vollständig in Sporttracker übernehmen”; sources further bounded by `docs/research/m9.1-garmin-poc.md` and M9.2.2/M9.3 plans.

## Global Constraints

- Never commit Garmin raw responses, FIT files, GPS, secrets, or personal data.
- Missing optional Garmin values remain null and render no empty metric section.
- FIT remains authoritative for records/GPS/laps and existing FIT-derived measurements.
- Keep sync idempotent; update supported enrichment for already-mapped Garmin IDs without reimporting or duplicating activities.
- No invented metrics, training-effect calculation, run/walk detection, new UI library, or dependency upgrade.
- M9.3 training-summary export remains compact and functional.
- V12 is the newest existing migration; use additive V13.
- Persistence tests run only through `scripts/test-all.sh` against its verified per-run isolated database.

## Review Focus

- Existing Garmin activity re-sync must update only enrichment and preserve FIT fields; test sync/update integration.
- Absent, malformed, measured-zero and out-of-range Garmin fields must remain distinguishable; test parser/persistence.
- Cadence is stored as FIT half-steps and doubled exactly once for UI/export; test chart/card/export.
- FIT-only activities, null Garmin payload, and pre-existing M9.3 export must keep rendering; test API/UI.
- Last partial kilometer and recorded FIT laps must remain exact and not be replaced by derived splits; test lap rendering.

## File Map

- `GarminPersonalApiClient.java`, `GarminSyncService.java`, `ImportService.java`: bounded JSON fields, parsing, repeat-sync updates.
- `db/migration/V13__garmin_activity_details.sql`: additive nullable columns for fields confirmed by the API evidence.
- `ActivityController.java`, `frontend/src/api.ts`: grouped API fields while retaining existing contract compatibility.
- `ActivityDetailView.vue`, `activityDetail.ts`, `TrainingSummaryExport.vue`: optional responsive sections, unit/cadence correctness, compact export additions.
- Existing Garmin integration/client/service tests, `ActivityDetailView.test.ts`, `activityDetail.test.ts`, and activity-detail Playwright coverage.
- `README.md` and M9.1 research report: document supported fields and current API gaps.

## Tasks

### Task 1: Extend mapped Garmin detail and enrichment update

- [x] Add client tests for confirmed `summaryDTO` fields and omitted/invalid/zero values.
- [x] Parse confirmed fields only; keep detail response size/time/auth protections.
- [x] Add migration V13 after verifying existing V1–V12; persist new nullable scalar values with safe range checks.
- [x] Add service tests proving repeated sync updates mapped activities without FIT re-import/overwrite or duplicate source mappings.
- [x] Implement narrowly scoped update of confirmed Garmin enrichment and verify FIT values are unchanged.

### Task 2: Detail API and responsive view

- [x] Add API tests for nullable/grouped Garmin values and FIT-only activity shape.
- [x] Extend DTO mapping and TypeScript types without removing current API fields.
- [x] Add unit tests for full/partial/no enrichment, units, hidden empty sections, lap precedence, and cadence doubled once.
- [x] Render supported summary metrics and FIT-derived pace/speed/elevation/cadence; keep map, existing charts, laps and export.
- [x] Add no new charts because no additional record series were confirmed; retain existing real-data charts.
- [x] Extend E2E detail fixture to assert key Garmin sections and compact M9.3 export availability.

### Task 3: Docs, verification, and delivery

- [x] Keep compact training summary unchanged; it already includes Training Effect/load, elevation, and cadence.
- [x] Document confirmed sources, unverified metrics/API gaps (especially subjective rating and devices), FIT vs Garmin vs calculated values.
- [x] Run `./scripts/test-all.sh`, `cd backend && ./gradlew bootJar`, and `git diff --check`.
- [ ] Review migration/source diff; commit/push feature branch and create/check PR against `main` without merging or deploying.
