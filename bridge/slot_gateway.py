#!/usr/bin/env python3
"""Bounded, loopback-only HTTP proxy slots. No account handling, TLS interception, or route failover."""
import argparse
import base64
import hashlib
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import ipaddress
import json
import os
from pathlib import Path
import select
import socket
import ssl
import threading
import time
from urllib.parse import urlsplit

class GatewayError(RuntimeError): pass

def parse_server(value):
    try:
        url=urlsplit(value if "://" in value else "http://"+value)
        if url.scheme not in {"http","https","socks5"} or not url.hostname or not 1<=url.port<=65535 or url.username or url.password or url.path not in {"","/"} or url.query or url.fragment: raise ValueError()
        return url
    except (ValueError,TypeError): raise GatewayError("Use scheme://host:port and separate username/password fields") from None

def receive(sock,count):
    data=b""
    while len(data)<count:
        chunk=sock.recv(count-len(data))
        if not chunk: raise GatewayError("Upstream ended during handshake")
        data+=chunk
    return data

def open_upstream(config,host,port,tunnel=True):
    upstream=parse_server(config["server"])
    sock=socket.create_connection((upstream.hostname,upstream.port),timeout=10)
    try:
        if upstream.scheme=="https": sock=ssl.create_default_context().wrap_socket(sock,server_hostname=upstream.hostname)
        if upstream.scheme=="socks5":
            username=config.get("username","").encode();password=config.get("password","").encode()
            if len(username)>255 or len(password)>255: raise GatewayError("SOCKS credentials exceed protocol bounds")
            sock.sendall(b"\x05\x01"+(b"\x02" if username else b"\x00"))
            method=receive(sock,2)
            if method[0]!=5 or method[1]!=(2 if username else 0): raise GatewayError("SOCKS authentication method rejected")
            if username:
                sock.sendall(b"\x01"+bytes([len(username)])+username+bytes([len(password)])+password)
                if receive(sock,2)!=b"\x01\x00": raise GatewayError("SOCKS credentials rejected")
            name=host.encode("idna")
            if len(name)>255: raise GatewayError("Target host exceeds SOCKS bounds")
            sock.sendall(b"\x05\x01\x00\x03"+bytes([len(name)])+name+port.to_bytes(2,"big"))
            response=receive(sock,4)
            if response[:2]!=b"\x05\x00": raise GatewayError("SOCKS target connection rejected")
            size={1:4,4:16}.get(response[3])
            if response[3]==3:size=receive(sock,1)[0]
            if size is None:raise GatewayError("Invalid SOCKS address type")
            receive(sock,size+2)
        elif tunnel:
            target=("["+host+"]" if ":" in host else host)+":"+str(port)
            auth=""
            if config.get("username"):
                auth="Proxy-Authorization: Basic "+base64.b64encode((config["username"]+":"+config.get("password","")).encode()).decode()+"\r\n"
            sock.sendall(("CONNECT "+target+" HTTP/1.1\r\nHost: "+target+"\r\n"+auth+"\r\n").encode())
            headers=b""
            while not headers.endswith(b"\r\n\r\n") and len(headers)<16384: headers+=receive(sock,1)
            if not headers.endswith(b"\r\n\r\n") or not headers.split(b" ")[1].startswith(b"2"):raise GatewayError("Upstream CONNECT was rejected")
        return sock,upstream.scheme in {"http","https"} and not tunnel
    except Exception:
        sock.close();raise

def relay(left,right,timeout=60):
    sockets=[left,right];peer={left:right,right:left};pending={left:bytearray(),right:bytearray()};reading=set(sockets)
    for item in sockets:item.setblocking(False)
    deadline=time.monotonic()+timeout;last=time.monotonic()
    while (reading or any(pending.values())) and time.monotonic()<deadline and time.monotonic()-last<30:
        readable=[s for s in reading if len(pending[peer[s]])<65536]
        writable=[s for s in sockets if pending[s]]
        ready_read,ready_write,_=select.select(readable,writable,[],1)
        for source in ready_read:
            data=source.recv(32768)
            if data:pending[peer[source]].extend(data);last=time.monotonic()
            else:reading.remove(source)
        for destination in ready_write:
            sent=destination.send(pending[destination])
            del pending[destination][:sent];last=time.monotonic()
        for source in list(sockets):
            if source not in reading and not pending[peer[source]]:
                try:peer[source].shutdown(socket.SHUT_WR)
                except OSError:pass
        if not reading and not any(pending.values()):return

class SlotState:
    def __init__(self,path):
        self.path=Path(path);self.path.parent.mkdir(parents=True,exist_ok=True);self.lock=threading.RLock()
        self.data=json.loads(self.path.read_text()) if self.path.exists() else {}
    def read(self,port):
        with self.lock:return dict(self.data.get(str(port),{"failures":0,"cooldownUntil":0,"enabled":True}))
    def update(self,port,**values):
        with self.lock:
            self.data[str(port)]={**self.read(port),**values}
            temp=self.path.with_suffix(".tmp")
            with temp.open("w") as stream:os.chmod(temp,0o600);json.dump(self.data,stream)
            temp.replace(self.path)
    def failed(self,port):
        state=self.read(port);n=state["failures"]+1
        self.update(port,failures=n,cooldownUntil=time.time()+min(300,2**min(n,8)))

class LoopbackServer(ThreadingHTTPServer):
    daemon_threads=True
    allow_reuse_address=False
    request_queue_size=8
    connection_budget=threading.BoundedSemaphore(32)
    def get_request(self):
        request,address=super().get_request();request.settimeout(10);return request,address
    def process_request(self,request,address):
        if not self.connection_budget.acquire(False):
            try:request.sendall(b'HTTP/1.1 503 BUSY\r\nContent-Length: 0\r\nConnection: close\r\n\r\n')
            except OSError:pass
            request.close();return
        try:super().process_request(request,address)
        except Exception:self.connection_budget.release();raise
    def process_request_thread(self,request,address):
        try:super().process_request_thread(request,address)
        finally:self.connection_budget.release()

class SlotGateway:
    def __init__(self,configs,root=None,token=None,control_port=9091):
        self.root=Path(root or Path.home()/".workbuddy-ai")
        self.state=SlotState(self.root/"state"/"gateway.json")
        self.token=token or os.environ.get("ABWR_GATEWAY_TOKEN","")
        if not self.token:raise GatewayError("ABWR_GATEWAY_TOKEN is required for the control plane")
        if control_port in {8765,8877} or 7901<=control_port<=7915 or not 1<=control_port<=65535:raise GatewayError("Control port conflicts with a reserved service")
        self.control_port=control_port;self.configs={};self.servers=[];self.workers=threading.BoundedSemaphore(4)
        if not 1<=len(configs)<=15:raise GatewayError("Configure 1–15 exit slots")
        for cfg in configs:
            port=cfg.get("port")
            if not isinstance(port,int) or isinstance(port,bool) or not 7901<=port<=7915 or port in self.configs:raise GatewayError("Slot ports must be unique, 7901–7915")
            parse_server(cfg["server"])
            if any("\r" in cfg.get(k,"") or "\n" in cfg.get(k,"") for k in ("username","password")):raise GatewayError("Invalid credentials")
            self.configs[port]=dict(cfg)
    def snapshot(self):
        now=time.time();slots=[{"port":port,"server":cfg["server"],"hasCredentials":bool(cfg.get("username")),**self.state.read(port)} for port,cfg in sorted(self.configs.items())]
        return {"slots":slots,"capacity":15,"controlPort":self.control_port,"bind":"127.0.0.1","allCooling":all(s["cooldownUntil"]>now for s in slots),"automaticFailover":False}
    def probe(self,port,target,expected_sha256=None):
        url=urlsplit(target)
        if url.scheme not in {"http","https"} or not url.hostname or url.username or url.password or len(target)>2048:raise GatewayError("Invalid target probe URL")
        with socket.create_connection(("127.0.0.1",port),timeout=5):pass
        connection=(http.client.HTTPSConnection("127.0.0.1",port,timeout=15,context=ssl.create_default_context())
                    if url.scheme=="https" else http.client.HTTPConnection("127.0.0.1",port,timeout=15))
        try:
            if url.scheme=="https":connection.set_tunnel(url.hostname,url.port or 443)
            path=(url.path or "/")+("?"+url.query if url.query else "")
            connection.request("GET",path if url.scheme=="https" else target,headers={"Host":url.netloc,"Connection":"close"})
            response=connection.getresponse();body=response.read(65537)
            if len(body)>65536:raise GatewayError("Probe body exceeds 64 KiB")
            digest=hashlib.sha256(body).hexdigest()
            verified=bool(expected_sha256) and digest==expected_sha256 and 200<=response.status<300
            if verified:
                try:
                    data=json.loads(body);exit_ip=str(ipaddress.ip_address(data.get("ip",data.get("origin",""))))
                    self.state.update(port,exitIp=exit_ip)
                except (ValueError,TypeError,AttributeError):pass
            return {"port":port,"tcpConnected":True,"targetHost":url.hostname,"status":response.status,
                    "tlsVerified":url.scheme=="https","bodySha256":digest,"targetVerified":verified,
                    "healthy":verified,"verificationRequiresExpectedContent":True}
        finally:connection.close()
    def start(self):
        gateway=self
        class SlotHandler(BaseHTTPRequestHandler):
            protocol_version="HTTP/1.1"
            def log_message(self,*_):pass
            def do_CONNECT(self):self.forward(True)
            def do_GET(self):self.forward(False)
            do_POST=do_PUT=do_PATCH=do_DELETE=do_OPTIONS=do_HEAD=do_GET
            def forward(self,tunnel):
                port=self.server.server_port;state=gateway.state.read(port)
                self.connection.settimeout(10)
                if not state["enabled"] or state["cooldownUntil"]>time.time():self.send_error(503,"SLOT_COOLDOWN");return
                if not gateway.workers.acquire(False):self.send_error(503,"BUSY");return
                upstream=None
                try:
                    target=urlsplit("//"+self.path if tunnel else self.path)
                    if target.username or target.password or not target.hostname or (not tunnel and target.scheme!="http"):raise GatewayError("Invalid proxy target")
                    host=target.hostname;target_port=target.port or (443 if tunnel else 80)
                    upstream,absolute=open_upstream(gateway.configs[port],host,target_port,tunnel)
                    if tunnel:self.send_response(200,"Connection Established");self.end_headers()
                    else:
                        if self.headers.get("Transfer-Encoding"):raise GatewayError("Chunked uploads are not supported; use Content-Length")
                        size=int(self.headers.get("Content-Length",0))
                        if not 0<=size<=2*1024*1024:raise GatewayError("Request body exceeds 2 MiB")
                        path=self.path if absolute else (target.path or "/")+("?"+target.query if target.query else "")
                        headers=[(k,v) for k,v in self.headers.items() if k.lower() not in {"proxy-authorization","proxy-connection","connection"}]
                        cfg=gateway.configs[port]
                        if absolute and cfg.get("username"):headers.append(("Proxy-Authorization","Basic "+base64.b64encode((cfg["username"]+":"+cfg.get("password","")).encode()).decode()))
                        head=self.command+" "+path+" HTTP/1.1\r\n"+"".join(k+": "+v+"\r\n" for k,v in headers)+"Connection: close\r\n\r\n"
                        upstream.sendall(head.encode("latin1"))
                        remaining=size
                        while remaining:
                            chunk=self.rfile.read(min(65536,remaining))
                            if not chunk:raise GatewayError("Truncated request body")
                            upstream.sendall(chunk);remaining-=len(chunk)
                    relay(self.connection,upstream)
                    self.close_connection=True
                except Exception:
                    gateway.state.failed(port)
                    if upstream is None:
                        try:self.send_error(502,"UPSTREAM_UNAVAILABLE")
                        except OSError:pass
                    self.close_connection=True
                finally:
                    if upstream:upstream.close()
                    gateway.workers.release()
        class ControlHandler(BaseHTTPRequestHandler):
            def log_message(self,*_):pass
            def reply(self,code,value):
                raw=json.dumps(value).encode();self.send_response(code);self.send_header("Content-Type","application/json");self.send_header("Content-Length",str(len(raw)));self.end_headers();self.wfile.write(raw)
            def authorized(self):
                import hmac
                return hmac.compare_digest(self.headers.get("Authorization",""),"Bearer "+gateway.token)
            def do_GET(self):
                if not self.authorized():self.reply(401,{"code":"UNAUTHORIZED"});return
                if self.path!="/slots":self.reply(404,{"code":"NOT_FOUND"});return
                value=gateway.snapshot();self.reply(503 if value["allCooling"] else 200,value)
            def do_POST(self):
                if not self.authorized():self.reply(401,{"code":"UNAUTHORIZED"});return
                try:
                    size=int(self.headers.get("Content-Length",0))
                    if not 0<size<=8192:raise GatewayError("Invalid control body")
                    body=json.loads(self.rfile.read(size));port=body["port"]
                    if port not in gateway.configs:raise GatewayError("Unknown slot")
                    if self.path=="/slots/enable":gateway.state.update(port,enabled=bool(body["enabled"]))
                    elif self.path=="/slots/cooldown":gateway.state.failed(port)
                    elif self.path=="/slots/probe":
                        self.reply(200,gateway.probe(port,body["target"],body.get("expectedSha256")));return
                    else:self.reply(404,{"code":"NOT_FOUND"});return
                    self.reply(200,gateway.state.read(port))
                except Exception:self.reply(400,{"code":"BAD_ARGUMENTS"})
        try:
            for port in self.configs:self.servers.append(LoopbackServer(("127.0.0.1",port),SlotHandler))
            self.servers.append(LoopbackServer(("127.0.0.1",self.control_port),ControlHandler))
        except OSError:
            for server in self.servers:server.server_close()
            self.servers=[];raise GatewayError("PORT_CONFLICT: a slot or control port is already in use") from None
        for server in self.servers:threading.Thread(target=server.serve_forever,daemon=True).start()
    def stop(self):
        for server in self.servers:server.shutdown();server.server_close()
        self.servers=[]

def main():
    parser=argparse.ArgumentParser();parser.add_argument("--config",required=True);parser.add_argument("--root");parser.add_argument("--control-port",type=int,default=9091)
    args=parser.parse_args();configs=json.loads(Path(args.config).read_text())["slots"]
    gateway=SlotGateway(configs,args.root,control_port=args.control_port);gateway.start()
    print("Gateway ready on 127.0.0.1; slot configuration never implies a healthy exit",flush=True)
    try:threading.Event().wait()
    except KeyboardInterrupt:pass
    finally:gateway.stop()
if __name__=="__main__":main()
