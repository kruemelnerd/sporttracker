# Proxmox-Deployment vorbereiten

Die konkrete, phasenweise Erstinstallationsanleitung für Ubuntu Server 24.04 steht in [proxmox-first-install.md](proxmox-first-install.md). Dieses Dokument beschreibt ergänzend Betriebsgrenzen und Release-Sicherheit.

**Status: Vorbereitung; kein Proxmox- oder Produktiv-Deployment durchgeführt.** Diese Anleitung ist für eine dedizierte VM bestimmt. Sie setzt weder Container auf dem Proxmox-Host noch eingehenden Zugriff aus GitHub Actions voraus.

## Architektur und Voraussetzungen

- Dedizierte VM mit Ubuntu Server 24.04 LTS, aktuellen Sicherheitsupdates und Docker Engine aus dem offiziellen Docker-APT-Repository einschließlich Docker Compose Plugin.
- Zielgröße 4 vCPU und 8 GiB RAM. App und PostgreSQL sind jeweils auf 1 GiB RAM und 1,5 CPU begrenzt; zusätzlich braucht der Host Reserven für Betriebssystem, Docker, Dateisystemcache und Scan-/Backup-Arbeiten.
- Mindestens 40 GiB VM-System-/Docker-Speicher als Planungswert, nicht als Obergrenze. PostgreSQL- und FIT-Daten wachsen unabhängig davon; ihre Kapazität muss anhand des tatsächlichen Datenbestands geplant werden. Backupkopien gehören nicht ausschließlich auf die VM.
- Netzwerk: VM erhält eine private LAN-Adresse per bestehendem DHCP/DNS oder bewusst eingerichteter DHCP-Reservierung. Keine feste Adresse ist hier vorgegeben. Keine Router-Portweiterleitung und keine öffentliche Freigabe.
- Auf der VM läuft ein lokaler Reverse Proxy (z. B. Caddy oder Nginx) als Hostdienst. Die App bleibt auf `127.0.0.1:8080` veröffentlicht; der Proxy leitet Anfragen intern an `http://127.0.0.1:8080` weiter. Der Proxy lauscht auf der privaten VM-Netzwerkschnittstelle. PostgreSQL bleibt ausschließlich im Compose-Netz und erhält keinen Host-Port.
- Host-Firewall: eingehend standardmäßig sperren; nur erforderliche Admin-SSH-Zugriffe aus dem Admin-LAN und Proxy-Ports aus dem privaten LAN-CIDR erlauben. `<LAN-CIDR>` ist vor Ort zu ersetzen. Bei internem HTTPS nur TCP 443 (und optional 80 für Redirect/ACME-freie interne Nutzung) aus dem LAN erlauben. Keine Regeln auf Proxmox oder Router werden durch diese Anleitung gesetzt.
- Für internes HTTPS kann Caddy mit einer internen CA eingesetzt werden. Clients müssen der CA vertrauen; CA-Schlüssel und Zertifikate dürfen nicht im Repository liegen. Ohne verteiltes Vertrauen keine TLS-Sicherheit behaupten. Alternativ kann der Reverse Proxy HTTP ausschließlich im vertrauenswürdigen privaten LAN bereitstellen.

Der Reverse Proxy ist ein VM-Hostdienst und benötigt keinen zusätzlichen Container oder Docker-Socket-Zugriff. Zugriff auf den Docker-Daemon entspricht praktisch Root-Zugriff; den `docker`-Gruppe keine gewöhnlichen Nutzer hinzufügen.

## Docker, Werkzeug und GHCR vorbereiten

Docker Engine und Compose Plugin ausschließlich aus dem offiziellen Docker-Paket-Repository installieren (nicht das ungeprüfte Docker-Snap-Paket). Danach prüfen:

```bash
docker --version
docker compose version
docker info
```

Zusätzlich werden Bash, GNU `stat`, `sha256sum`, `realpath`, `jq`, `curl` und ein aktuelles Trivy benötigt. Das verpflichtende `scripts/verify-release.sh` benötigt außerdem eine GitHub-CLI-Version mit `gh attestation verify` und dessen JSON-Ausgabe. Das Skript bricht bei fehlenden Werkzeugen oder nicht unterstützten CLI-Optionen ab. Trivy muss seine Vulnerability-Datenbank aktuell laden können; Offline-/DB-Fehler sind kein Anlass, den Scan zu überspringen.

Repository-Sichtbarkeit und GHCR-Package-Sichtbarkeit sind getrennt. Das Quellrepository ist öffentlich; ein anonymer Manifestabruf des freigegebenen Images wurde mit HTTP 401 (`authentication required`) beantwortet. Das Package erfordert damit derzeit Authentifizierung. Die Package-Sichtbarkeit wurde nicht verändert.

Für Pull und Attestierungsprüfung einen dedizierten GitHub-Zugang mit minimalem `read:packages`-Lesezugriff verwenden. Kein `write:packages`, kein Organisations-Adminzugriff und keine langfristigen Runner-Credentials. Falls ein Classic PAT nötig ist, ausschließlich Scope `read:packages`, ein kurzes Ablaufdatum und regelmäßige Rotation verwenden; außerhalb des Repositories mit restriktiven Dateirechten verwahren und bei Verlust sofort widerrufen. Nie in Compose-Dateien, History, Logs oder Git ablegen. Login nur über stdin, z. B. `... | docker login ghcr.io --username USERNAME --password-stdin`. `gh auth status` darf keine Tokens ausgeben. Das öffentliche Repository benötigt für diese Anleitung keinen zusätzlichen Schreibzugriff.

## Release-Identität und fail-closed Verifikation

Deployment-Identität ist ausschließlich der vollständige Digest, zum Beispiel:

```text
ghcr.io/kruemelnerd/sporttracker@sha256:<64-stellige-hex-ID>
```

Keine Tags wie `stable` oder `sha-...` als Deployment-Eingabe verwenden. Vor jedem Start/Update führt `scripts/deploy-verified.sh` ohne Skip-Option diese Schritte aus:

1. Eingabeformat und Image-Repository auf den vollständigen Digest begrenzen.
2. `scripts/verify-release.sh` ausführen; dieses lädt das Image per Digest und prüft dessen Quell- und Commit-Labels.
3. Provenance kryptografisch via `gh attestation verify` prüfen: Repository `kruemelnerd/sporttracker`, Workflow `.github/workflows/ci.yml`, Branch `refs/heads/main`, Commit-Label als Source-Digest, GitHub-OIDC-Issuer und SLSA-Provenance-Predicate. Self-hosted Runner werden abgelehnt.
4. Die signierte SPDX-Attestierung ebenso für Workflow, Branch, Commit, Issuer, Repository und SPDX-Predicate kryptografisch verifizieren.
5. Die verifizierten JSON-Statements auswerten: beide müssen den angeforderten SHA-256-Digest als Subject enthalten. Die SPDX-Nutzlast muss ein SPDX-2.3-Dokument mit `SPDXID`, Name und mindestens einem Package/Komponenteneintrag sein.
6. Einen aktuellen Trivy-Image-Scan des Digest-Images durchführen. High- oder Critical-Befunde und Scan-/Datenbankfehler brechen den Ablauf ab.
7. Erst nach erfolgreicher Prüfung Compose still validieren, die beiden erforderlichen externen Volumes nachweisen und anschließend den App-Service starten bzw. aktualisieren. Der Start wartet auf Compose-Readiness.

Jede fehlgeschlagene Release-Verifikation endet, bevor Compose ausgeführt wird. Fehlende externe Volumes werden nie automatisch erzeugt. Nach erfolgreicher Verifikation bewahrt der Runner die vorherige Compose-Umgebungsdatei unter einem eindeutigen `.previous.<UTC-Zeit>`-Namen und ersetzt den Digest atomar. Bei fehlgeschlagener Readiness wird kein automatischer Image-Rollback durchgeführt: Flyway kann das Schema bereits geändert haben. Der Runner bricht mit manueller Wiederherstellungserfordernis ab; Container-Rollback ist keine Datenbankwiederherstellung.

## Ersteinrichtung und Start

1. VM sichern/härten, Updates einspielen, Administratorzugänge einschränken und Firewall wie oben beschrieben planen. Noch keine produktiven Daten importieren.
2. Repository/Deployment-Dateien auf die VM holen und den freigegebenen Digest außerhalb von Git als Betriebswert bereitstellen. Die Produktionsdateien dürfen nicht durch lokale Entwicklungs-Compose-Dateien ersetzt werden.
3. Vorhandene Volume-Namen kontrollieren. Nur wenn beide Volume-Namen nachweislich fehlen und eine bewusste Neuinstallation freigegeben ist, dürfen sie manuell angelegt werden. Siehe den Phasen-Stoppunkt in `proxmox-first-install.md`:

   ```bash
   docker volume inspect sporttracker-prod-postgres
   docker volume inspect sporttracker-prod-fit
   # Nur nach Prüfung, dass es eine leere Neuinstallation sein soll:
   docker volume create sporttracker-prod-postgres
   docker volume create sporttracker-prod-fit
   ```

   Bei vorhandenem Volume anhalten und dessen Herkunft/Datenbestand klären. Niemals vorhandene Volumes löschen, ersetzen oder mit Entwicklungsdaten befüllen.

4. Secret-Verzeichnis außerhalb des Repositories mit Zugriff nur für den Betriebsbenutzer erstellen. Secret-Dateien außerhalb der VM- oder Terminal-History generieren. Host-Dateirechte `0400`; PostgreSQL-Dateien müssen für Container-UID 999 und App-Configtree-Dateien für UID 10001 lesbar sein. App-URL-/Benutzerdateien dürfen keine Passwörter enthalten. Keine Secret-Werte als Kommandoargumente übergeben.
5. Eine private Compose-Umgebungsdatei mit Modus `0600` anlegen. Sie enthält Dateipfade (`POSTGRES_ADMIN_PASSWORD_FILE`, `POSTGRES_APP_PASSWORD_FILE`, `APP_DATABASE_URL_FILE`, `APP_DATABASE_USERNAME_FILE`, `APP_DATABASE_PASSWORD_FILE`) sowie `SPORTTRACKER_IMAGE=ghcr.io/kruemelnerd/sporttracker` und `SPORTTRACKER_IMAGE_DIGEST=sha256:<freigegebener Digest>`. Keine Secret-Werte selbst eintragen.
6. Für eine erste leere Installation oder ein Update denselben geprüften Digest als Argument verwenden. Nach erfolgreicher Prüfung wird die Digest-Zeile in `production.env` atomar aktualisiert und eine private Kopie der vorherigen Datei behalten:

   ```bash
   scripts/deploy-verified.sh \
     --env-file /secure/path/production.env \
     --project sporttracker-prod \
     ghcr.io/kruemelnerd/sporttracker@sha256:<64-stellige-hex-ID>
   ```

   Platzhalter vor Ausführung ersetzen. Das Skript startet ausschließlich `compose.production.yaml`, führt keine Datenmigration/Restore von Entwicklungsdaten durch und verändert keine Volumes. Es ist in diesem Meilenstein ausschließlich mit isolierten Testressourcen bzw. Stubs getestet worden; der tatsächliche Produktionsstart bleibt offen. Ein positiver lokaler Nachweis-Test war hier nicht möglich: vorhandenes `gh` 2.46 unterstützt `gh attestation verify` nicht, und der vorhandene Login hat keinen `read:packages`-Scope. Der positive Attestierungsnachweis aus GitHub Actions ersetzt den späteren Verifier-Lauf auf der Ziel-VM nicht.

7. Bei Readiness-Fehlern erfolgt kein automatischer Container-Rollback, weil Flyway das Schema verändert haben kann. Manuelle Wiederherstellung aus verifiziertem Backup planen; keine Volumes löschen. Danach Proxy/Firewall aus dem LAN testen und `/api/ready` prüfen. Für Adressen wird nichts vorgegeben; DNS/DHCP ist vor Ort zu planen.

Die optionale Intervals.icu-Erweiterung bleibt deaktiviert. Sie darf nur nach expliziter Freigabe einer externen Datensynchronisierung mit `compose.production.intervals.yaml` und einem separaten, geschützten API-Key aktiviert werden.

## Persistenz, Backups und Restore

- PostgreSQL liegt ausschließlich im externen Volume `sporttracker-prod-postgres`; FIT-Dateien und Originale liegen im externen Volume `sporttracker-prod-fit` unter `/data`.
- Compose-Dateien und Secrets getrennt von Datenvolumes sichern. `scripts/backup.sh` erstellt einen konsistenten PostgreSQL-Custom-Dump, archiviert FIT-/Originaldaten, schreibt Versionsmetadaten und Prüfsummen. Das Skript stoppt während des Snapshots die App; externe DB-/Volume-Schreiber müssen ebenfalls ausgeschlossen sein.
- Backupziel muss außerhalb beider Quellvolumes liegen. `backup.sh` verschlüsselt das Backup selbst nicht. Für Betrieb ist ein verschlüsseltes, zugriffsbeschränktes Ziel außerhalb der VM erforderlich (separates NAS/Backup-System), mit einer zweiten Kopie und kontrollierter Aufbewahrung. Prüfsummen schützen vor unbeabsichtigter Beschädigung, nicht vor gezielter Manipulation.
- Trainings-/FIT-Daten dürfen erst übernommen werden, nachdem ein vollständiges Backup erstellt, Prüfsummen geprüft und eine externe Kopie nachweislich vorhanden ist. Zusätzlich muss ein isolierter Restore-Test erfolgreich sein.
- Restore ausschließlich mit `scripts/restore.sh` und `compose.restore.yaml` in neue, leere, eigens benannte Testressourcen ausführen. Restore startet die App nicht automatisch und löscht bei Fehlern angelegte Testressourcen nicht. Vor einem späteren manuellen Cutover Datensätze, Flyway-Stand, FIT-Dateien und Hashes vergleichen.
- Nie `docker compose down -v`, `docker volume rm/prune`, `Flyway clean` oder Restore in vorhandene produktive Volumes verwenden. Bei VM-Ausfall auf neue VM/neue Volumes wiederherstellen; produktive Umschaltung separat freigeben.

Backup-Timer, Rotation, verschlüsselte Offsite-Übertragung, Restore-Übungen und automatischer Cutover sind nicht implementiert. Es gibt keinen Proxmox-Backup-Job in diesem Schritt.

## Fehlerbehandlung und offene Punkte

- Digest, Pull, GitHub-CLI, Signatur, Issuer/Workflow/Branch/Commit, SPDX-Dokument, Subject-Digest, Trivy oder Compose-Vorprüfung fehlerhaft: fail closed; keine Compose-Änderung durch `deploy-verified.sh`.
- Volume fehlt oder Compose-Secrets/Env-Datei sind fehlerhaft: Start wird abgebrochen; Volumes/Secrets nicht automatisch anlegen oder reparieren.
- Neue App wird nicht ready: kein automatischer Digest-Rollback. Stack/Volumes nicht löschen oder weiter verändern; Logs ohne Secrets sichern, Schema-/Migrationsstand prüfen und manuelle Wiederherstellung aus Backup planen.
- GHCR ohne Authentifizierung: derzeit HTTP 401; Zugriffstoken mit nur erforderlichem Paket-Lesezugriff außerhalb des Repositories konfigurieren.
- Noch offen für M8.3.2: Proxmox-VM tatsächlich erstellen, OS-/Docker-Paketstände festlegen, private LAN-Adresse/DNS und LAN-CIDR vor Ort ermitteln, Proxy/Firewall/CA konfigurieren, Secret- und Backupziele einrichten, reale GHCR-Zugangsdaten auf der VM sicher bereitstellen, Verifikation und Trivy-Scan auf der VM wiederholen, Backup samt externer Kopie und isoliertem Restore nachweisen und erst danach einen produktiven Start separat freigeben.
