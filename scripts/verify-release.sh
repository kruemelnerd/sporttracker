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
command -v jq >/dev/null || { echo 'jq is required' >&2; exit 1; }
command -v trivy >/dev/null || { echo 'Trivy is required' >&2; exit 1; }
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
provenance=$(gh attestation verify "oci://$image" --repo kruemelnerd/sporttracker \
  --signer-workflow "$signer" \
  --source-ref refs/heads/main --source-digest "$commit" --deny-self-hosted-runners \
  --cert-oidc-issuer https://token.actions.githubusercontent.com \
  --predicate-type https://slsa.dev/provenance/v1 --format json)
sbom=$(gh attestation verify "oci://$image" --repo kruemelnerd/sporttracker \
  --signer-workflow "$signer" \
  --source-ref refs/heads/main --source-digest "$commit" --deny-self-hosted-runners \
  --cert-oidc-issuer https://token.actions.githubusercontent.com \
  --predicate-type https://spdx.dev/Document/v2.3 --format json)

for evidence in "$provenance" "$sbom"; do
  jq -e --arg digest "${digest#sha256:}" \
    'any(.[]; any(.verificationResult.statement.subject[]?; .digest.sha256 == $digest))' \
    <<<"$evidence" >/dev/null || { echo 'Verified evidence does not reference the requested image digest' >&2; exit 1; }
done
jq -e --arg digest "${digest#sha256:}" '
  any(.[]; .verificationResult.statement.predicateType == "https://spdx.dev/Document/v2.3"
    and any(.verificationResult.statement.subject[]?; .digest.sha256 == $digest)
    and (.verificationResult.statement.predicate | .spdxVersion == "SPDX-2.3"
      and (.SPDXID | type == "string" and startswith("SPDXRef-"))
      and (.name | type == "string" and length > 0)
      and (.packages | type == "array" and length > 0)))' <<<"$sbom" >/dev/null || {
  echo 'Verified SBOM is not a non-empty SPDX-2.3 document for the requested image digest' >&2
  exit 1
}

trivy image --scanners vuln,misconfig,secret --severity HIGH,CRITICAL --exit-code 1 "$image"

echo "Verified $image from kruemelnerd/sporttracker@$commit (main; $digest)"
