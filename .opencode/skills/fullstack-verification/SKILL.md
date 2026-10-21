---
name: fullstack-verification
description: Use when verifying changes to the Sporttracker backend, Vue frontend, API integration, Playwright E2E flow, or a delivery milestone.
---

# Fullstack Verification

Choose checks by change scope, record results, and never describe an unrun check as passing. This skill verifies only; commits and pushes belong to `safe-git-delivery`.

## Select scope

- **Small/localized:** run the directly relevant tests or typecheck. State why broader checks are unnecessary.
- **Functional behavior:** run affected backend/frontend tests plus a reasonable regression set; include production build when frontend or packaging is affected.
- **Integration, infrastructure, or milestone:** run the relevant complete backend and frontend suites, production build, and Playwright smoke/E2E where supported.
- Database schema, migration, or persistent-data changes also require `database-safety`.

## Checks present in this repository

Run from repository root unless a working directory is shown:

| Area | Command | Notes |
|---|---|---|
| Backend tests | `cd backend && ./gradlew test` | Gradle test tasks reset only the isolated E2E database after validating its database/user. Start it first with `docker compose -p sporttracker-m4-e2e -f compose.e2e.yaml up -d --wait db`. |
| Backend + packaged frontend build | `cd backend && ./gradlew bootJar` | Build task includes frontend dependency install, typecheck, and Vite production build. |
| Frontend typecheck | `cd frontend && npm run typecheck` | `vue-tsc --build --force`. |
| Frontend unit tests | `cd frontend && npm run test:unit` | Vitest. |
| Frontend production build | `cd frontend && npm run build` | Runs typecheck then Vite build. |
| Playwright E2E smoke | `cd frontend && ./scripts/phase1-smoke.sh` | Starts isolated `sporttracker-m4-e2e` PostgreSQL and Playwright-managed backend/frontend; requires installed Chromium (`cd frontend && npx playwright install --with-deps chromium`). |

There are no `lint` or npm security/audit scripts in `frontend/package.json`; do not report those as configured project checks. Run an additional security check only if the task requires it or configuration is added. Backend test configuration points to `running_dashboard_e2e` on port `55432`; never redirect it to the development database. E2E uses `compose.e2e.yaml`, its dedicated volume, ports `55432`/`18080`/`15173`, and `DATA_DIRECTORY=/tmp/running-dashboard-m4-e2e`. Do not substitute persistent user data or reuse a running server.

## Execute and report

1. Identify changed layers and Spring Boot/configuration or API paths affected. Confirm required services are available before integration/E2E checks; if unavailable, report **BLOCKED** rather than weakening isolation.
2. Run the smallest checks matching the selected scope. For a full backend/package build use `./gradlew bootJar`; for separate frontend checks use the listed npm scripts. E2E is not needed for Markdown-only changes.
3. Investigate failures, distinguish known pre-existing failures from regressions using available evidence, and fix only within task scope. Rerun failed checks after a fix. Never disable tests or weaken assertions just to get green.
4. Report each relevant command as **PASS**, **FAIL**, **SKIPPED**, or **BLOCKED**. Include pass/fail test counts when the output provides them, explain every skip/block, and state remaining limitations. Do not claim a test passed if it did not run.

## Stop conditions

Stop before destructive test setup if database identity or isolation cannot be confirmed. Stop/report when a required service, fixture, or browser is unavailable, when a check fails outside authorized scope, or when the available evidence cannot distinguish an existing failure from a new one.
