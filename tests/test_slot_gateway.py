import hashlib
import http.client
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
import socket
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
        count=FakeUpstream.calls
        result=gateway.probe(selected,'http://fixture.test/test',hashlib.sha256(BODY).hexdigest())
        self.assertTrue(result['healthy']);self.assertFalse(result['tlsVerified']);self.assertGreater(FakeUpstream.calls,count)
        self.assertFalse(gateway.probe(selected,'http://fixture.test/test')['healthy'],'Status 200 alone is not content verification')
        connection=http.client.HTTPConnection('127.0.0.1',control,timeout=5)
        connection.request('GET','/slots');self.assertEqual(connection.getresponse().status,401);connection.close()
        gateway.state.failed(selected)
        connection=http.client.HTTPConnection('127.0.0.1',selected,timeout=5)
        connection.request('GET','http://fixture.test/test');self.assertEqual(connection.getresponse().status,503);connection.close()
    def test_reserved_control_port_is_rejected(self):
        with self.assertRaises(GatewayError):SlotGateway([{'port':7901,'server':'host:80'}],self.temp.name,'test-only',8765)
