#!/usr/bin/env bash
set -euo pipefail

script=$(cd "$(dirname "$0")/../.." && pwd)/scripts/verify-release.sh
for ref in \
  ghcr.io/kruemelnerd/sporttracker:stable \
  ghcr.io/other/repo@sha256:$(printf 'a%.0s' {1..64}) \
  ghcr.io/kruemelnerd/sporttracker@sha256:abc; do
  if "$script" "$ref" >/dev/null 2>&1; then
    echo "Unexpectedly accepted $ref" >&2
    exit 1
  else
    status=$?
  fi
  [[ $status -eq 2 ]] || { echo "Expected usage failure for $ref; got $status" >&2; exit 1; }
done
echo 'Rejected mutable tags and malformed/untrusted image references.'
