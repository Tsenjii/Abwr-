"""MCP adapter contract tests without requiring an Android handset."""
import importlib.util
import pathlib
import json
import unittest
from unittest import mock

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = ROOT / "bridge" / "mcp_stdio.py"
spec = importlib.util.spec_from_file_location("abwr_mcp_v04", SOURCE)
mcp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mcp)


class BrowserMcpTests(unittest.TestCase):
    def test_frozen_schemas_match_the_runtime_tools_and_wait_contract(self):
        frozen=json.loads((ROOT/'docs/mcp-schema.json').read_text())
        self.assertEqual(frozen['tools'],mcp.TOOLS)
        self.assertEqual(frozen['toolCount'],82)
        api=json.loads((ROOT/'docs/openapi.json').read_text())
        variants=api['paths']['/v1/command']['post']['requestBody']['content']['application/json']['schema']['oneOf']
        schemas=[api['components']['schemas'][v['$ref'].split('/')[-1]] for v in variants]
        wait=next(s for s in schemas if s['properties']['action']['const']=='wait')
        self.assertIn('condition',wait['properties']);self.assertIn('previousDocumentId',wait['properties'])
        self.assertEqual(api['info']['version'],frozen['serverInfo']['version'])
        http_schema=next(t['inputSchema'] for t in mcp.TOOLS if t['name']=='browser_http_request')
        self.assertEqual(api['paths']['/v1/http/request']['post']['requestBody']['content']['application/json']['schema'],http_schema)
        self.assertTrue({'profile','tab','documentId','expectedUrl'} <= set(http_schema['properties']))
    def test_core_browser_tools_are_discoverable(self):
        tools = {entry["name"]: entry for entry in mcp.TOOLS}
        for name in ("browser_navigate", "browser_snapshot", "browser_click",
                     "browser_type", "browser_wait", "browser_screenshot",
                     "browser_diagnostics", "browser_back", "browser_forward",
                     "browser_reload", "browser_tabs", "browser_takeover",
                     "browser_profiles", "profile_switch", "browser_clear",
                     "browser_select", "browser_press", "browser_read", "browser_privacy", "browser_fingerprint_protection", "browser_compatibility",
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
        self.assertIn("[e1_1]", output["result"]["snapshot"])
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

    def test_background_recovery_routes_to_local_http(self):
        with mock.patch.object(mcp, "api", return_value={"retryScheduled": True}) as requester:
            result = mcp.execute("browser_background_recover", {})
        self.assertTrue(result["retryScheduled"])
        self.assertEqual(requester.call_args.args[:2],
                         ("POST", "/v1/background/recover"))

    def test_wrong_loopback_service_never_receives_browser_credential(self):
        with mock.patch.object(mcp,'TOKEN','test-only'),mock.patch.object(mcp.urllib.request,'build_opener') as opener:
            for host in ['http://127.0.0.1:9091','http://localhost:8765','http://127.0.0.1:7901']:
                with mock.patch.object(mcp,'HOST',host):
                    with self.assertRaises(mcp.BrowserApiError) as error:mcp.api('GET','/v1/status')
                    self.assertEqual(error.exception.code,'BAD_ARGUMENTS')
            opener.assert_not_called()

    def test_navigation_load_wait_default(self):
        with mock.patch.object(mcp, "api", return_value={"ok": True}) as api:
            mcp.execute("browser_navigate", {"url": "https://example.org"})
        path = api.call_args.args[1]
        body = api.call_args.args[2]
        self.assertEqual(path, "/v1/command")
        self.assertEqual(body["action"], "navigate")
        self.assertTrue(body["waitForLoad"])
        self.assertEqual(body["timeoutMs"], 12000)
    def test_conditional_wait_routes_through_mcp_without_a_selector(self):
        for condition in ['document_changed','url_changed','load','frame_ready','network_idle','delay']:
            with mock.patch.object(mcp,'api',return_value={'ok':True,'result':{'matched':True}}) as api:
                result=mcp.invoke_tool('browser_wait',{'condition':condition,'timeoutMs':1000,'durationMs':50})
                self.assertFalse(result['isError'],condition)
                self.assertEqual(api.call_args.args[2]['condition'],condition)
        with mock.patch.object(mcp,'api') as api:
            self.assertTrue(mcp.invoke_tool('browser_wait',{})['isError'])
            api.assert_not_called()
    def test_child_frame_write_requires_url_guard_before_dispatch(self):
        with mock.patch.object(mcp,'api',return_value={'ok':True}) as api:
            denied=mcp.invoke_tool('browser_click',{'frame':'child-doc','selector':'#submit'})
            self.assertTrue(denied['isError']);api.assert_not_called()
            accepted=mcp.invoke_tool('browser_click',{'frame':'child-doc','expectedFrameUrl':'https://example.org/frame','selector':'#submit'})
            self.assertFalse(accepted['isError']);self.assertEqual(api.call_count,1)

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
        with mock.patch.object(mcp, "api", return_value={"metadata":{"profile":"G001","tab":"main","documentId":"d1","stale":False},"imageBase64":"/9j/4HNvbWUtanBlZy1ieXRlcw=="}):
            result = mcp.execute("browser_screenshot", {})
        self.assertEqual(result[0]["type"], "text")
        self.assertEqual(result[1]["type"], "image")
        self.assertEqual(result[1]["mimeType"], "image/jpeg")
        with mock.patch.object(mcp, "api", return_value={"metadata":{"profile":"G001","tab":"main","documentId":"d1","stale":False},"imageBase64":"/9j/4HNvbWUtanBlZy1ieXRlcw=="}):
            content=mcp.invoke_tool("browser_screenshot",{})["content"]
        self.assertEqual([item["type"] for item in content],["text","image"])

    def test_group_tools_preserve_container_and_tab_ids(self):
        for action, args in (("create", {"name":"work", "tabs":["main", "T2"], "color":0}),
                             ("move", {"tabs":["T2"]}), ("ungroup", {"id":"group1"})):
            args = dict(args, profile="G002")
            with mock.patch.object(mcp, "api", return_value={"groups":[]}) as requester:
                result = mcp.invoke_tool("browser_group_"+action, args)
                self.assertFalse(result["isError"])
            requester.assert_called_once_with("POST", "/v1/tab-groups/"+action, args)

    def test_group_arrays_rejected_before_http(self):
        with mock.patch.object(mcp, "api") as requester:
            for tabs in ("T1", [2], ["T1"]*122):
                result = mcp.invoke_tool("browser_group_move", {"tabs":tabs})
                self.assertTrue(result["isError"])
                self.assertIn("BAD_ARGUMENTS", result["content"][0]["text"])
            requester.assert_not_called()


if __name__ == "__main__":
    unittest.main()
