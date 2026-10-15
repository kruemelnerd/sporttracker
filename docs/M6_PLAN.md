# Sporttracker M6 – Trainingsplan

## Ziel und Grenzen

Lokale Verwaltung mehrerer Trainingspläne mit Phasen und geplanten Einheiten. Pläne lassen sich erstellen, bearbeiten, löschen, als Wochenansicht nutzen sowie über ein versioniertes JSON-Format validiert importieren und exportieren. M6 enthält ausdrücklich weder KI/automatische Planerstellung noch FIT-Soll-Ist-Abgleich oder Änderungen an FIT-Tabellen/-Funktionen.

## Architektur

Backend folgt der bestehenden Spring MVC/JDBC/Flyway-Architektur; keine ORM- oder Framework-Einführung. Drei normalisierte Tabellen verwenden stabile UUIDs und `DATE` für lokale Kalenderdaten. Plan-, Phasen- und Einheitenoperationen laufen über transaktionale Services und validierte DTOs. JSON nutzt dasselbe versionierte fachliche Format beim Export und Import; Import validiert vor dem Schreiben vollständig und erzeugt neue IDs. Doppelte Pläne werden anhand des fachlichen Inhalts (Name, Ziel, Zeitraum und Wettkampfdatum) erkannt; Überschreiben erfordert explizite Bestätigung und erfolgt atomar.

Frontend ergänzt die vorhandene Navigation und Electric-Mint-Tokenbasis um eine responsive Trainingsplanroute. Ein API-Modul liefert explizit typisierte DTOs; eine kleine Wochenberechnung nutzt ISO-Wochen und `Date`-Arithmetik über lokale Datumsstrings, ohne UTC-Zeitstempel-Konvertierung. Vorhandene Tests, Styles und Komponenten bleiben bestehen.

## Daten- und API-Verträge

- `TrainingPlan`: UUID `id`, `name`, `goal`, `startDate`, `endDate`, optional `raceDate`, `targetTime` (Minuten), `description`.
- `TrainingPhase`: UUID `id`, `trainingPlanId`, `name`, `startDate`, `endDate`, optional `description`.
- `PlannedSession`: UUID `id`, `trainingPlanId`, `date`, `sport` (`RUN|BIKE|STRENGTH|OTHER`), `sessionType` (`EASY|RECOVERY|LONG_RUN|TEMPO|INTERVAL|TEST|RACE|OTHER`), `title`, optionale Distanz/Dauer/Paces/RPE/Anweisungen/Notizen. Nicht-Lauftypen dürfen `OTHER` verwenden; alle genannten Laufarten werden akzeptiert.
- Feldregeln: ISO-Datum, Ende nicht vor Start, Unterobjekte innerhalb Planzeitraum, Werte für Distanz/Dauer positiv, RPE 1–10, Pace-Werte positiv und `targetPaceFrom <= targetPaceTo` (Minuten/km). Alle Einheiten (auch gleicher Tag) erlaubt.
- REST-Basis `/api/training-plans`: Liste, Erstellen (`201`), Einzelabruf/-änderung/-löschung; Phasen als vollständige Plan-Phasenliste (`PUT .../{id}/phases`); Session-CRUD unter `.../{id}/sessions`; Zeitraumabruf `GET .../{id}/sessions?from=YYYY-MM-DD&to=YYYY-MM-DD`.
- Import/Export: `GET .../{id}/export` als `application/json`; `POST /api/training-plans/import` akzeptiert `{schemaVersion:1,plan:{...}}` plus optional `{replaceExisting:boolean}`. Neue IDs werden serverseitig erzeugt; Duplicate ohne Bestätigung gibt `409` samt verständlichem Duplicate-Fehler, bestätigter Replace ist atomar. Fehler haben vorhandenes konsistentes API-Fehlerformat.

## Bestand und Baseline (2026-10-15)

- Ausgangspunkt `025e9cc` auf `main`, identisch zu `origin/main`; Remote-Branch M6 existiert nicht.
- Lokale Änderungen im ursprünglichen Checkout (`AGENTS.md`, `.opencode/opencode.json`, `.opencode/plugins/graphify.js`) verbleiben dort unberührt; M6 arbeitet isoliert im Worktree.
- Backend `./gradlew test`: erfolgreich.
- Frontend nach `npm ci`: 10 Dateien / 43 Tests, Typecheck und Build erfolgreich. Build meldet die bereits bestehende ECharts-Chunk-Warnung. `npm ci` meldet Node `22.22.1`, knapp unter der Lockfile-Unterabhängigkeitsanforderung `22.22.2`; Installation, Tests und Build funktionieren.
- Playwright-Baseline und npm audit werden im abschließenden Integrationsschritt erhoben.

## Umsetzungsschritte und Gates

1. Persistenz: drei Migrationstabellen mit FKs/Cascade und Checks, JDBC-Repositories, Migration-/Persistenztests. Bestehende FIT-Tabellen bleiben unangetastet.
2. Backend API: DTOs, Validierung, transaktionale Services und CRUD/Zeitraumendpunkte; Tests für alle CRUD- und Fehlerfälle.
3. Frontend Wochenansicht: API-Client, Route/Nav, Planwahl und leere/aktive ISO-Wochen inklusive Phasen- und Wochenkennzahlen; Unit-Tests für Navigation, leere Wochen und getrennte RUN-Kilometer.
4. Bearbeitung: Plan-/Phasen-/Einheitenformulare, verschieben/löschen mit Bestätigung, Tastatur-/Responsive-States; Unit-Tests für valide/ungültige Formulare und Edit-Flows.
5. JSON Import/Export: serverseitige Schema-/Inhaltsvalidierung und atomarer Import, Export sowie Frontend-Vorschau/Bestätigung/Replace/Abbruch; Roundtrip-, Duplicate- und No-partial-write-Tests.
6. E2E/Abschluss: isolierte Playwright-Journeys, 375/768/1024/1440 Layout-/A11y-Prüfung, Backend + Frontend + Typecheck + Build + vollständige E2E + `npm audit`; Dokumentation und regressionssicherer Abschluss.

Jeder fachliche Schritt wird gezielt getestet, mit genau den zugehörigen Dateien committet und unmittelbar nach Commit zu `origin/milestone/06-training-plan` gepusht. `main` wird erst nach vollständig grüner Validierung konfliktfrei integriert; bei Konflikten oder fehlender Validierung bleibt die Branch-Integration offen.

## Review-Fokus

1. Ungültige/grenzwertige Datums-, Pace-, RPE-, Distanz- und Dauerwerte: Backend weist alles vor persistieren zurück (Schritt 2).
2. JSON-Struktur, unbekannte Version, doppelte IDs/Pläne, Replace und fehlerhafte Kindobjekte: keine partiellen Änderungen (Schritt 5).
3. ISO-Wochen über Jahreswechsel und Plangrenzen, Zeitzonen, leere Wochen (Schritt 3).
4. Mehrere Einheiten am selben Datum und nicht-laufende Distanzen: Wochenlaufkilometer zählen nur RUN (Schritt 3).
5. Löschung eines Plans mit Phasen/Einheiten: explizite Bestätigung und atomare referenzielle Löschung (Schritte 1/4).
