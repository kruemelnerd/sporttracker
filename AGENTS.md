## Project

**Running Dashboard**

Running Dashboard ist eine lokale Webanwendung zur Verwaltung und Auswertung eigener Lauftrainings aus Garmin-/FIT-Dateien. Nutzer importieren FIT-Dateien, sehen die letzten sechs Wochen als Dashboard und können einzelne Läufe mit Kennzahlen, Karte, Zeitreihen und Laps untersuchen.

**Core Value:** Die Anwendung ersetzt die manuelle Sicht auf Garmin-Läufe durch ein schnelles, nachvollziehbares 6-Wochen-Dashboard auf Basis korrekter und wiederverwendbarer Trainingsdaten.

### Constraints

- **Scope**: Nur Lauftraining und FIT-Import im MVP — zukünftige Coaching-Funktionen dürfen den ersten Release nicht verzögern
- **Privacy**: Laufdaten und GPS-Tracks bleiben lokal — keine Cloud-Abhängigkeit oder externe Analyse
- **Data integrity**: FIT-Zeitstempel, GPS-Semicircles, optionale Sensorwerte und Duplikate korrekt behandeln — Trainingsdaten sind wichtiger als UI-Politur
- **Persistence**: PostgreSQL für normalisierte Daten, Originaldateien optional im konfigurierbaren Dateisystem — keine Binärdateien in PostgreSQL
- **Compatibility**: Lokale Zeitzone nur für Anzeige, intern UTC/Instant — Sommer-/Winterzeit darf Datumswerte nicht verschieben

## Technology Stack

## Recommended Stack

### Core Framework

| Technology | Version | Purpose | Why |
|------------|---------|---------|-----|
| Java | 25 LTS | Backend runtime and language | Current configured Gradle toolchain and LTS baseline for the local Spring Boot application. |
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

### FIT ingestion

| Technology | Version | Purpose | Why |
|------------|---------|---------|-----|
| Garmin FIT SDK for Java | `com.garmin:fit:21.171.0` in the official example; verify the newest Maven Central SDK release before pinning | Decode FIT files into messages | Official Java decoder, Garmin profile knowledge, Maven artifact, and forward-compatible FIT format. It is a better MVP choice than writing a FIT parser or translating through another language. |

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

## Alternatives Considered

| Category | Recommended | Alternative | Why Not |
|----------|-------------|-------------|---------|
| Backend runtime | Java 25 + Spring Boot 4.1.x | Java 21 or Boot 3.5.x | Java 21 is an alternative for a constrained runtime; Boot 3.5 is viable when an existing ecosystem or dependency blocks Boot 4, but should not be selected without a concrete blocker. |
| Build | Gradle Kotlin DSL | Maven | Maven is equally supportable and may be preferable for team convention. Do not maintain both build systems. |
| ORM access | JPA for aggregates + SQL projections | jOOQ or JDBC everywhere | jOOQ is attractive for query-heavy systems but adds code-generation/setup; plain JDBC everywhere loses useful aggregate mapping. Revisit if dashboard SQL becomes complex. |
| Schema migration | Flyway | Liquibase | Liquibase is valid, but SQL-first Flyway is less ceremony for a single local PostgreSQL schema. |
| FIT parsing | Official Garmin FIT Java SDK | Third-party parser or Python subprocess | Avoids an extra runtime and format translation. A third-party parser is only a fallback if the official artifact cannot decode required Garmin fixtures. |
| Map | Leaflet + raster tiles | MapLibre GL JS | MapLibre is the future-facing option for vector maps but unnecessary for one polyline and introduces style/tile-provider decisions. |
| Charts | `vue-echarts` / ECharts | Chart.js, uPlot, Plotly | ECharts directly covers linked data zoom and multiple metric charts. Use uPlot later only if measured rendering cost becomes material; Plotly is overkill for this UI. |
| Database | PostgreSQL 18 | SQLite | SQLite would simplify distribution, but PostgreSQL is an explicit project constraint and gives durable migration/index/constraint behavior for the planned data model. |

## Implementation-ready dependency outline

## Coarse vertical-MVP roadmap implications

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

## Conventions

Conventions not yet established. Will populate as patterns emerge during development.

### Integrationsvalidierung

- Nach jeder größeren Integration prüfen, ob die Anwendung weiterhin baut und die Tests erfolgreich laufen.
- Falls der vollständige Build oder die Tests zu lange dauern, zunächst nur die relevanten Smoke-Tests ausführen.
## Architecture

Architecture not yet mapped. Follow existing patterns found in the codebase.
## Project Skills

No project skills found. Add skills to any of: `.claude/skills/`, `.agents/skills/`, `.cursor/skills/`, `.github/skills/`, or `.codex/skills/` with a `SKILL.md` index file.
## Developer Profile

> Profile not yet configured.
