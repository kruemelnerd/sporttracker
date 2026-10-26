#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "$0")/../.." && pwd)
tmp=$(mktemp -d)
trap 'rm -rf -- "$tmp"' EXIT
mkdir "$tmp/empty-path"
printf 'ID=debian\nVERSION_ID=12\n' >"$tmp/os-release"
if output=$(PATH="$tmp/empty-path" OS_RELEASE_FILE="$tmp/os-release" /bin/bash "$root/scripts/proxmox-preflight.sh" 2>&1); then
  echo 'Preflight accepted an unsupported operating system' >&2
  exit 1
fi
[[ $output == *'FAIL: Ubuntu Server 24.04'* && $output == *'No resources were changed.'* ]] || {
  echo 'Preflight did not clearly reject unsupported prerequisites' >&2
  printf '%s\n' "$output" >&2
  exit 1
}
echo 'Preflight rejected unsupported OS with missing tools and made no resource changes.'
