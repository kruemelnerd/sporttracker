# Datenverwaltung und sichere Löschfunktion – Entwurf

## Ziel und Absicht

Die Importansicht soll lokale FIT-Imports und Garmin-/Intervals.icu-Zuordnungen kontrolliert bereinigen können. Jeder destruktive Vorgang wird zuerst mit aktuellen Zahlen und Auswirkungen angezeigt und erfordert anschließend eine passende Bestätigungsphrase. Gelöschte Aktivitäten dürfen nicht unmittelbar durch automatische Synchronisierung wieder angelegt werden.

## Umfang und Nicht-Ziele

Enthalten sind vier Löschaktionen (Garmin, Intervals.icu, alle Aktivitäten, vollständiger fachlicher Datenreset), Vorschau/Bestätigung/Ergebnis, persistente Sync-Pausen, Provenienz für neue Imports und sichere Bereinigung verwalteter FIT-Dateien. Trainingspläne und geplante Einheiten bleiben beim reinen Aktivitätsreset erhalten; das Schema, Flyway-Historie, Konfiguration, Secrets, Container, Volumes und Dateien außerhalb des Datenverzeichnisses bleiben unangetastet.

Nicht enthalten sind Benutzerkonten, allgemeine Authentifizierung, ein Backup-/Restore-System oder eine pauschale Bereinigung des Datenverzeichnisses.

## Befund aus dem aktuellen Modell

- `activity_record`, `lap`, `import_file` und `activity_import_source` referenzieren `activity(id) ON DELETE CASCADE`.
- `training_phase` und `planned_session` hängen per Cascade von `training_plan` ab; keine dieser Tabellen referenziert Aktivitäten.
- `activity_import_source` speichert Provider und externe ID, aber keine Herkunft manueller FIT-Uploads. `import_file` enthält Hash, Größe und optionalen relativen Originalpfad, jedoch ebenfalls keine Herkunft.
- Garmin-Sync persistiert globalen Syncstatus in `garmin_sync_state`; Intervals.icu-Syncstatus ist derzeit nur im Speicher. Beide Syncs und manuelle Imports schreiben über getrennte Abläufe.
- Originale liegen im konfigurierten `running-dashboard.data-directory`, standardmäßig unter `originals/<hash-prefix>/<hash>.fit`; Uploads werden vorübergehend unter `.incoming` abgelegt.
- Es gibt keine Spring-Security-/Login-Sicherung. Das Produktions-Compose verwendet das Profil `container` und bindet den App-Port an Loopback.

## Vorgeschlagene Umsetzung

### Provenienz und sichere Quellbereinigung

Eine additive nullable Herkunftsspalte an `import_file` kennzeichnet neue Dateien als `MANUAL`, `GARMIN` oder `INTERVALS_ICU`; vorhandene Zeilen bleiben `UNKNOWN`. Der FIT-Import erhält einen internen Herkunftsparameter, manuelle Uploads setzen `MANUAL`, Sync-Importe den jeweiligen Provider. Duplikate ändern die ursprüngliche Herkunft nicht.

Bei Garmin-/Intervals-Löschung werden alle Zuordnungen des Providers entfernt. Eine Aktivität wird nur dann mitgelöscht, wenn mindestens eine importierte Datei vorliegt, deren Herkunft vollständig bekannt und ausschließlich der ausgewählte Provider ist, und keine Zuordnung oder andere Provenienz eines anderen Providers/manuellen Imports besteht. Unbekannte, manuelle, ältere oder gemeinsam genutzte Aktivitäten bleiben erhalten und werden in der Vorschau sichtbar als geschützte/mehrdeutige Aktivitäten von tatsächlich löschbaren Aktivitäten unterschieden. Bei jeder betroffenen Gruppe zeigt die Vorschau die zu löschenden und zu erhaltenden Zuordnungen. Mehrere externe IDs desselben Providers sind zulässig. Importhistorie der sicher gelöschten Aktivitäten fällt über den FK-Cascade weg.

„Alle Aktivitäten“ löscht alle Aktivitäten unabhängig von Herkunft, lässt Trainingspläne bestehen und setzt globale Aktivitäts-Syncstatistiken zurück. „Alle Anwendungsdaten“ löscht zusätzlich Trainingspläne (Phasen und Einheiten kaskadieren). Schema, Migrationen und nicht-fachliche Anwendungskonfiguration bleiben erhalten.

### Synchronisierung und dauerhafte Pause

Ein gemeinsam genutzter exklusiver Schreibschutz koordiniert FIT-Batchuploads, beide vollständigen Sync-Läufe samt Quellverknüpfungen und Löschaufträge. Löschungen starten nicht, solange ein Writer läuft (HTTP 409); neue Writer werden während einer Löschung abgewiesen. Die Vorschau bleibt lesend. Der persistente Pausenzustand muss vor dem `ApplicationReady`-Trigger gelesen/greifen; ein automatischer Sync darf erst nach erfolgreicher Pausenprüfung gestartet werden. Auch jeder manuelle/periodische Start prüft denselben Zustand unter dem Koordinator, damit ein Neustart keine Lücke öffnet.

Eine additive Tabelle hält je Provider die dauerhafte Auto-Sync-Pause. Providerbezogene Löschung pausiert den betroffenen Provider; beide Gesamtaktionen pausieren alle konfigurierten Provider. Trigger prüfen Pause und bestehende Konfiguration. Die Importansicht bietet ein explizites, geschütztes Wiederfreigeben; dies setzt die jeweilige Pause zurück und löst nicht unbemerkt sofort einen Sync aus. Manuelle Syncs bleiben ebenfalls gesperrt, bis ausdrücklich freigegeben wurde. „Alle Anwendungsdaten“ löscht sämtliche fachlichen Tabellen, schließt alle Bereinigungsaufträge ab oder lässt sie sichtbar wiederaufnehmbar, und hinterlässt die Sync-Pausen als nicht-fachlichen Sicherheitszustand; damit kann nach Reset oder Anwendungsneustart kein Auto-Sync anlaufen.

### API, Bestätigung und Zugriffsschutz

Ein eigener Controller-/Service-Bereich stellt Funktionsstatus, Vorschau, bestätigten Auftrag, Ergebnis und Pausenstatus/Wiederfreigabe bereit. Vorschauen sind unveränderlich und liefern einen kurzlebigen einmaligen Token mit Aktionsart und Zustandsfingerprint. Die Bestätigung prüft Token, passende Aktionsphrase und aktuellen Fingerprint unter dem Schreibschutz; veraltete Vorschauen ergeben 409, ungültige/abgelaufene Bestätigung 400/410. Erfolgreiche oder partielle Ergebnisse melden tatsächliche Zahlen und fehlgeschlagene Dateibereinigung.

`DATA_DELETION_ENABLED=false` ist der Default. Destruktive Beans/Routes werden im `container`-Profil unabhängig von Umgebungsüberschreibungen nicht registriert. Kein anderer Produktionsstart darf das Flag allein aktivieren: dafür muss zuerst eine geeignete Zugriffssicherung serverseitig aktiv sein; mangels Login/Autorisierungsmechanismus bleibt Löschen im Produktivbetrieb deaktiviert. Außerhalb des Produktionsprofils müssen Endpunkte zusätzlich aktiviert sein und POSTs werden auf same-origin `Origin`/Fetch-Metadata sowie JSON-Content-Type geprüft; kein CORS-Freigeben für diese Routes. Frontend-Steuerung wird nur beim positiven Backend-Status angezeigt. Diese lokale Schutzschicht ist keine Authentifizierung und darf nicht als Produktionszugriffsschutz missverstanden werden.

### Originaldateien und Fehlerbehandlung

Im DB-Delete-Commit werden zu entfernende relative Originalpfade in einer persistenten Bereinigungswarteschlange hinterlegt und die FK-abhängigen Datensätze gelöscht. Erst nach erfolgreichem Commit werden Dateien endgültig entfernt; ein Rollback lässt Dateien somit unangetastet. Nach Commit noch referenzierte Pfade bleiben erhalten. Ein idempotenter Bereiniger validiert jeden Pfad gegen das normalisierte/reale Datenverzeichnis, verweigert Traversal und Symlinkpfade und löscht keine Verzeichnisse. Jobs haben persistente Zustände, so dass ein Prozessabsturz vor, während oder nach dem Dateisystemaufruf nach Neustart sicher wiederaufgenommen werden kann; wiederholtes `deleteIfExists` ist als Erfolg idempotent. Erfolgreiche Jobs werden abgeschlossen; fehlgeschlagene oder fehlende Dateien erhalten einen sichtbaren Status und können wiederholt werden. Ein Wiederholungsversuch löscht nie außerhalb des verwalteten Datenverzeichnisses.

### Oberfläche

Unter dem FIT-Import erscheint optisch getrennter Bereich „Daten verwalten“ mit vier Aktionen. Klick erzeugt zunächst nur eine aktuelle Vorschau. Die UI nennt Aktivitäts-/Zuordnungszahlen, geschützte/mehrdeutige Fälle, erhaltene Daten und den Backup-/Nicht-Rückgängig-Hinweis. Erst nach Eingabe der aktionsspezifischen Phrase wird bestätigt; währenddessen sind Bestätigung und konkurrierende Import-/Löschaktionen deaktiviert. Ergebnis und etwaige Dateifehler bleiben sichtbar. Nach erfolgreicher Mutation werden Synchronisierungsstatus und Aktivitätsansichten neu geladen.

## Tests und Verifikation

Backend-Integrationstests decken Quell-/Gesamtlöschung, Shared-/Manual-/Unknown-Provenienz, mehrere Provider-IDs, Counts, Trainingsplan-Erhalt, vollständigen fachlichen Reset und Rollback, Dateipfade/Symlinks/fehlende Dateien/Fehler/Prozessabbruch und Wiederaufnahme, Locking, Pause vor ApplicationReady-Trigger, Neustartpersistenz, Produktivprofil-Sperre ohne zusätzliche Zugriffssicherung, Bestätigung und Origin-Schutz ab. Frontend-Tests decken Statusanzeige, Vorschau, Phrase, Abbruch, Busy/Error/Ergebnis und Neuladen ab. Alle datenzurücksetzenden Tests verwenden ausschließlich die geprüfte isolierte E2E-Datenbank `running_dashboard_e2e` und synthetische FIT-Dateien.

Abschlussprüfungen: vollständige Backendtests, `bootJar`, Frontend-Unit-Tests/Typecheck/Produktionsbuild, Playwright-Smoke und im Repository vorgeschriebene vollständige Pipeline. Resultate werden dokumentiert; kein Commit/PR bei fehlgeschlagenen Pflichtchecks.

## Git-Lieferung

PR #41 ist zum Entwurfszeitpunkt bereits in `origin/main` gemergt (`c571c1f`). Der Implementierungsbranch basiert auf dem aktualisierten `origin/main`; kein abhängiger Feature-Branch ist erforderlich. Vorhandene lokale Änderungen unter `.opencode/` bleiben im ursprünglichen Checkout unberührt und werden nicht in den PR aufgenommen. Nach erfolgreicher Verifikation eigener Commit und Push, anschließend vorhandenen PR prüfen und einen neuen PR gegen `main` erstellen; kein Merge und kein Deployment.

Der Entwurf wurde vom Auftraggeber freigegeben; die vorstehenden verbindlichen Ergänzungen sind Teil des Umsetzungsumfangs.
