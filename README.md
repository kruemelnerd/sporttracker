# Running Dashboard

Lokale Webanwendung zur Verwaltung und Auswertung eigener Lauftrainings aus Garmin-/FIT-Dateien.

### Einfache Installation unter Linux

Für den **Containerbetrieb** auf Ubuntu oder Debian (Server/Desktop, normaler Linux-PC, VM oder unprivilegierter Proxmox-LXC):

**Vor der Root-Ausführung die heruntergeladene Datei lesen.** Die URL lädt den veränderlichen Hauptbranch, keine unveränderliche signierte Veröffentlichung. Für zusätzliche Integrität einen unabhängig vertrauenswürdig erhaltenen SHA256-Wert vergleichen oder eine zuvor geprüfte vollständige Commit-ID statt `main` in der URL verwenden. Ein Hash von derselben Downloadquelle allein schützt nicht vor deren Manipulation.

```bash
wget -O sporttracker-setup.sh https://raw.githubusercontent.com/kruemelnerd/sporttracker/main/scripts/install-host-tools.sh
sudo bash sporttracker-setup.sh
```

Das einzelne Skript benötigt keinen vorherigen Git-Clone. Nach einer Bestandsaufnahme und ausdrücklicher Bestätigung installiert es fehlende Systemwerkzeuge, Docker Engine/CLI, containerd, Compose und Buildx sowie GitHub CLI und Trivy aus offiziellen signierten HTTPS-Paketquellen. `/opt/sporttracker` wird nur bei Fehlen geklont; bestehende Repositories und lokale Änderungen werden nicht aktualisiert oder überschrieben. Ein Proxmox-VE-Host wird ausdrücklich abgelehnt. Betriebssystemversion und Architektur werden gegen echte signierte Paketmetadaten geprüft, nicht gegen eine fest eingebaute Versionsliste.

```bash
bash sporttracker-setup.sh --check          # ausschließlich lesend, keine Installation
sudo bash sporttracker-setup.sh --upgrade  # bewusste Werkzeugupdates nach Bestätigung
bash sporttracker-setup.sh --help
```

Erneute normale Ausführung erhält funktionierende Installationen, insbesondere Docker. `--upgrade` führt kein OS-Release-Upgrade aus und verlangt bei einem anstehenden Docker-/containerd-Austausch manuelle Wartung statt eines automatischen Daemon-/Container-Neustarts. Paketkonflikte werden nicht automatisch deinstalliert. Ohne Docker-Socket-Berechtigung kann `--check` den Daemon nicht prüfen und meldet einen Fehler; das Skript fügt niemanden zur Docker-Gruppe hinzu.

**Setup ist kein Deployment:** keine produktiven Volumes, Datenbankinitialisierung, Secrets, GHCR-Anmeldung oder Containerstarts. Für die Erstinstallation gilt die verlinkte Anleitung; reguläre Updates erfolgen ausschließlich über `scripts/update-production.sh` mit Backup und Release-Verifikation. Derzeit wird das Sporttracker-Image nur für `linux/amd64` veröffentlicht; Werkzeuginstallation auf einer anderen unterstützten Architektur ist keine Image-Freigabe. Java/Node sind für diesen Betriebsweg nicht nötig.

Auch kleine Systeme wie LXC mit 2 GiB RAM + 2 GiB Swap, 4 vCPU und 40 GB werden berücksichtigt; Ressourcenwarnungen und mögliche Trivy-Speicherengpässe dürfen niemals zum Überspringen von Sicherheitsprüfungen führen. Details und die weiteren Freigabeschritte: [Linux-Erstinstallation](docs/proxmox-first-install.md).

## Manuelles Produktionsupdate mit Pflicht-Backup

Bei laufender Produktion erfolgt ein Versionswechsel ausschließlich nach erfolgreicher lokaler Sicherung der PostgreSQL-Datenbank und der FIT-Dateien. Das Script `scripts/update-production.sh` prüft die aktuell laufende, gesunde Version und erzeugt über `scripts/backup.sh` ein neues Backup. **Erst nach dessen erfolgreicher Prüfung** wird das bereits vorhandene `scripts/deploy-verified.sh` aufgerufen. Bei Backupfehlern findet kein Deployment statt. Releases müssen weiterhin aus erfolgreichem GitHub Actions und mit vollständigem SHA256-Digest stammen.

Auf dem bereits eingerichteten Sporttracker-LXC mit dem produktiven Compose-Projekt:

### 1. Repository aktualisieren

```bash
cd /opt/sporttracker
git status --short
git pull --ff-only
```

Vorhandene lokale Änderungen prüfen und erhalten; keinen automatischen Reset durchführen. Bei einem fehlgeschlagenen Pull zuerst die Ursache manuell klären.

### 2. Neues Release installieren

```bash
bash scripts/update-production.sh \
  --env-file /etc/sporttracker/production.env \
  --project sporttracker-prod \
  --backup-dir /var/backups/sporttracker \
  'ghcr.io/kruemelnerd/sporttracker@sha256:<NEUER_DIGEST>'
```

`<NEUER_DIGEST>` durch den vollständigen SHA256-Digest (64 kleine Hexadezimalzeichen) eines erfolgreich geprüften GitHub-Actions-Releases ersetzen; niemals Tags verwenden. Die Image-Verifikation prüft Provenance, SBOM und Schwachstellen. Das Backup-Verzeichnis muss vorher erstellt sein und nur dem ausführenden Benutzer zugänglich sein (`install -d -m 700 /var/backups/sporttracker`); auch die private Umgebungsdatei muss ihm gehören. Die laufende Anwendung wird während des Backups kurz gestoppt und danach wieder gestartet. Bei gleichem **laufendem und konfiguriertem** Digest wird weder ein Backup noch ein Deployment ausgeführt. Parallel laufende Aufrufe dieses Wrappers werden gesperrt.

Die produktiven PostgreSQL- und FIT-Volumes bleiben erhalten. Niemals produktive Volumes löschen oder die Datenbank zurücksetzen. Bei fehlgeschlagener Flyway-Migration gibt es keinen automatischen Rollback; Zustand erhalten und eine [manuelle Wiederherstellung](docs/containerization.md#wiederherstellung) planen. Die Backups liegen derzeit **nur lokal**; automatische Rotation oder externe Sicherung sind nicht eingerichtet. Restore-Tests und geschützte externe Kopien bleiben empfohlen. Weder Renovate noch GitHub Actions deployen automatisch.

### 3. Ergebnis kontrollieren

```bash
docker compose --env-file /etc/sporttracker/production.env -p sporttracker-prod -f compose.production.yaml ps
```

Die Healthchecks von App und Datenbank müssen `healthy` anzeigen. Anschließend [https://sporttracker.philippveit.de](https://sporttracker.philippveit.de) aufrufen und die Anwendung prüfen. Bei Fehlern Container-Logs lokal kontrollieren, ohne Secrets auszugeben oder weiterzugeben:

```bash
docker compose --env-file /etc/sporttracker/production.env -p sporttracker-prod -f compose.production.yaml logs --since=15m app db
```

Logs können sensible Inhalte enthalten; vor Weitergabe sorgfältig prüfen und bereinigen. Weitere Hinweise zur [Fehlerbehandlung](docs/proxmox-deployment.md#fehlerbehandlung-und-offene-punkte).

Isolierte Wrapper-Tests (ohne Docker-Zugriff): `bash tests/security/update-production-test.sh`.

## GHCR-Metadatenclient

`GhcrRegistryClient.fetchStable()` liest ausschließlich Metadaten von `ghcr.io/kruemelnerd/sporttracker:stable`. Der vollständige SHA256-Digest entsteht aus den unveränderten Bytes des obersten Registry-Manifests, genau wie in CI. Ein vorhandener `Docker-Content-Digest` muss damit übereinstimmen. Bei OCI-Index bzw. Docker-Manifest-Liste bleibt der Index-Digest die Identität; für den optionalen Commit wird nur die eindeutige `linux/amd64`-Variante der bestehenden CI berücksichtigt. Der Commit stammt aus dem Label `org.opencontainers.image.revision` ihrer per Digest geprüften Image-Konfiguration. Fehlende oder ungültige Commit-Labels ergeben `null`.

Es werden keine Image-Layer geladen und kein Docker-Daemon verwendet. Jede Metadatenantwort ist auf 1 MiB begrenzt. Die positiven Spring-Konfigurationswerte `running-dashboard.registry.connect-timeout` (Standard `PT3S`) und `running-dashboard.registry.read-timeout` (Standard `PT5S`) begrenzen Verbindungsaufbau und Antwortlesen. Eine Abfrage erfolgt erst beim Methodenaufruf, nicht beim Anwendungsstart, Healthcheck oder Readiness-Check.

Der Client versucht zuerst anonymen Zugriff und beantwortet eine GHCR-Bearer-Challenge mit einem anonym angeforderten Token ausschließlich für `repository:kruemelnerd/sporttracker:pull`. Tokens bleiben innerhalb des Aufrufs und werden weder geloggt noch zurückgegeben. Fremde Authentifizierungs-URLs und Schreibberechtigungen werden abgelehnt. Nur für die Image-Konfiguration ist genau ein HTTP-307-Verweis auf den passenden HTTPS-Blob unter `pkg-containers.githubusercontent.com` erlaubt, ohne Weitergabe des Registry-Tokens und mit anschließender Digest-Prüfung; alle anderen Redirects bleiben gesperrt. Private oder anderweitig gesperrte Pakete werden kontrolliert als `AUTHENTICATION_REQUIRED` bzw. `ACCESS_DENIED` gemeldet; aus der Sichtbarkeit des Git-Repositories wird keine Paketberechtigung abgeleitet. Persönliche Zugangsdaten unterstützt dieser Baustein nicht.

Weitere bereinigte Fehlerwerte sind `NOT_FOUND`, `RATE_LIMITED`, `REGISTRY_UNAVAILABLE`, `TIMEOUT`, `NETWORK_ERROR` und `INVALID_RESPONSE`; Exceptions enthalten keine Registry-Antworttexte oder ursprünglichen Exceptions. Der Versionsservice behandelt diese Fehler und liefert sie über die bestehende [Versionsstatus-API und Footer-Anzeige](#versionsanzeige-im-pacebase-footer) aus. Die Metadaten sind **keine kryptografische Release-Freigabe**; Updates bleiben ausschließlich beim [manuellen Prozess mit Pflicht-Backup](#manuelles-produktionsupdate-mit-pflicht-backup) über `scripts/update-production.sh`.

## Voraussetzungen für die lokale Entwicklung

- Java 25
- Node.js 22.12 oder neuer und npm
- Docker mit Docker Compose

## Lokaler Start

Optional `.env.example` nach `.env` kopieren; eine vorhandene `.env` nicht überschreiben. Die voreingestellten Zugangsdaten und `DATABASE_URL` zeigen auf die lokale Entwicklungsdatenbank `running_dashboard` auf Port `5432` und sind nur für die lokale Entwicklung gedacht. Für den Start mit `./dev.sh` die Integrationen in `.env` festlegen:

```dotenv
GARMIN_PERSONAL_API_ENABLED=true
INTERVALS_ICU_ENABLED=false
```

Wenn Garmin deaktiviert ist, wird keine Garmin-Key-Datei benötigt. Der Garmin-Key gehört **nicht** in `.env`: einmalig lokal in `~/.config/sporttracker/garmin-poc.env` hinterlegen, zum Beispiel:

```bash
install -d -m 700 ~/.config/sporttracker
umask 077
nano ~/.config/sporttracker/garmin-poc.env
```

In der Datei steht die Shell-Zuweisung `GARMIN_PERSONAL_API_KEY='…'`; Dateirechte auf `600` setzen (`chmod 600 ~/.config/sporttracker/garmin-poc.env`).

`dev.sh` lädt den Key nur, wenn Garmin aktiviert ist, und reicht ihn ausschließlich an den Backend-Start weiter. Ein bereits gesetztes `GARMIN_PERSONAL_API_KEY` hat Vorrang.

Die gesamte lokale Anwendung startet dann gemeinsam:

```bash
./dev.sh
```

Mit `Ctrl-C` werden Backend und Frontend beendet; PostgreSQL bleibt gestartet und die Daten bleiben erhalten.

Der Garmin-Importstatus lässt sich anschließend in der Importansicht prüfen. **Achtung:** Bei aktiviertem Garmin kann der Sync bereits beim Anwendungsstart automatisch anlaufen (ApplicationReady-Trigger), danach periodisch weiterlaufen. Vor einem Live-Test prüfen, dass `DATABASE_URL` auf die lokale Entwicklungsdatenbank `running_dashboard` zeigt; niemals dafür eine produktive Datenbank verwenden.

## Containerbetrieb

Für den produktiven Docker-Compose-Betrieb, Secrets, persistente Volumes sowie Backup und Restore siehe [docs/containerization.md](docs/containerization.md). Die schrittweise Linux-/VM-/LXC-Erstinstallation ist in [docs/proxmox-first-install.md](docs/proxmox-first-install.md) beschrieben; [docs/proxmox-deployment.md](docs/proxmox-deployment.md) ergänzt Architektur und verifizierten Deployment-Ablauf. Der lokale Entwicklungsstart über `./dev.sh` bleibt davon unabhängig.

## Garmin Connect Personal API

Garmin ist die primäre, optionale FIT-Datenquelle. Der Import verwendet ausschließlich die Garmin-Aktivitätssuche und das Original-FIT aus dem ZIP; Kennzahlen kommen weiterhin aus dem vorhandenen FIT-Parser. Garmin-JSON-Enrichment folgt erst in M9.2.2. Bereits vorhandene Läufe werden nicht überschrieben; Änderungen oder Löschungen auf Garmin entfernen keine lokalen Aktivitäten. Intervals.icu bleibt verfügbar, wird aber nicht parallel automatisch synchronisiert.

1. Im Garmin Connect Personal API Bereich einen Personal API Key mit gewünschter Laufzeit erstellen.
2. Den Key lokal außerhalb des Repositorys sicher ablegen, z. B. in `~/.config/sporttracker/garmin-personal-api-key` mit Dateirechten `0600`. Nicht in Git, Compose-Umgebungsdateien, Shell-Historie oder Build-Argumente schreiben.
3. Für lokale Entwicklung den Key nur dem Backend-Prozess übergeben und Garmin aktivieren:

   ```bash
   cd backend
   GARMIN_PERSONAL_API_ENABLED=true GARMIN_PERSONAL_API_KEY="$(<~/.config/sporttracker/garmin-personal-api-key)" ./gradlew bootRun
   ```

   Ohne `GARMIN_PERSONAL_API_ENABLED=true` bleibt Garmin deaktiviert. Standardmäßig sucht der Sync höchstens 20 Seiten à 50 Aktivitäten; Fensterinitialisierung: 90 Tage, anschließend 14 Tage überlappend. Interval, Seiten-/Response-Limits und Timeouts sind über `GARMIN_PERSONAL_API_*` konfigurierbar.
4. Manuellen Lauf über **Import → Garmin Connect → Jetzt synchronisieren** starten oder `POST /api/imports/garmin/sync` aufrufen. `GET /api/imports/garmin/status` zeigt Konfiguration, bestätigte Verbindung, Laufstatus, letzte Ergebnisse und Rate-Limit-Sperre; weder API-Key noch Token werden zurückgegeben.
5. Automatische Synchronisierung läuft nach aktiviertem Start und danach standardmäßig alle 30 Minuten. Ein fehlgeschlagenes/unvollständiges Fenster wird nicht als erfolgreich fortgeschrieben und bei späterem Lauf erneut geprüft.

### Garmin im Docker-Compose-Betrieb

Das Basis-Compose bleibt unverändert und Garmin deaktiviert. Für eine spätere, manuelle Aktivierung (nicht Teil dieses Changes und kein Deployment) die Key-Datei außerhalb des Repositorys bereitstellen, mit den für den App-Container passenden restriktiven Rechten, und zusätzlich `compose.production.garmin.yaml` einbinden:

```bash
export GARMIN_PERSONAL_API_KEY_FILE=/secure/path/garmin-personal-api-key
docker compose -f compose.production.yaml -f compose.production.garmin.yaml config --quiet
```

Der Override reicht den Key ausschließlich als Docker-Compose-Secret mit dem Namen `GARMIN_PERSONAL_API_KEY` ein; Spring Boot liest die Datei über den vorhandenen `configtree:/run/secrets/`-Import. Er aktiviert Intervals.icu nicht. Compose-Konfiguration prüfen, aber Garmin erst nach bewusster späterer Betriebsfreigabe starten. Der reguläre Updateprozess wird dadurch nicht geändert.

### Fehlerbehandlung und Einschränkungen

Ungültige/abgelaufene Keys, Zugriffssperren, fehlende Dateien, fehlerhafte Antworten, Netzwerkfehler, zu große Antworten/ZIPs und beschädigte FITs werden mit bereinigten Fehlercodes angezeigt. HTTP 429 beachtet `Retry-After` (begrenzt) und pausiert weitere Anfragen. Garmin-Quoten, historische Verfügbarkeit und Änderungen/Löschungen sind nicht verlässlich dokumentiert. Mehrere FIT-Dateien im ZIP oder mehrdeutige lokale Duplikate werden nicht automatisch ausgewählt/verknüpft.

Bestehende Werte werden nicht durch Garmin-JSON ersetzt; insbesondere Bewegungsdauer und Kadenz stammen weiter aus FIT. M9.2.1 führt keine automatische Aktualisierung, produktive Aktivierung oder Bereitstellung aus.

## Intervals.icu

1. Intervals.icu öffnen und **Settings → Developer Settings** aufrufen.
2. Einen persönlichen API-Key erstellen.
3. Den Key als Backend-Environment-Variable setzen und den automatischen Import aktivieren:

   ```bash
   export INTERVALS_ICU_API_KEY=<your-api-key>
   export INTERVALS_ICU_ENABLED=true
   ```

4. Das Backend starten. Der API-Key bleibt ausschließlich im Backend; der Sync läuft einseitig von Intervals.icu in die lokale Anwendung. Wenn Garmin und Intervals.icu beide für Automatik aktiviert sind, bleibt Garmin primär und die Intervals.icu-Automatik wird sichtbar pausiert. Manueller Intervals.icu-Sync bleibt verfügbar.

## Versionsanzeige im PACEBASE-Footer

Der Footer zeigt die installierte Anwendungsversion und den Versionsstatus. Aufklappen (auch per Tastatur) öffnet die Details zur installierten Version und zum veröffentlichten `stable`-Release: Git-Commit als gekürzter GitHub-Link, vollständiger kopierbarer Image-Digest und Zeitpunkt der letzten Prüfung. **Auf Updates prüfen** aktualisiert diese Informationen innerhalb der unten beschriebenen Begrenzung. Escape schließt die Details.

Die Anzeige informiert ausschließlich über verfügbare Releases. **Sie installiert keine Updates und lädt keine Images herunter.** Produktive Versionswechsel erfolgen weiterhin ausschließlich über `scripts/update-production.sh` mit Pflicht-Backup und verifizierter Release-Identität; ein angezeigtes Update ist keine Deployment-Freigabe.

| Status | Bedeutung |
| --- | --- |
| `CURRENT` – Version aktuell | Installierter und veröffentlichter vollständiger Image-Digest sind identisch; Commit-Metadaten sind dafür nicht erforderlich. |
| `UPDATE_AVAILABLE` – Update verfügbar | Unterschiedliche Digests und gültige unterschiedliche Commits; GitHub bestätigt das veröffentlichte Commit als echten Nachfolger des installierten Commits. |
| `REBUILD_AVAILABLE` – Neuer Build verfügbar | Unterschiedliche Digests zum selben gültigen Commit. Ein anderer Build ist nicht automatisch ein neuerer Softwarestand. |
| `DIFFERENT_RELEASE` – Anderes Release veröffentlicht | Unterschiedliche Digests ohne nachgewiesene neuere Commit-Abstammung, etwa bei älteren/divergierenden Commits, fehlenden Commit-Metadaten oder nicht verfügbarem GitHub-Vergleich. Kein Update-Nachweis. |
| `UNKNOWN` – Update-Status unbekannt | Installierter oder veröffentlichter Digest fehlt/ist ungültig, etwa im lokalen Entwicklungsmodus oder bei GHCR-Ausfall. |

### Versionsstatus-API und Build-Identität

`GET /api/version` liefert `applicationVersion`, `installedCommit`, `installedDigest`, `latestCommit`, `latestDigest`, `updateStatus`, `lastCheckedAt` und `errorStatus`.
Version und Commit stammen aus den generierten Spring-Boot-Buildinformationen (`build.version` / `build.commit`). CI übergibt denselben vollständigen Commit über `BUILD_COMMIT` an den Image-Build und an das OCI-Label `org.opencontainers.image.revision`; Docker reicht ihn als Gradle-Property `buildCommit` an das JAR weiter. Der installierte Digest stammt aus `SPORTTRACKER_IMAGE_DIGEST`: `compose.production.yaml` nutzt exakt dieselbe Variable für die digest-gepinnte Image-Referenz und die Backend-Umgebung, nicht eine Docker-Image-ID oder einen Plattform-Unterdigest. Der verifizierte Deployment-Aufruf setzt diesen Wert ausdrücklich auf den angeforderten Release-Digest, sodass ein geerbter alter Shell-Wert nicht die neue Anwendung falsch kennzeichnet. Veröffentlichten Commit und vollständigen GHCR-Digest liest der Metadatenclient aus `stable` wie oben beschrieben. Es werden weder Docker-Socket-Rechte noch persönliche GitHub-Tokens benötigt; Registry-Tokens bleiben ausschließlich im Backend.

Fehlende oder ungültige Digests/Commits werden als `null` ausgegeben. Beim lokalen `bootRun` ohne Produktions-Digest ist der Status `UNKNOWN`; die lokale Build-Version bleibt verfügbar, ein Commit wird ohne explizites `-PbuildCommit=<vollständiger-Commit>` nicht erfunden. GHCR-Metadaten können trotzdem angezeigt werden. `0.1.0-SNAPSHOT` ist derzeit die feste Gradle-Anwendungsversion, kein aus `stable` abgeleiteter Release-Name; Commit und Digest unterscheiden die Builds eindeutig.

### Cache, manueller Refresh und Ausfälle

Der bestehende GHCR-Client liest die Metadaten von `stable` erst bei Bedarf. Erfolgreiche Prüfungen bleiben sechs Stunden, GHCR- oder GitHub-Abfragefehler fünf Minuten im Prozessspeicher. `GET /api/version?refresh=true` erzwingt eine Prüfung außerhalb dieser Fristen, frühestens aber eine Minute nach Abschluss der letzten Prüfung. Häufigere Aktualisierungen liefern mit HTTP 200 den bestehenden Cache; gleichzeitige Aufrufe teilen eine Prüfung einschließlich des GitHub-Vergleichs. Es gibt keinen Scheduler und keine Datenbankablage. HTTP-Antworten verwenden `Cache-Control: no-store`.

`lastCheckedAt` ist der UTC-Abschlusszeitpunkt des letzten Prüfversuchs, auch bei Fehlern. Bei GHCR-Ausfall bleibt HTTP 200 mit den lokalen Informationen erhalten; `latestCommit`/`latestDigest` sind dann `null`, `updateStatus` ist `UNKNOWN` und `errorStatus` enthält ausschließlich einen definierten Fehlercode. Erfolgreiche Prüfungen haben `errorStatus: null`. Health und Readiness fragen GHCR nicht ab.

Bei unterschiedlichen Digests und gültigen, unterschiedlichen vollständigen Commit-SHAs prüft das Backend zusätzlich anonym und ausschließlich lesend `https://api.github.com/repos/kruemelnerd/sporttracker/compare/{installedCommit}...{latestCommit}?per_page=1`. Nur `ahead` mit positivem `ahead_by`, `behind_by: 0` und installiertem Commit als `base_commit` und `merge_base_commit` ergibt `UPDATE_AVAILABLE`. Ältere, divergierende oder unklare Releases bleiben `DIFFERENT_RELEASE`. Bei GitHub-Timeouts, Rate-Limits, HTTP-/Netzwerkfehlern oder ungültigen Antworten bleiben alle lokalen und GHCR-Metadaten erhalten; es wird kein Update behauptet und nach fünf Minuten erneut geprüft. `errorStatus` bleibt ausschließlich für GHCR-Fehler reserviert; ein fehlgeschlagener GitHub-Vergleich hat keinen eigenen Frontend-Fehlercode.

Pro Prüfversuch erfolgt höchstens eine GitHub-Anfrage ohne Token, automatische Wiederholung, Redirects oder weitere Pagination; die Antwortgröße ist auf 1 MiB begrenzt. Die GitHub-Verbindungs- und Antworttimeouts betragen standardmäßig zwei bzw. drei Sekunden (`running-dashboard.github.connect-timeout` / `running-dashboard.github.read-timeout`). Dieser Metadaten- und Abstammungsvergleich ersetzt weder Release-Verifikation noch ein manuelles Produktionsupdate; ein Update wird nicht automatisch ausgeführt.

## Stoppen und Zurücksetzen

```bash
docker compose stop
docker compose down
docker compose down -v  # löscht den benannten PostgreSQL-Datenträger unwiderruflich
```

Der Datenbestand liegt im benannten Volume `running-dashboard-postgres` und bleibt bei `stop`/`down` erhalten.

## Abhängigkeitsupdates mit Renovate

`renovate.json` überwacht Gradle samt Wrapper, npm samt `package-lock.json`, Docker-Images in Dockerfiles/Compose-Dateien und GitHub Actions. Reguläre Update-PRs entstehen montags zwischen 00:00 und 03:59 Uhr in `Europe/Berlin`; Sicherheitsupdates dürfen unabhängig davon zeitnah einen PR erzeugen. Es gibt höchstens vier reguläre und einen Sicherheits-PR gleichzeitig sowie maximal zwei reguläre neue PRs pro Stunde.

Kompatible Non-Major-Updates werden getrennt für Gradle, Frontend-Laufzeit-, Frontend-Entwicklungs-, Docker-Build- und GitHub-Actions-Abhängigkeiten gruppiert. Majors bleiben einzeln. PostgreSQL bleibt auf Major 18 beschränkt und erhält eigene Minor-, Patch- oder Digest-PRs; ein PostgreSQL-Major-Upgrade erfordert separat eine Migrations- und Backupplanung. Docker- und Actions-Pins bleiben unveränderlich: Docker-Updates behalten `@sha256`, GitHub Actions vollständige Commit-SHAs mit Versionskommentar. Die variable produktive Sporttracker-Image-Referenz verwaltet Renovate nicht.

Die gehostete Renovate-GitHub-App ist durch diese Änderung **nicht** installiert. Ein Repository-Administrator installiert die offizielle App in GitHub mit **Only select repositories** und wählt ausschließlich `kruemelnerd/sporttracker`; zusätzliche Tokens oder Secrets sind nicht nötig. Bei einer Auswahlinstallation kann GitHub einen Onboarding-PR anlegen. Diesen nur mergen, wenn er die vorhandene `renovate.json` nicht abschwächt oder ersetzt; andernfalls den PR schließen und die Konfiguration unverändert lassen.

Das Dependency Dashboard ist ein GitHub-Issue und zeigt ausstehende, begrenzte oder ignorierte Updates. Für jeden Renovate-PR: Diff, Release Notes und Kompatibilität prüfen, bei npm die aktualisierte Sperrdatei kontrollieren und alle bestehenden CI-Checks einschließlich Tests, Trivy, Gitleaks und Workflow-Prüfungen abwarten. Keine PRs werden automatisch gemergt. Nach einem manuellen Merge nach `main` baut, scannt, attestiert und veröffentlicht die bestehende CI ein GHCR-Image; sie deployt es nicht.

Für ein manuelles Update auf dem Proxmox-LXC ausschließlich den [Produktionsupdate-Ablauf mit Pflicht-Backup](#manuelles-produktionsupdate-mit-pflicht-backup) verwenden.

## Tests

```bash
./scripts/test-all.sh
```

Das Skript führt Backend-, Frontend- und Playwright-Tests sequenziell gegen eine pro Lauf neu angelegte PostgreSQL-Datenbank, ein eigenes Compose-Projekt und dynamische Loopback-Ports aus. Eigene temporäre Ressourcen werden anschließend geprüft und entfernt. Mit `./scripts/test-all.sh --keep-on-failure` bleiben Ressourcen eines fehlgeschlagenen Laufs zur Fehlersuche erhalten.

Der Playwright-Smoke kann separat innerhalb einer explizit bereitgestellten isolierten `TEST_*`-Umgebung über `frontend/scripts/phase1-smoke.sh` ausgeführt werden. Er startet keine Datenbank selbst.

Die Summary zeigt den dekodierten Lauf als `running` mit Status `decoded`, UTC-Startzeit, Distanz sowie positiven Record-/Lap-Zählungen. Upload, Persistenz, Dashboard, Karten, Charts und Analytics sind in dieser Phase nicht enthalten.

## Lizenz

Sporttracker steht unter der MIT-Lizenz; siehe [LICENSE](LICENSE).

UI UX Pro Max ist ein eingebundener Drittanbieter-Skill unter MIT-Lizenz, Copyright (c) 2024 Next Level Builder. Die [Originalquelle](https://github.com/nextlevelbuilder/ui-ux-pro-max-skill) und die lokale [Lizenzdatei](.opencode/skills/ui-ux-pro-max/LICENSE) sind hier verlinkt.
