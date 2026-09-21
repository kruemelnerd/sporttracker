# Project Research Summary

**Project:** Running Dashboard
**Domain:** Local web application for Garmin FIT activity import and running analytics
**Researched:** 2026-09-21
**Confidence:** HIGH for product scope and domain risks; MEDIUM for exact dependency versions and operational map-provider suitability

## Executive Summary

Running Dashboard is a private, local-first web application that turns Garmin FIT files into a trustworthy six-week running overview and inspectable per-run details. The research consistently recommends treating FIT ingestion and data integrity as the product foundation: decode into an internal model, normalize units and timestamps, preserve nullable measurements and raw files, classify duplicates and non-running activities, and persist each file atomically. The dashboard and visualizations should consume task-shaped read models rather than persistence entities.

The recommended implementation is one locally hosted Spring Boot application with a small internal REST API, PostgreSQL/Flyway persistence, and a separately developed Vue 3/TypeScript/Vite frontend. Start with one real FIT fixture before UI polish, then ship vertical slices for import, dashboard, detail, and finally map/chart hardening. The highest risks are FIT message variability, partial or corrupt files, time-zone/DST boundaries, duplicate identity, optional sensor data, oversized queries, and OSM tile/privacy constraints; each should be retired by a concrete fixture, contract, or smoke test rather than by speculative infrastructure.

## Key Findings

### Recommended Stack

Use Java 21 LTS with Spring Boot 4.1.x, Spring Web MVC, Gradle Kotlin DSL, PostgreSQL 18, Spring Data JPA/Hibernate for aggregates, SQL projections for dashboard/detail reads, and Flyway for versioned schema migrations. Decode FIT with the official Garmin FIT Java SDK behind an adapter. Store canonical numeric values, `Instant`/UTC timestamps, nullable optional measurements, normalized activities/laps/records, and original FIT files in a configurable content-addressed filesystem directory.

Use Vue 3 with Composition API and TypeScript, Vite, and Vue Router. Leaflet with configurable OSM-compatible raster tiles is sufficient for one route polyline; MapLibre is a future alternative only if vector styling or offline/self-hosted tiles becomes a real requirement. Use ECharts through `vue-echarts` for linked pace, HR, elevation, cadence, and weekly views. Pin versions from release registries at implementation time, keep a Gradle wrapper/npm lockfile, and do not maintain both Maven and Gradle builds.

**Core technologies:**
- **Java 21 + Spring Boot:** local REST backend and application services — conventional single-process deployment without microservice overhead.
- **PostgreSQL + Flyway:** normalized durable activity storage and reproducible schema — fits the expected scale and project constraint.
- **Garmin FIT Java SDK:** activity decoding — official profile knowledge without a second runtime or custom parser.
- **Vue 3 + TypeScript + Vite:** dashboard, import, and detail UI — typed, small, and aligned with the requested frontend stack.
- **Leaflet + ECharts:** route and time-series visualization — enough for the MVP while keeping map/chart concerns replaceable.

### Expected Features

The detailed feature landscape is in [FEATURES.md](FEATURES.md). Trustworthy multi-file import is the first table stake, followed by a current-week-inclusive six-week dashboard and resilient detail views. Every file needs an explicit outcome (`IMPORTED`, `DUPLICATE`, `SKIPPED_NON_RUN`, `INVALID`, or `FAILED`), and one bad file must not roll back neighboring valid files. Missing HR, cadence, GPS, elevation, laps, or Training Effect must remain unavailable, not become zero.

**Must have (table stakes):**
- Multi-file picker/drag-and-drop FIT upload with retryable per-file status and actionable errors.
- Idempotent duplicate detection using SHA-256 plus practical FIT activity identity/start-time constraints.
- Safe non-running filtering with an explicit skipped result.
- Six calendar-week buckets including zero-activity weeks, local-time semantics, core totals, weekly kilometers, and newest-first activity list.
- On-demand run detail with summary metrics, conditional GPS map, nullable pace/HR/elevation/cadence series, and lap table.
- Local-only storage/privacy behavior, responsive empty states, and independent failure handling for map, charts, and laps.

**Should have (competitive):**
- Retryable import result summary after the core per-file outcomes work.
- Synchronized chart cursor/section zoom if the selected chart library provides it cheaply and reliably.
- Preserve original FIT files for reproducible re-parsing and troubleshooting.
- Evidence/status labels for source versus derived values after the first usable detail view.

**Defer (v2+):**
- Training plans and planned-vs-actual comparison.
- AI analysis, recommendations, OpenAI integration, and coaching semantics.
- Garmin Connect, Garmin OAuth, automatic Garmin sync, Strava, or other external sync.
- GPX/TCX import.
- Accounts, multi-user support, social features, sharing, likes, followers, challenges, and gamification.
- Native mobile app, push notifications, and live tracking.
- Best-time/record logic, segment comparisons, complex training-load models, and medical/health evaluation.
- Route privacy controls, alternative time-axis charts, and other polish until actual data demonstrates the need.

### Architecture Approach

Keep a clear Vue-to-typed-HTTP-to-Spring boundary. Vue owns navigation, formatting, loading/empty/error states, upload progress, and map/chart adapters; Spring owns decoding, normalization, validation, duplicate classification, persistence, and query semantics. The stable domain is `Activity`, `ActivityRecord`, `Lap`, and `ImportFile`; FIT SDK types and JPA entities must not cross the API. Process each file in its own transaction, preserve originals by hash, use dashboard projections without records, and load detail series on demand with deterministic ordering and optional transport downsampling.

**Major components:**
1. **Import pipeline:** bounded multipart controller, content-addressed file store, FIT decoder adapter, normalizer/validator, duplicate classifier, and per-file transaction/outcome.
2. **Persistence/query layer:** PostgreSQL/Flyway schema for activities, records, laps, and import metadata; dashboard aggregate projections and detail projections.
3. **Typed frontend:** Dashboard, Import, and Activity Detail routes with explicit DTOs and honest nullable field handling.
4. **Visualization adapters:** Leaflet route rendering with attribution/configurable provider and ECharts series that preserve gaps and fail independently.

### Critical Pitfalls

1. **FIT variability and partial files** — never infer validity from filenames or one message type; decode/normalize/validate separately, support incomplete optional data, and isolate each file transactionally.
2. **Time and coordinate semantics** — convert FIT epoch to `Instant`, calculate local calendar boundaries then query UTC half-open ranges, test DST/midnight, and verify Garmin semicircle conversion without early rounding.
3. **Duplicate and storage identity** — combine SHA-256 with FIT identity/start time, enforce database constraints, use generated content-addressed paths, and keep ambiguous matches conservative.
4. **Nullable data and query fan-out** — carry `null`/gaps through API and charts, use task-shaped projections, and never load all records for dashboard requests.
5. **Map privacy and availability** — request only visible background tiles, show attribution, do not proxy tracks or prefetch tiles, keep the provider configurable, and let detail work without a map.

## Implications for Roadmap

Based on research, suggested phase structure:

### Phase 1: Foundation and One Real FIT
**Rationale:** FIT SDK behavior, fixture availability, timestamp conversion, and semicircle handling are the highest-risk unknowns and must be proven before UI work.
**Delivers:** Boot/Vue shell, PostgreSQL/Flyway baseline, configured local file root, API skeleton, pinned decoder dependency, and a decoder/normalizer spike against real and incomplete FIT fixtures.
**Addresses:** Core stack, canonical data model, raw-file preservation, and initial test harness.
**Avoids:** Building screens around one ideal file or committing to an incompatible SDK API.

### Phase 2: Reliable Import Vertical
**Rationale:** All later features depend on correct, idempotent normalized data.
**Delivers:** Multi-file upload, size/count/time limits, temp and content-addressed storage, decode/normalize/validate pipeline, non-run classification, duplicate handling, per-file outcomes, and Activity/Lap/Record persistence.
**Uses:** Garmin SDK, Spring MVC multipart handling, PostgreSQL constraints, Flyway, and per-file transactions.
**Implements:** Import controller, import service, decoder adapter, normalizer, file store, and repositories.
**Avoids:** Partial activities, batch rollback, path traversal, silent skips, and duplicate totals.

### Phase 3: Six-Week Dashboard Vertical
**Rationale:** The core product value is a fast bounded overview, and its calendar/time-zone semantics must be settled before detailed visualization.
**Delivers:** Backend dashboard endpoint, six explicit local calendar-week buckets including zero weeks, summary metrics, newest-first activity list, typed DTOs, empty state, and responsive dashboard/import navigation.
**Uses:** SQL projections/indexes on activity start time and Vue Router/TypeScript.
**Implements:** Dashboard query service and dashboard view without loading records.
**Avoids:** Entity-graph fan-out, fake history, server-time-zone drift, and oversized payloads.

### Phase 4: Resilient Activity Detail
**Rationale:** Detail is intentionally on demand and depends on the normalized child data contract, nullable semantics, and bounded response strategy.
**Delivers:** Activity detail endpoint/view with headline metrics, laps, ordered records, nullable series, GPS track data, and deterministic downsampling where needed.
**Uses:** Detail projections, indexed child queries, explicit frontend DTOs, and independent loading/error states.
**Implements:** Detail query service and Activity Detail route.
**Avoids:** Treating optional fields as required, serializing JPA graphs, losing raw records during UI optimization, and chart failures blanking the page.

### Phase 5: Map, Charts, and Hardening
**Rationale:** Visualization should follow validated data contracts; this phase verifies browser behavior and graceful degradation instead of hiding ingestion defects.
**Delivers:** Leaflet route map with configurable tiles/attribution, ECharts pace/HR/elevation/cadence charts, optional synchronized inspection, accessibility/empty states, repeated-import handling, and import-to-dashboard/detail smoke verification.
**Uses:** Leaflet, ECharts/vue-echarts, browser network assertions, representative fixture matrix, and performance checks.
**Implements:** Map/chart adapters and final cross-layer integration.
**Avoids:** GPS leakage, OSM policy violations, invalid bounds, fake zeroes, brittle charts, and premature caching or time-series infrastructure.

### Phase Ordering Rationale

- FIT decoding and persistence are prerequisites for trustworthy aggregation; aggregation precedes detail and visual polish.
- Each phase is a user-observable vertical slice or retires a concrete data-integrity risk, rather than splitting work into backend/frontend/infrastructure silos.
- The order makes the import result contract, nullable semantics, UTC/local-time boundary, and query payload discipline stable before UI complexity grows.
- PostgreSQL, JPA, Redis, TimescaleDB, message brokers, background workers, and microservices are intentionally absent until measured scale or request time requires them.

### Research Flags

Phases likely needing deeper research during planning:
- **Phase 1:** Verify the selected Garmin FIT SDK artifact/API and behavior for multiple sessions, truncated files, real device fixtures, FIT epoch, and semicircle conversion.
- **Phase 2:** Confirm multipart limits, file placement/rollback semantics, practical duplicate identity, ambiguous exports, and PostgreSQL constraint behavior under concurrent retries.
- **Phase 3:** Validate local IANA timezone/DST week boundaries and aggregate query plans with representative data.
- **Phase 4:** Validate record volume, deterministic downsampling policy, and DTO behavior for sparse/missing series.
- **Phase 5:** Recheck current Leaflet/ECharts integration details, OSM tile policy/operational suitability, accessibility, and browser network/privacy behavior.

Phases with standard patterns (skip research-phase):
- **Phase 3 UI shell and routing:** established Vue 3/Vite/TypeScript patterns once API DTOs are fixed.
- **Basic Flyway/PostgreSQL bootstrap:** well-documented local application setup; spend research effort on schema constraints and query behavior instead.

## Confidence Assessment

| Area | Confidence | Notes |
|------|------------|-------|
| Stack | MEDIUM-HIGH | Official documentation supports the direction; exact versions, especially FIT SDK, Vue-ECharts, Leaflet, and TypeScript, must be pinned at implementation time. |
| Features | HIGH for MVP scope; LOW for competitor differentiation | `PROJECT.md` and `idea.md` are authoritative; FIT feature semantics are backed by official Garmin documentation, while broader UX conclusions were not independently competitor-verified. |
| Architecture | HIGH | The single-process boundary, normalized model, projections, per-file transactions, and local privacy constraints agree across research files and project requirements. |
| Pitfalls | HIGH | Core risks are grounded in Garmin, PostgreSQL, OSM, and project-specific requirements; exact SDK edge behavior still needs real fixtures. |

**Overall confidence:** HIGH for roadmap direction and MVP boundaries; MEDIUM for implementation details requiring live dependency and fixture validation.

### Gaps to Address

- **FIT fixture coverage and multi-session semantics:** obtain representative real files from relevant devices plus corrupt/truncated, sensorless, GPS-poor, and non-running fixtures; decide whether one file may yield multiple activities before schema enforcement.
- **Dependency versions and SDK API:** verify current Maven/npm releases and compile a minimal decoder spike before locking the build.
- **Map provider operations:** public OSM tiles are acceptable for private normal viewing but have no SLA; keep the adapter/provider configurable and reassess before distribution.
- **Performance thresholds:** measure import memory/time, dashboard query plans, detail payload size, and chart rendering with hundreds of activities and thousands of points per run before adding caching or precomputation.
- **Derived metric precedence:** document whether summary or record/lap values win when FIT sources disagree, and define pace behavior for zero/invalid speed.
- **API evolution:** avoid speculative versioning for the internal MVP API; add a schema version only when an actual compatibility need appears.

## MVP Exclusions

The following remain explicitly excluded and must not shape MVP acceptance criteria: training plans; planned-vs-actual comparison; AI analysis; automatic recommendations; OpenAI integration; Garmin Connect API; Garmin OAuth; automatic Garmin sync; Strava sync; GPX/TCX import; user accounts; multi-user capability; social features; native mobile app; push notifications; live tracking; segment comparisons; best-time/record logic; complex training-load models; medical or health evaluation; sharing, likes, followers, challenges, badges, and gamification.

## Sources

### Primary (HIGH confidence)
- `PROJECT.md` and `idea.md` — authoritative product scope, constraints, data model, MVP acceptance, exclusions, and implementation priorities.
- [Garmin FIT Activity Files](https://developer.garmin.com/fit/file-types/activity/) — activity/session/lap/record message semantics.
- [Garmin FIT Cookbook: Decoding Activity Files](https://developer.garmin.com/fit/cookbook/decoding-activity-files/) — decoder flow and incomplete-file handling.
- [Garmin FIT Java Examples](https://developer.garmin.com/fit/example-projects/java) — official Java decoding direction.
- [PostgreSQL Versioning Policy](https://www.postgresql.org/support/versioning/) — supported database lifecycle.
- [OpenStreetMap Tile Usage Policy](https://operations.osmfoundation.org/policies/tiles/) — attribution, visible use, and no bulk/offline policy.

### Secondary (MEDIUM confidence)
- [Spring Boot project](https://spring.io/projects/spring-boot) and [system/data initialization documentation](https://docs.spring.io/spring-boot/system-requirements.html) — runtime, MVC, SQL, and Flyway direction.
- [Spring MVC multipart documentation](https://docs.spring.io/spring-boot/how-to/spring-mvc.html) — upload boundary and limits.
- [PostgreSQL indexes documentation](https://www.postgresql.org/docs/current/indexes.html) — B-tree range/order query guidance.
- [Vue tooling](https://vuejs.org/guide/scaling-up/tooling), [Vue testing](https://vuejs.org/guide/scaling-up/testing.html), and [Vue TypeScript guidance](https://vuejs.org/guide/typescript/composition-api) — frontend/tooling direction.
- [Vite guide](https://vite.dev/guide/) — current Node/tooling compatibility.
- [Leaflet](https://leafletjs.com/) and [Vue-ECharts usage patterns](https://github.com/ecomfe/vue-echarts/blob/main/_autodocs/08-usage-patterns.md) — map and chart implementation direction.

### Tertiary (LOW confidence)
- Competitor/UX landscape conclusions in `FEATURES.md` — useful hypotheses, not independently verified competitor research.
- Public OSM tile operational suitability — policy is clear, but the public service has no SLA and must remain replaceable.

---
*Research completed: 2026-09-21*
*Ready for roadmap: yes*
