# Containerbetrieb

## Architektur

`compose.production.yaml` betreibt zwei Container: Spring Boot liefert zugleich die Vue-Oberfläche und REST-API aus einem JAR; PostgreSQL 18.6 ist der zweite Dienst. Compose stellt ein internes Netzwerk bereit. Nur die Anwendung veröffentlicht standardmäßig `127.0.0.1:8080`; PostgreSQL hat keinen Host-Port. LAN-Zugriff erfordert eine bewusst konfigurierte Firewall bzw. einen Reverse Proxy. Es gibt keinen Docker-Socket-Mount.

Die externen Volumes `sporttracker-prod-postgres` und `sporttracker-prod-fit` speichern Datenbank bzw. FIT-Dateien. Das App-Volume wird unter `/data` eingebunden und enthält auch `originals/` und `.incoming/`. Container-Neuerstellung löscht diese externen Volumes nicht. Compose-Datei, Image-Referenz und Secrets liegen außerhalb der Datenvolumes.

## Installation und Betrieb

Voraussetzungen: Linux-VM mit Bash und GNU coreutils (`realpath`, `stat`, `sha256sum`), Docker Engine mit Compose v2 (`docker compose`), ausreichend Speicher und ein gesicherter Hostzugang. PostgreSQL-Dump und Restore benötigen PostgreSQL-18-Clientwerkzeuge; die Skripte verwenden dafür den PostgreSQL-18-Container. Das FIT-Archiv benötigt GNU `tar`; Restore benötigt Python 3 zum sicheren Prüfen der Archivpfade.

1. Vorhandene Volumes erst prüfen, dann nur fehlende Produktionsvolumes anlegen. Niemals ein vorhandenes Volume löschen oder ersetzen:

   ```bash
   docker volume inspect sporttracker-prod-postgres  # muss bei Neuinstallation fehlschlagen
   docker volume inspect sporttracker-prod-fit       # muss bei Neuinstallation fehlschlagen
   docker volume create sporttracker-prod-postgres
   docker volume create sporttracker-prod-fit
   ```

2. Secret-Dateien auf dem Host außerhalb des Repositories anlegen. Passwörter zufällig und unabhängig erzeugen; die App-Passwortdatei muss mit `postgres_app_password` übereinstimmen. PostgreSQL-Dateien müssen im Container für UID 999, App-Dateien für UID 10001 lesbar sein. Auf dem Host Modus `0400`, Verzeichniszugriff nur für den Betriebsbenutzer. Die `APP_DATABASE_URL`- und `APP_DATABASE_USERNAME`-Dateien enthalten keine Passwörter, sind aber ebenfalls nur für UID 10001 zugänglich zu machen. Keine Secret-Inhalte in Shell-History, Kommandoargumente oder Logs schreiben.

3. In einer geschützten Compose-Umgebungsdatei ausschließlich Pfade/Deploy-Konfiguration setzen (keine Passwortwerte), z. B. `POSTGRES_ADMIN_PASSWORD_FILE`, `POSTGRES_APP_PASSWORD_FILE`, `APP_DATABASE_URL_FILE`, `APP_DATABASE_USERNAME_FILE`, `APP_DATABASE_PASSWORD_FILE`, `SPORTTRACKER_IMAGE` und `SPORTTRACKER_IMAGE_DIGEST`. Image-Digest und Pfade vor dem Start verifizieren.

4. Start und Stop:

   ```bash
   docker compose --env-file /secure/path/production.env -p sporttracker-prod -f compose.production.yaml up -d --wait
   docker compose --env-file /secure/path/production.env -p sporttracker-prod -f compose.production.yaml ps
   docker compose --env-file /secure/path/production.env -p sporttracker-prod -f compose.production.yaml stop
   ```

   `stop` und `down` erhalten benannte Volumes. Niemals `down -v`, `docker volume rm/prune`, `Flyway clean` oder einen Datenbank-Reset für den produktiven Stack verwenden.

## Datensicherheit und Updates

- Das PostgreSQL-Volume enthält den vollständigen Datenbankzustand. Das separate FIT-Volume enthält Originaldateien, Zwischenablagen und weitere App-Dateien.
- `compose.yaml`/`./dev.sh` sind lokale Entwicklung; `compose.production.yaml` verwendet externe Volumes, dateibasierte Compose-Secrets, digest-gepinntes Image und bindet den App-Port nur an Loopback. Testressourcen müssen ein eigenes Compose-Projekt und eigene Volumes verwenden.
- Flyway ist beim App-Start aktiv und migriert vor Nutzung der Anwendung. Ein Start nach Restore darf erst erfolgen, wenn Dump und FIT-Dateien vollständig wiederhergestellt und geprüft sind. Es gibt keinen automatischen Rollback/Reset.
- PostgreSQL-Major-Upgrades sind kein Image-Tag-Wechsel: vorher verifiziertes Backup und Restore-Probe, danach ein geplanter `pg_upgrade`-/Dump-Migrationspfad. Kein Major-Upgrade durch direkten Volume-Austausch.

## Backups

`scripts/backup.sh` verlangt den Docker-Kontext, Compose-Projektnamen und die Compose-Datei ausdrücklich. Vorher `docker context ls`/`docker context inspect` verwenden und den gewünschten Host verifizieren; der Kontextname wird in den Metadaten festgehalten. Das Skript stoppt den ausdrücklich angegebenen App-Service, während PostgreSQL läuft. Dadurch sind Intervals-Sync und HTTP-Schreibzugriffe der Anwendung während des Dump-/Datei-Snapshots ausgesetzt. Direkte externe Datenbank- oder Volume-Schreibzugriffe müssen Betriebs-seitig ebenfalls ausgeschlossen werden. Das Skript sichert den vollständigen PostgreSQL-Dump im Custom-Format, das komplette `/data`-Verzeichnis (einschließlich versteckter Dateien), Flyway-Version, Compose-/PostgreSQL-Version, Image-Referenz und Image-Digest/Image-ID sowie nicht geheime Restore-Hinweise. SHA256SUMS deckt alle Backup-Artefakte ab. Erst nach Validierung und erfolgreichem Wiederanlauf wird das Backup als erfolgreich gemeldet.

Ziel muss ein geschütztes Verzeichnis außerhalb beider Docker-Volumes sein. Für Produktivbetrieb ein externes, verschlüsseltes und zugriffsbeschränktes Backupziel verwenden, idealerweise außerhalb der VM (NAS/separater Datenträger). Lokale Beispielausführung:

```bash
scripts/backup.sh --docker-context default --project sporttracker-prod --compose-file compose.production.yaml --env-file /secure/path/production.env --output-dir /mnt/backup/sporttracker
(cd /mnt/backup/sporttracker/<backup-verzeichnis> && sha256sum --check SHA256SUMS)
```

Die angegebene Compose-Umgebungsdatei enthält die Dateipfade für `POSTGRES_*_FILE` und `APP_DATABASE_*_FILE` sowie die Image-Referenz; sie enthält keine Passwortwerte. Kein Secret wird in Metadaten gesichert. Bei Fehlern bleibt ein nicht veröffentlichter Teilstand unvollständig; die App wird per Exit-Trap wieder gestartet. Ein nicht erfolgreicher Wiederanlauf ist ein kritischer Fehler und verlangt manuelle Prüfung.

SHA256SUMS erkennt unbeabsichtigte Beschädigung, ist aber keine Signatur gegen gezielte Manipulation. Backup-Zugriff deshalb beschränken und externe Kopien zusätzlich mit den Schutzmechanismen des Zielsystems sichern.

Empfehlung für später: tägliches Backup, zunächst 14 Tage Aufbewahrung, mit regelmäßigen Restore-Proben und einer zweiten Kopie außerhalb der VM. **Timer, Rotation und Offsite-Übertragung sind nicht implementiert; die Automatisierung ist M8.4.** Das Skript löscht keine alten Backups.

## Wiederherstellung

`scripts/restore.sh` akzeptiert ausschließlich das geprüfte Repository-`compose.restore.yaml` und ist auf neue, isolierte Compose-Projekte beschränkt. Es akzeptiert keine produktionsähnlichen Projektnamen, verlangt `--confirm-empty-project` mit exakt gleichem Zielnamen und bricht bei vorhandenen Containern/Volumes ab. Die wiederhergestellte App wird nicht automatisch gestartet. Fehlerhafte oder fehlende Backup-Dateien, Prüfsummenabweichungen, falsches PostgreSQL-Major, unsafe Archivpfade und nicht leere/unklare Ziele brechen den Ablauf ab. Bereits angelegte Restore-Ressourcen werden bei Fehlern ausdrücklich nicht automatisch entfernt.

Für einen isolierten Restore zuerst eigene, zufällige Test-Secrets mit den erforderlichen Eigentümern/Rechten bereitstellen und ein neues Projekt wählen. `compose.restore.yaml` enthält die benötigte Postgres-/App-Konfiguration, aber keine fest eingetragenen Volumenamen. Die Variablen aus der Compose-Umgebungsdatei setzen; `SPORTTRACKER_IMAGE` und `SPORTTRACKER_IMAGE_DIGEST` müssen auf das Image aus den Backup-Metadaten zeigen.

```bash
scripts/restore.sh \
  --docker-context default \
  --backup-dir /mnt/backup/sporttracker/<backup-verzeichnis> \
  --project sporttracker-m816-restore \
  --compose-file compose.restore.yaml \
  --env-file /secure/path/restore.env \
  --confirm-empty-project sporttracker-m816-restore
```

Der Ablauf verlangt einen ausdrücklich angegebenen Docker-Kontext und Restore-Projektnamen, prüft Checksummen und Archivformat, fordert nachweislich nicht existente projektspezifische Volumes, initialisiert eine leere Datenbank mit der vorgesehenen Rolle, stellt mit `pg_restore --exit-on-error --single-transaction --no-owner --no-privileges` wieder her, vergleicht Flyway-Version und entpackt FIT-Dateien ohne Links/Sonderdateien in das neue Volume. Die Skript-Ausgabe nennt den Zielprojekt-Namen; vor dem App-Start zusätzlich Tabellen-/Datensatzanzahlen, Aktivitäts-IDs, Trainingspläne/-einheiten, FIT-Pfade, Dateihashes und Dateirechte mit der Quelle vergleichen. Danach kann die App separat gestartet werden:

```bash
docker --context default compose --env-file /secure/path/restore.env -p sporttracker-m816-restore -f compose.restore.yaml up -d --wait app
```

Readiness, API-Antworten und das tatsächliche Lesen wiederhergestellter FIT-Originale prüfen. Die Import-API-Antwort `originalRetained` ist ein Importzeitpunkt-Ergebnis und wird nicht als boolesche Spalte gespeichert; dauerhaft belegt wird die Ablage durch `import_file.original_path`, Dateiinhalt und SHA256. Der API-Port ist standardmäßig nur loopback `18082`; bei Portkollision `RESTORE_APP_PORT` auf einen freien lokalen Port setzen. Bei Abweichungen nicht erneut importieren, keine Volumes löschen und keine Flyway-Reparatur ausführen; Ziel anhalten und Ursache untersuchen. Nach VM-Ausfall wird zunächst auf einer neuen VM/in neuen Volumes wiederhergestellt und validiert. Die Umschaltung in einen produktiven Stack bzw. das Ersetzen produktiver Ressourcen ist ein manueller, separat freizugebender Betriebsakt und wird vom Restore-Skript nicht ausgeführt.

## Laufender Betrieb und Sicherheit

- Logs ohne Secrets prüfen: `docker compose ... logs --since=15m app db`. Container-Health mit `docker compose ... ps` und `/api/health` bzw. `/api/ready` kontrollieren.
- Neustart ohne Datenlöschung: `docker compose ... restart app` oder `docker compose ... up -d`. Bei Fehlern Ursache prüfen; keine Volume-/Datenbank-Reparatur automatisieren.
- Ressourcenlimits, Container-Logs und freien Platz auf Host/Volumes regelmäßig prüfen. Datenbank- und FIT-Volume wachsen unabhängig.
- Image-Version: `docker compose ... images` und Container-Image-Digest via `docker inspect`; neue Images nur nach Backup, Release-Prüfung und geplantem Rollback-/Restore-Test deployen.
- Container laufen ohne Root (App UID 10001, PostgreSQL-Image-eigener Benutzer); App-Dateisysteme sind read-only, Capabilities reduziert, `no-new-privileges` aktiv. Secrets kommen als Dateien, nicht als Compose-Klartext-Umgebungswerte.
- PostgreSQL-Port nicht öffentlich veröffentlichen. Zugriff auf Docker-Daemon entspricht Host-Root und ist auf Administratoren zu beschränken. Keine Docker-Socket-Mounts hinzufügen.
- Sicherheitsupdates kontrolliert gegen PostgreSQL-Kompatibilität, Image-Digests und Restore-Test prüfen. Intervals.icu bleibt optional und ist im Produktions-Basis-Compose deaktiviert.

## Regressionstest-Isolation

Die vorhandene Backend- und Playwright-E2E-Infrastruktur verwendet fest `sporttracker-m4-e2e`, `running_dashboard_e2e` und Port `55432`; beide Testpfade leeren Anwendungstabellen. Zum Zeitpunkt dieser Dokumentation ist dieses Projekt bereits vorhanden. Diese Tests daher nicht gegen das vorhandene Projekt starten. Ein eigener Lauf erfordert eine separate Umsetzung, die Projektname, Port, Datenbankkennung und Testzugangsdaten durch Gradle-Testsetup, Compose und Playwright-Global-Setup konfigurierbar macht. Bis dahin Regressionstests nur nach nachweislich getrennter Infrastruktur ausführen.

Bekannte Einschränkung: Es gibt noch keine systemd-Automatisierung, Backup-Rotation, Offsite-Replikation, Verschlüsselung des Backup-Artefakts, konfigurierbare separate E2E-Testprojekte oder automatischen produktiven Cutover. Ein erfolgreicher Skript-Restore auf frische Ressourcen ersetzt nicht die regelmäßige Notfallübung.
