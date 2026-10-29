# M8.3.2b – Linux-Host-Setup

Status: Vom Betreiber freigegeben; implementiert und lokal verifiziert. Reale
Erstinstallation auf einem neuen Gast bleibt separat zu prüfen. Kein Deployment.

## Ziel und Abgrenzung

Eine einzelne heruntergeladene Bash-Datei `scripts/install-host-tools.sh` bereitet
Ubuntu/Debian für den Sporttracker vor. Sie funktioniert ohne vorherigen Clone.
Server und Desktop, physische Rechner, VMs und unprivilegierte LXC-Gäste werden
anhand derselben technischen Voraussetzungen geprüft. Proxmox-VE-Hosts werden
vor Downloads und Änderungen abgelehnt.

Setup installiert ausschließlich Werkzeuge und stellt optional das öffentliche
Repository unter `/opt/sporttracker` bereit. Es erzeugt keine produktiven Volumes,
Secrets, Datenbanken oder Container und führt weder Flyway noch FIT-Import aus.
`verify-release.sh` und `deploy-verified.sh` bleiben unverändert verbindlich.
Ein erfolgreicher Setup-Lauf ist keine erfolgreiche Release-Verifikation.

## Bedienung

- Ohne Parameter: Bestandsaufnahme, verständlicher Änderungsplan, ausdrückliche
  interaktive Bestätigung, anschließend ausschließlich notwendige Installation.
- `--check`: ohne Root möglich; keine Installation, kein Clone/Fetch, keine
  Paketlisten-, Schlüsselbund-, Cache- oder temporären Dateischreibzugriffe.
  Nur lesende lokale Prüfungen und HTTPS-Abfragen. Unprüfbares wird nicht als
  bestanden gemeldet; fehlender Socketzugriff wird von fehlendem Docker getrennt.
- `--upgrade`: ausdrückliche Aktualisierung verwalteter Pakete nach Bestätigung.
  Keine Betriebssystemaktualisierung und kein Release-Upgrade.
- `--help`: kurze Bedienungsbeschreibung; unbekannte/kombinierte Modi sind Fehler.

Meine Umsetzungsentscheidung: kein unbeaufsichtigtes `--yes`, kein automatischer
Smoke-Container und keine automatische Migration fremder Docker-Installationen.
Diese Optionen sind nicht erforderlich und würden die Sicherheitsfläche erhöhen.

## System- und Laufzeitprüfung

`/etc/os-release`, `dpkg --print-architecture` und, falls vorhanden,
`systemd-detect-virt` liefern Distribution, Codename, Paketarchitektur und
Virtualisierung. Nur die IDs `ubuntu` und `debian` sind zulässig; keine
Ableitungsheuristik über `ID_LIKE`. Fehlende/ungültige Metadaten führen zum Fehler.

Proxmox-Host-Indikatoren umfassen insbesondere PVE-Verwaltungswerkzeuge,
installierte PVE-Managementpakete und `/etc/pve`; ein PVE-Kernel allein ist kein
Hostbeweis, weil LXC den Hostkernel teilt. Bei widersprüchlichen Hostindikatoren
wird sicherheitshalber abgebrochen. VM/LXC wird nicht anhand des Kernelnamens
verboten. Fehlendes Nesting/Keyctl oder Sicherheitsprofilprobleme werden nur mit
manuellen Betreiberhinweisen gemeldet, niemals repariert.

Keine feste Versionsliste: Unterstützung erfordert tatsächlich erreichbare,
signierte offizielle Paketmetadaten für Codename/Architektur und die benötigten
Pakete. Fehlende Unterstützung, Signaturen oder Prüfvoraussetzungen blockieren.
APT-Prüfungen werden nicht durch eine bloße HTTP-200-Antwort ersetzt.

Docker wird funktional geprüft: CLI/Serverversion, lokaler Daemonzugriff,
Compose- und Buildx-Plugin, containerd, Storage-Treiber und cgroups. Unbekannte
Werte werden nicht pauschal akzeptiert; insbesondere LXC-Fehler erhalten klare
Hinweise. Compose wird nicht wegen einer neuen Hauptversion abgelehnt. Die
benötigten Compose-Funktionen werden anhand der bestehenden Deployment-Aufrufe
geprüft, ohne Compose auf produktive Konfiguration anzuwenden.

Die GitHub-CLI-Prüfung deckt alle Verifier-Flags einschließlich `--repo`,
`--signer-workflow`, `--source-ref`, `--source-digest`, `--cert-oidc-issuer`,
`--predicate-type`, `--format` (JSON) und `--deny-self-hosted-runners` ab.
Trivy muss erfolgreich eine Version melden; sein späterer vollständiger Scan
bleibt Pflicht.

Meine Umsetzungsentscheidung: Werkzeugunterstützung und Image-Unterstützung
werden getrennt ausgewiesen. Die bestehende CI veröffentlicht derzeit nur
`linux/amd64`; eine anderweitig unterstützte Architektur erhält daher keine
Sporttracker-Deployment-Freigabe. Eine Erweiterung des Image-Builds ist nicht
Bestandteil dieses Auftrags.

## Paketquellen und Installation

Systemwerkzeuge: Git, jq, curl, ca-certificates, gnupg, wget, Python 3 sowie die
tatsächlich von Setup/Deployment/Backup benötigten Debian-/Ubuntu-Basiswerkzeuge.
Kein Java-/Node-Entwicklungssetup für den reinen Containerbetrieb.

Herstellerpakete:

- Docker: `https://download.docker.com/linux/<ubuntu|debian>`, Codename, `stable`;
  `docker-ce`, `docker-ce-cli`, `containerd.io`, `docker-compose-plugin`,
  `docker-buildx-plugin`.
- GitHub CLI: `https://cli.github.com/packages`, Suite `stable`, Komponente `main`.
- Trivy: offizielles Aqua-APT-Repository, Suite `generic`, Komponente `main`;
  aktuelle Herstelleradresse und erlaubte HTTPS-Weiterleitungen dokumentieren.

Jedes Hersteller-Repository erhält einen eigenen Keyring und `Signed-By`.
Vollständige Primary-Key-Fingerprints werden vor Verwendung mit dokumentierten
Hersteller-Vertrauensankern verglichen; fremde zusätzliche Schlüssel werden
abgelehnt. Schlüsselrotation erfordert bewusst gepflegte Vertrauensanker, keine
automatische Übernahme unbekannter Schlüssel. Insbesondere den im April 2026
rotierten Trivy-Schlüssel verwenden, nicht den widerrufenen Vorgänger.

APT bleibt fail-closed: Signatur-/Download-/Installationsfehler abbrechen,
keine unsignierten Quellen, keine Paketentfernung, kein Downgrade, keine
stillen Fallbacks. Alle durch Setup ausgelösten Downloads müssen HTTPS verwenden.
Vorhandene HTTP-Standardquellen dürfen nicht still umgeschrieben werden: für die
Setup-Transaktion können kontrollierte temporäre HTTPS-Quellen mit den
Distributions-Keyrings verwendet werden. Unbekannte Fremdquellen dürfen keine
Herstellerpakete in die Transaktion einschleusen.

Bestehende `.list`- und Deb822-`.sources`-Dateien werden erkannt. Korrekte Quellen
werden wiederverwendet, nicht dupliziert; mehrdeutige, unsichere oder abweichende
Konfigurationen erfordern manuelle Klärung. Keine fremde Datei überschreiben.
APT-Transaktionen werden vor Ausführung simuliert; geplante Änderungen müssen
zu den angeforderten Paketen und erlaubten Abhängigkeiten passen.

Funktionierende Werkzeuge bleiben im Standardmodus unverändert. Docker-Snap,
Docker Desktop, `docker.io`, `podman-docker`, separat installiertes containerd/runc
und Podman werden auf tatsächliche Konflikte geprüft. Keine automatische
Deinstallation; nötige Migration wird verständlich beschrieben und separat
bestätigt/manuell durchgeführt. Eine kaputte vorhandene Docker-Installation
wird nicht durch einen automatischen Neustart verdeckt.

## Docker-Schutz und Upgrade

Bei vorhandenem Docker keine Start-/Restart-/Enable-Aktion und kein ungefragtes
Upgrade, auch nicht als Seiteneffekt einer APT-Abhängigkeit. Paketplanung muss
diesen Schutz vor jeder Installation berücksichtigen.

Meine konservative Umsetzungsentscheidung: Sobald ein Upgrade Docker Engine,
containerd oder andere dienstrelevante Docker-Pakete ersetzen müsste, bricht
`--upgrade` mit Hinweis auf manuelle Wartung ab. Vorher werden laufende Container
rein lesend ermittelt. Auch bei leerer Containerliste ist ein Paketupgrade kein
Beweis gegen einen Daemon-Neustart durch Maintainerskripte. Andere Werkzeuge
können nur aktualisiert werden, wenn die gesamte geplante Transaktion sicher ist.

Nur bei wirklicher Docker-Erstinstallation darf der neu installierte Dienst
gestartet werden, wenn das vorhandene Init-System dies unterstützt. Keine
Host-/LXC-/AppArmor-/Seccomp-/SSH-/UFW-Konfiguration ändern und niemanden der
Docker-Gruppe hinzufügen. Vor Bestätigung darauf hinweisen, dass Docker selbst
Netzwerk-/Firewallregeln verwaltet und veröffentlichte Ports UFW umgehen können.

## Repository und gemeinsamer Preflight

Fehlendes `/opt/sporttracker` darf nach Bestätigung über die feste öffentliche
HTTPS-URL geklont werden. Vorhandene Pfade auf Identität und Zustand prüfen,
keine lokalen Änderungen ausgeben, überschreiben oder löschen. Meine
Umsetzungsentscheidung: bestehende Repositories nicht automatisch aktualisieren;
Zustand melden und einen kontrollierten manuellen Fast-Forward empfehlen.

`scripts/proxmox-preflight.sh` bleibt als kompatibler Einstieg erhalten und nutzt
die lesende Prüfung des Installers aus demselben Repository. Dadurch keine
zweite OS-/Versions-/Sicherheitslogik. Fehlender Installer ist ein klarer Fehler.
Setup kann nach dem Bereitstellen den Preflight ausführen, aber keine fremden,
ungeprüften Skripte aus einem vorhandenen lokalen Repository als Root ausführen.

## Ressourcen und Betriebsdokumentation

2 GiB RAM + 2 GiB Swap, 4 vCPU und 40 GB im unprivilegierten LXC 116 werden
ausdrücklich als kleine Zielumgebung dokumentiert; kein pauschales 8-GiB-Minimum.
RAM, Swap und freier Speicher werden gemessen. Empfehlungen und Warnungen werden
von wirklich blockierenden Bedingungen getrennt. Vorhandene Containerlimits
können zusammen bereits 2 GiB beanspruchen: zusätzlicher Hostbedarf ist real.
Trivy-DB/Scan benötigen zusätzliche Reserven; OOM/Scanfehler bleiben blockierend.
Keine automatische Swap-/Limit-/Storage-Anpassung und keine deaktivierten Scans.

README erhält den sichtbaren Zwei-Befehle-Einstieg, alle Modi und die Trennung
Setup/Deployment. Root-Ausführung von `main` ausdrücklich als Vertrauensentscheidung
kennzeichnen: Datei vorher lesen, optional Commit-gepinnte URL und unabhängig
vertrauenswürdig erhaltenen SHA256-Wert vergleichen. Ein Hash vom selben Server
allein ist kein unabhängiger Authentizitätsnachweis.

`docs/proxmox-first-install.md`, `docs/proxmox-deployment.md` und
`docs/containerization.md` behalten ihre Pfade, um Links nicht zu brechen; Inhalt
und Titel werden auf Linux/VM/LXC erweitert. Lange parallele manuelle
Paketinstallationswege entfallen. Alle bestehenden Release-, Backup-,
Freigabe- und Wiederherstellungsgrenzen bleiben erhalten.

## Verifikation und Lieferung

Isolierte Tests mit kontrollierten Befehls-Stubs und separaten Dateibäumen:
frisches Ubuntu/Debian, Ubuntu 26.04, vorhandenes Docker/Quellen, fehlende Tools,
Wiederholung, Check ohne Schreibzugriffe, Upgrade einschließlich Docker-Sperre,
PVE-Host, LXC, VM/physisch, unbekannte Distribution/Architektur, fehlerhafte
Schlüssel/Signaturen, HTTPS-/APT-Fehler, Konflikte, abgelehnte Bestätigung,
bestehende geänderte Repositories und keine Secret-Ausgabe.

Tests müssen unzulässige Docker-/Volume-/Netzwerk-/Git-Befehle ausdrücklich
abweisen, nicht nur erwartete Erfolgsmeldungen prüfen. Zusätzlich Bash-Syntax,
ShellCheck, bestehende Release-/Deployment-Policytests und `git diff --check`.
Reale GPG-/APT-Signaturprüfung zusätzlich isoliert testen, nicht ausschließlich
durch einen Stub mit festem Erfolgsstatus.

Ein echter CI-Installationstest ist nur in wegwerfbaren Gästen ohne produktive
Docker-Sockets/-Volumes zulässig; Docker-in-Docker oder privilegierte Container
sind kein Beweis für unprivilegiertes Proxmox-LXC. CI-Erweiterung, wenn die
vorhandene Infrastruktur einen ehrlichen vollständigen Test ermöglicht.
Backend/Frontend-Build und Regression nach Projektregeln, nur mit nachgewiesener
Testdatenisolation. Nicht ausführbare Prüfungen ausdrücklich als blockiert melden.

Lokaler `main` lag bei Bestandsaufnahme 18 Commits hinter dem vorhandenen
`origin/main`. Vor Implementierung den aktuellen Remote-Stand prüfen und nur
konfliktfrei fast-forwarden; geschützte lokale `.opencode`-Änderungen weder
stashen noch verändern. SSH-Fetch war mangels Schlüssel fehlgeschlagen; keine
Credentials anfordern/ausgeben oder Remote-Konfiguration ungefragt ändern.

Nach erfolgreichen Pflichtprüfungen fachlich getrennte Commits, ausschließlich
Auftragsdateien stagen, jeden Commit nach `origin/main` pushen und Remote-HEAD
prüfen. Fehlende Pushberechtigung ist ein Lieferblocker, kein behaupteter Erfolg.
Abschlussbericht nach Abschnitt 17 der Anforderung, mit tatsächlichem PASS/FAIL
und exakten Bedienbefehlen; kein Produktivstart im Rahmen dieses Meilensteins.
