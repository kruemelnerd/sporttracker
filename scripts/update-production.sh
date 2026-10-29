#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

usage() {
  echo "Usage: bash scripts/update-production.sh --env-file PRIVATE_FILE --project sporttracker-prod --backup-dir PRIVATE_DIR ghcr.io/kruemelnerd/sporttracker@sha256:<64 lowercase hex>" >&2
}
fail() { echo "update: $*" >&2; exit 1; }

env_file='' project='' backup_dir='' image=''
while (($#)); do
  case "$1" in
    --env-file|--project|--backup-dir)
      (($# >= 2)) || { usage; exit 2; }
      case "$1" in
        --env-file) env_file=$2 ;;
        --project) project=$2 ;;
        --backup-dir) backup_dir=$2 ;;
      esac
      shift 2 ;;
    *)
      [[ -z $image ]] || { usage; exit 2; }
      image=$1
      shift ;;
  esac
done
[[ -n $env_file && -n $project && -n $backup_dir && -n $image ]] || { usage; exit 2; }
[[ $project == sporttracker-prod ]] || fail 'only the explicit production Compose project is supported'
[[ $image =~ ^ghcr\.io/kruemelnerd/sporttracker@sha256:[0-9a-f]{64}$ ]] || { usage; exit 2; }
[[ $env_file == /* && $backup_dir == /* ]] || fail 'environment and backup paths must be absolute'
[[ -f $env_file && ! -L $env_file && -O $env_file ]] || fail 'private environment file must exist, be a regular file and be owned by the current user'
env_mode=$(stat -c '%a' -- "$env_file") || fail 'cannot inspect environment permissions'
(( (8#$env_mode & 077) == 0 && (8#$env_mode & 0200) != 0 )) || fail 'environment file must be private and owner-writable'
[[ -d $backup_dir && ! -L $backup_dir && -O $backup_dir ]] || fail 'backup directory must already exist, be a real directory and be owned by the current user'
backup_mode=$(stat -c '%a' -- "$backup_dir") || fail 'cannot inspect backup directory'
(( (8#$backup_mode & 077) == 0 )) || fail 'backup directory must not be accessible to group or others'
[[ -z ${DOCKER_HOST:-} && -z ${DOCKER_CONTEXT:-} ]] || fail 'unset DOCKER_HOST and DOCKER_CONTEXT to avoid targeting a different Docker daemon'
command -v flock >/dev/null || fail 'flock is required'
command -v docker >/dev/null || fail 'Docker CLI is required'

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
repo_root=$(cd -- "$script_dir/.." && pwd -P)
compose_file=$repo_root/compose.production.yaml
[[ -f $compose_file && -f $script_dir/backup.sh && -f $script_dir/deploy-verified.sh ]] || fail 'required production scripts or Compose file are missing'

# Serialize manual updates on this host. Never delete the lock file (another process may hold it).
exec 9>"$backup_dir/.update.lock"
flock -n 9 || fail 'another production update is running'

# The backup script uses the explicit "default" context; deploy-verified.sh uses docker's default.
# Refuse a remote/reconfigured default so both scripts must target the same local daemon.
endpoint=$(docker context inspect default --format '{{.Endpoints.docker.Host}}') || fail 'cannot inspect default Docker context'
[[ $endpoint == unix:///var/run/docker.sock ]] || fail 'default Docker context is not the local Docker socket'

[[ $(grep -c '^SPORTTRACKER_IMAGE=' "$env_file" || true) == 1 ]] || fail 'unexpected image repository configuration'
[[ $(sed -n 's/^SPORTTRACKER_IMAGE=//p' "$env_file") == ghcr.io/kruemelnerd/sporttracker ]] || fail 'unexpected image repository'
[[ $(grep -c '^SPORTTRACKER_IMAGE_DIGEST=' "$env_file" || true) == 1 ]] || fail 'image digest configuration must occur exactly once'
current_digest=$(sed -n 's/^SPORTTRACKER_IMAGE_DIGEST=//p' "$env_file")
[[ $current_digest =~ ^sha256:[0-9a-f]{64}$ ]] || fail 'invalid currently configured image digest'

app_id=$(docker --context default compose --env-file "$env_file" -p "$project" -f "$compose_file" ps -q app) || fail 'cannot inspect running application'
[[ -n $app_id ]] || fail 'no running production application; use the documented first-install procedure'
[[ $(docker --context default inspect -f '{{.State.Status}}' "$app_id") == running ]] || fail 'production application is not running'
[[ $(docker --context default inspect -f '{{.State.Health.Status}}' "$app_id") == healthy ]] || fail 'production application is not healthy'
running_image=$(docker --context default inspect -f '{{.Config.Image}}' "$app_id") || fail 'cannot identify running image'
[[ $running_image == "ghcr.io/kruemelnerd/sporttracker@$current_digest" ]] || fail 'running image differs from saved image digest; investigate before updating'

if [[ $current_digest == "${image##*@}" ]]; then
  echo "Already running the requested verified image; no update or backup required."
  exit 0
fi

echo 'Creating and verifying a pre-update backup (brief application downtime)...'
bash "$script_dir/backup.sh" \
  --docker-context default \
  --project "$project" \
  --compose-file "$compose_file" \
  --env-file "$env_file" \
  --output-dir "$backup_dir" || fail 'backup failed; deployment was NOT started'

echo 'Backup successful. Verifying and deploying the requested release...'
bash "$script_dir/deploy-verified.sh" \
  --env-file "$env_file" \
  --project "$project" \
  "$image"

echo 'Update successful. Current production services:'
docker --context default compose --env-file "$env_file" -p "$project" -f "$compose_file" ps
