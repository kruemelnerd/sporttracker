---
name: database-safety
description: Use when changing PostgreSQL schemas or data, Flyway migrations, persistence behavior, database tests, or Docker volumes containing Sporttracker data.
---

# Database Safety

Protect real running activities, training plans, sessions, FIT data, original files, and PostgreSQL volumes. If the target or impact is uncertain, stop before executing a modifying operation.

## Before changing anything

1. Inspect `backend/src/main/resources/application.yaml` and `compose.yaml` to identify the configured connection and local development volume. Defaults are database/user `running_dashboard` on port `5432` and volume `running-dashboard-postgres`; environment overrides may change them.
2. For test work, inspect `backend/build.gradle.kts`, `compose.e2e.yaml`, and `frontend/playwright.config.ts`. The isolated backend/E2E database is `running_dashboard_e2e` on `55432`; its Compose project is `sporttracker-m4-e2e` and volume is `sporttracker-m4-e2e-postgres`. Confirm actual container/database/user before any reset; configuration alone is not proof of the live target.
3. Inspect all existing files in `backend/src/main/resources/db/migration` and the current schema/data constraints relevant to the change. Migrations currently run through V9. Trace affected foreign keys, unique constraints, indexes, and application reads/writes.
4. Assess whether the change can lose, rewrite, duplicate, expose, or make existing data inaccessible. Check the backup and recovery plan before risky operations. No repository backup/restore procedure is established: do not invent one or claim a backup is restorable without a successful restore verification.

## Protect data

- Preserve existing activity, training-plan/session, FIT, original-file, and database-volume data. Never replace real data with fixtures or send personal data to logs, external services, or AI contexts unless explicitly necessary and authorized.
- Never run `DROP DATABASE`, `DROP TABLE` on existing data, `TRUNCATE` of existing data, uncontrolled `DELETE`, Flyway `clean`, `docker compose down -v`, `docker volume prune`, or persistent-database reset without explicit authorization. Destructive test operations are allowed only after proving the target is an isolated test database.
- Do not modify a Flyway migration already applied in an environment. Add a new versioned migration after checking the existing V1–V9 numbering/names. Prefer additive changes and preserve rows/relationships; check for existing duplicates before adding unique constraints.
- Never attempt automatic destructive repair. Do not resolve errors by deleting user data or overwriting a volume.

## Test database rules

The documented backend test command is `cd backend && ./gradlew test`. Before using it, start only the isolated E2E database with `docker compose -p sporttracker-m4-e2e -f compose.e2e.yaml up -d --wait db` and verify its identity. The Gradle test setup truncates application tables in that test database after checking database and user. Playwright is run with `cd frontend && ./scripts/phase1-smoke.sh`; it uses the isolated E2E compose project and its own data directory. Never point either test flow at the development database or its volume. If isolation is uncertain, do not run tests that reset data.

## Validate changes

After a migration or persistence change, verify migration completion and PostgreSQL reachability, that expected existing tables/constraints remain, and that representative existing data remains consistent and accessible without unexpected deletions or duplicates. Run relevant backend tests (`cd backend && ./gradlew test`) only against the verified isolated test database; exercise affected API queries where available. Also use `fullstack-verification` for broader application checks. If a required validation cannot be safely performed, report it as blocked/skipped and why.

## Stop conditions

Do not proceed with a modifying operation if database name/environment, container/volume identity, migration state, data impact, or backup/recovery readiness for a risky operation is unclear. On conflicts or migration errors, preserve state, diagnose first, and report safe options; never overwrite another change or claim unverified data integrity.
