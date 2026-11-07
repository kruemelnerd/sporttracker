# Project Overview

Running Dashboard is a local web application for importing and reviewing running activities, FIT-derived records and laps, dashboards, activity details, and training plans. PostgreSQL and the configured local data directory hold application data; keep real activity and GPS data local. An optional, configurable Intervals.icu integration syncs activity data into the application. Do not add other external data transfers or send real FIT files, GPS tracks, secrets, or personal data to AI services or logs.

# Architecture

- **Backend:** Java/Spring Boot REST application, built with the Gradle Kotlin DSL; JDBC and Flyway manage PostgreSQL access and versioned schema migrations. Garmin FIT SDK decodes FIT data.
- **Frontend:** Vue 3, TypeScript, and Vite; Vitest covers unit tests and Playwright covers E2E.
- **Persistence/runtime:** PostgreSQL via `compose.yaml`; application settings and environment-variable defaults are in `backend/src/main/resources/application.yaml`. Original/import files use the configured `running-dashboard.data-directory`.
- **Key paths:** `backend/src/main/java/local/runningdashboard/`, `backend/src/main/resources/db/migration/`, `backend/src/test/`, `frontend/src/`, `frontend/tests/e2e/`.
- **Configuration/docs:** `backend/build.gradle.kts`, `frontend/package.json`, `frontend/playwright.config.ts`, `compose.yaml`, `compose.e2e.yaml`, and `README.md`. The README documents local startup, integration setup, and test commands; keep it as the operational reference.
- **Current integrations:** FIT import and optional Intervals.icu activity sync. Follow existing API/service patterns rather than assuming planned features or unimplemented architecture.

# Data Safety

- Treat PostgreSQL data, named Docker volumes, stored FIT files, and original files as user data. Never perform destructive operations or reset persistent data without explicit authorization.
- Backend integration tests and Playwright use the isolated `running_dashboard_e2e` database via `compose.e2e.yaml`; verify isolation before any test that resets data. Never point destructive test setup at the development database or its volume.
- Add new, versioned Flyway migrations; do not edit migrations already applied in an environment. Preserve existing rows, relationships, constraints, and import identity guarantees.
- **Required skill:** use `database-safety` for database, migration, persistence, or volume changes. See `.opencode/skills/database-safety/SKILL.md`.

# Development Conventions

- Follow established code and test patterns. Keep changes small and cohesive; avoid unrelated refactors and unrequested dependency upgrades.
- Preserve FIT timestamps as UTC instants internally; use the configured display timezone only for presentation. Distinguish local calendar dates (for example, planned-session dates) from instants so timezone/DST conversion cannot shift their meaning.
- Preserve FIT semicircle precision, optional sensor values, duplicate protection, and the relationship between activities, records, laps, and source files. Store canonical numeric measurements, not formatted display strings.
- Maintain local-first privacy; Intervals.icu is the explicitly supported, opt-in external integration. Keep credentials in backend configuration/environment, never in source, prompts, or logs.

# Verification

- Match checks to the changed scope; use relevant unit/integration tests for localized changes and broader regression/build/E2E checks for functional or cross-layer changes.
- Before integration or milestone completion, run the applicable full required checks. Do not report skipped or unrun checks as passing; investigate failures and report existing failures separately from regressions when evidence permits.
- **Required skill:** use `fullstack-verification` to choose and report checks. See `.opencode/skills/fullstack-verification/SKILL.md`.
- For available commands and isolation details, follow the `Tests` and `Phase-1-Smoke` sections of `README.md`.

# Git and Delivery

- Preserve pre-existing and untracked local changes. Review the working tree and stage only files belonging to the request; never include secrets or private data.
- Commit only after required checks succeed. Push the verified result to the intended remote/branch when authorized by the task.
- **Required skill:** use `safe-git-delivery` for commit/push work. It is global at `~/.config/opencode/skills/safe-git-delivery/SKILL.md`.

## GitHub Pull Requests

- After successfully completing a development task, create an appropriate feature, fix, or documentation branch, make small cohesive commits, and push the branch to `origin`.
- Check whether a pull request already exists for the branch before creating one; never create duplicates. Create a pull request targeting `main`, using `gh pr create` if the available GitHub integration cannot create one.
- Describe the changes, tests, and known risks in the pull request. Check GitHub Actions status when possible, report it, and provide the pull request link at the end of the task.
- If pull request creation fails, check GitHub CLI authentication and repository permissions, and use GitHub CLI if another integration is restricted. Never change credentials without asking. If permissions remain insufficient, report the specific cause and a manual pull request creation link.

### GitHub PR Creation

After pushing a feature branch:
1. Check whether a pull request already exists.
2. Create the pull request using the GitHub CLI.
3. Prefer the locally authenticated GitHub CLI:

   `env -u GH_TOKEN -u GITHUB_TOKEN gh pr create --base main --fill`

4. If authentication fails, report the error.
5. Never expose tokens or credentials.
6. Never merge pull requests without explicit user approval.

## Prohibited Actions

Without the user's explicit approval, never merge pull requests, enable auto-merge, bypass branch protection, disable security checks, perform production deployments, modify production data or volumes, force-push, or rewrite shared history.

# Available Skills

- `safe-git-delivery` — global: `~/.config/opencode/skills/safe-git-delivery/SKILL.md`.
- `fullstack-verification` — project-local: `.opencode/skills/fullstack-verification/SKILL.md`.
- `database-safety` — project-local: `.opencode/skills/database-safety/SKILL.md`.
- `ui-ux-pro-max` — project-local: `.opencode/skills/ui-ux-pro-max/SKILL.md`.
