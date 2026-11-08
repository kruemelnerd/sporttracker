# Garmin Personal API PoC

This opt-in command-line probe is not part of the application, FIT import, or any scheduler. It uses only the documented Garmin Personal API token, activity-search, activity-detail, and activity-download routes. No database is accessed.

## Run locally

1. Create a Garmin Personal API key in Garmin Connect's [Personal API Key settings](https://connect.garmin.com/app/settings/personalApiToken). Garmin displays it only once; choose an expiration period and store it in a local secret manager or protected environment file. Do not put it in a command argument, repository file, chat, or shell history.
2. From the repository root, load the user-managed file into a temporary shell environment and start the probe:

   ```sh
   (
     set -e
     set -a
     . "$HOME/.config/sporttracker/garmin-poc.env"
     set +a
     python3 tools/garmin-poc/garmin_poc.py --limit 3
   )
   ```

   The Python PoC reads only `GARMIN_PERSONAL_API_KEY` from its environment. It never opens the env file; the subshell keeps the variable out of the parent shell after exit. Do not enable shell tracing (`set -x`). If the variable is absent/empty, the probe stops and reports configuration is required.

   Optional documented search parameters: `--start <zero-based offset>`, `--start-date YYYY-MM-DD`, `--end-date YYYY-MM-DD`, and `--activity-type <Garmin type>`. Each call reads one page, with `--limit` capped at 10.
3. The summary JSON, individual JSON details, download ZIPs, and any contained FIT files are written only to `backend/data/garmin-poc/`, which Git ignores. The directory is restricted to the current user (`0700`), files to `0600`. Keep this local data private and remove it yourself when no longer needed.
4. Inspect a downloaded FIT using the existing FIT decoder without importing or persisting it:

   ```sh
   cd backend
   ./gradlew inspectGarminPocFit -PfitFile=data/garmin-poc/<activity-id>-<filename>.fit
   ```

The probe reports safe HTTP metadata (status, content type/length and counts), not credentials, activity IDs, filenames, or response bodies. It fails closed on invalid responses, unexpected archive contents, and oversized responses. Unit tests use synthetic local responses and are not evidence of successful live API access.

Garmin documents personal keys as expiring after 30, 90, 180 days, or one year. Access tokens last 24 hours or until the key expires; there is no refresh token, so a new token is obtained for each run. See the M9.1 research report for endpoint sources and known unknowns.
