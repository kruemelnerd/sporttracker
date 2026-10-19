# Intervals.icu Import Implementation Plan

> **For agentic workers:** Execute inline task-by-task with tests first; commit each green, cohesive unit and push it immediately.

**Goal:** Import new running FIT activities from Intervals.icu through the existing FIT pipeline while preserving manual upload and local-only ownership.

**Architecture:** A configured Intervals HTTP client lists activities and downloads gzip originals. A provider source delegates FIT bytes to the existing `ImportService`; V8 stores provider/external-ID links. One serialized sync service owns startup, scheduled, and manual runs and exposes safe status/results to the existing import page.

**Tech Stack:** Existing Spring Boot MVC/JDBC/Flyway/Java, Garmin FIT SDK, Vue 3/TypeScript/Vitest/Playwright; no new dependency unless the existing stack cannot support a tested requirement.

**Spec:** User-provided “Meilenstein 7 – Automatischer Aktivitätsimport über Intervals.icu” in this conversation.

## Global Constraints

- `INTERVALS_ICU_ENABLED` defaults false; missing `INTERVALS_ICU_API_KEY` leaves sync disabled and startup healthy.
- `INTERVALS_ICU_BASE_URL` defaults to `https://intervals.icu`, `INTERVALS_ICU_SYNC_INTERVAL` to `PT15M`, initial lookback to 90 days; base URL is injectable for mock HTTP.
- Basic-auth username is `API_KEY`; never expose/log the secret or include it in fixtures/errors.
- Only run variants are imported; only gzip-original FIT is processed; all other originals are safe, reported skips.
- Reuse import hashing, matching, parsing and persistence. Never delete/update activities or upload local data.
- Initial search is today minus configured lookback; subsequent searches roll over the last 14 days.
- `provider + external_id` is unique; a repeated sync must not redownload linked activities.
- No overlapping sync; per-activity errors do not abort the run; 429 honors Retry-After without an aggressive retry loop.
- A manual FIT duplicate is linked only when the existing identity check resolves exactly one local activity; ambiguous candidates are reported and never arbitrarily linked.
- Successful external-ID mappings make subsequent runs skip downloads; after a crash between FIT import and mapping, a retry reuses FIT duplicate detection and then writes the mapping. No cursor is required for correctness.
- Preserve manual import API and existing local changes. Use V8 after V7. Commit/push only scoped M7 files.

## Review Focus

- Malformed or oversized gzip/file data cannot exhaust memory or leave temporary files; test bounded import handling and cleanup.
- Duplicate FIT matching must link only a uniquely identified existing activity and never choose arbitrarily among ambiguous candidates; test exact, re-export and ambiguous matches.
- Concurrent triggers and database uniqueness races cannot create duplicate activities or external mappings; test serialized runs and idempotent mapping.
- Missing credentials and upstream failures must not impede app startup or expose credentials; test configuration and sanitized status/errors.
- Rate limiting must stop immediate scheduled/manual attempts until Retry-After expires; test throttled sync state and no retry loop.
- Activity date boundaries must use the configured date semantics without DST shifting; test initial and rolling windows at a fixed date/clock.

---

### Task 1: Intervals configuration and HTTP client

**Files:** `backend/src/main/resources/application.yaml`; new focused configuration/client/error classes under `backend/src/main/java/local/runningdashboard/`; corresponding backend tests.

**Interfaces:** Typed properties carry enabled/key/base URL/interval/lookback and validation. Client offers typed activity listing and original-file download; it owns Basic Auth, User-Agent, safe HTTP error classification and Retry-After, with no FIT logic.

- [ ] Test endpoint/date query, athlete 0, Basic Auth, User-Agent, gzip body and each 401/403/404/429/5xx/network failure using a local mock HTTP server; verify key is absent from logs/errors and no live API or real credential is used.
- [ ] Verify tests fail before implementation.
- [ ] Implement validated optional configuration and client; avoid logging request headers or response bodies.
- [ ] Run focused tests and backend suite.
- [ ] Commit/push the configuration/client unit.

### Task 2: Persistent provider mapping and duplicate identity

**Files:** new `V8__activity_import_source.sql`; `ImportService.java`; new provider mapping repository/class; backend persistence/import tests.

**Interfaces:** Mapping lookup by `(provider, external_id)` and insert with `activity_id`/`imported_at`; FIT import result returns a local activity ID on both newly imported and recognized duplicate sessions.

- [ ] Test migration uniqueness/FK; manual FIT duplicate links only when exactly one local activity matches, while ambiguous same-time/same-measurement candidates create no link and no activity.
- [ ] Verify the test fails before implementation.
- [ ] Add V8 mapping table and minimally extend duplicate result to identify existing activity for both SHA-256 and session-match paths.
- [ ] Run migration/import tests and backend suite.
- [ ] Commit/push the mapping and import-result unit.

### Task 3: Provider and serialized sync service

**Files:** new provider interface/Intervals implementation/sync service/result models; existing `ImportService.java`; backend tests.

**Interfaces:** provider maps listed source activities to source metadata and downloads bytes; sync service returns a safe aggregate result and per-item reason; source submits inflated FIT bytes to the existing `ImportService` with a bounded in-memory or staged upload adapter, without a second parser.

- [ ] Test Run/TrailRun/VirtualRun, other sport skip, known external ID no-download, unique/ambiguous manual FIT duplicate linking, damaged file isolation, initial/rolling windows with fixed clock, and concurrent trigger exclusion.
- [ ] Verify tests fail before implementation.
- [ ] Implement gzip validation and bounded decompression, temporary cleanup as applicable, idempotent persistence/linking, run-level isolation, and serialized execution. Keep successful mappings durable; a retry after interruption relies on the existing FIT duplicate identity when the local import succeeded before mapping.
- [ ] Run focused integration tests and full backend suite.
- [ ] Commit/push the sync unit.

### Task 4: Startup, scheduler, status and manual endpoint

**Files:** new sync controller/status service or focused additions to current API; backend config; backend MockMvc tests.

**Interfaces:** status endpoint returns configured/running/attempt/success/latest safe result; manual POST starts the same service and rejects/returns current state if already running. Startup trigger is asynchronous/non-blocking; schedule uses configured interval.

- [ ] Test status and manual sync with secret absent from serialized responses; test disabled/missing-key startup, sanitized failures, and Retry-After suppressing immediate scheduled/manual requests.
- [ ] Verify tests fail before implementation.
- [ ] Implement endpoint and triggers with no separate manual code path.
- [ ] Run backend suite.
- [ ] Commit/push API and scheduler unit.

### Task 5: Import-page sync card

**Files:** `frontend/src/api.ts`, `frontend/src/views/ImportView.vue`, `ImportView.test.ts` (and focused API tests if established).

**Interfaces:** Typed status/result fetch and manual-sync functions; card shows configuration, activity counts/reasons and running/error states alongside unchanged upload UI.

- [ ] Test unconfigured/configured, last success, running, error, button action and disabled-while-running.
- [ ] Verify tests fail before implementation.
- [ ] Implement card using existing design tokens/patterns and accessible status announcements.
- [ ] Run frontend unit tests, typecheck and build.
- [ ] Commit/push UI unit.

### Task 6: E2E and documentation

**Files:** relevant `frontend/tests/e2e/*`, E2E test profile/config, `README.md`.

- [ ] Add mock/test-backend scenario showing the card, triggering manual sync, and displaying result; no live Intervals requests.
- [ ] Run relevant Playwright flow, complete relevant suite, backend suite, frontend unit/typecheck/build, and manual-upload/duplicate regression checks.
- [ ] Document Developer Settings/API key environment setup with placeholder only.
- [ ] Commit/push E2E/docs unit.
- [ ] Confirm branch and protected modifications, fast-forward only if main is safe/up to date, push and verify local/remote HEADs; otherwise report the exact blocker without overwriting anything.
