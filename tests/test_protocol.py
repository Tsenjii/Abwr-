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
        # V1 stability gates: no LRU eviction of pending navigation, active
        # bridge requests fail if their GeckoSession is closed, and deleted
        # recreated public IDs receive fresh opaque Gecko contexts before loading.
        self.assertIn("hasPendingNavigation", controller)
        self.assertIn("failSessionRequests", controller)
        self.assertIn("bridgeRequests", controller)
        self.assertIn("retiring_profiles_v1", controller)
        self.assertIn("clearContext", controller)
        self.assertIn("profile_contexts_v1", controller)
        self.assertIn("persist(sync = true)", controller)
        self.assertIn("storageClearRequested", controller)
        self.assertIn("idReserved", controller)
        self.assertIn("screenshotGeneration", controller)
        self.assertIn("invalidateScreenshot", controller)
        self.assertIn("popupLoading", controller)
        self.assertIn("pendingBridgeCommands", controller)
        self.assertIn("pruneSessions(preserveId = id)", controller)
        self.assertIn("navigateAndWaitAsync", controller)
        self.assertIn("openExtraTab", controller)
        self.assertIn("setTrackingProtection", controller)
        # Session-pinned navigation is exercised by JVM readiness tests and the
        # emulator workflow; do not bind this smoke check to a local variable name.
        self.assertIn("selectedLoadedSession()", controller)
        self.assertIn("newPopupSession", controller)
        self.assertIn("pruneTabSessions(preserveKey = key)", controller)
        self.assertIn("tabLoading", controller)
        self.assertIn("findInPageAsync", controller)
        self.assertIn("setDesktopMode", controller)
        self.assertIn("onCanGoBack", controller)
        self.assertIn("onCanGoForward", controller)
        self.assertIn("sessionSettings", controller)
        self.assertIn("bookmarks(profile", controller)
        self.assertIn("humanBack()", controller)
        self.assertIn("lastShots.size > 3", controller)
        self.assertIn("session.promptDelegate = sitePrompts", controller)
        self.assertIn("session.permissionDelegate = sitePermissions", controller)
        self.assertIn("compatibility", controller)
        self.assertIn("fingerprintProtectionEnabled", controller)
        self.assertIn("setFingerprintProtection", controller)
        self.assertIn("shared_gecko_runtime", controller)
        self.assertIn("independentPerProfileWebFingerprint", controller)
        self.assertIn("setGlobalPrivacyControl", controller)
        activity = (ROOT / "app/src/main/java/dev/local/aibrowser/MainActivity.kt").read_text()
        self.assertIn("showSitePrompt", activity)
        self.assertIn("showContentPermission", activity)
        self.assertIn("onActivityResult", activity)
        self.assertIn("ACTION_OPEN_DOCUMENT", activity)
        self.assertIn("openExternalBrowser", activity)
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
