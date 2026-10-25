#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

usage() { echo "Usage: $0 --env-file PRIVATE_FILE --project COMPOSE_PROJECT ghcr.io/kruemelnerd/sporttracker@sha256:<64 lowercase hex>" >&2; }
fail() { echo "deploy: $*" >&2; exit 1; }

env_file= project= image=
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
(( (8#$mode & 077) == 0 )) || fail 'Compose environment file must not be accessible to group or other users'
command -v docker >/dev/null || fail 'Docker CLI is required'

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
repo_root=$(cd -- "$script_dir/.." && pwd -P)
compose_file=$repo_root/compose.production.yaml
docker=(docker)
compose=("${docker[@]}" compose --env-file "$env_file" -p "$project" -f "$compose_file")
override=$(mktemp)
rollback_override=
cleanup() { rm -f -- "$override"; [[ -z $rollback_override ]] || rm -f -- "$rollback_override"; }
trap cleanup EXIT

# The verifier must finish before Compose inspects or changes deployment resources.
"$script_dir/verify-release.sh" "$image"
printf 'services:\n  app:\n    image: "%s"\n' "$image" >"$override"
compose+=( -f "$override" )
"${compose[@]}" config --quiet || fail 'production Compose configuration is invalid'

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

if "${compose[@]}" up -d --wait --wait-timeout 180 app; then
  echo "Ready: $image"
  exit 0
fi

if [[ -n $old_image ]]; then
  echo 'New app failed readiness; restoring the previous digest.' >&2
  rollback_override=$(mktemp)
  printf 'services:\n  app:\n    image: "%s"\n' "$old_image" >"$rollback_override"
  rollback=("${docker[@]}" compose --env-file "$env_file" -p "$project" -f "$compose_file" -f "$rollback_override")
  "${rollback[@]}" up -d --wait --wait-timeout 180 app || fail 'rollback readiness failed; inspect containers and database schema immediately'
  fail 'new app failed readiness; previous digest restored'
fi
fail 'initial app failed readiness; no previous app version existed to restore'
