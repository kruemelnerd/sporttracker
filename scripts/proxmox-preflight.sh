#!/usr/bin/env bash
set -uo pipefail

failures=0
check() {
  local label=$1; shift
  if "$@"; then printf 'PASS: %s\n' "$label"; else printf 'FAIL: %s\n' "$label"; failures=$((failures + 1)); fi
}
has() { command -v "$1" >/dev/null 2>&1; }
version_at_least() { [[ $1 =~ ([0-9]+)\.([0-9]+) ]] && (( BASH_REMATCH[1] > $2 || (BASH_REMATCH[1] == $2 && BASH_REMATCH[2] >= $3) )); }
supported_os() {
  # shellcheck disable=SC1090
  source "$1" && [[ ${ID:-} == ubuntu && ${VERSION_ID:-} == 24.04 ]]
}
supported_arch() { [[ $(uname -m) == x86_64 ]]; }

os_release=${OS_RELEASE_FILE:-/etc/os-release}
check 'Ubuntu Server 24.04' supported_os "$os_release"
check 'x86_64 architecture' supported_arch
for tool in docker git gh trivy jq curl df awk grep sed uname stat realpath sha256sum tar python3 mktemp chmod mv cp date; do
  check "required command: $tool" has "$tool"
done

docker_ok=false
if has docker; then
  docker_version=$(docker --version 2>/dev/null) || docker_version=
  if [[ $docker_version =~ Docker\ version\ ([0-9]+\.[0-9]+) ]] && version_at_least "${BASH_REMATCH[1]}" 24 0; then
    printf 'PASS: Docker Engine %s\n' "${BASH_REMATCH[1]}"; docker_ok=true
  else
    printf 'FAIL: Docker Engine >= 24.0 required\n'; failures=$((failures + 1))
  fi
  compose_version=$(docker compose version --short 2>/dev/null) || compose_version=
  if version_at_least "$compose_version" 2 20; then
    printf 'PASS: Docker Compose %s\n' "$compose_version"
  else
    printf 'FAIL: Docker Compose plugin >= 2.20 required\n'; failures=$((failures + 1))
  fi
else
  printf 'FAIL: Docker Engine and Compose plugin unavailable\n'; failures=$((failures + 2))
fi
if [[ $docker_ok == true ]]; then check 'Docker daemon reachable (read-only info)' docker info >/dev/null 2>&1; fi

if has gh; then
  gh_version=$(gh --version 2>/dev/null | head -n1)
  if gh_help=$(gh attestation verify --help 2>/dev/null) && [[ $gh_help == *--signer-workflow* && $gh_help == *--source-ref* && $gh_help == *--source-digest* && $gh_help == *--cert-oidc-issuer* && $gh_help == *--predicate-type* && $gh_help == *--deny-self-hosted-runners* && $gh_help == *--format* ]]; then
    printf 'PASS: GitHub CLI attestation verification support (%s)\n' "$gh_version"
  else
    printf 'FAIL: GitHub CLI lacks required attestation verification options\n'; failures=$((failures + 1))
  fi
else
  printf 'FAIL: GitHub CLI unavailable\n'; failures=$((failures + 1))
fi
if has trivy; then
  trivy_version=$(trivy --version 2>/dev/null | head -n1)
  if [[ -n $trivy_version ]]; then
    printf 'PASS: Trivy available (%s)\n' "$trivy_version"
  else
    printf 'FAIL: Trivy did not report a version\n'; failures=$((failures + 1))
  fi
else
  printf 'FAIL: Trivy unavailable\n'; failures=$((failures + 1))
fi

if has df; then
  read -r total_kib available_kib < <(df -Pk / | awk 'NR == 2 { print $2, $4 }')
  if [[ $total_kib =~ ^[0-9]+$ && $available_kib =~ ^[0-9]+$ ]] && (( total_kib >= 40 * 1024 * 1024 && available_kib >= 20 * 1024 * 1024 )); then
    printf 'PASS: system filesystem has at least 40 GiB total and 20 GiB free\n'
  else
    printf 'FAIL: system filesystem requires at least 40 GiB total and 20 GiB free\n'; failures=$((failures + 1))
  fi
fi

if has curl; then
  check 'GitHub HTTPS reachable' curl --fail --silent --show-error --output /dev/null --max-time 15 https://github.com/
  ghcr_status=$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' --max-time 15 https://ghcr.io/v2/ 2>/dev/null) || ghcr_status=000
  if [[ $ghcr_status == 200 || $ghcr_status == 401 ]]; then
    printf 'PASS: GHCR registry endpoint reachable (HTTP %s)\n' "$ghcr_status"
  else
    printf 'FAIL: GHCR registry endpoint unavailable (HTTP %s)\n' "$ghcr_status"; failures=$((failures + 1))
  fi
fi

if (( failures )); then printf 'Preflight failed: %d prerequisite check(s). No resources were changed.\n' "$failures" >&2; exit 1; fi
printf 'Preflight passed. This only verifies host prerequisites; it does not verify a release or start containers.\n'
