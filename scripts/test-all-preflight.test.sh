#!/usr/bin/env bash
set -Eeuo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

cat >"$tmp/docker" <<'EOF'
#!/usr/bin/env bash
touch "$DOCKER_WAS_CALLED"
exit 0
EOF
chmod +x "$tmp/docker"

if PATH="$tmp:$PATH" DOCKER_WAS_CALLED="$tmp/docker-called" TEST_DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/other \
  "$root/scripts/test-all.sh" >"$tmp/output" 2>&1; then
  echo 'Expected conflicting test database configuration to fail' >&2
  exit 1
fi

grep -q 'TEST_DATABASE_URL must not be set' "$tmp/output"
test ! -e "$tmp/docker-called"
