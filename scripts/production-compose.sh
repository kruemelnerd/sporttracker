#!/usr/bin/env bash

production_compose_args() {
  local env_file=$1 compose_name=$2 mode_count mode key_count key_file owner mode_bits
  local -n compose_ref=$compose_name
  mode_count=$(grep -c '^GARMIN_PRODUCTION_ENABLED=' "$env_file" || true)
  (( mode_count <= 1 )) || { echo 'production: GARMIN_PRODUCTION_ENABLED must occur at most once' >&2; return 1; }
  (( mode_count == 0 )) && return 0
  mode=$(sed -n 's/^GARMIN_PRODUCTION_ENABLED=//p' "$env_file")
  [[ $mode == true || $mode == false ]] || { echo 'production: GARMIN_PRODUCTION_ENABLED must be true or false' >&2; return 1; }
  [[ $mode == true ]] || return 0

  key_count=$(grep -c '^GARMIN_PERSONAL_API_KEY_FILE=' "$env_file" || true)
  (( key_count == 1 )) || { echo 'production: enabled Garmin requires exactly one GARMIN_PERSONAL_API_KEY_FILE path' >&2; return 1; }
  key_file=$(sed -n 's/^GARMIN_PERSONAL_API_KEY_FILE=//p' "$env_file")
  [[ $key_file == /* && -f $key_file && ! -L $key_file ]] || { echo 'production: Garmin key file must be an existing absolute regular file, not a symlink' >&2; return 1; }
  owner=$(stat -c '%u' -- "$key_file") || { echo 'production: cannot inspect Garmin key file ownership' >&2; return 1; }
  [[ $owner == 10001 ]] || { echo 'production: Garmin key file must be owned by app UID 10001' >&2; return 1; }
  mode_bits=$(stat -c '%a' -- "$key_file") || { echo 'production: cannot inspect Garmin key file permissions' >&2; return 1; }
  (( (8#$mode_bits & 077) == 0 && (8#$mode_bits & 0400) != 0 )) || { echo 'production: Garmin key file must be owner-readable and inaccessible to group/others' >&2; return 1; }
  compose_ref+=( -f "$(dirname -- "${BASH_SOURCE[0]}")/../compose.production.garmin.yaml" )
}
