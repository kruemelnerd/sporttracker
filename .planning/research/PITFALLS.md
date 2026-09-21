# Domain Pitfalls

**Domain:** Lokales Lauf-Dashboard mit Garmin-FIT-Import  
**Researched:** 2026-09-21

## Critical Pitfalls

### 1. FIT-Semantik auf Dateinamen oder einzelne Message-Typen reduzieren
**What goes wrong:** Importlogik erwartet genau eine Session, vollständige Records oder bekannte Felder und verwirft ansonsten gültige Aktivitäten. FIT-Dateien können unterschiedliche Kombinationen aus FileId-, Activity-, Session-, Lap-, Record- und Event-Messages enthalten; unbekannte Felder und fehlende Sensoren sind normal.
**Why it happens:** Die UI-Felder werden direkt aus einem Beispiel-FIT abgeleitet, statt einen SDK-Import in ein eigenes Zwischenmodell zu übersetzen.
**Consequences:** Leere oder falsche Kennzahlen, verlorene Laps/Trackpunkte und unnötige Importabbrüche bei anderen Garmin-Geräten.
**Prevention:** `decode → normalize → validate → persist` trennen; Summary-, Lap- und Record-Daten unabhängig sammeln; nicht unterstützte Felder ignorieren; mehrere Sessions pro Datei explizit entscheiden und testen.
**Detection:** Fixtures von verschiedenen Geräten/Formaten zeigen fehlende Werte oder unerwartete Message-Reihenfolgen; Parserfehler werden als globale Batch-Fehler zurückgegeben.

### 2. Beschädigte oder nur teilweise geschriebene FIT-Dateien als atomar gültig behandeln
**What goes wrong:** Ein abgeschnittener Upload erzeugt eine teilweise Activity oder blockiert den gesamten Mehrfachimport.
**Why it happens:** Datei wird direkt aus dem Multipart-Stream persistiert und die Transaktion umfasst den ganzen Upload statt genau eine Datei.
**Consequences:** Falsche Summen, verwaiste Records und unklare Wiederholungen.
**Prevention:** Größen-/Anzahl-/Timeout-Limits setzen, zunächst kontrolliert temporär speichern, vollständig dekodieren und validieren, pro Datei transaktional persistieren. Bei Fehlern Original als Diagnoseartefakt behalten oder sauber als fehlgeschlagen markieren; keine Teil-Activity committen.
**Detection:** Trunkierte und zufällig beschädigte Fixtures; ein kaputtes File neben einem gültigen File muss den gültigen Import nicht zurückrollen.

### 3. GPS-Semicircles falsch oder zu früh gerundet konvertieren
**What goes wrong:** Track liegt um Größenordnungen falsch, ist gespiegelt/verschoben oder `fitBounds` scheitert an ungültigen Punkten.
**Why it happens:** Semicircles werden wie Dezimalgrade behandelt, eine falsche Konstante/Datentyp-Konvertierung verwendet oder Koordinaten beim Import grob gerundet.
**Consequences:** Unbrauchbare Karten, falsche Distanz-/Ortsinterpretation und möglicher Privacy-Schaden durch fehlerhafte Darstellung.
**Prevention:** Garmin-/SDK-Konvertierung an einer bekannten Koordinate verifizieren; als Decimal/Numeric ausreichend präzise speichern; erst für Darstellung formatieren. NaN, Out-of-range-Koordinaten und fehlende Werte zu `null` machen. Map erst ab mindestens zwei gültigen Punkten anzeigen.
**Detection:** Golden-Fixture mit bekannten Koordinaten, Grenzwerttests für ±90/±180, GPS-Lücken und einzelne ungültige Records.

### 4. FIT-Zeit, lokale Kalenderwochen und DST vermischen
**What goes wrong:** Läufe erscheinen am falschen Tag, fallen aus der Sechs-Wochen-Ansicht oder werden bei Sommer-/Winterzeit doppelt/gar nicht aggregiert.
**Why it happens:** FIT-Epoch, Server-Zeitzone und Anzeigezeitzone werden implizit durch `LocalDateTime` oder die Host-Konfiguration bestimmt.
**Consequences:** Vertrauensverlust in Dashboard-Summen und schwer reproduzierbare Grenzfälle.
**Prevention:** FIT-Zeit einmal explizit in `Instant`/UTC überführen; Anzeigezeitzone als IANA-Konfiguration führen; Wochenbeginn und Sechs-Wochen-Grenzen lokal berechnen, anschließend als UTC-Halboffenintervall abfragen. Dauer/Motion-Time unabhängig von Wanduhr speichern.
**Detection:** Fixed-clock-Tests direkt um Mitternacht und an beiden DST-Übergängen; dieselbe Aktivität muss in UTC und lokaler Darstellung dieselbe Identität behalten.

### 5. Duplikate nur über Dateinamen oder nur über Hash erkennen
**What goes wrong:** Derselbe Upload wird doppelt gezählt oder dieselbe Aktivität mit neu exportierter Datei erneut importiert.
**Why it happens:** Dateiname ist nicht stabil; ein Byte-Hash erkennt nur exakt dieselbe Datei, nicht dieselbe Aktivität in anderer Verpackung.
**Consequences:** Verfälschte Wochenkilometer und nicht idempotente Retry-Workflows.
**Prevention:** SHA-256 der Originaldatei als sichere Idempotenz verwenden und zusätzlich FIT-Activity/File-Identität, Startzeit sowie Quelle zu einer praktischen Aktivitätsidentität kombinieren. Datenbank-Constraints als letzte Schranke, Anwendungsklassifikation für verständliche `DUPLICATE`-Meldungen. Ambige Fälle nicht aggressiv zusammenführen.
**Detection:** Gleiche Datei, neu gespeicherte Kopie, veränderte Metadaten/Exportvariante und identische Startzeit in Test-Fixtures; konkurrierende Imports prüfen.

### 6. Optionale Sensorwerte als Null oder Pflicht behandeln
**What goes wrong:** Fehlende Herzfrequenz, Kadenz, GPS, Höhe, Training Effect oder Laps erscheinen als echte 0 oder lassen die Detailseite abstürzen.
**Why it happens:** Primitive Felder/DTOs und Charts setzen Vollständigkeit voraus.
**Consequences:** Falsche Analysen und eine fragile UI für ältere Geräte oder sensorlose Läufe.
**Prevention:** Nullable canonical values und sparse Records verwenden; `null`/Lücke durch API, Mapper und Visualisierung erhalten. Sektionen unabhängig laden und bei fehlenden Daten ausblenden oder `nicht verfügbar` anzeigen; nur gemessene Nullwerte als 0 darstellen.
**Detection:** Fixtures ohne HR, ohne GPS, ohne Laps, mit teilweise leeren Records und mit ausschließlich Summary-Werten.

### 7. Dashboard- und Detailabfragen laden ungefiltert alle Trackpunkte
**What goes wrong:** JPA-Entity-Graphen oder eager Beziehungen laden bei jeder Dashboard-Anfrage tausende Records pro Lauf.
**Why it happens:** Persistenzmodell wird direkt als API-Modell serialisiert.
**Consequences:** Große Payloads, N+1-Abfragen, hoher Speicherverbrauch und langsame UI trotz kleiner Aktivitätsanzahl.
**Prevention:** Task-shaped DTOs/SQL-Projektionen verwenden: Dashboard nur Summary plus Wochen-Buckets, Detail on demand. Records deterministisch indexiert abfragen und nur für Charts transportseitig downsamplen; Rohdaten unverändert behalten. Keine Timeseries-Erweiterung oder Cache-Schicht ohne Messung.
**Detection:** SQL-Logging/`EXPLAIN`, Payload-Größen und Laufzeit mit mehreren hundert Aktivitäten und mehreren tausend Records pro Lauf messen.

### 8. OSM-Tiles als privater Kartenproxy oder zuverlässige Infrastruktur behandeln
**What goes wrong:** GPS-Daten werden an einen Drittanbieter übertragen, Tiles werden massenhaft vorab geladen oder eine Tile-Störung macht die Laufdetailseite unbrauchbar.
**Why it happens:** Tile-URL wird als beliebiger Kartenservice verstanden und direkt in Komponenten verteilt.
**Consequences:** Privacy-Verletzung, Missachtung der OSM-Tile-Policy, Rate-Limits und unzuverlässige Darstellung.
**Prevention:** Browser darf nur sichtbare Hintergrund-Tiles anfordern; FIT/Track nie an den Tile-Provider senden oder proxyen. Attribution sichtbar anzeigen, keine Bulk-/Offline-Downloads, URL und Adapter konfigurierbar halten. Map-Fehler muss Detailwerte/Charts nicht blockieren.
**Detection:** Netzwerk-Assertions im Browser, keine Koordinaten in Backend-Logs, konfigurierbarer Provider-Smoke-Test und Prüfung ohne Tile-Verbindung.

## Moderate Pitfalls

### 1. Import-Reihenfolge und Einheiten nicht explizit definieren
FIT-Werte kommen mit unterschiedlichen Einheiten und können sowohl Summary- als auch Record-Quellen besitzen. Canonical values müssen im Normalizer festgelegt werden: Meter, Sekunden, m/s bzw. Sekunden/km, bpm und Decimal-Degrees. Pace niemals als formatierter String speichern; widersprüchliche Quellen brauchen eine dokumentierte Priorität.

### 2. Nicht-Lauf-Aktivitäten stillschweigend verwerfen
Eine Datei kann lesbar sein, aber Radfahren oder andere Sportarten enthalten. Als `SKIPPED_NON_RUN` melden, nicht in Laufaggregate aufnehmen und keinen Parserfehler vortäuschen.

### 3. Originaldatei und Datenbank nicht gemeinsam idempotent behandeln
Hash-basierte, content-addressed Ablage verwenden; Benutzerdateinamen nie als Pfad verwenden. Temp-Dateien bei Abbruch bereinigen und bei Wiederholung weder Datei noch Activity unkontrolliert duplizieren.

### 4. Charts verbinden, obwohl Datenreihen unterschiedliche Samples haben
Pace, HR, Höhe und Kadenz können unterschiedliche Lücken und Frequenzen besitzen. Keine künstlichen Nullen oder blindes Index-Matching; pro Serie Distanz/Zeit und Null-Lücken erhalten. Gemeinsamer Cursor ist nur sinnvoll, wenn die Zuordnung belastbar ist.

### 5. Testdaten zu sauber wählen
Ein einzelnes vollständiges FIT-Fixture beweist keine Robustheit. Kleine echte FIT-Dateien plus gezielt beschädigte/trunkierte Varianten und synthetisch erzeugte Boundary-Fixtures verwenden; erwartete Rohwerte und normalisierte Werte getrennt prüfen.

## Minor Pitfalls

### 1. Frühzeitiges Glätten oder Runden von GPS und Zeitreihen
Rohpräzision beim Persistieren bewahren. Vereinfachung/Downsampling nur für eine begrenzte UI-Antwort durchführen und nicht als Datenverlust missverstehen.

### 2. Leere Wochen künstlich auslassen
Die sechs Kalenderwochen als feste Buckets zurückgeben, inklusive echter Nullwochen. Fehlende Historie ist nicht dasselbe wie eine Aktivität mit Nullwerten.

### 3. Parser- und Dateisystemdetails an den Client leaken
Fehlerantworten sollen Code, Dateiname und sichere Handlungsanweisung enthalten, aber keine Stacktraces, absoluten Pfade oder Rohdaten.

### 4. Karten-/Chart-Bibliothek zur fachlichen Datenquelle machen
Leaflet und ECharts sind Darstellungsschichten. Normalisierte API-Daten müssen auch ohne Karte, Chart oder JavaScript-Widget prüfbar bleiben.

## Phase-Specific Warnings

| Phase Topic | Likely Pitfall | Mitigation |
|-------------|---------------|------------|
| Foundation / FIT spike | SDK-Version, API und reale Message-Kombinationen weichen vom Beispiel ab | Garmin-SDK pinnen und zuerst mindestens ein echtes sowie ein unvollständiges FIT dekodieren |
| Import vertical | Teilpersistenz, schlechte Pfade und Batch-Rollback | Temp-Datei, content-addressed storage, Transaktion pro Datei, Ergebnis je Datei |
| Normalization | Semicircles, FIT-Epoch, Einheiten und nullable Werte werden inkonsistent behandelt | Pure Normalizer mit Golden-Fixtures und Boundary-/DST-Tests |
| Duplicate handling | Neue Datei derselben Aktivität oder parallele Retries umgehen die Erkennung | Hash plus Aktivitätsidentität, DB-Constraint und Race-Test |
| Dashboard | Serverzeitzone und Trackpunkt-Fan-out verfälschen Zeitraum oder Performance | Lokale Wochen→UTC-Halboffenintervalle; SQL-Projektionen ohne Records |
| Detail / visualization | Fehlende Reihen brechen Charts oder ungültige GPS-Punkte brechen Bounds | null-preserving DTOs, unabhängige Sektionen, Map nur mit gültigem Track |
| OSM integration | Externe Tile-Anfragen verletzen Privacy/Policy oder haben keine Verfügbarkeit | Konfigurierbarer Adapter, sichtbare Attribution, keine Prefetch-/Proxy-Logik, graceful degradation |
| Test hardening | Fixtures decken nur Happy Path ab | Matrix aus echten, minimalen, korrupten, sensorlosen, DST- und Duplikat-Fixtures; Import-to-UI-Smoke-Test |

## Sources

- `PROJECT.md` und `idea.md` — Produktgrenzen, Datenmodell, Privacy-, Zeit- und Testanforderungen (**HIGH**, projektspezifische Quelle)
- [Garmin FIT Activity Files](https://developer.garmin.com/fit/file-types/activity/) — Message-/Session-/Lap-/Record-Semantik (**HIGH**, offizielle Quelle)
- [Garmin FIT Cookbook: Decoding Activity Files](https://developer.garmin.com/fit/cookbook/decoding-activity-files/) — Decoderfluss und unvollständige Dateien (**HIGH**, offizielle Quelle)
- [Garmin FIT DateTime Cookbook](https://developer.garmin.com/fit/cookbook/datetime) — FIT-Epoch und Zeitkonvertierung (**MEDIUM**, offizielle Dokumentation; konkrete SDK-API versionsabhängig)
- [PostgreSQL Indexes](https://www.postgresql.org/docs/current/indexes.html) — B-Tree-/Range-/Order-Abfragen (**MEDIUM**, offizielle Dokumentation)
- [OpenStreetMap Tile Usage Policy](https://operations.osmfoundation.org/policies/tiles/) — Attribution, sichtbare Nutzung, keine Bulk-/Offline-Nutzung (**HIGH** für die Policy; Verfügbarkeit des öffentlichen Dienstes **LOW**)
- `.planning/research/STACK.md` und `.planning/research/ARCHITECTURE.md` — empfohlene Grenzen, Query- und Privacy-Muster (**MEDIUM**, projektbezogene Synthese)
