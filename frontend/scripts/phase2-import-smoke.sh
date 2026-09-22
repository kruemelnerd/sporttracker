#!/usr/bin/env bash
set -euo pipefail

if [[ "${1:-}" != "--tracer" ]]; then
  echo "usage: $0 --tracer" >&2
  exit 2
fi

root=$(cd "$(dirname "$0")/../.." && pwd)
backend_pid=""
frontend_pid=""
cleanup() {
  [[ -n "$frontend_pid" ]] && kill "$frontend_pid" 2>/dev/null || true
  [[ -n "$backend_pid" ]] && kill "$backend_pid" 2>/dev/null || true
}
trap cleanup EXIT

docker compose -f "$root/compose.yaml" up -d db
until docker compose -f "$root/compose.yaml" exec -T db pg_isready -U running_dashboard -d running_dashboard >/dev/null; do sleep 1; done
(cd "$root/backend" && GRADLE_OPTS="${GRADLE_OPTS:+$GRADLE_OPTS }-Djdk.lang.Process.launchMechanism=VFORK" DATA_DIRECTORY="$root/.tmp-import-data" ./gradlew --no-daemon bootRun) >"$root/backend/import-smoke.log" 2>&1 & backend_pid=$!
until curl --fail --silent http://127.0.0.1:8080/api/health >/dev/null; do sleep 1; done
(cd "$root/frontend" && npm run dev -- --host 127.0.0.1) >"$root/frontend/import-smoke.log" 2>&1 & frontend_pid=$!
until curl --fail --silent http://127.0.0.1:5173/import >/dev/null; do sleep 1; done
(cd "$root/frontend" && npx playwright test import-tracer.spec.ts)
docker compose -f "$root/compose.yaml" exec -T db psql -U running_dashboard -d running_dashboard -Atc 'SELECT count(*) FROM activity' | grep -Eq '^[1-9][0-9]*$'
test -n "$(find "$root/.tmp-import-data/originals" -type f -name '*.fit' -print -quit)"
