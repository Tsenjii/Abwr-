import importlib.util
import json
from pathlib import Path
import threading
import unittest
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("fixture", ROOT / "scripts/acceptance_fixture.py")
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)


class AcceptanceFixtureTests(unittest.TestCase):
    def test_real_html_and_cross_origin_link_do_not_fail_python_brace_formatting(self):
        server = fixture.make_server(0, 12345)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            host = "http://127.0.0.1:%d" % server.server_port
            with urllib.request.urlopen(host + "/A") as response:
                page = response.read().decode()
            self.assertIn('http://127.0.0.1:12345/child', page)
            self.assertIn("attachShadow({mode:'open'})", page)
            self.assertIn('id="late"', page)
            self.assertEqual(page.count('Navigation '), 180)
            with urllib.request.urlopen(urllib.request.Request(host + "/echo", data=b"name=fixture")) as response:
                self.assertEqual(json.load(response)["body"], "name=fixture")
            request = urllib.request.Request(host + "/http/echo", data=b'{"message":"hi"}', headers={"Content-Type":"application/json", "X-Abwr-Test":"fixture", "Cookie":"ABWR_CI_HTTP=one"})
            with urllib.request.urlopen(request) as response:
                echo = json.load(response)
                self.assertEqual(echo["custom"], "fixture")
                self.assertEqual(echo["cookie"], "ABWR_CI_HTTP=one")
                self.assertEqual(echo["contentType"], "application/json")
            with urllib.request.urlopen(host + "/download.bin") as response:
                self.assertIn("attachment", response.headers["Content-Disposition"])
                self.assertEqual(len(response.read()), 5 * 1024 * 1024)
        finally:
            server.shutdown(); server.server_close(); thread.join(2)


if __name__ == "__main__":
    unittest.main()
