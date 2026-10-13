#!/usr/bin/env python3
"""Abwr V1 readiness smoke check. Uses only local loopback and standard library.

Run from Termux with AI_BROWSER_TOKEN exported. No passwords/tokens/cookies are
printed or written to disk. Optional --screenshot validates JPEG header in memory.
"""
import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from urllib.parse import urlsplit

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*_):return None


def check(host, token, path, screenshot=False):
    req = urllib.request.Request(host + path,
        headers={"Authorization": "Bearer " + token} if path != "/health" else {},
        method="GET")
    try:
        with urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect()).open(req, timeout=17) as res:
            raw = res.read(7_000_000 if screenshot else 300_000)
            if screenshot:
                if not raw.startswith(b"\xff\xd8\xff"):
                    raise ValueError("Response did not contain a JPEG")
                return {"ok": True, "jpegBytes": len(raw), "noFileSaved": True}
            payload = json.loads(raw)
            if not isinstance(payload, dict):
                raise ValueError("Expected an API object")
            return payload
    except urllib.error.HTTPError as exc:
        return {"ok": False, "httpStatus": exc.code,
                "error": exc.reason}  # Deliberately do not expose private response body
    except Exception as exc:
        return {"ok": False, "errorType": type(exc).__name__,
                "hint": "Check Abwr foreground service, local token and screen surface"}


def main():
    parser = argparse.ArgumentParser(description="Abwr authorized local V1 smoke check")
    parser.add_argument("--screenshot", action="store_true",
                        help="Attempt LIVE JPEG capture; verify in memory, never save pixels")
    args = parser.parse_args()
    host = os.environ.get("AI_BROWSER_HOST", "http://127.0.0.1:8765").rstrip("/")
    try:
        uri = urlsplit(host)
        valid = (uri.scheme == "http" and uri.hostname == "127.0.0.1" and uri.port == 8765
                 and not uri.username and not uri.password and not uri.path and not uri.query and not uri.fragment)
    except ValueError:valid = False
    if not valid:
        parser.error("AI_BROWSER_HOST must be http://127.0.0.1:8765")
    token = os.environ.get("AI_BROWSER_TOKEN", "")
    if not token:
        parser.error("Export AI_BROWSER_TOKEN in your local Termux session")
    results = {}
    for name, path in [
        ("health", "/health"), ("status", "/v1/status"),
        ("diagnostics", "/v1/diagnostics"), ("capabilities", "/v1/capabilities"),
        ("tabs", "/v1/tabs"), ("screenshotMeta", "/v1/screenshot/meta"),
    ]:
        response = check(host, token, path)
        healthy = response.get("ok") is not False and "error" not in response and "errorType" not in response
        if name == "status" and healthy:
            # Never dump a full current URL: OAuth callbacks may carry codes.
            results[name] = {
                "ok": response.get("ready") is True and response.get("extensionPortReady") is True,
                "ready": response.get("ready"), "activeTab": response.get("activeTab"),
                "loading": response.get("loading"), "bridgeReady": response.get("extensionPortReady"),
                "backgroundPhase": response.get("backgroundRendererPhase"),
                "backgroundGuaranteed": response.get("backgroundScreenshotGuaranteed"),
            }
        elif name == "diagnostics" and healthy:
            results[name] = {
                key: response.get(key) for key in (
                    "backgroundRendererPhase", "backgroundDisplayFrames",
                    "backgroundLastFrameAgeMs", "backgroundRecoveryAttempts",
                    "backgroundSurfaceError", "memoryHeapUsedBytes",
                    "batteryOptimizationExempt", "uptimeMs")
            }
        elif name == "tabs" and healthy:
            results[name] = {"count": len(response.get("tabs", [])),
                             "popupActive": response.get("popupActive")}
        elif name == "capabilities" and healthy:
            results[name] = {"version": response.get("apiVersion"),
                             "engine": response.get("engine")}
        elif name == 'screenshotMeta' and healthy:
            results[name] = {key:response.get(key) for key in (
                'profile','tab','documentId','capturedAtMs','width','height','stale','cached')}
        else:
            results[name] = response
    if args.screenshot:
        results["liveScreenshot"] = check(host, token, "/v1/screenshot", screenshot=True)
    print(json.dumps(results, ensure_ascii=False, indent=2))
    if any(item.get("ok") is False for item in results.values() if isinstance(item, dict)):
        sys.exit(1)


if __name__ == "__main__":
    main()
