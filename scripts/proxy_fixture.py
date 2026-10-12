"""Loopback-only CI proxy, restricted to explicit fixture hosts; never stores credentials."""
import base64
from collections import deque
import hmac
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import select
import socket
import socketserver
import threading
import time
from urllib.parse import urlsplit


def make_proxy(label, allowed_targets, username='', password=''):
    expected = 'Basic ' + base64.b64encode((username+':'+password).encode()).decode() if username else None
    class Handler(BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.1'
        def log_message(self, *args): pass
        def reply(self, status, headers=None):
            self.send_response(status)
            for name,value in (headers or {}).items(): self.send_header(name,value)
            self.send_header('Content-Length','0');self.send_header('Connection','close');self.end_headers()
            self.close_connection=True
        def authorised(self):
            if expected and not hmac.compare_digest(self.headers.get('Proxy-Authorization',''),expected):
                self.reply(407,{'Proxy-Authenticate':'Basic realm="ABWR controlled fixture"'});return False
            return True
        def record(self,method,host,port,path):
            with self.server.lock:self.server.records.append({'exit':label,'method':method,'host':host,'port':port,'path':path})
        def do_GET(self):
            if not self.authorised():return
            url=urlsplit(self.path);port=url.port or 80
            if url.scheme!='http' or url.username or (url.hostname,port) not in allowed_targets:self.reply(403);return
            self.record('GET',url.hostname,port,url.path)
            connection=http.client.HTTPConnection(url.hostname,port,timeout=15)
            try:
                headers={k:v for k,v in self.headers.items() if k.lower() not in {'proxy-authorization','proxy-connection','connection','host'}}
                connection.request('GET',(url.path or '/')+('?' + url.query if url.query else ''),headers=headers)
                response=connection.getresponse();body=response.read(6*1024*1024+1)
                if len(body)>6*1024*1024:self.reply(502);return
                self.send_response(response.status)
                for name,value in response.getheaders():
                    if name.lower() not in {'content-length','connection','transfer-encoding'}:self.send_header(name,value)
                self.send_header('X-Abwr-Fixture-Exit',label);self.send_header('Content-Length',str(len(body)))
                self.send_header('Connection','close');self.end_headers();self.wfile.write(body);self.close_connection=True
            except (OSError,http.client.HTTPException):self.close_connection=True
            finally:connection.close()
        def do_CONNECT(self):
            if not self.authorised():return
            host,separator,port=self.path.rpartition(':')
            if not separator or not port.isdigit() or (host,int(port)) not in allowed_targets:self.reply(403);return
            try:upstream=socket.create_connection((host,int(port)),timeout=15)
            except OSError:self.reply(502);return
            self.record('CONNECT',host,int(port),'')
            self.send_response(200);self.end_headers();self.wfile.flush()
            sockets=(self.connection,upstream);deadline=time.monotonic()+30
            try:
                while time.monotonic()<deadline:
                    ready,_,_=select.select(sockets,[],[],1)
                    for source in ready:
                        data=source.recv(65536)
                        if not data:return
                        (upstream if source is self.connection else self.connection).sendall(data)
            except OSError:pass
            finally:upstream.close();self.close_connection=True
    server=ThreadingHTTPServer(('127.0.0.1',0),Handler)
    server.daemon_threads=True;server.records=deque(maxlen=1024);server.lock=threading.Lock()
    return server

def make_socks(label,allowed_targets,username='',password=''):
    def receive(stream,count):
        result=b''
        while len(result)<count:
            chunk=stream.recv(count-len(result))
            if not chunk:raise OSError('SOCKS peer closed')
            result+=chunk
        return result
    class Handler(socketserver.BaseRequestHandler):
        def handle(self):
            source=self.request;source.settimeout(10);upstream=None
            try:
                version,count=receive(source,2);methods=receive(source,count)
                required=2 if username else 0
                if version!=5 or required not in methods:source.sendall(b'\x05\xff');return
                source.sendall(bytes((5,required)))
                if username:
                    if receive(source,1)!=b'\x01':return
                    owner=receive(source,receive(source,1)[0]);secret=receive(source,receive(source,1)[0])
                    allowed=hmac.compare_digest(owner,username.encode()) and hmac.compare_digest(secret,password.encode())
                    source.sendall(b'\x01'+(b'\x00' if allowed else b'\x01'))
                    if not allowed:return
                version,command,reserved,kind=receive(source,4)
                if kind==1:host=socket.inet_ntop(socket.AF_INET,receive(source,4))
                elif kind==4:host=socket.inet_ntop(socket.AF_INET6,receive(source,16))
                elif kind==3:host=receive(source,receive(source,1)[0]).decode('ascii')
                else:return
                port=int.from_bytes(receive(source,2),'big')
                if version!=5 or command!=1 or (host,port) not in allowed_targets:source.sendall(b'\x05\x02\x00\x01'+b'\x00'*6);return
                upstream=socket.create_connection((host,port),timeout=10)
                with self.server.lock:self.server.records.append({'exit':label,'method':'SOCKS_CONNECT','host':host,'port':port,'authenticated':bool(username)})
                source.sendall(b'\x05\x00\x00\x01'+b'\x00'*6)
                sockets=(source,upstream);deadline=time.monotonic()+30
                while time.monotonic()<deadline:
                    ready,_,_=select.select(sockets,[],[],1)
                    for current in ready:
                        data=current.recv(65536)
                        if not data:return
                        (upstream if current is source else source).sendall(data)
            except (OSError,ValueError):pass
            finally:
                if upstream:upstream.close()
    class Server(socketserver.ThreadingTCPServer):daemon_threads=True
    server=Server(('127.0.0.1',0),Handler);server.records=deque(maxlen=1024);server.lock=threading.Lock()
    return server
