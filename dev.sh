#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ -f "$ROOT_DIR/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$ROOT_DIR/.env"
  set +a
fi
backend_pid=""
frontend_pid=""

cleanup() {
  trap - EXIT INT TERM
  for pid in "$frontend_pid" "$backend_pid"; do
    if [[ -n "$pid" ]]; then kill "$pid" 2>/dev/null || true; fi
  done
  [[ -z "$frontend_pid" ]] || wait "$frontend_pid" 2>/dev/null || true
  [[ -z "$backend_pid" ]] || wait "$backend_pid" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

docker compose -f "$ROOT_DIR/compose.yaml" up -d --wait db

(cd "$ROOT_DIR/backend" && exec ./gradlew bootRun) &
backend_pid=$!
(cd "$ROOT_DIR/frontend" && exec npm run dev) &
frontend_pid=$!

echo "Backend:  http://localhost:8080"
echo "Frontend: http://localhost:5173"
echo "Ctrl-C beendet Backend und Frontend; PostgreSQL bleibt gestartet."

wait -n "$backend_pid" "$frontend_pid"
