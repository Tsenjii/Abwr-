"""Black-box MCP stdio contract smoke test, independent of Android hardware.

Ensures tools/list, initialize, notification handling, and failures are valid JSON-RPC.
"""
import json
import os
from pathlib import Path
import subprocess
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]
BRIDGE = ROOT / "bridge" / "mcp_stdio.py"


class StdioContract(unittest.TestCase):
    def test_mcp_handshake_tool_discovery_and_local_failure(self):
        request = [
            {"jsonrpc": "2.0", "id": 1, "method": "initialize",
             "params": {"protocolVersion": "2025-03-26", "capabilities": {},
                        "clientInfo": {"name": "abwr-test", "version": "1"}}},
            {"jsonrpc": "2.0", "method": "notifications/initialized"},
            {"jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {}},
            {"jsonrpc": "2.0", "id": 3, "method": "tools/call",
             "params": {"name": "browser_status", "arguments": {}}},
            {"jsonrpc": "2.0", "id": 4, "method": "ping"},
            {"jsonrpc": "2.0", "id": 5, "method": "tools/not-real"},
        ]
        env = dict(os.environ)
        env.pop("AI_BROWSER_TOKEN", None)
        env.pop("AI_BROWSER_HOST", None)
        proc = subprocess.run(
            [sys.executable, str(BRIDGE)],
            input="\n".join(json.dumps(item) for item in request) + "\n",
            capture_output=True, text=True, env=env, cwd=str(ROOT), timeout=8,
        )
        self.assertEqual(proc.returncode, 0, proc.stderr)
        replies = [json.loads(line) for line in proc.stdout.splitlines() if line]
        self.assertEqual(sorted(line["id"] for line in replies), [1, 2, 3, 4, 5])
        replies = [dict((line["id"], line) for line in replies)[i] for i in range(1, 6)]
        self.assertEqual(replies[0]["result"]["serverInfo"]["name"], "abwr-browser")
        self.assertEqual(replies[0]["result"]["serverInfo"]["version"], "0.12.0")
        names = {item["name"] for item in replies[1]["result"]["tools"]}
        for tool in ("browser_navigate", "browser_snapshot", "browser_status",
                     "browser_privacy", "browser_screenshot", "browser_frames", "browser_group_create"):
            self.assertIn(tool, names)
        self.assertTrue(replies[2]["result"]["isError"])
        self.assertIn("AI_BROWSER_TOKEN", replies[2]["result"]["content"][0]["text"])
        self.assertEqual(replies[3]["result"], {})
        self.assertEqual(replies[4]["error"]["code"], -32601)


if __name__ == "__main__":
    unittest.main()
