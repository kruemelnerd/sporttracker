#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: $0 ghcr.io/kruemelnerd/sporttracker@sha256:<64 lowercase hex>" >&2
  exit 2
}

[[ $# -eq 1 ]] || usage
image=$1
[[ "$image" =~ ^ghcr\.io/kruemelnerd/sporttracker@sha256:[0-9a-f]{64}$ ]] || usage
command -v gh >/dev/null || { echo 'gh CLI is required' >&2; exit 1; }
command -v docker >/dev/null || { echo 'Docker CLI is required' >&2; exit 1; }
gh attestation verify --help >/dev/null 2>&1 || {
  echo 'Update GitHub CLI: this version does not support attestation verification' >&2
  exit 1
}

digest=${image##*@}
docker pull "$image" >/dev/null
commit=$(docker image inspect "$image" --format '{{index .Config.Labels "org.opencontainers.image.revision"}}')
[[ "$commit" =~ ^[0-9a-f]{40}$ ]] || { echo 'Image has no valid source revision label' >&2; exit 1; }
[[ "$(docker image inspect "$image" --format '{{index .Config.Labels "org.opencontainers.image.source"}}')" == 'https://github.com/kruemelnerd/sporttracker' ]] || {
  echo 'Image source label does not match the trusted repository' >&2
  exit 1
}

signer='github.com/kruemelnerd/sporttracker/.github/workflows/ci.yml'
gh attestation verify "oci://$image" --repo kruemelnerd/sporttracker \
  --signer-repo kruemelnerd/sporttracker --signer-workflow "$signer" \
  --source-ref refs/heads/main --source-digest "$commit" --deny-self-hosted-runners \
  --cert-oidc-issuer https://token.actions.githubusercontent.com \
  --predicate-type https://slsa.dev/provenance/v1
gh attestation verify "oci://$image" --repo kruemelnerd/sporttracker \
  --signer-repo kruemelnerd/sporttracker --signer-workflow "$signer" \
  --source-ref refs/heads/main --source-digest "$commit" --deny-self-hosted-runners \
  --cert-oidc-issuer https://token.actions.githubusercontent.com \
  --predicate-type https://spdx.dev/Document

echo "Verified $image from kruemelnerd/sporttracker@$commit (main; $digest)"
