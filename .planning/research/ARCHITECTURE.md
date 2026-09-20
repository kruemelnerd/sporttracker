# Architecture Patterns

**Domain:** Lokales Lauf-Dashboard mit FIT-Import
**Researched:** 2026-09-20

## Recommended Architecture

Use one locally hosted Spring Boot application with a versioned HTTP API and a separately developed Vue 3/Vite frontend. Keep the boundary explicit: Vue owns navigation, presentation, formatting, map/chart interaction, and upload progress; Spring owns FIT decoding, normalization, validation, duplicate identity, persistence, and all dashboard/detail queries. Do not expose JPA entities or FIT SDK types to the frontend.

```text
Vue 3 / TypeScript / Router
  ├─ typed API client ────────────────┐
  ├─ dashboard view                  │ HTTP JSON + multipart
  ├─ import view                     ▼
  └─ detail view             Spring Boot REST controllers
                                      │
                           application services / DTO mappers
                              ├─ ImportService
                              │    ├─ file hash + file storage
                              │    ├─ FIT decoder adapter
                              │    ├─ normalizer/validator
                              │    └─ duplicate classifier
                              └─ query services
                                   ├─ dashboard projections
                                   └─ activity detail projection
                                      │
                       PostgreSQL normalized data + local FIT files
```

The FIT decoder is an adapter behind the import application service. Its output should be an internal import model, not persistence entities. The stable domain boundary is `Activity` (one running session), `ActivityRecord` (sparse time-series samples), and `Lap`, with `ImportFile` metadata tracking the original file, hash, and outcome. Keep raw FIT files in a configured, content-addressed filesystem directory; PostgreSQL stores metadata and normalized values, never the binary.

### Component Boundaries

| Component | Responsibility | Communicates With |
|-----------|----------------|-------------------|
| Vue route views | Dashboard, import, and activity-detail screens; loading/empty/error states | Typed API client, map/chart adapters |
| Typed API client | Serialize query parameters, multipart files, and JSON DTOs; normalize HTTP errors | Spring REST API |
| Import controller | Accept a bounded multi-file request and return one outcome per file | Import service |
| Import service | Process each file independently; classify duplicate, skipped, invalid, and imported outcomes | File store, decoder, normalizer, repositories |
| FIT decoder adapter | Decode `FileId`, session/activity, lap, record, and event messages | Garmin FIT SDK; import model |
| Normalizer/validator | Convert FIT epoch to `Instant`, semicircles to decimal degrees, units to canonical units, and invalid optional values to `null` | Import model, import service |
| File store | SHA-256 content-addressed original files; no user filename as a path | Configured local filesystem |
| Activity repository | Persist aggregate summary and enforce identity constraints | PostgreSQL |
| Record/lap repositories | Bulk insert child rows and query detail rows in deterministic order | PostgreSQL |
| Dashboard query service | Six-week aggregate, weekly buckets, and summary activity list | SQL projections/repositories |
| Detail query service | Activity summary plus laps and records, optionally downsampled for transport | SQL projections/repositories |
| Map/chart adapters | Render a local GPS polyline and nullable series; own third-party library details | Detail DTOs, Leaflet/ECharts |

Avoid a separate ingestion worker, message broker, or microservice for the MVP. Uploads are small local batches and synchronous processing keeps the result understandable. Add background jobs only if real files make request time or memory unacceptable.

## Data Flow

### Import flow

1. Vue sends `multipart/form-data` with multiple `.fit` parts. The backend applies explicit per-file, total-request, file-count, and timeout limits; never trust the filename or MIME type as proof of FIT content.
2. For each file, copy to a temporary controlled location, compute SHA-256 while reading, and keep the original under a content-addressed path such as `files/ab/cd/<hash>.fit`. Do not construct a path directly from the client filename.
3. Decode the file through the Garmin SDK. Extract session/activity summaries, laps, records, and relevant IDs. Unknown fields and absent optional messages are normal; they must not become zeroes or abort another file in the same request.
4. Normalize into canonical values: `Instant`/UTC timestamps, metres, seconds, m/s or seconds-per-kilometre, beats/minute, decimal latitude/longitude, and nullable sensor values. Reject or drop only clearly invalid records (for example, impossible coordinates or missing record timestamps), while retaining the source file.
5. Classify duplicate before writing. File hash is the certain same-file key; combine FIT activity identity and start time (with a uniqueness constraint) to catch the same activity exported into a different file.
6. Persist one complete activity, its records, and laps in one database transaction. The transaction is per file, not per HTTP batch. On rollback, leave the original file as a diagnosable artifact and report the file failure; never partially count an activity.
7. Return an outcome for every input file: `IMPORTED`, `DUPLICATE`, `SKIPPED_NON_RUN`, `INVALID`, or `FAILED`, including a safe message and activity ID where applicable.

Spring Boot MVC supports `MultipartFile[]` and configurable multipart size limits; use those limits as an application safety boundary rather than relying on default 1 MB/10 MB values. The official FIT activity model describes summary messages and timestamped records, and Garmin's Java example decodes an input stream into `FitMessages`. These sources support the adapter-and-normalizer split, but real Garmin fixtures must validate the exact SDK API and message edge cases.

### Read flow

Dashboard requests must not load `ActivityRecord` rows. Compute the local six-week boundary in the configured display zone, convert the boundary instants to UTC, then query `activity.start_time` with a half-open range. Return six explicit calendar-week buckets, including zero-valued buckets, plus aggregate metrics and a newest-first activity summary list.

Activity detail is on-demand. Fetch the summary, laps, and records by activity ID in separate bounded queries or one deliberately designed projection. Return canonical numeric values and nullable fields; let Vue format pace, duration, dates, and units. If the record count is large, return a deterministic downsampled series for charts while retaining all records in PostgreSQL and exposing the raw/detail policy in the response. Map payloads should be derived from valid record coordinates only, not from a separate map table.

## API Shape

Keep the internal API small and task-shaped rather than mirroring tables:

```text
POST /api/imports
  multipart files[]
  -> { results: [{ fileName, status, message, activityId }] }

GET /api/dashboard?from=...&to=...
  -> { range, summary, weeks[], activities[] }

GET /api/activities/{id}
  -> { activity, laps[], series: { track[], pace[], heartRate[], elevation[], cadence[] } }
```

The six-week default belongs to the backend so all clients share the same calendar and timezone semantics; optional `from`/`to` parameters are useful for testing but should be validated and bounded. Error responses should use one predictable shape (`code`, `message`, optional `fileName`, optional field details). Do not return stack traces, filesystem paths, or raw FIT parser internals.

`activity` should contain summary values and source/availability metadata. `series` points should carry `elapsedSeconds`, `distanceMeters`, and only the measurements present at that sample; `null` means unavailable, never “measured zero”. The frontend should not infer missing series from another series. Include a `schemaVersion` only if response evolution becomes necessary; avoid speculative versioning for the first internal API.

## Persistence and Query Strategy

Recommended relational shape:

```text
import_file(id, sha256 unique, original_path, original_name, byte_size, imported_at, status)
activity(id, import_file_id, fit_activity_id, fit_start_time, start_time, sport, sub_sport,
         duration_seconds, moving_time_seconds, distance_meters, average_speed_mps,
         average_pace_seconds_per_km, average_hr, max_hr, average_cadence,
         elevation_gain_meters, elevation_loss_meters, calories, training_effect...)
activity_record(id, activity_id, timestamp, elapsed_seconds, distance_meters,
                latitude, longitude, altitude_meters, speed_mps, heart_rate, cadence)
lap(id, activity_id, lap_index, start_time, duration_seconds, distance_meters,
    average_speed_mps, average_pace_seconds_per_km, average_hr, max_hr,
    average_cadence, elevation_gain_meters, elevation_loss_meters)
```

Use database constraints for integrity: foreign keys with cascade from activity to children, non-negative checks for durations/distances where applicable, a unique file hash, and a practical activity identity unique constraint. Be cautious with a global unique FIT activity ID if devices omit it or reuse it; include source/start-time semantics and handle ambiguous duplicates in application classification.

Indexes should be minimal and query-driven: `activity(start_time DESC)` for the six-week list, a unique index on file hash, `activity_record(activity_id, elapsed_seconds)` for chart/map order, and `lap(activity_id, lap_index)`. PostgreSQL B-tree indexes support range predicates and ordered retrieval, but at the stated scale an aggregate projection query is preferable to loading entities. Verify plans with `EXPLAIN` only after representative fixtures exist. Do not introduce TimescaleDB, Redis, a search index, or JSON-only storage for this MVP.

## Timezone and Privacy Boundaries

Persist FIT timestamps as `Instant`/UTC and keep the user's IANA display timezone in application configuration. Compute “current week plus five previous weeks” in that display zone, then query with UTC instants; do not derive week boundaries from the server's default zone. Format dates and start times in Vue using the response/configured zone, and test DST transitions explicitly. Store elapsed durations independently from wall-clock timestamps.

GPS is sensitive even for a single local user. Keep FIT files, tracks, database, and logs local; never log full uploads or coordinates. Restrict file paths to the configured data root, avoid exposing a static file directory, and validate all upload content at the trust boundary. OSM tiles are an external exception: the browser requests only visible background tiles, with visible attribution and a configurable provider URL. The public OSM raster service is best-effort and prohibits bulk prefetch/offline use, so do not proxy workout data, prefetch tiles, or make the tile provider a hard-coded architectural dependency.

## Patterns to Follow

### Pattern 1: Decode → normalize → persist
**What:** Keep FIT SDK message handling separate from canonical domain mapping and from database writes.
**When:** Every import, including future re-parsing of preserved originals.
**Why:** SDK quirks, semicircle conversion, FIT epoch conversion, optional fields, and duplicate policy remain testable without a database or UI.

### Pattern 2: Per-file transaction and outcome
**What:** Run each file through its own application-service transaction and collect a result object for the batch.
**When:** Multi-file uploads.
**Why:** One corrupt or non-running file cannot roll back valid neighboring files, while a single activity cannot be counted partially.

### Pattern 3: Task-shaped read models
**What:** Dashboard and detail endpoints use explicit projections/DTOs rather than returning entities.
**When:** All frontend reads.
**Why:** Prevents accidental record fan-out on the dashboard, makes nullable semantics explicit, and permits chart downsampling without losing stored raw records.

### Pattern 4: Null-preserving visualization
**What:** Carry absent metrics as `null`/gaps through API, chart series, and UI.
**When:** HR, cadence, elevation, GPS, training effect, and laps vary by device/file.
**Why:** Zero is a real measurement and silently substituting it corrupts interpretation.

## Anti-Patterns to Avoid

### Anti-Pattern 1: JPA entity graph as API
**What:** Serialize `Activity` with eager records and laps.
**Why bad:** Dashboard queries become large, circular, unstable payloads and may load every track point.
**Instead:** Use repository projections and separate dashboard/detail payloads.

### Anti-Pattern 2: Whole-batch transaction
**What:** Wrap all uploaded files in one transaction.
**Why bad:** A single malformed file makes successful imports appear to fail and encourages retry duplication.
**Instead:** Transaction per file with explicit outcome reporting.

### Anti-Pattern 3: Store formatted values or rounded GPS
**What:** Persist `"5:23"` pace strings or early-rounded coordinates.
**Why bad:** Sorting, aggregation, charting, and later analysis lose precision and unit semantics.
**Instead:** Store numeric canonical units and format only at the UI boundary.

### Anti-Pattern 4: Treat upload filename/MIME as trust
**What:** Build storage paths from client filenames or accept any file named `.fit`.
**Why bad:** Path traversal, collisions, misleading errors, and parser abuse risk.
**Instead:** bound request sizes, use generated/content-addressed paths, inspect/decode content, and avoid returning sensitive parser details.

## Coarse Vertical-MVP Phases and Integration Risks

1. **Foundation + one real FIT** — Boot/Vue shell, PostgreSQL/Flyway, configured file root, API skeleton, and a decoder spike against real Garmin fixtures. **Risk retired:** SDK artifact/API, FIT timestamp and semicircle handling, fixture availability.
2. **Reliable import vertical** — multi-file upload, per-file outcomes, normalization, duplicate constraints, file preservation, and Activity/Lap/Record persistence. **Risk retired:** transaction atomicity, partial failures, missing optional data, non-run behavior, and storage permissions.
3. **Six-week dashboard vertical** — backend calendar-window/weekly aggregate queries, typed dashboard DTO, Vue list/summary/empty states. **Risk retired:** local timezone/DST semantics and query payload discipline.
4. **Detail vertical** — activity summary, laps, records, nullable series, detail route, and bounded/downsampled payload strategy. **Risk retired:** child-row volume, deterministic ordering, and API/UI optional-field contracts.
5. **Map/chart integration + hardening** — Leaflet adapter with configurable tiles/attribution, ECharts series and synchronized inspection if cheap, then end-to-end import-to-dashboard verification. **Risk retired:** browser rendering, map policy/privacy, large-series responsiveness, and graceful degradation.

Do not split these into infrastructure, backend, and frontend-only phases: each phase should produce a user-observable vertical slice or retire a concrete data-integrity risk. The highest-risk dependency order is FIT decoding and persistence before dashboard aggregation, and aggregation before visual polish.

## Scalability Considerations

| Concern | At 100 users | At 10K users | At 1M users |
|---------|---------------|--------------|--------------|
| This MVP's local single user | One Spring process and PostgreSQL instance | Not an MVP target; add authentication/tenant boundaries before sharing | Requires a new hosted architecture and is explicitly out of scope |
| Activity records | Indexed PostgreSQL rows; bulk insert per import | Partition/archive only after measured growth | Time-series/partition strategy and asynchronous ingestion likely required |
| Detail payloads | On-demand full data or simple deterministic downsampling | Server-side series materialization/cache per activity | Precomputed multi-resolution series and CDN/API limits |
| FIT storage | Configured local content-addressed filesystem | Managed object storage with encryption and lifecycle policy | Object storage, tenant isolation, retention and disaster recovery |
| Map tiles | Normal interactive provider usage | Dedicated/provider-backed or self-hosted tiles | Dedicated tile infrastructure and strict privacy controls |

## Integration Risks Requiring Explicit Tests

- Garmin SDK version and Java API differ from examples; pin and test against real `.fit` fixtures, including corrupt/truncated input.
- FIT files may contain multiple sessions or message combinations; define whether one file can produce multiple activities before enforcing identity constraints.
- FIT epoch/timezone conversion and local calendar weeks can shift dates at midnight or DST boundaries; use fixed-clock tests around both transitions.
- Semicircle conversion, invalid coordinates, GPS gaps, and duplicate points can make map bounds or polylines invalid; filter only invalid points and hide the map when fewer than two usable points remain.
- JPA cascades and bulk child inserts can cause memory or N+1 behavior; inspect SQL and keep dashboard queries record-free.
- Multipart temporary files and permanent originals can diverge on failure; clean abandoned temp files and make final file placement idempotent by hash.
- Public OSM tiles have no SLA and policy constraints; keep the provider switchable and ensure map failure does not block activity detail.

## Sources

- Garmin FIT Java example and activity decoding cookbook: https://developer.garmin.com/fit/example-projects/java and https://developer.garmin.com/fit/cookbook/decoding-activity-files (**MEDIUM**, official Context7-verified digest)
- Garmin FIT DateTime cookbook: https://developer.garmin.com/fit/cookbook/datetime (**MEDIUM**, official Context7-verified digest)
- Spring Boot MVC multipart handling: https://docs.spring.io/spring-boot/how-to/spring-mvc.html and `MultipartProperties` (**MEDIUM**, official Context7-verified digest)
- PostgreSQL indexes and B-tree behavior: https://www.postgresql.org/docs/current/indexes-types.html and https://www.postgresql.org/docs/current/sql-createindex.html (**MEDIUM**, official Context7-verified digest)
- Vue Composition API and TypeScript: https://vuejs.org/guide/reusability/composables.html and https://vuejs.org/guide/typescript/composition-api (**MEDIUM**, official Context7-verified digest)
- OpenStreetMap tile usage policy: https://operations.osmfoundation.org/policies/tiles/ (**LOW for broad ecosystem conclusion; direct policy text inspected, web-search provider unavailable**)
