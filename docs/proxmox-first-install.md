# Proxmox: erste Installation vorbereiten

**Nur Vorbereitung.** Diese Anleitung führt keine Proxmox-Änderung und kein Deployment aus. Alle VM-Befehle führt der Benutzer selbst aus. Keine IP, DNS-Adresse oder LAN-CIDR ist vorgegeben. Bis zur ausdrücklichen Freigabe für Phase 4 werden keine Produktivcontainer gestartet und keine echten Trainingsdaten übernommen.

## Ziel und Regeln

- Dedizierte VM: Ubuntu Server 24.04 LTS, 4 vCPU, 8 GiB RAM, mindestens 40 GiB System-/Docker-Speicher. PostgreSQL/FIT- und Backup-Speicher separat einplanen.
- LAN-only. PostgreSQL erhält keinen Host-Port; die App bleibt an `127.0.0.1:8080`. Reverse Proxy ist ein VM-Hostdienst.
- Nur offizielle Ubuntu-, Docker-, GitHub CLI- und Aqua Security (Trivy)-Paketquellen. Keine `curl | bash`-Installer.
- Image immer per vollständigem SHA256-Digest bereitstellen und auf der VM erneut prüfen. CI-Erfolg allein reicht nicht.
- Niemals vorhandene Volumes `sporttracker-prod-postgres` oder `sporttracker-prod-fit` löschen, ersetzen oder diagnostisch beschreiben.
- Keine Secrets in Git, Shell-History, Prozessargumente, Tickets oder Logs übernehmen.

## PHASE 1 – VM vorbereiten

**Stoppunkt 1:** Erst fortfahren, wenn die Abnahmekriterien am Ende von Phase 1 geprüft und vom Betreiber bestätigt wurden.

### A. VM in Proxmox manuell erstellen

1. Im Proxmox-Webinterface manuell eine neue, dedizierte VM anlegen; keine Änderungen am Proxmox-Host, Storage oder Netzwerk aus dieser Anleitung ableiten.
2. Ubuntu Server 24.04 LTS ISO aus der offiziellen Ubuntu-Quelle beziehen und die veröffentlichte SHA256-Prüfsumme vergleichen.
3. 4 vCPU, 8 GiB RAM und mindestens 40 GiB System-/Docker-Datenträger zuweisen; Storage für Daten und externe Backups nicht als Teil dieser Mindestgröße annehmen.
4. Netzwerkkarte nur an das vorgesehene private LAN hängen. Keine Router-Portweiterleitung einrichten. VM-Name, Adresse, DNS und LAN-CIDR vor Ort bestimmen; hier nicht raten.

### B. Ubuntu Server installieren

1. Ubuntu Server 24.04 LTS installieren, verschlüsselten Datenträger verwenden, sofern das lokale Betriebs-/Recovery-Verfahren dies unterstützt.
2. Einen persönlichen Administrator statt gemeinsam genutzter Konten anlegen. OpenSSH Server nur installieren, wenn SSH benötigt wird.
3. Keine Anwendungsdaten oder Secrets auf dem Installationsmedium ablegen.

### C. SSH und Betriebssystem absichern

```bash
sudo apt update
sudo apt full-upgrade
sudo apt install --yes ca-certificates curl gnupg git jq openssh-client
sudo reboot
```

Nach dem Neustart SSH-Schlüsselzugang von einem zweiten Admin-Gerät testen, bevor Passwortzugriff deaktiviert wird. `PermitRootLogin no` und `PasswordAuthentication no` in einer Datei unter `/etc/ssh/sshd_config.d/` setzen; anschließend `sudo sshd -t` und `sudo systemctl reload ssh` ausführen. Den Zugang in einer zweiten Sitzung erneut prüfen.

Firewall-Regeln erst nach Ermittlung des tatsächlichen Admin-Netzes planen. SSH nicht aussperren: zuerst nur den benötigten Admin-LAN-Zugriff erlauben, dann UFW aktivieren. Proxy-Ports kommen in Phase 3. Keine öffentlichen Regeln hinzufügen.

### D. Docker Engine installieren

Installationsweg nach der offiziellen [Docker-Anleitung für Ubuntu](https://docs.docker.com/engine/install/ubuntu/). Ubuntu-Codename/Architektur prüfen; den Docker-APT-Schlüssel aus `download.docker.com` in `/etc/apt/keyrings/docker.asc` speichern, dessen Fingerprint mit der aktuell von Docker veröffentlichten Angabe vergleichen und das APT-Repository gemäß offizieller Anleitung eintragen. Danach:

```bash
sudo apt update
apt-cache policy docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo apt install --yes docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo systemctl enable --now docker
docker --version
docker compose version
sudo docker info
```

Keine Nutzer pauschal zur Gruppe `docker` hinzufügen (diese entspricht weitgehend Root-Zugriff). Für administrative Docker-Aufrufe `sudo docker` verwenden und die Kommandos der Anleitung entsprechend mit `sudo` ausführen.

### E. GitHub CLI installieren

Offizielle [GitHub-CLI-APT-Anleitung](https://github.com/cli/cli/blob/trunk/docs/install_linux.md) verwenden: GitHub-CLI-Schlüssel aus der offiziellen `cli.github.com`-Quelle laden, den veröffentlichten Fingerprint unabhängig vergleichen, Keyring und signiertes APT-Repository einrichten und `gh` aus APT installieren. Keine Installationspipeline ausführen.

```bash
gh --version
gh attestation verify --help
```

Die benötigten Flags `--signer-workflow`, `--source-ref`, `--source-digest`, `--cert-oidc-issuer`, `--predicate-type` und `--format` müssen unterstützt werden. Keine Authentifizierung auf gemeinsam genutzten Terminals durchführen.

### F. Trivy installieren

Trivy über die offizielle [Aqua Security APT-Repository-Anleitung](https://aquasecurity.github.io/trivy/latest/getting-started/installation/) einrichten. Schlüssel/Fingerprint und Repository wie dort dokumentiert prüfen; keine Installer-Skripte verwenden. Danach `trivy --version` ausführen. Der spätere Scan muss eine aktuelle Vulnerability-Datenbank laden können.

### G. Repository bereitstellen

```bash
git clone https://github.com/kruemelnerd/sporttracker.git
cd sporttracker
git status --short --branch
git rev-parse origin/main
```

Vor dem Betrieb Branch/Commit mit dem freigegebenen Release abgleichen. Das Repository enthält keine Credentials. Dateien `compose.production.yaml`, `scripts/`, `docker/postgres/` und `docs/` nicht lokal durch Entwicklungsvarianten ersetzen.

### H. GHCR-Zugriff prüfen

Das Quellrepository und das GHCR-Package können unterschiedliche Sichtbarkeit haben. Package-Sichtbarkeit nicht ändern. Beide Wege sind zulässig:

**A – Package öffentlich:** anonymen Zugriff zuerst prüfen. Kein Login erforderlich, wenn der Digest-Pull tatsächlich ohne Authentifizierung erfolgreich ist.

**B – Package privat:** dediziertes GitHub-Konto/Token mit minimalem `read:packages`-Zugriff und kurzer Laufzeit verwenden; kein Schreib- oder Adminrecht. Token außerhalb des Repositorys sicher im Secret-Manager halten. Interaktiv einlesen, damit es nicht als Shell-Argument erscheint:

```bash
read -rsp 'GHCR read:packages token: ' GHCR_TOKEN; echo
printf '%s' "$GHCR_TOKEN" | sudo docker login ghcr.io --username GITHUB_USERNAME --password-stdin
unset GHCR_TOKEN
```

`GITHUB_USERNAME` ist durch das Paket-Konto zu ersetzen. Niemals den Tokenwert in eine Datei im Repository schreiben oder `set -x` aktivieren. Docker speichert Login-Material standardmäßig in der Benutzerkonfiguration; auf einer produktiven VM dafür einen geschützten Credential Helper bzw. den freigegebenen lokalen Secret-Speicher verwenden. Nur das Vorhandensein von `gh auth status` ist kein Beweis für Package-Leserechte.

**Stoppunkt 1 – Abnahme Phase 1:** Ubuntu 24.04 und Architektur stimmen; Updates sind eingespielt; Adminzugriff ist getestet; Docker Engine/Compose, `gh`, Trivy, Git und jq sind vorhanden; freier Speicher reicht; GitHub/GHCR sind erreichbar; kein Produktivvolume wurde erstellt oder verändert. Ergebnisse prüfen, bevor Phase 2 beginnt.

## PHASE 2 – Image-Verifikation

**Stoppunkt 2:** Bei jedem Fehler anhalten. Nicht auf Tags, CI-Grün oder lokale Simulation ausweichen.

### I. Provenance und signierte SBOM verifizieren

Aus dem freigegebenen GitHub Actions Release den vollständigen Image-Digest kopieren und vor Ort als Variable setzen (nur Digest-Referenz, keine Secrets):

```bash
IMAGE='ghcr.io/kruemelnerd/sporttracker@sha256:<64-stellige-kleinschreibung-hex-ID>'
[[ "$IMAGE" =~ ^ghcr\.io/kruemelnerd/sporttracker@sha256:[0-9a-f]{64}$ ]] || exit 1
sudo docker pull "$IMAGE"
```

Der Pull muss mit der in H geprüften öffentlichen oder privaten Berechtigung gelingen. Danach auf der VM ausführen:

```bash
./scripts/verify-release.sh "$IMAGE"
```

Der Verifier prüft den exakten SHA256-Image-Digest, GH OIDC-Provenance und signierte SPDX-2.3-SBOM, Repository `kruemelnerd/sporttracker`, freigegebenen Workflow `.github/workflows/ci.yml`, `refs/heads/main`, Git-Commit/Source-Digest, Issuer `https://token.actions.githubusercontent.com`, konsistenten Subject-Digest und SPDX-Metadaten. Er bricht bei fehlender/abweichender Evidenz ab.

### J. Aktuellen Image-Sicherheitsscan

`verify-release.sh` führt anschließend Trivy gegen genau diesen Digest mit Vulnerability-, Misconfiguration- und Secret-Scannern aus. High/Critical (Grenze 0/0), DB-Downloadfehler, veraltete/fehlende Datenbank oder Scannerfehler blockieren die Freigabe. Kein `--ignore-unfixed`, keine Skip-Option. Scan-Ergebnis und Zeitstempel für das Deploymentprotokoll sichern, aber keine privaten Auth-Daten.

**Abnahmekriterien Phase 2:** Pull erfolgreich; Verifier auf der tatsächlichen VM erfolgreich; Provenance/SBOM Subjects identisch zum exakten Digest; zulässiger Workflow/Repository/Branch/Commit; aktueller Trivy-Scan mit 0 High und 0 Critical. Andernfalls stoppen und Fehler untersuchen.

## PHASE 3 – Produktive Konfiguration (kein Start)

**Stoppunkt 3:** Diese Phase bereitet nur Ressourcen vor. Noch kein `compose up`, keine Migration, kein FIT-Import.

### K. Docker-Volumes und Secrets vorbereiten

Zunächst nur lesend prüfen:

```bash
sudo docker volume inspect sporttracker-prod-postgres
sudo docker volume inspect sporttracker-prod-fit
```

Bei vorhandenem Volume sofort anhalten und Herkunft/Datenbestand klären. Nichts löschen, umbenennen oder neu erstellen. Nur nach bestätigter leerer Erstinstallation und separater Freigabe dürfen fehlende externe Volumes einmalig manuell erstellt werden:

```bash
sudo docker volume create sporttracker-prod-postgres
sudo docker volume create sporttracker-prod-fit
```

Secrets in einem root-/Betriebsbenutzer-geschützten Pfad außerhalb des Repositorys erstellen. Generierte zufällige, unabhängige Admin- und App-Passwörter nicht in Argumente/History schreiben. Compose-Secrets als Dateien mit Modus `0400` anlegen; PostgreSQL-Dateien müssen für Container-UID 999, App-Configtree-Dateien für UID 10001 lesbar sein. `APP_DATABASE_URL` und Username enthalten keine Passwörter. App-URL muss auf DB-Service `db`/Port 5432 zeigen. App-Passwortdatei und PostgreSQL-App-Rollenpasswort müssen übereinstimmen.

Eine geschützte `production.env` mit Modus `0600` enthält ausschließlich Dateipfade und:

```text
SPORTTRACKER_IMAGE=ghcr.io/kruemelnerd/sporttracker
SPORTTRACKER_IMAGE_DIGEST=sha256:<freigegebener-digest>
POSTGRES_ADMIN_PASSWORD_FILE=<absoluter-pfad>
POSTGRES_APP_PASSWORD_FILE=<absoluter-pfad>
APP_DATABASE_URL_FILE=<absoluter-pfad>
APP_DATABASE_USERNAME_FILE=<absoluter-pfad>
APP_DATABASE_PASSWORD_FILE=<absoluter-pfad>
```

Kein Passwort als Env-Wert. Die Deployment-Datei bewahrt vor Digest-Änderung die vorherige Konfiguration mit restriktiven Rechten.

### L. Netzwerk und Reverse Proxy vorbereiten

Einen Host-Reverse-Proxy aus einer offiziellen Ubuntu-Paketquelle installieren (z. B. Nginx). Vor Ort eine tatsächlich gültige private VM-Adresse, DNS-Namen und LAN-CIDR ermitteln; keine Platzhalterwerte produktiv übernehmen. Proxy nur an die vorgesehene LAN-Schnittstelle binden und upstream ausschließlich `http://127.0.0.1:8080` verwenden. Keine Weiterleitung für PostgreSQL.

UFW: SSH nur aus dem Admin-LAN; erforderliche Proxy-Ports (normalerweise TCP 443, optional 80) nur aus dem privaten Client-LAN erlauben; eingehend sonst standardmäßig sperren. Regeln vor `ufw enable` prüfen und zweiten SSH-Zugang offen halten. Keine Proxmox-Firewall-, Router- oder Portforwarding-Änderung durch diese Anleitung. TLS nur dann als sicher ausweisen, wenn Clients der internen CA vertrauen; deren Schlüssel/Zertifikate separat schützen.

### Backupziel vorbereiten

Geschütztes, zugriffsbeschränktes und verschlüsseltes Ziel außerhalb der VM bereitstellen (z. B. separates Backup-System). Das Backupskript verschlüsselt selbst nicht. Vor echten FIT-/Trainingsdaten: Backup erstellen, Prüfsummen prüfen, externe Übertragung nachweisen und Restore in einem neuen isolierten Projekt/neuen Volumes erfolgreich testen. Niemals in Produktionsvolumes restaurieren.

**Abnahmekriterien Phase 3:** Volumes eindeutig als fehlend oder bestehend dokumentiert; Secrets außerhalb Git mit geprüften Rechten; Compose-Konfiguration validiert; LAN-CIDR/IP/DNS lokal bestimmt; Firewall-/Proxy-Regeln lokal geprüft; externes Backupziel vorhanden und Zugriff eingeschränkt. Kein Container gestartet, keine Produktivdaten verwendet.

## PHASE 4 – Erstinstallation (nur nach ausdrücklicher Freigabe)

**Freigabepunkt:** Erst fortfahren, nachdem Phase 1–3 dokumentiert abgenommen und die erstmalige produktive Initialisierung ausdrücklich autorisiert wurde. Bei vorhandenen Volumes nicht als Erstinstallation fortfahren.

### M. Kontrolliert starten

Auf der VM den geprüften Digest aus Phase 2 erneut einsetzen. Aufruf aus Repository-Verzeichnis; das Skript verweigert fehlende externe Volumes, ungültige Secrets/Compose-Konfiguration und jede fehlgeschlagene Releaseprüfung:

```bash
sudo ./scripts/deploy-verified.sh \
  --env-file /secure/path/production.env \
  --project sporttracker-prod \
  "$IMAGE"
```

Der Verifier muss unmittelbar auf der Ziel-VM laufen. Der Deployment-Runner persistiert nach der Prüfung den neuen Digest atomar in der angegebenen Compose-Umgebung und bewahrt die vorige Datei als `production.env.previous.<UTC-Zeit>`. Ein normaler Compose-Neustart verwendet damit denselben freigegebenen Digest.

### N. Health- und Readiness-Prüfung

```bash
sudo docker compose --env-file /secure/path/production.env -p sporttracker-prod -f compose.production.yaml ps
curl --fail --silent --show-error http://127.0.0.1:8080/api/health
curl --fail --silent --show-error http://127.0.0.1:8080/api/ready
```

Danach Proxyzugriff von einem LAN-Client testen. In `ps` müssen DB und App gesund sein; API Health/Readiness müssen erfolgreich sein; Datenbankport darf nicht am Host veröffentlicht sein; App-Port bleibt Loopback-only. Secrets nicht mit `config`, `inspect`, `env` oder Logs ausgeben.

**Migration-/Rollback-Grenze:** Das Deployment kann bei Readiness-Fehlern durch Flyway bereits veränderte Datenbankstrukturen hinterlassen. Der Runner führt deshalb absichtlich keinen automatischen Image-Rollback aus und meldet keinen Erfolg. Volumes/Container nicht löschen und nicht blind auf einen alten App-Digest umstellen. Zustand sichern, Logs ohne Secrets prüfen, Schema-/Migrationsstand erfassen und manuelle Wiederherstellung aus einem verifizierten Backup in neue isolierte Volumes planen. Es gibt keine automatische Flyway-Rückmigration; ein Container-Rollback ist keine Datenbankwiederherstellung. Auch ein unvollständiger Containerstart ist kein erfolgreicher Rollback.

**Abnahmekriterien Phase 4:** Auf der VM lief die vollständige Release-Verifikation erfolgreich; der Digest ist persistent gespeichert und die vorherige Compose-Datei geschützt erhalten; App/DB sind gesund; lokale Health-/Readiness- und LAN-Proxy-Prüfung sind erfolgreich; Backup liegt extern und ein isolierter Restore wurde geprüft. Erst danach echte Trainingsdaten übernehmen.

### O. Backup und Wiederherstellung

Nach erfolgreicher Erstinstallation zuerst Backup auf externes Ziel erstellen:

```bash
sudo ./scripts/backup.sh \
  --docker-context default \
  --project sporttracker-prod \
  --compose-file compose.production.yaml \
  --env-file /secure/path/production.env \
  --output-dir /secure/offsite-mount/sporttracker
```

`backup.sh` stoppt die App für einen konsistenten Snapshot und startet sie wieder; der Befehl meldet nur bei erfolgreichem Backup und erfolgreichem Readiness-Wiederanlauf Erfolg. Backup-Dateien enthalten Trainings-/FIT-Daten: Zugriff schützen und verschlüsselte externe Übertragung verifizieren.

Restore ausschließlich nach `docs/containerization.md` mit `scripts/restore.sh` in ein neues, leeres, isoliertes Compose-Projekt. Vor App-Start Hashes, Aktivitäts-/Trainingsdatensätze und Flyway-Stand vergleichen. Restore-Fehlerressourcen bleiben zur Untersuchung erhalten. Kein Restore in `sporttracker-prod-postgres` oder `sporttracker-prod-fit`.

## Lokal vorbereiteter VM-Preflight

Vor Phase 2 auf der VM, aus dem Repository, ohne `sudo` falls Docker-Berechtigungen reichen:

```bash
./scripts/proxmox-preflight.sh
```

Das Skript führt ausschließlich lesende Host-/Netzwerkprüfungen aus. Es startet/stoppt keine Container, erstellt keine Volumes und ändert keine Netzwerke oder Secrets. Netzwerkprüfungen prüfen lediglich GitHub-HTTPS und GHCR-Endpunkt; sie ersetzen keinen Digest-Pull.

## Was diese Anleitung nicht erledigt

Sie erstellt weder die VM noch setzt sie Adressen, Proxmox-Firewall, Routerregeln, Proxy-DNS oder lokale CA. Sie erzeugt keine produktiven Volumes/Secrets automatisch und importiert keine FIT-Dateien. Keine lokale Prüfung gilt als erfolgreiche Produktionsinstallation.
