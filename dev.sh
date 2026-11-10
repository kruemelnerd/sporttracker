#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ -f "$ROOT_DIR/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$ROOT_DIR/.env"
  set +a
fi

GARMIN_KEY_FILE="$HOME/.config/sporttracker/garmin-poc.env"
if [[ "${GARMIN_PERSONAL_API_ENABLED:-false}" == true && -z "${GARMIN_PERSONAL_API_KEY:-}" ]]; then
  if [[ ! -r "$GARMIN_KEY_FILE" ]]; then
    echo "Garmin ist aktiviert, aber GARMIN_PERSONAL_API_KEY fehlt und $GARMIN_KEY_FILE ist nicht lesbar." >&2
    exit 1
  fi
  if ! (unset GARMIN_PERSONAL_API_KEY; source "$GARMIN_KEY_FILE" >/dev/null && [[ -n "${GARMIN_PERSONAL_API_KEY:-}" ]]); then
    echo "Garmin ist aktiviert, aber $GARMIN_KEY_FILE enthält keinen lesbaren, nicht-leeren GARMIN_PERSONAL_API_KEY." >&2
    exit 1
  fi
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

env -u GARMIN_PERSONAL_API_KEY docker compose -f "$ROOT_DIR/compose.yaml" up -d --wait db

if [[ "${GARMIN_PERSONAL_API_ENABLED:-false}" == true && -z "${GARMIN_PERSONAL_API_KEY:-}" ]]; then
  cd "$ROOT_DIR/backend"
  bash -c 'source "$1" && export GARMIN_PERSONAL_API_KEY && exec ./gradlew bootRun' _ "$GARMIN_KEY_FILE" &
else
  cd "$ROOT_DIR/backend"
  ./gradlew bootRun &
fi
backend_pid=$!
cd "$ROOT_DIR/frontend"
env -u GARMIN_PERSONAL_API_KEY npm run dev &
frontend_pid=$!
cd "$ROOT_DIR"

echo "Backend:  http://localhost:8080"
echo "Frontend: http://localhost:5173"
echo "Ctrl-C beendet Backend und Frontend; PostgreSQL bleibt gestartet."

wait -n "$backend_pid" "$frontend_pid"
