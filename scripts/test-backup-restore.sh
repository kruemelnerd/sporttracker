#!/usr/bin/env bash
set -Eeuo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
tmp=$(mktemp -d)
trap 'rm -rf -- "$tmp"' EXIT
mkdir -p "$tmp/bin" "$tmp/backup"
mkdir -p "$tmp/fit"
chmod 700 "$tmp/backup"
touch "$tmp/compose.env"
chmod 600 "$tmp/compose.env"
cat >"$tmp/bin/docker" <<'SH'
#!/usr/bin/env bash
if [[ ${1-} == --context && ${3-} == context && ${4-} == inspect ]]; then exit 0; fi
touch "$DOCKER_CALLED"
exit 99
SH
chmod 700 "$tmp/bin/docker"
export PATH="$tmp/bin:$PATH" DOCKER_CALLED="$tmp/docker-called"

expect_failure() {
  local expected=$1; shift
  local actual=0
  "$@" >/dev/null 2>&1 || actual=$?
  [[ $actual == "$expected" ]] || { echo "Expected exit $expected, got $actual: $*" >&2; exit 1; }
}

expect_failure 2 bash "$ROOT/scripts/backup.sh" --project only
expect_failure 1 bash "$ROOT/scripts/restore.sh" --docker-context test --backup-dir "$tmp/missing" --project isolated --compose-file "$ROOT/compose.restore.yaml" --env-file "$tmp/compose.env" --confirm-empty-project isolated
touch "$tmp/other-compose.yaml"
expect_failure 1 bash "$ROOT/scripts/restore.sh" --docker-context test --backup-dir "$tmp/backup" --project isolated --compose-file "$tmp/other-compose.yaml" --env-file "$tmp/compose.env" --confirm-empty-project isolated
expect_failure 1 bash "$ROOT/scripts/restore.sh" --docker-context test --backup-dir "$tmp/backup" --project sporttracker-production --compose-file "$ROOT/compose.restore.yaml" --env-file "$tmp/compose.env" --confirm-empty-project sporttracker-production

touch "$tmp/backup/fit-data.tar.gz" "$tmp/backup/flyway-version.txt" "$tmp/backup/compose-version.txt" "$tmp/backup/metadata.txt" "$tmp/backup/database.dump"
chmod 600 "$tmp/backup"/*
tar -czf "$tmp/backup/fit-data.tar.gz" -C "$tmp/fit" .
printf 'postgres_version=18.6\nsource_compose_project=source\n' >"$tmp/backup/metadata.txt"
printf '9\n' >"$tmp/backup/flyway-version.txt"
(cd "$tmp/backup" && sha256sum database.dump fit-data.tar.gz flyway-version.txt compose-version.txt metadata.txt >SHA256SUMS)
chmod 600 "$tmp/backup/SHA256SUMS"
printf 'modified' >>"$tmp/backup/metadata.txt"
expect_failure 1 bash "$ROOT/scripts/restore.sh" --docker-context test --backup-dir "$tmp/backup" --project restore --compose-file "$ROOT/compose.restore.yaml" --env-file "$tmp/compose.env" --confirm-empty-project restore
[[ ! -e $DOCKER_CALLED ]] || { echo 'Docker was invoked before checksum validation' >&2; exit 1; }

printf 'postgres_version=18.6\nsource_compose_project=source\n' >"$tmp/backup/metadata.txt"
(cd "$tmp/backup" && sha256sum database.dump fit-data.tar.gz flyway-version.txt compose-version.txt metadata.txt >SHA256SUMS)
expect_failure 1 bash "$ROOT/scripts/restore.sh" --docker-context test --backup-dir "$tmp/backup" --project source --compose-file "$ROOT/compose.restore.yaml" --env-file "$tmp/compose.env" --confirm-empty-project source
[[ ! -e $DOCKER_CALLED ]] || { echo 'Docker was invoked for a source/target project collision' >&2; exit 1; }

mkdir "$tmp/unsafe"
ln -s /etc/passwd "$tmp/unsafe/link"
tar -czf "$tmp/backup/fit-data.tar.gz" -C "$tmp/unsafe" .
(cd "$tmp/backup" && sha256sum database.dump fit-data.tar.gz flyway-version.txt compose-version.txt metadata.txt >SHA256SUMS)
expect_failure 1 bash "$ROOT/scripts/restore.sh" --docker-context test --backup-dir "$tmp/backup" --project restore --compose-file "$ROOT/compose.restore.yaml" --env-file "$tmp/compose.env" --confirm-empty-project restore
[[ ! -e $DOCKER_CALLED ]] || { echo 'Docker was invoked for an unsafe archive' >&2; exit 1; }

rm "$tmp/backup/database.dump"
expect_failure 1 bash "$ROOT/scripts/restore.sh" --docker-context test --backup-dir "$tmp/backup" --project restore --compose-file "$ROOT/compose.restore.yaml" --env-file "$tmp/compose.env" --confirm-empty-project restore
touch "$tmp/backup/database.dump"
rm "$tmp/backup/fit-data.tar.gz"
expect_failure 1 bash "$ROOT/scripts/restore.sh" --docker-context test --backup-dir "$tmp/backup" --project restore --compose-file "$ROOT/compose.restore.yaml" --env-file "$tmp/compose.env" --confirm-empty-project restore
echo 'backup/restore refusal checks passed'
