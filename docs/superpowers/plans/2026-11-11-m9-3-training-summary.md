# M9.3 Training Summary Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a local JSON/YAML training summary preview and one-click clipboard copy to activity details.

**Architecture:** Build one typed summary model from the existing ActivityDetail API and `lapRows()`. Keep JSON/YAML serialization as pure functions and add a focused Vue component for format selection, optional splits, preview, and clipboard feedback; no backend or database changes.

**Tech Stack:** Vue 3, TypeScript, Vitest, Playwright; existing frontend dependencies only.

**Spec:** User request “M9.3 – Kompakte Trainingszusammenfassung als JSON/YAML” (2026-11-11).

## Global Constraints

- Export only metrics present in the existing activity detail API; no GPS, raw FIT data, paths, credentials, or account data.
- Pace uses elapsed time / total distance, matching ActivityDetailView; date is the browser-local calendar date of the activity start instant.
- FIT cadence is stored as half-steps and must be doubled once for `steps/min`.
- Recorded laps retain actual distance; derived laps keep heart rate absent; missing values are omitted.
- No external transfer, new REST endpoint, migration, or dependency.

## Review Focus

- Invalid, zero, or missing distance must not produce pace; preserve measured zeros for other metrics.
- UTC instants near local midnight must serialize to the same local calendar date as the detail view.
- Long split arrays (e.g. half-marathon) and incomplete final kilometers must remain complete and ordered.
- Clipboard rejection must be visible and must not claim success.
- JSON and YAML must serialize the same ordered model without sensitive fields or non-finite numbers.

---

### Task 1: Summary model and serializers

**Files:**
- Modify: `frontend/src/activityDetail.ts`
- Test: `frontend/src/activityDetail.test.ts`

**Interfaces:**
- Produces `createTrainingSummary(detail: ActivityDetail, splits?: boolean): TrainingSummary`, `serializeTrainingSummaryJson(summary)`, and `serializeTrainingSummaryYaml(summary)`.
- Summary fields use the requested snake_case schema; optional measurements and `splits` are omitted when unavailable/disabled.

- [x] Add tests for complete and missing metrics, measured zero, pace basis, cadence conversion, rounding, invalid distance, local date, recorded/derived/no splits, and longer split sequences.
- [x] Implement the typed model builder using existing lap processing, then implement deterministic serializers from that model.
- [x] Run `cd frontend && npm run test:unit` (111 passed).
- [x] Commit Task 1 as `feat: build training summary model and serializers`.

### Task 2: Export UI, copy behavior, and documentation

**Files:**
- Create: `frontend/src/components/TrainingSummaryExport.vue`
- Modify: `frontend/src/views/ActivityDetailView.vue`
- Test: `frontend/src/views/ActivityDetailView.test.ts`
- Modify: `frontend/tests/e2e/dashboard.spec.ts`
- Modify: `README.md`

**Interfaces:**
- Component receives `detail: ActivityDetail` and `splits: LapRow[]`.
- Default format is JSON; default split selection is off.

- [ ] Test preview updates, JSON/YAML choice, split toggle, successful clipboard copy, and clipboard error feedback.
- [ ] Implement labelled native controls, accessible preview, responsive layout, and explicit click-only clipboard copying.
- [ ] Add a Playwright mobile-width check for no horizontal overflow and copy/selection interaction.
- [ ] Document exported fields, format choice, optional splits, missing values, privacy, and a compact example.
- [ ] Run frontend unit tests, typecheck, production build, full test script, and inspect the final diff.
