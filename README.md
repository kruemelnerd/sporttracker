# Running Dashboard

Lokale Webanwendung zur Verwaltung und Auswertung eigener Lauftrainings aus Garmin-/FIT-Dateien.

## Voraussetzungen

- Java 25
- Node.js 22.12 oder neuer und npm
- Docker mit Docker Compose

## Lokaler Start

Optional `.env.example` nach `.env` kopieren und Werte anpassen. Die voreingestellten Zugangsdaten sind nur für die lokale Entwicklung gedacht.

1. PostgreSQL starten und Health prüfen:

   ```bash
   docker compose up -d db
   docker compose ps
   ```

2. Backend starten (Flyway führt die Baseline beim Start aus):

   ```bash
   cd backend
   ./gradlew bootRun
   ```

3. In einem zweiten Terminal Frontend starten:

   ```bash
   cd frontend
   npm ci
   npm run dev
   ```

Backend: `http://localhost:8080`. Frontend: `http://localhost:5173`. Das Frontend leitet `/api` über Vite an das Backend weiter.

Beide Anwendungen samt PostgreSQL lassen sich auch gemeinsam starten:

```bash
./dev.sh
```

Mit `Ctrl-C` werden Backend und Frontend beendet; PostgreSQL bleibt gestartet und die Daten bleiben erhalten.

## Containerbetrieb

Für den produktiven Docker-Compose-Betrieb, Secrets, persistente Volumes sowie Backup und Restore siehe [docs/containerization.md](docs/containerization.md). Der lokale Entwicklungsstart über `./dev.sh` bleibt davon unabhängig.

## Intervals.icu

1. Intervals.icu öffnen und **Settings → Developer Settings** aufrufen.
2. Einen persönlichen API-Key erstellen.
3. Den Key als Backend-Environment-Variable setzen und den automatischen Import aktivieren:

   ```bash
   export INTERVALS_ICU_API_KEY=<your-api-key>
   export INTERVALS_ICU_ENABLED=true
   ```

4. Das Backend starten. Der API-Key bleibt ausschließlich im Backend; der Sync läuft einseitig von Intervals.icu in die lokale Anwendung.

## Stoppen und Zurücksetzen

```bash
docker compose stop
docker compose down
docker compose down -v  # löscht den benannten PostgreSQL-Datenträger unwiderruflich
```

Der Datenbestand liegt im benannten Volume `running-dashboard-postgres` und bleibt bei `stop`/`down` erhalten.

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
