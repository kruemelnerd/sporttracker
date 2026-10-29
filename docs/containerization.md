# Containerbetrieb

## Architektur

Für die phasenweise Linux-/VM-/LXC-Erstinstallation siehe [Linux First Install](proxmox-first-install.md).

`compose.production.yaml` betreibt zwei Container: Spring Boot liefert zugleich die Vue-Oberfläche und REST-API aus einem JAR; PostgreSQL 18.6 ist der zweite Dienst. Compose stellt ein internes Netzwerk bereit. Nur die Anwendung veröffentlicht standardmäßig `127.0.0.1:8080`; PostgreSQL hat keinen Host-Port. LAN-Zugriff erfordert eine bewusst konfigurierte Firewall bzw. einen Reverse Proxy. Es gibt keinen Docker-Socket-Mount.

Die externen Volumes `sporttracker-prod-postgres` und `sporttracker-prod-fit` speichern Datenbank bzw. FIT-Dateien. Das App-Volume wird unter `/data` eingebunden und enthält auch `originals/` und `.incoming/`. Container-Neuerstellung löscht diese externen Volumes nicht. Compose-Datei, Image-Referenz und Secrets liegen außerhalb der Datenvolumes.

Linux-Rechner, VM oder unprivilegierter Proxmox-LXC, ein lokaler Reverse Proxy für LAN-Zugriff und das obligatorische SPDX-/Trivy-Gate vor jedem Deployment sind in [docs/proxmox-deployment.md](proxmox-deployment.md) beschrieben. Niemals auf dem Proxmox-VE-Host installieren.

## Installation und Betrieb

Hostwerkzeuge über den [einfachen Linux-Einstieg](../README.md#einfache-installation-unter-linux) installieren: `install-host-tools.sh` arbeitet als einzelne heruntergeladene Datei, installiert fehlende Abhängigkeiten nach Bestätigung und stellt `/opt/sporttracker` nur bei Fehlen bereit. `--check` bleibt ausschließlich lesend; erneute normale Ausführung verändert funktionierendes Docker nicht. `--upgrade` verlangt bei Docker/containerd-Ersatz manuelle Wartung und führt keine automatischen Dienst-/Container-Neustarts aus. Setup erzeugt keine produktiven Volumes/Secrets und deployt nichts. Erst anschließend die gesondert freizugebenden Schritte unten durchführen.

Ubuntu/Debian Server und Desktop werden anhand tatsächlicher signierter Paketquellen und Laufzeitfunktionen geprüft, nicht anhand einer festen OS-Version. Ressourcenplanung: ca. 4 GiB RAM bieten mehr Reserve, 8 GiB sind kein hartes Minimum. LXC 116 mit 2 GiB RAM + 2 GiB Swap/4 vCPU/40 GB wird berücksichtigt; zusammen 2 GiB App-/DB-Speicherlimits plus Docker/OS/Trivy können trotzdem zu Engpässen führen. Bei OOM oder Scanfehlern Ressourcen/Last manuell klären, Sicherheitsprüfungen nicht abschalten. Keine automatischen LXC-/AppArmor-/Seccomp-/Firewalländerungen.

### Image-Veröffentlichung

Ein erfolgreicher Push auf `main` veröffentlicht das Image nach den bestehenden CI- und Security-Jobs. Der Workflow baut einmal für `linux/amd64`, prüft Image-Konfiguration und scannt Betriebssystem-/Java-Abhängigkeiten, Container-Konfiguration sowie Secrets mit Trivy. High- oder Critical-Befunde verhindern den Push; Medium-/Low-Befunde bleiben im Actions-Log dokumentiert. Erst danach werden `sha-<vollständiger Commit>` und `stable` aus genau diesem geprüften lokalen Image nach GHCR gepusht. Veröffentlichungen sind serialisiert; Läufe, deren Commit nicht mehr `main`-HEAD ist, werden verworfen.

Der Actions-Run zeigt nach erfolgreichem Push den Registry-Digest, die Pull-Prüfung und die vollständige Referenz `ghcr.io/kruemelnerd/sporttracker@sha256:<digest>`. Für den späteren Compose-Betrieb `SPORTTRACKER_IMAGE=ghcr.io/kruemelnerd/sporttracker` und `SPORTTRACKER_IMAGE_DIGEST=sha256:<digest>` setzen; nicht `stable` als Produktionsreferenz verwenden. Das Package bleibt privat, bis seine Sichtbarkeit separat geprüft und freigegeben wird. Dieser Workflow deployt nicht auf Proxmox.

Voraussetzungen: unterstütztes Linux-Zielsystem mit Bash und GNU coreutils (`realpath`, `stat`, `sha256sum`), funktionsfähige Docker Engine mit Compose-Plugin (`docker compose`, auch kompatible neuere Hauptversionen), Buildx, containerd, GitHub CLI mit allen Verifier-Optionen, Trivy, ausreichend Speicher und gesicherter Adminzugang. PostgreSQL-Dump und Restore benötigen PostgreSQL-18-Clientwerkzeuge; die Skripte verwenden dafür den PostgreSQL-18-Container. Das FIT-Archiv benötigt GNU `tar`; Restore benötigt Python 3 zum sicheren Prüfen der Archivpfade.

1. Vorhandene Volumes erst prüfen, dann nur fehlende Produktionsvolumes anlegen. Niemals ein vorhandenes Volume löschen oder ersetzen:

   ```bash
   docker volume inspect sporttracker-prod-postgres  # muss bei Neuinstallation fehlschlagen
   docker volume inspect sporttracker-prod-fit       # muss bei Neuinstallation fehlschlagen
   docker volume create sporttracker-prod-postgres
   docker volume create sporttracker-prod-fit
   ```

2. Secret-Dateien auf dem Host außerhalb des Repositories anlegen. Passwörter zufällig und unabhängig erzeugen; die App-Passwortdatei muss mit `postgres_app_password` übereinstimmen. PostgreSQL-Dateien müssen im Container für UID 999, App-Dateien für UID 10001 lesbar sein. Auf dem Host Modus `0400`, Verzeichniszugriff nur für den Betriebsbenutzer. Die `APP_DATABASE_URL`- und `APP_DATABASE_USERNAME`-Dateien enthalten keine Passwörter, sind aber ebenfalls nur für UID 10001 zugänglich zu machen. Keine Secret-Inhalte in Shell-History, Kommandoargumente oder Logs schreiben.

3. In einer geschützten Compose-Umgebungsdatei ausschließlich Pfade/Deploy-Konfiguration setzen (keine Passwortwerte), z. B. `POSTGRES_ADMIN_PASSWORD_FILE`, `POSTGRES_APP_PASSWORD_FILE`, `APP_DATABASE_URL_FILE`, `APP_DATABASE_USERNAME_FILE`, `APP_DATABASE_PASSWORD_FILE`, `SPORTTRACKER_IMAGE` und `SPORTTRACKER_IMAGE_DIGEST`. Image-Digest und Pfade vor dem Start verifizieren.

4. Start ausschließlich über den verifizierten Deployment-Prozess; vollständigen freigegebenen Digest einsetzen, niemals `stable`:

   ```bash
   sudo bash scripts/deploy-verified.sh --env-file /secure/path/production.env --project sporttracker-prod 'ghcr.io/kruemelnerd/sporttracker@sha256:<freigegebener-64-stelliger-digest>'
   docker compose --env-file /secure/path/production.env -p sporttracker-prod -f compose.production.yaml ps
   docker compose --env-file /secure/path/production.env -p sporttracker-prod -f compose.production.yaml stop
   ```

   `stop` und `down` erhalten benannte Volumes. Niemals `down -v`, `docker volume rm/prune`, `Flyway clean` oder einen Datenbank-Reset für den produktiven Stack verwenden.

## Datensicherheit und Updates

- Das PostgreSQL-Volume enthält den vollständigen Datenbankzustand. Das separate FIT-Volume enthält Originaldateien, Zwischenablagen und weitere App-Dateien.
- `compose.yaml`/`./dev.sh` sind lokale Entwicklung; `compose.production.yaml` verwendet externe Volumes, dateibasierte Compose-Secrets, digest-gepinntes Image und bindet den App-Port nur an Loopback. Testressourcen müssen ein eigenes Compose-Projekt und eigene Volumes verwenden.
- Flyway ist beim App-Start aktiv und migriert vor Nutzung der Anwendung. Ein Start nach Restore darf erst erfolgen, wenn Dump und FIT-Dateien vollständig wiederhergestellt und geprüft sind. Ein fehlgeschlagener App-Start kann ein bereits migriertes Schema hinterlassen; `deploy-verified.sh` führt deshalb keinen automatischen Image-Rollback aus. Ein Container-Rollback ersetzt keine Datenbankwiederherstellung. Bei Fehlern Zustand erhalten und manuelle Wiederherstellung aus geprüftem Backup planen.
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
- Reiner Neustart eines bereits verifizierten, persistent gespeicherten Digests ohne Konfigurations-/Imagewechsel: `docker compose ... restart app`. Jede Erstinstallation und jedes Image-Update ausschließlich über `scripts/deploy-verified.sh`; kein direktes `compose up` als alternativer Deploymentweg. Bei Fehlern Ursache prüfen; keine Volume-/Datenbank-Reparatur automatisieren.
- Ressourcenlimits, Container-Logs und freien Platz auf Host/Volumes regelmäßig prüfen. Datenbank- und FIT-Volume wachsen unabhängig.
- Image-Version: `docker compose ... images` und Container-Image-Digest via `docker inspect`; neue Images nur nach Backup, Release-Prüfung und geprüftem Restore-Pfad deployen. Der Deployment-Runner aktualisiert die gespeicherte Digest-Zeile atomar und bewahrt die vorige Compose-Umgebung auf, damit ein normaler Compose-Neustart nicht auf einen alten Digest zurückfällt.
- Container laufen ohne Root (App UID 10001, PostgreSQL-Image-eigener Benutzer); App-Dateisysteme sind read-only, Capabilities reduziert, `no-new-privileges` aktiv. Secrets kommen als Dateien, nicht als Compose-Klartext-Umgebungswerte.
- PostgreSQL-Port nicht öffentlich veröffentlichen. Zugriff auf Docker-Daemon entspricht Host-Root und ist auf Administratoren zu beschränken. Keine Docker-Socket-Mounts hinzufügen.
- Sicherheitsupdates kontrolliert gegen PostgreSQL-Kompatibilität, Image-Digests und Restore-Test prüfen. Intervals.icu bleibt optional und ist im Produktions-Basis-Compose deaktiviert.

## Regressionstest-Isolation

`./scripts/test-all.sh` erzeugt pro Lauf ein zufälliges `sporttracker-ci-<run-id>`-Projekt, eigene Datenbank/Benutzer, dynamische Loopback-Ports und ein eigenes Testvolume über `compose.test.yaml`. Vor jedem Reset werden Identität und Laufmarker geprüft; das Cleanup entfernt nur eindeutig diesem Lauf zugeordnete Ressourcen. Nicht direkt gegen `compose.yaml`, bestehende Produktivressourcen oder das alte `compose.e2e.yaml` testen. Backend/Playwright verlangen die konsistente `TEST_*`-Umgebung des Runners.

Die Installer-Tests sind Teil desselben CI-Testlaufs. Separat und ohne Docker-/APT-Änderungen: `python3 -m unittest discover -s tests/security -p 'test_install_host_tools.py'`. Sie simulieren Ubuntu/Debian, Erstinstallation und Wiederholung, prüfen negative Fälle und echte isolierte GPG-Signaturen. Das ist kein realer vollständiger VM-/LXC-Installationstest; insbesondere Proxmox-Hostprofile werden in CI nicht verändert oder als erfolgreich getestet ausgegeben.

Bekannte Einschränkung: Es gibt noch keine systemd-Automatisierung für den Sporttracker, Backup-Rotation, Offsite-Replikation, Verschlüsselung des Backup-Artefakts oder automatischen produktiven Cutover. Ein erfolgreicher Skript-Restore auf frische Ressourcen ersetzt nicht die regelmäßige Notfallübung.
