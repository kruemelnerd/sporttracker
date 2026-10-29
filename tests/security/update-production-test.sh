#!/usr/bin/env bash
set -Eeuo pipefail

root=$(cd "$(dirname "$0")/../.." && pwd -P)
tmp=$(mktemp -d)
trap 'rm -rf -- "$tmp"' EXIT
mkdir -p "$tmp/repo/scripts" "$tmp/bin" "$tmp/backups"
cp "$root/scripts/update-production.sh" "$tmp/repo/scripts/update-production.sh"
printf '# test Compose file\n' > "$tmp/repo/compose.production.yaml"
printf 'SPORTTRACKER_IMAGE=ghcr.io/kruemelnerd/sporttracker\nSPORTTRACKER_IMAGE_DIGEST=sha256:%064d\n' 0 > "$tmp/production.env"
chmod 600 "$tmp/production.env"
chmod 700 "$tmp/backups"

cat > "$tmp/bin/docker" <<'SH'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$CALL_LOG"
case "$*" in
  'context inspect default --format {{.Endpoints.docker.Host}}') echo 'unix:///var/run/docker.sock' ;;
  *' ps -q app') echo 'fixture-app' ;;
  'inspect -f {{.State.Status}} fixture-app') echo 'running' ;;
  'inspect -f {{.State.Health.Status}} fixture-app') echo 'healthy' ;;
  'inspect -f {{.Config.Image}} fixture-app') printf 'ghcr.io/kruemelnerd/sporttracker@sha256:%064d\n' 0 ;;
  *) exit 0 ;;
esac
SH
cat > "$tmp/repo/scripts/backup.sh" <<'SH'
#!/usr/bin/env bash
echo 'backup' >> "$EVENT_LOG"
[[ ${BACKUP_FAIL:-0} == 0 ]]
SH
cat > "$tmp/repo/scripts/deploy-verified.sh" <<'SH'
#!/usr/bin/env bash
echo 'deploy' >> "$EVENT_LOG"
[[ ${DEPLOY_FAIL:-0} == 0 ]]
SH
chmod +x "$tmp/bin/docker" "$tmp/repo/scripts/"*.sh
export CALL_LOG="$tmp/docker.log" EVENT_LOG="$tmp/events.log"
: > "$CALL_LOG"; : > "$EVENT_LOG"
export PATH="$tmp/bin:$PATH"
image=ghcr.io/kruemelnerd/sporttracker@sha256:5bbc988288cf286507f0c6a7cd1c38ecf8c977c97d154ed21ce939a07837daf5
run() {
  bash "$tmp/repo/scripts/update-production.sh" --env-file "$tmp/production.env" --project sporttracker-prod --backup-dir "$tmp/backups" "$1"
}
assert_no_events() {
  [[ ! -s $EVENT_LOG ]] || { echo "Unexpected backup/deploy: $(cat "$EVENT_LOG")" >&2; exit 1; }
}

# Reject tag and wrong production project without accessing docker or backup.
if run ghcr.io/kruemelnerd/sporttracker:stable > /dev/null 2>&1; then
  echo 'Accepted mutable tag' >&2; exit 1
fi
assert_no_events
if bash "$tmp/repo/scripts/update-production.sh" --env-file "$tmp/production.env" --project other --backup-dir "$tmp/backups" "$image" >/dev/null 2>&1; then
  echo 'Accepted an unexpected Compose project' >&2; exit 1
fi
assert_no_events

# No-op when requested digest is already deployed, no interruption or backup.
current_image=ghcr.io/kruemelnerd/sporttracker@sha256:$(printf '%064d' 0)
run "$current_image" >/dev/null
assert_no_events

# A failed backup absolutely forbids deploy.
if BACKUP_FAIL=1 run "$image" >/dev/null 2>&1; then
  echo 'Deployment continued after a failed backup' >&2; exit 1
fi
[[ $(cat "$EVENT_LOG") == backup ]] || { echo 'Backup failure triggered deployment' >&2; exit 1; }
: > "$EVENT_LOG"

# Normal update always invokes backup first.
run "$image" >/dev/null
[[ $(cat "$EVENT_LOG") == $'backup\ndeploy' ]] || { echo 'Missing or out-of-order backup/deployment' >&2; exit 1; }
: > "$EVENT_LOG"

# Deployment failures propagate and do not retry backups.
if DEPLOY_FAIL=1 run "$image" >/dev/null 2>&1; then
  echo 'Reported success after deployment failure' >&2; exit 1
fi
[[ $(cat "$EVENT_LOG") == $'backup\ndeploy' ]] || exit 1
: > "$EVENT_LOG"

# Prevent a second update while lock is held.
(
  exec 9>"$tmp/backups/.update.lock"
  flock -n 9
  touch "$tmp/locked"
  sleep 2
) &
holder=$!
for _ in {1..100}; do [[ -e $tmp/locked ]] && break; sleep 0.02; done
[[ -e $tmp/locked ]] || { echo 'Could not establish fixture lock' >&2; exit 1; }
if run "$image" > /dev/null 2>&1; then
  echo 'Allowed concurrent update' >&2; exit 1
fi
assert_no_events
wait "$holder"

# No interaction with production Docker daemon or volumes: stubbed all entry points.
echo 'PASS: mandatory backup precedes deployment, failures block, no-op and concurrency guards work (isolated stubs)'
