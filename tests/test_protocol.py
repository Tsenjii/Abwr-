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
    def test_mcp_bridge_can_be_imported(self):
        import importlib.util
        p = ROOT / "bridge/mcp_stdio.py"
        spec = importlib.util.spec_from_file_location("abwr_mcp", p)
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        self.assertTrue(any(x["name"] == "browser_dom" for x in mod.TOOLS))
if __name__ == "__main__": unittest.main()
