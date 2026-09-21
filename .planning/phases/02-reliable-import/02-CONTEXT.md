# Phase 2: Reliable Import - Context

**Gathered:** 2026-09-21
**Status:** Ready for planning

<domain>
## Phase Boundary

Phase 2 delivers reliable local FIT import: multi-file picker and drag-and-drop upload, per-file outcomes and retry, configurable conservative upload limits, isolated per-file processing, non-running classification, duplicate detection, optional local original-file retention, and normalized Activity/Record/Lap persistence. It must preserve local UTC/timezone semantics, nullable FIT measurements, valid GPS conversion, and independent batch outcomes. Dashboard aggregation, activity detail visualization, maps, charts, and external sync remain in later phases.

</domain>

<decisions>
## Implementation Decisions

### Duplicate identity
- **D-01:** Treat an identical SHA-256 as an unequivocal duplicate; use FIT activity identity and start time as additional identity signals for re-exports with different bytes.
- **D-02:** When FIT activity ID and start time identify an already imported activity, classify the new file as `DUPLICATE` and do not create a second Activity. Keep the file hash available as the import-file identity if the implementation records that metadata.
- **D-03:** Phase 2 assumes one FIT file represents one Activity. Files containing multiple sessions or multiple candidate activities are not partially imported; classify them as an actionable invalid/failed outcome.
- **D-04:** If the FIT activity ID is missing or unusable, use a conservative fallback based on start time plus sport and normalized duration/distance. Only sufficiently matching data is a duplicate; otherwise import a new Activity.

### File storage and retry
- **D-05:** Persist the original FIT file in the configurable local filesystem only after a successful import. Invalid, failed, skipped, and duplicate inputs do not become durable original files by default.
- **D-06:** Enforce configurable conservative maximum file-size and batch-count limits. Exceeding a limit produces an understandable upload result before that file/batch is processed.
- **D-07:** Retry only the selected failed file. Existing successful, duplicate, skipped, and other results from the same batch remain intact and visible; retry must not restart the whole batch.
- **D-08:** Process each file atomically in its own transaction. A file failure must not roll back neighboring valid files and must not leave partial Activity, Record, or Lap data visible.

### the agent's Discretion
- Exact default values for configurable upload limits, provided they are conservative, documented, and testable.
- Exact naming and transport shape of the internal import endpoint and per-file outcome DTO.
- Exact database schema, indexes, and repository technology used to enforce the decisions above.
- Exact user-facing wording and UI layout, provided every file receives an understandable actionable outcome and retry remains per-file.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Project scope and phase contract
- `.planning/PROJECT.md` — local privacy, normalized PostgreSQL persistence, UTC/timezone, data-integrity constraints, and MVP exclusions.
- `.planning/REQUIREMENTS.md` — Phase 2 requirements IMPT-01 through IMPT-08, DATA-01 through DATA-07, and QUAL-02.
- `.planning/ROADMAP.md` — Phase 2 goal, deliverables, success criteria, and requirement mapping.
- `idea.md` — original import behavior, Activity/Record/Lap model, duplicate strategy, FIT edge cases, and persistence preferences.

### Prior decisions and research
- `.planning/phases/01-foundation-and-one-real-fit/01-CONTEXT.md` — locked repository structure, typed REST boundary, Garmin SDK adapter, local startup, fixture policy, and Phase 2 boundary.
- `.planning/phases/01-foundation-and-one-real-fit/01-RESEARCH.md` — Phase 1 risks retired and explicit Phase 2 deferrals.
- `.planning/research/SUMMARY.md` — recommended import pipeline, duplicate strategy, per-file transactions, nullable semantics, and known research flags.
- `.planning/research/ARCHITECTURE.md` — backend/frontend boundary, ingestion adapter, persistence boundary, and query guidance.
- `.planning/research/PITFALLS.md` — FIT variability, timestamps, GPS semantics, duplicate identity, and local privacy pitfalls.

### Existing implementation
- `backend/build.gradle.kts` — current Spring Boot/Java/Garmin SDK dependencies and test setup.
- `backend/src/main/java/local/runningdashboard/GarminFitFixtureAdapter.java` — existing Garmin decoder listener pattern and UTC `Instant` conversion.
- `backend/src/main/resources/db/migration/V1__foundation.sql` — existing Flyway baseline to extend with Phase 2 schema migrations.
- `frontend/src/api.ts` — existing typed fetch boundary to extend with import DTOs and upload calls.
- `frontend/src/router.ts` — existing Vue route integration point for the import view.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `GarminFitFixtureAdapter` — already wires Garmin `Decode`/`MesgBroadcaster` listeners for Activity, Session, Lap, and Record messages; Phase 2 should generalize this adapter rather than expose SDK types outside ingestion.
- `backend/src/test/resources/fit/` — committed real FIT fixture location and offline-fixture convention from Phase 1; extend with corrupt, non-running, duplicate, sensorless, GPS, and lap cases.
- `frontend/src/api.ts` — explicit TypeScript DTO and `fetch` error-handling pattern for the internal `/api` boundary.
- Vue Router and `SummaryView.vue` — existing frontend route/view structure to connect an import screen without changing the typed backend boundary.

### Established Patterns
- Backend uses Java 21, Spring Boot Web MVC, JDBC/Flyway, Gradle Kotlin DSL, and Garmin FIT SDK.
- Frontend uses Vue 3, TypeScript, Vite, Vue Router, Vitest, and Playwright.
- FIT timestamps currently become `Instant`; keep UTC internally and leave local display formatting to the frontend.
- Phase 1 tests use semantic assertions against committed fixtures rather than brittle full-payload snapshots.

### Integration Points
- Add import API/controller and service beneath the existing Spring MVC application and `/api` Vite proxy.
- Replace the foundation-only Flyway schema with additive Activity, ActivityRecord, Lap, and import metadata/file-reference migrations.
- Add the import route and upload state handling to the existing Vue router/API structure.
- Extend the existing browser smoke path with a representative multi-file import outcome flow when the phase implementation is ready.

</code_context>

<specifics>
## Specific Ideas

- The user selected hash-first duplicate handling, with FIT activity ID/start time and a conservative start-time/metric fallback for missing IDs.
- A FIT file is intentionally treated as one Activity for this MVP; multi-activity files are not partially imported.
- Original files are retained only after successful import, unlike the research default of storing them before processing; planners must preserve this explicit choice and handle failures without orphaned durable originals.
- Retry is per failed file, not a batch restart.

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope.

</deferred>

---

*Phase: 2-Reliable-Import*
*Context gathered: 2026-09-21*
