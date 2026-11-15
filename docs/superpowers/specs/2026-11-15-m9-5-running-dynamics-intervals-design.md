# M9.5 – Trainingsdiagramme und strukturierte Intervalle

## Ziel

Die Aktivitätsdetailseite soll FIT-Record-Messwerte direkt und verständlich visualisieren und echte, strukturierte Workout-Phasen getrennt von gewöhnlichen Runden und Timer-Pausen darstellen. Fehlende Messwerte bleiben fehlend; es werden keine Durchschnittswerte als Zeitreihen ausgegeben.

## Umfang und Grenzen

- Vorhandene FIT-Record-Messpunkte und Garmin FIT SDK Felder nutzen; FIT-Zeitstempel bleiben UTC-Instanten.
- Neue Werte in kanonischen Einheiten speichern und nur über eine additive Flyway-Migration ergänzen.
- Workout-Steps, Wiederholungsblöcke und per `lap.wkt_step_index` zugeordnete Ausführungsrunden als Intervalle behandeln; andere Laps bleiben Runden.
- Timer-Stop/Start-Paare separat als Unterbrechungen ausweisen.
- Diagramme bleiben bei ECharts, benutzen eine gemeinsame Zeit- oder Distanzachse und erhalten passende Intervallbereiche, Tooltips und Zoom-Synchronisation.
- Import und Garmin-Sync verwenden dieselbe FIT-Decodierung. Eine explizite erneute Bereitstellung darf vorhandene Daten nur ergänzen und keine Aktivität/Laps duplizieren oder Garmin-Enrichment löschen. Kein automatischer Backfill.
- Karten, Dashboard, Trainingsplanverwaltung und M9.3-Export bleiben unverändert.

## Bestandsaufnahme

| Metrik | FIT-Feld | Einheit | Parser vorhanden | DB vorhanden | API vorhanden | UI vorhanden |
| --- | --- | --- | --- | --- | --- | --- |
| Pace | `record.speed` / `enhanced_speed`, ersatzweise Record-Distanz und -Zeit | min/km | ja | ja (`speed_mps`) | ja | ja |
| Herzfrequenz | `record.heart_rate` | bpm | ja | ja | ja | ja |
| Höhe | `record.altitude` / `enhanced_altitude` | m | ja | ja | ja | ja |
| Kadenz | `record.cadence` | FIT rpm; UI multipliziert einmal mit 2 | ja | ja | ja | ja |
| Schrittlänge | `record.step_length` | m (FIT-Profilwert in mm) | nein | nein | nein | nein |
| Vertikale Bewegung | `record.vertical_oscillation` | cm (FIT-Profilwert in mm) | nein | nein | nein | nein |
| Vertikales Verhältnis | `record.vertical_ratio` | % | nein | nein | nein | nein |
| Bodenkontaktzeit | `record.stance_time` | ms | nein | nein | nein | nein |
| Bodenkontaktbalance | `record.stance_time_balance` | % links/rechts | nein | nein | nein | nein |
| Temperatur | `record.temperature` | °C | nein | nein | nein | nein |
| Running Power | `record.power` | W | nein | nein | nein | nein |
| Geschwindigkeit | `record.speed` / `enhanced_speed` | km/h (Anzeige) | ja | ja | ja | nur als Pace |
| Workout-Phasen | `workout`, `workout_step`, `lap.wkt_step_index` | Dauer/Distanz/Ziel | nein | nein | nein | nein |
| Timer-Pausen | `event` mit Timer Start/Stop | UTC-Zeitpunkt | nein | nein | nein | nein |

Die FIT-SDK-Getter übernehmen die im Profil definierte Standardfeld-Skalierung. Entwicklerfelder werden nur übernommen, wenn Native-Override oder Feldname **und** Einheit eindeutig einer unterstützten Metrik entsprechen; nicht bekannte Werte werden ignoriert.

## Lokale Fixture-Evidenz

`synthetic-running.fit` und `synthetic-running-2.fit` sind synthetische Testdateien. Die bestehende lokale SDK-Dekodierung erkennt in beiden je zwei Records und eine Lap; die Tests belegen Herzfrequenz, außerdem für die erste Datei einen Höhenwert. In diesen Fixtures ist keine Workout-Struktur nachgewiesen. Die dokumentierte Summary-Ausgabe ist kein vollständiger FIT-Feldinventarbericht; fehlende Summary-Felder gelten daher nicht als Nachweis für Feldabwesenheit.

## Architekturentscheidungen

- FIT-Adapter bleibt gemeinsame Dekodierstelle für manuelle Imports, Garmin-ZIP-Import und Detailwiederherstellung.
- Neue Record-Metriken werden in `activity_record` ergänzt. Workout-Step-Definitionen, Lap-Grenzen/-Zuordnung und Timerereignisse werden additiv relational persistiert; keine FIT-Rohdatei wird redundant gespeichert.
- Strukturierte Phasen werden nur aus tatsächlich vorhandenen Workout-Steps plus eindeutiger Lap-Zuordnung angezeigt. Ohne Struktur bleiben vorhandene Runden unverändert.
- Achsenpositionen werden aus FIT-Zeit/Distanzen gebildet; keine Geschwindigkeitsschwelle klassifiziert Lauf/Gehen.
- Downsampling bleibt begrenzt und bewahrt Nullübergänge sowie Intervallgrenzen.

## Verifikation

FIT-Adapter-/Einheitentests, Persistenz- und Reimporttests, Frontend-Serientests, Detailseiten-E2E inkl. schmalem Viewport und großer Zeitreihe. Abschließend `./scripts/test-all.sh`, `./gradlew bootJar` und `git diff --check`. Alle DB-Tests müssen den isolierten Testdatenbank-Runner verwenden.

## Offizielle Referenzen

- Garmin FIT Protocol: https://developer.garmin.com/fit/protocol/
- Garmin Workout File Structure: https://developer.garmin.com/fit/file-types/workout/
- Garmin Activity File Structure: https://developer.garmin.com/fit/file-types/activity/
- Garmin Developer Data Cookbook: https://developer.garmin.com/fit/cookbook/developer-data/
- FIT scale/offset werden vom SDK für Profilfelder angewendet; Entwicklerfelder werden nicht anhand der nativen Feldskalierung verändert.
- Wiederholungsblöcke verweisen per `duration_type=repeat_until_steps_cmplt` auf einen vorherigen `message_index`; Aktivitäts-Laps können per `wkt_step_index` auf Workout-Steps zeigen.
