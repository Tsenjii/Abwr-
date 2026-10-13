"""Real loopback HTTP and stdio regressions; no Android device is claimed."""
import importlib.util
import json
import os
from pathlib import Path
import queue
import subprocess
import sys
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
BRIDGE = ROOT / "bridge/mcp_stdio.py"
spec = importlib.util.spec_from_file_location("abwr_reliability", BRIDGE)
mcp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mcp)


class LocalFixture:
    def __init__(self, mode="normal"):
        self.entered = threading.Event()
        self.release = threading.Event()
        self.commands = []
        self.redirect_hits = 0
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_GET(self):
                if self.path == "/destination":
                    owner.redirect_hits += 1
                if mode == "redirect" and self.path != "/destination":
                    self.send_response(302)
                    self.send_header("Location", owner.host + "/destination")
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                self.respond([] if mode == "array" else {
                    "profile": self.headers.get("X-Browser-Profile", "G001"),
                    "activeProfile": "G001", "url": "https://example.org/", "activeTab": "main"})

            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                owner.commands.append(body)
                owner.entered.set()
                owner.release.wait(4)
                self.respond({"ok": True, "result": {"text": "complete"}})

            def respond(self, data):
                raw = json.dumps(data).encode()
                self.send_response(200)
                self.send_header("Content-Length", str(len(raw)))
                self.end_headers()
                try:
                    self.wfile.write(raw)
                except (BrokenPipeError, ConnectionResetError):
                    pass

        # The real adapter has a fixed authority. Tests run serially and close
        # this isolated fixture; never redirect the production token elsewhere.
        self.server = ThreadingHTTPServer(("127.0.0.1", 8765), Handler)
        self.host = "http://127.0.0.1:%s" % self.server.server_port
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def close(self):
        self.release.set()
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(2)


class AgentReliability(unittest.TestCase):
    def test_redirect_is_refused_before_credentials_reach_destination(self):
        fixture = LocalFixture("redirect")
        try:
            with mock.patch.object(mcp, "HOST", fixture.host), mock.patch.object(mcp, "TOKEN", "test-only"):
                with self.assertRaises(mcp.BrowserApiError) as caught:
                    mcp.api("GET", "/v1/status")
            self.assertEqual(caught.exception.code, "REDIRECT_REJECTED")
            self.assertEqual(fixture.redirect_hits, 0)
        finally:
            fixture.close()

    def test_invalid_json_shape_is_tool_failure(self):
        fixture = LocalFixture("array")
        try:
            with mock.patch.object(mcp, "HOST", fixture.host), mock.patch.object(mcp, "TOKEN", "test-only"):
                result = mcp.invoke_tool("browser_status", {})
            self.assertTrue(result["isError"])
            self.assertEqual(json.loads(result["content"][0]["text"])["code"], "INVALID_RESPONSE")
        finally:
            fixture.close()

    def test_validation_rejects_bad_arguments_before_request(self):
        with mock.patch.object(mcp, "api") as requester:
            for args in ({"text": "a", "selector": 12}, {"text": "a", "unexpected": True}):
                result = mcp.invoke_tool("browser_type", args)
                self.assertTrue(result["isError"])
                self.assertEqual(json.loads(result["content"][0]["text"])["code"], "BAD_ARGUMENTS")
        requester.assert_not_called()

    def test_profile_status_and_page_preconditions_are_preserved(self):
        with mock.patch.object(mcp, "api", return_value={"profile":"G002", "activeProfile":"G001"}) as requester:
            self.assertEqual(mcp.execute("browser_get_url", {"profile":"G002"})["profile"], "G002")
            self.assertEqual(requester.call_args.kwargs["profile"], "G002")
        args = {"profile":"G002", "tab":"T2", "expectedUrl":"https://example.org/", "selector":"#next"}
        with mock.patch.object(mcp, "api", return_value={"ok":True}) as requester:
            mcp.invoke_tool("browser_click", args)
            self.assertEqual(requester.call_args.args[2], dict(args, action="click"))

    def test_old_server_wait_timeout_is_not_success(self):
        with mock.patch.object(mcp, "api", return_value={"ok":True, "result":{"found":False, "timeout":True}}):
            result = mcp.invoke_tool("browser_wait", {"selector":"#later"})
        self.assertTrue(result["isError"])
        self.assertEqual(json.loads(result["content"][0]["text"])["code"], "WAIT_TIMEOUT")

    def test_socket_timeout_has_actionable_code(self):
        opener = mock.Mock()
        opener.open.side_effect = TimeoutError()
        with mock.patch.object(mcp, "TOKEN", "test-only"), mock.patch.object(mcp.urllib.request, "build_opener", return_value=opener):
            result = mcp.invoke_tool("browser_status", {})
        self.assertTrue(result["isError"])
        self.assertEqual(json.loads(result["content"][0]["text"])["code"], "TIMEOUT")

    def test_stdio_ping_status_and_cancellation_while_page_request_is_blocked(self):
        fixture = LocalFixture()
        env = dict(os.environ, AI_BROWSER_HOST=fixture.host, AI_BROWSER_TOKEN="test-only")
        proc = subprocess.Popen([sys.executable, str(BRIDGE)], stdin=subprocess.PIPE,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                text=True, env=env, cwd=ROOT)
        replies = queue.Queue()

        def reader():
            for line in proc.stdout:
                replies.put(json.loads(line))

        threading.Thread(target=reader, daemon=True).start()

        def send(rid, method, params=None):
            item = {"jsonrpc":"2.0", "method":method, "params":params or {}}
            if rid is not None:
                item["id"] = rid
            proc.stdin.write(json.dumps(item) + "\n")
            proc.stdin.flush()

        try:
            send(0, "initialize", {"protocolVersion":"2024-11-05"})
            self.assertEqual(replies.get(timeout=2)["result"]["protocolVersion"], "2024-11-05")
            send(1, "tools/call", {"name":"browser_read", "arguments":{}})
            self.assertTrue(fixture.entered.wait(2))
            send(2, "tools/call", {"name":"browser_click", "arguments":{"selector":"#must-not-click"}})
            send(None, "notifications/cancelled", {"requestId":2})
            send(3, "tools/call", {"name":"browser_status", "arguments":{}})
            send(4, "ping")
            fast = [replies.get(timeout=2), replies.get(timeout=2)]
            self.assertEqual({item["id"] for item in fast}, {3, 4})
            self.assertFalse(fixture.release.is_set(), "status/ping must finish before the page operation")
            fixture.release.set()
            self.assertEqual(replies.get(timeout=2)["id"], 1)
            proc.stdin.close()
            proc.wait(timeout=3)
            self.assertEqual(proc.returncode, 0, proc.stderr.read())
            self.assertEqual(len(fixture.commands), 1, "cancelled queued click must never reach HTTP")
            self.assertTrue(replies.empty(), "cancelled request must not send a response")
        finally:
            fixture.close()
            if proc.poll() is None:
                proc.kill()
            proc.wait(timeout=3)
            proc.stdout.close()
            proc.stderr.close()
            if not proc.stdin.closed:
                proc.stdin.close()


if __name__ == "__main__":
    unittest.main()
