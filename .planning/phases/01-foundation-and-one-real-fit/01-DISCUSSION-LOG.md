# Phase 1: Foundation and One Real FIT - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-09-21
**Phase:** 1-Foundation and One Real FIT
**Areas discussed:** Project structure, Local startup, FIT fixture, Skeleton boundary

---

## Project structure

| Option | Description | Selected |
|--------|-------------|----------|
| Monorepo backend/frontend | `backend/` and `frontend/` in one repository | ✓ |
| Backend-root + frontend | Spring Boot at root, Vue under `frontend/` | |
| Separate repositories | Backend and frontend versioned separately | |

**User's choice:** Monorepo backend/frontend
**Notes:** Separate native Gradle and npm/Vite builds; typed internal REST; Vite `/api` proxy.

---

## Local startup

| Option | Description | Selected |
|--------|-------------|----------|
| Docker Compose | Reproducible local PostgreSQL service | ✓ |
| Host PostgreSQL | Use an existing host installation | |
| Both supported | Document both environments | |

**User's choice:** Docker Compose
**Notes:** Named volume, environment defaults/overrides, one clear documented dev path with health, stop, and reset instructions.

---

## FIT fixture

| Option | Description | Selected |
|--------|-------------|----------|
| Sanitized real FIT | Private but representative fixture | |
| Garmin sample FIT | Public reproducible sample | ✓ |
| Local-only real FIT | Private file outside Git | |

**User's choice:** Garmin sample FIT
**Notes:** Store under `backend/src/test/resources/fit/`; prove core Activity and record/lap decoding with stable semantic assertions. Full error and edge-case matrix is Phase 2.

---

## Skeleton boundary

| Option | Description | Selected |
|--------|-------------|----------|
| Decode endpoint | Decode fixture and return typed summary DTO | ✓ |
| Persist then read | Persist a minimal Activity and read it back | |
| Health only | Only prove process/SDK startup | |

**User's choice:** Decode endpoint
**Notes:** PostgreSQL/Flyway baseline only; Vue summary route displays decoded sport, start time, distance, and status. Acceptance is a local automated end-to-end smoke path.

---

## the agent's Discretion

- Exact compatible dependency patch versions, ports, endpoint path, minimal schema, DTO naming, and browser smoke-test tool.

## Deferred Ideas

- Full import pipeline, dashboard, detail, map, charts, and complete fixture matrix remain assigned to later roadmap phases.
