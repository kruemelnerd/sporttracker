# Running Dashboard

Lokale Webanwendung zur Verwaltung und Auswertung eigener Lauftrainings aus Garmin-/FIT-Dateien.

## Voraussetzungen

- Java 21
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

## Stoppen und Zurücksetzen

```bash
docker compose stop
docker compose down
docker compose down -v  # löscht den benannten PostgreSQL-Datenträger unwiderruflich
```

Der Datenbestand liegt im benannten Volume `running-dashboard-postgres` und bleibt bei `stop`/`down` erhalten.

## Tests

```bash
cd backend && ./gradlew build
cd frontend && npm run test:unit && npm run build
```

## Phase-1-Smoke

Einmalig den Playwright-Browser installieren:

```bash
cd frontend && npx playwright install --with-deps chromium
```

Der vollständige lokale Smoke-Test startet Compose-PostgreSQL, wartet auf Health und Flyway, prüft Backend und Vite-Proxy und führt anschließend explizit Playwright aus:

```bash
cd frontend && ./scripts/phase1-smoke.sh
```

Das Skript beendet nur seine Backend-/Frontend-Prozesse. Das benannte PostgreSQL-Volume bleibt erhalten; `docker compose down -v` löscht es ausdrücklich.

Die Summary zeigt den dekodierten Lauf als `running` mit Status `decoded`, UTC-Startzeit, Distanz sowie positiven Record-/Lap-Zählungen. Upload, Persistenz, Dashboard, Karten, Charts und Analytics sind in dieser Phase nicht enthalten.
