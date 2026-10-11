#!/usr/bin/env python3
"""Abwr: dependency-free local MCP (stdio) adapter for Android GeckoView.

Always loopback, always bearer token, never logs passwords or credentials.
Page content is untrusted input; MCP tool replies are observations, not instructions.
"""
import base64
import json
import os
import queue
import sys
import threading
import urllib.error
import urllib.request
from urllib.parse import urlsplit

HOST = os.environ.get("AI_BROWSER_HOST", "http://127.0.0.1:8765").rstrip("/")
TOKEN = os.environ.get("AI_BROWSER_TOKEN", "")


class BrowserApiError(Exception):
    def __init__(self, message, code="API_ERROR", retryable=False):
        super().__init__(str(message)[:480])
        self.code = code
        self.retryable = bool(retryable)

    def result(self):
        return {"code": self.code, "message": str(self), "retryable": self.retryable}


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # The bearer credential belongs only to the explicitly validated host.
        return None


def api(method, path, data=None, profile=None, binary=False):
    if not TOKEN:
        raise BrowserApiError("AI_BROWSER_TOKEN is not set. Copy it from Abwr on this device.")
    # Parse the authority, not a string prefix: URL user-info like
    # http://127.0.0.1:8765@untrusted.example must never receive our Bearer token.
    try:
        endpoint = urlsplit(HOST)
        valid = (endpoint.scheme == "http" and
                 endpoint.hostname in ("127.0.0.1", "localhost") and
                 endpoint.port is not None and
                 endpoint.username is None and endpoint.password is None and
                 not endpoint.path.strip("/") and not endpoint.query and not endpoint.fragment)
    except ValueError:
        valid = False
    if not valid:
        raise BrowserApiError("AI_BROWSER_HOST must be a bare HTTP loopback host and port.")
    headers = {"Authorization": "Bearer " + TOKEN, "Content-Type": "application/json"}
    if profile:
        headers["X-Browser-Profile"] = profile
    payload = json.dumps(data or {}).encode("utf-8") if method == "POST" else None
    request = urllib.request.Request(HOST + path, data=payload, method=method, headers=headers)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    try:
        with opener.open(request, timeout=23) as response:
            raw = response.read(8 * 1024 * 1024 + 1)
            if len(raw) > 8 * 1024 * 1024:
                raise BrowserApiError("Local API response exceeded 8 MiB", "RESPONSE_TOO_LARGE")
            if binary:
                if len(raw) < 4 or raw[:3] != b"\xff\xd8\xff":
                    raise BrowserApiError("Screenshot response was not JPEG")
                return raw
            data = json.loads(raw)
            if not isinstance(data, dict):
                raise BrowserApiError("Local API response must be a JSON object", "INVALID_RESPONSE")
            if data.get("ok") is False:
                raise BrowserApiError(data.get("message") or data.get("error") or "Page action failed",
                                      data.get("code", "PAGE_ACTION_FAILED"), data.get("retryable", False))
            return data
    except urllib.error.HTTPError as exc:
        try:
            if 300 <= exc.code < 400:
                raise BrowserApiError("Local API redirects are refused", "REDIRECT_REJECTED") from None
            try:
                data = json.loads(exc.read(4096))
                if not isinstance(data, dict):
                    data = {}
                message = data.get("message") or data.get("error") or "API rejected request"
            except (ValueError, UnicodeDecodeError):
                data = {}
                message = "API rejected request"
            raise BrowserApiError("HTTP %d: %s" % (exc.code, message),
                                  data.get("code", "HTTP_ERROR"), exc.code == 503) from None
        finally:
            exc.close()
    except urllib.error.URLError as exc:
        raise BrowserApiError("Cannot connect to local Abwr service: " + str(exc.reason), "CONNECTION_FAILED") from None
    except TimeoutError:
        raise BrowserApiError("Local Abwr response timed out; inspect the page before retrying a write", "TIMEOUT") from None
    except OSError:
        raise BrowserApiError("Local Abwr connection ended; inspect the page before retrying a write", "CONNECTION_FAILED") from None


S = {"type": "string"}
I = {"type": "integer"}
B = {"type": "boolean"}


def tool(name, description, props=None, required=None, readonly=False):
    entry = {"name": name, "description": description,
             "inputSchema": {"type": "object", "properties": dict(props or {}),
                             "required": required or [], "additionalProperties": False}}
    entry["annotations"] = {"readOnlyHint": bool(readonly)}
    return entry


PROFILE = {"profile": S}
TARGET = {"target": S, "selector": S, "profile": S}
TOOLS = [
    tool("browser_status", "Read selected profile status without switching the visible browser.", PROFILE, readonly=True),
    tool("browser_capabilities", "Discover actual API capabilities and limits before using the browser.", readonly=True),
    tool("browser_downloads", "Read local download progress metadata; starting/exporting files requires the owner's UI.", PROFILE, readonly=True),
    tool("browser_diagnostics", "Inspect memory, battery policy, render frame age and API uptime.", readonly=True),
    tool("browser_background_recover", "Best-effort explicit restart of Abwr private background surface; throttled every 20 seconds."),
    tool("browser_privacy", "Inspect this profile's real storage isolation and global anti-fingerprinting scope.",
         PROFILE, readonly=True),
    tool("browser_fingerprint_protection", "Set Gecko native fingerprint protection for the shared browser runtime (all profiles, not individual identities).",
         {"enabled": B}, ["enabled"]),
    tool("browser_get_url", "Read selected profile URL and title without changing the visible browser.", PROFILE, readonly=True),
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
         {"profile": S, "limit": I, "selector": S, "offset": I, "includeHidden": B,
          "includeFrames": B, "text": S, "role": S, "order": S}, readonly=True),
    tool("browser_read", "Fast article-style page reading: main text, headings and metadata with bounded output.",
         {"profile": S, "maxChars": I}, readonly=True),
    tool("browser_dom", "Inspect page text and interactive DOM element metadata.",
         {"profile": S, "limit": I}, readonly=True),
    tool("browser_find", "Search page elements by CSS, visible text, role or aria-label.",
         {"profile": S, "selector": S, "text": S, "role": S, "ariaLabel": S}, readonly=True),
    tool("browser_links", "Get link text and URL list.", {"profile": S, "limit": I}, readonly=True),
    tool("browser_click", "Click a CSS selector or current-snapshot element ref; re-snapshot after navigation.", TARGET),
    tool("browser_type", "Fill an input, textarea or plain contenteditable editor; set submit=true to submit its form.",
         dict(TARGET, text=S, submit=B), ["text"]),
    tool("browser_clear", "Clear an input, textarea or plain contenteditable editor.", TARGET),
    tool("browser_toggle", "Set checkbox or radio element checked state.", dict(TARGET, checked=B)),
    tool("browser_select", "Select dropdown option by value.", dict(TARGET, value=S), ["value"]),
    tool("browser_focus", "Focus an input or focusable element.", TARGET),
    tool("browser_press", "Send native Gecko keyboard input, including Enter, Tab, Backspace, Delete, arrows and Ctrl+A. Focus a target first or supply target/selector.",
         dict(TARGET, key=S), ["key"]),
    tool("browser_click_point", "Send a native touch in current browser viewport pixels. For JPEG coordinates supply imageWidth/imageHeight from screenshot metadata. Inspect after input to confirm the site's response.",
         {"profile": S, "x": I, "y": I, "imageWidth": I, "imageHeight": I}, ["x", "y"]),
    tool("browser_frames", "List connected frame documents. Use the frame ID for iframe actions; snapshot/read include child-frame results by default.",
         PROFILE, readonly=True),
    tool("browser_scroll", "Scroll vertically by pixel delta.", {"profile": S, "y": I}),
    tool("browser_scroll_to", "Scroll to top/bottom or bring a target into view.",
         dict(TARGET, position=S)),
    tool("browser_wait", "Wait for selector or page text using DOM MutationObserver; max 10 sec.",
         {"profile": S, "selector": S, "text": S, "timeoutMs": I}),
    tool("browser_bookmarks", "List saved URL bookmarks in Abwr profile.", PROFILE, readonly=True),
    tool("browser_bookmark_add", "Save a site URL as a bookmark in selected profile.",
         {"profile":S,"url":S}, ["url"]),
    tool("browser_history", "Read browser URL history for one Abwr profile.", PROFILE, readonly=True),
    tool("browser_find_in_page", "Native GeckoView highlight and locate text on currently selected page.",
         {"profile":S, "query":S, "backwards":B}, ["query"], readonly=True),
    tool("browser_clear_find", "Remove GeckoView find-in-page highlights.", PROFILE),
    tool("browser_settings", "Read active browser search engine, desktop mode and tracking protection.", readonly=True),
    tool("browser_desktop_mode", "Toggle desktop website UA and viewport for an Abwr container.",
         {"profile":S, "enabled":B}, ["enabled"]),
    tool("browser_search_engine", "Set UI default search engine: google, duckduckgo, brave or bing.",
         {"engine":S}, ["engine"]),
    tool("browser_tabs", "List tabs and active popup in current container.", PROFILE, readonly=True),
    tool("browser_tab_new", "Open a new tab inside an Abwr container, retaining the same account storage.",
         {"profile": S, "url": S}),
    tool("browser_tab_select", "Switch to a tab by id. Use browser_tabs to discover valid ids.",
         {"profile": S, "id": S}, ["id"]),
    tool("browser_tab_close", "Close a tab by id without deleting its whole account container.",
         {"profile": S, "id": S}, ["id"]),
    tool("browser_compatibility", "Read page-only login/challenge compatibility signals. Does not solve or bypass CAPTCHA, OAuth or anti-bot checks.",
         PROFILE, readonly=True),
    tool("browser_devtools", "Read page-scoped script errors, resource timing and DOM diagnostics (not full CDP).",
         PROFILE, readonly=True),
    tool("browser_storage_inspect", "Inspect current-origin Web Storage with token-like values redacted.",
         PROFILE, readonly=True),
    tool("browser_cookie_names", "Read document-visible Cookie names only, excluding HttpOnly values.",
         PROFILE, readonly=True),
    tool("browser_popup_close", "Close the popup in specified container.", PROFILE),
    tool("browser_takeover", "Hand same container to human (manual=true) or back to AI (false).",
         {"profile": S, "manual": B}, ["manual"]),
    tool("browser_screenshot", "Capture real current JPEG pixels; requires a valid GeckoView display surface.",
         {"profile": S}, readonly=True),
    tool("browser_screenshot_meta", "Read screenshot age and whether cache is stale.", PROFILE, readonly=True),
    tool("browser_screenshot_last", "Retrieve OLD cached JPEG, NEVER current live pixels.", PROFILE, readonly=True),
]

# Page operations can refuse stale UI selections before issuing a write.
PAGE_TOOLS = {"browser_navigate", "browser_back", "browser_forward", "browser_reload",
              "browser_snapshot", "browser_read", "browser_dom", "browser_find", "browser_links",
              "browser_click", "browser_type", "browser_clear", "browser_toggle", "browser_select",
              "browser_focus", "browser_press", "browser_scroll", "browser_scroll_to", "browser_wait",
              "browser_compatibility", "browser_devtools", "browser_storage_inspect", "browser_cookie_names",
              "browser_click_point", "browser_frames"}
for entry in TOOLS:
    if entry["name"] in PAGE_TOOLS:
        entry["inputSchema"]["properties"].update({"tab": S, "expectedUrl": S, "documentId": S})
        if entry["name"] not in {"browser_navigate", "browser_back", "browser_forward", "browser_reload", "browser_click_point"}:
            entry["inputSchema"]["properties"].update({"frame": S, "expectedFrameUrl": S})
        if entry["name"] in {"browser_read", "browser_dom", "browser_links", "browser_compatibility"}:
            entry["inputSchema"]["properties"]["includeFrames"] = B
CONTROL_TOOLS = {"browser_status", "browser_capabilities", "browser_downloads", "browser_diagnostics",
                 "browser_get_url", "browser_privacy", "browser_profiles", "browser_tabs",
                 "browser_settings", "browser_screenshot_meta", "browser_takeover"}


def execute(name, args):
    if name == "browser_status":
        return api("GET", "/v1/status", profile=args.get("profile"))
    if name == "browser_capabilities":
        return api("GET", "/v1/capabilities")
    if name == "browser_downloads":
        return api("GET", "/v1/downloads", profile=args.get("profile"))
    if name == "browser_diagnostics":
        return api("GET", "/v1/diagnostics")
    if name == "browser_background_recover":
        return api("POST", "/v1/background/recover", {})
    if name == "browser_privacy":
        return api("GET", "/v1/privacy", profile=args.get("profile"))
    if name == "browser_fingerprint_protection":
        return api("POST", "/v1/settings/fingerprint-protection", args)
    if name == "browser_get_url":
        status = api("GET", "/v1/status", profile=args.get("profile"))
        return {"profile": status.get("profile", status.get("activeProfile")), "tab": status.get("activeTab"),
                "url": status.get("url"), "title": status.get("title"), "loading": status.get("loading")}
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
    if name == "browser_bookmarks":
        return api("GET", "/v1/bookmarks", profile=args.get("profile"))
    if name == "browser_bookmark_add":
        return api("POST", "/v1/bookmarks/add", args)
    if name == "browser_history":
        return api("GET", "/v1/history", profile=args.get("profile"))
    if name == "browser_find_in_page":
        return api("POST", "/v1/find-in-page", args)
    if name == "browser_clear_find":
        return api("POST", "/v1/find-in-page/clear", args)
    if name == "browser_settings":
        return api("GET", "/v1/settings")
    if name == "browser_desktop_mode":
        return api("POST", "/v1/settings/desktop", args)
    if name == "browser_search_engine":
        return api("POST", "/v1/settings/search", args)
    if name == "browser_tabs":
        return api("GET", "/v1/tabs", profile=args.get("profile"))
    if name == "browser_tab_new":
        return api("POST", "/v1/tabs/new", args)
    if name == "browser_tab_select":
        return api("POST", "/v1/tabs/select", args)
    if name == "browser_tab_close":
        return api("POST", "/v1/tabs/close", args)
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
        "browser_snapshot": "snapshot", "browser_read": "read",
        "browser_dom": "dom", "browser_find": "find",
        "browser_links": "links", "browser_click": "click", "browser_type": "type",
        "browser_clear": "clear", "browser_toggle": "toggle", "browser_select": "select",
        "browser_focus": "focus", "browser_press": "key", "browser_scroll": "scroll",
        "browser_click_point": "click_point", "browser_frames": "frames",
        "browser_scroll_to": "scrollTo", "browser_wait": "wait",
        "browser_compatibility": "compatibility",
        "browser_devtools": "devtools",
        "browser_storage_inspect": "storage_inspect",
        "browser_cookie_names": "cookie_metadata"
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
            raise BrowserApiError(result.get("error") or "DOM action failed", result.get("code", "PAGE_ACTION_FAILED"))
        if name == "browser_wait" and (result.get("result") or {}).get("found") is False:
            raise BrowserApiError("Requested element or text was not found within timeoutMs", "WAIT_TIMEOUT")
        if name == "browser_snapshot":
            # Keep document/frame IDs, pagination and element bounds for subsequent actions.
            return result
        return result
    raise BrowserApiError("Unknown browser tool: " + name)


_output_lock = threading.Lock()


def reply(obj):
    with _output_lock:
        sys.stdout.write(json.dumps(obj, ensure_ascii=False, separators=(",", ":")) + "\n")
        sys.stdout.flush()


def validate_arguments(name, args):
    entry = next((item for item in TOOLS if item["name"] == name), None)
    if entry is None:
        raise BrowserApiError("Unknown browser tool: " + str(name), "UNKNOWN_TOOL")
    if not isinstance(args, dict):
        raise BrowserApiError("Tool arguments must be an object", "BAD_ARGUMENTS")
    schema = entry["inputSchema"]
    for key in schema["required"]:
        if key not in args:
            raise BrowserApiError("Missing required argument: " + key, "BAD_ARGUMENTS")
    for key, value in args.items():
        prop = schema["properties"].get(key)
        if prop is None:
            raise BrowserApiError("Unknown argument: " + key, "BAD_ARGUMENTS")
        kind = prop["type"]
        if ((kind == "string" and not isinstance(value, str)) or
                (kind == "boolean" and not isinstance(value, bool)) or
                (kind == "integer" and (not isinstance(value, int) or isinstance(value, bool)))):
            raise BrowserApiError("Invalid type for argument: " + key, "BAD_ARGUMENTS")


def invoke_tool(name, args):
    try:
        validate_arguments(name, args)
        result = execute(name, args)
        if isinstance(result, list) and result and result[0].get("type") == "image":
            content = result
        else:
            text = result if isinstance(result, str) else json.dumps(result, ensure_ascii=False)
            content = [{"type": "text", "text": text}]
        return {"content": content, "isError": False}
    except (BrowserApiError, ValueError, KeyError, TypeError, OSError) as error:
        data = error.result() if isinstance(error, BrowserApiError) else {
            "code": "INVALID_RESPONSE", "message": str(error)[:480], "retryable": False}
        return {"content": [{"type": "text", "text": json.dumps(data, ensure_ascii=False)}], "isError": True}


def serve():
    # Serialize page tools to preserve refs and writes, with a separate small
    # control lane. The input loop remains available for ping and cancellation.
    lanes = (queue.Queue(maxsize=8), queue.Queue(maxsize=4))
    pending = {}
    guard = threading.Lock()
    stopping = threading.Event()

    def worker(lane):
        while not stopping.is_set():
            try:
                rid, key, name, args, cancelled = lane.get(timeout=0.1)
            except queue.Empty:
                continue
            try:
                value = None if cancelled.is_set() else invoke_tool(name, args)
                with guard:
                    pending.pop(key, None)
                    if not cancelled.is_set():
                        reply({"jsonrpc": "2.0", "id": rid, "result": value})
            finally:
                lane.task_done()

    threads = [threading.Thread(target=worker, args=(lane,), daemon=True) for lane in lanes]
    for thread in threads:
        thread.start()
    try:
        for line in sys.stdin:
            try:
                item = json.loads(line)
            except (ValueError, UnicodeDecodeError):
                reply({"jsonrpc": "2.0", "id": None, "error": {"code": -32700, "message": "Parse error"}})
                continue
            if not isinstance(item, dict):
                reply({"jsonrpc": "2.0", "id": None, "error": {"code": -32600, "message": "Invalid request"}})
                continue
            rid = item.get("id")
            method = item.get("method")
            params = item.get("params", {})
            if rid is None:
                if method == "notifications/cancelled" and isinstance(params, dict):
                    request_id = params.get("requestId")
                    if isinstance(request_id, (str, int)) and not isinstance(request_id, bool):
                        with guard:
                            cancelled = pending.get((type(request_id), request_id))
                            if cancelled is not None:
                                cancelled.set()
                continue
            if (item.get("jsonrpc") != "2.0" or not isinstance(method, str) or
                    not isinstance(rid, (str, int)) or isinstance(rid, bool)):
                reply({"jsonrpc": "2.0", "id": None, "error": {"code": -32600, "message": "Invalid request"}})
                continue
            if not isinstance(params, dict):
                reply({"jsonrpc": "2.0", "id": rid, "error": {"code": -32602, "message": "Params must be an object"}})
                continue
            if method == "initialize":
                # Explicit legacy compatibility; do not advertise the 2026
                # stateless lifecycle, which this adapter does not implement.
                requested = params.get("protocolVersion")
                version = requested if requested in ("2024-11-05", "2025-03-26") else "2025-03-26"
                value = {"protocolVersion": version, "capabilities": {"tools": {"listChanged": False}},
                         "serverInfo": {"name": "abwr-browser", "version": "0.9.0"}}
            elif method == "ping":
                value = {}
            elif method == "tools/list":
                value = {"tools": TOOLS}
            elif method == "tools/call":
                name = params.get("name")
                args = params.get("arguments", {})
                if not isinstance(name, str):
                    reply({"jsonrpc": "2.0", "id": rid, "error": {"code": -32602, "message": "Tool name must be a string"}})
                    continue
                key = (type(rid), rid)
                cancelled = threading.Event()
                lane = lanes[1 if name in CONTROL_TOOLS else 0]
                with guard:
                    if key in pending:
                        reply({"jsonrpc": "2.0", "id": rid, "error": {"code": -32600, "message": "Duplicate in-progress request ID"}})
                        continue
                    pending[key] = cancelled
                    try:
                        lane.put_nowait((rid, key, name, args, cancelled))
                    except queue.Full:
                        pending.pop(key, None)
                        data = {"code": "BUSY", "message": "MCP work queue is full; retry later", "retryable": True}
                        reply({"jsonrpc": "2.0", "id": rid, "result": {
                            "content": [{"type": "text", "text": json.dumps(data)}], "isError": True}})
                continue
            else:
                reply({"jsonrpc": "2.0", "id": rid, "error": {"code": -32601, "message": "Method not found"}})
                continue
            reply({"jsonrpc": "2.0", "id": rid, "result": value})
    finally:
        # Allow already-finishing work a brief drain; never pin a disconnected
        # MCP process to a full queue of 23-second HTTP calls.
        for thread in threads:
            thread.join(timeout=0.25)
        with guard:
            for cancelled in pending.values():
                cancelled.set()
        stopping.set()


if __name__ == "__main__":
    serve()
