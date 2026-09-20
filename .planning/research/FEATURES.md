# Feature Landscape

**Domain:** Lokales Lauf-Dashboard mit FIT-Dateiimport
**Researched:** 2026-09-20

## Research Scope and Confidence

This is a feature-landscape recommendation for the greenfield MVP, grounded primarily in the supplied product definition and Garmin's current FIT activity-file documentation. The official FIT source is **HIGH confidence**; broader UX conclusions are **LOW confidence** because competitor UI pages were not independently verified during this run. The product requirements therefore remain the source of truth where they are more specific.

## Table Stakes

Features users expect. Missing = product feels incomplete or untrustworthy.

| Feature | Why Expected | Complexity | MVP behavior |
|---------|--------------|------------|--------------|
| Multi-file FIT upload via picker and drag & drop | Garmin exports are file-based and users commonly import batches, not one activity at a time. | Med | Accept only `.fit`; show selected files before submit and allow retry without losing the batch. |
| Per-file import status | A batch must communicate which files succeeded, failed, were duplicates, or were skipped. | Med | Show progress/result for every file; one bad file must not hide successful imports. |
| Actionable import errors | “Import failed” does not tell a private user how to recover. | Med | Distinguish unreadable/corrupt, unsupported content, non-run activity, duplicate, and unexpected server failure; retain the filename and a short next action. |
| Idempotent duplicate handling | Re-uploading an export is normal; duplicate training rows destroy trust in totals. | High | Use file hash plus FIT activity identity/start time; do not create a second activity; explain that it was already imported. |
| Non-run filtering | FIT files can contain multiple sport types and the MVP promises a running view, not a generic sports database. | Med | Parse safely, exclude non-running activities from the dashboard, and report the skip rather than silently dropping it. |
| Empty dashboard state | A first launch has no data and still needs a clear next step. | Low | Explain the dashboard and provide a prominent `FIT-Dateien importieren` action. |
| Six-week window with explicit calendar semantics | The core value is a fast, bounded training overview. | Med | Show the current week plus five previous calendar weeks; use the local timezone for displayed dates and do not invent empty activity data. |
| Summary metrics with consistent units | Users need to answer “how much did I run?” without opening every activity. | Med | Show run count, total distance, total training time, average distance, average pace, and elevation gain; show average HR only when meaningful data exists. |
| Weekly trend | A total alone hides whether training is increasing or absent in a week. | Med | Include a kilometers-per-calendar-week chart with all six week buckets, including zero-activity weeks as real zero totals. |
| Newest-first activity list | The dashboard must be useful as a daily log as well as a period summary. | Low | List date, local start time, distance, duration, pace, and available HR/elevation; clicking opens detail. |
| Run detail headline | Users need a quick verification that the imported activity is correct. | Med | Show date/time, distance, elapsed and moving time when available, pace, HR, cadence, elevation, calories, and Training Effect when present. |
| Optional data represented honestly | FIT files vary by device, sensor, recording mode, and sport. Garmin defines records, laps, and summaries as variable message content; missing fields are normal, not exceptional. | High | Render `nicht verfügbar` or omit the metric; never turn absent HR, cadence, GPS, or elevation into a real `0`. |
| GPS map when track exists | A route view is a standard way to validate and understand a run. | Med | Draw the complete track, fit bounds, mark start/end, and support pan/zoom. Hide the map section with a clear explanation when no valid GPS exists. |
| Run charts | A detail page without time-series context loses the main value of recorded data. | High | Show pace, heart rate, and elevation over distance when available; add cadence if present. Use meaningful units, tooltips, and gaps for missing samples. |
| Lap table | Automatic kilometer laps and manual intervals are fundamental to inspecting a run. | Med | Show lap number, distance, time, pace, HR, max HR, cadence, and elevation when available; keep the table readable for many laps. |
| Privacy-first local behavior | GPS tracks reveal sensitive locations; this MVP promises private local use. | High | Keep FIT data, normalized records, and tracks local; do not send activities to analytics or third-party APIs. External map tiles may be used only as the map background, with the tile provider isolated. |
| Resilient detail loading | A missing chart series must not make the whole activity unusable. | Med | Load detail data on demand; each map/chart/lap section degrades independently and the rest of the page remains usable. |

## Differentiators

Valuable improvements after the table stakes are reliable. Only the first item is a safe MVP candidate; the rest should not delay data integrity.

| Feature | Value Proposition | Complexity | Recommendation |
|---------|-------------------|------------|----------------|
| Synchronized chart cursor and section zoom | Lets a runner inspect the same distance across pace, HR, elevation, and cadence. | Med | Include if the chosen chart library provides it without custom interaction infrastructure; otherwise defer. |
| Import result summary with direct retry | Turns a mixed batch into a recoverable workflow instead of forcing the user to identify bad files manually. | Med | Good small differentiator after basic per-file outcomes work. |
| Preserve original FIT file locally | Enables reproducible re-parsing when the normalized model grows and supports troubleshooting. | Med | Recommended MVP capability, but make storage configurable and never store binaries in PostgreSQL. |
| Evidence/status labels for computed values | Makes clear whether a metric came from FIT summary data, records, or a derived calculation. | Med | Consider after the first usable detail view; useful for trust but not required for initial UI. |
| Compact route privacy controls | Lets a local user hide or trim home/start locations before sharing a screenshot. | High | Defer: social sharing is out of scope, and there is no need to build a sharing workflow now. |
| Alternative distance/time chart axis | Distance is best for laps and pace; time remains useful for stopped or GPS-poor activities. | Med | Defer until real data shows the need; use distance as the initial axis. |

## Anti-Features

Features to explicitly NOT build in this MVP.

| Anti-Feature | Why Avoid | What to Do Instead |
|--------------|-----------|-------------------|
| Training plans and planned-vs-actual comparison | Adds a second domain model and coaching semantics before the activity data foundation is proven. | Preserve clean activity and lap data so a later phase can attach planned sessions. |
| AI analysis, recommendations, or OpenAI integration | Not required to validate import, correctness, or the six-week dashboard; creates privacy and evaluation overhead. | Present descriptive facts only. |
| Garmin Connect, Strava, or other sync/OAuth integrations | Contradicts the controlled local-file MVP and adds credentials, rate limits, and external failure modes. | Import local FIT files. |
| GPX/TCX support | Broadens parsing and validation scope without improving the first FIT workflow. | Keep the ingestion boundary FIT-only. |
| Accounts, multi-user, social features, sharing, likes, followers, challenges | The product is explicitly a private local tool; these features create authorization and privacy obligations. | Assume one local user and keep data on the local system. |
| Native mobile app, push notifications, live tracking | Not needed for post-run file analysis and would divert effort from the web MVP. | Make the web detail view responsive enough for basic use. |
| Best-time/record logic, segment comparisons, complex load models, medical evaluation | These require definitions, data quality guarantees, and potentially unsafe interpretation beyond a descriptive dashboard. | Display imported measurements without coaching or health claims. |

## Feature Dependencies

```text
FIT validation/decoding
  → normalized Activity / Record / Lap data
  → duplicate identity and import result classification
  → six-week query and summary metrics
  → dashboard list and weekly chart
  → on-demand run detail
      → optional GPS map
      → optional metric charts
      → optional lap table
```

Additional rules:

- Raw records must be retained before chart downsampling; UI optimization must not destroy the source of truth.
- Import classification must precede dashboard aggregation, otherwise duplicates and non-runs contaminate totals.
- Missing-field semantics belong in the data contract before UI work: `null`/absent means unavailable, while numeric zero means a measured or valid zero.
- FIT activity files contain session, lap, record, and event messages; Garmin notes that message presence varies by device, sport, sensors, and features. Lap messages can be automatic or manual and should be sequential/non-overlapping. This is why every detail section is conditional rather than mandatory.

## MVP Recommendation

Prioritize:

1. **Trustworthy multi-file FIT import** — validation, per-file outcomes, partial failure isolation, duplicate prevention, non-run reporting, and local privacy.
2. **Six-week dashboard** — current-week-inclusive buckets, core totals, kilometer trend, and newest-first activity list with honest empty/partial states.
3. **Resilient run detail** — headline metrics plus conditional map, pace/HR/elevation charts, and laps; each optional section must fail soft.
4. **One restrained differentiator:** synchronized chart inspection or a polished retryable import summary, but only after the data path is correct.

Defer: training plans, AI/coaching, external sync, GPX/TCX, accounts/multi-user/social, native mobile, live tracking, records/segments, complex load models, and medical interpretation. These are explicitly out of scope in `PROJECT.md` and `idea.md`; do not let them shape MVP acceptance criteria beyond keeping the data model extensible.

## Sources

- `PROJECT.md` and `idea.md` (project requirements and explicit scope; authoritative for this MVP)
- [Garmin FIT Activity Files](https://developer.garmin.com/fit/file-types/activity/) — activity-file message and lap semantics (**HIGH**, official source)
- [Garmin FIT Cookbook: Decoding Activity Files](https://developer.garmin.com/fit/cookbook/decoding-activity-files/) — session/lap/record/event structure and incomplete-file handling (**HIGH**, official source)
- Research cache: Garmin FIT documentation digest `c106b061c01f67229d852c088657bda3cfb6d3058d3df68c2116510b688042aa` (**HIGH** after source verification)
