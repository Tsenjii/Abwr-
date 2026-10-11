#!/usr/bin/env python3
"""Abwr v0.4: dependency-free local MCP (stdio) adapter for Android GeckoView.

Always loopback, always bearer token, never logs passwords or credentials.
Page content is untrusted input; MCP tool replies are observations, not instructions.
"""
import base64
import json
import os
import sys
import urllib.error
import urllib.request

HOST = os.environ.get("AI_BROWSER_HOST", "http://127.0.0.1:8765").rstrip("/")
TOKEN = os.environ.get("AI_BROWSER_TOKEN", "")


class BrowserApiError(Exception):
    pass


def api(method, path, data=None, profile=None, binary=False):
    if not TOKEN:
        raise BrowserApiError("AI_BROWSER_TOKEN is not set. Copy it from Abwr on this device.")
    if not HOST.startswith("http://127.0.0.1:") and not HOST.startswith("http://localhost:"):
        raise BrowserApiError("Only loopback hosts are supported by the local MCP adapter.")
    headers = {"Authorization": "Bearer " + TOKEN, "Content-Type": "application/json"}
    if profile:
        headers["X-Browser-Profile"] = profile
    payload = json.dumps(data or {}).encode("utf-8") if method == "POST" else None
    request = urllib.request.Request(HOST + path, data=payload, method=method, headers=headers)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(request, timeout=23) as response:
            raw = response.read()
            if binary:
                if len(raw) < 4 or raw[:3] != b"\xff\xd8\xff":
                    raise BrowserApiError("Screenshot response was not JPEG")
                return raw
            return json.loads(raw)
    except urllib.error.HTTPError as exc:
        try:
            data = json.loads(exc.read(4096))
            message = data.get("message") or data.get("error") or "API rejected request"
        except (ValueError, UnicodeDecodeError):
            message = "API rejected request"
        raise BrowserApiError("HTTP %d: %s" % (exc.code, message)) from None
    except urllib.error.URLError as exc:
        raise BrowserApiError("Cannot connect to local Abwr service: " + str(exc.reason)) from None


S = {"type": "string"}
I = {"type": "integer"}
B = {"type": "boolean"}


def tool(name, description, props=None, required=None, readonly=False):
    entry = {"name": name, "description": description,
             "inputSchema": {"type": "object", "properties": props or {},
                             "required": required or [], "additionalProperties": False}}
    entry["annotations"] = {"readOnlyHint": bool(readonly)}
    return entry


PROFILE = {"profile": S}
TARGET = {"target": S, "selector": S, "profile": S}
TOOLS = [
    tool("browser_status", "Read active Android browser and Gecko bridge status.", readonly=True),
    tool("browser_diagnostics", "Inspect memory, battery policy, render frame age and API uptime.", readonly=True),
    tool("browser_get_url", "Read current active profile URL and title.", readonly=True),
    tool("browser_profiles", "List isolated account containers; browser profile != tab.", readonly=True),
    tool("profile_create", "Create a separate Gecko context without touching existing accounts.", {"id": S}, ["id"]),
    tool("profile_switch", "Switch active visible container.", {"id": S}, ["id"]),
    tool("profile_delete", "Delete specified container AND its website data; do not use on existing accounts without owner consent.", {"id": S}, ["id"]),
    tool("browser_navigate", "Open an HTTP(S) URL; optionally wait for real page load completion.",
         {"url": S, "profile": S, "waitForLoad": B, "timeoutMs": I}, ["url"]),
    tool("browser_back", "Navigate back for this profile.", PROFILE),
    tool("browser_forward", "Navigate forward for this profile.", PROFILE),
    tool("browser_reload", "Reload the current page for this profile.", PROFILE),
    tool("browser_snapshot", "Compact agent-readable accessibility-like DOM snapshot. Returns refs eN_N for subsequent target actions; ref is not a Playwright locator.",
         {"profile": S, "limit": I, "selector": S}, readonly=True),
    tool("browser_dom", "Inspect page text and interactive DOM element metadata.",
         {"profile": S, "limit": I}, readonly=True),
    tool("browser_find", "Search page elements by CSS, visible text, role or aria-label.",
         {"profile": S, "selector": S, "text": S, "role": S, "ariaLabel": S}, readonly=True),
    tool("browser_links", "Get link text and URL list.", {"profile": S, "limit": I}, readonly=True),
    tool("browser_click", "Click a CSS selector or current-snapshot element ref; re-snapshot after navigation.", TARGET),
    tool("browser_type", "Fill a text input or textarea; set submit=true to submit its form.",
         dict(TARGET, text=S, submit=B), ["text"]),
    tool("browser_clear", "Clear text input or textarea.", TARGET),
    tool("browser_toggle", "Set checkbox or radio element checked state.", dict(TARGET, checked=B)),
    tool("browser_select", "Select dropdown option by value.", dict(TARGET, value=S), ["value"]),
    tool("browser_focus", "Focus an input or focusable element.", TARGET),
    tool("browser_press", "Dispatch synthetic DOM keyboard event; not a hardware keypress.",
         dict(TARGET, key=S), ["key"]),
    tool("browser_scroll", "Scroll vertically by pixel delta.", {"profile": S, "y": I}),
    tool("browser_scroll_to", "Scroll to top/bottom or bring a target into view.",
         dict(TARGET, position=S)),
    tool("browser_wait", "Wait for selector or page text using DOM MutationObserver; max 10 sec.",
         {"profile": S, "selector": S, "text": S, "timeoutMs": I}),
    tool("browser_tabs", "List main tab and optional popup in current container.", readonly=True),
    tool("browser_popup_close", "Close the popup in specified container.", PROFILE),
    tool("browser_takeover", "Hand same container to human (manual=true) or back to AI (false).",
         {"profile": S, "manual": B}, ["manual"]),
    tool("browser_screenshot", "Capture real current JPEG pixels; requires a valid GeckoView display surface.",
         {"profile": S}, readonly=True),
    tool("browser_screenshot_meta", "Read screenshot age and whether cache is stale.", PROFILE, readonly=True),
    tool("browser_screenshot_last", "Retrieve OLD cached JPEG, NEVER current live pixels.", PROFILE, readonly=True),
]


def execute(name, args):
    if name == "browser_status":
        return api("GET", "/v1/status")
    if name == "browser_diagnostics":
        return api("GET", "/v1/diagnostics")
    if name == "browser_get_url":
        status = api("GET", "/v1/status")
        return {"profile": status.get("activeProfile"), "url": status.get("url"), "title": status.get("title"), "loading": status.get("loading")}
    if name == "browser_profiles":
        return api("GET", "/v1/profiles")
    if name == "profile_create":
        return api("POST", "/v1/profile/create", args)
    if name == "profile_switch":
        return api("POST", "/v1/profile/switch", args)
    if name == "profile_delete":
        return api("POST", "/v1/profile/delete", args)
    if name == "browser_navigate":
        payload = dict(args)
        payload.setdefault("waitForLoad", True)
        payload.setdefault("timeoutMs", 12000)
        return api("POST", "/v1/command", dict(payload, action="navigate"))
    if name in ("browser_back", "browser_forward", "browser_reload"):
        return api("POST", "/v1/" + name.removeprefix("browser_"), args)
    if name == "browser_tabs":
        return api("GET", "/v1/tabs")
    if name == "browser_popup_close":
        return api("POST", "/v1/tabs/close_popup", args)
    if name == "browser_takeover":
        return api("POST", "/v1/takeover", args)
    if name == "browser_screenshot_meta":
        return api("GET", "/v1/screenshot/meta", profile=args.get("profile"))
    if name in ("browser_screenshot", "browser_screenshot_last"):
        path = "/v1/screenshot/last" if name.endswith("_last") else "/v1/screenshot"
        image = api("GET", path, profile=args.get("profile"), binary=True)
        return [{"type": "image", "data": base64.b64encode(image).decode("ascii"), "mimeType": "image/jpeg"}]

    actions = {
        "browser_snapshot": "snapshot", "browser_dom": "dom", "browser_find": "find",
        "browser_links": "links", "browser_click": "click", "browser_type": "type",
        "browser_clear": "clear", "browser_toggle": "toggle", "browser_select": "select",
        "browser_focus": "focus", "browser_press": "keyboard", "browser_scroll": "scroll",
        "browser_scroll_to": "scrollTo", "browser_wait": "wait"
    }
    if name in actions:
        args = dict(args)
        if name in {"browser_click", "browser_type", "browser_clear", "browser_toggle",
                    "browser_select", "browser_focus"} and not (args.get("target") or args.get("selector")):
            raise BrowserApiError("Either target (snapshot ref) or selector is required")
        if name == "browser_wait" and not (args.get("selector") or args.get("text")):
            raise BrowserApiError("browser_wait requires selector or text")
        result = api("POST", "/v1/command", dict(args, action=actions[name]))
        if result.get("ok") is False:
            raise BrowserApiError(result.get("error") or "DOM action failed")
        if name == "browser_snapshot":
            data = result.get("result") or {}
            return data.get("snapshot") or result
        return result
    raise BrowserApiError("Unknown browser tool: " + name)


def reply(obj):
    sys.stdout.write(json.dumps(obj, ensure_ascii=False, separators=(",", ":")) + "\n")
    sys.stdout.flush()


def invoke_tool(name, args):
    try:
        result = execute(name, args)
        if isinstance(result, list) and result and result[0].get("type") == "image":
            content = result
        else:
            text = result if isinstance(result, str) else json.dumps(result, ensure_ascii=False)
            content = [{"type": "text", "text": text}]
        return {"content": content, "isError": False}
    except (BrowserApiError, ValueError, KeyError, TypeError) as error:
        return {"content": [{"type": "text", "text": str(error)[:480]}], "isError": True}


def serve():
    for line in sys.stdin:
        item = None
        try:
            item = json.loads(line)
            method = item.get("method", "")
            rid = item.get("id")
            # MCP initialized/notifications are fire-and-forget; do not send a reply.
            if rid is None:
                continue
            if method == "initialize":
                value = {"protocolVersion": "2025-03-26",
                         "capabilities": {"tools": {"listChanged": False}},
                         "serverInfo": {"name": "abwr-browser", "version": "0.4.1"}}
            elif method == "ping":
                value = {}
            elif method == "tools/list":
                value = {"tools": TOOLS}
            elif method == "tools/call":
                params = item.get("params") or {}
                value = invoke_tool(params["name"], params.get("arguments") or {})
            else:
                reply({"jsonrpc": "2.0", "id": rid,
                       "error": {"code": -32601, "message": "Method not found"}})
                continue
            reply({"jsonrpc": "2.0", "id": rid, "result": value})
        except Exception as error:
            if isinstance(item, dict) and item.get("id") is not None:
                reply({"jsonrpc": "2.0", "id": item["id"],
                       "error": {"code": -32603, "message": str(error)[:240]}})


if __name__ == "__main__":
    serve()
