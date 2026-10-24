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
docker compose -p sporttracker-m4-e2e -f compose.e2e.yaml up -d --wait db
cd backend && ./gradlew test
cd frontend && npm run test:unit && npm run build
```

Backend integration tests always use the isolated `running_dashboard_e2e` database and reset its application tables only after checking the database and user. They never use the development database.

## Phase-1-Smoke

Einmalig den Playwright-Browser installieren:

```bash
cd frontend && npx playwright install --with-deps chromium
```

Der vollständige E2E-Test startet eine eigene PostgreSQL-Datenbank (`sporttracker-m4-e2e`, Port `55432`, eigenes Volume), dazu Backend (`18080`) und Vite (`15173`) aus diesem Checkout. Playwright verwendet ausschließlich diese Server; ein vorhandener Server wird nicht wiederverwendet. Das globale Setup leert die Anwendungstabellen nur nach einer Datenbank-/Benutzerprüfung auf `running_dashboard_e2e`:

```bash
cd frontend && ./scripts/phase1-smoke.sh
```

Die normale Entwicklungsdatenbank und deren Volume bleiben unberührt. E2E-Daten liegen ausschließlich im Volume `sporttracker-m4-e2e_sporttracker-m4-e2e-postgres`; zum Stoppen nur des E2E-Containers: `docker compose -p sporttracker-m4-e2e -f compose.e2e.yaml down`. Das Volume bleibt damit für weitere Läufe erhalten.

Die Summary zeigt den dekodierten Lauf als `running` mit Status `decoded`, UTC-Startzeit, Distanz sowie positiven Record-/Lap-Zählungen. Upload, Persistenz, Dashboard, Karten, Charts und Analytics sind in dieser Phase nicht enthalten.
