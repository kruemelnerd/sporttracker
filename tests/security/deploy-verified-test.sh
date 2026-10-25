#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "$0")/../.." && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
mkdir "$tmp/bin"
touch "$tmp/compose.env"
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
exit 0
SH
cat >"$tmp/bin/gh" <<'SH'
#!/usr/bin/env bash
if [[ $1 == attestation && $2 == verify && $3 == --help ]]; then exit 0; fi
[[ ${VERIFY_SUCCESS:-} == true ]] || exit 1
predicate_type=https://slsa.dev/provenance/v1
if [[ " $* " == *' https://spdx.dev/Document/v2.3 '* ]]; then predicate_type=https://spdx.dev/Document/v2.3; fi
predicate='{}'
[[ $predicate_type != https://spdx.dev/Document/v2.3 ]] || predicate='{"spdxVersion":"SPDX-2.3","SPDXID":"SPDXRef-DOCUMENT","name":"fixture","packages":[{"SPDXID":"SPDXRef-Package-fixture","name":"fixture","downloadLocation":"NOASSERTION","filesAnalyzed":false,"licenseConcluded":"NOASSERTION","licenseDeclared":"NOASSERTION","copyrightText":"NOASSERTION"}]}'
jq -cn --arg type "$predicate_type" --argjson predicate "$predicate" '[{verificationResult:{statement:{predicateType:$type,subject:[{digest:{sha256:"5bbc988288cf286507f0c6a7cd1c38ecf8c977c97d154ed21ce939a07837daf5"}}],predicate:$predicate}}}]'
SH
cat >"$tmp/bin/trivy" <<'SH'
#!/usr/bin/env bash
[[ ${VERIFY_SUCCESS:-} == true ]] || exit 1
exit 0
SH
chmod +x "$tmp/bin/"*
export DOCKER_CALL_LOG="$tmp/docker.log"
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
grep -q '^pull ' "$DOCKER_CALL_LOG" || { echo 'Deployment did not invoke release verification' >&2; exit 1; }
if grep -q 'compose ' "$DOCKER_CALL_LOG"; then
  echo 'Compose was invoked before release verification succeeded' >&2
  exit 1
fi
PATH="$tmp/bin:$PATH" VERIFY_SUCCESS=true "$root/scripts/deploy-verified.sh" --env-file "$tmp/compose.env" --project m831-test "$image" >/dev/null
grep -q 'compose .*config --quiet' "$DOCKER_CALL_LOG" || { echo 'Deployment did not validate Compose configuration' >&2; exit 1; }
grep -q 'volume inspect sporttracker-prod-postgres' "$DOCKER_CALL_LOG" || { echo 'Deployment did not protect/check the PostgreSQL volume' >&2; exit 1; }
grep -q 'volume inspect sporttracker-prod-fit' "$DOCKER_CALL_LOG" || { echo 'Deployment did not protect/check the FIT volume' >&2; exit 1; }
grep -q 'compose .*up -d --wait --wait-timeout 180 app' "$DOCKER_CALL_LOG" || { echo 'Deployment did not wait for app readiness' >&2; exit 1; }
echo 'Rejected mutable references, stopped before Compose on failed verification, and exercised the verified Compose/readiness path with isolated stubs.'
