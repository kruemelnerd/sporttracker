#!/usr/bin/env bash
set -euo pipefail

# Keep the historic entrypoint without a second OS/security policy to maintain.
(( $# == 0 )) || { echo 'Usage: proxmox-preflight.sh (read-only; no arguments)' >&2; exit 2; }
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
[[ -f $script_dir/install-host-tools.sh ]] || {
  echo 'Shared install-host-tools.sh missing; prerequisite checks cannot be performed.' >&2
  exit 1
}
exec bash "$script_dir/install-host-tools.sh" --check
