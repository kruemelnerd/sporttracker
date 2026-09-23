#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BACKEND_PID=""
FRONTEND_PID=""

cleanup() {
  [[ -z "$FRONTEND_PID" ]] || kill -- -"$FRONTEND_PID" 2>/dev/null || true
  [[ -z "$BACKEND_PID" ]] || kill -- -"$BACKEND_PID" 2>/dev/null || true
}
trap cleanup EXIT

cd "$ROOT_DIR"
docker compose up -d db
until [[ "$(docker inspect --format '{{.State.Health.Status}}' "$(docker compose ps -q db)")" == healthy ]]; do
  sleep 2
done

setsid bash -c 'cd backend && GRADLE_OPTS="${GRADLE_OPTS:-} -Djdk.lang.Process.launchMechanism=VFORK" exec ./gradlew --no-daemon bootRun' &
BACKEND_PID=$!
setsid bash -c 'cd frontend && exec npm run dev -- --host 127.0.0.1' &
FRONTEND_PID=$!

until curl --fail --silent http://127.0.0.1:8080/api/fixture-summary >/dev/null; do sleep 2; done
until curl --fail --silent http://127.0.0.1:5173/ >/dev/null; do sleep 2; done
docker compose exec -T db psql -U running_dashboard -d running_dashboard -c 'TRUNCATE activity, import_file, activity_record, lap RESTART IDENTITY CASCADE' >/dev/null

cd frontend
npx playwright test --config=playwright.config.ts
