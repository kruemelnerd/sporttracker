# Garmin Personal API PoC

This opt-in command-line probe is not part of the application, FIT import, or any scheduler. It uses only the documented Garmin Personal API token, activity-search, activity-detail, and activity-download routes. No database is accessed.

## Run locally

1. Create a Garmin Personal API key in Garmin Connect's [Personal API Key settings](https://connect.garmin.com/app/settings/personalApiToken). Garmin displays it only once; choose an expiration period and store the key in a local secret manager or protected shell environment. Do not put it in a command argument, repository file, chat, or shell history.
2. In a shell where the key is already securely available as `GARMIN_PERSONAL_API_KEY`, from the repository root run:

   ```sh
   python3 tools/garmin-poc/garmin_poc.py --limit 3
   ```

   Optional documented search parameters: `--start <zero-based offset>`, `--start-date YYYY-MM-DD`, `--end-date YYYY-MM-DD`, and `--activity-type <Garmin type>`. Each call reads one page, with `--limit` capped at 10.
3. The summary JSON, individual JSON details, download ZIPs, and any contained FIT files are written only to `backend/data/garmin-poc/`, which Git ignores. The directory is restricted to the current user (`0700`), files to `0600`. Keep this local data private and remove it yourself when no longer needed.
4. Inspect a downloaded FIT using the existing FIT decoder without importing or persisting it:

   ```sh
   cd backend
   ./gradlew inspectGarminPocFit -PfitFile=data/garmin-poc/<activity-id>-<filename>.fit
   ```

The probe reports safe HTTP metadata (status, content type/length and counts), not credentials, activity IDs, filenames, or response bodies. It fails closed on invalid responses, unexpected archive contents, and oversized responses. Unit tests use synthetic local responses and are not evidence of successful live API access.

Garmin documents personal keys as expiring after 30, 90, 180 days, or one year. Access tokens last 24 hours or until the key expires; there is no refresh token, so a new token is obtained for each run. See the M9.1 research report for endpoint sources and known unknowns.
