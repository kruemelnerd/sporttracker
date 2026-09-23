# Phase 1 Research: Foundation and One Real FIT

## Scope

Phase 1 proves a locally runnable vertical slice: Gradle/Spring Boot backend, Vue/Vite frontend, PostgreSQL in Docker Compose, Flyway baseline, one committed Garmin sample FIT, a typed decode endpoint, and a Vue summary route reached through the Vite `/api` proxy. Full upload, normalization/persistence, duplicate handling, dashboard aggregation, and detail visualization remain deferred to later phases.

## Decisions Applied

- Use `backend/` and `frontend/` in one repository, with independent Gradle and npm builds and no root orchestrator.
- Use Java 21, Spring Boot/Web MVC, PostgreSQL, Flyway, Garmin FIT Java SDK, Vue 3, TypeScript, Vite, and Vue Router. Pin versions during implementation from compatible current releases; do not add libraries for future phases.
- Run PostgreSQL through Compose with a named volume, healthcheck, safe local defaults, and explicit reset documentation. Flyway creates only the minimal baseline required to prove connectivity; no activity/record/lap persistence is implemented yet.
- Keep a Garmin-provided sample FIT in `backend/src/test/resources/fit/`, with source and SHA-256 recorded in test documentation or a fixture note. Tests must be offline and must not download the fixture.
- Put the FIT SDK behind a small adapter. Decode into an internal summary model and map that model to an explicit REST DTO; do not expose SDK types or persistence entities.
- The endpoint is intentionally minimal: a deterministic local fixture decode returning sport, start time, distance, and status, plus only the smallest additional fields needed to demonstrate a non-empty record/lap path.
- Frontend owns its TypeScript DTO and renders the endpoint response on a summary route. Vite proxies `/api` to Spring Boot so local development avoids direct CORS calls.
- The phase gate is automated: Compose health and Flyway baseline, backend fixture/endpoint smoke, frontend production build, and browser/API smoke proving the summary renders. No dashboard, upload UI, or import persistence acceptance is added.

## Risks Retired

1. The selected Garmin SDK artifact and API can compile and decode a real sample file.
2. FIT summary timestamps and numeric values cross the backend boundary without leaking SDK internals.
3. A real fixture has a non-empty decoded record/lap path without requiring the Phase-2 import model.
4. PostgreSQL/Flyway and the frontend proxy are reproducible in a clean local startup path.

## Implementation Notes

- Verify the actual SDK API against the pinned artifact before designing abstractions; the research documents explicitly mark exact SDK versions/API as medium confidence.
- Preserve nullable optional values and avoid early GPS rounding, even though Phase 1 does not persist the full activity.
- Use UTC/`Instant` at the backend boundary; presentation formatting belongs to the frontend. Do not introduce timezone aggregation in this phase.
- Keep the fixture test assertions semantic rather than a full JSON snapshot: running sport, plausible core values, and non-empty decoded structures.
- Do not add Leaflet, ECharts, upload limits, content-addressed storage, duplicate constraints, or full schema tables in this phase.

## Verification Strategy

- Backend unit/integration smoke against the committed FIT resource and Spring MockMvc or an equivalent HTTP test.
- Compose health/Flyway check against the local PostgreSQL service.
- Frontend typecheck/build and one browser smoke against the running proxy/backend, with a stable fixture response assertion.
- One documented command sequence must be sufficient for a clean checkout; no network download is allowed during tests beyond dependency installation outside the fixture test itself.
