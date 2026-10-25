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

## Latest committed scan baseline

For release commit `657db3a988f33618cd1a09caf0d84c960da6450a` (run
`40225081265`, 2026-10-25), Trivy reported 25 Medium and 4 Low image findings,
with no High or Critical findings. The 23 distinct Medium CVEs were
`CVE-2025-66382`, `CVE-2026-102473`, `CVE-2026-102474`, `CVE-2026-103111`,
`CVE-2026-18374`, `CVE-2026-18477`, `CVE-2026-18508`, `CVE-2026-67693`,
`CVE-2026-85091`, `CVE-2026-86145`, `CVE-2026-88647`, `CVE-2026-88648`,
`CVE-2026-89092`, `CVE-2026-89156`, `CVE-2026-89157`, `CVE-2026-89158`,
`CVE-2026-89160`, `CVE-2026-89161`, `CVE-2026-95184`, `CVE-2026-95209`,
`CVE-2026-95210`, `CVE-2026-112949`, and `CVE-2026-112950`. They are primarily
Ubuntu 24.04 runtime-package findings; Trivy did not report fixed versions for
most of them at scan time. The four Low image findings correspond to
`CVE-2026-40228` and `CVE-2024-56433` across multiple packages. Trivy also
reported the Low configuration recommendation `DS-0026` (add a Docker
`HEALTHCHECK`). The filesystem scan independently reported the same
`DS-0026`; it is a recommendation, not a CVE.

The frontend dependency audit reported two Moderate findings in
`@vitest/mocker` (`CVE-2026-112949` and `CVE-2026-112950`); this is a
development/test dependency and is not included in the Runtime image SBOM.
High/Critical findings continue to fail CI. Counts and advisories can change
as Trivy's vulnerability database and upstream packages are updated; use the
fresh scan output rather than treating this snapshot as a permanent allowlist.
