# Phase 1 Plan Check

**Phase:** Foundation and One Real FIT  
**Checked:** 2026-09-21  
**Scope:** `01-CONTEXT.md`, `01-RESEARCH.md`, `01-01-PLAN.md`, `01-02-PLAN.md`, `01-03-PLAN.md`, `01-DISCUSSION-LOG.md`, `REQUIREMENTS.md`, `ROADMAP.md`, and relevant project research

## Final Verdict

**PASS**

The prior planning gaps are resolved. The exact smoke command now starts the local stack, waits for readiness, explicitly runs `npx playwright test` with `frontend/playwright.config.ts`, and exits non-zero when the browser/API assertions fail. Backend and frontend verification commands are also named explicitly.

## Findings

### PASS

1. **QUAL-01 goal-backward coverage**
   - `ROADMAP.md:19-29` defines the local backend/frontend/PostgreSQL path, one real FIT, and an automated end-to-end smoke proof.
   - Plan 01 covers independent backend/frontend builds, Compose, Flyway, configuration, and startup documentation.
   - Plan 02 covers the real fixture, SDK adapter, internal model, and typed decode endpoint.
   - Plan 03 covers the Vue summary route, proxy path, browser/API smoke, and final documented command sequence.
   - This is consistent with `REQUIREMENTS.md:55` and the Phase 1 boundary in `01-CONTEXT.md:9`.

2. **Dependency ordering**
   - Plan 01 is wave 1 with no dependencies.
   - Plan 02 is wave 2 and depends on Plan 01.
   - Plan 03 is wave 3 and depends on both preceding plans.
   - The order matches the research rationale: foundation before decoder/API, then frontend and full smoke.

3. **Locked decisions are mostly respected**
   - `backend/` and `frontend/`, independent Gradle/npm builds, and no root orchestrator are preserved (`01-CONTEXT.md:17-22` and Plan 01).
   - Compose with named volume, environment overrides, explicit reset, and local-only data are addressed (`01-CONTEXT.md:23-27`, Plan 01).
   - The FIT fixture location, offline loading, adapter boundary, internal DTO, nullable values, and Phase 2 deferrals are explicitly carried into Plan 02.
   - The Vite `/api` proxy and frontend-owned TypeScript DTO are explicitly carried into Plans 01 and 03.

4. **Scope discipline**
   - The plans explicitly defer uploads, persistence of activities/records/laps, duplicate handling, dashboard aggregation, maps, and charts. This matches `01-CONTEXT.md:94-101`, `01-RESEARCH.md:31`, and the Phase 2-5 boundaries in `ROADMAP.md`.

5. **Research alignment**
    - The plans use the official Garmin Java SDK, `Instant`/UTC at the backend boundary, semantic fixture assertions, nullable optional data, explicit DTOs, and offline fixture tests as recommended by `SUMMARY.md`, `ARCHITECTURE.md`, `PITFALLS.md`, and `STACK.md`.

6. **Prior BLOCK/FLAG corrections are present**
    - Plan 03 names Playwright, `frontend/playwright.config.ts`, `frontend/tests/e2e/phase1.spec.ts`, `frontend/scripts/phase1-smoke.sh`, readiness checks, cleanup, the exact invocation `cd frontend && ./scripts/phase1-smoke.sh`, and the one-time Chromium prerequisite.
    - The smoke script explicitly runs `npx playwright test` after readiness and makes its exit status determine the smoke result.
   - Plan 02 owns `backend/build.gradle.kts` and pins/verifies the Garmin SDK through the checked-in wrapper; Plan 01 owns the wrapper files and explicitly excludes the SDK until Plan 02.
   - The endpoint is consistently `/api/fixture-summary` in Plans 02 and 03.
    - Exact verification commands are concrete: `cd backend && ./gradlew test`, `cd frontend && npm run test:unit`, `cd frontend && npm run build`, and `cd frontend && ./scripts/phase1-smoke.sh`; Plan 01 additionally names `cd backend && ./gradlew build`, `docker compose config`, and `docker compose up -d db`.
   - Plan 01 creates the new `README.md`; Plan 03 owns the final smoke-command section, so the documentation hand-off is explicit.

7. **No scope or dependency conflict found**
   - The Garmin SDK is the only dependency intentionally added after the foundation build, and its ownership is explicit. Playwright is limited to Phase-1 verification.
   - No upload, persistence, dashboard, map, chart, analytics, or root-orchestrator work is pulled into Phase 1. The plans remain aligned with `QUAL-01` and the roadmap boundaries.

### BLOCK

None. The former smoke-script blocker is resolved by `01-03-PLAN.md:39-45`, including the explicit `npx playwright test` invocation and exit-status requirement.

### FLAG

1. **Fixture source is not concrete enough for a reproducible task**
   - Plan 02 requires a Garmin-provided fixture and source/license/hash documentation (`01-02-PLAN.md:23-29`), which is directionally correct, but no source URL or approved redistribution reference is recorded.
   - This is a manageable implementation choice, not a phase blocker, provided the fixture is available before coding and the committed documentation records the exact source and hash. It should not be downloaded by tests, as required by `01-CONTEXT.md:29-32`.

2. **Minor research tension: incomplete fixture coverage**
   - `PITFALLS.md:95-106` recommends at least one real and one incomplete FIT for the foundation spike, while `01-CONTEXT.md:31` and `01-RESEARCH.md:31` explicitly defer the malformed/sensorless fixture matrix to Phase 2.
   - The locked phase decision should win for this plan. Either record that the Phase 1 scope intentionally accepts only the real fixture, or add one narrowly scoped incomplete-file decoder check without expanding into the Phase 2 matrix.

## Verification Command Review

| Area | Current command/claim | Result |
|---|---|---|
| Backend build | `./gradlew build` from `backend/` | **PASS:** wrapper files and version verification are explicitly owned by Plan 01 |
| Backend tests | `cd backend && ./gradlew test` | **PASS:** exact command is named in Plan 03 |
| Compose syntax | `docker compose config` | **PASS:** concrete and appropriate |
| Database startup | `docker compose up -d db` and healthcheck | **PASS:** executable if the service is named `db`; make that name explicit in the task |
| Flyway | backend startup and second startup | **FLAG:** no exact assertion/query/log criterion for “baseline applied” |
| Frontend build | `npm ci` and `npm run build` | **PASS:** concrete, assuming package lock and scripts are created |
| Frontend unit test | `npm run test:unit` with Vitest | **PASS:** runner, script, and route/error assertions are concrete |
| Browser/API smoke | `cd frontend && ./scripts/phase1-smoke.sh` -> `npx playwright test` | **PASS:** readiness is followed by the explicit Playwright suite and its exit status gates the script |

## Required Pre-Execution Changes

No required pre-execution changes remain. The plan set is ready for execution; no roadmap, requirement, or dependency re-scoping is needed.
