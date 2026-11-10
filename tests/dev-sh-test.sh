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
if sys.argv[1:] != ["--no-daemon", "bootRun"]: sys.exit(71)
with open(os.environ["EVENTS"], "a") as f: f.write("backend:" + os.environ.get("GARMIN_PERSONAL_API_KEY", "") + "\n")
open(os.environ["BACKEND_MARKER"], "w").close()
def stop(*_):
    open(os.environ["EVENTS"], "a").write("backend-stopped\n")
    os.unlink(os.environ["BACKEND_MARKER"])
    sys.exit(0)
if os.environ.get("EARLY_EXIT"):
    os.unlink(os.environ["BACKEND_MARKER"])
    sys.exit(0)
signal.signal(signal.SIGTERM, stop)
signal.signal(signal.SIGINT, stop)
while True: signal.pause()
MOCK
cat > "$TMP_DIR/bin/npm" <<'MOCK'
#!/usr/bin/env python3
import os, signal, sys
if sys.argv[1:] != ["run", "dev"]: sys.exit(73)
if os.environ.get("GARMIN_PERSONAL_API_KEY"): sys.exit(72)
with open(os.environ["EVENTS"], "a") as f: f.write("frontend\n")
signal.signal(signal.SIGTERM, lambda *_: (open(os.environ["EVENTS"], "a").write("frontend-stopped\n"), sys.exit(0)))
signal.signal(signal.SIGINT, lambda *_: (open(os.environ["EVENTS"], "a").write("frontend-stopped\n"), sys.exit(0)))
while True: signal.pause()
MOCK
cat > "$TMP_DIR/bin/curl" <<'MOCK'
#!/usr/bin/env bash
if grep -q '^backend:' "$EVENTS" && [[ "${HEALTH:-ok}" == ok ]]; then
  [[ -f "$BACKEND_MARKER" ]] || exit 7
  printf 'health-ready\n' >> "$EVENTS"
  exit 0
fi
printf 'health-fail\n' >> "$EVENTS"
exit 7
MOCK
cat > "$TMP_DIR/bin/sleep" <<'MOCK'
#!/usr/bin/env bash
[[ "$1" == 1 || "$1" == 0.1 ]]
MOCK
chmod +x "$TMP_DIR/bin/docker" "$TMP_DIR/bin/npm" "$TMP_DIR/bin/curl" "$TMP_DIR/bin/sleep" "$TMP_DIR/project/backend/gradlew"
export HOME="$TMP_DIR/home" EVENTS="$TMP_DIR/events" BACKEND_MARKER="$TMP_DIR/backend.running" PATH="$TMP_DIR/bin:$PATH"
cd "$TMP_DIR/project"

run_case() {
  local name="$1" expected_status="$2" signal_name="${3:-TERM}"
  shift 3
  : > "$EVENTS"
  python3 -c 'import os, signal, sys; signal.signal(signal.SIGINT, signal.SIG_DFL); os.setsid(); os.execvp(sys.argv[1], sys.argv[1:])' \
    env "$@" ./dev.sh >"$TMP_DIR/$name.out" 2>&1 &
  local pid=$!
  for _ in {1..200}; do
    if [[ "$expected_status" == running ]] && grep -q '^frontend$' "$EVENTS" 2>/dev/null; then break; fi
    if [[ "$expected_status" == fail ]] && ! kill -0 "$pid" 2>/dev/null; then break; fi
    kill -0 "$pid" 2>/dev/null || break
    /bin/sleep .05
  done
  if [[ "$expected_status" == running ]]; then
    grep -q '^frontend$' "$EVENTS"
    kill -s "$signal_name" "$pid"
    wait "$pid" || true
    grep -q '^backend-stopped$' "$EVENTS"
    grep -q '^frontend-stopped$' "$EVENTS"
    ! kill -0 -- "-$pid" 2>/dev/null
  else
    if wait "$pid"; then echo "Expected $name to fail" >&2; return 1; fi
    ! grep -q '^frontend$' "$EVENTS"
  fi
}

# Disabled Garmin never reads the key file; termination cleans up both app processes.
run_case disabled running TERM GARMIN_PERSONAL_API_ENABLED=false
[[ "$(grep -c '^backend:$' "$EVENTS")" == 1 ]]
grep -q '^frontend$' "$EVENTS"
backend_line=$(grep -n '^backend:' "$EVENTS" | cut -d: -f1)
health_line=$(grep -n '^health-ready$' "$EVENTS" | cut -d: -f1)
frontend_line=$(grep -n '^frontend$' "$EVENTS" | cut -d: -f1)
(( backend_line < health_line && health_line < frontend_line ))
grep -q 'PostgreSQL:    Ready' "$TMP_DIR/disabled.out"
grep -q 'Backend:       Ready' "$TMP_DIR/disabled.out"
grep -q 'Frontend:      Starting' "$TMP_DIR/disabled.out"
! grep -q 'Connected' "$TMP_DIR/disabled.out"

# A local env file is loaded; key file assignment reaches backend only, never output.
cat > "$TMP_DIR/project/.env" <<'ENV'
GARMIN_PERSONAL_API_ENABLED=true
INTERVALS_ICU_ENABLED=false
ENV
mkdir -p "$HOME/.config/sporttracker"
printf "GARMIN_PERSONAL_API_KEY='synthetic-test-key'\n" > "$HOME/.config/sporttracker/garmin-poc.env"
run_case loaded running INT
grep -q '^backend:synthetic-test-key$' "$EVENTS"
! grep -R 'synthetic-test-key' "$TMP_DIR/loaded.out"

# An already-exported key wins over the local key file.
printf "GARMIN_PERSONAL_API_KEY='synthetic-file-key'\n" > "$HOME/.config/sporttracker/garmin-poc.env"
run_case preferred running TERM GARMIN_PERSONAL_API_ENABLED=true GARMIN_PERSONAL_API_KEY=synthetic-env-key
grep -q '^backend:synthetic-env-key$' "$EVENTS"
grep -q 'Garmin:        Enabled (key available)' "$TMP_DIR/preferred.out"
grep -q 'Intervals.icu: Disabled' "$TMP_DIR/preferred.out"
rm "$HOME/.config/sporttracker/garmin-poc.env"

# A backend that exits early or never becomes healthy must not start the frontend.
cat > "$TMP_DIR/project/.env" <<'ENV'
GARMIN_PERSONAL_API_ENABLED=false
INTERVALS_ICU_ENABLED=false
ENV
run_case early-exit fail TERM GARMIN_PERSONAL_API_ENABLED=false EARLY_EXIT=true
[[ "$(grep -c '^health-ready$' "$EVENTS" || true)" == 0 ]]
run_case health-timeout fail TERM GARMIN_PERSONAL_API_ENABLED=false HEALTH=fail
[[ "$(grep -c '^health-fail$' "$EVENTS")" == 30 ]]

# Missing, empty, and unreadable-syntax keys fail before Docker starts.
cat > "$TMP_DIR/project/.env" <<'ENV'
GARMIN_PERSONAL_API_ENABLED=true
INTERVALS_ICU_ENABLED=false
ENV
run_case missing fail TERM GARMIN_PERSONAL_API_ENABLED=true
! grep -q '^docker$' "$EVENTS"
grep -qi 'key' "$TMP_DIR/missing.out"
printf "GARMIN_PERSONAL_API_KEY=''\n" > "$HOME/.config/sporttracker/garmin-poc.env"
run_case empty fail TERM GARMIN_PERSONAL_API_ENABLED=true
! grep -q '^docker$' "$EVENTS"
printf 'if\n' > "$HOME/.config/sporttracker/garmin-poc.env"
run_case invalid fail TERM GARMIN_PERSONAL_API_ENABLED=true
! grep -q '^docker$' "$EVENTS"
! grep -R 'synthetic-' "$TMP_DIR" --exclude=dev-sh-test.sh

echo 'dev.sh tests passed'
