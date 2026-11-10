#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

mkdir -p "$TMP_DIR/project/backend" "$TMP_DIR/project/frontend" "$TMP_DIR/bin" "$TMP_DIR/home"
cp "$ROOT_DIR/dev.sh" "$TMP_DIR/project/dev.sh"
cp "$ROOT_DIR/compose.yaml" "$TMP_DIR/project/compose.yaml"
cat > "$TMP_DIR/bin/docker" <<'MOCK'
#!/usr/bin/env bash
[[ -z "${GARMIN_PERSONAL_API_KEY:-}" ]] || exit 70
printf 'docker\n' >> "$EVENTS"
MOCK
cat > "$TMP_DIR/project/backend/gradlew" <<'MOCK'
#!/usr/bin/env python3
import os, signal, sys
if sys.argv[1:] != ["bootRun"]: sys.exit(71)
with open(os.environ["EVENTS"], "a") as f: f.write("backend:" + os.environ.get("GARMIN_PERSONAL_API_KEY", "") + "\n")
signal.signal(signal.SIGTERM, lambda *_: (open(os.environ["EVENTS"], "a").write("backend-stopped\n"), sys.exit(0)))
while True: signal.pause()
MOCK
cat > "$TMP_DIR/bin/npm" <<'MOCK'
#!/usr/bin/env python3
import os, signal
if os.environ.get("GARMIN_PERSONAL_API_KEY"): sys.exit(72)
with open(os.environ["EVENTS"], "a") as f: f.write("frontend\n")
signal.signal(signal.SIGTERM, lambda *_: (open(os.environ["EVENTS"], "a").write("frontend-stopped\n"), sys.exit(0)))
while True: signal.pause()
MOCK
chmod +x "$TMP_DIR/bin/docker" "$TMP_DIR/bin/npm" "$TMP_DIR/project/backend/gradlew"
export HOME="$TMP_DIR/home" EVENTS="$TMP_DIR/events" PATH="$TMP_DIR/bin:$PATH"
cd "$TMP_DIR/project"

run_case() {
  local name="$1" expected_status="$2"
  shift 2
  : > "$EVENTS"
  env "$@" ./dev.sh >"$TMP_DIR/$name.out" 2>&1 &
  local pid=$!
  for _ in {1..100}; do
    [[ -f "$EVENTS" ]] && grep -q '^backend:' "$EVENTS" 2>/dev/null && break
    kill -0 "$pid" 2>/dev/null || break
    sleep .05
  done
  if [[ "$expected_status" == running ]]; then
    kill -TERM "$pid"
    wait "$pid" || true
    grep -q '^backend-stopped$' "$EVENTS"
    grep -q '^frontend-stopped$' "$EVENTS"
  else
    if wait "$pid"; then echo "Expected $name to fail" >&2; return 1; fi
  fi
}

# Disabled Garmin never reads the key file; termination cleans up both app processes.
run_case disabled running GARMIN_PERSONAL_API_ENABLED=false
[[ "$(grep -c '^backend:$' "$EVENTS")" == 1 ]]
grep -q '^frontend$' "$EVENTS"

# A local env file is loaded; key file assignment reaches backend only, never output.
cat > "$TMP_DIR/project/.env" <<'ENV'
GARMIN_PERSONAL_API_ENABLED=true
INTERVALS_ICU_ENABLED=false
ENV
mkdir -p "$HOME/.config/sporttracker"
printf "GARMIN_PERSONAL_API_KEY='synthetic-test-key'\n" > "$HOME/.config/sporttracker/garmin-poc.env"
run_case loaded running
grep -q '^backend:synthetic-test-key$' "$EVENTS"
! grep -R 'synthetic-test-key' "$TMP_DIR/loaded.out"

# An already-exported key wins over the local key file.
rm "$HOME/.config/sporttracker/garmin-poc.env"
run_case preferred running GARMIN_PERSONAL_API_ENABLED=true GARMIN_PERSONAL_API_KEY=synthetic-env-key
grep -q '^backend:synthetic-env-key$' "$EVENTS"

# Missing, empty, and unreadable-syntax keys fail before Docker starts.
run_case missing fail GARMIN_PERSONAL_API_ENABLED=true
! grep -q '^docker$' "$EVENTS"
grep -qi 'key' "$TMP_DIR/missing.out"
printf "GARMIN_PERSONAL_API_KEY=''\n" > "$HOME/.config/sporttracker/garmin-poc.env"
run_case empty fail GARMIN_PERSONAL_API_ENABLED=true
! grep -q '^docker$' "$EVENTS"
printf 'if\n' > "$HOME/.config/sporttracker/garmin-poc.env"
run_case invalid fail GARMIN_PERSONAL_API_ENABLED=true
! grep -q '^docker$' "$EVENTS"
! grep -R 'synthetic-' "$TMP_DIR" --exclude=dev-sh-test.sh

echo 'dev.sh tests passed'
