# Linux Host Tools Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Single-file, fail-closed Ubuntu/Debian prerequisite installer without deployment side effects.
**Architecture:** Standalone Bash installer with embedded Python for structured APT metadata/source validation; the existing preflight delegates to its read-only mode. Isolated process/filesystem tests exercise the actual script with external tools stubbed.
**Tech Stack:** Bash, Python standard library, APT, GnuPG, unittest.
**Spec:** `docs/superpowers/specs/2026-10-29-host-tools-design.md`

## Global Constraints
- Preserve `.opencode/opencode.json` and `.opencode/plugins/graphify.js` byte-for-byte.
- No real package installation, production containers, volumes, secrets or SSH configuration changes during development.
- Runtime: only Ubuntu/Debian and official HTTPS signed package sources; preserve functional Docker.
- No version pinning for managed packages; trust-anchor fingerprints are pinned.
- `--check` never writes files, runs APT updates, fetches Git or starts containers.

## Review Focus
- Existing Docker can be indirectly upgraded through package dependencies: reject the simulated transaction.
- APT may return success after partial index failures: require error-on-any and signed fresh metadata.
- Sources can contain credentials, duplicates, embedded keys or unsafe options: fail closed without echoing contents.
- LXC uses the PVE kernel: distinguish management-host evidence from shared kernel.
- Existing local repository may contain untrusted code: never run it as root from setup.

### Task 1: Installer and isolated behavioral tests
**Files:** create `scripts/install-host-tools.sh`, `tests/security/test_install_host_tools.py`.
**Interfaces:** executable Bash file, default / `--check` / `--upgrade` / `--help`, exit 0 success, nonzero failure; safe to source for function-level tests without executing main.
- [x] Write tests for system detection, PVE refusal, confirmation, check-only behavior, missing dependencies, official source handling, install/idempotency, upgrade guards, invalid keys/signatures and APT errors.
- [x] Run `python3 -m unittest discover -s tests/security -p 'test_install_host_tools.py' -v`; observe failure before implementation.
- [x] Implement standalone installer and real signed-metadata checking; extend tests at trust boundaries before each new behavior.
- [x] Run the complete installer suite, Bash syntax and ShellCheck; require all pass.

### Task 2: Shared preflight and usage documentation
**Files:** modify `scripts/proxmox-preflight.sh`, `tests/security/proxmox-preflight-test.sh`, `README.md`, `docs/proxmox-first-install.md`, `docs/proxmox-deployment.md`, `docs/containerization.md`.
**Interfaces:** preflight calls sibling installer `--check`; no release verifier/deployment changes.
- [x] Write failing preflight forwarding/negative tests, then replace duplicated preflight logic.
- [x] Update simple Linux installation instructions and realistic LXC resource guidance; retain release verification and phased deployment gates.
- [x] Run installer/preflight and existing release policy suites; Bash syntax, ShellCheck, `git diff --check`.

### Task 3: Verification, review and delivery
**Files:** verification ledger below; optional isolated CI test if runtime permits.
- [x] Run scope-appropriate regression/build checks without production resources; report blocked prerequisites honestly.
- [x] Obtain fresh security/code review, reproduce important findings with failing tests, fix and rerun suites.
- [x] Review diff and protected-file hashes. Commit/push only if all required checks pass and authorized Git access is available; no SSH changes.

## Execution ledger

- Ruling: user explicitly requested immediate implementation on the previously specified main delivery path; work in place without creating an unapproved worktree or asking another planning question. Protected-file hashes recorded before fast-forward; upstream changes do not touch them.
- Baseline: public HTTPS fetch and conflict-free fast-forward to `fc72505`; origin URL and SSH settings unchanged.
- Pre-flight: Task 2 consumes Task 1's `--check` entrypoint; Task 3 verifies both. No conflicting interfaces.
- Ruling: this turn prioritizes script implementation, as requested; no SSH troubleshooting/fix and no claim of completed remote delivery without a verified push.
- Task 1: initial 15 contract tests RED (installer absent), then GREEN; real GPG tests RED on attempted lock creation, then GREEN with read-only key inspection. Added signature tampering, suite, digest, architecture, HTTPS, source-option, cold-install and second-run regressions before fixes.
- Task 1: 38 isolated tests implemented; ShellCheck found one empty-assignment warning, fixed without suppression. Real HTTPS/signature/index probes passed for Ubuntu 26.04 and vendor sources for Debian 13, without installation.
- Task 2: historic preflight forwarding test RED, then GREEN; shared read-only implementation now covers both entrypoints. Installer suite is included in the existing CI test runner, not an independent privileged workflow.
- Ruling: Python handles structured metadata, subprocesses, safe temporary files and in-memory GPG verification inside the single Bash artifact; Bash bootstraps Python only if missing. No Python dependency beyond the standard library. Cost: the one downloaded file is longer, but avoids a second download/source of privileged code and duplicated ad-hoc parsers.
- Verification: first full regression timed out at the tool's 120-second limit during Playwright; backend, 71 frontend unit tests, typecheck/build and security tests passed. The interrupted run's PASS banner does not count as full-suite success. A new isolated full run was started without that timeout.
- Final review: four High findings reproduced RED (Python shadow import, Git filters/config inheritance, pending/Conf-only Docker package configuration, inherited APT hooks/TLS rules), then fixed and GREEN. Isolated APT configuration is loaded before system fragments; no changes to host APT configuration. Executable mode was already restored and is now covered by direct preflight invocation.
- Final: additional trust-boundary regressions RED→GREEN for old Docker state, current signatures with existing keyrings, and similarly named but unsupported CLI flags.
- Final: Ruling: do not inspect existing checkout status as root — Git status can execute clean/process filters, so only local origin metadata is read without includes; files are preserved and manual status/update is required. Cost: setup does not classify dirty vs clean repositories automatically.
- Final: Ruling: protected OpenCode files remain byte-for-byte unchanged and documentation keeps historical paths — preserve user work and existing links; cost: historic Proxmox filenames now cover general Linux.
- Final: Ruling: preserve functional installed binaries based on required capabilities, rather than auditing historical provenance — explicitly required by the approved spec; cost: setup is not a retrospective supply-chain audit of all installed tools.
- Final: Ruling: real package-install/maintainer-script behavior and actual unprivileged Proxmox-LXC operation are not claimed as tested — only controlled installation simulations, real signature/configuration boundary tests and read-only live metadata/runtime probes are available here. Cost: the operator must validate actual installation on a disposable supported guest before treating that environment as proven.
- Verification: full regression completed with 58 backend, 71 frontend and 17 E2E tests, production frontend build and packaged `bootJar` PASS. Final post-fix regression runs again with the complete installer test set. Actual host `--check` correctly returned 1 for missing attestation-capable gh and absent `/opt/sporttracker`, preserved working Docker 29.9.2/overlayfs/cgroups2, verified existing official Docker/Trivy sources, and made no setup changes.
- Delivery: code commit `56220a4` pushed to the same repository's main over HTTPS using the existing GitHub CLI credential helper for that invocation only. No stored Git/SSH/credential settings changed; no token displayed. Plain HTTPS lacked a configured credential helper; the existing gh 2.46 helper was verified to read cached credentials and treat store/erase as no-ops.
- Final: socket-activation regressions RED→GREEN: do not contact known inactive systemd Docker/containerd services through their activation sockets, and detect Docker Snap from files rather than starting snapd through a supposedly read-only RPC. Working Docker remains readable without service mutation.
- Final verification: `scripts/test-all.sh` PASS with 50 installer tests, existing release-policy/deployment/preflight tests, 58 backend tests, 71 frontend tests, typecheck/build and 17 Playwright tests. Final run `b64e07520aa0eee2c3303196` cleaned up its own isolated resources. `bootJar`, Bash syntax, ShellCheck 0.11.0 and `git diff --check` PASS. Existing npm engine/moderate-audit and Vite chunk-size warnings remain outside this scope; no dependency upgrade was made.
- Delivery: socket-activation follow-up `f393c2b` also pushed successfully over HTTPS. Operational documentation keeps historical links and a single setup path; its final commit is delivered using the same unchanged-origin, existing-credential mechanism. No worktree/branch cleanup is needed in this normal checkout; the two protected user changes remain.
