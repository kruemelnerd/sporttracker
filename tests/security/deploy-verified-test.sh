#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "$0")/../.." && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
mkdir "$tmp/bin"
printf 'SPORTTRACKER_IMAGE=ghcr.io/kruemelnerd/sporttracker\nSPORTTRACKER_IMAGE_DIGEST=sha256:%064d\n' 0 >"$tmp/compose.env"
chmod 600 "$tmp/compose.env"
cat >"$tmp/bin/docker" <<'SH'
#!/usr/bin/env bash
printf '%s\n' "$*" >>"$DOCKER_CALL_LOG"
if [[ $1 == pull ]]; then exit 0; fi
if [[ $1 == image && $2 == inspect ]]; then
  case "$5" in
    *revision*) printf '%s\n' 'b9af77982ba94cd8aca03207c4241370acfc006a' ;;
    *source*) printf '%s\n' 'https://github.com/kruemelnerd/sporttracker' ;;
  esac
  exit 0
fi
if [[ $1 == volume && $2 == inspect ]]; then exit 0; fi
if [[ $1 == compose && " $* " == *' up -d --wait --wait-timeout 180 app '* ]]; then
  printf '%s\n' "${SPORTTRACKER_IMAGE_DIGEST:-}" >>"$DOCKER_ENV_LOG"
fi
if [[ $1 == compose ]]; then printf '%s\n' "$*" >>"$DOCKER_COMPOSE_LOG"; fi
if [[ $1 == compose && " $* " == *' up -d --wait --wait-timeout 180 app '* && ${COMPOSE_UP_FAIL:-} == true ]]; then exit 1; fi
exit 0
SH
cat >"$tmp/bin/gh" <<'SH'
#!/usr/bin/env bash
if [[ $1 == attestation && $2 == verify && $3 == --help ]]; then exit 0; fi
[[ ${VERIFY_SUCCESS:-} == true ]] || exit 1
predicate_type=https://slsa.dev/provenance/v1
if [[ " $* " == *' https://spdx.dev/Document/v2.3 '* ]]; then predicate_type=https://spdx.dev/Document/v2.3; fi
predicate='{}'
[[ $predicate_type != https://spdx.dev/Document/v2.3 ]] || predicate='{"spdxVersion":"SPDX-2.3","SPDXID":"SPDXRef-DOCUMENT","name":"fixture","dataLicense":"CC0-1.0","documentNamespace":"https://example.test/spdx/fixture","creationInfo":{"created":"2026-10-25T00:00:00Z","creators":["Tool: fixture"]},"packages":[{"SPDXID":"SPDXRef-Package-fixture","name":"fixture","downloadLocation":"NOASSERTION","filesAnalyzed":false,"licenseConcluded":"NOASSERTION","licenseDeclared":"NOASSERTION","copyrightText":"NOASSERTION"}]}'
jq -cn --arg type "$predicate_type" --argjson predicate "$predicate" '[{verificationResult:{statement:{predicateType:$type,subject:[{digest:{sha256:"5bbc988288cf286507f0c6a7cd1c38ecf8c977c97d154ed21ce939a07837daf5"}}],predicate:$predicate}}}]'
SH
cat >"$tmp/bin/trivy" <<'SH'
#!/usr/bin/env bash
[[ ${VERIFY_SUCCESS:-} == true ]] || exit 1
exit 0
SH
chmod +x "$tmp/bin/"*
real_stat=$(command -v stat)
cat >"$tmp/bin/stat" <<'SH'
#!/usr/bin/env bash
if [[ ${1:-} == -c && ${2:-} == %u && ${3:-} == -- && ${4:-} == "${GARMIN_TEST_KEY:-}" ]]; then
  echo 10001
else
  exec "$REAL_STAT" "$@"
fi
SH
chmod +x "$tmp/bin/stat"
export DOCKER_CALL_LOG="$tmp/docker.log"
export DOCKER_ENV_LOG="$tmp/docker-env.log"
export DOCKER_COMPOSE_LOG="$tmp/docker-compose.log"
export REAL_STAT="$real_stat"
touch "$DOCKER_CALL_LOG"
image=ghcr.io/kruemelnerd/sporttracker@sha256:5bbc988288cf286507f0c6a7cd1c38ecf8c977c97d154ed21ce939a07837daf5

if PATH="$tmp/bin:$PATH" "$root/scripts/deploy-verified.sh" --env-file "$tmp/compose.env" --project m831-test ghcr.io/kruemelnerd/sporttracker:stable >/dev/null 2>&1; then
  echo 'Deployment accepted a mutable tag' >&2
  exit 1
fi
if PATH="$tmp/bin:$PATH" "$root/scripts/deploy-verified.sh" --env-file "$tmp/compose.env" --project m831-test "$image" >/dev/null 2>&1; then
  echo 'Deployment continued after release verification failed' >&2
  exit 1
fi
grep -q 'SPORTTRACKER_IMAGE_DIGEST=sha256:'"$(printf '%064d' 0)"'$' "$tmp/compose.env" || { echo 'Failed verification changed the saved digest' >&2; exit 1; }
grep -q '^pull ' "$DOCKER_CALL_LOG" || { echo 'Deployment did not invoke release verification' >&2; exit 1; }
if grep -q 'compose ' "$DOCKER_CALL_LOG"; then
  echo 'Compose was invoked before release verification succeeded' >&2
  exit 1
fi
PATH="$tmp/bin:$PATH" VERIFY_SUCCESS=true SPORTTRACKER_IMAGE_DIGEST="sha256:$(printf '%064d' 0)" "$root/scripts/deploy-verified.sh" --env-file "$tmp/compose.env" --project m831-test "$image" >/dev/null
grep -Fxq "${image##*@}" "$DOCKER_ENV_LOG" || { echo 'Compose did not receive the verified digest over an inherited old digest' >&2; exit 1; }
grep -Fxq "SPORTTRACKER_IMAGE_DIGEST=${image##*@}" "$tmp/compose.env" || { echo 'Deployment did not persist the verified digest' >&2; exit 1; }
compgen -G "$tmp/compose.env.previous.*" >/dev/null || { echo 'Previous Compose configuration was not retained' >&2; exit 1; }
grep -q 'compose .*config --quiet' "$DOCKER_CALL_LOG" || { echo 'Deployment did not validate Compose configuration' >&2; exit 1; }
grep -q 'volume inspect sporttracker-prod-postgres' "$DOCKER_CALL_LOG" || { echo 'Deployment did not protect/check the PostgreSQL volume' >&2; exit 1; }
grep -q 'volume inspect sporttracker-prod-fit' "$DOCKER_CALL_LOG" || { echo 'Deployment did not protect/check the FIT volume' >&2; exit 1; }
grep -q 'compose .*up -d --wait --wait-timeout 180 app' "$DOCKER_CALL_LOG" || { echo 'Deployment did not wait for app readiness' >&2; exit 1; }
if grep -q 'compose.production.garmin.yaml' "$DOCKER_COMPOSE_LOG"; then echo 'Base deployment unexpectedly enabled Garmin' >&2; exit 1; fi
if grep -q 'restoring the previous digest\|compose .*rollback' "$DOCKER_CALL_LOG"; then
  echo 'Deployment attempted an unsafe automatic image rollback' >&2
  exit 1
fi

failure_log=$tmp/failure.log
if PATH="$tmp/bin:$PATH" VERIFY_SUCCESS=true COMPOSE_UP_FAIL=true "$root/scripts/deploy-verified.sh" --env-file "$tmp/compose.env" --project m831-test "$image" >"$failure_log" 2>&1; then
  echo 'Deployment reported success after a readiness failure' >&2
  exit 1
fi
grep -q 'no automatic image rollback.*Flyway' "$failure_log" || { echo 'Readiness failure did not require manual database-aware recovery' >&2; exit 1; }

# Garmin mode is opt-in, persistent in the private env, and the override remains attached to the deployment.
printf 'GARMIN_PRODUCTION_ENABLED=true\nGARMIN_PERSONAL_API_KEY_FILE=%s\n' "$tmp/garmin-key" >>"$tmp/compose.env"
printf 'fixture-secret\n' >"$tmp/garmin-key"
chmod 400 "$tmp/garmin-key"
export GARMIN_TEST_KEY="$tmp/garmin-key"
cp "$tmp/compose.env" "$tmp/disabled-garmin.env"
sed -i 's/GARMIN_PRODUCTION_ENABLED=true/GARMIN_PRODUCTION_ENABLED=false/' "$tmp/disabled-garmin.env"
PATH="$tmp/bin:$PATH" VERIFY_SUCCESS=true "$root/scripts/deploy-verified.sh" --env-file "$tmp/disabled-garmin.env" --project m831-test "$image" >/dev/null
[[ $(grep -c 'compose.production.garmin.yaml' "$DOCKER_COMPOSE_LOG" || true) == 0 ]] || { echo 'A key alone activated Garmin' >&2; exit 1; }
cp "$tmp/compose.env" "$tmp/missing-key.env"
sed -i '/GARMIN_PERSONAL_API_KEY_FILE=/d' "$tmp/missing-key.env"
if PATH="$tmp/bin:$PATH" VERIFY_SUCCESS=true "$root/scripts/deploy-verified.sh" --env-file "$tmp/missing-key.env" --project m831-test "$image" >"$tmp/missing-key.log" 2>&1; then
  echo 'Deployment accepted enabled Garmin without a key file' >&2
  exit 1
fi
grep -q 'enabled Garmin requires exactly one' "$tmp/missing-key.log" || { echo 'Missing key did not produce a clear configuration error' >&2; exit 1; }
PATH="$tmp/bin:$PATH" VERIFY_SUCCESS=true "$root/scripts/deploy-verified.sh" --env-file "$tmp/compose.env" --project m831-test "$image" >/dev/null
grep -q -- '-f .*compose.production.garmin.yaml' "$DOCKER_COMPOSE_LOG" || { echo 'Garmin deployment omitted the production override' >&2; exit 1; }
if grep -q 'fixture-secret' "$DOCKER_CALL_LOG" "$DOCKER_COMPOSE_LOG" "$failure_log"; then echo 'Garmin key leaked into Docker arguments or logs' >&2; exit 1; fi
if PATH="$tmp/bin:$PATH" GARMIN_TEST_KEY=/wrong VERIFY_SUCCESS=true "$root/scripts/deploy-verified.sh" --env-file "$tmp/compose.env" --project m831-test "$image" >"$tmp/wrong-owner.log" 2>&1; then
  echo 'Deployment accepted a Garmin key owned by the wrong UID' >&2
  exit 1
fi
grep -q 'must be owned by app UID 10001' "$tmp/wrong-owner.log" || { echo 'Wrong key owner did not produce a clear configuration error' >&2; exit 1; }
echo 'Rejected mutable references, stopped before Compose on failed verification, and exercised the verified Compose/readiness path with isolated stubs.'
