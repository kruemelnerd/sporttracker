# Requirements: Running Dashboard

**Defined:** 2026-09-21
**Core Value:** Die Anwendung ersetzt die manuelle Sicht auf Garmin-Läufe durch ein schnelles, nachvollziehbares 6-Wochen-Dashboard auf Basis korrekter und wiederverwendbarer Trainingsdaten.

## v1 Requirements

Requirements for the initial local MVP. Each maps to exactly one roadmap phase.

### Import

- [ ] **IMPT-01**: User can select one or more `.fit` files through a file picker.
- [ ] **IMPT-02**: User can add one or more `.fit` files through drag and drop.
- [ ] **IMPT-03**: User sees a per-file import status while a batch is processed.
- [ ] **IMPT-04**: User receives an actionable result for each file: imported, duplicate, skipped non-run, invalid, or failed.
- [ ] **IMPT-05**: User can retry a failed file without losing successful results from the same batch.
- [ ] **IMPT-06**: User can re-import the same FIT file without creating a duplicate activity.
- [ ] **IMPT-07**: User is informed when a FIT file contains no running activity and it is skipped from the running dashboard.
- [ ] **IMPT-08**: User can import valid files even when another file in the same batch is corrupt or unsupported.

### Data

- [ ] **DATA-01**: User's imported running activity stores start time, sport/subsport, duration, moving time, distance, speed/pace, heart rate, cadence, elevation, calories, and available Training Effect values.
- [ ] **DATA-02**: User's imported activity preserves available track records with timestamp, elapsed time, distance, GPS coordinates, altitude, speed, heart rate, and cadence.
- [ ] **DATA-03**: User's imported activity preserves available lap data with lap number, duration, distance, pace, heart rate, cadence, and elevation.
- [ ] **DATA-04**: User can rely on FIT timestamps being displayed in the local timezone without date shifts at midnight or daylight-saving transitions.
- [ ] **DATA-05**: User can rely on FIT GPS semicircle coordinates being converted to valid latitude/longitude values without premature rounding.
- [ ] **DATA-06**: User sees missing FIT measurements as unavailable or omitted, never as a fabricated numeric zero.
- [ ] **DATA-07**: User's FIT data and GPS tracks remain stored locally and are not sent to analytics or external analysis services.

### Dashboard

- [ ] **DASH-01**: User sees the current calendar week and the five preceding calendar weeks, including weeks with no activity.
- [ ] **DASH-02**: User sees six-week totals for run count, distance, training time, average distance, average pace, and elevation gain.
- [ ] **DASH-03**: User sees average heart rate only when meaningful heart-rate data exists.
- [ ] **DASH-04**: User sees kilometers per calendar week in a chart with honest zero-activity weeks.
- [ ] **DASH-05**: User sees all runs in the selected six-week window newest first.
- [ ] **DASH-06**: User sees each listed run's local date, start time, distance, duration, pace, and available heart rate/elevation.
- [ ] **DASH-07**: User can open a listed run's detail view.
- [ ] **DASH-08**: User sees a clear empty state and an import action when no runs have been imported.
- [ ] **DASH-09**: User sees the dashboard and import flow in a layout usable on desktop and responsive on smaller screens.

### Detail

- [ ] **DETL-01**: User can open a dedicated detail view for every imported running activity.
- [ ] **DETL-02**: User sees available headline metrics including date, time, distance, elapsed/moving time, pace, heart rate, cadence, elevation, calories, and Training Effect.
- [ ] **DETL-03**: User sees the complete GPS track on a pannable and zoomable map with fitted bounds and marked start/end points when GPS exists.
- [ ] **DETL-04**: User sees no map section, but can still use the rest of the detail view, when valid GPS data is unavailable.
- [ ] **DETL-05**: User can inspect pace, heart-rate, and elevation over distance, plus cadence when available, with meaningful units and gaps for missing samples.
- [ ] **DETL-06**: User can inspect available laps in a readable table with distance, time, pace, heart rate, cadence, and elevation values.
- [ ] **DETL-07**: User can use the detail view even when one optional map, chart, or lap section has no data or fails independently.

### Quality

- [ ] **QUAL-01**: User can start the application locally with the documented backend, frontend, and PostgreSQL setup.
- [ ] **QUAL-02**: The application provides automated coverage for successful import, core values, records, GPS conversion, laps, missing sensors, duplicates, corrupt files, and non-running activities.
- [ ] **QUAL-03**: The application provides automated frontend coverage for dashboard loading, activity display, detail navigation, missing optional data, and understandable import errors.

## v2 Requirements

Deferred until the local FIT MVP has been validated.

### Analysis and Integrations

- **ANAL-01**: User can attach and compare a training plan with completed activities.
- **ANAL-02**: User receives rule-based or AI-generated run and weekly analysis.
- **SYNC-01**: User can synchronize activities from Garmin Connect or Strava.
- **SYNC-02**: User can import GPX or TCX files.
- **UX-01**: User can inspect charts with synchronized cursors, section zoom, and alternate time axes.

## Out of Scope

| Feature | Reason |
|---------|--------|
| Training plans, coaching, and automatic recommendations | Not required to validate FIT import and descriptive analytics |
| AI analysis and OpenAI integration | Adds privacy, evaluation, and product complexity before the data foundation is proven |
| Garmin OAuth, Garmin Connect API, automatic Garmin sync, and Strava sync | MVP uses controlled local files and avoids external credentials/failure modes |
| User accounts, multi-user support, social features, sharing, likes, followers, challenges, and gamification | Product is private and local-first |
| Native mobile app, push notifications, and live tracking | MVP is post-run web analysis |
| Best-time logic, segment comparisons, complex training-load models, and medical evaluation | Requires separate definitions and may imply health claims beyond the MVP |

## Traceability

Updated during roadmap creation. Each v1 requirement must map to exactly one phase.

| Requirement | Phase | Status |
|-------------|-------|--------|
| IMPT-01 | Phase 2 - Reliable Import | Planned |
| IMPT-02 | Phase 2 - Reliable Import | Planned |
| IMPT-03 | Phase 2 - Reliable Import | Planned |
| IMPT-04 | Phase 2 - Reliable Import | Planned |
| IMPT-05 | Phase 2 - Reliable Import | Planned |
| IMPT-06 | Phase 2 - Reliable Import | Planned |
| IMPT-07 | Phase 2 - Reliable Import | Planned |
| IMPT-08 | Phase 2 - Reliable Import | Planned |
| DATA-01 | Phase 2 - Reliable Import | Planned |
| DATA-02 | Phase 2 - Reliable Import | Planned |
| DATA-03 | Phase 2 - Reliable Import | Planned |
| DATA-04 | Phase 2 - Reliable Import | Planned |
| DATA-05 | Phase 2 - Reliable Import | Planned |
| DATA-06 | Phase 2 - Reliable Import | Planned |
| DATA-07 | Phase 2 - Reliable Import | Planned |
| DASH-01 | Phase 3 - Six-Week Dashboard | Planned |
| DASH-02 | Phase 3 - Six-Week Dashboard | Planned |
| DASH-03 | Phase 3 - Six-Week Dashboard | Planned |
| DASH-04 | Phase 3 - Six-Week Dashboard | Planned |
| DASH-05 | Phase 3 - Six-Week Dashboard | Planned |
| DASH-06 | Phase 3 - Six-Week Dashboard | Planned |
| DASH-07 | Phase 3 - Six-Week Dashboard | Planned |
| DASH-08 | Phase 3 - Six-Week Dashboard | Planned |
| DASH-09 | Phase 3 - Six-Week Dashboard | Planned |
| DETL-01 | Phase 4 - Resilient Activity Detail | Planned |
| DETL-02 | Phase 4 - Resilient Activity Detail | Planned |
| DETL-03 | Phase 5 - Map, Charts, and Hardening | Planned |
| DETL-04 | Phase 4 - Resilient Activity Detail | Planned |
| DETL-05 | Phase 5 - Map, Charts, and Hardening | Planned |
| DETL-06 | Phase 4 - Resilient Activity Detail | Planned |
| DETL-07 | Phase 4 - Resilient Activity Detail | Planned |
| QUAL-01 | Phase 1 - Foundation and One Real FIT | Planned |
| QUAL-02 | Phase 2 - Reliable Import | Planned |
| QUAL-03 | Phase 5 - Map, Charts, and Hardening | Planned |

**Coverage:**
- v1 requirements: 34 total
- Mapped to phases: 34
- Unmapped: 0
- Mapped more than once: 0
- Phase counts: Phase 1 = 1, Phase 2 = 16, Phase 3 = 9, Phase 4 = 5, Phase 5 = 3

---
*Requirements defined: 2026-09-21*
*Last updated: 2026-09-21 after initial definition*
