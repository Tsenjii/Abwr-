import json
import pathlib
import unittest
ROOT = pathlib.Path(__file__).resolve().parents[1]

class ProtocolTests(unittest.TestCase):
    def test_browser_extension_manifest(self):
        data = json.loads((ROOT / "app/src/main/assets/agentbridge/manifest.json").read_text())
        self.assertEqual(data["manifest_version"], 2)
        self.assertIn("nativeMessaging", data["permissions"])
    def test_docs_exist(self):
        for name in ("PROJECT_GOALS.md", "PROGRESS.md", "HANDOFF.md", "API.md"):
            self.assertTrue((ROOT / "docs" / name).is_file())
    def test_agent_api_background_and_lifecycle_contract(self):
        controller = (ROOT / "app/src/main/java/dev/local/aibrowser/BrowserController.kt").read_text()
        server = (ROOT / "app/src/main/java/dev/local/aibrowser/LocalApiServer.kt").read_text()
        display = (ROOT / "app/src/main/java/dev/local/aibrowser/OffscreenBrowserDisplay.kt").read_text()
        self.assertIn("inFlightByProfile", controller)
        self.assertIn("pruneSessions(preserveId = id)", controller)
        self.assertIn("navigateAndWaitAsync", controller)
        self.assertIn("openExtraTab", controller)
        self.assertIn("setTrackingProtection", controller)
        self.assertIn("bookmarks(profile", controller)
        self.assertIn("humanBack()", controller)
        self.assertIn("lastShots.size > 3", controller)
        self.assertIn("experimentalBackgroundFramesReceived", controller)
        self.assertIn('"/v1/diagnostics"', server)
        self.assertIn("waitForLoad", server)
        self.assertIn('"/v1/screenshot/meta"', server)
        self.assertIn("framesReceived", display)

    def test_mcp_bridge_can_be_imported(self):
        import importlib.util
        p = ROOT / "bridge/mcp_stdio.py"
        spec = importlib.util.spec_from_file_location("abwr_mcp", p)
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        self.assertTrue(any(x["name"] == "browser_dom" for x in mod.TOOLS))
if __name__ == "__main__": unittest.main()
