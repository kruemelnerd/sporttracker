#!/usr/bin/env bash
set -Eeuo pipefail

if [[ "${1:-}" != "--tracer" ]]; then
  echo "usage: $0 --tracer" >&2
  exit 2
fi

root=$(cd "$(dirname "$0")/../.." && pwd)
run_id=${TEST_RUN_ID:-}
project=${TEST_COMPOSE_PROJECT:-}
database=${TEST_DATABASE_NAME:-}
username=${TEST_DATABASE_USERNAME:-}
[[ "$run_id" =~ ^[a-f0-9]{24}$ && "$project" == "sporttracker-ci-$run_id" && "$database" == "running_dashboard_test_$run_id" && "$username" == "$database" ]] || {
  echo 'Run this smoke test inside scripts/test-all.sh or with its isolated TEST_* environment' >&2
  exit 2
}
compose=(docker compose -p "$project" -f "$root/compose.test.yaml")
"${compose[@]}" exec -T db psql -X -v ON_ERROR_STOP=1 -U "$username" -d "$database" \
  -c "DO \$\$ BEGIN IF current_database() <> '$database' OR current_user <> '$username' OR (SELECT shobj_description(oid, 'pg_database') FROM pg_database WHERE datname = current_database()) <> 'sporttracker-test:$run_id:$project' THEN RAISE EXCEPTION 'Refusing smoke test outside current isolated database'; END IF; END \$\$;"

(cd "$root/frontend" && npx playwright test --config=playwright.config.ts fit-upload.spec.ts)
"${compose[@]}" exec -T db psql -X -v ON_ERROR_STOP=1 -U "$username" -d "$database" \
  -Atc "SELECT count(*) FROM activity" | grep -Eq '^[2-9][0-9]*$|^[1-9][0-9]{2,}$'
