#!/usr/bin/env python3
"""Local, stdio MCP adapter to an explicitly trusted Android browser API."""
import base64
import json
import os
import sys
import urllib.error
import urllib.request

HOST = os.environ.get("AI_BROWSER_HOST", "http://127.0.0.1:8765").rstrip("/")
TOKEN = os.environ.get("AI_BROWSER_TOKEN", "")

def api(method, path, data=None, profile=None, binary=False):
    if not TOKEN:
        raise ValueError("AI_BROWSER_TOKEN is required")
    headers = {"Authorization": "Bearer " + TOKEN, "Content-Type": "application/json"}
    if profile:
        headers["X-Browser-Profile"] = profile
    payload = json.dumps(data or {}).encode() if method == "POST" else None
    req = urllib.request.Request(HOST + path, data=payload, method=method, headers=headers)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(req, timeout=24) as resp:
        raw = resp.read()
        return raw if binary else json.loads(raw)

def tool(name, properties=None, required=None):
    return {"name": name, "description": name.replace("_", " "),
            "inputSchema": {"type": "object", "properties": properties or {},
                            "required": required or [], "additionalProperties": False}}

S = {"type": "string"}
N = {"type": "integer"}
B = {"type": "boolean"}
TOOLS = [
    tool("browser_status"), tool("browser_profiles"),
    tool("profile_create", {"id":S}, ["id"]),
    tool("profile_switch", {"id":S}, ["id"]),
    tool("browser_navigate", {"url":S, "profile":S}, ["url"]),
    tool("browser_dom", {"profile":S, "limit":N}),
    tool("browser_find", {"profile":S, "selector":S}, ["selector"]),
    tool("browser_click", {"profile":S, "selector":S}, ["selector"]),
    tool("browser_type", {"profile":S, "selector":S, "text":S}, ["selector", "text"]),
    tool("browser_scroll", {"profile":S, "y":N}),
    tool("browser_screenshot", {"profile":S, "full":B}),
    tool("browser_takeover", {"profile":S, "manual":B}, ["manual"])
]

def execute(name, args):
    if name == "browser_status": return api("GET", "/v1/status")
    if name == "browser_profiles": return api("GET", "/v1/profiles")
    if name == "profile_create": return api("POST", "/v1/profile/create", args)
    if name == "profile_switch": return api("POST", "/v1/profile/switch", args)
    if name == "browser_navigate": return api("POST", "/v1/navigate", args)
    if name == "browser_takeover": return api("POST", "/v1/takeover", args)
    if name == "browser_screenshot":
        blob = api("GET", "/v1/screenshot/full" if args.get("full") else "/v1/screenshot", profile=args.get("profile"), binary=True)
        return [{"type": "image", "data": base64.b64encode(blob).decode(), "mimeType": "image/jpeg"}]
    acts = {"browser_dom":"dom", "browser_find":"find", "browser_click":"click",
            "browser_type":"type", "browser_scroll":"scroll"}
    if name in acts:
        return api("POST", "/v1/command", dict(args, action=acts[name]))
    raise ValueError("Unknown tool: " + name)

def reply(obj):
    sys.stdout.write(json.dumps(obj, ensure_ascii=False) + "\n")
    sys.stdout.flush()

def serve():
    for line in sys.stdin:
        try:
            item = json.loads(line)
            rid = item.get("id")
            method = item.get("method", "")
            if rid is None: continue
            if method == "initialize":
                value = {"protocolVersion":"2025-03-26", "capabilities":{"tools":{}},
                         "serverInfo":{"name":"abwr-browser","version":"0.2.0"}}
            elif method == "ping": value = {}
            elif method == "tools/list": value = {"tools": TOOLS}
            elif method == "tools/call":
                p = item.get("params", {})
                result = execute(p["name"], p.get("arguments", {}))
                if isinstance(result, list) and result and result[0].get("type") == "image":
                    items = result
                else:
                    items = [{"type":"text","text":json.dumps(result,ensure_ascii=False)}]
                value = {"content":items,"isError":False}
            else:
                reply({"jsonrpc":"2.0","id":rid,"error":{"code":-32601,"message":"Method not found"}})
                continue
            reply({"jsonrpc":"2.0","id":rid,"result":value})
        except Exception as exc:
            if isinstance(item, dict) and item.get("id") is not None:
                reply({"jsonrpc":"2.0","id":item.get("id"),
                       "error":{"code":-32000,"message":str(exc)[:240]}})
if __name__ == "__main__":
    serve()
