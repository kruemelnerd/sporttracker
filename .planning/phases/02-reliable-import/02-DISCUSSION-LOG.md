# Phase 2: Reliable Import - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-09-21
**Phase:** 2-Reliable Import
**Areas discussed:** Duplikat-Identität, Dateiablage und Retry

---

## Duplikat-Identität

| Option | Description | Selected |
|--------|-------------|----------|
| Hash zuerst | Gleicher SHA-256 ist sicher DUPLICATE; FIT-ID und Startzeit ergänzen Re-Export-Erkennung. | ✓ |
| Aktivität zuerst | FIT-ID plus Startzeit primär, Hash nur als Fallback. | |
| Nur exakter Hash | Nur bytegleiche Dateien zusammenführen. | |

**User's choice:** Hash zuerst.
**Notes:** Re-Exports mit gleicher FIT-Aktivitäts-ID und Startzeit werden als DUPLICATE behandelt. Eine Datei entspricht einer Activity; Mehrfachinhalt wird nicht teilweise importiert. Ohne brauchbare FIT-ID gilt Startzeit plus Sport und normalisierte Dauer/Distanz als konservativer Fallback.

---

## Dateiablage und Retry

| Option | Description | Selected |
|--------|-------------|----------|
| Vor Verarbeitung, content-addressed | Original vor Verarbeitung dauerhaft ablegen. | |
| Nach erfolgreichem Import | Original erst nach erfolgreicher Normalisierung und Persistenz behalten. | ✓ |
| Gar nicht dauerhaft | Nur Hash und normalisierte Daten aufbewahren. | |

| Option | Description | Selected |
|--------|-------------|----------|
| Konfigurierbare konservative Grenzen | Dateigröße und Batch-Anzahl konfigurierbar begrenzen. | ✓ |
| Keine Batch-Grenze | Nur Dateigröße begrenzen. | |
| Feste MVP-Grenzen | Kleine feste Grenzen ohne Konfiguration. | |

| Option | Description | Selected |
|--------|-------------|----------|
| Datei erneut verarbeiten | Nur die ausgewählte fehlgeschlagene Datei erneut verarbeiten. | ✓ |
| Gesamten Batch wiederholen | Den vollständigen ursprünglichen Batch erneut ausführen. | |
| Nur technische Fehler | Retry ausschließlich für FAILED anbieten. | |

| Option | Description | Selected |
|--------|-------------|----------|
| Per-Datei atomar | Eigene Transaktion je Datei, keine halben Daten, Nachbarn bleiben erhalten. | ✓ |
| Best effort speichern | Teilweise dekodierte Daten als unvollständige Activity behalten. | |
| Batch-Transaktion | Ein Fehler rollt den gesamten Batch zurück. | |

**User's choice:** Originaldatei erst nach erfolgreichem Import speichern; konfigurierbare konservative Grenzen; per-Datei-Retry; per-Datei-atomare Verarbeitung.
**Notes:** Bereits erfolgreiche oder anderweitig abgeschlossene Ergebnisse eines Batches bleiben bei einem Retry unverändert sichtbar.

---

## the agent's Discretion

- Exact default upload limits, endpoint/DTO names, schema/index design, and UI wording/layout remain open for research and planning within the locked behavior.

## Deferred Ideas

- None.
