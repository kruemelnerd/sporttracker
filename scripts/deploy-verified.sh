#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

usage() { echo "Usage: $0 --env-file PRIVATE_FILE --project COMPOSE_PROJECT ghcr.io/kruemelnerd/sporttracker@sha256:<64 lowercase hex>" >&2; }
fail() { echo "deploy: $*" >&2; exit 1; }

env_file='' project='' image=''
while (($#)); do
  case "$1" in
    --env-file) (($# >= 2)) || { usage; exit 2; }; env_file=$2; shift 2 ;;
    --project) (($# >= 2)) || { usage; exit 2; }; project=$2; shift 2 ;;
    *) [[ -z $image ]] || { usage; exit 2; }; image=$1; shift ;;
  esac
done
[[ -n $env_file && -n $project && -n $image ]] || { usage; exit 2; }
[[ $project =~ ^[a-zA-Z0-9][a-zA-Z0-9_-]*$ ]] || fail 'invalid Compose project name'
[[ $image =~ ^ghcr\.io/kruemelnerd/sporttracker@sha256:[0-9a-f]{64}$ ]] || { usage; exit 2; }
[[ -f $env_file && ! -L $env_file ]] || fail 'private Compose environment file not found or is a symlink'
mode=$(stat -c '%a' "$env_file") || fail 'cannot inspect Compose environment file permissions'
(( (8#$mode & 077) == 0 && (8#$mode & 0200) != 0 )) || fail 'Compose environment file must be private and owner-writable'
[[ $(stat -c '%u' "$env_file") == "$EUID" ]] || fail 'Compose environment file must be owned by the deployment user'
env_dir=$(dirname -- "$env_file")
dir_mode=$(stat -c '%a' "$env_dir") || fail 'cannot inspect Compose environment directory permissions'
(( (8#$dir_mode & 022) == 0 )) || fail 'Compose environment directory must not be group- or world-writable'
command -v docker >/dev/null || fail 'Docker CLI is required'

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
repo_root=$(cd -- "$script_dir/.." && pwd -P)
compose_file=$repo_root/compose.production.yaml
docker=(docker)
compose=("${docker[@]}" compose --env-file "$env_file" -p "$project" -f "$compose_file")
override=$(mktemp)
saved_env=
updated_env=
# shellcheck disable=SC2329
cleanup() { rm -f -- "$override"; [[ -z $updated_env ]] || rm -f -- "$updated_env"; }
trap cleanup EXIT

# The verifier must finish before Compose inspects or changes deployment resources.
"$script_dir/verify-release.sh" "$image"
printf 'services:\n  app:\n    image: "%s"\n' "$image" >"$override"
compose+=( -f "$override" )
"${compose[@]}" config --quiet || fail 'production Compose configuration is invalid'

[[ $(grep -c '^SPORTTRACKER_IMAGE=' "$env_file" || true) == 1 ]] || fail 'Compose environment must contain exactly one SPORTTRACKER_IMAGE assignment'
[[ $(sed -n 's/^SPORTTRACKER_IMAGE=//p' "$env_file") == ghcr.io/kruemelnerd/sporttracker ]] || fail 'Compose environment has an unexpected image repository'
[[ $(grep -c '^SPORTTRACKER_IMAGE_DIGEST=' "$env_file" || true) == 1 ]] || fail 'Compose environment must contain exactly one SPORTTRACKER_IMAGE_DIGEST assignment'
old_digest=$(sed -n 's/^SPORTTRACKER_IMAGE_DIGEST=//p' "$env_file")
[[ $old_digest =~ ^sha256:[0-9a-f]{64}$ ]] || fail 'saved image digest is missing or invalid'

for volume in sporttracker-prod-postgres sporttracker-prod-fit; do
  "${docker[@]}" volume inspect "$volume" >/dev/null 2>&1 || fail "required external volume is missing: $volume (create it deliberately; no volumes were created)"
done

old_id=$("${compose[@]}" ps -q app) || fail 'cannot inspect current app service'
old_image=
if [[ -n $old_id ]]; then
  [[ $("${docker[@]}" inspect -f '{{.State.Status}}' "$old_id") == running ]] || fail 'existing app is not running; refusing to replace it'
  [[ $("${docker[@]}" inspect -f '{{.State.Health.Status}}' "$old_id") == healthy ]] || fail 'existing app is not healthy; refusing to replace it'
  old_image=$("${docker[@]}" inspect -f '{{.Config.Image}}' "$old_id") || fail 'cannot identify existing app image'
  [[ $old_image =~ ^ghcr\.io/kruemelnerd/sporttracker@sha256:[0-9a-f]{64}$ ]] || fail 'existing app image is not a trusted digest reference; refusing replacement'
fi

saved_env="${env_file}.previous.$(date -u +%Y%m%dT%H%M%S.%NZ)"
[[ ! -e $saved_env && ! -L $saved_env ]] || fail 'previous-configuration backup path already exists; refusing ambiguous update'
cp -p -- "$env_file" "$saved_env" || fail 'could not preserve previous Compose environment'
updated_env=$(mktemp "${env_file}.tmp.XXXXXXXX") || fail 'could not prepare atomic Compose environment update'
if ! awk -v digest="${image##*@}" '
  /^SPORTTRACKER_IMAGE_DIGEST=/ { count++; print "SPORTTRACKER_IMAGE_DIGEST=" digest; next }
  { print }
  END { if (count != 1) exit 1 }
' "$env_file" >"$updated_env"; then
  fail 'could not safely update saved image digest'
fi
chmod "$mode" "$updated_env" || fail 'could not preserve Compose environment permissions'
mv -f -- "$updated_env" "$env_file" || fail "could not atomically persist verified digest; previous configuration is at $saved_env"
updated_env=

if "${compose[@]}" up -d --wait --wait-timeout 180 app; then
  echo "Ready: $image (saved configuration: $env_file; previous: $saved_env)"
  exit 0
fi

fail 'new app failed readiness; no automatic image rollback was attempted because Flyway may have changed the database schema. Keep resources intact and perform the documented manual recovery/restore procedure.'
