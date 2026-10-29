#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
KEEP_ON_FAILURE=false
case "${1:-}" in
  "") ;;
  --keep-on-failure) KEEP_ON_FAILURE=true ;;
  *) echo "Usage: $0 [--keep-on-failure]" >&2; exit 2 ;;
esac

for name in TEST_DATABASE_URL TEST_DATABASE_NAME TEST_DATABASE_USERNAME TEST_DATABASE_PASSWORD TEST_DATABASE_PORT \
  TEST_RUN_ID TEST_COMPOSE_PROJECT TEST_BACKEND_URL TEST_FRONTEND_URL TEST_DATA_DIRECTORY \
  DATABASE_URL DATABASE_USERNAME DATABASE_PASSWORD SPRING_DATASOURCE_URL SPRING_DATASOURCE_USERNAME \
  SPRING_DATASOURCE_PASSWORD SPRING_APPLICATION_JSON; do
  if [[ -n "${!name:-}" ]]; then
    echo "$name must not be set; this runner owns the isolated test connection" >&2
    exit 2
  fi
done

for command in docker git java jq node npm openssl python3; do
  command -v "$command" >/dev/null || { echo "Required command not found: $command" >&2; exit 2; }
done
docker compose version >/dev/null
docker info >/dev/null 2>&1 || { echo 'Docker daemon is unavailable' >&2; exit 2; }
[[ "$(git -C "$ROOT_DIR" rev-parse --show-toplevel)" == "$ROOT_DIR" ]] || { echo 'Could not verify repository root' >&2; exit 2; }

TEST_RUN_ID=$(openssl rand -hex 12)
TEST_COMPOSE_PROJECT="sporttracker-ci-$TEST_RUN_ID"
TEST_DATABASE_NAME="running_dashboard_test_$TEST_RUN_ID"
TEST_DATABASE_USERNAME="$TEST_DATABASE_NAME"
TEST_DATABASE_PASSWORD=$(openssl rand -hex 24)
TEST_DATA_DIRECTORY="/tmp/running-dashboard-test-$TEST_RUN_ID"
TEST_COMPOSE_FILE="$ROOT_DIR/compose.test.yaml"
export TEST_RUN_ID TEST_COMPOSE_PROJECT TEST_DATABASE_NAME TEST_DATABASE_USERNAME TEST_DATABASE_PASSWORD TEST_DATA_DIRECTORY
COMPOSE=(docker compose -p "$TEST_COMPOSE_PROJECT" -f "$TEST_COMPOSE_FILE")
RESULT=FAIL
STEPS=()
RESOURCES_MAY_EXIST=false
[[ ! -e "$TEST_DATA_DIRECTORY" ]] || { echo 'Unexpected existing test data directory; refusing to continue' >&2; exit 2; }

for resource in containers networks volumes; do
  case "$resource" in
    containers) ids=$(docker ps -aq --filter "label=com.docker.compose.project=$TEST_COMPOSE_PROJECT") ;;
    networks) ids=$(docker network ls -q --filter "label=com.docker.compose.project=$TEST_COMPOSE_PROJECT") ;;
    volumes) ids=$(docker volume ls -q --filter "label=com.docker.compose.project=$TEST_COMPOSE_PROJECT") ;;
  esac
  [[ -z "$ids" ]] || { echo "Compose project collision detected ($resource); refusing to continue" >&2; exit 2; }
done

get_loopback_port() {
  # shortcut: a probed port can be claimed before bind; reserve ports across startup if strictPort retries become noisy.
  node -e 'const net = require("node:net"); const server = net.createServer(); server.listen(0, "127.0.0.1", () => { console.log(server.address().port); server.close(); });'
}

db() {
  "${COMPOSE[@]}" exec -T db psql -X -v ON_ERROR_STOP=1 -U "$TEST_DATABASE_USERNAME" -d "$TEST_DATABASE_NAME" "$@"
}

start_database() {
  docker compose -f "$TEST_COMPOSE_FILE" config --quiet
  RESOURCES_MAY_EXIST=true
  "${COMPOSE[@]}" up -d --wait --wait-timeout 90 db
  local port_line container_id container_project container_service
  port_line=$("${COMPOSE[@]}" port db 5432)
  [[ "$port_line" =~ ^127\.0\.0\.1:([0-9]{1,5})$ ]] || { echo 'Database port is not bound exclusively to IPv4 loopback' >&2; return 1; }
  TEST_DATABASE_PORT=${BASH_REMATCH[1]}
  (( TEST_DATABASE_PORT > 0 && TEST_DATABASE_PORT <= 65535 )) || { echo 'Docker returned an invalid database port' >&2; return 1; }
  TEST_DATABASE_URL="jdbc:postgresql://127.0.0.1:$TEST_DATABASE_PORT/$TEST_DATABASE_NAME"
  TEST_BACKEND_PORT=$(get_loopback_port)
  TEST_FRONTEND_PORT=$(get_loopback_port)
  while [[ "$TEST_BACKEND_PORT" == "$TEST_FRONTEND_PORT" ]]; do TEST_FRONTEND_PORT=$(get_loopback_port); done
  TEST_BACKEND_URL="http://127.0.0.1:$TEST_BACKEND_PORT"
  TEST_FRONTEND_URL="http://127.0.0.1:$TEST_FRONTEND_PORT"
  export TEST_DATABASE_PORT TEST_DATABASE_URL TEST_BACKEND_URL TEST_FRONTEND_URL
  export INTERVALS_ICU_ENABLED=false INTERVALS_ICU_API_KEY=
  container_id=$("${COMPOSE[@]}" ps -q db)
  [[ -n "$container_id" ]] || { echo 'Test database container is missing' >&2; return 1; }
  container_project=$(docker inspect --format '{{index .Config.Labels "com.docker.compose.project"}}' "$container_id")
  container_service=$(docker inspect --format '{{index .Config.Labels "com.docker.compose.service"}}' "$container_id")
  [[ "$container_project" == "$TEST_COMPOSE_PROJECT" && "$container_service" == db ]] || { echo 'Test database container ownership mismatch' >&2; return 1; }
  local -a volumes
  mapfile -t volumes < <(docker volume ls -q --filter "label=com.docker.compose.project=$TEST_COMPOSE_PROJECT")
  [[ "${#volumes[@]}" == 1 && "${volumes[0]}" == "${TEST_COMPOSE_PROJECT}_test-postgres" ]] || {
    echo 'Test database volume ownership mismatch' >&2
    return 1
  }
  [[ "$(docker volume inspect --format '{{index .Labels "com.docker.compose.project"}}' "${volumes[0]}")" == "$TEST_COMPOSE_PROJECT" &&
     "$(docker volume inspect --format '{{index .Labels "com.docker.compose.volume"}}' "${volumes[0]}")" == test-postgres ]] || {
    echo 'Test database volume labels do not match this run' >&2
    return 1
  }
  db -c "COMMENT ON DATABASE \"$TEST_DATABASE_NAME\" IS 'sporttracker-test:$TEST_RUN_ID:$TEST_COMPOSE_PROJECT'" >/dev/null
  local identity_sql="DO \$\$ BEGIN
    IF current_database() <> '$TEST_DATABASE_NAME' OR current_user <> '$TEST_DATABASE_USERNAME' OR
       (SELECT shobj_description(oid, 'pg_database') FROM pg_database WHERE datname = current_database()) <> 'sporttracker-test:$TEST_RUN_ID:$TEST_COMPOSE_PROJECT' THEN
      RAISE EXCEPTION 'Test database identity mismatch';
    END IF;
  END \$\$;"
  db -c "$identity_sql" >/dev/null
  STEPS+=("PASS: Create and verify isolated PostgreSQL ($TEST_COMPOSE_PROJECT)")
}

run_step() {
  local label=$1
  shift
  echo "==> $label"
  if "$@"; then
    STEPS+=("PASS: $label")
  else
    local status=$?
    STEPS+=("FAIL: $label (exit $status)")
    RESULT=FAIL
    return "$status"
  fi
}

verify_reset_guard() {
  db -c 'CREATE TABLE public.activity (id integer PRIMARY KEY); INSERT INTO public.activity VALUES (1)' >/dev/null
  db -c "COMMENT ON DATABASE \"$TEST_DATABASE_NAME\" IS 'invalid-test-marker'" >/dev/null
  local output count
  if output=$(cd "$ROOT_DIR/backend" && ./gradlew test 2>&1); then
    echo 'Gradle unexpectedly accepted a database with a mismatched run marker' >&2
    return 1
  fi
  [[ "$output" == *'Refusing to reset backend test database'* ]] || {
    echo 'Gradle rejected the negative reset check for an unexpected reason' >&2
    printf '%s\n' "$output" | tail -20 >&2
    return 1
  }
  count=$(db -Atc 'SELECT count(*) FROM public.activity')
  [[ "$count" == 1 ]] || { echo 'A rejected reset changed its sentinel row' >&2; return 1; }
  db -c 'DROP TABLE public.activity' >/dev/null
  db -c "COMMENT ON DATABASE \"$TEST_DATABASE_NAME\" IS 'sporttracker-test:$TEST_RUN_ID:$TEST_COMPOSE_PROJECT'" >/dev/null
}

run_release_security_tests() {
  python3 -m unittest discover -s "$ROOT_DIR/tests/security" -p 'test_install_host_tools.py' &&
    "$ROOT_DIR/tests/security/verify-release-test.sh" &&
    "$ROOT_DIR/tests/security/verify-release-policy-test.sh" &&
    "$ROOT_DIR/tests/security/deploy-verified-test.sh" &&
    "$ROOT_DIR/tests/security/proxmox-preflight-test.sh"
}

run_backend_tests() { (cd "$ROOT_DIR/backend" && ./gradlew test); }
run_playwright_tests() { (cd "$ROOT_DIR/frontend" && npx playwright test --config=playwright.config.ts); }

run_tests() {
  run_step 'Release verification, deployment, and VM preflight security tests' run_release_security_tests || return $?
  run_step 'Reject mismatched database identity before reset' verify_reset_guard || return $?
  run_step 'Backend Gradle test suite' run_backend_tests || return $?
  run_step 'Frontend dependency install' npm --prefix "$ROOT_DIR/frontend" ci || return $?
  run_step 'Frontend TypeScript typecheck' npm --prefix "$ROOT_DIR/frontend" run typecheck || return $?
  run_step 'Frontend unit tests' npm --prefix "$ROOT_DIR/frontend" run test:unit || return $?
  run_step 'Frontend production build' npm --prefix "$ROOT_DIR/frontend" run build || return $?
  run_step 'Playwright Chromium installation' npm --prefix "$ROOT_DIR/frontend" exec playwright -- install chromium || return $?
  run_step 'Playwright E2E suite' run_playwright_tests || return $?
}

cleanup_owned_resources() {
  [[ "$RESOURCES_MAY_EXIST" == true ]] || return 0
  local failed=0 volume label compose_volume attached network network_label network_name
  local -a volumes containers networks
  mapfile -t containers < <(docker ps -aq --filter "label=com.docker.compose.project=$TEST_COMPOSE_PROJECT")
  for container in "${containers[@]}"; do
    label=$(docker inspect --format '{{index .Config.Labels "com.docker.compose.project"}}' "$container") || return 1
    [[ "$label" == "$TEST_COMPOSE_PROJECT" && "$(docker inspect --format '{{index .Config.Labels "com.docker.compose.service"}}' "$container")" == db ]] || {
      echo "Refusing to remove unverified container: $container" >&2
      return 1
    }
  done
  mapfile -t networks < <(docker network ls -q --filter "label=com.docker.compose.project=$TEST_COMPOSE_PROJECT")
  for network in "${networks[@]}"; do
    network_label=$(docker network inspect --format '{{index .Labels "com.docker.compose.project"}}' "$network") || return 1
    network_name=$(docker network inspect --format '{{.Name}}' "$network") || return 1
    [[ "$network_label" == "$TEST_COMPOSE_PROJECT" && "$network_name" == "${TEST_COMPOSE_PROJECT}_default" ]] || {
      echo "Refusing to remove unverified network: $network" >&2
      return 1
    }
  done
  mapfile -t volumes < <(docker volume ls -q --filter "label=com.docker.compose.project=$TEST_COMPOSE_PROJECT")
  for volume in "${volumes[@]}"; do
    label=$(docker volume inspect --format '{{index .Labels "com.docker.compose.project"}}' "$volume") || return 1
    compose_volume=$(docker volume inspect --format '{{index .Labels "com.docker.compose.volume"}}' "$volume") || return 1
    case "$volume" in
      sporttracker_running-dashboard-postgres|sporttracker-m4-e2e_sporttracker-m4-e2e-postgres|sporttracker-m81-test-postgres|sporttracker-m81-test-fit)
        echo "Refusing to remove protected volume: $volume" >&2
        return 1
        ;;
    esac
    [[ "$label" == "$TEST_COMPOSE_PROJECT" && "$compose_volume" == test-postgres && "$volume" == "${TEST_COMPOSE_PROJECT}_test-postgres" ]] || {
      echo "Refusing to remove unverified volume: $volume" >&2
      return 1
    }
  done
  "${COMPOSE[@]}" down || return 1
  for volume in "${volumes[@]}"; do
    attached=$(docker ps -aq --filter "volume=$volume") || return 1
    [[ -z "$attached" ]] || { echo "Refusing to remove attached test volume: $volume" >&2; return 1; }
    docker volume rm "$volume" >/dev/null || failed=1
  done
  if [[ -e "$TEST_DATA_DIRECTORY" || -L "$TEST_DATA_DIRECTORY" ]]; then
    [[ ! -L "$TEST_DATA_DIRECTORY" && "$TEST_DATA_DIRECTORY" == "/tmp/running-dashboard-test-$TEST_RUN_ID" ]] || {
      echo 'Refusing to remove unverified test data directory' >&2
      return 1
    }
    rm -rf -- "$TEST_DATA_DIRECTORY"
  fi
  return "$failed"
}

finish() {
  local status=$?
  trap - EXIT
  if [[ "$RESULT" == FAIL && "$KEEP_ON_FAILURE" == true ]]; then
    echo "Keeping this run's isolated resources for debugging: $TEST_COMPOSE_PROJECT"
  else
    if ! cleanup_owned_resources; then
      echo "Failed to safely clean up this run's test resources" >&2
      status=1
      RESULT=FAIL
    fi
  fi
  echo "Test run $TEST_RUN_ID: $RESULT"
  printf '%s\n' "${STEPS[@]}"
  exit "$status"
}

trap finish EXIT
start_database
RESULT=PASS
if ! run_tests; then
  RESULT=FAIL
  exit 1
fi
