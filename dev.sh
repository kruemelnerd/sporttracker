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

stop_process_group() {
  local pid="$1"
  [[ -n "$pid" ]] || return 0
  kill -TERM -- "-$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true
  for _ in {1..50}; do
    kill -0 -- "-$pid" 2>/dev/null || break
    sleep 0.1
  done
  kill -KILL -- "-$pid" 2>/dev/null || true
}

cleanup() {
  local status=$?
  trap - EXIT INT TERM
  stop_process_group "$frontend_pid"
  stop_process_group "$backend_pid"
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

env -u GARMIN_PERSONAL_API_KEY docker compose -f "$ROOT_DIR/compose.yaml" up -d --wait db
printf 'PostgreSQL:    Ready\n'

if [[ "${GARMIN_PERSONAL_API_ENABLED:-false}" == true && -z "${GARMIN_PERSONAL_API_KEY:-}" ]]; then
  cd "$ROOT_DIR/backend"
  setsid bash -c 'source "$1" && export GARMIN_PERSONAL_API_KEY && exec ./gradlew --no-daemon bootRun' _ "$GARMIN_KEY_FILE" &
else
  cd "$ROOT_DIR/backend"
  setsid ./gradlew --no-daemon bootRun &
fi
backend_pid=$!

backend_ready=false
for attempt in {1..30}; do
  backend_state=$(ps -o stat= -p "$backend_pid" 2>/dev/null || true)
  if [[ -z "$backend_state" || "$backend_state" == *Z* ]]; then
    echo 'Backend exited before becoming ready.' >&2
    exit 1
  fi
  if curl --silent --show-error --fail --connect-timeout 1 --max-time 1 --output /dev/null \
      http://127.0.0.1:8080/api/health; then
    backend_ready=true
    break
  fi
  (( attempt == 30 )) || sleep 1
done
if [[ "$backend_ready" != true ]]; then
  echo 'Backend healthcheck timed out after 60 seconds.' >&2
  exit 1
fi
printf 'Backend:       Ready\n'
if [[ "${GARMIN_PERSONAL_API_ENABLED:-false}" == true ]]; then
  if [[ -n "${GARMIN_PERSONAL_API_KEY:-}" ]] || [[ -r "$GARMIN_KEY_FILE" ]]; then
    printf 'Garmin:        Enabled (key available)\n'
  else
    printf 'Garmin:        Enabled (key missing)\n'
  fi
else
  printf 'Garmin:        Disabled\n'
fi
if [[ "${INTERVALS_ICU_ENABLED:-false}" == true ]]; then
  printf 'Intervals.icu: Enabled\n'
else
  printf 'Intervals.icu: Disabled\n'
fi
printf 'Frontend:      Starting\n'
printf '\nBackend:  http://localhost:8080\nFrontend: http://localhost:5173\n'

cd "$ROOT_DIR/frontend"
setsid env -u GARMIN_PERSONAL_API_KEY npm run dev &
frontend_pid=$!
echo 'Ctrl-C beendet Backend und Frontend; PostgreSQL bleibt gestartet.'

wait -n "$backend_pid" "$frontend_pid"
