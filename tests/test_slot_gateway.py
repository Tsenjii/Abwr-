import hashlib
import http.client
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
import socket
import ssl
import shutil
import subprocess
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
from unittest.mock import patch
import tempfile
import threading
import unittest
from bridge.slot_gateway import SlotGateway,SlotState,GatewayError,parse_server

BODY=b'{"fixture":"slot-upstream"}'
class FakeUpstream(BaseHTTPRequestHandler):
    calls=0
    def log_message(self,*_):pass
    def do_GET(self):
        type(self).calls+=1
        self.send_response(200);self.send_header('Content-Length',str(len(BODY)));self.end_headers();self.wfile.write(BODY)

class GatewayTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
    def test_server_syntax_rejects_inline_credentials(self):
        self.assertEqual(parse_server('host.test:8000').hostname,'host.test')
        for value in ['host:80:user:password','http://user:pass@host:80','ftp://host:80','http://host:80/path']:
            with self.assertRaises(GatewayError):parse_server(value)
    def test_slot_cooldown_survives_restart(self):
        state=SlotState(self.temp.name+'/state.json');state.failed(7901)
        fresh=SlotState(self.temp.name+'/state.json');self.assertEqual(fresh.read(7901)['failures'],1)
        self.assertGreater(fresh.read(7901)['cooldownUntil'],0)
    def test_real_http_hop_control_auth_and_content_probe(self):
        upstream=ThreadingHTTPServer(('127.0.0.1',0),FakeUpstream)
        threading.Thread(target=upstream.serve_forever,daemon=True).start()
        self.addCleanup(lambda:(upstream.shutdown(),upstream.server_close()))
        selected=None
        for port in range(7901,7916):
            with socket.socket() as sock:
                try:sock.bind(('127.0.0.1',port));selected=port;break
                except OSError:pass
        self.assertIsNotNone(selected)
        with socket.socket() as sock:sock.bind(('127.0.0.1',0));control=sock.getsockname()[1]
        gateway=SlotGateway([{'port':selected,'server':'http://127.0.0.1:'+str(upstream.server_port)}],self.temp.name,'test-only',control)
        gateway.start();self.addCleanup(gateway.stop)
        gateway.state.update(selected,exitIp='198.51.100.7')
        count=FakeUpstream.calls
        result=gateway.probe(selected,'http://fixture.test/test',hashlib.sha256(BODY).hexdigest())
        self.assertTrue(result['healthy']);self.assertFalse(result['tlsVerified']);self.assertGreater(FakeUpstream.calls,count)
        self.assertIsNone(result['exitIp'],'A previous target mapping is not a current observation')
        self.assertEqual(gateway.state.read(selected)['exitIp'],'198.51.100.7')
        self.assertFalse(gateway.probe(selected,'http://fixture.test/test')['healthy'],'Status 200 alone is not content verification')
        connection=http.client.HTTPConnection('127.0.0.1',control,timeout=5)
        connection.request('GET','/slots');self.assertEqual(connection.getresponse().status,401);connection.close()
        gateway.state.failed(selected)
        connection=http.client.HTTPConnection('127.0.0.1',selected,timeout=5)
        connection.request('GET','http://fixture.test/test');self.assertEqual(connection.getresponse().status,503);connection.close()
    def test_reserved_control_port_is_rejected(self):
        with self.assertRaises(GatewayError):SlotGateway([{'port':7901,'server':'host:80'}],self.temp.name,'test-only',8765)
    def test_failure_updates_are_atomic_and_all_disabled_pool_times_out(self):
        gateway=SlotGateway([{'port':7901,'server':'fixture.test:8000'}],self.temp.name,'test-only')
        with ThreadPoolExecutor(8) as pool:list(pool.map(lambda _:gateway.state.failed(7901),range(20)))
        self.assertEqual(gateway.state.read(7901)['failures'],20)
        gateway.state.update(7901,enabled=False)
        state=gateway.wait_available(10)
        self.assertTrue(state['allUnavailable']);self.assertFalse(state['allCooling']);self.assertEqual(state['availableCount'],0)
    @unittest.skipUnless(shutil.which('openssl'),'Local TLS certificate tool is unavailable')
    def test_verified_https_upstream_relays_large_response_without_truncation(self):
        cert=Path(self.temp.name)/'fixture-cert.pem';key=Path(self.temp.name)/'fixture-key.pem'
        subprocess.run(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-keyout',str(key),'-out',str(cert),'-days','1','-subj','/CN=127.0.0.1','-addext','subjectAltName=IP:127.0.0.1'],check=True,capture_output=True)
        payload=b'controlled TLS response '*60000
        class LargeUpstream(BaseHTTPRequestHandler):
            def log_message(self,*_):pass
            def do_GET(self):
                self.send_response(200);self.send_header('Content-Length',str(len(payload)));self.end_headers();self.wfile.write(payload)
        upstream=ThreadingHTTPServer(('127.0.0.1',0),LargeUpstream)
        server_context=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);server_context.load_cert_chain(str(cert),str(key))
        upstream.socket=server_context.wrap_socket(upstream.socket,server_side=True)
        threading.Thread(target=upstream.serve_forever,daemon=True).start()
        self.addCleanup(lambda:(upstream.shutdown(),upstream.server_close()))
        selected=None
        for port in range(7901,7916):
            with socket.socket() as sock:
                try:sock.bind(('127.0.0.1',port));selected=port;break
                except OSError:pass
        self.assertIsNotNone(selected)
        with socket.socket() as sock:sock.bind(('127.0.0.1',0));control=sock.getsockname()[1]
        gateway=SlotGateway([{'port':selected,'server':'https://127.0.0.1:'+str(upstream.server_port)}],self.temp.name,'test-only',control)
        trusted=ssl.create_default_context(cafile=str(cert))
        with patch('bridge.slot_gateway.ssl.create_default_context',return_value=trusted):
            gateway.start()
            try:
                connection=http.client.HTTPConnection('127.0.0.1',selected,timeout=10)
                connection.request('GET','http://fixture.test/large')
                response=connection.getresponse();self.assertEqual(response.status,200);self.assertEqual(response.read(),payload)
                connection.close();self.assertEqual(gateway.state.read(selected)['failures'],0)
            finally:gateway.stop()
