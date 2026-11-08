"""Local-only probe for Garmin Connect Personal API; no app/database integration."""

import argparse
import json
import os
import stat
import sys
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from dataclasses import dataclass
from io import BytesIO
from pathlib import Path

BASE_URL = "https://personal.connectapi.garmin.com"
TOKEN_PATH = "/personalapitoken-service/personal-api/personal-api-key/token"
ACTIVITIES_PATH = "/activitylist-service/activities/search/activities"
MAX_RESPONSE_BYTES = 100 * 1024 * 1024


class PocError(Exception):
    """Sanitized request or local-data error."""


class NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


_HTTP_OPENER = urllib.request.build_opener(NoRedirectHandler())


def _open_request(request, timeout):
    return _HTTP_OPENER.open(request, timeout=timeout)


@dataclass
class Response:
    body: bytes
    status: int
    headers: dict

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return None

    def read(self, amount=-1):
        return self.body if amount < 0 else self.body[:amount]

    def getheader(self, name, default=None):
        return self.headers.get(name, default)


@dataclass
class Result:
    activities: int
    fit_files: int


def _request(url, method="GET", headers=None, body=None, opener=_open_request):
    request = urllib.request.Request(url, data=body, headers=headers or {}, method=method)
    try:
        with opener(request, timeout=30) as response:
            payload = response.read(MAX_RESPONSE_BYTES + 1)
            if len(payload) > MAX_RESPONSE_BYTES:
                raise PocError("Garmin response exceeds the 100 MiB PoC limit")
            return payload, response.status, response.headers
    except urllib.error.HTTPError as error:
        retry_after = error.headers.get("Retry-After", "unknown") if error.headers else "unknown"
        raise PocError(f"Garmin returned HTTP {error.code}, retry-after={retry_after}") from None
    except (urllib.error.URLError, TimeoutError, OSError) as error:
        raise PocError(f"Garmin request failed ({type(error).__name__})") from None


def _write(path, data):
    try:
        descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, stat.S_IRUSR | stat.S_IWUSR)
    except FileExistsError:
        raise PocError("PoC output already exists; refusing to overwrite local data") from None
    with os.fdopen(descriptor, "wb") as output:
        output.write(data)


def _checked_output(path, repo_root):
    output = path.resolve()
    expected = (Path(repo_root).resolve() / "backend" / "data" / "garmin-poc").resolve()
    if output != expected:
        raise PocError("output must be under backend/data/garmin-poc")
    output.mkdir(parents=True, exist_ok=True, mode=0o700)
    output.chmod(stat.S_IRWXU)
    return output


def run(output_dir, limit=3, start_date=None, end_date=None, activity_type=None,
        opener=_open_request, repo_root=None, start=0):
    key = os.environ.get("GARMIN_PERSONAL_API_KEY", "").strip()
    if not key:
        raise PocError("Set GARMIN_PERSONAL_API_KEY in the local environment")
    if not 1 <= limit <= 10:
        raise PocError("limit must be between 1 and 10")
    if start < 0:
        raise PocError("start must be zero or greater")
    output = _checked_output(Path(output_dir), repo_root or Path(__file__).resolve().parents[2])

    token, status, headers = _request(
        BASE_URL + TOKEN_PATH,
        method="POST",
        headers={"Authorization": f"ApiKey {key}", "Accept": "*/*"},
        opener=opener,
    )
    if status != 200 or not token.strip():
        raise PocError(f"Token request failed (HTTP {status})")
    bearer = token.decode("utf-8").strip()
    print(f"Token: HTTP {status}, content-type={headers.get('Content-Type', 'unknown')}, content-length={headers.get('Content-Length', 'unknown')}; token not displayed")

    params = {"start": start, "limit": limit}
    if start_date:
        params["startDate"] = start_date
    if end_date:
        params["endDate"] = end_date
    if activity_type:
        params["activityType"] = activity_type
    query = urllib.parse.urlencode(params)
    payload, status, headers = _request(
        f"{BASE_URL}{ACTIVITIES_PATH}?{query}", headers={"Authorization": f"Bearer {bearer}"}, opener=opener
    )
    if status != 200:
        raise PocError(f"Activity search failed (HTTP {status})")
    try:
        activities = json.loads(payload)
    except (UnicodeDecodeError, json.JSONDecodeError):
        raise PocError("Activity search returned invalid JSON") from None
    if not isinstance(activities, list):
        raise PocError("Activity search response was not a JSON list")
    if len(activities) > limit:
        raise PocError("Activity search returned more items than the requested PoC limit")
    _write(output / "activities.json", json.dumps(activities, indent=2).encode())
    print(f"Activity search: HTTP {status}, content-type={headers.get('Content-Type', 'unknown')}, content-length={headers.get('Content-Length', 'unknown')}, count={len(activities)}")

    fit_files = 0
    for activity in activities:
        activity_id = activity.get("activityId") if isinstance(activity, dict) else None
        if not isinstance(activity_id, int) or activity_id <= 0:
            raise PocError("Activity search item has no positive integer activityId")
        detail, detail_status, detail_headers = _request(
            f"{BASE_URL}/activity-service/activity/{activity_id}",
            headers={"Authorization": f"Bearer {bearer}"}, opener=opener
        )
        if detail_status != 200:
            raise PocError(f"Activity detail failed (HTTP {detail_status})")
        try:
            json.loads(detail)
        except (UnicodeDecodeError, json.JSONDecodeError):
            raise PocError("Activity detail returned invalid JSON") from None
        _write(output / f"{activity_id}.json", detail)
        print(f"Activity detail: HTTP {detail_status}, content-type={detail_headers.get('Content-Type', 'unknown')}, content-length={detail_headers.get('Content-Length', 'unknown')}")

        archive, file_status, file_headers = _request(
            f"{BASE_URL}/download-service/files/activity/{activity_id}",
            headers={"Authorization": f"Bearer {bearer}", "Accept": "application/zip, application/x-zip-compressed, application/octet-stream"},
            opener=opener,
        )
        if file_status != 200:
            raise PocError(f"Activity download failed (HTTP {file_status})")
        try:
            with zipfile.ZipFile(BytesIO(archive)) as zipped:
                entries = [entry for entry in zipped.infolist() if not entry.is_dir()]
                fit_entries = [entry for entry in entries if entry.filename.lower().endswith(".fit")]
                if sum(entry.file_size for entry in fit_entries) > MAX_RESPONSE_BYTES:
                    raise PocError("FIT entries exceed the 100 MiB PoC limit")
                for entry in fit_entries:
                    if Path(entry.filename).name != entry.filename:
                        raise PocError("Unsafe FIT entry name in activity archive")
                    if entry.file_size > MAX_RESPONSE_BYTES:
                        raise PocError("FIT entry exceeds the 100 MiB PoC limit")
                    fit_bytes = zipped.read(entry)
                    if len(fit_bytes) < 12 or fit_bytes[8:12] != b".FIT":
                        raise PocError("Invalid FIT file in activity archive")
                    _write(output / f"{activity_id}-{Path(entry.filename).name}", fit_bytes)
                _write(output / f"{activity_id}.zip", archive)
                fit_files += len(fit_entries)
                names = ", ".join(Path(entry.filename).name for entry in entries) or "no files"
        except zipfile.BadZipFile:
            raise PocError("Activity download was not a ZIP archive") from None
        print(f"Activity file: HTTP {file_status}, content-type={file_headers.get('Content-Type', 'unknown')}, content-length={file_headers.get('Content-Length', 'unknown')}, entries={len(entries)}, FIT entries={len(fit_entries)}")

    return Result(len(activities), fit_files)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--limit", type=int, default=3, help="number of activities in the selected page (1-10)")
    parser.add_argument("--start", type=int, default=0, help="zero-based activity result offset")
    parser.add_argument("--start-date", help="inclusive YYYY-MM-DD filter")
    parser.add_argument("--end-date", help="inclusive YYYY-MM-DD filter")
    parser.add_argument("--activity-type", help="Garmin activity type filter")
    parser.add_argument("--output-dir", default="backend/data/garmin-poc")
    args = parser.parse_args(argv)
    try:
        run(args.output_dir, args.limit, args.start_date, args.end_date, args.activity_type, start=args.start)
    except PocError as error:
        print(f"Garmin PoC: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
