# Running Dashboard Roadmap

**Milestone:** v1 Local FIT MVP
**Created:** 2026-09-21
**Granularity:** coarse

## Phase Overview

| Phase | Name | Goal | Requirements |
|-------|------|------|--------------|
| 1 | Foundation and One Real FIT | Einen lokal startbaren vertikalen Grundpfad mit echtem FIT nachweisen | 1 |
| 2 | Reliable Import | FIT-Dateien robust, idempotent und vollständig normalisiert importieren | 16 |
| 3 | Six-Week Dashboard | Die letzten sechs lokalen Kalenderwochen verständlich zusammenfassen | 9 |
| 4 | Resilient Activity Detail | Eine Aktivität mit Kennzahlen, Laps und unabhängigen optionalen Bereichen untersuchbar machen | 5 |
| 5 | Map, Charts, and Hardening | Track- und Messwertvisualisierung abschließen und den MVP end-to-end absichern | 3 |

## Phase 1: Foundation and One Real FIT

**Goal:** Einen lokal startbaren vertikalen Grundpfad mit PostgreSQL, Schema, API-/UI-Shell und einem echten FIT nachweisen.
**Mode:** mvp

**Delivers:** Java/Spring-Boot- und Vue/Vite-Grundgerüst, PostgreSQL/Flyway-Baseline, konfigurierbares lokales Datenverzeichnis, FIT-SDK-Adapter-Spike und ein echter Lauf als reproduzierbare Fixture.

**Success criteria:**
- Die dokumentierte lokale Umgebung startet Backend, Frontend und PostgreSQL ohne externe Laufdaten- oder Analyseabhängigkeit.
- Ein echter FIT-Lauf wird über den Adapter dekodiert und als nachvollziehbares internes Modell sichtbar.
- Ein minimaler automatisierter Smoke-Test beweist den End-to-end-Pfad vom FIT-Input bis zur lokalen API-Antwort.

**Requirements:** QUAL-01

## Phase 2: Reliable Import

**Goal:** Mehrere FIT-Dateien pro Batch sicher verarbeiten und Laufdaten inklusive optionaler Felder dauerhaft, korrekt und lokal speichern.
**Mode:** mvp

**Delivers:** Picker-/Drag-and-drop-Upload, per-Datei-Status und Retry, Größen-/Anzahlgrenzen, isolierte Transaktionen, Originaldateiablage, SHA-256- und Aktivitäts-Duplikaterkennung, Nicht-Lauf-Klassifikation sowie Activity-, Record- und Lap-Persistenz.

**Success criteria:**
- Ein Batch mit gültigen, korrupten, nicht unterstützten, doppelten und Nicht-Lauf-Dateien liefert pro Datei einen verständlichen Outcome, ohne gültige Nachbarn zurückzurollen.
- Ein erneuter Upload derselben Datei erzeugt keine zweite Aktivität und kann als Duplicate nachvollzogen werden.
- Ein importierter Lauf enthält die verfügbaren Summary-, Record- und Lap-Werte; fehlende Sensorwerte bleiben leer statt künstliche Nullwerte zu erzeugen.
- FIT-Zeitstempel bleiben intern UTC-basiert und werden lokal auch über Mitternacht und DST-Grenzen korrekt angezeigt; Semicircle-GPS-Werte werden ohne vorzeitiges Runden konvertiert.
- Backend-Automation deckt erfolgreichen Import, Kernwerte, Records, GPS, Laps, fehlende Sensoren, Duplikate, beschädigte Dateien und Nicht-Läufe ab.

**Requirements:** IMPT-01, IMPT-02, IMPT-03, IMPT-04, IMPT-05, IMPT-06, IMPT-07, IMPT-08, DATA-01, DATA-02, DATA-03, DATA-04, DATA-05, DATA-06, DATA-07, QUAL-02

## Phase 3: Six-Week Dashboard

**Goal:** Die aktuelle Kalenderwoche und die fünf vorherigen Wochen als performantes, responsives Dashboard mit ehrlichen Summen und Laufsliste darstellen.
**Mode:** mvp

**Delivers:** Bounded Dashboard-Read-Model/API, sechs lokale Wochen-Buckets inklusive Nullwochen, Zeitraum-Summen, Wochenkilometer-Chart, newest-first-Liste, Empty State, Import-Navigation und responsive Layout.

**Success criteria:**
- Das Dashboard zeigt immer genau sechs lokale Kalenderwochen einschließlich leerer Wochen und verschiebt keine Aktivität an Mitternacht oder DST-Grenzen.
- Summen und Durchschnitte für Läufe, Distanz, Zeit, Durchschnittsdistanz, Pace und Höhenmeter stimmen mit den importierten Daten überein; Herzfrequenz erscheint nur bei sinnvoller Datenbasis.
- Der Wochenkilometer-Chart zeigt echte Nullwerte für Wochen ohne Lauf, und die Laufsliste ist innerhalb des Fensters absteigend sortiert.
- Jeder Listeneintrag zeigt lokale Zeit, Distanz, Dauer, Pace sowie verfügbare Herzfrequenz/Höhe und führt zur Detailansicht.
- Ein leerer Datenbestand zeigt eine verständliche Erklärung mit Import-Aktion; die Oberfläche bleibt auf Desktop und kleineren Screens nutzbar.

**Requirements:** DASH-01, DASH-02, DASH-03, DASH-04, DASH-05, DASH-06, DASH-07, DASH-08, DASH-09

## Phase 4: Resilient Activity Detail

**Goal:** Eine einzelne importierte Laufaktivität als eigenständige Detailansicht mit Kernmetriken und fehlertoleranten optionalen Bereichen untersuchbar machen.
**Mode:** mvp

**Delivers:** Detail-Endpoint und Route, task-shaped Detail-Projektion, headline metrics, geordnete Child-Daten, Lap-Tabelle sowie getrennte Lade-/Fehlerzustände für optionale Bereiche.

**Success criteria:**
- Jede importierte Laufaktivität ist über eine eigene Route erreichbar und zeigt Datum, Zeit, Distanz, elapsed/moving time sowie verfügbare Pace-, Herzfrequenz-, Kadenz-, Höhen-, Kalorien- und Training-Effect-Werte.
- Verfügbare Laps erscheinen in einer lesbaren Tabelle mit Distanz, Zeit, Pace, Herzfrequenz, Kadenz und Höhenwerten.
- Fehlende Laps, GPS- oder Messwertdaten entfernen nur den jeweiligen Bereich; die übrige Detailansicht bleibt benutzbar.
- Detaildaten werden erst beim Öffnen geladen und bleiben bei typischen Laufgrößen deterministisch geordnet.

**Requirements:** DETL-01, DETL-02, DETL-04, DETL-06, DETL-07

## Phase 5: Map, Charts, and Hardening

**Goal:** GPS-Track und Messreihen verständlich visualisieren und den vollständigen lokalen MVP-Fluss mit Browser- und Frontend-Tests absichern.
**Mode:** mvp

**Delivers:** Leaflet-Map mit konfigurierbaren OSM-kompatiblen Tiles und Attribution, ECharts-Zeitreihen für Pace/Herzfrequenz/Höhe/Kadenz, Gap-/Downsampling-Verhalten, Browser-Smoke-Tests, Accessibility- und Privacy-Hardening.

**Success criteria:**
- Bei gültigem GPS werden Track, Start/Ende und passende Bounds auf einer pannbaren und zoombaren Karte angezeigt; ohne GPS wird keine Map-Sektion erzwungen und keine Laufspur an externe Analyse gesendet.
- Pace, Herzfrequenz und Höhe sowie verfügbare Kadenz werden über Distanz mit korrekten Einheiten und sichtbaren Lücken für fehlende Samples dargestellt.
- Automatisierte Frontend-Verifikation deckt Dashboard-Laden, Laufdarstellung, Detailnavigation, fehlende optionale Daten und verständliche Importfehler ab; der Import-to-dashboard-to-detail-Smoke-Test läuft mit repräsentativen Fixtures.

**Requirements:** DETL-03, DETL-05, QUAL-03

## Requirements Coverage Validation

Every v1 requirement is assigned to exactly one phase in the traceability table in `REQUIREMENTS.md`.

| Check | Result |
|-------|--------|
| v1 requirements defined | 34 |
| Requirements mapped | 34 |
| Unmapped requirements | 0 |
| Requirements mapped more than once | 0 |
| Phase counts | P1: 1, P2: 16, P3: 9, P4: 5, P5: 3 |

The roadmap intentionally excludes all v2 requirements and the out-of-scope features listed in `PROJECT.md`, `REQUIREMENTS.md`, and `idea.md`.

---
*Roadmap created: 2026-09-21*
