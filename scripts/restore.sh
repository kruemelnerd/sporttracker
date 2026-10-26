#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

usage() { echo "Usage: $0 --docker-context NAME --backup-dir DIR --project NEW_PROJECT --compose-file FILE --env-file FILE --confirm-empty-project NEW_PROJECT" >&2; }
fail() { echo "restore: $*" >&2; exit 1; }

docker_context='' backup_dir='' project='' compose_file='' env_file='' confirmation=''
while (($#)); do
  case "$1" in
    --docker-context) (($# >= 2)) || { usage; exit 2; }; docker_context=$2; shift 2 ;;
    --backup-dir) (($# >= 2)) || { usage; exit 2; }; backup_dir=$2; shift 2 ;;
    --project) (($# >= 2)) || { usage; exit 2; }; project=$2; shift 2 ;;
    --compose-file) (($# >= 2)) || { usage; exit 2; }; compose_file=$2; shift 2 ;;
    --env-file) (($# >= 2)) || { usage; exit 2; }; env_file=$2; shift 2 ;;
    --confirm-empty-project) (($# >= 2)) || { usage; exit 2; }; confirmation=$2; shift 2 ;;
    *) usage; exit 2 ;;
  esac
done
[[ -n $docker_context && -n $backup_dir && -n $project && -n $compose_file && -n $env_file && $confirmation == "$project" ]] || { usage; exit 2; }
[[ $project =~ ^[a-zA-Z0-9][a-zA-Z0-9_-]*$ ]] || fail "invalid Compose project name"
[[ $project != *prod* && $project != *production* ]] || fail "production-like project names are prohibited"
[[ -d $backup_dir && ! -L $backup_dir && -f $compose_file && -f $env_file && ! -L $env_file ]] || fail "backup directory, Compose file or private environment file not found"
env_mode=$(stat -c '%a' "$env_file")
(( (8#$env_mode & 077) == 0 )) || fail "Compose environment file must not be accessible to group or other users"
command -v realpath >/dev/null || fail "realpath is required"
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
expected_compose=$(cd -- "$script_dir/.." && pwd -P)/compose.restore.yaml
compose_file=$(realpath -e -- "$compose_file") || fail "could not resolve restore Compose file"
[[ $compose_file == "$expected_compose" ]] || fail "only the repository's guarded compose.restore.yaml is accepted"
backup_mode=$(stat -c '%a' "$backup_dir")
(( (8#$backup_mode & 077) == 0 )) || fail "backup directory must not be accessible to group or other users"
for file in database.dump fit-data.tar.gz flyway-version.txt compose-version.txt metadata.txt SHA256SUMS; do
  [[ -f $backup_dir/$file && ! -L $backup_dir/$file ]] || fail "backup file missing or not a regular file: $file"
  mode=$(stat -c '%a' "$backup_dir/$file")
  (( (8#$mode & 077) == 0 )) || fail "backup file permissions are too broad: $file"
done
command -v docker >/dev/null || fail "docker is required"
command -v sha256sum >/dev/null || fail "sha256sum is required"
command -v python3 >/dev/null || fail "python3 is required to validate archive paths"
docker=(docker --context "$docker_context")
"${docker[@]}" context inspect "$docker_context" >/dev/null 2>&1 || fail "Docker context is not available"
python3 - "$backup_dir/SHA256SUMS" <<'PY'
import re, sys
expected = {"database.dump", "fit-data.tar.gz", "flyway-version.txt", "compose-version.txt", "metadata.txt"}
with open(sys.argv[1], encoding="ascii") as manifest:
    lines = manifest.read().splitlines()
if len(lines) != len(expected) or any(not re.fullmatch(r"[0-9a-fA-F]{64}  ([A-Za-z0-9._-]+)", line) or line.split()[-1] not in expected for line in lines) or {line.split()[-1] for line in lines} != expected:
    raise SystemExit("restore: invalid checksum manifest")
PY
(cd "$backup_dir" && sha256sum --check SHA256SUMS) || fail "backup checksum verification failed"
grep -q '^postgres_version=18\.' "$backup_dir/metadata.txt" || fail "backup is not from PostgreSQL 18"
source_project=$(sed -n 's/^source_compose_project=//p' "$backup_dir/metadata.txt")
[[ -n $source_project && $source_project != "$project" ]] || fail "restore target must differ from source project"
source_image=$(sed -n 's/^application_image=//p' "$backup_dir/metadata.txt")
source_image_digest=${source_image##*@}
if [[ $source_image_digest == "$source_image" ]]; then source_image_digest=$(sed -n 's/^application_image_digest=//p' "$backup_dir/metadata.txt"); fi
[[ $source_image_digest =~ ^sha256:[[:xdigit:]]{64}$ ]] || fail "backup does not contain an application image digest"

# Refuse links, special files, absolute paths and parent traversal before extraction.
python3 - "$backup_dir/fit-data.tar.gz" <<'PY'
import sys, tarfile
seen = set()
with tarfile.open(sys.argv[1], "r:gz") as archive:
    for item in archive:
        name = item.name.removeprefix("./")
        parts = name.split("/")
        if (name in seen or name.startswith("/") or ".." in parts or "\\" in name
                or item.issym() or item.islnk() or not (item.isfile() or item.isdir())
                or item.mode & 0o7022):
            raise SystemExit("restore: unsafe FIT archive member")
        seen.add(name)
PY

compose=("${docker[@]}" compose --env-file "$env_file" -p "$project" -f "$compose_file")
config=$("${compose[@]}" config --format json) || fail "Compose target configuration is invalid"
target_image=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["services"]["app"]["image"])' <<<"$config") || fail "target application image is missing"
[[ ${target_image##*@} == "$source_image_digest" ]] || fail "target application image digest does not match backup"
mapfile -t target_volumes < <(CONFIG_JSON="$config" python3 - "$project" <<'PY'
import json, os, sys
project = sys.argv[1]
c = json.loads(os.environ["CONFIG_JSON"])
s = c.get("services", {})
v = c.get("volumes", {})
assert {"db", "app"}.issubset(s), "target requires app and db services"
assert {"postgres", "fit"}.issubset(v), "target requires postgres and fit named volumes"
assert not any(x.get("external", False) for x in v.values()), "external volumes are prohibited"
assert all(not v[x].get("driver_opts") and v[x].get("driver", "local") == "local" for x in ("postgres", "fit")), "volume bind/device options are prohibited"
assert all(v[x].get("name", "").startswith(project + "_") for x in ("postgres", "fit")), "volumes must be project-scoped"
db, app = s["db"], s["app"]
assert db.get("image") == "postgres:18.6-bookworm@sha256:bbf83594798e144bff365a9c191c5d779f181fb3135902064ab8ff11918020fc", "unexpected PostgreSQL image"
assert db.get("environment", {}).get("POSTGRES_DB") == "postgres" and db["environment"].get("POSTGRES_USER") == "running_dashboard_admin", "unexpected database bootstrap identity"
assert not db.get("ports") and not any(x in db for x in ("container_name", "network_mode", "privileged", "volumes_from")), "unsafe database service configuration"
assert str(app.get("environment", {}).get("INTERVALS_ICU_ENABLED", "")).lower() == "false", "Intervals sync must be disabled"
assert not any(x in app for x in ("container_name", "network_mode", "privileged", "volumes_from")), "unsafe app service configuration"
assert all(x.get("host_ip") == "127.0.0.1" for x in app.get("ports", [])), "app ports must be loopback-only"
db_mounts, app_mounts = db.get("volumes", []), app.get("volumes", [])
assert len(db_mounts) == 2 and any(x.get("type") == "volume" and x.get("source") == "postgres" and x.get("target") == "/var/lib/postgresql" for x in db_mounts), "db must use only its project database volume and role-init script"
assert any(x.get("type") == "bind" and x.get("target") == "/docker-entrypoint-initdb.d/10-app-role.sh" and x.get("read_only") for x in db_mounts), "expected read-only app-role initializer"
assert len(app_mounts) == 1 and app_mounts[0].get("type") == "volume" and app_mounts[0].get("source") == "fit" and app_mounts[0].get("target") == "/data", "app must use only its project FIT volume"
assert all(not n.get("external", False) for n in c.get("networks", {}).values()), "external networks are prohibited"
print(v["postgres"].get("name", ""))
print(v["fit"].get("name", ""))
PY
) || fail "target must use the guarded isolated restore Compose configuration"
[[ ${#target_volumes[@]} == 2 && -n ${target_volumes[0]} && -n ${target_volumes[1]} && ${target_volumes[0]} != "${target_volumes[1]}" ]] || fail "could not identify target volumes"
existing_containers=$("${compose[@]}" ps -aq) || fail "cannot inspect target project"
[[ -z $existing_containers ]] || fail "target Compose project already has containers (no data changed)"
for volume in "${target_volumes[@]}"; do
  if "${docker[@]}" volume inspect "$volume" >/dev/null 2>&1; then fail "target volume already exists: $volume (no data changed)"; fi
done

"${docker[@]}" run --rm --network none -i postgres:18.6-bookworm@sha256:bbf83594798e144bff365a9c191c5d779f181fb3135902064ab8ff11918020fc pg_restore --list <"$backup_dir/database.dump" >/dev/null 2>&1 || fail "invalid PostgreSQL custom archive"

echo "Creating fresh restore resources for project $project (volumes: ${target_volumes[*]})"
"${compose[@]}" up -d --wait db || fail "could not initialize new target database"
db_id=$("${compose[@]}" ps -q db)
[[ -n $db_id ]] || fail "target database container identity missing"
[[ $("${docker[@]}" inspect -f '{{index .Config.Labels "com.docker.compose.project"}}' "$db_id") == "$project" ]] || fail "target database project identity mismatch"
[[ $("${docker[@]}" inspect -f '{{.State.Health.Status}}' "$db_id") == healthy ]] || fail "target database is not healthy"
target_pg_version=$("${compose[@]}" exec -T db postgres --version | awk '{print $3}')
[[ $target_pg_version == 18.* ]] || fail "target PostgreSQL major version is incompatible"
empty=$("${compose[@]}" exec -T db sh -ec 'PGPASSWORD="$(cat /run/secrets/postgres_admin_password)" psql -XAt -h 127.0.0.1 -U running_dashboard_admin -d postgres -c "SELECT count(*) FROM pg_database WHERE datname = '\''running_dashboard'\''"')
[[ $empty == 1 ]] || fail "expected exactly one newly initialized target database"
tables=$("${compose[@]}" exec -T db sh -ec 'PGPASSWORD="$(cat /run/secrets/postgres_app_password)" psql -XAt -h 127.0.0.1 -U running_dashboard -d running_dashboard -c "SELECT count(*) FROM information_schema.tables WHERE table_schema='\''public'\''"')
[[ $tables == 0 ]] || fail "target database is not empty"

"${compose[@]}" exec -T db sh -ec 'PGPASSWORD="$(cat /run/secrets/postgres_app_password)" pg_restore --exit-on-error --single-transaction --no-owner --no-privileges --host=127.0.0.1 --username=running_dashboard --dbname=running_dashboard' <"$backup_dir/database.dump" || fail "pg_restore failed; target resources retained for investigation"
flyway=$("${compose[@]}" exec -T db sh -ec 'PGPASSWORD="$(cat /run/secrets/postgres_app_password)" psql -XAt -h 127.0.0.1 -U running_dashboard -d running_dashboard -c "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1"')
[[ $flyway == "$(<"$backup_dir/flyway-version.txt")" ]] || fail "restored Flyway version does not match backup"

fit_volume=${target_volumes[1]}
"${docker[@]}" run --rm -i --network none --mount "type=volume,src=$fit_volume,dst=/restore" postgres:18.6-bookworm@sha256:bbf83594798e144bff365a9c191c5d779f181fb3135902064ab8ff11918020fc sh -ec 'tar --no-same-owner --no-same-permissions -xzf - -C /restore && chown -R 10001:10001 /restore' <"$backup_dir/fit-data.tar.gz" || fail "FIT archive extraction failed; target resources retained for investigation"
echo "Database and FIT data restored into fresh project $project. App remains stopped; verify data before starting it."
