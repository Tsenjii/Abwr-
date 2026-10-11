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


def check(host, token, path, screenshot=False):
    req = urllib.request.Request(host + path,
        headers={"Authorization": "Bearer " + token} if path != "/health" else {},
        method="GET")
    try:
        with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req, timeout=17) as res:
            raw = res.read(7_000_000 if screenshot else 300_000)
            if screenshot:
                if not raw.startswith(b"\xff\xd8\xff"):
                    raise ValueError("Response did not contain a JPEG")
                return {"ok": True, "jpegBytes": len(raw), "noFileSaved": True}
            return json.loads(raw)
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
    uri = urlsplit(host)
    if (uri.scheme != "http" or uri.hostname not in ("127.0.0.1", "localhost")
            or uri.port is None or uri.username or uri.password or uri.path
            or uri.query or uri.fragment):
        parser.error("AI_BROWSER_HOST must be a bare local loopback HTTP host:port")
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
        if name == "status" and "error" not in response:
            # Never dump a full current URL: OAuth callbacks may carry codes.
            results[name] = {
                "ready": response.get("ready"), "activeTab": response.get("activeTab"),
                "loading": response.get("loading"), "bridgeReady": response.get("extensionPortReady"),
                "backgroundPhase": response.get("backgroundRendererPhase"),
                "backgroundGuaranteed": response.get("backgroundScreenshotGuaranteed"),
            }
        elif name == "diagnostics" and "error" not in response:
            results[name] = {
                key: response.get(key) for key in (
                    "backgroundRendererPhase", "backgroundDisplayFrames",
                    "backgroundLastFrameAgeMs", "backgroundRecoveryAttempts",
                    "backgroundSurfaceError", "memoryHeapUsedBytes",
                    "batteryOptimizationExempt", "uptimeMs")
            }
        elif name == "tabs" and "error" not in response:
            results[name] = {"count": len(response.get("tabs", [])),
                             "popupActive": response.get("popupActive")}
        elif name == "capabilities" and "error" not in response:
            results[name] = {"version": response.get("apiVersion"),
                             "engine": response.get("engine")}
        else:
            results[name] = response
    if args.screenshot:
        results["liveScreenshot"] = check(host, token, "/v1/screenshot", screenshot=True)
    print(json.dumps(results, ensure_ascii=False, indent=2))
    if any(item.get("ok") is False for item in results.values() if isinstance(item, dict)):
        sys.exit(1)


if __name__ == "__main__":
    main()
