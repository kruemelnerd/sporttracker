import json
import io
import stat
import tempfile
import unittest
import zipfile
from contextlib import redirect_stdout
from email.message import Message
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import parse_qs, urlparse
from unittest.mock import patch

import garmin_poc


class GarminPocTests(unittest.TestCase):
    def test_redirects_are_not_followed(self):
        handler = garmin_poc.NoRedirectHandler()
        request = garmin_poc.urllib.request.Request("https://personal.connectapi.garmin.com/test")
        response = type("RedirectResponse", (), {})()
        self.assertIsNone(handler.redirect_request(request, response, 302, "Found", {}, "http://127.0.0.1/"))

    def test_missing_key_fails_without_request(self):
        with patch.dict("os.environ", {}, clear=True):
            with self.assertRaisesRegex(garmin_poc.PocError, "GARMIN_PERSONAL_API_KEY"):
                garmin_poc.run(Path("backend/data/garmin-poc"), 1, opener=lambda *_a, **_k: self.fail("network called"))

    def test_runs_documented_endpoints_and_saves_private_outputs(self):
        requests = []
        archive_buffer = io.BytesIO()
        with zipfile.ZipFile(archive_buffer, "w") as archive:
            archive.writestr("activity.fit", b"\x00" * 8 + b".FIT" + b"synthetic")

        def opener(request, timeout):
            requests.append(request)
            if request.full_url.endswith("/personal-api-key/token"):
                return garmin_poc.Response(b"opaque-test-token", 200, {"Content-Type": "text/plain"})
            if "/activities/search/activities?" in request.full_url:
                return garmin_poc.Response(json.dumps([{"activityId": 42}]).encode(), 200, {"Content-Type": "application/json; note=garmin-secret", "Content-Length": "garmin-secret"})
            if request.full_url.endswith("/files/activity/42"):
                return garmin_poc.Response(archive_buffer.getvalue(), 200, {"Content-Type": "application/zip"})
            if request.full_url.endswith("/activity/42"):
                return garmin_poc.Response(b'{"activityId":42}', 200, {"Content-Type": "application/json"})
            self.fail(f"unexpected request {request.full_url}")

        with tempfile.TemporaryDirectory() as temp, patch.dict("os.environ", {"GARMIN_PERSONAL_API_KEY": "test-key"}):
            out = Path(temp) / "backend" / "data" / "garmin-poc"
            captured = io.StringIO()
            with redirect_stdout(captured):
                result = garmin_poc.run(out, 1, start_date="2026-01-01", end_date="2026-01-31",
                                        activity_type="running", opener=opener, repo_root=temp, start=10)

            self.assertNotIn("garmin-secret", captured.getvalue())
            self.assertEqual(result.activities, 1)
            self.assertEqual(result.fit_files, 1)
            self.assertTrue((out / "activities.json").is_file())
            self.assertTrue((out / "42.json").is_file())
            self.assertTrue((out / "42.zip").is_file())
            self.assertTrue((out / "42-activity.fit").is_file())
            self.assertEqual(stat.S_IMODE(out.stat().st_mode), 0o700)
            self.assertEqual(stat.S_IMODE((out / "42.json").stat().st_mode), 0o600)
            self.assertEqual(requests[0].get_header("Authorization"), "ApiKey test-key")
            self.assertEqual(requests[1].get_header("Authorization"), "Bearer opaque-test-token")
            self.assertEqual(parse_qs(urlparse(requests[1].full_url).query)["limit"], ["1"])
            query = parse_qs(urlparse(requests[1].full_url).query)
            self.assertEqual(query["start"], ["10"])
            self.assertEqual(query["startDate"], ["2026-01-01"])
            self.assertEqual(query["endDate"], ["2026-01-31"])
            self.assertEqual(query["activityType"], ["running"])

    def test_rejects_non_zip_activity_download(self):
        def opener(request, timeout):
            if request.full_url.endswith("/personal-api-key/token"):
                return garmin_poc.Response(b"opaque-token", 200, {})
            if "/activities/search/activities?" in request.full_url:
                return garmin_poc.Response(b'[{"activityId":42}]', 200, {})
            if request.full_url.endswith("/files/activity/42"):
                return garmin_poc.Response(b"not zip", 200, {})
            return garmin_poc.Response(b'{"activityId":42}', 200, {})

        with tempfile.TemporaryDirectory() as temp, patch.dict("os.environ", {"GARMIN_PERSONAL_API_KEY": "test-key"}):
            with self.assertRaisesRegex(garmin_poc.PocError, "not a ZIP"):
                garmin_poc.run(Path(temp) / "backend/data/garmin-poc", 1, opener=opener, repo_root=temp)

    def test_http_failure_does_not_expose_response_body_or_key(self):
        def opener(request, timeout):
            raise HTTPError(request.full_url, 401, "Unauthorized", {}, None)

        with patch.dict("os.environ", {"GARMIN_PERSONAL_API_KEY": "secret-test-key"}):
            with self.assertRaises(garmin_poc.PocError) as raised:
                garmin_poc.run(Path("backend/data/garmin-poc"), 1, opener=opener)
        self.assertNotIn("secret-test-key", str(raised.exception))

    def test_rejects_output_outside_ignored_backend_data(self):
        with patch.dict("os.environ", {"GARMIN_PERSONAL_API_KEY": "test-key"}):
            with self.assertRaisesRegex(garmin_poc.PocError, "backend/data/garmin-poc"):
                garmin_poc.run(Path("/tmp/not-ignored"), 1, opener=lambda *_a, **_k: self.fail("network called"), repo_root="/tmp/repo")

    def test_rejects_symlinked_output_directory_before_request(self):
        with tempfile.TemporaryDirectory() as temp, patch.dict("os.environ", {"GARMIN_PERSONAL_API_KEY": "test-key"}):
            backend_data = Path(temp) / "backend" / "data"
            backend_data.mkdir(parents=True)
            tracked = Path(temp) / "tracked"
            tracked.mkdir()
            (backend_data / "garmin-poc").symlink_to(tracked, target_is_directory=True)
            with self.assertRaisesRegex(garmin_poc.PocError, "symlink"):
                garmin_poc.run(backend_data / "garmin-poc", 1,
                               opener=lambda *_a, **_k: self.fail("network called"), repo_root=temp)

    def test_http_error_does_not_echo_retry_after_header(self):
        headers = Message()
        headers["Retry-After"] = "private-garmin-secret"

        def opener(request, timeout):
            raise HTTPError(request.full_url, 429, "Too Many Requests", headers, None)

        with patch.dict("os.environ", {"GARMIN_PERSONAL_API_KEY": "test-key"}):
            with self.assertRaises(garmin_poc.PocError) as raised:
                garmin_poc.run(Path("backend/data/garmin-poc"), 1, opener=opener)
        self.assertNotIn("private-garmin-secret", str(raised.exception))


if __name__ == "__main__":
    unittest.main()
