"""MCP adapter contract tests without requiring an Android handset."""
import importlib.util
import pathlib
import unittest
from unittest import mock

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = ROOT / "bridge" / "mcp_stdio.py"
spec = importlib.util.spec_from_file_location("abwr_mcp_v04", SOURCE)
mcp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mcp)


class BrowserMcpTests(unittest.TestCase):
    def test_core_browser_tools_are_discoverable(self):
        tools = {entry["name"]: entry for entry in mcp.TOOLS}
        for name in ("browser_navigate", "browser_snapshot", "browser_click",
                     "browser_type", "browser_wait", "browser_screenshot",
                     "browser_diagnostics", "browser_back", "browser_forward",
                     "browser_reload", "browser_tabs", "browser_takeover",
                     "browser_profiles", "profile_switch", "browser_clear",
                     "browser_select", "browser_press", "browser_read",
                     "browser_find_in_page", "browser_clear_find",
                     "browser_desktop_mode", "browser_search_engine", "browser_settings", "browser_tab_new", "browser_tab_select", "browser_tab_close", "browser_devtools", "browser_storage_inspect", "browser_bookmarks", "browser_history"):
            self.assertIn(name, tools, name)
            self.assertEqual(tools[name]["inputSchema"]["type"], "object")
            self.assertFalse(tools[name]["inputSchema"]["additionalProperties"])
        self.assertTrue(tools["browser_snapshot"]["annotations"]["readOnlyHint"])
        self.assertFalse(tools["browser_click"]["annotations"]["readOnlyHint"])

    def test_standard_browser_controls_route_through_http(self):
        cases = (
            ("browser_find_in_page", {"query":"Example"}, "/v1/find-in-page"),
            ("browser_clear_find", {}, "/v1/find-in-page/clear"),
            ("browser_desktop_mode", {"enabled":True}, "/v1/settings/desktop"),
            ("browser_search_engine", {"engine":"duckduckgo"}, "/v1/settings/search"),
        )
        for tool_name, arguments, path in cases:
            with self.subTest(tool=tool_name):
                with mock.patch.object(mcp, "api", return_value={"ok":True}) as requester:
                    mcp.execute(tool_name, arguments)
                self.assertEqual(requester.call_args.args[0], "POST")
                self.assertEqual(requester.call_args.args[1], path)
        with mock.patch.object(mcp, "api", return_value={"searchEngine":"google"}) as requester:
            mcp.execute("browser_settings", {})
        self.assertEqual(requester.call_args.args[1], "/v1/settings")

    def test_snapshot_ref_targets_api(self):
        with mock.patch.object(mcp, "api", return_value={
                "id": "123", "ok": True,
                "result": {"snapshot": 'Page: Demo\n[e1_1] button "Submit"'}}) as api:
            output = mcp.execute("browser_snapshot", {"profile": "G002", "limit": 20})
        self.assertIn("[e1_1]", output)
        api.assert_called_once_with("POST", "/v1/command", {
            "action": "snapshot", "profile": "G002", "limit": 20})
        with mock.patch.object(mcp, "api", return_value={"ok": True}) as api:
            mcp.execute("browser_click", {"target": "e1_1", "profile": "G002"})
        self.assertEqual(api.call_args.args[2]["target"], "e1_1")

    def test_reader_tool_and_localhost_authority_check(self):
        with mock.patch.object(mcp, "api", return_value={
                "id":"3", "ok":True,
                "result":{"title":"Example","text":"A short article"}}) as browser_api:
            result = mcp.execute("browser_read", {"maxChars":4096,"profile":"G001"})
        self.assertEqual(result["result"]["text"],"A short article")
        self.assertEqual(browser_api.call_args.args[2]["action"],"read")
        with mock.patch.object(mcp,"TOKEN","secret"), mock.patch.object(
                mcp,"HOST","http://127.0.0.1:8765@untrusted.example"):
            with self.assertRaises(mcp.BrowserApiError):
                mcp.api("GET","/v1/status")

    def test_navigation_load_wait_default(self):
        with mock.patch.object(mcp, "api", return_value={"ok": True}) as api:
            mcp.execute("browser_navigate", {"url": "https://example.org"})
        path = api.call_args.args[1]
        body = api.call_args.args[2]
        self.assertEqual(path, "/v1/command")
        self.assertEqual(body["action"], "navigate")
        self.assertTrue(body["waitForLoad"])
        self.assertEqual(body["timeoutMs"], 12000)

    def test_page_command_error_is_mcp_tool_error(self):
        with mock.patch.object(mcp, "api", return_value={"ok": False, "error": "Element not found"}):
            output = mcp.invoke_tool("browser_click", {"selector": "#missing"})
        self.assertTrue(output["isError"])
        self.assertIn("Element not found", output["content"][0]["text"])

    def test_no_missing_target_or_untrusted_host(self):
        result = mcp.invoke_tool("browser_click", {})
        self.assertTrue(result["isError"])
        self.assertIn("target", result["content"][0]["text"])
        with mock.patch.object(mcp, "TOKEN", "not-real"), mock.patch.object(mcp, "HOST", "http://other-host:8765"):
            with self.assertRaises(mcp.BrowserApiError):
                mcp.api("GET", "/v1/status")

    def test_screenshot_requires_jpeg_and_binary_tool_shape(self):
        with mock.patch.object(mcp, "api", return_value=b"\xff\xd8\xff\xe0some-jpeg-bytes"):
            result = mcp.execute("browser_screenshot", {})
        self.assertEqual(result[0]["type"], "image")
        self.assertEqual(result[0]["mimeType"], "image/jpeg")


if __name__ == "__main__":
    unittest.main()
