#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

usage() { echo "Usage: $0 --docker-context NAME --project NAME --compose-file FILE --env-file FILE --output-dir DIR" >&2; }
fail() { echo "backup: $*" >&2; exit 1; }

docker_context='' project='' compose_file='' env_file='' output_dir=''
while (($#)); do
  case "$1" in
    --docker-context) (($# >= 2)) || { usage; exit 2; }; docker_context=$2; shift 2 ;;
    --project) (($# >= 2)) || { usage; exit 2; }; project=$2; shift 2 ;;
    --compose-file) (($# >= 2)) || { usage; exit 2; }; compose_file=$2; shift 2 ;;
    --env-file) (($# >= 2)) || { usage; exit 2; }; env_file=$2; shift 2 ;;
    --output-dir) (($# >= 2)) || { usage; exit 2; }; output_dir=$2; shift 2 ;;
    *) usage; exit 2 ;;
  esac
done
[[ -n $docker_context && -n $project && -n $compose_file && -n $env_file && -n $output_dir ]] || { usage; exit 2; }
[[ $project =~ ^[a-zA-Z0-9][a-zA-Z0-9_-]*$ ]] || fail "invalid Compose project name"
[[ -f $compose_file && -f $env_file && ! -L $env_file ]] || fail "Compose file or private environment file not found"
env_mode=$(stat -c '%a' "$env_file")
(( (8#$env_mode & 077) == 0 )) || fail "Compose environment file must not be accessible to group or other users"
command -v docker >/dev/null || fail "docker is required"
command -v realpath >/dev/null || fail "realpath is required"
docker=(docker --context "$docker_context")
"${docker[@]}" context inspect "$docker_context" >/dev/null 2>&1 || fail "Docker context is not available"
command -v tar >/dev/null || fail "tar is required"
command -v sha256sum >/dev/null || fail "sha256sum is required"
output_dir=$(realpath -m -- "$output_dir")
app_id='' app_stopped=0 tmp=''
published=
cleanup() { [[ -z $tmp ]] || rm -rf -- "$tmp"; }
restart_app() {
  if ((app_stopped)); then
    if ! "${docker[@]}" compose --env-file "$env_file" -p "$project" -f "$compose_file" start app >/dev/null; then
      echo "CRITICAL: backup failed and application did not restart (project $project)" >&2
      return 1
    fi
    local ready=0
    for _ in {1..45}; do
      if "${docker[@]}" compose --env-file "$env_file" -p "$project" -f "$compose_file" exec -T app java -cp /healthcheck Healthcheck >/dev/null 2>&1; then ready=1; break; fi
      sleep 2
    done
    if ((!ready)); then
      echo "CRITICAL: application restarted but readiness check failed (project $project)" >&2
      return 1
    fi
    app_stopped=0
  fi
}
on_exit() {
  local rc=$?
  trap - EXIT
  if ! restart_app; then rc=1; fi
  cleanup
  if ((rc == 0)) && [[ -n $published ]]; then echo "Backup created: $published"; fi
  exit "$rc"
}
compose=("${docker[@]}" compose --env-file "$env_file" -p "$project" -f "$compose_file")
app_id=$("${compose[@]}" ps -q app) || fail "cannot identify app service"
db_id=$("${compose[@]}" ps -q db) || fail "cannot identify db service"
[[ -n $app_id && -n $db_id ]] || fail "app and db must both be running in the specified project"
[[ $("${docker[@]}" inspect -f '{{index .Config.Labels "com.docker.compose.project"}}' "$app_id") == "$project" ]] || fail "app project identity mismatch"
[[ $("${docker[@]}" inspect -f '{{index .Config.Labels "com.docker.compose.project"}}' "$db_id") == "$project" ]] || fail "database project identity mismatch"
[[ $("${docker[@]}" inspect -f '{{.State.Health.Status}}' "$db_id") == healthy ]] || fail "database is not healthy"
app_volume=$("${docker[@]}" inspect -f '{{range .Mounts}}{{if eq .Destination "/data"}}{{.Name}}{{end}}{{end}}' "$app_id")
[[ -n $app_volume ]] || fail "persistent FIT volume at /data could not be identified"
db_volume=$("${docker[@]}" inspect -f '{{range .Mounts}}{{if eq .Destination "/var/lib/postgresql"}}{{.Name}}{{end}}{{end}}' "$db_id")
[[ -n $db_volume ]] || fail "persistent PostgreSQL volume could not be identified"
fit_mount=$("${docker[@]}" volume inspect -f '{{.Mountpoint}}' "$app_volume") || fail "could not identify FIT volume mountpoint"
db_mount=$("${docker[@]}" volume inspect -f '{{.Mountpoint}}' "$db_volume") || fail "could not identify PostgreSQL volume mountpoint"
case "$output_dir/" in "$fit_mount/"*|"$db_mount/"*) fail "backup destination must be outside source data volumes" ;; esac
fit_root_mode=$("${docker[@]}" exec "$app_id" stat -c '%a' /data) || fail "could not read FIT directory permissions"
fit_root_owner=$("${docker[@]}" exec "$app_id" stat -c '%u:%g' /data) || fail "could not read FIT directory ownership"
[[ $fit_root_owner == 10001:10001 ]] || fail "FIT volume root is not owned by the application user"
other_fit_owner=$("${docker[@]}" exec "$app_id" find /data -xdev \( ! -uid 10001 -o ! -gid 10001 \) -print -quit) || fail "could not verify FIT file ownership"
[[ -z $other_fit_owner ]] || fail "FIT volume contains files not owned by the application user"
if [[ -e $output_dir ]]; then
  [[ -d $output_dir && ! -L $output_dir ]] || fail "backup destination must be a real directory"
  output_mode=$(stat -c '%a' "$output_dir")
  (( (8#$output_mode & 077) == 0 )) || fail "backup destination must not be accessible to group or other users"
else
  mkdir -p -- "$output_dir"
fi
output_dir=$(cd -- "$output_dir" && pwd -P)
case "$output_dir/" in "$fit_mount/"*|"$db_mount/"*) fail "backup destination must be outside source data volumes" ;; esac
tmp=$(mktemp -d "$output_dir/.sporttracker-backup.XXXXXXXX")
trap on_exit EXIT
image_id=$("${docker[@]}" inspect -f '{{.Image}}' "$app_id")
image_ref=$("${docker[@]}" inspect -f '{{.Config.Image}}' "$app_id")
image_digest=$("${docker[@]}" inspect -f '{{.Image}}' "$app_id")
pg_version=$("${compose[@]}" exec -T db postgres --version | awk '{print $3}')
[[ $pg_version == 18.* ]] || fail "expected PostgreSQL 18, found $pg_version"

stamp=$(date -u +%Y%m%dT%H%M%SZ)
backup_name="sporttracker-${project}-${stamp}"
final="$output_dir/$backup_name"
[[ ! -e $final ]] || fail "backup destination already exists"
mkdir -m 700 "$tmp/$backup_name"
echo "Stopping app service in Compose project $project"
app_stopped=1
"${compose[@]}" stop -t 60 app >/dev/null || fail "could not stop application"

"${compose[@]}" exec -T db sh -ec 'PGPASSWORD="$(cat /run/secrets/postgres_app_password)" pg_dump --format=custom --no-owner --no-privileges --host=127.0.0.1 --username=running_dashboard --dbname=running_dashboard' >"$tmp/$backup_name/database.dump" || fail "pg_dump failed"
"${compose[@]}" exec -T db sh -ec 'PGPASSWORD="$(cat /run/secrets/postgres_app_password)" psql -XAt --host=127.0.0.1 --username=running_dashboard --dbname=running_dashboard -c "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1"' >"$tmp/$backup_name/flyway-version.txt" || fail "could not read Flyway version"
[[ -s $tmp/$backup_name/flyway-version.txt ]] || fail "Flyway version is missing"

mkdir "$tmp/fit"
"${docker[@]}" cp "$app_id:/data/." "$tmp/fit/" >/dev/null || fail "could not copy FIT volume contents"
chmod "$fit_root_mode" "$tmp/fit"
if find "$tmp/fit" \( -type l -o -type b -o -type c -o -type p -o -type s \) -print -quit | grep -q .; then
  fail "FIT data contains a link or special file that cannot be safely restored"
fi
if find "$tmp/fit" -perm /7022 -print -quit | grep -q .; then
  fail "FIT data contains unsafe special or group/world-writable permissions"
fi
tar --numeric-owner --owner=10001 --group=10001 -czpf "$tmp/$backup_name/fit-data.tar.gz" -C "$tmp/fit" . || fail "FIT archive failed"
tar -tzf "$tmp/$backup_name/fit-data.tar.gz" >/dev/null || fail "FIT archive verification failed"
"${docker[@]}" compose version >"$tmp/$backup_name/compose-version.txt" 2>/dev/null || fail "could not record Compose version"
cat >"$tmp/$backup_name/metadata.txt" <<EOF
format_version=1
created_at_utc=$stamp
source_compose_project=$project
source_docker_context=$docker_context
source_compose_file=$(basename -- "$compose_file")
source_compose_env_file=$(basename -- "$env_file")
application_image=$image_ref
application_image_id=$image_id
application_image_digest=$image_digest
postgres_image=$("${docker[@]}" inspect -f '{{.Config.Image}}' "$db_id")
postgres_version=$pg_version
database_name=running_dashboard
database_role=running_dashboard
flyway_version=$(<"$tmp/$backup_name/flyway-version.txt")
fit_volume_name=$app_volume
fit_root_mode=$fit_root_mode
fit_owner=$fit_root_owner
postgres_volume_name=$db_volume
restore_requires=new isolated volumes and an empty database; see docs/containerization.md
EOF
(cd "$tmp/$backup_name" && sha256sum database.dump fit-data.tar.gz flyway-version.txt compose-version.txt metadata.txt > SHA256SUMS)
(cd "$tmp/$backup_name" && sha256sum --check SHA256SUMS >/dev/null) || fail "backup checksum verification failed"
"${compose[@]}" exec -T db pg_restore --list <"$tmp/$backup_name/database.dump" >/dev/null 2>&1 || fail "custom-format PostgreSQL archive verification failed"
chmod 600 "$tmp/$backup_name"/*
mv -- "$tmp/$backup_name" "$final" || fail "could not publish backup atomically"
published=$final
