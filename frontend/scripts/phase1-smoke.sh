#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

docker compose -p sporttracker-m4-e2e -f compose.e2e.yaml up -d --wait --wait-timeout 90 db
docker compose -p sporttracker-m4-e2e -f compose.e2e.yaml exec -T db \
  psql -v ON_ERROR_STOP=1 -U running_dashboard_e2e -d running_dashboard_e2e \
  -c "DO \$\$ BEGIN IF current_database() <> 'running_dashboard_e2e' OR current_user <> 'running_dashboard_e2e' THEN RAISE EXCEPTION 'Not the isolated E2E database'; END IF; END \$\$;"

cd frontend
npx playwright test --config=playwright.config.ts
