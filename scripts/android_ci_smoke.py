#!/usr/bin/env python3
"""Exercise a fresh CI emulator. Refuses production package; never prints the API token."""
import io
import base64
import json
import os
from pathlib import Path
import re
import select
import subprocess
import threading
import sys
import traceback
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
last_request = None


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
        assert response.status == expected_status, (path, response.status,
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
    assert completed["documentId"] == current["documentId"]
    return current


def run(name, fn):
    started = time.monotonic()
    try:
        fn()
        cases.append({"case": name, "result": "PASS", "seconds": round(time.monotonic()-started, 2)})
        print("PASS", name, flush=True)
    except Exception as error:
        source = traceback.extract_tb(error.__traceback__)[-1]
        cases.append({"case": name, "result": "FAIL", "error": type(error).__name__,
                      "detail": str(error)[:350], "source": source.name + ":" + str(source.lineno),
                      "lastRequest": last_request})
        print("FAIL", name, type(error).__name__, source.name, source.lineno, str(error)[:350], flush=True)
        try:
            requested_profile = (last_request or {}).get("profile")
            (OUT / ("failure-" + str(len(cases)) + "-status.json")).write_text(json.dumps(
                {"status": api("/v1/status", profile=requested_profile),
                 "diagnostics": api("/v1/diagnostics")}, indent=2))
            ui_image("failure-" + str(len(cases)))
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


def tap_node(predicate):
    node = until(lambda: next((n for n in ui_tree().iter("node") if predicate(n)), None), 15)
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds", "")))
    adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))


def ui_image(name):
    time.sleep(.4)
    (OUT / (name + ".png")).write_bytes(adb("exec-out", "screencap", "-p"))


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
        raw = adb("shell", "run-as", PACKAGE, "cat", "shared_prefs/local_api.xml", check=False)
        try:
            root = ET.fromstring(raw)
            return next((n.text for n in root if n.get("name") == "token"), None)
        except ET.ParseError:
            return None
    token = until(read_token, 45)
    until(lambda: api("/v1/status").get("ready"), 60)
    initial_pid = adb("shell", "pidof", PACKAGE).decode().strip()
    (OUT / "emulator-memory.txt").write_bytes(adb("shell", "cat", "/proc/meminfo"))

    def identity_and_snapshot():
        s = navigate(fixture)
        assert s["contentBridgeVersion"] == "0.11.0" and s["bridgeSchemaVersion"] == 3
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
        for selector in ("#disabled", "#hidden", "#custom-disabled", "#unlabelled-hidden"):
            result = api("/v1/command", {"action": "toggle", "selector": selector})
            assert not result["ok"] and result["code"] == "TARGET_NOT_INTERACTABLE"
        result = api("/v1/command", {"action": "wait", "selector": "#missing", "timeoutMs": 100})
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
        for index in range(50):
            current_pid = adb("shell", "pidof", PACKAGE, check=False).decode().strip()
            assert current_pid == initial_pid, "Abwr process restarted during cycle " + str(index)
            s = navigate(fixture + "?cycle=" + str(index))
            snapshot = command("snapshot", expectedUrl=s["url"], documentId=s["documentId"])
            target = next(e["ref"] for e in snapshot["elements"] if e["selectorHint"] == "#name")
            command("type", target=target, text="cycle-" + str(index), documentId=s["documentId"])
            command("click", selector="#late", documentId=s["documentId"])
            assert "clicked late" in command("read", documentId=s["documentId"])["text"]
            (OUT / "cycle-progress.json").write_text(json.dumps({"completed": index+1, "processId": current_pid}))
        cases.append({"case": "cycle count", "completed": 50})
    run("50 sequential navigation and operation cycles", cycles)

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
            if response["content"][0]["type"] == "image":
                return response["content"][0]
            return json.loads(response["content"][0]["text"])
        try:
            initialized = rpc("initialize", {"protocolVersion": "2025-03-26",
                "capabilities": {}, "clientInfo": {"name": "abwr-ci", "version": "1"}})
            assert initialized["serverInfo"]["version"] == "0.11.0"
            tools = rpc("tools/list", {})["tools"]
            assert len(tools) == 59
            assert call("browser_capabilities")["apiVersion"] == "0.11.0"
            assert {"browser_snapshot", "browser_frames", "browser_press", "browser_click_point"} <= {t["name"] for t in tools}
            status = call("browser_status")
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
            call("browser_click", selector="#frame-button", frame=frame["frame"], **guard)
            assert "clicked child" in call("browser_read", frame=frame["frame"])["result"]["text"]
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
            for index in range(50):
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
                "cycles": 50, "realAndroidHttpBackend": True, "result": "PASS"}, indent=2))
        finally:
            process.stdin.close()
            process.terminate()
            process.wait(timeout=5)
    run("real MCP workflow, groups and 50 operation cycles", mcp_live)

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
        adb("shell", "input", "keyevent", "123")
        adb("shell", "input", "keyevent", *("67" for _ in "CIWORK"))
        adb("shell", "input", "text", "CIWORK2")
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

    assert not any(case.get("result") == "FAIL" for case in cases), "Fast acceptance failed; skipping the 30-minute idle test"

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
    run("fresh screenshots after 1/10/30 minutes in background with screen off", long_locked_background)

    (OUT / "system-exit-info.txt").write_bytes(adb("shell", "dumpsys", "activity", "exit-info", PACKAGE, check=False))
    (OUT / "installed-application.json").write_text(json.dumps(api("/v1/diagnostics").get("installedApplication"), indent=2))
    assert api("/v1/diagnostics")["installedApplication"]["sourceRevision"] == os.environ["GITHUB_SHA"]
    assert not any(case.get("result") == "FAIL" for case in cases), "Emulator acceptance failed"


if __name__ == "__main__":
    assert os.environ.get("ABWR_CI_EMULATOR") == "1", "CI emulator only"
    assert adb("shell", "getprop", "ro.kernel.qemu").decode().strip() == "1", "Refusing a physical device"
    adb("logcat", "-c")
    with (OUT / "logcat.txt").open("wb") as output:
        logs = subprocess.Popen(["adb", "logcat", "-b", "all", "-v", "threadtime",
            "AndroidRuntime:E", "AIBrowser:W", "Abwr:W", "ActivityManager:W", "libc:F",
            "lmkd:I", "am_kill:I", "am_proc_died:I", "am_crash:I", "am_anr:I", "*:S"],
            stdout=output, stderr=subprocess.DEVNULL)
        try:
            main()
        finally:
            logs.terminate()
            logs.wait(timeout=5)
