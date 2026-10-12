"""Android-only browser adapter. No Playwright import or desktop path on the abwr backend."""
from __future__ import annotations
import base64
import contextlib
import fcntl
import json
import os
from pathlib import Path
import re
import threading
import time
import urllib.error
import urllib.request
from urllib.parse import urlsplit, urlunsplit

VERSION = "0.14.0"
SCHEMA_VERSION = 4
MCP_TOOL_COUNT = 82
WRITE_ACTIONS = {"click", "type", "clear", "toggle", "select", "focus", "key", "key_sequence", "pointer_path", "click_point", "drag", "scroll", "scrollTo", "navigate"}

class AbwrError(RuntimeError):
    def __init__(self, code, message, retryable=False):
        super().__init__(message)
        self.code, self.retryable = code, bool(retryable)

class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *_): return None

class QuotaLedger:
    """Reservations survive restarts and uncertain writes. The caller never silently refunds/replays."""
    def __init__(self, root, limit=1000, gap_ms=1200):
        if not isinstance(limit, int) or limit < 1 or gap_ms < 1200:
            raise ValueError("A positive quota and at least 1200 ms pacing are required")
        self.root, self.limit, self.gap_ms = Path(root), limit, gap_ms
        self.root.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()
    def reserve(self, bucket, now=None):
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,40}", bucket): raise ValueError("Invalid exit bucket")
        now = time.time() if now is None else now
        day = int(now // 86400)  # Explicit UTC quota day; stored alongside the reservation.
        with self._lock, (self.root / "quota.lock").open("a+") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            path = self.root / (bucket + ".jsonl")
            count, last = 0, 0.0
            if path.exists():
                for line in path.read_text().splitlines():
                    try: item = json.loads(line)
                    except ValueError: raise AbwrError("LEDGER_CORRUPT", "Quota ledger cannot be read safely") from None
                    if item.get("event") == "reserved":
                        if item.get("day") == day: count += 1
                        last = max(last, item["scheduledAt"])
            if count >= self.limit:
                self._append(path, {"event":"quota_reached", "day":day, "timestamp":now})
                raise AbwrError("QUOTA_REACHED", "Exit quota reached; batch stopped")
            scheduled = max(now, last + self.gap_ms / 1000)
            self._append(path, {"event":"reserved", "day":day, "scheduledAt":scheduled, "timestamp":now})
            return max(0, round((scheduled - now) * 1000))
    @staticmethod
    def _append(path, item):
        with path.open("a", encoding="utf-8") as stream:
            os.chmod(path, 0o600)
            stream.write(json.dumps(item, separators=(",", ":")) + "\n")
            stream.flush(); os.fsync(stream.fileno())

class AbwrBrowser:
    def __init__(self, host=None, token=None, root=None, quota=1000, transport=None):
        self.host = (host or os.environ.get("AI_BROWSER_HOST", "http://127.0.0.1:8765")).rstrip("/")
        self.token = token or os.environ.get("AI_BROWSER_TOKEN", "")
        try:
            url = urlsplit(self.host)
            valid = url.scheme == "http" and url.hostname == "127.0.0.1" and url.port == 8765 and not url.username and not url.password and not url.path and not url.query and not url.fragment
        except ValueError: valid = False
        if not valid: raise AbwrError("BAD_ARGUMENTS", "ABWR must use http://127.0.0.1:8765")
        if not self.token: raise AbwrError("UNAUTHORIZED", "AI_BROWSER_TOKEN is required")
        self.root = Path(root or Path.home() / ".workbuddy-ai")
        for name in ("ledger", "shots", "state"): (self.root / name).mkdir(parents=True, exist_ok=True)
        self.ledger = QuotaLedger(self.root / "state", quota)
        self._transport = transport
        self._versions_checked = False
        self._locks = {}; self._lock = threading.Lock(); self._workers = threading.BoundedSemaphore(2)
        self._opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), _NoRedirect())
    def request(self, method, path, data=None, profile=None, timeout=23):
        if not path.startswith("/v1/") or "?" in path or "#" in path: raise AbwrError("BAD_ARGUMENTS", "Invalid local API path")
        if self._transport: return self._transport(method, path, data, profile)
        headers = {"Authorization":"Bearer " + self.token, "Content-Type":"application/json"}
        if profile: headers["X-Browser-Profile"] = profile
        body = json.dumps(data, allow_nan=False).encode() if data is not None else None
        req = urllib.request.Request(self.host + path, body, headers, method=method)
        try:
            with self._opener.open(req, timeout=timeout) as response:
                raw = response.read(8 * 1024 * 1024 + 1)
                if len(raw) > 8 * 1024 * 1024: raise AbwrError("RESPONSE_TOO_LARGE", "Local reply exceeds 8 MiB")
                reply = json.loads(raw)
        except urllib.error.HTTPError as exc:
            if 300 <= exc.code < 400: raise AbwrError("REDIRECT_REJECTED", "Local API redirects are refused") from None
            try: reply = json.loads(exc.read(4096))
            except ValueError: reply = {}
            raise AbwrError(reply.get("code", "HTTP_ERROR"), reply.get("message", "Local API rejected request"), reply.get("retryable", False)) from None
        except (urllib.error.URLError, TimeoutError, OSError):
            raise AbwrError("CONNECTION_FAILED", "Local operation outcome is uncertain; inspect before retrying a write") from None
        if not isinstance(reply, dict): raise AbwrError("INVALID_RESPONSE", "Local reply must be an object")
        if reply.get("ok") is False: raise AbwrError(reply.get("code", "API_ERROR"), reply.get("message", "Operation failed"), reply.get("retryable", False))
        return reply
    def check_versions(self, profile=None):
        caps = self.request("GET", "/v1/capabilities")
        status = self.request("GET", "/v1/status", profile=profile)
        installed = self.request("GET", "/v1/diagnostics").get("installedApplication", {})
        known = [caps.get("apiVersion"), installed.get("versionName")]
        if status.get("contentBridgeVersion") is not None: known.append(status["contentBridgeVersion"])
        if any(x != VERSION for x in known) or caps.get("bridgeSchemaVersion") != SCHEMA_VERSION or caps.get("mcpToolCount") != MCP_TOOL_COUNT:
            raise AbwrError("VERSION_MISMATCH", "App, API, content bridge or SDK/MCP versions differ; update the APK and bridge together")
        self._versions_checked = True
        return {"version":VERSION, "schema":SCHEMA_VERSION, "toolCount":MCP_TOOL_COUNT, "contentBridgeVerified":status.get("contentBridgeVersion") == VERSION}
    def context(self, profile, exit_bucket=None, create=False):
        if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_-]{0,30}", profile): raise ValueError("Invalid profile")
        if create: self.request("POST", "/v1/profiles/create", {"id":profile})
        with self._lock: lock = self._locks.setdefault(profile, threading.RLock())
        return AbwrContext(self, profile, exit_bucket or "direct", lock)
    def queues(self): return self.request("GET", "/v1/queues")

class AbwrContext:
    def __init__(self, browser, profile, exit_bucket, lock):
        self.browser, self.profile, self.exit_bucket, self.lock = browser, profile, exit_bucket, lock
    def page(self, tab="main"): return AbwrPage(self, tab)
    def new_page(self, url=None):
        reply = self.browser.request("POST", "/v1/tabs/new", {"profile":self.profile, **({"url":url} if url else {})})
        tab = reply.get("id", reply.get("tab"))
        if not isinstance(tab,str): raise AbwrError("INVALID_RESPONSE", "New tab reply omitted its ID")
        return self.page(tab)
    def takeover(self, manual):
        return self.browser.request("POST", "/v1/takeover", {"profile":self.profile, "manual":bool(manual)})
    def set_proxy(self, server, username="", password=""):
        return self.browser.request("POST", "/v1/proxy", {"profile":self.profile,"server":server,"username":username,"password":password})
    def proxy(self): return self.browser.request("GET", "/v1/proxy", profile=self.profile)

class AbwrPage:
    def __init__(self, context, tab):
        self.context, self.browser, self.profile, self.tab = context, context.browser, context.profile, tab
        self._responses = []; self._cursor = 0
    def status(self):
        state = self.browser.request("GET", "/v1/status", profile=self.profile)
        if state.get("activeTab") != self.tab: raise AbwrError("PAGE_CHANGED", "Select the intended tab before using this page")
        return state
    def select(self): return self.browser.request("POST", "/v1/tabs/select", {"profile":self.profile,"id":self.tab})
    def _wait_delay(self, duration):
        while duration > 0:
            chunk = min(duration, 20000)
            self.browser.request("POST", "/v1/command", {"action":"wait","condition":"delay","durationMs":chunk,"timeoutMs":max(100,chunk),"profile":self.profile,"tab":self.tab})
            duration -= chunk
    def command(self, action, **params):
        with self.context.lock, self.browser._workers:
            if not self.browser._versions_checked: self.browser.check_versions(self.profile)
            state = self.status()
            payload = {"profile":self.profile,"tab":self.tab,"action":action,**params}
            if action not in {"wait","navigate"} and state.get("documentId"):
                payload.setdefault("documentId", state["documentId"])
                payload.setdefault("expectedUrl", state["url"])
            if action in WRITE_ACTIONS:
                self._wait_delay(self.browser.ledger.reserve(self.context.exit_bucket))
            reply = self.browser.request("POST", "/v1/command", payload)
            data = reply.get("result",reply)
            if action in WRITE_ACTIONS:
                # Read the visible outcome once; never retry the write after a timeout/disconnect.
                data["after"] = self.status()
                data["siteCompleted"] = False
            return data
    def goto(self, url, timeout=12000): return self.command("navigate", url=url, waitForLoad=True, timeoutMs=timeout)
    def evaluate(self, *_): raise AbwrError("NOT_IMPLEMENTED", "Use allowlisted geometry, DOM, snapshot or storage probes")
    def fill(self, selector, text, **scope): return self.command("type",selector=selector,text=text,**scope)
    def click(self, selector, **scope): return self.command("click",selector=selector,**scope)
    def press(self, key, **scope): return self.command("key",key=key,**scope)
    def type_keys(self, keys, interval_ms=50): return self.command("key_sequence",keys=keys,intervalMs=interval_ms)
    def pointer_path(self, steps, **dimensions): return self.command("pointer_path",steps=steps,**dimensions)
    def wait(self, condition=None, timeout=10000, **params): return self.command("wait",timeoutMs=timeout,**({"condition":condition} if condition else {}),**params)
    def wait_for_timeout(self, ms): return self.wait("delay",durationMs=ms,timeout=max(100,ms))
    def snapshot(self, **params): return self.command("snapshot",**params)
    def bounding_box(self, selector, **scope): return self.command("geometry",selector=selector,**scope)["element"]["bounding_box"]
    def links(self, limit=60, offset=0): return self.command("links",limit=limit,offset=offset)
    def prompts(self): return self.browser.request("GET", "/v1/prompts", profile=self.profile)
    def resolve_prompt(self, prompt, decision, **params):
        return self.browser.request("POST", "/v1/prompts/resolve", {"profile":self.profile,"tab":self.tab,"id":prompt["id"],"scopeToken":prompt["scopeToken"],"decision":decision,**params})
    def on(self, name, callback):
        if name != "response": raise AbwrError("NOT_IMPLEMENTED", "Only response events are exposed")
        if not self._responses: self.browser.request("POST", "/v1/network/observe", {"profile":self.profile,"tab":self.tab,"record":True,"clear":True})
        self._responses.append(callback)
    def pump_events(self):
        reply = self.browser.request("POST", "/v1/network/observe", {"profile":self.profile,"tab":self.tab,"afterSeq":self._cursor,"limit":200})
        self._cursor = reply["nextSeq"]
        events = [e for e in reply["events"] if e.get("phase") == "response"]
        for event in events:
            for callback in self._responses: callback(event)
        return events
    def wait_for_response(self, predicate, timeout=10000):
        if not self._responses: self.on("response",lambda _: None)
        deadline = time.monotonic() + timeout / 1000
        while time.monotonic() < deadline:
            for event in self.pump_events():
                if predicate(event): return event
            self._wait_delay(min(100,max(1,round((deadline-time.monotonic())*1000))))
        raise AbwrError("WAIT_TIMEOUT", "No matching network response")
    def export_download(self, identifier, name):
        if not re.fullmatch(r"[A-Za-z0-9_.-]{1,100}",name) or name in {".",".."}:raise ValueError("Invalid export name")
        target=self.browser.root/"ledger"/name
        offset=0
        temporary=target.with_suffix(target.suffix+".part")
        try:
            with temporary.open("wb") as stream:
                os.chmod(temporary,0o600)
                while True:
                    chunk=self.browser.request("POST","/v1/downloads/action",{"profile":self.profile,"id":identifier,"operation":"export","offset":offset,"maxBytes":65536})
                    data=base64.b64decode(chunk["dataBase64"],validate=True)
                    if chunk["offset"]!=offset or chunk["nextOffset"]!=offset+len(data):raise AbwrError("INVALID_RESPONSE","Download chunk identity differs")
                    stream.write(data);offset=chunk["nextOffset"]
                    if chunk["done"]:
                        if offset!=chunk["totalBytes"]:raise AbwrError("INVALID_RESPONSE","Download ended before its expected size")
                        break
            temporary.replace(target);return target
        except Exception:
            temporary.unlink(missing_ok=True);raise
    def screenshot(self):
        with self.context.lock, self.browser._workers:
            before = self.status()
            evidence = self.browser.request("GET", "/v1/screenshot/evidence", profile=self.profile)
            meta = dict(evidence["metadata"])
            if meta.get("profile") != self.profile or meta.get("tab") != self.tab or meta.get("documentId") != before.get("documentId") or meta.get("stale") is not False:
                raise AbwrError("PAGE_CHANGED", "Screenshot identity differs from the requested document")
            image = base64.b64decode(evidence["imageBase64"],validate=True)
            if image[:3] != b"\xff\xd8\xff": raise AbwrError("INVALID_RESPONSE", "Expected fresh JPEG")
            components = [self.profile,self.tab,str(meta["documentId"]),str(meta["capturedAtMs"])]
            name = "_".join(re.sub(r"[^A-Za-z0-9_-]","_",part)[:100] for part in components)
            target = self.browser.root / "shots" / (name+".jpg")
            with target.open("wb") as stream: os.chmod(target,0o600);stream.write(image)
            url = urlsplit(meta.get("url", "")); authority=url.hostname or ""
            if ":" in authority: authority="["+authority+"]"
            if url.port: authority+=":"+str(url.port)
            meta["url"] = urlunsplit((url.scheme,authority,url.path,"",""))
            sidecar = target.with_suffix(".json")
            with sidecar.open("w") as stream: os.chmod(sidecar,0o600);json.dump(meta,stream,ensure_ascii=False)
            return target

def browser_backend(config):
    if config.get("backend","abwr") == "abwr":
        return AbwrBrowser(host=config.get("host"),token=config.get("token"),root=config.get("root"))
    if config["backend"] != "desktop-chrome": raise ValueError("Unknown browser backend")
    from playwright.sync_api import sync_playwright  # Only this explicit desktop branch imports it.
    engine = sync_playwright().start()
    return engine.chromium.launch(**({"executable_path":config["executable_path"]} if config.get("executable_path") else {}))
