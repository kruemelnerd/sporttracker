# Verifying a Sporttracker container release

Deploy only the exact digest emitted by the CI run, never a mutable tag:

```sh
scripts/verify-release.sh ghcr.io/kruemelnerd/sporttracker@sha256:<64-hex-digest>
```

The verifier requires Docker and GitHub CLI (`gh`). It pulls the image, checks
the embedded repository and commit labels, then requires GitHub to verify both
the SLSA provenance and SPDX SBOM attestation for that exact OCI digest. The
policy pins the repository, `.github/workflows/ci.yml`, `refs/heads/main`, and
the source commit matching the image revision. Missing attestations, invalid
signatures, mismatched identity/digest/predicate, or unavailable GitHub/registry
services cause failure; there is no bypass mode.

If GHCR remains private, authenticate Docker with an account that has
`read:packages` access before running the verifier. GitHub CLI also needs
permission to query attestations for this public repository. Do not store a
personal access token in this repository; use a local credential helper or a
short-lived credential provider. Making the GHCR package public later does not
change the verification policy.

GitHub Actions uploads `sporttracker-sbom.spdx.json`, both complete signed
Sigstore bundles, and `release-metadata.json` as a run artifact. The same
attestations are published through GitHub's artifact-attestations service and
to GHCR, so verification does not depend on artifact retention.

Attestation verification establishes signature integrity and build origin; it
does not establish that the image is free of known vulnerabilities. CI's Trivy
scan still blocks High and Critical findings and reports lower severities.
Operators should also run a fresh vulnerability scan against the pinned digest
before deployment and during operation.
