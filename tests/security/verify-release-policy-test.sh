#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "$0")/../.." && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
mkdir "$tmp/bin"

cat >"$tmp/bin/docker" <<'SH'
#!/usr/bin/env bash
if [[ $1 == pull ]]; then [[ ${DOCKER_TEST_MODE:-} != registry-fail ]]; exit; fi
if [[ $1 == image && $2 == inspect ]]; then
  case "$5" in
    *revision*) printf '%s\n' 'b9af77982ba94cd8aca03207c4241370acfc006a' ;;
    *source*) printf '%s\n' 'https://github.com/kruemelnerd/sporttracker' ;;
  esac
  exit 0
fi
exit 1
SH

cat >"$tmp/bin/gh" <<'SH'
#!/usr/bin/env bash
if [[ $1 == attestation && $2 == verify && $3 == --help ]]; then exit 0; fi
[[ $1 == attestation && $2 == verify ]] || exit 1
[[ " $* " == *' --repo kruemelnerd/sporttracker '* && " $* " == *' --signer-workflow github.com/kruemelnerd/sporttracker/.github/workflows/ci.yml '* && " $* " == *' --source-ref refs/heads/main '* && " $* " == *' --source-digest b9af77982ba94cd8aca03207c4241370acfc006a '* && " $* " == *' --cert-oidc-issuer https://token.actions.githubusercontent.com '* ]] || exit 1
[[ ${GH_TEST_MODE:-} != missing-attestation ]] || exit 1
predicate_type=https://slsa.dev/provenance/v1
if [[ " $* " == *' https://spdx.dev/Document/v2.3 '* ]]; then predicate_type=https://spdx.dev/Document/v2.3; fi
sbom='{"spdxVersion":"SPDX-2.3","SPDXID":"SPDXRef-DOCUMENT","name":"fixture","packages":[{"SPDXID":"SPDXRef-Package-fixture","name":"fixture","downloadLocation":"NOASSERTION","filesAnalyzed":false,"licenseConcluded":"NOASSERTION","licenseDeclared":"NOASSERTION","copyrightText":"NOASSERTION"}]}'
[[ ${GH_TEST_MODE:-} != empty-sbom || $predicate_type != https://spdx.dev/Document/v2.3 ]] || sbom='{"spdxVersion":"SPDX-2.3","SPDXID":"SPDXRef-DOCUMENT","name":"fixture","packages":[]}'
[[ ${GH_TEST_MODE:-} != malformed-sbom || $predicate_type != https://spdx.dev/Document/v2.3 ]] || sbom='{"spdxVersion":"SPDX-2.2","SPDXID":"wrong","name":"","packages":[]}'
[[ ${GH_TEST_MODE:-} != malformed-package || $predicate_type != https://spdx.dev/Document/v2.3 ]] || sbom='{"spdxVersion":"SPDX-2.3","SPDXID":"SPDXRef-DOCUMENT","name":"fixture","packages":[{"name":"fixture"}]}'
digest=5bbc988288cf286507f0c6a7cd1c38ecf8c977c97d154ed21ce939a07837daf5
[[ ${GH_TEST_MODE:-} != wrong-subject ]] || digest=$(printf 'a%.0s' {1..64})
jq -cn --arg type "$predicate_type" --arg digest "$digest" --argjson predicate "$([[ $predicate_type == https://spdx.dev/Document/v2.3 ]] && printf '%s' "$sbom" || printf '{}')" '[{verificationResult:{statement:{predicateType:$type,subject:[{digest:{sha256:$digest}}],predicate:$predicate}}}]'
SH

cat >"$tmp/bin/trivy" <<'SH'
#!/usr/bin/env bash
[[ ${TRIVY_TEST_MODE:-} != fail ]]
SH

chmod +x "$tmp/bin/"*
image=ghcr.io/kruemelnerd/sporttracker@sha256:5bbc988288cf286507f0c6a7cd1c38ecf8c977c97d154ed21ce939a07837daf5
verify() { PATH="$tmp/bin:$PATH" "$root/scripts/verify-release.sh" "$image"; }

if PATH="$tmp/bin:$PATH" GH_TEST_MODE=empty-sbom verify >/dev/null 2>&1; then
  echo 'Verifier accepted an SBOM with no components' >&2
  exit 1
fi
if PATH="$tmp/bin:$PATH" GH_TEST_MODE=wrong-subject verify >/dev/null 2>&1; then
  echo 'Verifier accepted an attestation for another digest' >&2
  exit 1
fi
if PATH="$tmp/bin:$PATH" GH_TEST_MODE=malformed-sbom verify >/dev/null 2>&1; then
  echo 'Verifier accepted an invalid SPDX document' >&2
  exit 1
fi
if PATH="$tmp/bin:$PATH" GH_TEST_MODE=malformed-package verify >/dev/null 2>&1; then
  echo 'Verifier accepted a package without required SPDX fields' >&2
  exit 1
fi
if PATH="$tmp/bin:$PATH" GH_TEST_MODE=missing-attestation verify >/dev/null 2>&1; then
  echo 'Verifier accepted missing attestation evidence' >&2
  exit 1
fi
if PATH="$tmp/bin:$PATH" TRIVY_TEST_MODE=fail verify >/dev/null 2>&1; then
  echo 'Verifier accepted a High/Critical Trivy finding' >&2
  exit 1
fi
if PATH="$tmp/bin:$PATH" DOCKER_TEST_MODE=registry-fail verify >/dev/null 2>&1; then
  echo 'Verifier accepted an unreachable registry' >&2
  exit 1
fi
PATH="$tmp/bin:$PATH" GH_TEST_MODE=valid verify >/dev/null
echo 'Rejected invalid/missing/mismatched attestation evidence, Trivy findings and registry failures; accepted valid mocked evidence.'
