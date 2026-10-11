"""Browser-shell regression tests that do not require an Android emulator."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
CONTROLLER = (ROOT / "app/src/main/java/dev/local/aibrowser/BrowserController.kt").read_text()
ACTIVITY = (ROOT / "app/src/main/java/dev/local/aibrowser/MainActivity.kt").read_text()
SEARCH = (ROOT / "app/src/main/java/dev/local/aibrowser/BrowserSearch.kt").read_text()
ROUTER = (ROOT / "app/src/main/java/dev/local/aibrowser/LocalApiServer.kt").read_text()
TABS = (ROOT / "app/src/main/java/dev/local/aibrowser/TabOverview.kt").read_text()
MANIFEST = (ROOT / "app/src/main/AndroidManifest.xml").read_text()


class BrowserShellContract(unittest.TestCase):
    def test_native_gecko_finder_and_back_tracking(self):
        self.assertIn("session.finder.find(query, flags)", CONTROLLER)
        self.assertIn("session.finder.setDisplayFlags", CONTROLLER)
        self.assertIn("fun clearFind(", CONTROLLER)
        self.assertIn("override fun onCanGoBack", CONTROLLER)
        self.assertIn("override fun onCanGoForward", CONTROLLER)
        self.assertIn("fun canGoBack()", CONTROLLER)
        self.assertIn("override fun onBackPressed()", ACTIVITY)

    def test_desktop_settings_persist_and_do_not_reload_every_tab(self):
        self.assertIn('desktop_profiles_v1', CONTROLLER)
        self.assertIn("userAgentMode(", CONTROLLER)
        self.assertIn("viewportMode(", CONTROLLER)
        self.assertIn("selectedLoadedSession(profile)?.reload()", CONTROLLER)
        self.assertIn('"desktopMode"', CONTROLLER)

    def test_search_engines_and_regular_android_links(self):
        for engine in ("google", "duckduckgo", "brave", "bing"):
            self.assertIn('"' + engine + '"', SEARCH)
        for field in ('/v1/settings/search', '/v1/settings/desktop',
                      '/v1/find-in-page', '/v1/find-in-page/clear'):
            self.assertIn('"' + field + '"', ROUTER)
        self.assertIn('android.intent.action.VIEW', MANIFEST)
        self.assertIn('android.intent.category.BROWSABLE', MANIFEST)
        self.assertIn('android:scheme="https"', MANIFEST)
        self.assertIn('fun handleExternalLink', ACTIVITY)

    def test_v1_background_recovery_and_browser_ui(self):
        self.assertIn("fun recoverBackgroundSurface()", CONTROLLER)
        self.assertIn("backgroundRenderPhase()", CONTROLLER)
        self.assertIn("backgroundRecoveryCooldownMs", CONTROLLER)
        self.assertIn("backgroundSuccessfulPixelCaptures", CONTROLLER)
        self.assertIn('"/v1/background/recover"', ROUTER)
        self.assertIn("ctrl.closePopup(profile, fromUser = true)", TABS)
        self.assertIn("hint = \"搜尋網站名稱或網址\"", ACTIVITY)
        self.assertIn("it.activityInfo.packageName != packageName", ACTIVITY)
        self.assertIn("android.intent.action.VIEW", MANIFEST)
        self.assertIn("<queries>", MANIFEST)

    def test_master_handoff_and_local_device_smoke_tool(self):
        for name in ("docs/V1_MASTER_HANDOFF.md", "docs/V1_NEXT_STAGE.md",
                     "docs/V1_RELEASE_GATE.md", "scripts/v1_smoke.py"):
            self.assertTrue((ROOT / name).is_file(), name)

    def test_agent_agnostic_documentation_and_token_boundary(self):
        for path in ("AGENTS.md", "skills/abwr-browser/SKILL.md",
                     ".agents/skills/abwr-browser/SKILL.md"):
            self.assertTrue((ROOT / path).exists(), path)
        self.assertIn('Authorization: Bearer', (ROOT / "skills/abwr-browser/SKILL.md").read_text())
        self.assertIn('127.0.0.1', (ROOT / "skills/abwr-browser/SKILL.md").read_text())


if __name__ == "__main__":
    unittest.main()
