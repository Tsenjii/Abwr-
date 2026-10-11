#!/usr/bin/env python3
"""Exercise a fresh CI emulator. Refuses production package; never prints the API token."""
import io
import json
import os
from pathlib import Path
import re
import subprocess
import threading
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

from PIL import Image
from acceptance_fixture import make_server

PACKAGE = "dev.local.aibrowser.dev"
OUT = Path("ci-device-evidence")
OUT.mkdir(exist_ok=True)
cases = []
token = None


def adb(*args, check=True):
    return subprocess.run(["adb", *args], check=check, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, timeout=35).stdout


def until(fn, timeout=35):
    end = time.monotonic() + timeout
    last = None
    while time.monotonic() < end:
        try:
            value = fn()
            if value is not None and value is not False:
                return value
        except (OSError, ValueError, AssertionError) as error:
            last = type(error).__name__
        time.sleep(.35)
    raise AssertionError("Condition timed out" + (": " + last if last else ""))


def api(path, body=None, expected_status=200, raw=False):
    request = urllib.request.Request("http://127.0.0.1:8765" + path,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
    try:
        response = urllib.request.urlopen(request, timeout=20)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        data = response.read()
        assert response.status == expected_status, (path, response.status,
            json.loads(data).get("code") if data.startswith(b"{") else "non-json")
        return data if raw else json.loads(data)


def command(action, **args):
    response = api("/v1/command", {"action": action, **args})
    assert response.get("ok") is True, (action, response.get("code"), response.get("error"))
    return response["result"]


def navigate(url):
    api("/v1/navigate", {"url": url, "waitForLoad": True, "timeoutMs": 12000})
    return until(lambda: (s if (s := api("/v1/status")).get("extensionPortReady")
                         and s.get("url") == url else None))


def run(name, fn):
    started = time.monotonic()
    try:
        fn()
        cases.append({"case": name, "result": "PASS", "seconds": round(time.monotonic()-started, 2)})
        print("PASS", name, flush=True)
    except Exception as error:
        cases.append({"case": name, "result": "FAIL", "error": type(error).__name__,
                      "detail": str(error)[:350]})
        print("FAIL", name, type(error).__name__, str(error)[:350], flush=True)
    (OUT / "results.json").write_text(json.dumps(cases, ensure_ascii=False, indent=2))


def ui_tree():
    adb("shell", "uiautomator", "dump", "/sdcard/abwr-ui.xml")
    raw = adb("shell", "cat", "/sdcard/abwr-ui.xml")
    return ET.fromstring(raw)


def tap_node(predicate):
    node = until(lambda: next((n for n in ui_tree().iter("node") if predicate(n)), None), 15)
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds", "")))
    adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))


def ui_image(name):
    time.sleep(.4)
    (OUT / (name + ".png")).write_bytes(adb("exec-out", "screencap", "-p"))


def capture_color(name, rgb):
    # Explicit repeated READ captures permit compositor settling; never repeat page writes.
    def capture():
        raw = api("/v1/screenshot", raw=True)
        image = Image.open(io.BytesIO(raw)).convert("RGB")
        meta = api("/v1/screenshot/meta")
        width, height = image.size
        assert width >= 300 and height >= 500, (width, height)
        pixel = image.getpixel((width//2, height*3//4))
        assert max(abs(pixel[i]-rgb[i]) for i in range(3)) < 24, pixel
        (OUT / (name + ".jpg")).write_bytes(raw)
        (OUT / (name + "-meta.json")).write_text(json.dumps(meta, indent=2))
        return True
    until(capture, 25)


def main():
    global token
    assert os.environ.get("ABWR_CI_EMULATOR") == "1", "CI emulator only"
    child = make_server()
    top = make_server(child_port=child.server_port)
    for server in (child, top):
        threading.Thread(target=server.serve_forever, daemon=True).start()
        port = str(server.server_port)
        adb("reverse", "tcp:" + port, "tcp:" + port)
    base = "http://127.0.0.1:%d" % top.server_port
    fixture = base + "/"
    adb("shell", "pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS", check=False)
    adb("shell", "am", "start", "-n", PACKAGE + "/dev.local.aibrowser.MainActivity")
    adb("forward", "tcp:8765", "tcp:8765")

    def read_token():
        raw = adb("shell", "run-as", PACKAGE, "cat", "shared_prefs/local_api.xml", check=False)
        try:
            root = ET.fromstring(raw)
            return next((n.text for n in root if n.get("name") == "token"), None)
        except ET.ParseError:
            return None
    token = until(read_token, 45)
    until(lambda: api("/v1/status").get("ready"), 60)

    def identity_and_snapshot():
        s = navigate(fixture)
        snapshot = command("snapshot")
        assert snapshot["url"] == s["url"]
        assert snapshot["documentId"] == s["documentId"]
        assert any(e["selectorHint"] == "#late" for e in snapshot["elements"])
        assert any(e.get("accessibleName") == "Name" for e in snapshot["elements"])
        assert any(e["selectorHint"] == "#shadow-button" for e in snapshot["elements"])
        assert not any(e["selectorHint"] == "#hidden" for e in snapshot["elements"])
        assert "Navigation 179" not in snapshot["snapshot"]
        old = s["documentId"]
        navigate(fixture + "?new=1")
        stale = api("/v1/command", {"action": "read", "documentId": old}, expected_status=409)
        assert stale["code"] == "PAGE_CHANGED"
        navigate(fixture)
    run("document identity, priority snapshot and shadow roots", identity_and_snapshot)

    def frames():
        result = until(lambda: (r if len((r := command("snapshot"))["frames"]) else None))
        frame = next(f for f in result["frames"] if "/child" in f["url"])
        command("click", frame=frame["frame"], selector="#frame-button")
        assert "clicked child" in command("read", frame=frame["frame"])["text"]
    run("cross-origin iframe read and click", frames)

    def arguments():
        for args in ({"action": "click"}, {"action": "click", "selector": 7},
                     {"action": "read", "unexpected": True}):
            result = api("/v1/command", args, expected_status=400)
            assert result["code"] == "BAD_ARGUMENTS"
        for selector in ("#disabled", "#hidden"):
            result = api("/v1/command", {"action": "toggle", "selector": selector})
            assert not result["ok"] and result["code"] == "TARGET_NOT_INTERACTABLE"
        result = api("/v1/command", {"action": "wait", "selector": "#missing", "timeoutMs": 100})
        assert not result["ok"] and result["code"] == "WAIT_TIMEOUT"
    run("HTTP validation and non-interactable targets", arguments)

    def input_actions():
        command("select", selector="#choice", value="b")
        assert command("find", selector="#choice")["elements"][0]["value"] == "b"
        command("type", selector="#name", text="erase me")
        command("key", selector="#name", key="Ctrl+A")
        command("key", key="Backspace")
        until(lambda: command("find", selector="#name")["elements"][0]["value"] == "")
        command("type", selector="#name", text="ci-native-enter")
        command("key", selector="#name", key="Enter")
        until(lambda: "ci-native-enter" in command("read")["text"])
        navigate(fixture)
        command("scrollTo", selector="#late")
        shot = Image.open(io.BytesIO(api("/v1/screenshot", raw=True)))
        target = command("find", selector="#late")
        rect = target["elements"][0]["rect"]
        viewport = target["viewport"]
        x = (rect["x"] + rect["width"]/2) * shot.width / viewport["width"]
        y = (rect["y"] + rect["height"]/2) * shot.height / viewport["height"]
        command("click_point", x=x, y=y, imageWidth=shot.width, imageHeight=shot.height)
        until(lambda: "clicked late" in command("read")["text"])
    run("select, native keyboard, coordinate input", input_actions)

    def cycles():
        for index in range(50):
            s = navigate(fixture + "?cycle=" + str(index))
            snapshot = command("snapshot", expectedUrl=s["url"], documentId=s["documentId"])
            target = next(e["ref"] for e in snapshot["elements"] if e["selectorHint"] == "#name")
            command("type", target=target, text="cycle-" + str(index), documentId=s["documentId"])
            command("click", selector="#late", documentId=s["documentId"])
            assert "clicked late" in command("read", documentId=s["documentId"])["text"]
        cases.append({"case": "cycle count", "completed": 50})
    run("50 sequential navigation and operation cycles", cycles)

    def tabs_profiles_ui():
        first = api("/v1/tabs/new", {"url": fixture})["id"]
        second = api("/v1/tabs/new", {"url": fixture})["id"]
        listing = api("/v1/tabs")
        assert len(listing["tabs"]) == api("/v1/status")["tabCount"] == 3
        api("/v1/tabs/close", {"id": "main"})
        assert len(api("/v1/tabs")["tabs"]) == 2
        api("/v1/tabs/select", {"id": first})
        navigate(fixture)
        ui_image("ui-browser")
        tap_node(lambda n: "開啟分頁面板" in n.get("content-desc", ""))
        ui_image("ui-tabs")
        adb("shell", "input", "keyevent", "4")
        tap_node(lambda n: n.get("content-desc") == "選單")
        ui_image("ui-menu")
        adb("shell", "input", "keyevent", "4")
        tap_node(lambda n: "目前容器" in n.get("content-desc", ""))
        ui_image("ui-profiles")
        adb("shell", "input", "keyevent", "4")
        api("/v1/profiles/create", {"id": "CI001"})
        api("/v1/profiles/switch", {"id": "CI001"})
        assert api("/v1/status")["activeProfile"] == "CI001"
        api("/v1/profiles/switch", {"id": "G001"})
        api("/v1/profiles/delete", {"id": "CI001"})
        api("/v1/tabs/close", {"id": second})
    run("tab counts, closing main, profile HTTP routes and UI evidence", tabs_profiles_ui)

    def foreground_background_pixels():
        navigate(base + "/color?c=green")
        capture_color("foreground-green", (20, 184, 122))
        adb("shell", "input", "keyevent", "3")
        until(lambda: api("/v1/status").get("experimentalBackgroundSurfaceAttached"), 30)
        navigate(base + "/color?c=blue")
        capture_color("background-blue", (50, 105, 221))
        (OUT / "background-diagnostics.json").write_text(json.dumps(api("/v1/diagnostics"), indent=2))
        adb("shell", "am", "start", "-n", PACKAGE + "/dev.local.aibrowser.MainActivity")
    run("full foreground viewport and fresh background screenshot", foreground_background_pixels)

    (OUT / "logcat.txt").write_bytes(adb("logcat", "-d", "-t", "1500", "AndroidRuntime:E", "AIBrowser:W", "*:S"))
    (OUT / "installed-application.json").write_text(json.dumps(api("/v1/diagnostics").get("installedApplication"), indent=2))
    assert not any(case.get("result") == "FAIL" for case in cases), "Emulator acceptance failed"


if __name__ == "__main__":
    main()
