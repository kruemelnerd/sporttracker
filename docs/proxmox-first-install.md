# Linux, VM und Proxmox-LXC: erste Installation vorbereiten

**Setup ist kein Deployment.** Phase 1 installiert ausschließlich die Betriebswerkzeuge. Keine Proxmox-Host-Konfiguration, IP, DNS-Adresse oder LAN-CIDR wird automatisch verändert. Bis zur ausdrücklichen Freigabe für Phase 4 werden keine Produktivcontainer gestartet und keine echten Trainingsdaten übernommen.

## Ziel und Regeln

- Ubuntu oder Debian, Server/Desktop, physischer Rechner, VM oder unprivilegierter Proxmox-LXC; aktuelle Unterstützung wird gegen offizielle signierte Paketquellen geprüft. Keine fest eingebaute Releaseversion. Derzeit veröffentlichtes Sporttracker-Image: nur `linux/amd64`.
- Zielumgebung des Betreibers: Proxmox mit insgesamt 8 GB RAM, unprivilegierter LXC 116 mit Ubuntu 26.04.1, 2 GiB RAM + 2 GiB Swap, 4 vCPU, 40 GB, Nesting/Keyctl aktiviert. Docker 29.9.2/Compose 5.6.1, overlayfs und cgroups v2 funktionieren bereits und werden im normalen Setup nicht aktualisiert oder neu gestartet.
- 8 GiB RAM sind **keine harte Mindestanforderung**. Für mehr Reserve sind ca. 4 GiB RAM, 2–4 vCPU und 40 GB ein sinnvoller Planungswert; Daten und externe Backups separat kalkulieren. Bei 2 GiB RAM können die bestehenden App-/DB-Limits zusammen bereits den gesamten RAM beanspruchen. Swap hilft gegen Lastspitzen, ersetzt aber keine Kapazitätsplanung. Trivy und seine Datenbanken benötigen zusätzliche Reserven; bei OOM/Scanfehlern stoppen, nie den Scan deaktivieren.
- LAN-only. PostgreSQL erhält keinen Host-Port; die App bleibt an `127.0.0.1:8080`. Reverse Proxy ist ein Dienst im Zielsystem, nicht auf dem Proxmox-VE-Host.
- Nur offizielle Ubuntu-/Debian-, Docker-, GitHub CLI- und Aqua Security (Trivy)-Paketquellen. Keine `curl | bash`-Installer.
- Image immer per vollständigem SHA256-Digest bereitstellen und auf der VM erneut prüfen. CI-Erfolg allein reicht nicht.
- Niemals vorhandene Volumes `sporttracker-prod-postgres` oder `sporttracker-prod-fit` löschen, ersetzen oder diagnostisch beschreiben.
- Keine Secrets in Git, Shell-History, Prozessargumente, Tickets oder Logs übernehmen.

## PHASE 1 – Linux-Gast oder Rechner vorbereiten

**Stoppunkt 1:** Erst fortfahren, wenn die Abnahmekriterien am Ende von Phase 1 geprüft und vom Betreiber bestätigt wurden.

### A. Ziel und Adminzugang manuell prüfen

Einen persönlichen Administrator und ein unterstütztes Ubuntu-/Debian-System bereitstellen. Installationsmedien nur offiziell beziehen und deren Prüfsummen/Signaturen unabhängig prüfen. Adressen, DNS und LAN-Zugriff vor Ort festlegen; keine Router-Portweiterleitung. Auf einem normalen Linux-PC ist keine Proxmox-Konfiguration nötig. Auf einem **Proxmox-VE-Host selbst niemals ausführen**; das Skript verweigert diesen Fall.

Bei einem bestehenden LXC wie 116 nichts neu erstellen und nichts umprivilegieren. Falls Docker scheitert, Nesting/Keyctl und die konkreten Kernel-/cgroup-/AppArmor-/Seccomp-Meldungen am Proxmox-Host durch den Betreiber prüfen lassen. Keine pauschale Deaktivierung von Sicherheitsprofilen und keine automatischen Änderungen an LXC, Firewall oder Storage. Ein `-pve`-Kernel im LXC allein bedeutet nicht, dass das Skript auf dem Host läuft.

OS-Sicherheitsupdates, SSH-Härtung und Firewalländerungen sind eigene geplante Wartungsarbeiten, nicht Teil des Installers. Vor Änderungen am Zugang immer eine zweite funktionierende Sitzung sicherstellen; der Installer ändert weder SSH noch UFW.

### B. Einzeldatei herunterladen, prüfen und installieren

```bash
wget -O sporttracker-setup.sh https://raw.githubusercontent.com/kruemelnerd/sporttracker/main/scripts/install-host-tools.sh
sudo bash sporttracker-setup.sh
```

**Vor `sudo bash` die Datei lesen.** `main` ist veränderlich. Optional eine geprüfte vollständige Commit-ID statt `main` verwenden und zusätzlich einen über einen unabhängigen vertrauenswürdigen Kanal erhaltenen SHA256-Wert vergleichen:

```bash
printf '%s  %s\n' "$TRUSTED_SHA256" sporttracker-setup.sh | sha256sum --check --strict
```

`TRUSTED_SHA256` muss vorher aus diesem unabhängigen Kanal bekannt sein; ein daneben heruntergeladener Hash allein beweist keine Authentizität. Keine fremden Installationsskripte mit `curl | bash` ausführen.

Der Installer zeigt Bestand und Paketplan und verlangt eine ausdrückliche Bestätigung. Er installiert nur fehlende/ungeeignete Werkzeuge, klont bei Fehlen `/opt/sporttracker` über HTTPS und verwendet für die gemeinsame Prüfung seine eigene geprüfte Logik. Skripte aus einem vorhandenen Repository werden nicht automatisch als Root ausgeführt. Ein vorhandenes Repository wird weder gelöscht noch gepullt, zurückgesetzt oder bereinigt; erforderliche Aktualisierungen nach Prüfung lokaler Änderungen bewusst manuell vornehmen.

### C. Wiederholen, prüfen und aktualisieren

```bash
bash sporttracker-setup.sh --check
sudo bash sporttracker-setup.sh --upgrade
```

`--check` ist ausschließlich lesend, einschließlich Paketquellenprüfung: kein APT-Update, keine Cache-/Keyring-Dateien, kein Clone und kein Containerstart. Fehlende Werkzeuge oder Docker-Socket-Rechte sind Fehler, keine Erfolgsmeldung. Für die Docker-Prüfung gegebenenfalls bewusst `sudo bash sporttracker-setup.sh --check` verwenden; keine gewöhnlichen Benutzer zur Docker-Gruppe hinzufügen.

Bei vorhandenen, aber inaktiven systemd-Diensten für Docker/containerd wird kein Socket kontaktiert: selbst `docker info` könnte sonst über Socket-Aktivierung den Dienst und dessen alte Container starten. Docker-Snap wird anhand lokaler Dateien erkannt, ohne dafür snapd zu aktivieren.

Der normale erneute Aufruf erhält funktionierende Versionen, auch neuere Hauptversionen wie Compose 5. `--upgrade` aktualisiert nur verwaltete Werkzeuge, niemals das OS-Release. Sobald Docker/containerd ersetzt werden müsste, bricht es mit Wartungshinweis ab; auch ohne laufende Container wäre ein Dienstneustart durch Paketskripte nicht ausgeschlossen. Keine automatische Deinstallation von `docker.io`, Docker Desktop/Snap oder Podman-Konfliktpaketen.

Docker selbst richtet bei der Erstinstallation Netzwerk-/Firewallregeln ein; veröffentlichte Ports können UFW umgehen. Der Installer öffnet keine Sporttracker-Ports und startet keinen Smoke-/Produktivcontainer.

### D. Paketquellen und Vertrauensanker

- Ubuntu/Debian: offizielle Distributionsarchive einschließlich Security/Updates, mit vorhandenem Distributions-Keyring. Bestehende HTTP-Standardquellen werden nicht umgeschrieben; Setup nutzt eigene temporäre HTTPS-Quellen für seine Transaktion.
- Docker: `https://download.docker.com/linux/ubuntu` bzw. `/debian`, tatsächlicher Codename, Komponente `stable`. Primary-Fingerprint `9DC858229FC7DD38854AE2D88D81803C0EBFCD88`, dokumentiert in der [offiziellen Docker-Dokumentationshistorie](https://github.com/docker/docker.github.io/issues/3457); aktuelle [Ubuntu-](https://docs.docker.com/engine/install/ubuntu/) und [Debian-Anleitung](https://docs.docker.com/engine/install/debian/).
- GitHub CLI: `https://cli.github.com/packages`, `stable main`. Erlaubte Primary-Fingerprints `2C6106201985B60E6C7AC87323F3D4EA75716059` und `7F38BBB59D064DBCB3D84D725612B36462313325` laut [offizieller Installationsdokumentation](https://github.com/cli/cli/blob/trunk/docs/install_linux.md). Der Keyring enthält auch den alten, inzwischen abgelaufenen Schlüssel; gültige Repository-Signaturen bleiben Pflicht.
- Trivy: `https://aquasecurity.github.io/trivy-repo/deb`, `generic main`; offizieller Nachfolger/Alias `https://get.trivy.dev/deb`. Ausschließlich neuer Primary-Fingerprint `825AD9036F7C850E6A6FED4935B8ACA44FD9CA9F` gemäß [Hersteller-Mitteilung zur Rotation vom April 2026](https://github.com/aquasecurity/trivy/discussions/10549), nicht der ersetzte Vorgänger.

Versionen kommen dynamisch aus signierten stabilen Paketquellen. Fingerprints sind bewusst feste Vertrauensanker, keine Versionspins: unbekannte Schlüsselrotation blockiert, bis sie unabhängig geprüft und im Installer gepflegt wurde. Eigene Keyrings mit `Signed-By`; Download-, Signatur-, Hash- oder APT-Fehler brechen ab. Vorhandene `.list`/Deb822-Quellen werden erkannt, nicht unkontrolliert überschrieben. Keine fremden PPAs in Setup-Transaktionen. Bestehende Docker-Pakete und nicht angeforderte installierte Abhängigkeiten werden zusätzlich gegen indirekte Upgrades geschützt.

Auch ein vorhandener Keyring muss die aktuelle Repository-Signatur verifizieren können. Unvollständiger dpkg-Zustand, zurückgebliebene Docker-Daten ohne CLI oder widersprüchliche Quellen verlangen manuelle Wartung statt automatischer Reparatur. Setup-Transaktionen laden bewusst keine geerbten APT-Hooks, Zugangsdaten oder unsicheren TLS-Sonderregeln. Für HTTPS muss bereits ein vertrauenswürdiger CA-Speicher nutzbar sein; ohne jeden CA-Vertrauensanker ist ein sicherer automatischer Download nicht möglich. Dann CA-Vertrauen zuerst über vertrauenswürdige Distributionsmedien herstellen, niemals Zertifikatsprüfungen abschalten.

GitHub CLI muss sämtliche Verifier-Flags einschließlich `--deny-self-hosted-runners` und JSON unterstützen. Ein erfolgreiches `trivy --version` ist **kein** erfolgreicher Image-Scan. Für Release-Freigabe bleibt der vollständige Scan in Phase 2 verpflichtend.

### G. Betriebsrepository prüfen

```bash
cd /opt/sporttracker
sudo bash scripts/proxmox-preflight.sh
```

Der historische Preflight-Name bleibt für bestehende Aufrufe erhalten; er nutzt denselben ausschließlich lesenden Check des Installers. Vor dem späteren Betrieb Branch/Commit mit dem freigegebenen Release abgleichen. Dateien `compose.production.yaml`, `scripts/`, `docker/postgres/` und `docs/` nicht durch Entwicklungsvarianten ersetzen.

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

**Stoppunkt 1 – Abnahme Phase 1:** Ubuntu/Debian und Architektur werden von signierten offiziellen Quellen unterstützt; Adminzugriff ist getestet; Docker Engine/CLI, Compose, Buildx, containerd, `gh` mit allen Verifier-Optionen, Trivy und Basiswerkzeuge sind funktionsfähig; Speicher-/LXC-Warnungen sind betrieblich geklärt; tatsächlichen GitHub/GHCR-Zugriff separat prüfen. Kein Produktivvolume wurde erstellt oder verändert, kein Release als verifiziert gemeldet. Erst danach Phase 2.

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

Einen Reverse Proxy im Zielsystem aus einer offiziellen Ubuntu-/Debian-Paketquelle installieren (z. B. Nginx), als separaten bewussten Betriebsschritt. Vor Ort eine tatsächlich gültige private Adresse, DNS-Namen und LAN-CIDR ermitteln; keine Platzhalterwerte produktiv übernehmen. Proxy nur an die vorgesehene LAN-Schnittstelle binden und upstream ausschließlich `http://127.0.0.1:8080` verwenden. Keine Weiterleitung für PostgreSQL und kein Dienst auf dem Proxmox-VE-Host.

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

## Gemeinsamer Linux-/VM-/LXC-Preflight

Vor Phase 2 auf dem Zielsystem, aus dem Repository, ohne `sudo` falls Docker- und Repository-Berechtigungen reichen:

```bash
./scripts/proxmox-preflight.sh
```

Das Skript führt ausschließlich lesende Host-/Netzwerkprüfungen aus. Es startet/stoppt keine Container, erstellt keine Volumes und ändert keine Netzwerke oder Secrets. Netzwerkprüfungen prüfen lediglich GitHub-HTTPS und GHCR-Endpunkt; sie ersetzen keinen Digest-Pull.

## Was diese Anleitung nicht erledigt

Sie erstellt weder die VM noch setzt sie Adressen, Proxmox-Firewall, Routerregeln, Proxy-DNS oder lokale CA. Sie erzeugt keine produktiven Volumes/Secrets automatisch und importiert keine FIT-Dateien. Keine lokale Prüfung gilt als erfolgreiche Produktionsinstallation.
