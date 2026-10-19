#!/usr/bin/env bash
set -Eeuo pipefail

if [[ "${1:-}" != "--tracer" ]]; then
  echo "usage: $0 --tracer" >&2
  exit 2
fi

root=$(cd "$(dirname "$0")/../.." && pwd)
compose=(docker compose -p sporttracker-m4-e2e -f "$root/compose.e2e.yaml")
"${compose[@]}" up -d --wait db
"${compose[@]}" exec -T db psql -v ON_ERROR_STOP=1 -U running_dashboard_e2e -d running_dashboard_e2e \
  -c "DO \$\$ BEGIN IF current_database() <> 'running_dashboard_e2e' OR current_user <> 'running_dashboard_e2e' THEN RAISE EXCEPTION 'Refusing smoke test outside isolated E2E database'; END IF; END \$\$;"

(cd "$root/frontend" && npx playwright test fit-upload.spec.ts)
"${compose[@]}" exec -T db psql -v ON_ERROR_STOP=1 -U running_dashboard_e2e -d running_dashboard_e2e \
  -Atc "SELECT count(*) FROM activity" | grep -Eq '^[2-9][0-9]*$|^[1-9][0-9]{2,}$'
