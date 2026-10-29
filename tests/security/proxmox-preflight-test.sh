#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "$0")/../.." && pwd)
tmp=$(mktemp -d)
trap 'rm -rf -- "$tmp"' EXIT
mkdir "$tmp/bin"
ln -s /bin/bash "$tmp/bin/bash"
ln -s /usr/bin/dirname "$tmp/bin/dirname"
cp "$root/scripts/proxmox-preflight.sh" "$tmp/proxmox-preflight.sh"
cat >"$tmp/install-host-tools.sh" <<'STUB'
#!/usr/bin/env bash
[[ $# == 1 && $1 == --check ]] || exit 90
echo 'READ_ONLY_SHARED_CHECK'
exit 23
STUB
status=0
output=$(PATH="$tmp/bin" "$tmp/proxmox-preflight.sh" 2>&1) || status=$?
[[ $status == 23 && $output == READ_ONLY_SHARED_CHECK ]] || {
  echo 'Preflight must delegate to the shared read-only check and preserve its exit status.' >&2
  exit 1
}
if PATH="$tmp/bin" "$tmp/proxmox-preflight.sh" --upgrade >/dev/null 2>&1; then
  echo 'Preflight accepted a mutating mode.' >&2
  exit 1
fi
rm "$tmp/install-host-tools.sh"
if PATH="$tmp/bin" "$tmp/proxmox-preflight.sh" >/dev/null 2>&1; then
  echo 'Preflight accepted a missing shared installer.' >&2
  exit 1
fi
echo 'Preflight delegates only --check, preserves failures, and refuses missing shared code.'
