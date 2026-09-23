# Phase 1: Foundation and One Real FIT - Context

**Gathered:** 2026-09-21
**Status:** Ready for planning

<domain>
## Phase Boundary

Phase 1 establishes a locally runnable vertical foundation for Running Dashboard: a `backend/` Spring Boot project, a `frontend/` Vue/Vite project, PostgreSQL with Flyway baseline, a reproducible local startup path, the Garmin FIT SDK adapter, one Garmin sample FIT fixture, and a minimal typed REST plus Vue summary path that proves the fixture can be decoded. Full FIT batch import, persistence of activities/records/laps, import UX, dashboard, and detail visualization remain in later phases.

</domain>

<decisions>
## Implementation Decisions

### Project structure
- **D-01:** Use a single repository with `backend/` and `frontend/` directories. — **Reversibility:** costly — moving to separate repositories later changes local startup, documentation, and CI boundaries.
- **D-02:** Use separate native builds: Gradle Kotlin DSL in `backend/` and npm/Vite in `frontend/`; do not add a root build orchestrator in this phase.
- **D-03:** Keep a typed internal REST boundary. Spring exposes explicit DTOs; Vue owns its TypeScript DTOs and must not depend on JPA entities or Garmin FIT SDK types.
- **D-04:** Use a Vite development proxy for `/api` to Spring Boot. Do not make direct cross-origin calls the primary local development path.

### Local startup
- **D-05:** Provide PostgreSQL through Docker Compose. — **Reversibility:** costly — the documented environment and database lifecycle become the foundation for later persistence phases.
- **D-06:** Persist PostgreSQL data in a named Docker volume across container restarts. Reset must be an explicit, documented operation.
- **D-07:** Use safe environment defaults with environment-variable or `.env` overrides for database credentials, ports, and the configurable original-file directory. Never commit secrets.
- **D-08:** Document one clear development path: start Compose, verify health, start backend with Gradle, start frontend with npm/Vite, and document stop/reset commands. A root parallel-start wrapper is not required.

### FIT fixture
- **D-09:** Use a Garmin-provided sample FIT file for the first decoder proof rather than a private user activity.
- **D-10:** Store the fixture under `backend/src/test/resources/fit/` with source and hash documentation. Do not download it during tests and do not store it in PostgreSQL.
- **D-11:** Phase 1 proves core decoding only: a running activity summary plus a non-empty record/lap path. The complete malformed, duplicate, sensorless, GPS, and non-running fixture matrix belongs to Phase 2.
- **D-12:** Assert stable semantics (sport, core values, and non-empty decoded structures), not an exact full JSON snapshot or fragile byte/point-count equality.

### Walking skeleton boundary
- **D-13:** The first real backend path is a minimal decode endpoint that uses the Garmin sample fixture and returns a small typed activity-summary DTO. Full persistence and file-upload endpoints start in Phase 2.
- **D-14:** PostgreSQL is proven through Compose connectivity and Flyway baseline only in this phase; FIT activity persistence is explicitly deferred to Phase 2.
- **D-15:** The first real UI interaction is a minimal Vue summary route that calls the decode endpoint through `/api` and displays sport, start time, distance, and status.
- **D-16:** Phase 1 is accepted only when a local end-to-end smoke path proves Compose DB availability, backend FIT decoding, typed REST response, and Vue summary rendering. The proof should be automated where practical, with no dashboard or batch-import behavior required.

### the agent's Discretion
- Exact Spring Boot patch version, Garmin SDK patch version, Vite/Vue package versions, database port, and DTO field naming may follow current compatible releases and repository conventions, provided the decisions above remain true.
- The exact minimal Flyway schema and internal endpoint path are implementation details; do not expand them into Phase 2 persistence.
- The exact browser smoke-test tool and test layering may be selected by the planner based on the available project setup.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Project scope and phase contract
- `.planning/PROJECT.md` — product boundary, privacy, data-integrity, persistence, and timezone constraints.
- `.planning/REQUIREMENTS.md` — v1 requirements; Phase 1 owns `QUAL-01` and must not pull later-phase requirements forward.
- `.planning/ROADMAP.md` — Phase 1 goal, deliverables, success criteria, and phase ordering.
- `idea.md` — original product idea, preferred stack, definition of done, and explicit MVP exclusions.

### Research
- `.planning/research/SUMMARY.md` — synthesized stack, architecture, FIT risks, and Phase 1 roadmap implications.
- `.planning/research/STACK.md` — recommended Java/Spring, PostgreSQL/Flyway, Garmin SDK, Vue/Vite, and build direction.
- `.planning/research/ARCHITECTURE.md` — frontend/backend boundary, ingestion adapter, persistence boundary, and API guidance.
- `.planning/research/PITFALLS.md` — FIT variability, timestamps, GPS semantics, fixture, and local privacy pitfalls.

### External specifications
- No separate ADR or SPEC files exist. The project and research documents above are the canonical sources for this phase.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- None yet. This is a greenfield repository with no application source code or codebase maps.

### Established Patterns
- Planning conventions are established in `AGENTS.md`; implementation conventions do not yet exist.
- The repository currently contains planning artifacts and the idea document only. Do not infer application patterns from them.

### Integration Points
- New backend code connects to the future `backend/` Gradle project, PostgreSQL Compose service, and the internal `/api` REST boundary.
- New frontend code connects to the future `frontend/` Vite project and its `/api` proxy.

</code_context>

<specifics>
## Specific Ideas

- Keep the first UI restrained: a fixture summary is proof of the data path, not an early dashboard.
- Prefer a reproducible Garmin sample over private data for the first committed test fixture.
- Preserve a clear separation between the Phase 1 decoder proof and Phase 2's robust multi-file import/persistence behavior.

</specifics>

<deferred>
## Deferred Ideas

- Full FIT upload, per-file outcomes, retries, duplicate handling, non-run filtering, and normalized Activity/Record/Lap persistence — Phase 2.
- Dashboard queries, six-week aggregation, weekly chart, activity list, and responsive empty state — Phase 3.
- Activity detail metrics and lap table — Phase 4.
- Leaflet map, ECharts charts, chart interactions, and final browser hardening — Phase 5.
- Root wrapper command, full fixture matrix, exact SDK/version pinning research, and alternate deployment packaging — revisit only if Phase 1 implementation exposes a concrete need.

</deferred>

---

*Phase: 1-Foundation-and-One-Real-FIT*
*Context gathered: 2026-09-21*
