# Running Dashboard

## What This Is

Running Dashboard ist eine lokale Webanwendung zur Verwaltung und Auswertung eigener Lauftrainings aus Garmin-/FIT-Dateien. Nutzer importieren FIT-Dateien, sehen die letzten sechs Wochen als Dashboard und können einzelne Läufe mit Kennzahlen, Karte, Zeitreihen und Laps untersuchen.

## Core Value

Die Anwendung ersetzt die manuelle Sicht auf Garmin-Läufe durch ein schnelles, nachvollziehbares 6-Wochen-Dashboard auf Basis korrekter und wiederverwendbarer Trainingsdaten.

## Requirements

### Validated

(None yet — ship to validate)

### Active

- [ ] FIT-Dateien robust importieren und Laufdaten vollständig normalisieren
- [ ] Laufaktivitäten der letzten sechs Wochen übersichtlich zusammenfassen
- [ ] Einzelne Läufe mit Kennzahlen, GPS-Karte, Zeitreihen und Laps darstellen
- [ ] Optionale und fehlende FIT-Daten sicher behandeln
- [ ] Duplikate erkennen und lokale Trainingsdaten privat halten

### Out of Scope

- Trainingspläne und Soll-/Ist-Vergleiche — gehören in eine spätere Ausbaustufe
- KI-Analyse, Empfehlungen und OpenAI-Anbindung — nicht Teil des MVP
- Garmin-, Strava- oder andere Sync-Integrationen — der MVP verarbeitet lokale Dateien
- Benutzerkonten, Multi-User und Social Features — private lokale Nutzung genügt zunächst
- GPX-/TCX-Import und native Mobile App — Fokus bleibt auf FIT und Web

## Context

Das Projekt ist greenfield und für private lokale Nutzung gedacht. Der bevorzugte Stack ist Java/Spring Boot/PostgreSQL im Backend sowie Vue 3, TypeScript und Vite im Frontend. FIT-Dateien sollen mit einer etablierten Java-Lösung verarbeitet werden. Karten sollen ohne kommerziellen API-Key mit Leaflet oder MapLibre und OSM-kompatiblen Tiles funktionieren.

Die Architektur soll Rohdaten erhalten und abgeleitete Werte reproduzierbar berechnen. Interne HTTP-Endpunkte zwischen Frontend und Backend sind vorgesehen; eine öffentliche oder Garmin-API ist nicht erforderlich. Typische Datenmengen sind einige hundert Aktivitäten mit mehreren tausend Trackpunkten pro Lauf.

## Constraints

- **Scope**: Nur Lauftraining und FIT-Import im MVP — zukünftige Coaching-Funktionen dürfen den ersten Release nicht verzögern
- **Privacy**: Laufdaten und GPS-Tracks bleiben lokal — keine Cloud-Abhängigkeit oder externe Analyse
- **Data integrity**: FIT-Zeitstempel, GPS-Semicircles, optionale Sensorwerte und Duplikate korrekt behandeln — Trainingsdaten sind wichtiger als UI-Politur
- **Persistence**: PostgreSQL für normalisierte Daten, Originaldateien optional im konfigurierbaren Dateisystem — keine Binärdateien in PostgreSQL
- **Compatibility**: Lokale Zeitzone nur für Anzeige, intern UTC/Instant — Sommer-/Winterzeit darf Datumswerte nicht verschieben

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| FIT-Dateien statt Garmin-Sync als MVP-Eingang | Lokaler, kontrollierbarer und unabhängiger Datenimport | — Pending |
| Vertical MVP mit end-to-end Phasen | Frühe Validierung eines funktionierenden Lauf-Dashboards | — Pending |
| Rohdaten persistieren, abgeleitete Werte berechnen | Spätere Analyse soll ohne erneuten Import möglich sein | — Pending |
| Leaflet/MapLibre mit OSM-kompatiblen Tiles | Interaktive Karte ohne kommerziellen API-Key | — Pending |

## Evolution

This document evolves at phase transitions and milestone boundaries.

**After each phase transition** (via `/gsd-transition`):
1. Requirements invalidated? → Move to Out of Scope with reason
2. Requirements validated? → Move to Validated with phase reference
3. New requirements emerged? → Add to Active
4. Decisions to log? → Add to Key Decisions
5. "What This Is" still accurate? → Update if drifted

**After each milestone** (via `/gsd-complete-milestone`):
1. Full review of all sections
2. Core Value check — still the right priority?
3. Audit Out of Scope — reasons still valid?
4. Update Context with current state

---
*Last updated: 2026-09-20 after initialization*
