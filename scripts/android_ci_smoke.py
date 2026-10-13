#!/usr/bin/env python3
"""Exercise a fresh CI emulator. Production package requires Actions; never prints the API token."""
import io
import hashlib
import base64
import json
import os
from pathlib import Path
import re
import select
import shlex
import subprocess
import threading
import sys
import traceback
import time
import urllib.error
import urllib.request
from urllib.parse import quote
import xml.etree.ElementTree as ET

from PIL import Image
from acceptance_fixture import make_server

PACKAGE = os.environ.get("ABWR_CI_PACKAGE", "dev.local.aibrowser.dev")
SUITE = os.environ.get("ABWR_CI_SUITE", "all")
BROWSER_CASES = {
    "native workbench UI and real sandbox script",
    "opt-in console and current-tab request/response body recording",
    "Mozilla store signature install, popup and options",
    "private cookie scope and no disk history, tab reopen, native drag and media fullscreen",
    "native reading, authenticated 5 MiB download, PDF and HTML bookmark export",
}
AUTOMATION_CASES = {
    "document identity, priority snapshot and shadow roots",
    "timed input, conditional waits, network events and shared prompt API",
    "15 isolated fixture profiles measured sequentially",
    "isolated profile proxy routes, new tabs, HTTPS and no direct fallback",
}
OUT = Path("ci-device-evidence")
OUT.mkdir(exist_ok=True)
cases = []
token = None
last_request = None


def adb(*args, check=True):
    return subprocess.run(["adb", *args], check=check, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, timeout=35).stdout


def app_data(command, relative_path, check=True):
    assert command in {"cat", "sha256sum"}
    assert not Path(relative_path).is_absolute() and ".." not in Path(relative_path).parts
    if PACKAGE == "dev.local.aibrowser.dev":
        return adb("shell", "run-as", PACKAGE, command, relative_path, check=check)
    # Only the fresh, rooted Actions emulator may inspect the actual release
    # package. The product APK stays non-debuggable and uses its pinned key.
    assert PACKAGE == "dev.local.aibrowser" and os.environ.get("GITHUB_ACTIONS") == "true"
    return adb("shell", command, "/data/user/0/" + PACKAGE + "/" + relative_path, check=check)


def collect_process_evidence():
    # HTTP can disappear after a native crash. Collect from Android independently
    # of the API, including the DEBUG backtrace that the filtered log omitted.
    for filename, arguments in (
        ("native-crash-buffer.txt", ("logcat", "-b", "crash", "-d", "-v", "threadtime")),
        ("system-exit-info.txt", ("shell", "dumpsys", "activity", "exit-info", PACKAGE)),
        ("final-process-id.txt", ("shell", "pidof", PACKAGE)),
    ):
        try:
            data = adb(*arguments, check=False)
            if token:
                data = data.replace(token.encode(), b"[REDACTED]")
            (OUT / filename).write_bytes(data)
        except Exception as error:
            (OUT / (filename + ".error")).write_text(type(error).__name__)


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


def api(path, body=None, expected_status=200, raw=False, profile=None, method=None):
    global last_request
    actual_method = method or ("POST" if body is not None else "GET")
    last_request = {"path": path, "method": actual_method, "profile": profile or (body or {}).get("profile"),
                    "action": (body or {}).get("action")}
    headers = {"Authorization": "Bearer " + token, "Content-Type": "application/json"}
    if profile:
        headers["X-Browser-Profile"] = profile
    request = urllib.request.Request("http://127.0.0.1:8765" + path,
        data=json.dumps(body).encode() if body is not None else None,
        method=actual_method, headers=headers)
    try:
        response = urllib.request.urlopen(request, timeout=20)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        data = response.read()
        expected = expected_status if isinstance(expected_status, tuple) else (expected_status,)
        assert response.status in expected, (path, response.status,
            json.loads(data).get("code") if data.startswith(b"{") else "non-json")
        return data if raw else json.loads(data)


def command(action, **args):
    response = api("/v1/command", {"action": action, **args})
    assert response.get("ok") is True, (action, response.get("code"), response.get("error"))
    return response["result"]


def navigate(url, profile=None):
    payload = {"url": url, "waitForLoad": True, "timeoutMs": 12000}
    if profile:
        payload["profile"] = profile
    completed = api("/v1/navigate", payload)
    assert completed["navigationCompleted"] and completed["loadSucceeded"]
    assert completed["loadSignal"] in ("fresh_document_ready_state_complete", "same_document_ready_state_complete")
    current = until(lambda: (s if (s := api("/v1/status", profile=profile)).get("extensionPortReady")
                            and s.get("url") == url else None))
    assert completed["documentId"] == current["documentId"], {
        "completedDocument": completed["documentId"], "currentDocument": current["documentId"],
        "joinedInitialLoad": completed.get("joinedInitialLoad"), "loading": current.get("loading")}
    return current


def run(name, fn):
    if (SUITE == "core" and name in BROWSER_CASES) or (SUITE == "browser" and name not in BROWSER_CASES) or (SUITE == "automation" and name not in AUTOMATION_CASES):
        cases.append({"case": name, "result": "NOT_RUN", "reason": "Covered by the other CI suite"})
        (OUT / "results.json").write_text(json.dumps(cases, ensure_ascii=False, indent=2))
        return
    started = time.monotonic()
    try:
        fn()
        cases.append({"case": name, "result": "PASS", "seconds": round(time.monotonic()-started, 2)})
        print("PASS", name, flush=True)
    except Exception as error:
        frames = traceback.extract_tb(error.__traceback__)
        source = frames[-1]
        cases.append({"case": name, "result": "FAIL", "error": type(error).__name__,
                      "detail": str(error)[:350], "source": source.name + ":" + str(source.lineno),
                      "lastRequest": last_request,
                      "callStack": [{"function": f.name, "line": f.lineno} for f in frames]})
        print("FAIL", name, type(error).__name__, source.name, source.lineno, str(error)[:350], flush=True)
        try:
            requested_profile = (last_request or {}).get("profile")
            (OUT / ("failure-" + str(len(cases)) + "-status.json")).write_text(json.dumps(
                {"status": api("/v1/status", profile=requested_profile),
                 "diagnostics": api("/v1/diagnostics"),
                 "scripts": api("/v1/userscripts"), "extensions": api("/v1/extensions")}, indent=2))
            if (cases[-1].get("lastRequest") or {}).get("action") == "devtools":
                (OUT / ("failure-" + str(len(cases)) + "-devtools.json")).write_text(json.dumps(command("devtools"), indent=2))
            ui_image("failure-" + str(len(cases)))
        except Exception:
            pass
        # A failed editor/store case must not leave a modal window covering the
        # viewport and contaminate an independent native-input test.
        try:
            for prompt in api('/v1/prompts')['prompts']:
                api('/v1/prompts/resolve',{'profile':prompt['profile'],'tab':prompt['tab'],'id':prompt['id'],'scopeToken':prompt['scopeToken'],'decision':'dismiss'},expected_status=(200,404))
            for attempt in range(6):
                if any(n.get("content-desc") == "選單" for n in ui_tree().iter("node")): break
                adb("shell", "input", "keyevent", "4")
        except Exception:
            pass
    (OUT / "results.json").write_text(json.dumps(cases, ensure_ascii=False, indent=2))


def ui_tree():
    for attempt in range(2):
        adb("shell", "uiautomator", "dump", "/sdcard/abwr-ui.xml")
        raw = adb("shell", "cat", "/sdcard/abwr-ui.xml")
        root = ET.fromstring(raw)
        # API 35 emulator launcher startup can ANR behind the foreground browser.
        # Dismiss only this named emulator infrastructure failure, never an Abwr ANR.
        launcher_anr = any(n.get("text", "").startswith("Pixel Launcher") and
                           "responding" in n.get("text", "") for n in root.iter("node"))
        if not launcher_anr:
            return root
        (OUT / "emulator-launcher-anr.xml").write_bytes(raw)
        ui_image("emulator-launcher-anr")
        close = next(n for n in root.iter("node") if n.get("text") == "Close app")
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", close.get("bounds", "")))
        adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))
        cases.append({"case": "emulator infrastructure", "result": "INFO",
                      "detail": "Closed Pixel Launcher ANR dialog; Abwr failures are not dismissed"})
        time.sleep(.5)
    raise AssertionError("Emulator launcher ANR still blocks UI")


def visible_node(node):
    values = re.findall(r"\d+", node.get("bounds", ""))
    return len(values) == 4 and int(values[2]) > int(values[0]) and int(values[3]) > int(values[1])


def tap_node(predicate):
    node = until(lambda: next((n for n in ui_tree().iter("node") if predicate(n) and visible_node(n)), None), 15)
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds", "")))
    adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))


def ui_image(name):
    time.sleep(.4)
    (OUT / (name + ".png")).write_bytes(adb("exec-out", "screencap", "-p"))


def browser_menu(title):
    tap_node(lambda n: n.get("content-desc") == "選單")
    for attempt in range(12):
        tree = ui_tree()
        if any(n.get("text") == title and visible_node(n) for n in tree.iter("node")):
            tap_node(lambda n: n.get("text") == title)
            return
        scroll = next(n for n in tree.iter("node") if n.get("class") == "android.widget.ScrollView" and visible_node(n))
        left, top, right, bottom = map(int, re.findall(r"\d+", scroll.get("bounds", "")))
        step = min(300, (bottom-top)//3)
        adb("shell", "input", "swipe", str((left+right)//2), str(bottom-30), str((left+right)//2), str(bottom-30-step), "300")
    raise AssertionError("Menu item not reachable: " + title)


def capture_color(name, rgb, profile=None):
    # Explicit repeated READ captures permit compositor settling; never repeat page writes.
    def capture():
        raw = api("/v1/screenshot", raw=True, profile=profile)
        image = Image.open(io.BytesIO(raw)).convert("RGB")
        meta = api("/v1/screenshot/meta", profile=profile)
        width, height = image.size
        assert width >= 300 and height >= 500, (width, height)
        pixel = image.getpixel((width//2, height*3//4))
        assert max(abs(pixel[i]-rgb[i]) for i in range(3)) < 24, pixel
        (OUT / (name + ".jpg")).write_bytes(raw)
        (OUT / (name + "-meta.json")).write_text(json.dumps(meta, indent=2))
        return meta
    return until(capture, 25)


def main():
    global token
    assert os.environ.get("ABWR_CI_EMULATOR") == "1", "CI emulator only"
    assert SUITE in {"all", "core", "browser", "automation"}, "Invalid CI suite"
    child = make_server()
    top = make_server(child_port=child.server_port)
    for server in (child, top):
        threading.Thread(target=server.serve_forever, daemon=True).start()
        port = str(server.server_port)
        adb("reverse", "tcp:" + port, "tcp:" + port)
    base = "http://127.0.0.1:%d" % top.server_port
    fixture = base + "/"
    adb("shell", "cmd", "uimode", "night", "yes")
    adb("shell", "pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS", check=False)
    adb("shell", "am", "start", "-n", PACKAGE + "/dev.local.aibrowser.MainActivity")
    adb("forward", "tcp:8765", "tcp:8765")

    def read_token():
        raw = app_data("cat", "shared_prefs/local_api.xml", check=False)
        try:
            root = ET.fromstring(raw)
            return next((n.text for n in root if n.get("name") == "token"), None)
        except ET.ParseError:
            return None
    token = until(read_token, 45)
    until(lambda: api("/v1/status").get("ready"), 60)
    initial_pid = adb("shell", "pidof", PACKAGE).decode().strip()
    (OUT / "emulator-memory.txt").write_bytes(adb("shell", "cat", "/proc/meminfo"))
    installed = api("/v1/diagnostics")["installedApplication"]
    assert installed["applicationId"] == PACKAGE and installed["versionName"] == "0.14.0" and installed["versionCode"] == 23
    if PACKAGE == "dev.local.aibrowser":
        assert Path("signing/certificate-sha256.txt").read_text().strip() in installed["certificateSha256"]
    (OUT / "installed-application.json").write_text(json.dumps(installed, indent=2))

    def identity_and_snapshot():
        s = navigate(fixture)
        assert s["contentBridgeVersion"] == "0.14.0" and s["bridgeSchemaVersion"] == 4
        snapshot = command("snapshot")
        assert snapshot["url"] == s["url"]
        assert snapshot["documentId"] == s["documentId"]
        assert any(e["selectorHint"] == "#late" for e in snapshot["elements"])
        assert any(e.get("accessibleName") == "Name" for e in snapshot["elements"])
        assert any(e["selectorHint"] == "#shadow-button" for e in snapshot["elements"])
        assert not any(e["selectorHint"] == "#hidden" for e in snapshot["elements"])
        assert "Navigation 179" not in snapshot["snapshot"]
        fragment = navigate(fixture + "#next")
        assert fragment["documentId"] == s["documentId"]
        navigate(fixture)
        redirected = api("/v1/navigate", {"url": base + "/redirect", "waitForLoad": True, "timeoutMs": 12000})
        assert redirected["url"] == fixture and redirected["documentId"] != s["documentId"]
        assert redirected["loadSignal"] == "fresh_document_ready_state_complete"
        assert command("snapshot")["documentId"] == redirected["documentId"]
        unicode_url = base + "/中文?name=測試"
        unicode_document = api("/v1/navigate", {"url": unicode_url, "waitForLoad": True, "timeoutMs": 12000})
        assert unicode_document["url"] == quote(unicode_url, safe=":/?=&")
        assert command("snapshot")["documentId"] == unicode_document["documentId"]
        old = s["documentId"]
        navigate(fixture + "?new=1")
        stale = api("/v1/command", {"action": "read", "documentId": old}, expected_status=409)
        assert stale["code"] == "PAGE_CHANGED"
        navigate(fixture)
        top.slow_started.clear(); top.slow_release.clear()
        try:
            api("/v1/navigate", {"url": base + "/slow", "waitForLoad": False})
            until(lambda: top.slow_started.is_set() and api("/v1/status").get("loading"))
            tap_node(lambda n: n.get("content-desc") == "停止載入")
            stopped = until(lambda: (s if not (s := api("/v1/status")).get("loading") and s.get("pendingUrl") is None else None))
            assert stopped["url"] == fixture, stopped["url"]
            until(lambda: "ABWR · CONTROLLED FIXTURE" in command("read")["text"])
        finally:
            # UI automation may wait for accessibility idle. The response cannot
            # commit before the actual Stop tap and document checks complete.
            top.slow_release.set()
    run("document identity, priority snapshot and shadow roots", identity_and_snapshot)

    def frames():
        navigate(fixture)
        result = until(lambda: (r if len((r := command("snapshot"))["frames"]) else None))
        frame = next(f for f in result["frames"] if "/child" in f["url"])
        command("click", frame=frame["frame"], expectedFrameUrl=frame["url"], selector="#frame-button")
        assert "clicked child" in command("read", frame=frame["frame"], expectedFrameUrl=frame["url"])["text"]
    run("cross-origin iframe read and click", frames)

    def arguments():
        for args in ({"action": "click"}, {"action": "click", "selector": 7},
                     {"action": "read", "unexpected": True}):
            result = api("/v1/command", args, expected_status=400)
            assert result["code"] == "BAD_ARGUMENTS"
        for selector in ("#disabled", "#hidden", "#custom-disabled", "#unlabelled-hidden"):
            result = api("/v1/command", {"action": "toggle", "selector": selector}, expected_status=409)
            assert not result["ok"] and result["code"] == "TARGET_NOT_INTERACTABLE"
        result = api("/v1/command", {"action": "wait", "selector": "#missing", "timeoutMs": 100}, expected_status=504)
        assert not result["ok"] and result["code"] == "WAIT_TIMEOUT"
    run("HTTP validation and non-interactable targets", arguments)

    def custom_controls_and_scroll():
        snapshot = command("snapshot", role="checkbox")
        custom = next(e for e in snapshot["elements"] if e["selectorHint"] == "#custom-check")
        assert custom["visible"] is False and custom["interactable"] is True
        assert custom["interactionProxy"]["reason"] == "associated_visible_label"
        result = command("toggle", target=custom["ref"], checked=True)
        assert result["checked"] is True and result["viaLabel"] is True
        assert command("toggle", target=custom["ref"], checked=True)["unchanged"] is True
        assert command("find", selector="#custom-check")["elements"][0]["checked"] is True
        command("click", target=custom["ref"])
        assert command("find", selector="#custom-check")["elements"][0]["checked"] is False
        command("scrollTo", position="top")
        assert command("scroll", direction="down", pixels=280)["y"] > 0
        assert command("scroll", direction="up", pixels=280)["y"] == 0
        for params in ({"y": 1, "direction": "down"}, {"direction": "left"}, {"pixels": -1}):
            assert api("/v1/command", {"action": "scroll", **params}, expected_status=400)["code"] == "BAD_ARGUMENTS"
    run("custom checkbox visible label proxy and legacy scroll", custom_controls_and_scroll)

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
        for index in range(100):
            current_pid = adb("shell", "pidof", PACKAGE, check=False).decode().strip()
            assert current_pid == initial_pid, "Abwr process restarted during cycle " + str(index)
            s = navigate(fixture + "?cycle=" + str(index))
            snapshot = command("snapshot", expectedUrl=s["url"], documentId=s["documentId"])
            target = next(e["ref"] for e in snapshot["elements"] if e["selectorHint"] == "#name")
            command("type", target=target, text="cycle-" + str(index), documentId=s["documentId"])
            command("click", selector="#late", documentId=s["documentId"])
            assert "clicked late" in command("read", documentId=s["documentId"])["text"]
            (OUT / "cycle-progress.json").write_text(json.dumps({"completed": index+1, "processId": current_pid}))
        cases.append({"case": "cycle count", "completed": 100})
    run("100 sequential navigation and operation cycles", cycles)

    def mcp_live():
        navigate(fixture)
        env = dict(os.environ, AI_BROWSER_HOST="http://127.0.0.1:8765", AI_BROWSER_TOKEN=token)
        process = subprocess.Popen([sys.executable, "bridge/mcp_stdio.py"], env=env,
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
        request_id = 0
        def rpc(method, params):
            nonlocal request_id
            request_id += 1
            process.stdin.write(json.dumps({"jsonrpc": "2.0", "id": request_id,
                                           "method": method, "params": params}) + "\n")
            process.stdin.flush()
            assert select.select([process.stdout], [], [], 25)[0], "MCP reply timed out"
            reply = json.loads(process.stdout.readline())
            assert reply.get("id") == request_id and "error" not in reply, "Invalid MCP reply"
            return reply["result"]
        def call(tool_name, **arguments):
            response = rpc("tools/call", {"name": tool_name, "arguments": arguments})
            assert not response.get("isError"), (tool_name, response.get("content"))
            image=next((item for item in response["content"] if item["type"]=="image"),None)
            if image is not None:
                meta=next((item for item in response["content"] if item["type"]=="text"),None)
                if meta:(OUT/"mcp-screenshot-evidence.json").write_text(meta["text"])
                return image
            return json.loads(response["content"][0]["text"])
        try:
            initialized = rpc("initialize", {"protocolVersion": "2025-03-26",
                "capabilities": {}, "clientInfo": {"name": "abwr-ci", "version": "1"}})
            assert initialized["serverInfo"]["version"] == "0.14.0"
            tools = rpc("tools/list", {})["tools"]
            assert len(tools) == 82
            assert call("browser_capabilities")["apiVersion"] == "0.14.0"
            assert {"browser_snapshot", "browser_frames", "browser_press", "browser_click_point"} <= {t["name"] for t in tools}
            status = call("browser_status")
            assert call("browser_wait",condition="load",timeoutMs=1000)["result"]["matched"]
            assert call("browser_wait",condition="delay",durationMs=50,timeoutMs=1000)["result"]["matched"]
            snapshot = call("browser_snapshot")["result"]
            assert snapshot["documentId"] == status["documentId"]
            guard = {"documentId": status["documentId"], "expectedUrl": status["url"]}
            ref = next(e["ref"] for e in snapshot["elements"] if e["selectorHint"] == "#name")
            call("browser_type", target=ref, text="MCP native input", **guard)
            call("browser_press", selector="#name", key="Ctrl+A", **guard)
            call("browser_press", key="Backspace", **guard)
            until(lambda: call("browser_find", selector="#name")["result"]["elements"][0]["value"] == "")
            call("browser_click", selector="#shadow-button", **guard)
            assert "clicked shadow" in call("browser_read")["result"]["text"]
            frame = next(f for f in call("browser_frames")["result"]["frames"] if "/child" in f["url"])
            assert call("browser_wait",condition="frame_ready",frame=frame["frame"],expectedFrameUrl=frame["url"],timeoutMs=1000)["result"]["matched"]
            call("browser_click", selector="#frame-button", frame=frame["frame"], expectedFrameUrl=frame["url"], **guard)
            assert "clicked child" in call("browser_read", frame=frame["frame"], expectedFrameUrl=frame["url"])["result"]["text"]
            call("browser_scroll_to", selector="#late")
            shot = call("browser_screenshot")
            image = Image.open(io.BytesIO(base64.b64decode(shot["data"])))
            target = call("browser_find", selector="#late")["result"]
            rect, viewport = target["elements"][0]["rect"], target["viewport"]
            x = round((rect["x"]+rect["width"]/2) * image.width/viewport["width"])
            y = round((rect["y"]+rect["height"]/2) * image.height/viewport["height"])
            call("browser_click_point", x=x, y=y, imageWidth=image.width, imageHeight=image.height, **guard)
            until(lambda: "clicked late" in call("browser_read")["result"]["text"])
            failure = rpc("tools/call", {"name": "browser_click", "arguments": {"selector": 123}})
            assert failure["isError"] is True
            group = call("browser_group_create", name="MCP group", color=2, tabs=[status["activeTab"]])
            group_id = group["createdGroupId"]
            call("browser_group_update", id=group_id, name="MCP renamed", color=0)
            assert any(g["name"] == "MCP renamed" for g in call("browser_tab_groups")["groups"])
            call("browser_group_move", tabs=[status["activeTab"]])
            call("browser_group_ungroup", id=group_id)
            assert not call("browser_tab_groups")["groups"]
            for index in range(100):
                assert adb("shell", "pidof", PACKAGE).decode().strip() == initial_pid
                call("browser_navigate", url=fixture + "?mcp-cycle=" + str(index))
                snapshot = call("browser_snapshot")["result"]
                ref = next(e["ref"] for e in snapshot["elements"] if e["selectorHint"] == "#name")
                guard = {"documentId": snapshot["documentId"], "expectedUrl": snapshot["url"]}
                call("browser_type", target=ref, text="MCP cycle " + str(index), **guard)
                call("browser_click", selector="#late", **guard)
                assert "clicked late" in call("browser_read", **guard)["result"]["text"]
                (OUT / "mcp-cycle-progress.json").write_text(json.dumps({"completed": index+1, "processId": initial_pid}))
            (OUT / "mcp-live.json").write_text(json.dumps({"protocol": initialized["protocolVersion"],
                "toolCount": len(tools), "bridgeVersion": initialized["serverInfo"]["version"],
                "cycles": 100, "realAndroidHttpBackend": True, "result": "PASS"}, indent=2))
        finally:
            process.stdin.close()
            process.terminate()
            process.wait(timeout=5)
    run("real MCP workflow, groups and 100 operation cycles", mcp_live)

    def manual_bridge_regression():
        for index in range(20):
            navigate(fixture + "?manual-cycle=" + str(index))
            before = api("/v1/status")
            api("/v1/takeover", {"manual": True})
            assert command("read", documentId=before["documentId"])["documentId"] == before["documentId"]
            assert command("snapshot", documentId=before["documentId"])["elements"]
            error = api("/v1/command", {"action": "click", "selector": "#late"}, expected_status=409)
            assert error["code"] == "MANUAL_TAKEOVER"
            api("/v1/takeover", {"manual": False})
            command("type", selector="#name", text="manual cycle " + str(index))
            assert api("/v1/status")["documentId"] == before["documentId"]
        api("/v1/profiles/create", {"id": "CI_DELETE_ONLY"})
        assert api("/v1/profiles/CI_DELETE_ONLY", method="DELETE")["removed"] == "CI_DELETE_ONLY"
    run("20 manual takeover read/resume cycles and REST DELETE", manual_bridge_regression)

    def tabs_profiles_ui():
        def close_idle(tab):
            # BUSY is rejected before mutation, including a real thumbnail
            # capture still using the tab. Retry only this explicit rejection.
            def close():
                result = api("/v1/tabs/close", {"id": tab}, expected_status=(200, 503))
                if result.get("code") == "BUSY":
                    assert any(t["id"] == tab for t in api("/v1/tabs")["tabs"])
                    return None
                return result
            return until(close, 15)
        for index in range(6):
            startup_url = fixture + "?new-tab=" + str(index)
            fresh_tab = api("/v1/tabs/new", {"url": startup_url})["id"]
            current = navigate(startup_url)
            time.sleep(.4)
            assert api("/v1/status")["documentId"] == current["documentId"], "Initial tab load replaced the completed API document"
            assert command("snapshot", documentId=current["documentId"])["documentId"] == current["documentId"]
            close_idle(fresh_tab)
        first = api("/v1/tabs/new", {"url": fixture})["id"]
        second = api("/v1/tabs/new", {"url": fixture})["id"]
        listing = api("/v1/tabs")
        assert len(listing["tabs"]) == api("/v1/status")["tabCount"] == 3
        api("/v1/tabs/close", {"id": "main"})
        assert len(api("/v1/tabs")["tabs"]) == 2
        api("/v1/tabs/select", {"id": first})
        navigate(fixture)
        tree = ui_tree()
        (OUT / "ui-browser.xml").write_bytes(ET.tostring(tree, encoding="utf-8"))
        address = next(n for n in tree.iter("node") if n.get("content-desc", "").startswith("搜尋或輸入網址"))
        assert address.get("text", "").startswith("127.0.0.1"), address.get("text")
        ui_image("ui-browser")
        tap_node(lambda n: "開啟分頁面板" in n.get("content-desc", ""))
        ui_image("ui-tabs")
        (OUT / "ui-tabs.xml").write_bytes(ET.tostring(ui_tree(), encoding="utf-8"))
        adb("shell", "input", "keyevent", "4")
        tap_node(lambda n: n.get("content-desc") == "選單")
        ui_image("ui-menu")
        (OUT / "ui-menu.xml").write_bytes(ET.tostring(ui_tree(), encoding="utf-8"))
        adb("shell", "input", "keyevent", "4")
        tap_node(lambda n: "目前容器" in n.get("content-desc", ""))
        ui_image("ui-profiles")
        (OUT / "ui-profiles.xml").write_bytes(ET.tostring(ui_tree(), encoding="utf-8"))
        adb("shell", "input", "keyevent", "4")
        api("/v1/profiles/create", {"id": "CI001"})
        api("/v1/profiles/switch", {"id": "CI001"})
        assert api("/v1/status")["activeProfile"] == "CI001"
        api("/v1/profiles/switch", {"id": "G001"})
        api("/v1/profiles/delete", {"id": "CI001"})
        api("/v1/tabs/close", {"id": second})
    run("tab counts, closing main, profile HTTP routes and UI evidence", tabs_profiles_ui)

    def group_ui_and_persistence():
        ids = []
        for index in range(1, 8):
            url = base + "/color?c=" + ("green" if index % 2 else "blue") + "&label=CI%20tab%20" + str(index)
            ids.append(api("/v1/tabs/new", {"url": url})["id"])
            navigate(url)
            capture_color("tab-preview-" + str(index), (20,184,122) if index % 2 else (50,105,221))
        tap_node(lambda n: "開啟分頁面板" in n.get("content-desc", ""))
        tap_node(lambda n: n.get("content-desc") == "更多分頁操作")
        tap_node(lambda n: n.get("text") == "選取分頁")
        # Select through visible controls, scrolling the real overview as necessary.
        for index in range(1, 6):
            wanted = "選取分頁 CI tab " + str(index)
            for attempt in range(6):
                tree = ui_tree()
                node = next((n for n in tree.iter("node") if n.get("content-desc") == wanted), None)
                if node is not None:
                    tap_node(lambda n: n.get("content-desc") == wanted)
                    break
                adb("shell", "input", "swipe", "800", "1500", "800", "700", "350")
            else:
                raise AssertionError("Tab selection was not reachable: " + wanted)
        tap_node(lambda n: n.get("text", "").startswith("加入群組（"))
        tap_node(lambda n: n.get("text") == "建立新群組")
        tap_node(lambda n: n.get("content-desc") == "群組名稱")
        adb("shell", "input", "text", "CIWORK")
        tap_node(lambda n: n.get("text") == "儲存")
        groups = until(lambda: (g if (g := api("/v1/tab-groups"))["groups"] else None))["groups"]
        group = next(g for g in groups if g["name"] == "CIWORK")
        assert set(group["tabs"]) == set(ids[:5]) and group["color"] == 0
        # Scroll back to the group card after selection moved down the grid.
        adb("shell", "input", "swipe", "800", "700", "800", "1700", "350")
        ui_image("ui-tabs-groups-dark")
        (OUT / "ui-tabs-groups-dark.xml").write_bytes(ET.tostring(ui_tree(), encoding="utf-8"))
        tap_node(lambda n: n.get("content-desc", "").startswith("開啟群組 CIWORK，"))
        ui_image("ui-group-inside-dark")
        tap_node(lambda n: n.get("content-desc") == "在群組新增分頁")
        listing = api("/v1/tabs")
        new_tab = listing["tabs"][-1]["id"]
        assert new_tab in next(g for g in listing["groups"] if g["id"] == group["id"])["tabs"]
        navigate(base + "/color?c=blue&label=Group%20new%20tab")
        capture_color("group-new-tab", (50,105,221))
        tap_node(lambda n: "開啟分頁面板" in n.get("content-desc", ""))
        tap_node(lambda n: n.get("content-desc") == "群組選單 CIWORK")
        tap_node(lambda n: n.get("text") == "重新命名與顏色")
        tap_node(lambda n: n.get("content-desc") == "群組名稱")
        adb("shell", "input", "keycombination", "113", "29")
        adb("shell", "input", "keyevent", "67")
        adb("shell", "input", "text", "CIWORK2")
        node = next(n for n in ui_tree().iter("node") if n.get("content-desc") == "群組名稱")
        assert node.get("text") == "CIWORK2", "Group editor input did not reach the name field"
        adb("shell", "input", "keyevent", "4")
        tap_node(lambda n: n.get("content-desc") == "群組顏色 藍色")
        tap_node(lambda n: n.get("text") == "儲存")
        saved = api("/v1/tab-groups")["groups"]
        updated = next(g for g in saved if g["id"] == group["id"])
        assert updated["name"] == "CIWORK2" and updated["color"] == 1
        assert set(updated["tabs"]) == set(ids[:5] + [new_tab])
        ui_image("ui-tabs-renamed-dark")
        adb("shell", "input", "keyevent", "4")
        adb("shell", "cmd", "uimode", "night", "no")
        until(lambda: api("/v1/status")["viewAttached"])
        tap_node(lambda n: "開啟分頁面板" in n.get("content-desc", ""))
        ui_image("ui-tabs-groups-light")
        adb("shell", "input", "keyevent", "4")
        adb("shell", "cmd", "uimode", "night", "yes")
        before = api("/v1/tabs")
        api("/v1/takeover", {"manual": True})
        # This is a fresh .dev CI emulator. Never force-stop the user's product app.
        adb("shell", "am", "force-stop", PACKAGE)
        adb("shell", "am", "start", "-n", PACKAGE + "/dev.local.aibrowser.MainActivity")
        until(lambda: api("/v1/status")["ready"], 60)
        assert read_token() == token, "Restart changed the local API credential"
        after = api("/v1/tabs")
        until(lambda: api("/v1/status")["extensionPortReady"])
        after = api("/v1/tabs")
        assert after["groups"] == saved
        assert [(t["id"],t["url"],t["title"]) for t in before["tabs"]] == [(t["id"],t["url"],t["title"]) for t in after["tabs"]]
        assert api("/v1/status")["manualMode"] is True
        assert "blue lower viewport" in command("read")["text"]
        assert api("/v1/tab-groups/ungroup", {"id": group["id"]}, expected_status=409)["code"] == "MANUAL_TAKEOVER"
        api("/v1/takeover", {"manual": False})
        tap_node(lambda n: "開啟分頁面板" in n.get("content-desc", ""))
        ui_image("ui-tabs-restored-dark")
        adb("shell", "input", "keyevent", "4")
    run("UI select/create/rename/color/add tab, theme and persistent groups", group_ui_and_persistence)

    def foreground_background_pixels():
        navigate(base + "/color?c=green")
        capture_color("foreground-green", (20, 184, 122))
        adb("shell", "input", "keyevent", "3")
        until(lambda: api("/v1/status").get("experimentalBackgroundSurfaceAttached"), 30)
        time.sleep(12)  # Regression: zero ImageReader swaps must not reject valid captures after 7s.
        navigate(base + "/color?c=blue")
        capture_color("background-blue", (50, 105, 221))
        assert api("/v1/status")["liveBackgroundScreenshotSupported"] is True
        api("/v1/profiles/create", {"id": "CI_CAPTURE"})
        inactive_url = base + "/color?c=green&inactive=1"
        navigate(inactive_url, profile="CI_CAPTURE")
        capture_color("inactive-profile-green", (20,184,122), profile="CI_CAPTURE")
        assert api("/v1/status")["activeProfile"] == "G001", "Screenshot switched the visible container"
        capture_color("background-blue-after-inactive", (50,105,221))
        api("/v1/profiles/CI_CAPTURE", method="DELETE")
        (OUT / "background-diagnostics.json").write_text(json.dumps(api("/v1/diagnostics"), indent=2))
        adb("shell", "am", "start", "-n", PACKAGE + "/dev.local.aibrowser.MainActivity")
    run("full foreground viewport and fresh background screenshot after 12s", foreground_background_pixels)

    def http_client_and_streams():
        navigate(base + "/http/set-cookie?value=one")
        request = {"url":base + "/http/echo","method":"POST","headers":{"Content-Type":"application/json","X-Abwr-Test":"ci"},"body":"{\"message\":\"hi\"}"}
        reply = api("/v1/http/request", request)["result"]
        echoed = json.loads(reply["body"])
        assert reply["transport"] == "page" and reply["cookieScope"] == "selected_profile_document"
        assert echoed["method"] == "POST" and echoed["body"] == request["body"] and echoed["custom"] == "ci"
        assert "ABWR_CI_HTTP=one" in echoed["cookie"] and not echoed["authorization"], "Control bearer leaked to origin"
        api("/v1/profiles/create", {"id":"CI_HTTP"})
        navigate(base + "/http/set-cookie?value=two", profile="CI_HTTP")
        other = api("/v1/http/request", {"url":base+"/http/echo", "profile":"CI_HTTP"})["result"]
        assert "ABWR_CI_HTTP=two" in json.loads(other["body"])["cookie"]
        first = api("/v1/http/request", {"url":base+"/http/echo", "profile":"G001"})["result"]
        assert "ABWR_CI_HTTP=one" in json.loads(first["body"])["cookie"]
        native = api("/v1/http/request", {"url":base+"/http/echo", "transport":"native"})["result"]
        assert native["cookieScope"] == "none" and not json.loads(native["body"])["cookie"]
        raw = api("/v1/http/request", {"url":base+"/http/binary", "transport":"native", "responseEncoding":"base64"})["result"]
        assert base64.b64decode(raw["body"]) == bytes(range(256))
        quota = api("/v1/http/request", {"url":base+"/http/quota", "transport":"native"})["result"]
        assert quota["status"] == 403 and json.loads(quota["body"])["quota"] == "exhausted"
        api("/v1/takeover", {"manual":True})
        assert api("/v1/http/request", request, expected_status=409)["code"] == "MANUAL_TAKEOVER"
        api("/v1/takeover", {"manual":False})
        for transport in ("native", "page"):
            ack = api("/v1/http/request", {"url":base+"/http/stream", "stream":True,"transport":transport})["result"]
            identifier = ack["streamId"]; cursor=0; chunks=[]; observed_live=False
            end=time.monotonic()+12
            while time.monotonic()<end:
                value=api("/v1/http/streams/"+identifier+"/poll", {"afterSeq":cursor})
                for event in value["events"]:
                    if event["type"]=="chunk":chunks.append(base64.b64decode(event["data"])); observed_live |= not value["done"]
                    assert event["type"] != "error", event
                cursor=value["nextSeq"]
                if value["done"] and not value["events"]:break
                time.sleep(.12)
            assert observed_live and b"data: first" in b"".join(chunks) and b"data: done" in b"".join(chunks)
        api("/v1/profiles/CI_HTTP",method="DELETE")
    run("HTTP POST, profile cookies, anonymous native, binary, quota and real streams", http_client_and_streams)

    def isolation_and_first_pixels():
        original = [(t["id"],t["url"]) for t in api("/v1/tabs")["tabs"]]
        api("/v1/profiles/create", {"id":"CI_SCOPE"})
        navigate(base+"/http/set-cookie?value=retired",profile="CI_SCOPE")
        extra=api("/v1/tabs/new", {"url":base+"/", "profile":"CI_SCOPE"})["id"]
        api("/v1/tabs/select", {"id":"main"},profile="CI_SCOPE")
        api("/v1/tabs/close", {"id":"main"},profile="CI_SCOPE")
        assert [(t["id"],t["url"]) for t in api("/v1/tabs")["tabs"]] == original, "Header-scoped close changed G001"
        assert any(t["id"]==extra for t in api("/v1/tabs",profile="CI_SCOPE")["tabs"])
        assert api("/v1/tabs/close", {"id":"main","profile":"G001"},profile="CI_SCOPE",expected_status=400)["code"] == "BAD_ARGUMENTS"
        api("/v1/profiles/CI_SCOPE",method="DELETE")
        api("/v1/profiles/create", {"id":"CI_SCOPE"})
        navigate(base+"/",profile="CI_SCOPE")
        fresh=api("/v1/http/request", {"url":base+"/http/echo","profile":"CI_SCOPE"})["result"]
        assert "retired" not in json.loads(fresh["body"])["cookie"]
        navigate(base+"/color?c=blue",profile="CI_SCOPE")
        navigate(base+"/color?c=green",profile="G001")
        def first(name,rgb):
            raw=api("/v1/screenshot",raw=True)
            image=Image.open(io.BytesIO(raw)).convert("RGB");w,h=image.size
            pixel=image.getpixel((w//2,h*3//4));assert max(abs(pixel[i]-rgb[i]) for i in range(3))<24,(name,pixel)
            (OUT/(name+".jpg")).write_bytes(raw)
        for index in range(4):
            for profile,rgb in (("CI_SCOPE",(50,105,221)),("G001",(20,184,122))):
                api("/v1/profiles/switch", {"id":profile});first("first-profile-"+str(index)+"-"+profile,rgb)
        api("/v1/profiles/switch", {"id":"G001"})
        green=api("/v1/tabs/new", {"url":base+"/color?c=green"})["id"];navigate(base+"/color?c=green")
        blue=api("/v1/tabs/new", {"url":base+"/color?c=blue"})["id"];navigate(base+"/color?c=blue")
        for index in range(3):
            for tab,rgb in ((green,(20,184,122)),(blue,(50,105,221))):
                api("/v1/tabs/select", {"id":tab});first("first-tab-"+str(index)+"-"+tab,rgb)
        api("/v1/tabs/close", {"id":blue});api("/v1/tabs/close", {"id":green})
        api("/v1/profiles/CI_SCOPE",method="DELETE")
    run("header scope, fresh recreated cookie context and first-shot pixel isolation", isolation_and_first_pixels)

    def workbench_ui_and_runtime():
        navigate(fixture)
        menu = browser_menu
        def fill(description,value):
            # Dismiss IME before scrolling. Screen coordinates inside an open
            # keyboard must never be mistaken for editor scroll gestures.
            # The previous window's requested IME can outlive its visibility.
            # Do not press Back on a fresh editor or it may dismiss the dialog.
            for attempt in range(8):
                tree = ui_tree()
                if any(n.get("content-desc")==description and visible_node(n) for n in tree.iter("node")):break
                scroll = next(n for n in tree.iter("node") if n.get("class") == "android.widget.ScrollView" and visible_node(n))
                left, top, right, bottom = map(int, re.findall(r"\d+", scroll.get("bounds", "")))
                adb("shell", "input", "swipe", str((left+right)//2), str(bottom-20), str((left+right)//2), str(top+20), "300")
            else:
                raise AssertionError("Editor field not reachable: " + description)
            tap_node(lambda n:n.get("content-desc")==description)
            adb("shell","input","keycombination","113","29")
            adb("shell","input text "+shlex.quote(value.replace(" ","%s")))
            node = next(n for n in ui_tree().iter("node") if n.get("content-desc") == description)
            assert node.get("text") == value, "Editor text was entered into the wrong field: " + description
        for title,name in (("書籤","ui-bookmarks"),("歷史紀錄","ui-history"),("下載","ui-downloads"),("設定","ui-settings")):
            menu(title);ui_image(name);adb("shell","input","keyevent","4")
        menu("使用者腳本");ui_image("ui-scripts-empty")
        tap_node(lambda n:n.get("content-desc")=="新增腳本")
        fill("腳本名稱","CI_SCRIPT")
        fill("https://*.example.com/*","http://127.0.0.1/*")
        fill("腳本內容","document.body.append('ABWR_CI_SCRIPT_OK');")
        adb("shell","input","keyevent","4")
        ui_image("ui-script-editor")
        tap_node(lambda n:n.get("text")=="儲存腳本")
        until(lambda:api("/v1/userscripts")["runtime"].get("active")==1,20)
        ui_image("ui-scripts-installed");adb("shell","input","keyevent","4")
        navigate(fixture)
        # The marker is deliberately appended outside <main>; article read()
        # omits it. Verify the whole document rather than the reader extraction.
        until(lambda:"ABWR_CI_SCRIPT_OK" in command("dom")["text"])
    run("native workbench UI and real sandbox script", workbench_ui_and_runtime)

    def console_network_runtime():
        navigate(fixture)
        menu = browser_menu
        try:
            command("devtools",record=True,captureBodies=True)
            command("click",selector="#http-check")
            def captured():
                tools=command("devtools")
                (OUT / "workbench-inspector.json").write_text(json.dumps(tools, indent=2))
                rows=tools.get("network",{}).get("requests",[])
                return next((r for r in rows if r["url"].endswith("/http/echo") and r.get("responseBody")),None)
            row=until(captured,20)
            assert "abwr_ci" in row["requestBody"]["text"] and "ci-nonce" in row["requestBody"]["text"]
            assert "redact-me" not in json.dumps(row),"Body inspector exposed a password fixture"
            tools=command("devtools")
            assert any("ABWR_CI_CONSOLE" in e.get("message","") for e in tools.get("console",{}).get("events",[]))
            menu("DevTools");ui_image("ui-devtools-console")
            tap_node(lambda n:n.get("text")=="Network");ui_image("ui-devtools-network")
            tap_node(lambda n:n.get("text")=="DOM");ui_image("ui-devtools-dom")
            adb("shell","input","keyevent","4")
        finally:
            command("devtools",record=False)
    run("opt-in console and current-tab request/response body recording", console_network_runtime)

    def extension_store_ui():
        navigate(fixture)
        menu = browser_menu
        menu("擴充功能");ui_image("ui-extensions-empty")
        tap_node(lambda n:n.get("text")=="探索商店")
        tap_node(lambda n:n.get("text")=="Tampermonkey")
        until(lambda:any(n.get("text")=="安裝" for n in ui_tree().iter("node")),30)
        ui_image("ui-extension-store")
        tap_node(lambda n:n.get("text")=="安裝")
        until(lambda:any("安裝 Tampermonkey" in n.get("text","") for n in ui_tree().iter("node")),30)
        ui_image("ui-extension-permissions")
        tap_node(lambda n:n.get("text")=="允許")
        until(lambda:any("tampermonkey" in e.get("id","").lower() for e in api("/v1/extensions")["extensions"]),45)
        # Tampermonkey opens a delayed first-install welcome tab. Observe and
        # dismiss only that named extension window before using the manager.
        welcome_end = time.monotonic() + 8
        while time.monotonic() < welcome_end:
            tree = ui_tree()
            if any(n.get("text") == "Tampermonkey" and n.get("class") == "android.widget.TextView" for n in tree.iter("node")) and not any(n.get("text") == "開啟" for n in tree.iter("node")):
                ui_image("ui-extension-welcome")
                adb("shell", "input", "keyevent", "4")
                break
            time.sleep(.3)
        ui_image("ui-extensions-installed")
        (OUT/"extension-before-action.json").write_text(json.dumps(api("/v1/extensions"),indent=2))
        tap_node(lambda n:n.get("text")=="開啟")
        (OUT/"extension-after-action.json").write_text(json.dumps(api("/v1/extensions"),indent=2))
        until(lambda:any("Dashboard" in n.get("text", "") or "No script is running" in n.get("text", "") for n in ui_tree().iter("node")),20)
        ui_image("ui-extension-popup")
        adb("shell","input","keyevent","4")
        tap_node(lambda n:n.get("content-desc")=="設定")
        until(lambda:any(n.get("text", "") == "Settings" or "Installed Userscripts" in n.get("text", "") for n in ui_tree().iter("node")),20)
        ui_image("ui-extension-options")
        adb("shell","input","keyevent","4")
        adb("shell","input","keyevent","4")
        (OUT/"workbench-runtime.json").write_text(json.dumps({"scripts":api("/v1/userscripts"),"extensions":api("/v1/extensions")},indent=2))
    run("Mozilla store signature install, popup and options", extension_store_ui)

    def everyday_browser():
        navigate(fixture)
        tab = api("/v1/tabs/new", {"url": fixture + "?closed-tab=1"})["id"]
        navigate(fixture + "?closed-tab=1")
        api("/v1/tabs/close", {"id": tab})
        assert api("/v1/status")["recentlyClosedTabCount"] > 0
        restored = api("/v1/tabs/reopen", {})
        assert restored["url"] == fixture + "?closed-tab=1"
        navigate(fixture)
        command("scrollTo", selector="#range")
        info = command("find", selector="#range")
        rect = info["elements"][0]["rect"]
        width = api("/v1/status")["screenshotViewport"]["width"]
        scale = width / info["viewport"]["width"]
        y = round((rect["y"] + rect["height"] / 2) * scale)
        start = round((rect["x"] + 16) * scale)
        end = round((rect["x"] + rect["width"] - 16) * scale)
        gesture = dict(fromX=start, fromY=y, toX=end, toY=y, durationMs=400)
        assert command("drag", **gesture)["native"] is True
        assert int(command("find", selector="#range")["elements"][0]["value"]) > 60
        api("/v1/takeover", {"manual": True})
        assert api("/v1/command", {"action": "drag", **gesture}, expected_status=409)["code"] == "MANUAL_TAKEOVER"
        api("/v1/takeover", {"manual": False})
        command("scrollTo", selector="#fullscreen")
        target = command("find", selector="#fullscreen")
        rect = target["elements"][0]["rect"]
        scale = api("/v1/status")["screenshotViewport"]["width"] / target["viewport"]["width"]
        command("click_point", x=round((rect["x"]+rect["width"]/2)*scale), y=round((rect["y"]+rect["height"]/2)*scale))
        until(lambda: not any(n.get("content-desc")=="選單" for n in ui_tree().iter("node")))
        # The fresh emulator's system immersive-mode tutorial intercepts Back.
        # Acknowledge it through its actual UI before testing the browser callback.
        until(lambda: command("find", selector="#fullscreen-state")["elements"][0]["text"] == "fullscreen")
        tutorial_deadline = time.monotonic() + 5
        while time.monotonic() < tutorial_deadline:
            if any(n.get("text")=="Got it" for n in ui_tree().iter("node")):
                tap_node(lambda n:n.get("text")=="Got it")
                until(lambda: not any(n.get("text")=="Got it" for n in ui_tree().iter("node")))
                break
        ui_image("ui-media-fullscreen")
        adb("shell", "input", "keyevent", "4")
        until(lambda: any(n.get("content-desc")=="選單" for n in ui_tree().iter("node")))
        until(lambda: command("find", selector="#fullscreen-state")["elements"][0]["text"] == "windowed")
        private = api("/v1/profiles/private", {})["profile"]
        assert api("/v1/status")["private"] is True
        navigate(base + "/http/set-cookie?value=private")
        echoed = api("/v1/http/request", {"url":base+"/http/echo"})["result"]
        assert "ABWR_CI_HTTP=private" in json.loads(echoed["body"])["cookie"]
        assert not api("/v1/history")["history"]
        prefs = app_data("cat", "shared_prefs/browser_profiles.xml").decode()
        assert private not in prefs and "value=private" not in prefs, "Private navigation persisted to disk"
        ui_image("ui-private-browser")
        api("/v1/profiles/switch", {"id":"G001"})
        api("/v1/profiles/"+private, method="DELETE")
        assert not any(p["id"] == private for p in api("/v1/profiles")["profiles"])
        navigate(fixture)
        tap_node(lambda n:n.get("content-desc")=="選單")
        for attempt in range(5):
            if any(n.get("text")=="網站資訊與權限" for n in ui_tree().iter("node")): break
            adb("shell","input","swipe","850","1600","850","600","300")
        tap_node(lambda n:n.get("text")=="網站資訊與權限")
        ui_image("ui-site-information")
        adb("shell","input","keyevent","4")
    run("private cookie scope and no disk history, tab reopen, native drag and media fullscreen", everyday_browser)

    def native_files_and_reading():
        navigate(base + "/http/set-cookie?value=download-auth")
        navigate(fixture)
        browser_menu("閱讀檢視")
        until(lambda: any("HTTP test request" in n.get("text", "") for n in ui_tree().iter("node")))
        ui_image("ui-reading-view")
        adb("shell", "input", "keyevent", "4")

        command("click", selector='a[href="/download-auth.bin"]')
        tap_node(lambda n: n.get("text") == "下載")
        def complete_download():
            rows = api("/v1/downloads")["downloads"]
            return next((r for r in rows if r["name"] == "abwr-auth-fixture.bin" and r["state"] == "complete"), None)
        item = until(complete_download, 40)
        assert item["bytes"] == 5 * 1024 * 1024
        actual_hash = app_data("sha256sum", "files/downloads/" + item["id"] + "-" + item["name"]).decode().split()[0]
        assert actual_hash == hashlib.sha256(b"a" * (5 * 1024 * 1024)).hexdigest()
        until(lambda: (s := api("/v1/status"))["url"] == fixture and s.get("extensionPortReady"))
        browser_menu("下載"); ui_image("ui-download-complete")
        adb("shell", "input", "keyevent", "4")

        def save_picker(name, valid):
            ui_image("ui-save-picker-" + name.rsplit(".", 1)[-1])
            tap_node(lambda n: n.get("text", "").lower() == "save" and n.get("enabled") == "true")
            until(lambda: api("/v1/status").get("viewAttached"), 20)
            def saved():
                raw = adb("exec-out", "cat", "/sdcard/Download/" + name, check=False)
                return raw if valid(raw) else None
            return until(saved, 20)

        browser_menu("另存網頁")
        tap_node(lambda n: n.get("text") == "PDF 文件")
        pdf = save_picker("Abwr-page.pdf", lambda raw: raw.startswith(b"%PDF-") and b"%%EOF" in raw[-2048:])
        assert pdf.startswith(b"%PDF-") and b"%%EOF" in pdf[-2048:]
        (OUT / "fixture-page.pdf").write_bytes(pdf)
        ui_image("ui-pdf-export-complete")

        api("/v1/bookmarks/add", {"url": fixture + "?bookmark-html=1"})
        browser_menu("設定")
        tap_node(lambda n: n.get("text") == "書籤匯入與匯出")
        tap_node(lambda n: n.get("text") == "匯出目前容器的 HTML 書籤")
        exported = save_picker("Abwr-bookmarks.html", lambda raw: b"NETSCAPE-Bookmark-file-1" in raw and b"</DL>" in raw)
        assert b"NETSCAPE-Bookmark-file-1" in exported and (fixture + "?bookmark-html=1").encode() in exported
        (OUT / "fixture-bookmarks.html").write_bytes(exported)
        adb("shell", "input", "keyevent", "4")
    run("native reading, authenticated 5 MiB download, PDF and HTML bookmark export", native_files_and_reading)

    assert not any(case.get("result") == "FAIL" for case in cases), "Fast acceptance failed; skipping the 30-minute idle test"

    from android_automation_cases import run_case, profile_scale_case, proxy_case
    run("timed input, conditional waits, network events and shared prompt API", lambda: run_case(api,command,navigate,until,fixture,OUT))
    run("15 isolated fixture profiles measured sequentially", lambda: profile_scale_case(api,navigate,OUT,fixture))
    run("isolated profile proxy routes, new tabs, HTTPS and no direct fallback", lambda: proxy_case(api,navigate,command,until,adb,OUT,fixture,top,child,token))

    assert not any(case.get("result") == "FAIL" for case in cases), "Automation acceptance failed; skipping the 30-minute idle test"

    def long_locked_background():
        navigate(base + "/color?c=green&checkpoint=0")
        previous = capture_color("long-background-start", (20,184,122))
        pid = adb("shell", "pidof", PACKAGE).decode().strip()
        adb("shell", "input", "keyevent", "3")
        until(lambda: not api("/v1/status")["viewAttached"])
        adb("shell", "svc", "power", "stayon", "false")
        adb("shell", "input", "keyevent", "223")
        until(lambda: api("/v1/diagnostics")["screenInteractive"] is False)
        start = time.monotonic()
        checkpoints = []
        for minute in (1, 10, 30):
            while time.monotonic() < start + minute*60:
                time.sleep(min(15, max(.1, start + minute*60 - time.monotonic())))
            assert adb("shell", "pidof", PACKAGE).decode().strip() == pid, "Background process restarted"
            green = minute == 10
            url = base + "/color?c=" + ("green" if green else "blue") + "&checkpoint=" + str(minute)
            current = navigate(url)
            assert current["viewAttached"] is False
            assert current["documentId"] != previous["documentId"]
            shot = capture_color("background-" + str(minute) + "min-locked", (20,184,122) if green else (50,105,221))
            assert shot["documentId"] == current["documentId"] and shot["url"] == url
            assert shot["capturedAtMs"] > previous["capturedAtMs"]
            assert ("green" if green else "blue") + " lower viewport" in command("read")["text"]
            diag = api("/v1/diagnostics")
            assert diag["screenInteractive"] is False and diag["backgroundRendererActive"] is True
            checkpoints.append({"minute": minute, "elapsedSeconds": round(time.monotonic()-start, 1),
                "processId": pid, "documentId": current["documentId"], "shot": shot, "diagnostics": diag})
            previous = shot
            (OUT / "background-locked-checkpoints.json").write_text(json.dumps(checkpoints, indent=2))
            print("Background locked checkpoint:", minute, "minutes PASS", flush=True)
        adb("shell", "input", "keyevent", "224")
        adb("shell", "wm", "dismiss-keyguard")
        adb("shell", "am", "start", "-n", PACKAGE + "/dev.local.aibrowser.MainActivity")
        until(lambda: api("/v1/status")["viewAttached"])
        capture_color("foreground-after-30min", (50,105,221))
    long_case = "fresh screenshots after 1/10/30 minutes in background with screen off"
    if os.environ.get("ABWR_CI_LONG_BACKGROUND", "1") == "1":
        run(long_case, long_locked_background)
    else:
        cases.append({"case": long_case, "result": "NOT_RUN",
                      "reason": "Fast acceptance; run workflow_dispatch with long_background=true for a fresh idle test"})
        (OUT / "results.json").write_text(json.dumps(cases, ensure_ascii=False, indent=2))
        print("NOT_RUN", long_case, "(explicit fast acceptance)", flush=True)

    (OUT / "installed-application.json").write_text(json.dumps(api("/v1/diagnostics").get("installedApplication"), indent=2))
    assert api("/v1/diagnostics")["installedApplication"]["sourceRevision"] == os.environ["GITHUB_SHA"]
    assert not any(case.get("result") == "FAIL" for case in cases), "Emulator acceptance failed"


if __name__ == "__main__":
    assert os.environ.get("ABWR_CI_EMULATOR") == "1", "CI emulator only"
    assert PACKAGE in {"dev.local.aibrowser.dev", "dev.local.aibrowser"}, "Unexpected CI package"
    assert os.environ.get("ABWR_CI_LONG_BACKGROUND", "1") in {"0", "1"}, "Invalid background test mode"
    assert adb("shell", "getprop", "ro.kernel.qemu").decode().strip() == "1", "Refusing a physical device"
    if PACKAGE == "dev.local.aibrowser":
        assert os.environ.get("GITHUB_ACTIONS") == "true", "Release acceptance requires a fresh Actions emulator"
        assert adb("shell", "id", "-u").decode().strip() == "0", "Release evidence requires a rooted CI emulator"
    adb("logcat", "-c")
    with (OUT / "logcat.txt").open("wb") as output:
        logs = subprocess.Popen(["adb", "logcat", "-b", "all", "-v", "threadtime",
            "AndroidRuntime:E", "AIBrowser:W", "Abwr:W", "ActivityManager:W", "libc:F",
            "GeckoConsole:E", "Gecko:W", "GeckoViewWebExtension:W",
            "GeckoJNI:V", "GeckoEventDispatcher:W", "GeckoAppShell:E", "GeckoRuntime:E", "System.err:W",
            "DEBUG:V", "tombstoned:I",
            "lmkd:I", "am_kill:I", "am_proc_died:I", "am_crash:I", "am_anr:I", "*:S"],
            stdout=output, stderr=subprocess.DEVNULL)
        try:
            main()
        finally:
            try:
                collect_process_evidence()
            finally:
                logs.terminate()
                logs.wait(timeout=5)
                if token:
                    output.flush()
                    data = (OUT / "logcat.txt").read_bytes().replace(token.encode(), b"[REDACTED]")
                    output.seek(0); output.truncate(0); output.write(data); output.flush()
