# Technology Stack

**Project:** Running Dashboard  
**Researched:** 2026-09-20

## Recommended Stack

### Core Framework

| Technology | Version | Purpose | Why |
|------------|---------|---------|-----|
| Java | 21 LTS | Backend runtime and language | Conservative LTS baseline; Spring Boot 4.x requires Java 17+ and this avoids adopting a newer non-LTS runtime for a local data application. |
| Spring Boot | 4.1.1 (pin latest stable patch when implementation starts) | REST backend, configuration, embedded server | Current official Spring project release; gives a conventional single-process application without microservice overhead. |
| Spring Web MVC | Spring Boot-managed | Internal HTTP API | REST endpoints are sufficient for the Vue frontend; no public API, OAuth, or reactive stack is needed. |
| Gradle Kotlin DSL | 9.x, wrapper pinned | Build and dependency management | Prefer one reproducible wrapper-based build; Kotlin DSL is concise and works with current Boot support. Maven 3.6.3+ remains a valid alternative if team familiarity wins. |

### Database

| Technology | Version | Purpose | Why |
|------------|---------|---------|-----|
| PostgreSQL | 18.6 (current minor at research date) | Normalized activity, lap, and record persistence | Current supported major with a five-year support window; relational constraints and indexed queries fit hundreds of activities and millions of records without a time-series extension. |
| Spring Data JPA / Hibernate | Spring Boot-managed | Activity/lap aggregate persistence | Useful for ordinary CRUD and relationships; avoid loading all records through entity graphs. Use projections or SQL for dashboard summaries and detail-series payloads. |
| Flyway | Spring Boot-compatible current release | Versioned schema migrations | Makes the schema reproducible and keeps raw data/derived columns explicit. Use PostgreSQL's Flyway database module where required by the selected Flyway line. |
| PostgreSQL JDBC driver | Spring Boot-managed current release | JDBC connectivity | Let the Boot BOM manage the compatible driver version. |

Recommended initial tables: `activity`, `activity_record`, `lap`, and `import_file` (or equivalent import metadata). Keep `Instant`/UTC timestamps, numeric base units (metres, seconds, m/s, beats/minute), nullable optional measurements, and the original FIT file outside PostgreSQL in a configured data directory. Add unique constraints for file SHA-256 and a practical activity identity such as source + FIT start timestamp + FIT activity/file identity.

### FIT ingestion

| Technology | Version | Purpose | Why |
|------------|---------|---------|-----|
| Garmin FIT SDK for Java | `com.garmin:fit:21.171.0` in the official example; verify the newest Maven Central SDK release before pinning | Decode FIT files into messages | Official Java decoder, Garmin profile knowledge, Maven artifact, and forward-compatible FIT format. It is a better MVP choice than writing a FIT parser or translating through another language. |

Use `FitDecoder`/`FitMessages` or the SDK's listener/API shown by the selected SDK version. Implement an import mapper that is deliberately separate from persistence: decode file → collect activity/session/lap/record messages → normalize units and timestamps → validate/filter → persist in one file-level transaction. Unknown fields must not fail the file; missing GPS, HR, cadence, laps, and training effect remain `null` rather than zero.

Important FIT rules:

- Convert FIT semicircles to decimal degrees using the SDK/profile conversion and test with real fixtures; do not round coordinates before storage.
- Treat FIT epoch timestamps and SDK `DateTime` conversion as an explicit boundary, then store `Instant`.
- Preserve the original file (content-addressed by SHA-256) so mappings can be re-run later.
- Store pace as seconds/km or derive it from m/s; never persist a formatted `5:23` string as the canonical value.
- Filter non-running activities for the running dashboard while reporting the skip to the user.

### Frontend

| Technology | Version | Purpose | Why |
|------------|---------|---------|-----|
| Vue | 3.x current | UI components and pages | Matches the requested stack and is sufficient for three screens: dashboard, import, and activity detail. Use Composition API with `<script setup lang="ts">`. |
| TypeScript | Vite/create-vue compatible current release | Typed DTOs and UI logic | Prevents unit/optional-field mistakes in chart and detail rendering. Keep API response types explicit instead of sharing backend classes. |
| Vite | 8.3.0 current docs release; pin the current stable generated version | Dev server and production build | Official Vue setup path, fast local feedback, and simple static build. Use Node.js 22.12+ (the documented minimum for the current Vite line). |
| Vue Router | 4.x current | Dashboard/import/detail navigation | Small, standard solution; route by activity id and avoid a larger state framework initially. |
| Vitest + Vue Test Utils | current versions compatible with Vite/Vue 3 | Frontend tests | Official Vue guidance recommends Vitest for Vite projects; cover loading, empty/optional data, and import error states. |

### Mapping and charts

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| Leaflet | Current stable 1.x compatible with Vue 3 | Raster map, route polyline, start/end markers, `fitBounds` | Recommended for this MVP: simple route display needs no vector styling or WebGL. Keep tile URL and attribution in one map adapter/config object. |
| MapLibre GL JS | Current stable alternative | Vector-tile/WebGL maps | Choose only if vector styling, large datasets, offline/self-hosted vector tiles, or future map layers become requirements. It adds style/source complexity that the MVP does not need. |
| `vue-echarts` + Apache ECharts | Current compatible ECharts 6 line | Pace, HR, elevation, cadence and weekly charts | Supports tooltips, `dataZoom`, and chart groups for synchronized detail charts; tree-shake components to keep the bundle small. |

For OSM raster tiles use `https://tile.openstreetmap.org/{z}/{x}/{y}.png` only for normal user-viewed tiles, display visible `© OpenStreetMap contributors` attribution, and do not implement bulk prefetch/offline downloads. The tile URL must be configurable because the public service is best-effort and may not be suitable for heavier use. Do not send FIT data or GPS tracks to a map service; only tile requests leave the local application.

## Alternatives Considered

| Category | Recommended | Alternative | Why Not |
|----------|-------------|-------------|---------|
| Backend runtime | Java 21 + Spring Boot 4.1.x | Java 25 or Boot 3.5.x | Newer Java may be fine but gives less conservative runtime compatibility; Boot 3.5 is viable when an existing Java 17 ecosystem or dependency blocks Boot 4, but should not be selected without a concrete blocker. |
| Build | Gradle Kotlin DSL | Maven | Maven is equally supportable and may be preferable for team convention. Do not maintain both build systems. |
| ORM access | JPA for aggregates + SQL projections | jOOQ or JDBC everywhere | jOOQ is attractive for query-heavy systems but adds code-generation/setup; plain JDBC everywhere loses useful aggregate mapping. Revisit if dashboard SQL becomes complex. |
| Schema migration | Flyway | Liquibase | Liquibase is valid, but SQL-first Flyway is less ceremony for a single local PostgreSQL schema. |
| FIT parsing | Official Garmin FIT Java SDK | Third-party parser or Python subprocess | Avoids an extra runtime and format translation. A third-party parser is only a fallback if the official artifact cannot decode required Garmin fixtures. |
| Map | Leaflet + raster tiles | MapLibre GL JS | MapLibre is the future-facing option for vector maps but unnecessary for one polyline and introduces style/tile-provider decisions. |
| Charts | `vue-echarts` / ECharts | Chart.js, uPlot, Plotly | ECharts directly covers linked data zoom and multiple metric charts. Use uPlot later only if measured rendering cost becomes material; Plotly is overkill for this UI. |
| Database | PostgreSQL 18 | SQLite | SQLite would simplify distribution, but PostgreSQL is an explicit project constraint and gives durable migration/index/constraint behavior for the planned data model. |

## Implementation-ready dependency outline

Backend (let the Spring BOM manage versions):

```text
spring-boot-starter-web
spring-boot-starter-validation
spring-boot-starter-data-jpa
org.postgresql:postgresql (runtime)
org.flywaydb:flyway-core
org.flywaydb:flyway-database-postgresql
com.garmin:fit:21.171.0
spring-boot-starter-test
```

Frontend:

```text
vue
vue-router
leaflet
echarts
vue-echarts
vite (dev)
typescript (dev)
vitest + @vue/test-utils (dev)
```

Use a checked-in Gradle wrapper, npm lockfile, and PostgreSQL Docker Compose file for local repeatability. Configure the original-file directory, database URL, local display timezone, and map tile URL through environment/application configuration; do not hard-code a user-specific path.

## Coarse vertical-MVP roadmap implications

1. **Foundation and one real FIT:** bootstrap Boot/Vite/PostgreSQL, Flyway schema, file storage, and a decoder spike with real Garmin fixtures before building UI. This retires the highest data-integrity risk early.
2. **Import vertical:** implement upload → decode → normalize → duplicate decision → transactionally persist, with per-file error isolation and backend tests for missing fields, semicircles, laps, corrupt files, and non-running activities.
3. **Dashboard vertical:** expose an aggregate six-week endpoint returning only activity summaries and weekly rollups; index `activity.start_time`. Do not include records or GPS points in this response.
4. **Detail vertical:** expose a separate activity detail endpoint with summary, laps, and records. Return raw normalized values and let the frontend format pace/time/units.
5. **Visualization and hardening:** add Leaflet route rendering and grouped ECharts charts, then verify empty states, optional values, downsampling, accessibility, and repeated imports.

## Sources

- Spring Boot project and current release: https://spring.io/projects/spring-boot
- Spring Boot system requirements and SQL/Flyway guidance: https://docs.spring.io/spring-boot/system-requirements.html and https://docs.spring.io/spring-boot/how-to/data-initialization.html
- Garmin FIT overview and Java examples: https://developer.garmin.com/fit/overview/ and https://developer.garmin.com/fit/example-projects/java
- PostgreSQL versioning policy: https://www.postgresql.org/support/versioning/
- PostgreSQL indexes: https://www.postgresql.org/docs/current/indexes.html
- Vue tooling and testing: https://vuejs.org/guide/scaling-up/tooling and https://vuejs.org/guide/scaling-up/testing.html
- Vite current guide and Node compatibility: https://vite.dev/guide/
- Leaflet documentation/examples: https://leafletjs.com/ and https://github.com/leaflet/leaflet
- Vue-ECharts usage patterns: https://github.com/ecomfe/vue-echarts/blob/main/_autodocs/08-usage-patterns.md
- OpenStreetMap tile usage policy: https://operations.osmfoundation.org/policies/tiles/

## Confidence and validation notes

- **High confidence in direction:** all requested technologies have active official documentation and directly cover the MVP.
- **Medium confidence in exact versions:** release pages and Context7 were checked on 2026-09-20, but dependency versions should be pinned from their release pages/registries when implementation starts, especially Garmin FIT SDK, Vue-ECharts, Leaflet, and TypeScript.
- **Low confidence in OSM operational suitability:** the official policy is clear, but the public tile service has no SLA. Keep the provider swappable and revisit a dedicated/self-hosted provider before distributing the app beyond private local use.
