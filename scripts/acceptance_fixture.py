#!/usr/bin/env python3
"""Controlled browser pages. No credentials, no external sites, no Python brace formatting."""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import html
import time
import threading
from urllib.parse import urlsplit, parse_qs

PAGE = r'''<!doctype html><html lang="zh-Hant"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Abwr browser fixture</title><style>
body{font:16px system-ui;margin:0;background:#f7faf9;color:#192b24}main{padding:24px;max-width:700px;margin:auto}
button,input,select,textarea{font:inherit;padding:12px;border:1px solid #9fb4ab;border-radius:12px;margin:6px 0}
button{background:#007352;color:white}nav{display:none}label{display:block}iframe{width:100%;height:180px;border:1px solid #9fb4ab;border-radius:16px}
.muted{color:#65796f}.card{background:#eaf1ee;border-radius:20px;padding:20px;margin:20px 0}
.custom-check{position:relative;padding:14px;border:2px solid #007352;border-radius:12px;cursor:pointer}
.custom-check input{position:absolute;opacity:0;width:0;height:0;padding:0;margin:0}
.custom-check:has(input:checked){background:#b4ecd7}
</style><main><p class="muted">ABWR · CONTROLLED FIXTURE</p><h1>瀏覽器操作測試</h1>
<p>這個頁面只使用測試資料。</p><nav>__NAV_LINKS__</nav>
<form action="/echo" method="post"><label for="name">Name</label><input id="name" name="name" placeholder="Test name">
<label for="note">Note</label><textarea id="note" name="note"></textarea>
<label><input id="check" type="checkbox"> Enabled checkbox</label>
<label><input id="disabled" type="checkbox" disabled> Disabled checkbox</label>
<label id="custom-label" class="custom-check"><input id="custom-check" type="checkbox"> Custom checkbox fixture</label>
<label class="custom-check"><input id="custom-disabled" type="checkbox" disabled> Custom disabled fixture</label>
<input id="unlabelled-hidden" type="checkbox" style="opacity:0;width:0;height:0" aria-label="Hidden without visible label">
<label><input id="readonly" readonly value="fixed"> Read-only</label>
<select id="choice"><option value="a">Alpha</option><option value="b">Beta</option><option value="locked" disabled>Disabled</option></select>
<input id="hidden" style="visibility:hidden" value="hidden"><button id="submit" type="submit">Submit form</button></form>
<button id="automation-prompt" onclick="document.getElementById('automation-answer').textContent=prompt('Controlled test prompt','') || 'dismissed'">Prompt fixture</button><span id="automation-answer"></span>
<button id="late" onclick="document.getElementById('result').textContent='clicked late'">Continue with test provider</button>
<div id="editor" contenteditable="true" role="textbox" aria-label="Plain editor" class="card">editable</div>
<button id="http-check" onclick="console.log('ABWR_CI_CONSOLE');fetch('/http/echo',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({action:'abwr_ci',nonce:'ci-nonce',password:'redact-me'})})">HTTP test request</button>
<input type="range" id="range" min="0" max="100" value="0" style="width:260px" aria-label="Gesture slider"><button id="fullscreen" onclick="document.documentElement.requestFullscreen()">Fullscreen fixture</button><span id="fullscreen-state">windowed</span><script>document.addEventListener('fullscreenchange',()=>document.getElementById('fullscreen-state').textContent=document.fullscreenElement?'fullscreen':'windowed');</script><div id="shadow"></div><p id="result">not clicked</p>
<iframe title="Cross-origin child" src="__CHILD_URL__"></iframe>
<p><a href="/download.bin">Download 5 MiB fixture</a> <a href="/download-auth.bin">Authenticated download</a></p>
<div id="baxia-fixture" class="card">安全驗證測試訊號（僅測偵測器，沒有真實驗證）</div>
</main><script>
const root=document.getElementById('shadow').attachShadow({mode:'open'});
root.innerHTML='<button id="shadow-button" aria-label="Shadow action">Shadow action</button>';
root.querySelector('button').onclick=()=>{document.getElementById('result').textContent='clicked shadow'};
</script></html>'''

CHILD = '''<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><title>Child document</title>
<style>body{font:16px system-ui;background:#eaf1ee;padding:16px}button{padding:14px;border:0;border-radius:12px;background:#007352;color:white}</style>
<button id="frame-button" onclick="document.getElementById('frame-result').textContent='clicked child'">Child action</button><p id="frame-result">not clicked child</p>'''


def make_server(port=0, child_port=None):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def respond(self, raw, mime="text/html; charset=utf-8", extra=None):
            self.send_response(200)
            self.send_header("Content-Type", mime)
            self.send_header("Content-Length", str(len(raw)))
            for key, value in (extra or {}).items():
                self.send_header(key, value)
            self.end_headers()
            self.wfile.write(raw)

        def do_POST(self):
            length = min(int(self.headers.get("Content-Length", "0")), 256000)
            body = self.rfile.read(length).decode("utf-8", errors="replace")
            self.respond(json.dumps({"method":"POST", "body":body,"cookie":self.headers.get("Cookie", ""),
                "contentType":self.headers.get("Content-Type", ""),"authorization":self.headers.get("Authorization", ""),
                "custom":self.headers.get("X-Abwr-Test", "")}).encode(), "application/json")

        def do_GET(self):
            target = urlsplit(self.path)
            if target.path == "/http/echo":
                self.respond(json.dumps({"method":"GET","cookie":self.headers.get("Cookie", ""),
                    "authorization":self.headers.get("Authorization", ""),"custom":self.headers.get("X-Abwr-Test", "")}).encode(), "application/json")
            elif target.path == "/http/set-cookie":
                value = parse_qs(target.query).get("value", ["one"])[0]
                self.respond(b"<!doctype html><title>HTTP cookie fixture</title><p>HTTP cookie fixture</p>", extra={"Set-Cookie":"ABWR_CI_HTTP="+value+"; Path=/; SameSite=Lax; HttpOnly"})
            elif target.path == "/http/binary":
                self.respond(bytes(range(256)), "application/octet-stream")
            elif target.path == "/http/stream":
                self.send_response(200); self.send_header("Content-Type", "text/event-stream"); self.end_headers()
                try:
                    for message in ("first", "second", "done"):
                        self.wfile.write(("data: "+message+"\n\n").encode()); self.wfile.flush(); time.sleep(.6)
                except (BrokenPipeError, ConnectionResetError): pass
            elif target.path == "/http/quota":
                self.send_response(403); self.send_header("Content-Type", "application/json"); self.end_headers()
                self.wfile.write(b'{"quota":"exhausted"}')
            elif target.path == "/child":
                self.respond(CHILD.encode())
            elif target.path == "/redirect":
                self.send_response(302)
                self.send_header("Location", "/")
                self.send_header("Content-Length", "0")
                self.end_headers()
            elif target.path == "/slow":
                self.server.slow_started.set()
                self.server.slow_release.wait(120)
                try:
                    self.respond(b"<!doctype html><title>Slow page</title><main>Slow page completed</main>")
                except (BrokenPipeError, ConnectionResetError): pass
            elif target.path == "/color":
                green = parse_qs(target.query).get("c", ["green"])[0] == "green"
                color = "#14b87a" if green else "#3269dd"
                name = "green" if green else "blue"
                title = html.escape(parse_qs(target.query).get("label", [name])[0][:80])
                self.respond(('''<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
<title>''' + title + '''</title><style>html,body{margin:0;height:100%;background:''' + color + '''}
header{height:80px;background:#b82835;color:white}main{height:calc(100% - 80px);background:''' + color + '''}</style>
<header>Screenshot header</header><main>''' + name + ''' lower viewport</main>''').encode())
            elif target.path in ("/download.bin", "/download-auth.bin"):
                if target.path == "/download-auth.bin" and "ABWR_CI_HTTP=download-auth" not in self.headers.get("Cookie", ""):
                    self.send_response(403); self.end_headers(); self.wfile.write(b"Cookie required"); return
                filename = "abwr-auth-fixture.bin" if target.path == "/download-auth.bin" else "abwr-fixture.bin"
                self.respond(b"a" * (5 * 1024 * 1024), "application/octet-stream",
                             {"Content-Disposition": 'attachment; filename="' + filename + '"'})
            else:
                links = "".join('<a href="/#nav%d">Navigation %d</a>' % (i, i) for i in range(180))
                actual_child = child_port or self.server.server_port
                raw = PAGE.replace("__NAV_LINKS__", links).replace("__CHILD_URL__", "http://127.0.0.1:%d/child" % actual_child)
                self.respond(raw.encode())

    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    server.slow_started = threading.Event()
    server.slow_release = threading.Event()
    return server


if __name__ == "__main__":
    import threading
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8877)
    args = parser.parse_args()
    child = make_server(args.port + 1)
    threading.Thread(target=child.serve_forever, daemon=True).start()
    server = make_server(args.port, child.server_port)
    print("Fixture: http://127.0.0.1:%d/" % server.server_port, flush=True)
    server.serve_forever()
