# Running Dashboard

## Product Idea

Eine einfache Webanwendung zur lokalen Verwaltung und Auswertung eigener Lauftrainings aus Garmin-/FIT-Dateien.

Die Anwendung soll zunächst **keinen Trainingsplan erstellen oder bewerten** und **keine Garmin-Integration** besitzen. Der erste Meilenstein konzentriert sich ausschließlich darauf, FIT-Dateien hochzuladen, die enthaltenen Laufdaten zuverlässig zu importieren und die letzten sechs Wochen übersichtlich sowie einzelne Läufe detailliert darzustellen.

Das Ziel ist ein solides technisches Fundament, auf dem später Trainingsplan-Abgleich, automatische Analyse und KI-Funktionen aufgebaut werden können.

---

## Ziel des ersten Releases

Nach dem Start der Anwendung soll ein Benutzer:

1. eine oder mehrere `.fit`-Dateien hochladen können,
2. die darin enthaltenen Laufaktivitäten importieren können,
3. auf einem Dashboard alle Lauftrainings der letzten sechs Wochen sehen,
4. zentrale Kennzahlen über diesen Zeitraum sehen,
5. einen einzelnen Lauf öffnen können,
6. den Lauf auf einer Karte sehen,
7. Pace, Herzfrequenz, Höhe, Kadenz und weitere vorhandene Messwerte über den Laufverlauf ansehen können,
8. Runden/Laps und grundlegende Laufstatistiken ansehen können.

Der Fokus liegt auf einer **guten Datenbasis und einer übersichtlichen Darstellung**, nicht auf Coaching oder Trainingsplanung.

---

# Scope

## Im MVP enthalten

### FIT-Dateien importieren

Die Anwendung unterstützt den Upload einer oder mehrerer FIT-Dateien über die Weboberfläche.

Anforderungen:

- Drag & Drop sowie Dateiauswahl
- mehrere Dateien in einem Upload möglich
- nur FIT-Dateien akzeptieren
- Upload-Status anzeigen
- fehlerhafte oder nicht unterstützte Dateien verständlich melden
- vorhandene Aktivitäten nicht doppelt importieren
- Original-FIT-Datei optional lokal aufbewahren
- Import soll wiederholbar und robust sein

Beim Import werden die FIT-Daten dekodiert und in ein internes Datenmodell überführt.

Der Import soll mindestens folgende Datentypen berücksichtigen, sofern sie in der FIT-Datei vorhanden sind:

- Aktivitätsbeginn
- Sportart / Subsport
- Dauer
- Bewegungszeit
- Distanz
- Durchschnittspace
- Durchschnittsgeschwindigkeit
- Durchschnittliche Herzfrequenz
- Maximale Herzfrequenz
- Kadenz
- Höhenmeter bergauf
- Höhenmeter bergab
- Kalorien
- GPS-Track
- Höhe
- momentane Geschwindigkeit/Pace
- Herzfrequenz pro Messpunkt
- Kadenz pro Messpunkt
- Distanz pro Messpunkt
- Runden / Laps
- Garmin Training Effect, falls vorhanden
- weitere sinnvolle FIT-Metriken dürfen gespeichert werden, auch wenn sie im ersten UI noch nicht angezeigt werden

Unbekannte oder fehlende FIT-Felder dürfen den Import nicht verhindern.

---

# Dashboard

Die Startseite zeigt standardmäßig die **letzten sechs Wochen einschließlich der aktuellen Woche**.

## Zusammenfassung

Oben sollen zentrale Kennzahlen dargestellt werden:

- Anzahl Läufe
- Gesamtdistanz
- Gesamttrainingszeit
- durchschnittliche Distanz pro Lauf
- durchschnittliche Pace
- Gesamthöhenmeter
- optional: durchschnittliche Herzfrequenz

Zusätzlich soll die Entwicklung über die sechs Wochen sichtbar sein.

Mindestens ein Wochenchart:

- Kilometer pro Kalenderwoche

Optional bereits im MVP:

- Trainingszeit pro Woche
- Anzahl Läufe pro Woche
- Höhenmeter pro Woche

---

## Liste der Läufe

Alle Läufe der letzten sechs Wochen werden chronologisch angezeigt, neuester Lauf zuerst.

Pro Lauf sollen mindestens angezeigt werden:

- Datum
- Startzeit
- Distanz
- Dauer
- Pace
- durchschnittliche Herzfrequenz, falls vorhanden
- Höhenmeter, falls vorhanden

Ein Klick auf einen Lauf öffnet die Detailansicht.

Eine einfache tabellarische oder Karten-artige Darstellung ist ausreichend. Die Oberfläche soll auf Desktop gut nutzbar und grundsätzlich responsive sein.

---

# Laufdetail

Jeder Lauf erhält eine eigene Detailansicht.

## Kopfbereich

Darstellung der wichtigsten Kennzahlen:

- Datum
- Startzeit
- Distanz
- Gesamtdauer
- Bewegungszeit, falls vorhanden
- durchschnittliche Pace
- durchschnittliche Herzfrequenz
- maximale Herzfrequenz
- durchschnittliche Kadenz
- Höhenmeter bergauf
- Höhenmeter bergab
- Kalorien
- Training Effect, falls vorhanden

Nicht vorhandene Werte werden nicht als `0`, sondern als `nicht verfügbar` oder gar nicht angezeigt.

---

## Karte

Wenn GPS-Daten vorhanden sind, wird der Streckenverlauf auf einer Karte angezeigt.

Anforderungen:

- kompletter Track als Linie
- automatische Zoomstufe auf den gesamten Lauf
- Startpunkt markieren
- Endpunkt markieren
- Karte soll mit Maus/Touch verschieb- und zoombar sein
- kein externer kommerzieller Karten-API-Key notwendig

Bevorzugte Umsetzung:

- Leaflet oder MapLibre
- OpenStreetMap-kompatible Kartendaten

Für den MVP ist die Verwendung öffentlicher OSM-Tiles akzeptabel. Die Kartenimplementierung soll aber so gekapselt sein, dass später ein anderer Tile-Provider oder selbst gehostete Tiles verwendet werden können.

Wenn keine GPS-Daten vorhanden sind, wird die Karte ausgeblendet und die restliche Detailseite funktioniert weiterhin.

---

# Zeitreihen / Diagramme

Unterhalb der Karte werden die aufgezeichneten Messwerte über den Laufverlauf dargestellt.

Mindestens:

1. Pace
2. Herzfrequenz
3. Höhenprofil

Wenn vorhanden zusätzlich:

4. Kadenz

Bevorzugte X-Achse:

- Distanz in Kilometern

Alternativ, falls technisch sinnvoll:

- Zeit

Die Darstellung soll verständlich und interaktiv sein.

Wünschenswert:

- Tooltip beim Überfahren eines Messpunkts
- Zoomen in einen Abschnitt
- gemeinsamer Cursor bzw. synchronisierte X-Achse zwischen Diagrammen
- sinnvolle Einheiten
- keine Darstellung offensichtlich fehlender Messwerte als echte `0`

Eine Chart-Bibliothek wie Apache ECharts ist gut geeignet, die konkrete Bibliothek darf aber bei der Umsetzung begründet geändert werden.

---

# Runden / Laps

Wenn Lap-Daten vorhanden sind, wird eine Tabelle angezeigt.

Spalten mindestens:

- Rundennummer
- Distanz
- Zeit
- Pace
- durchschnittliche Herzfrequenz
- maximale Herzfrequenz
- Kadenz, falls vorhanden
- Höhenmeter, falls vorhanden

Die Tabelle soll insbesondere für automatische Kilometer-Laps gut lesbar sein.

---

# Datenmodell

Das Datenmodell soll nicht nur exakt die aktuelle Oberfläche abbilden, sondern eine spätere Trainingsanalyse ermöglichen.

Mindestens folgende fachliche Objekte sind sinnvoll.

## Activity

Beispielhafte Felder:

```text
id
source
sourceFileName
sourceFileHash
fitActivityId / fitStartTimestamp
sport
subSport
startTime
durationSeconds
movingTimeSeconds
distanceMeters
averageSpeed
averagePace
averageHeartRate
maxHeartRate
averageCadence
elevationGain
elevationLoss
calories
aerobicTrainingEffect
anaerobicTrainingEffect
createdAt
```

## ActivityRecord

Zeitreihenmesspunkt einer Aktivität.

```text
id
activityId
timestamp
elapsedSeconds
distanceMeters
latitude
longitude
altitudeMeters
speed
heartRate
cadence
```

Nicht jeder Messpunkt enthält jedes Feld.

## Lap

```text
id
activityId
lapIndex
startTime
durationSeconds
distanceMeters
averageSpeed
averagePace
averageHeartRate
maxHeartRate
averageCadence
elevationGain
elevationLoss
```

Das Modell darf beim Implementieren erweitert werden, wenn FIT dafür sinnvolle zusätzliche Informationen liefert.

---

# Duplikaterkennung

Ein wiederholter Upload derselben Aktivität darf keine doppelte Aktivität erzeugen.

Empfohlene Strategie:

1. SHA-256 der Originaldatei speichern.
2. Zusätzlich Aktivitätsmerkmale wie Startzeit und FIT-interne ID berücksichtigen.
3. Gleiche Datei sicher erkennen.
4. Falls dieselbe Aktivität in einer anderen FIT-Datei erneut vorkommt, möglichst ebenfalls erkennen.

Bei einem Duplikat soll der Benutzer eine verständliche Meldung erhalten.

---

# Technischer Rahmen

## Bevorzugter Stack

Backend:

- Java
- Spring Boot
- PostgreSQL
- Gradle oder Maven
- FIT-Parsing über eine etablierte Java-Lösung, bevorzugt Garmin FIT SDK

Frontend:

- Vue 3
- TypeScript
- Vite
- Leaflet oder MapLibre für Karten
- Apache ECharts oder vergleichbare Chart-Bibliothek

Die Anwendung darf als klassisches Frontend + Backend umgesetzt werden.

Mit „keine API nötig“ ist gemeint:

- keine öffentliche API
- keine Garmin API
- keine externe Integrations-API

Interne HTTP-Endpunkte zwischen Vue-Frontend und Spring-Boot-Backend sind selbstverständlich erlaubt und erwartbar.

---

# Persistenz

PostgreSQL speichert die normalisierten Trainingsdaten.

Für das MVP reicht eine einzelne lokale Instanz.

Die Original-FIT-Dateien können entweder:

- im Dateisystem gespeichert werden oder
- nur importiert und anschließend verworfen werden.

Bevorzugt wird das Speichern im Dateisystem mit einem konfigurierbaren Datenverzeichnis, damit ein späteres erneutes Parsen möglich ist.

Keine Binärdateien direkt in PostgreSQL speichern.

---

# Zeit und Zeitzonen

FIT-Zeitstempel müssen sauber behandelt werden.

Wichtig:

- intern möglichst `Instant` / UTC verwenden
- für die Anzeige lokale Zeitzone verwenden
- Sommer-/Winterzeit berücksichtigen
- keine Laufdaten wegen Zeitzonenkonvertierungen um einen Tag verschieben

---

# Pace-Berechnung

Pace soll intern nicht als formatierter String gespeichert werden.

Bevorzugt speichern bzw. berechnen:

- Geschwindigkeit in m/s oder
- Pace in Sekunden pro Kilometer

Darstellung beispielsweise:

```text
5:23 min/km
```

Dadurch bleiben Sortierung, Diagramme und spätere Analysen korrekt.

---

# GPS-Daten

FIT-GPS-Koordinaten können im Garmin-Semicircle-Format vorliegen.

Beim Import müssen sie korrekt in normale Latitude-/Longitude-Werte konvertiert werden.

GPS-Punkte sollten nicht unnötig früh gerundet werden.

Für die erste Version ist keine aufwendige Glättung oder Map-Matching-Funktion nötig.

Offensichtlich ungültige GPS-Punkte sollen ignoriert werden.

---

# Fehlerbehandlung

Die Anwendung darf durch eine ungewöhnliche FIT-Datei nicht komplett abbrechen.

Beispiele:

- FIT-Datei beschädigt
- keine GPS-Daten
- keine Herzfrequenz
- keine Laps
- Aktivität ist kein Lauf
- Sensorwerte teilweise leer
- unbekannte FIT-Felder

Ein Importfehler einer Datei darf andere Dateien desselben Uploads nicht blockieren.

---

# Umgang mit anderen Sportarten

Der erste fachliche Fokus liegt ausschließlich auf Lauftraining.

Falls eine hochgeladene FIT-Datei keine Laufaktivität enthält:

- Datei sauber lesen, soweit möglich
- Aktivität im MVP nicht in das Lauf-Dashboard aufnehmen
- dem Benutzer anzeigen, dass die Aktivität wegen einer anderen Sportart übersprungen wurde

Eine spätere Erweiterung auf Radfahren ist denkbar, aber ausdrücklich nicht Teil des MVP.

---

# Datenschutz

Die Anwendung ist zunächst für private Nutzung gedacht.

Grundsätze:

- keine Cloud-Abhängigkeit notwendig
- keine Weitergabe der FIT-Daten
- keine externe Analyse
- GPS-Tracks bleiben im eigenen System
- keine Telemetrie erforderlich

Externe Kartentiles sind die einzige für das MVP akzeptierte externe Datenquelle. Die eigentlichen Laufdaten oder GPS-Tracks dürfen nicht aktiv an einen Kartendienst übertragen werden.

---

# UX

Die Anwendung soll funktional und ruhig wirken, nicht wie ein soziales Fitness-Netzwerk.

Prioritäten:

1. Daten schnell erfassen
2. Trainingsverlauf schnell verstehen
3. einzelnen Lauf einfach untersuchen

Keine Gamification im MVP.

Keine:

- Badges
- Follower
- Likes
- Challenges
- Social Sharing

---

# Navigation

Für den ersten Stand reichen drei zentrale Bereiche:

```text
Dashboard
Import
Laufdetail
```

Optional kann `Import` als Button direkt im Dashboard umgesetzt werden.

---

# Leere Zustände

Wenn noch keine Läufe importiert wurden:

- kurze Erklärung anzeigen
- prominenter Button `FIT-Dateien importieren`

Wenn weniger als sechs Wochen Daten vorhanden sind:

- vorhandene Daten anzeigen
- keine künstlichen Daten erzeugen

---

# Performance

Typischer Umfang:

- einige hundert Aktivitäten
- je Lauf mehrere tausend Trackpunkte

Das System muss dafür problemlos funktionieren.

Beim Dashboard dürfen nicht alle einzelnen Trackpunkte aller Läufe geladen werden.

Detaildaten eines Laufs werden erst beim Öffnen der Aktivität geladen.

Falls Zeitreihen sehr viele Punkte enthalten, darf für Diagramme eine Downsampling-Strategie verwendet werden. Die Rohdaten sollen trotzdem gespeichert bleiben.

---

# Tests

Automatisierte Tests sind Teil des MVP.

## Backend

Mindestens testen:

- FIT-Datei erfolgreich importieren
- zentrale Aktivitätswerte korrekt lesen
- Trackpunkte korrekt lesen
- GPS-Koordinaten korrekt konvertieren
- Laps korrekt lesen
- Datei ohne Herzfrequenz
- Datei ohne GPS
- Duplikat erkennen
- beschädigte FIT-Datei sauber ablehnen
- Nicht-Lauf-Aktivität behandeln

Ideal sind echte kleine FIT-Fixtures im Testbestand.

## Frontend

Mindestens testen:

- Dashboard lädt
- Läufe werden dargestellt
- Detailansicht funktioniert
- fehlende optionale Daten führen nicht zu UI-Fehlern
- Import-Fehler werden verständlich angezeigt

Keine übermäßige Testabdeckung erzwingen. Kritisch sind FIT-Parsing und Datenintegrität.

---

# Definition of Done für das MVP

Das MVP ist fertig, wenn folgendes Szenario zuverlässig funktioniert:

1. Anwendung lokal starten.
2. Mehrere Garmin-FIT-Dateien auswählen.
3. Dateien hochladen.
4. Laufaktivitäten werden korrekt importiert.
5. Doppelte Uploads erzeugen keine Duplikate.
6. Dashboard zeigt die letzten sechs Wochen.
7. Dashboard zeigt Wochenkilometer und zentrale Kennzahlen.
8. Alle Läufe dieses Zeitraums sind auswählbar.
9. Detailseite zeigt die zentralen Laufwerte.
10. GPS-Track wird auf einer interaktiven Karte angezeigt.
11. Pace-, Herzfrequenz- und Höhenverlauf werden als Diagramme angezeigt.
12. Vorhandene Lap-Daten werden dargestellt.
13. Aktivitäten ohne einzelne optionale Messwerte funktionieren trotzdem.
14. Relevante Backend-Tests laufen erfolgreich.
15. Relevante Frontend-Tests laufen erfolgreich.

---

# Nicht im ersten Release

Explizit **nicht** umsetzen:

- Trainingsplan
- Soll-/Ist-Vergleich mit einem Trainingsplan
- KI-Analyse
- automatische Trainingsempfehlungen
- OpenAI-Anbindung
- Garmin Connect API
- Garmin OAuth
- automatischer Garmin-Sync
- Strava-Sync
- GPX- oder TCX-Import
- Benutzerkonten
- Multi-User-Fähigkeit
- Social Features
- native Mobile App
- Push-Benachrichtigungen
- Live-Tracking
- Segmentvergleiche
- Bestzeiten-/Rekordlogik
- komplexe Trainingsbelastungsmodelle
- medizinische oder gesundheitliche Bewertung

Diese Punkte gehören in spätere Ausbaustufen und dürfen den MVP nicht verzögern.

---

# Mögliche spätere Ausbaustufen

## Phase 2 – Trainingsplan

- Trainingsplan hinterlegen
- geplante und ausgeführte Einheiten verbinden
- Wochen-Soll/Ist
- Planerfüllung

## Phase 3 – Trainingsanalyse

- regelbasierte Kennzahlen
- Vergleich mehrerer Wochen
- Belastungsentwicklung
- automatische Analyse von Läufen

## Phase 4 – KI-Coach

- strukturierte Laufanalyse
- Wochenanalyse
- Anpassung kommender Einheiten
- Generierung eines Trainingsplans

## Phase 5 – Garmin

- Garmin Connect Developer Program prüfen
- automatischer Activity-Import
- optional Trainings zurück zu Garmin übertragen

Diese Phasen sind Kontext für die Architektur, aber **keine Anforderungen an das aktuelle MVP**.

---

# Architekturprinzipien

Bei Entscheidungen während der Umsetzung gelten folgende Prioritäten:

1. **Korrekte Trainingsdaten vor schöner Oberfläche**
2. **Einfache Architektur vor vorschneller Abstraktion**
3. **Robuster FIT-Import vor Unterstützung vieler Dateiformate**
4. **Rohdaten erhalten, abgeleitete Werte reproduzierbar berechnen**
5. **Optionale Sensordaten niemals voraussetzen**
6. **MVP-Scope nicht durch zukünftige Features aufblähen**
7. **Komponenten so schneiden, dass Trainingsplan und Analyse später ergänzt werden können**
8. **Keine KI im ersten Release**

---

# Empfohlene Implementierungsreihenfolge

1. Projektgrundgerüst erstellen.
2. PostgreSQL und Schema einrichten.
3. FIT-Parser evaluieren und einen echten Lauf importieren.
4. Activity-, Lap- und Record-Persistenz implementieren.
5. Import-Service robust machen.
6. Duplikaterkennung ergänzen.
7. Upload-Oberfläche bauen.
8. Dashboard mit 6-Wochen-Abfrage erstellen.
9. Wochenkilometer visualisieren.
10. Laufdetail mit Kennzahlen bauen.
11. GPS-Karte integrieren.
12. Pace-/HR-/Höhen-Charts integrieren.
13. Lap-Tabelle ergänzen.
14. Fehlerfälle und fehlende Sensordaten testen.
15. UI aufräumen und MVP abschließen.

Wichtig: Nicht vorzeitig mit Trainingsplan, KI oder Garmin-Integration beginnen.

---

# Erfolgskriterium

Die Anwendung ist erfolgreich, wenn sie die bisherige manuelle Sicht auf Garmin-Läufe durch ein eigenes, schnelles und nachvollziehbares 6-Wochen-Dashboard ersetzt und dabei eine ausreichend saubere Datenbasis schafft, um im nächsten Schritt den Trainingsplan zu integrieren.
