# Phase 2: Reliable Import - Research

**Researched:** 2026-09-21  
**Domain:** Robust local multi-file Garmin FIT import, normalization, and persistence  
**Confidence:** HIGH for project constraints and architecture; MEDIUM for exact Garmin SDK edge behavior

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

### Duplicate identity
- **D-01:** Treat an identical SHA-256 as an unequivocal duplicate; use FIT activity identity and start time as additional identity signals for re-exports with different bytes.
- **D-02:** When FIT activity ID and start time identify an already imported activity, classify the new file as `DUPLICATE` and do not create a second Activity. Keep the file hash available as the import-file identity if the implementation records that metadata.
- **D-03:** Phase 2 assumes one FIT file represents one Activity. Files containing multiple sessions or multiple candidate activities are not partially imported; classify them as an actionable invalid/failed outcome.
- **D-04:** If the FIT activity ID is missing or unusable, use a conservative fallback based on start time plus sport and normalized duration/distance. Only sufficiently matching data is a duplicate; otherwise import a new Activity.

### File storage and retry
- **D-05:** Persist the original FIT file in the configurable local filesystem only after a successful import. Invalid, failed, skipped, and duplicate inputs do not become durable original files by default.
- **D-06:** Enforce configurable conservative maximum file-size and batch-count limits. Exceeding a limit produces an understandable upload result before that file/batch is processed.
- **D-07:** Retry only the selected failed file. Existing successful, duplicate, skipped, and other results from the same batch remain intact and visible; retry must not restart the whole batch.
- **D-08:** Process each file atomically in its own transaction. A file failure must not roll back neighboring valid files and must not leave partial Activity, Record, or Lap data visible.

### the agent's Discretion
- Exact default values for configurable upload limits, provided they are conservative, documented, and testable.
- Exact naming and transport shape of the internal import endpoint and per-file outcome DTO.
- Exact database schema, indexes, and repository technology used to enforce the decisions above.
- Exact user-facing wording and UI layout, provided every file receives an understandable actionable outcome and retry remains per-file.

### Deferred Ideas (OUT OF SCOPE)
None — discussion stayed within phase scope.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research support |
|----|-------------|------------------|
| IMPT-01 | User can select one or more `.fit` files through a file picker. | Native multiple file input; typed multipart client. |
| IMPT-02 | User can add one or more `.fit` files through drag and drop. | Same import queue as picker; prevent default browser navigation. |
| IMPT-03 | User sees a per-file import status while a batch is processed. | Client queue keyed by stable local item id and outcome DTO. |
| IMPT-04 | User receives an actionable result for each file: imported, duplicate, skipped non-run, invalid, or failed. | Backend outcome state and safe error code/message. |
| IMPT-05 | User can retry a failed file without losing successful results from the same batch. | Retry calls a single-file endpoint or single-file multipart request. |
| IMPT-06 | User can re-import the same FIT file without creating a duplicate activity. | Hash-first idempotency plus activity identity constraints. |
| IMPT-07 | User is informed when a FIT file contains no running activity and it is skipped from the running dashboard. | Classify sport before persistence; return explicit skip. |
| IMPT-08 | User can import valid files even when another file in the same batch is corrupt or unsupported. | Per-file transaction and exception isolation. |
| DATA-01 | User's imported running activity stores start time, sport/subsport, duration, moving time, distance, speed/pace, heart rate, cadence, elevation, calories, and available Training Effect values. | Canonical nullable Activity summary mapping. |
| DATA-02 | User's imported activity preserves available track records with timestamp, elapsed time, distance, GPS coordinates, altitude, speed, heart rate, and cadence. | Sparse normalized child rows and deterministic order. |
| DATA-03 | User's imported activity preserves available lap data with lap number, duration, distance, pace, heart rate, cadence, and elevation. | Lap mapping from SDK messages. |
| DATA-04 | User can rely on FIT timestamps being displayed in the local timezone without date shifts at midnight or daylight-saving transitions. | Persist `Instant`; localize only at presentation/query-boundary tests. |
| DATA-05 | User can rely on FIT GPS semicircle coordinates being converted to valid latitude/longitude values without premature rounding. | SDK conversion boundary and precision tests. |
| DATA-06 | User sees missing FIT measurements as unavailable or omitted, never as a fabricated numeric zero. | Boxed/nullable domain and DTO fields; null-preserving UI. |
| DATA-07 | User's FIT data and GPS tracks remain stored locally and are not sent to analytics or external analysis services. | Local filesystem/PostgreSQL only; no analytics client. |
| QUAL-02 | The application provides automated coverage for successful import, core values, records, GPS conversion, laps, missing sensors, duplicates, corrupt files, and non-running activities. | Fixture matrix and backend integration test plan below. |
</phase_requirements>

## Summary

Phase 2 should be implemented as one synchronous, task-shaped import vertical: bounded multipart request → per-file temporary handling and SHA-256 → Garmin SDK decode → internal normalization/validation → non-run/duplicate classification → one transaction for Activity, records, laps, and import metadata → original-file move only after a successful commit → one outcome per input. Spring Boot natively supports multipart arrays and configurable size limits; Spring transaction documentation confirms that an independent transaction requires a proxy-visible transactional method. [CITED: https://docs.spring.io/spring-boot/how-to/spring-mvc.html] [CITED: https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html]

The current repository already has the correct seam: Java 21/Spring MVC/JDBC/Flyway, Garmin FIT SDK `21.171.0`, a `Decode`/`MesgBroadcaster` listener adapter, Vue 3/Vite/TypeScript, Vitest, and Playwright. The implementation should generalize that adapter without leaking SDK types, add an additive Flyway migration after `V1__foundation.sql`, and extend the explicit `/api` client rather than introducing a new dependency or root build. [VERIFIED: backend/build.gradle.kts:10-26] Quote: `languageVersion = JavaLanguageVersion.of(21)`, `implementation("com.garmin:fit:21.171.0")`, `implementation("org.springframework.boot:spring-boot-starter-jdbc")`, `implementation("org.springframework.boot:spring-boot-starter-flyway")`. [VERIFIED: frontend/package.json:6-27] Quote: `"test:unit": "vitest run"`, `"@playwright/test": "^1.63.0"`, `"vue": "^3.5.13"`, `"vite": "^7.1.7"`.

**Primary recommendation:** Keep import classification and persistence backend-owned, make every file an independent transaction/outcome, and preserve all optional FIT values as nullable canonical data.

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Picker/drag-drop and visible queue | Browser / Client | Frontend Server — | The browser owns `File` objects, selection, drop prevention, progress, and retry button state. [CITED: https://vuejs.org/api/sfc-script-setup] |
| Multipart limits and request validation | API / Backend | Browser / Client | The backend is the trust boundary; client `accept=".fit"` is only a convenience. Spring Boot exposes server-side file/request limits. [CITED: https://docs.spring.io/spring-boot/how-to/spring-mvc.html] |
| FIT decoding and message collection | API / Backend | — | Garmin SDK types belong behind an ingestion adapter. [VERIFIED: backend/src/main/java/local/runningdashboard/GarminFitFixtureAdapter.java:23-38] Quote: `Decode decode = new Decode();`, `MesgBroadcaster broadcaster = new MesgBroadcaster(decode);`, `if (!decode.read(input, broadcaster, broadcaster))`. |
| Canonical units, UTC, GPS, nullable values | API / Backend | Database / Storage | Normalization must happen once before persistence; the database stores canonical numeric values. [VERIFIED: backend/src/main/java/local/runningdashboard/GarminFitFixtureAdapter.java:48-79] Quote: `startTime = mesg.getStartTime().getDate().toInstant();` and `distanceMeters = mesg.getTotalDistance().doubleValue();` |
| Duplicate identity and classification | API / Backend | Database / Storage | Application classification creates understandable outcomes; constraints close race-condition gaps. [CITED: https://www.postgresql.org/docs/18/indexes-unique.html] |
| Atomic Activity/Record/Lap persistence | Database / Storage | API / Backend | PostgreSQL owns foreign keys/uniqueness; the service owns the per-file transaction boundary. [CITED: https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html] |
| Original FIT retention | Database / Storage | API / Backend | Files remain outside PostgreSQL in configured local storage and are finalized only after successful import. [VERIFIED: AGENTS.md:13-17] Quote: `Originaldateien optional im konfigurierbaren Dateisystem — keine Binärdateien in PostgreSQL` |

## Standard Stack

### Core

| Library | Version | Purpose | Why standard |
|---------|---------|---------|--------------|
| Java | 21 | Backend runtime | Existing toolchain and Phase 1 decision. [VERIFIED: backend/build.gradle.kts:10-13] Quote: `JavaLanguageVersion.of(21)` |
| Spring Boot MVC | 4.1.1 / managed | Multipart REST endpoint and error handling | Already installed; native servlet multipart support avoids another upload dependency. [VERIFIED: backend/build.gradle.kts:3,16-20] Quote: `id("org.springframework.boot") version "4.1.1"` and `spring-boot-starter-webmvc`. [CITED: https://docs.spring.io/spring-boot/how-to/spring-mvc.html] |
| Garmin FIT Java SDK | 21.171.0 | Decode Activity, Session, Lap, and Record messages | Official SDK already pinned and proven against a real fixture. [VERIFIED: backend/build.gradle.kts:21] Quote: `implementation("com.garmin:fit:21.171.0")`. |
| PostgreSQL + JDBC | 18 image / Boot-managed driver | Durable normalized storage | Project constraint; nullable columns and unique B-tree indexes fit this model. [VERIFIED: compose.yaml:1-20] Quote: `image: postgres:18`. [CITED: https://www.postgresql.org/docs/18/ddl-constraints.html] |
| Flyway | Boot-managed | Additive schema migration | Existing baseline and checksum validation. [VERIFIED: backend/src/main/resources/db/migration/V1__foundation.sql:1-7] Quote: `CREATE TABLE foundation_schema`. [CITED: https://documentation.red-gate.com/flyway/reference/versioned-migrations] |

### Supporting

| Library | Version | Purpose | When to use |
|---------|---------|---------|-------------|
| Vue | 3.5.x currently in lockfile | Import screen and reactive per-file state | Use `<script setup lang="ts">`; keep DTOs explicit. [VERIFIED: frontend/package.json:13-16] Quote: `"vue": "^3.5.13"`. [CITED: https://vuejs.org/api/sfc-script-setup] |
| Vitest + Vue Test Utils | Existing lockfile versions | Component/API state tests | Test queue transitions, nullable rendering, and retry without a browser. [VERIFIED: frontend/package.json:11,21,26] Quote: `"test:unit": "vitest run"`, `"@vue/test-utils": "^2.4.6"`. [CITED: https://vuejs.org/guide/scaling-up/testing.html] |
| Playwright | 1.63.x currently in project | Browser import smoke | Use one representative multi-file flow and assert visible outcomes. [VERIFIED: frontend/package.json:18] Quote: `"@playwright/test": "^1.63.0"`. |

### Alternatives Considered

| Instead of | Could use | Tradeoff |
|------------|-----------|----------|
| Synchronous per-file service | Queue/worker | Not justified for local MVP; adds retry/state complexity before request-time evidence. [ASSUMED] |
| JDBC repositories | JPA aggregate mapping | JDBC matches the existing build and gives predictable bulk child inserts; do not add ORM solely for this phase. [VERIFIED: backend/build.gradle.kts:16-20] Quote: `spring-boot-starter-jdbc`. |
| Content-addressed local filesystem | Binary PostgreSQL column | Local file retention is locked; PostgreSQL must contain metadata, not FIT bytes. [VERIFIED: AGENTS.md:15-17] Quote: `PostgreSQL für normalisierte Daten, Originaldateien optional im konfigurierbaren Dateisystem — keine Binärdateien in PostgreSQL`. |

**Installation:** No new external package is required for the recommended implementation; use the dependencies already present in `backend/build.gradle.kts` and `frontend/package.json`. [VERIFIED: backend/build.gradle.kts:16-26] [VERIFIED: frontend/package.json:13-27]

## Package Legitimacy Audit

Not applicable: this phase research recommends no new package installation. Existing dependencies are already present in the repository; implementation should not add an upload helper, FIT parser, ORM, queue, or state-management package without a new decision.

## Architecture Patterns

### System Architecture Diagram

```text
picker / drop
    -> Vue import queue (one stable item per File)
    -> POST multipart files[]
    -> Spring controller: count/size/content boundary checks
    -> per-file service invocation
         -> temp file + SHA-256
         -> Garmin Decode/MesgBroadcaster adapter
         -> internal model: summary + records + laps
         -> normalize: Instant, metres, seconds, nullable sensors, GPS degrees
         -> classify: limit / invalid / non-run / duplicate / import
         -> one DB transaction: import metadata + Activity + children
         -> after commit: atomic move to hash-derived local path
    <- one safe outcome per input
    -> Vue status/result rows and retry only for failed item
```

### Recommended Project Structure

```text
backend/src/main/java/local/runningdashboard/
├── importapi/          # controller, request/result DTOs, safe error mapping
├── importservice/      # orchestration and per-file transaction boundary
├── fit/                # Garmin adapter and internal decoded model
├── domain/             # canonical Activity/Record/Lap/import metadata model
└── persistence/        # JDBC repositories and row mappers
backend/src/main/resources/db/migration/
└── V2__activity_import.sql
frontend/src/
├── api.ts              # typed multipart call and outcome DTO
└── views/ImportView.vue # picker/drop zone, queue, retry
```

This is a recommended grouping, not an assertion about existing paths. The existing adapter is currently in the root package and should be generalized with the smallest move that preserves the Phase 1 tests. [VERIFIED: .planning/phases/02-reliable-import/02-CONTEXT.md:66-82] Quote: `GarminFitFixtureAdapter` and `Add import API/controller and service beneath the existing Spring MVC application and `/api` Vite proxy.`

### Pattern 1: Decode → normalize → validate → classify → persist

**What:** Collect SDK messages into an internal import object, normalize all units and nullable values, validate one-file invariants, classify non-run/duplicate/invalid cases, then persist only importable data. [CITED: https://developer.garmin.com/fit/cookbook/decoding-activity-files/]

**When to use:** Every uploaded file and future re-parse of a retained original. [VERIFIED: .planning/research/ARCHITECTURE.md:120-124] Quote: `Keep FIT SDK message handling separate from canonical domain mapping and from database writes.`

**Implementation guidance:** Register listeners for FileId/Activity/Session/Lap/Record where supported by the pinned SDK. Collect all candidates, then require exactly one candidate activity for this phase; multiple sessions/candidates are not partially persisted. Preserve unknown fields and absent optional messages as absence, not failure. Validate timestamps, non-negative durations/distances, and coordinates before mapping to database rows. [VERIFIED: .planning/phases/02-reliable-import/02-CONTEXT.md:17-20] Quote: `one FIT file represents one Activity` and `not partially imported`.

### Pattern 2: One file, one transaction, one outcome

**What:** The batch controller loops over bounded files, but each file invokes a public method on a separate Spring bean annotated for its own transaction. `REQUIRES_NEW` is useful if an outer request transaction exists; otherwise a normal per-file transaction is sufficient. [CITED: https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html]

**Critical detail:** Do not put the transactional method behind self-invocation; Spring's proxy-based transaction advice does not intercept it. [CITED: https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html]

```java
// Illustrative shape; keep the actual DTO/domain names project-specific.
public BatchResult importBatch(List<MultipartFile> files) {
    return files.stream().map(file -> perFileImporter.importOne(file)).toList();
}

@Transactional
public FileOutcome importOne(MultipartFile file) {
    // decode, normalize, classify, write all rows, then return one outcome
}
```

The original file is not the transaction's database row payload: write it to a controlled temporary path, commit database rows, then move it to the hash-derived permanent path. If the move fails after commit, return/record an operational failure that is distinguishable from a parse failure and make the finalization idempotent. The locked decision remains that invalid, failed, skipped, and duplicate inputs are not durable originals by default. [VERIFIED: .planning/phases/02-reliable-import/02-CONTEXT.md:23-27] Quote: `Persist the original FIT file ... only after a successful import`.

### Pattern 3: Hash-first identity with conservative activity matching

Use SHA-256 as the unequivocal same-byte key. Before creating an Activity, check the import hash, then FIT activity identity plus start time. If identity is absent/unusable, compare start time plus sport and normalized duration/distance; only a sufficiently matching candidate is a duplicate. Do not globally unique-index a nullable activity ID alone. The application returns the duplicate outcome; a database unique constraint remains the race-condition backstop. [VERIFIED: .planning/phases/02-reliable-import/02-CONTEXT.md:16-20] Quote: `Treat an identical SHA-256 as an unequivocal duplicate` and `use a conservative fallback based on start time plus sport and normalized duration/distance`. [CITED: https://www.postgresql.org/docs/18/indexes-unique.html]

### Pattern 4: Null-preserving canonical model

Use nullable boxed Java fields and nullable TypeScript fields for HR, cadence, elevation, GPS, calories, Training Effect, speed/pace, and any absent summary/lap/record measurement. A measured zero remains zero; absent values remain `null`/omitted. PostgreSQL unique constraints treat nulls as distinct by default, so nullable identity fields need explicit non-null composite keys or application matching rather than accidental uniqueness. [CITED: https://www.postgresql.org/docs/18/indexes-unique.html]

### Anti-Patterns to Avoid

- **Whole-batch transaction:** one corrupt file rolls back valid neighbors; use per-file transaction. [VERIFIED: .planning/phases/02-reliable-import/02-CONTEXT.md:25-27] Quote: `A file failure must not roll back neighboring valid files`.
- **Persist original before validation:** violates the locked retention decision and leaves orphaned files. [VERIFIED: .planning/phases/02-reliable-import/02-CONTEXT.md:23-24] Quote: `only after a successful import`.
- **Filename/MIME as proof or path:** names are untrusted; validate/decode content and derive storage path from hash. [CITED: https://docs.spring.io/spring-boot/how-to/spring-mvc.html]
- **JPA/entity graph as import or API model:** causes child-row fan-out and leaks persistence/SDK types; use JDBC rows and explicit DTOs. [VERIFIED: .planning/phases/01-foundation-and-one-real-fit/01-CONTEXT.md:18-20] Quote: `Vue owns its TypeScript DTOs and must not depend on JPA entities or Garmin FIT SDK types`.
- **Early GPS rounding or formatted pace storage:** loses precision and unit semantics; persist numeric canonical values and format at the UI boundary. [CITED: https://github.com/garmin/fit-javascript-sdk/blob/main/_autodocs/types.md]

## Don't Hand-Roll

| Problem | Don't build | Use instead | Why |
|---------|-------------|-------------|-----|
| FIT binary decoding/CRC/profile mapping | Custom FIT parser | Existing Garmin FIT Java SDK adapter | Official SDK already compiles and decodes the committed fixture; parser correctness is not MVP scope. [VERIFIED: backend/build.gradle.kts:21] Quote: `com.garmin:fit:21.171.0`. |
| Transaction interception | Manual connection/commit orchestration | Spring `@Transactional` on a proxy-visible per-file service | Framework propagation and rollback semantics are already defined. [CITED: https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html] |
| Uniqueness race protection | Java-only check-then-insert | PostgreSQL unique constraints/indexes plus friendly classifier | Database constraints are the final concurrent-import guard. [CITED: https://www.postgresql.org/docs/18/ddl-constraints.html] |
| File naming | User filename paths | SHA-256-derived path under configured root | Avoids traversal/collision and makes finalization idempotent. [VERIFIED: AGENTS.md:15-17] Quote: `Originaldateien optional im konfigurierbaren Dateisystem`. |
| Upload state framework | New global state library | Local Vue `ref`/composable keyed by file item | One import screen does not justify a new dependency. [CITED: https://vuejs.org/guide/reusability/composables.html] |

**Key insight:** Correctness lives at boundaries: the SDK adapter protects FIT semantics, the normalizer protects canonical values, the database protects atomic identity, and the UI only reports outcomes. Custom cross-layer shortcuts blur those boundaries and make retries/data corruption hard to diagnose.

## Common Pitfalls

### Pitfall 1: Multipart limits are mistaken for business validation

**What goes wrong:** A request passes servlet limits but exceeds the configured batch count, contains a non-FIT payload, or consumes too many resources. [CITED: https://docs.spring.io/spring-boot/how-to/spring-mvc.html]  
**How to avoid:** Set explicit `max-file-size` and `max-request-size`; add count and empty-file checks before decode; map limit violations to safe per-file/batch outcomes. Spring Boot's documented defaults are 1 MB per file and 10 MB per request, so do not silently rely on them. [CITED: https://docs.spring.io/spring-boot/how-to/spring-mvc.html]

### Pitfall 2: FIT message order and partial data

**What goes wrong:** Summary is read only from one message type, or a truncated/unsupported file leaves partial rows. [CITED: https://developer.garmin.com/fit/cookbook/decoding-activity-files/]  
**How to avoid:** Accumulate independently, validate after decode, require one candidate activity, and write children only inside the file transaction. Add corrupt/truncated and multi-session fixtures. [VERIFIED: .planning/research/PITFALLS.md:8-20] Quote: `decode → normalize → validate → persist`.

### Pitfall 3: UTC/DST is mixed with local display

**What goes wrong:** `LocalDateTime` or host timezone changes the stored instant or later calendar grouping. [VERIFIED: AGENTS.md:17] Quote: `intern UTC/Instant — Sommer-/Winterzeit darf Datumswerte nicht verschieben`.  
**How to avoid:** Convert SDK time to `Instant` once, persist PostgreSQL `timestamptz`, and use an explicit IANA display zone only for later formatting/query-window calculations. Phase 2 tests should include an imported activity at local midnight and fixtures/clock cases on both DST transitions.

### Pitfall 4: Semicircles and invalid GPS

**What goes wrong:** Raw semicircles are treated as degrees, rounded too early, or invalid samples poison future map bounds. [CITED: https://github.com/garmin/fit-javascript-sdk/blob/main/_autodocs/types.md]  
**How to avoid:** Convert at the adapter/normalizer boundary, retain decimal precision in PostgreSQL, range-check latitude/longitude, map missing/invalid coordinates to null, and retain non-GPS records. Verify one known coordinate and ±90/±180 boundaries.

### Pitfall 5: Duplicate checks race or over-match

**What goes wrong:** Retry creates a second activity, or a weak timestamp-only heuristic merges distinct runs. [VERIFIED: .planning/phases/02-reliable-import/02-CONTEXT.md:16-20] Quote: `Only sufficiently matching data is a duplicate; otherwise import a new Activity.`  
**How to avoid:** Hash-first; then exact activity identity/start time; fallback only with sport and normalized metrics; use a database uniqueness backstop; test concurrent/repeated import and re-export-with-different-bytes cases.

### Pitfall 6: Original file finalization diverges from the database

**What goes wrong:** A database rollback leaves a durable original, or a post-commit filesystem failure leaves metadata pointing at a missing file. [VERIFIED: .planning/research/ARCHITECTURE.md:55-63] Quote: `Persist one complete activity ... in one database transaction` and `Return an outcome for every input`.  
**How to avoid:** Temporary controlled file first; permanent hash path only after commit; use atomic move/no-replace semantics where available; make same-hash finalization idempotent; record path only once the move succeeds or model the post-commit recovery state explicitly.

### Pitfall 7: Nullable fields become fake zeros

**What goes wrong:** Primitive Java/TypeScript fields or chart defaults make missing HR/cadence/GPS/elevation look measured. [VERIFIED: REQUIREMENTS.md:23-29] Quote: `missing FIT measurements as unavailable or omitted, never as a fabricated numeric zero`.  
**How to avoid:** Use nullable fields end to end, sparse record rows, explicit `null` in JSON, and tests for absent sensors and partial records.

## Code Examples

### Garmin listener boundary

The existing adapter is the correct starting pattern: it creates a `Decode`, a `MesgBroadcaster`, registers typed listeners, and converts the SDK date to an `Instant`. [VERIFIED: backend/src/main/java/local/runningdashboard/GarminFitFixtureAdapter.java:23-35,48-79] Quote: `broadcaster.addListener((RecordMesgListener) listener);` and `mesg.getTimestamp().getDate().toInstant()`. Generalize the listener output into an internal model; do not return SDK messages from a controller.

### Transaction boundary

Spring's documented propagation behavior supports an independent physical transaction with `REQUIRES_NEW`; proxy limitations mean the method must be called through another bean/proxy. [CITED: https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html] [CITED: https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html]

```java
// Shape only: the planner should use the repository's chosen domain names.
@Transactional
public FileOutcome importOne(DecodedFile decoded) {
    var activity = activityRepository.insert(decoded.activity());
    recordRepository.insertAll(activity.id(), decoded.records());
    lapRepository.insertAll(activity.id(), decoded.laps());
    return outcomeFor(activity);
}
```

### Frontend upload boundary

Use a native multiple file input and a drop zone feeding the same queue. Vue's `<script setup>` is the established project style, and component tests should assert rendered behavior rather than implementation details. [CITED: https://vuejs.org/api/sfc-script-setup] [CITED: https://vuejs.org/guide/scaling-up/testing.html]

```ts
const form = new FormData()
for (const file of files) form.append('files', file, file.name)
const response = await fetch('/api/imports', { method: 'POST', body: form })
```

The queue must retain existing rows when retrying: replace only the selected failed item's state/result, not the batch array. The backend response must be treated as untrusted input and mapped into the explicit frontend DTO.

## Runtime State Inventory

Not a rename/refactor/migration phase. No runtime-state inventory is required; this phase adds new import state rather than renaming existing deployed state.

## Environment Availability

| Dependency | Required by | Available | Version | Fallback |
|------------|-------------|-----------|---------|----------|
| Java | Backend build/tests | ✓ | 21.0.12 | — |
| Docker Compose | PostgreSQL integration tests | ✓ | Docker 29.8.1; Compose config succeeds | — |
| PostgreSQL container | Persistence tests | ✓ | `postgres:18`, healthy | — |
| Node.js | Frontend tests/build | ✓ | 22.20.0 | — |
| npm | Frontend dependencies/scripts | ✓ | 11.10.0 | — |
| Garmin FIT SDK | FIT decode | ✓ | Gradle dependency `21.171.0` | — |

Availability is observed from this environment; no missing blocking dependency was found. [VERIFIED: backend/build.gradle.kts:21] Quote: `implementation("com.garmin:fit:21.171.0")`.

## Validation Architecture

### Test Framework

| Property | Value |
|----------|-------|
| Framework | JUnit 5 via Gradle; Vitest; Playwright |
| Config file | `backend/build.gradle.kts`, `frontend/vitest.config.ts`, `frontend/playwright.config.ts` |
| Quick run command | `backend/./gradlew test --tests '*Import*'` and `frontend/npm run test:unit` |
| Full suite command | `backend/./gradlew test && frontend/npm run typecheck && frontend/npm run build` |

Existing Phase 1 tests use semantic fixture assertions rather than full payload snapshots. [VERIFIED: backend/src/test/java/local/runningdashboard/GarminFitFixtureAdapterTests.java:25-37] Quote: `assertEquals("running", summary.sport());`, `assertTrue(summary.recordCount() > 0);`, `assertTrue(summary.lapCount() > 0);`.

### Phase Requirements → Test Map

| Req ID | Behavior | Test type | Automated command | File exists? |
|--------|----------|-----------|-------------------|--------------|
| IMPT-01/02 | Picker and drop feed the same multi-file queue | component/browser | `frontend/npm run test:unit`; Playwright import smoke | ❌ Wave 0 |
| IMPT-03/04/05 | Per-file pending/imported/duplicate/skipped/invalid/failed and selected retry | component + browser | `frontend/npm run test:unit` | ❌ Wave 0 |
| IMPT-06 | Same bytes and re-export identity do not create a second Activity | integration | `backend/./gradlew test --tests '*Import*'` | ❌ Wave 0 |
| IMPT-07/08 | Non-run is explicit; corrupt neighbor does not roll back valid file | integration | `backend/./gradlew test --tests '*Import*'` | ❌ Wave 0 |
| DATA-01/02/03 | Summary, nullable fields, ordered records, and laps persist | integration | `backend/./gradlew test --tests '*Import*'` | ❌ Wave 0 |
| DATA-04/05/06 | UTC/DST, semicircle precision/range, and null sensors survive API/storage | unit + integration | `backend/./gradlew test --tests '*Import*'` | ❌ Wave 0 |
| DATA-07 | No analytics/external upload and no sensitive logs | browser/network + review | Playwright network assertions plus log review | ❌ Wave 0 |
| QUAL-02 | Full fixture matrix is automated | integration | `backend/./gradlew test` | ❌ Wave 0 |

### Fixture matrix required

Add offline fixtures under the established `backend/src/test/resources/fit/` convention: valid running with GPS/laps/sensors, valid running without HR/cadence/GPS/laps, non-running, corrupt/truncated, duplicate bytes, same activity with changed bytes, multi-session/multi-candidate, and boundary timestamp/coordinate cases. The committed Phase 1 fixture has verified SHA-256 `fe0710d14f164f033948d3f499ee72886fbf1f11c5193540459cbd37a1d39a67`. [VERIFIED: .planning/phases/01-foundation-and-one-real-fit/01-02-SUMMARY.md:11-24] Quote: `backend/src/test/resources/fit/`, `Records: 3578`, `Laps: 11`, and the SHA-256 value above.

### Wave 0 Gaps

- [ ] Backend import model, decoder adapter tests, and fixture matrix.
- [ ] Flyway migration test against PostgreSQL for constraints and rollback.
- [ ] Per-file transaction test proving a failed child insert leaves no Activity/Record/Lap rows.
- [ ] Frontend ImportView component tests for queue, nullable outcomes, drop, and retry.
- [ ] Playwright multi-file smoke with valid, corrupt, duplicate, non-run, and retryable inputs.

## Security Domain

Security enforcement is enabled at ASVS level 1 in `.planning/config.json`. [VERIFIED: .planning/config.json:47-50] Quote: `"security_enforcement": true`, `"security_asvs_level": 1`.

### Applicable ASVS Categories

| ASVS Category | Applies | Standard control |
|---------------|---------|------------------|
| V2 Authentication | No for local single-user MVP | Do not add accounts/auth in this phase; this is an explicit product boundary. [CITED: `.planning/REQUIREMENTS.md` lines 71-80] |
| V3 Session Management | No | No authenticated session is introduced. [ASSUMED] |
| V4 Access Control | Yes, local filesystem/API boundary | Do not expose the file root as static content; restrict API to local intended use. [VERIFIED: REQUIREMENTS.md:29] Quote: `remain stored locally and are not sent to analytics or external analysis services`. |
| V5 Input Validation | Yes | Enforce count/size/empty-file/content validation, safe filenames, bounded decode, and safe error DTOs. [CITED: https://docs.spring.io/spring-boot/how-to/spring-mvc.html] |
| V6 Cryptography | Yes, integrity not secrecy | Use SHA-256 for content identity; never log FIT bytes, GPS, or secrets. Do not invent encryption requirements for the local MVP. [VERIFIED: AGENTS.md:15-17] Quote: `Laufdaten und GPS-Tracks bleiben lokal` and `keine Binärdateien in PostgreSQL`. |

### Known Threat Patterns for Java/Spring upload stack

| Pattern | STRIDE | Standard mitigation |
|---------|--------|---------------------|
| Path traversal via original filename | Tampering | Ignore filename for path construction; use generated hash path under configured root. [CITED: https://owasp.org/www-community/attacks/Path_Traversal] |
| Oversized/malformed FIT resource exhaustion | Denial of service | Multipart file/request limits, batch count limit, bounded temp storage, decode timeout/size guard. [CITED: https://docs.spring.io/spring-boot/how-to/spring-mvc.html] |
| GPS or parser detail leakage | Information disclosure | Do not log uploads/coordinates/stack traces; return safe code/message only. [VERIFIED: .planning/research/ARCHITECTURE.md:87,112-116] Quote: `Do not return stack traces, filesystem paths, or raw FIT parser internals.` |
| Concurrent retry duplicate | Tampering/repudiation | Hash/activity constraints plus application duplicate classification and race test. [CITED: https://www.postgresql.org/docs/18/indexes-unique.html] |

## State of the Art

| Old approach | Current approach | Impact |
|--------------|------------------|--------|
| Store full FIT bytes in relational rows | Store metadata/normalized rows in PostgreSQL and original bytes in configured local filesystem | Keeps query model relational and honors local binary-storage constraint. [VERIFIED: AGENTS.md:15-17] Quote: `keine Binärdateien in PostgreSQL`. |
| One request-wide transaction | One transaction per file with per-file outcome | Valid neighbors survive corrupt inputs. [VERIFIED: 02-CONTEXT.md:25-27] Quote: `Process each file atomically in its own transaction.` |
| Null sensor values coerced to zero | Nullable canonical values and sparse series | Prevents false measurements and later chart corruption. [VERIFIED: REQUIREMENTS.md:29] Quote: `never as a fabricated numeric zero`. |

## Assumptions Log

| # | Claim | Section | Risk if wrong |
|---|-------|---------|---------------|
| A1 | Synchronous processing remains acceptable for the conservative local batch limits. | Alternatives / Summary | Large files could exceed request time; add measured async processing only if tests demonstrate it. |
| A2 | No authentication/session controls are needed in this local single-user MVP. | Security | Exposing the service beyond localhost would require access control and threat review. |
| A3 | A post-commit filesystem finalization failure can be represented by a recoverable operational state without adding a queue. | Transaction pattern | Database metadata/original-file consistency needs a recovery task if the failure is observed. |

## Resolved Research Decisions

1. **Upload and processing bounds — resolved.** Configure a maximum of 10 files per batch, 10 MiB per file, and 100 MiB per HTTP request. The synchronous Garmin decode has no separate wall-clock cancellation mechanism in this phase; its bounded input is the 10 MiB file limit and any decode exception produces that file's terminal `INVALID` or `FAILED` outcome. The controller returns a typed batch error with `BATCH_COUNT_EXCEEDED` for more than 10 submitted files and `REQUEST_TOO_LARGE` for servlet multipart rejection; neither path begins decoding. These defaults are conservative, configuration-overridable, documented, and test-covered per D-06.
2. **Multi-session, candidate, and truncated FIT handling — resolved.** The adapter collects all relevant messages, then accepts exactly one running candidate/session. Zero candidates, more than one candidate/session, a decoder exception, or an incomplete/invalid decoded aggregate returns an actionable `INVALID` outcome; it writes neither Activity/Record/Lap rows nor a durable original. This deterministic classifier applies regardless of the SDK's particular listener/error sequence and implements D-03 and D-08.
3. **Post-commit original-file finalization — resolved.** Commit normalized rows and import metadata without a durable original path, then atomically move the controlled temporary file to its SHA-256-derived path. Only after a successful move may a separate metadata update record that path. A move failure deletes the temporary file, leaves the normalized imported aggregate valid, returns `IMPORTED` with `originalRetained: false` and a safe operational message, and leaves no metadata path to a missing file. A repeat of the same hash treats an already-present target as idempotent. This preserves D-05: raw bytes become durable only after a successful import and never enter PostgreSQL.

## Sources

### Primary (HIGH confidence)

- `AGENTS.md`, `.planning/REQUIREMENTS.md`, `.planning/ROADMAP.md`, and `02-CONTEXT.md` — locked project/phase constraints and exact requirement values. [VERIFIED: opened this session]
- `backend/build.gradle.kts`, existing adapter/tests, and Phase 1 summaries — current repository stack and proven fixture boundary. [VERIFIED: opened this session]
- Garmin FIT activity/decoding documentation: https://developer.garmin.com/fit/file-types/activity/ and https://developer.garmin.com/fit/cookbook/decoding-activity-files/ [CITED]
- PostgreSQL constraints/indexes: https://www.postgresql.org/docs/18/ddl-constraints.html and https://www.postgresql.org/docs/18/indexes-unique.html [CITED]

### Secondary (MEDIUM confidence)

- Spring Boot multipart: https://docs.spring.io/spring-boot/how-to/spring-mvc.html [CITED; Context7 lookup]
- Spring transaction propagation/proxy limits: https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html and https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html [CITED; Context7 lookup]
- Flyway versioned migrations: https://documentation.red-gate.com/flyway/reference/versioned-migrations [CITED; Context7 lookup]
- Vue script setup/testing/composables: https://vuejs.org/api/sfc-script-setup, https://vuejs.org/guide/scaling-up/testing.html, and https://vuejs.org/guide/reusability/composables.html [CITED; Context7 lookup]

### Tertiary (LOW confidence)

- None used for locked decisions. Exact malformed-file behavior and operational thresholds remain open until fixtures and measurements exist.

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — existing dependencies and source files were read; current npm registry checks also returned Vue 3.5.43, Vue Router 5.3.1, Vite 8.3.0, Vitest 5.0.1, and Playwright 1.63.0, but the phase should retain the repository lockfile rather than upgrade speculatively. [VERIFIED: npm registry]
- Architecture: HIGH — locked context, existing code, and official Spring/PostgreSQL documentation agree on the boundaries. [CITED/VERIFIED]
- Garmin edge behavior: MEDIUM — official concepts are documented, but exact Java SDK behavior for malformed/multi-candidate fixtures must be proven by tests. [CITED]
- Pitfalls: HIGH — project requirements and prior research identify concrete failure modes; thresholds remain assumptions. [VERIFIED/CITED]

**Research date:** 2026-09-21  
**Valid until:** 2026-10-21 for stable architecture; recheck dependency versions and Garmin SDK behavior before implementation if the repository changes.
