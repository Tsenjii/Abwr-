"""A failed readiness probe must never be converted into an empty PASS report."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("v1_smoke", Path(__file__).resolve().parents[1] / "scripts/v1_smoke.py")
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


class ReadinessReportTests(unittest.TestCase):
    def run_probe(self, responses):
        output = io.StringIO()
        code = 0
        with patch.dict(smoke.os.environ, {"AI_BROWSER_TOKEN": "local-test-token", "AI_BROWSER_HOST": "http://127.0.0.1:8765"}), \
             patch.object(smoke.sys, "argv", ["v1_smoke.py"]), \
             patch.object(smoke, "check", side_effect=responses), contextlib.redirect_stdout(output):
            try:
                smoke.main()
            except SystemExit as error:
                code = error.code
        return code, json.loads(output.getvalue())

    def test_timeout_after_healthy_health_is_a_failure(self):
        code, report = self.run_probe([
            {"ok": True}, {"ok": False, "errorType": "TimeoutError"},
            {}, {"apiVersion": "0.8.0"}, {"tabs": []}, {},
        ])
        self.assertEqual(code, 1)
        self.assertEqual(report["status"]["errorType"], "TimeoutError")
        self.assertFalse(report["status"]["ok"])

    def test_api_alive_without_document_bridge_is_not_ready(self):
        code, report = self.run_probe([
            {"ok": True}, {"ready": True, "extensionPortReady": False},
            {}, {"apiVersion": "0.8.0"}, {"tabs": []}, {},
        ])
        self.assertEqual(code, 1)
        self.assertFalse(report["status"]["ok"])

    def test_ready_report_keeps_private_page_data_out(self):
        code, report = self.run_probe([
            {"ok": True}, {"ready": True, "extensionPortReady": True,
                           "url": "https://site.test/callback?code=private", "title": "private"},
            {}, {"apiVersion": "0.8.0"}, {"tabs": [{"url": "private"}]}, {},
        ])
        self.assertEqual(code, 0)
        self.assertTrue(report["status"]["ok"])
        self.assertNotIn("private", json.dumps(report))


if __name__ == "__main__":
    unittest.main()
