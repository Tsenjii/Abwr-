import base64
import http.client
import importlib.util
from pathlib import Path
import socket
import threading
import unittest
from bridge.slot_gateway import open_upstream

ROOT=Path(__file__).resolve().parents[1]
def module(name):
    spec=importlib.util.spec_from_file_location(name,ROOT/'scripts'/f'{name}.py')
    result=importlib.util.module_from_spec(spec);spec.loader.exec_module(result);return result
fixture=module('acceptance_fixture');proxy_fixture=module('proxy_fixture')

class ProxyFixtureTests(unittest.TestCase):
    def test_authenticated_hop_and_unlisted_target_rejection(self):
        target=fixture.make_server();proxy=proxy_fixture.make_proxy('A',{('127.0.0.1',target.server_port)},'fixture','controlled-only')
        for server in (target,proxy):threading.Thread(target=server.serve_forever,daemon=True).start()
        try:
            url=f'http://127.0.0.1:{target.server_port}/http/echo'
            connection=http.client.HTTPConnection('127.0.0.1',proxy.server_port,timeout=3)
            connection.request('GET',url);response=connection.getresponse();response.read();self.assertEqual(response.status,407);connection.close()
            headers={'Proxy-Authorization':'Basic '+base64.b64encode(b'fixture:controlled-only').decode()}
            connection=http.client.HTTPConnection('127.0.0.1',proxy.server_port,timeout=3)
            connection.request('GET',url,headers=headers);response=connection.getresponse();body=response.read()
            self.assertEqual(response.status,200);self.assertEqual(response.getheader('X-Abwr-Fixture-Exit'),'A')
            self.assertIn(b'"authorization": ""',body);connection.close()
            connection=http.client.HTTPConnection('127.0.0.1',proxy.server_port,timeout=3)
            connection.request('GET','http://unlisted.invalid/',headers=headers);response=connection.getresponse();response.read()
            self.assertEqual(response.status,403);connection.close()
            self.assertNotIn('controlled-only',str(list(proxy.records)))
        finally:
            for server in (proxy,target):server.shutdown();server.server_close()

    def test_connect_relays_bytes_without_replacing_the_target_protocol(self):
        listener=socket.socket();listener.bind(('127.0.0.1',0));listener.listen(1)
        def echo():
            with listener.accept()[0] as incoming:incoming.sendall(incoming.recv(32))
        threading.Thread(target=echo,daemon=True).start()
        proxy=proxy_fixture.make_proxy('B',{('127.0.0.1',listener.getsockname()[1])})
        threading.Thread(target=proxy.serve_forever,daemon=True).start()
        connection=http.client.HTTPConnection('127.0.0.1',proxy.server_port,timeout=3)
        try:
            connection.set_tunnel('127.0.0.1',listener.getsockname()[1]);connection.connect()
            connection.sock.sendall(b'controlled bytes');self.assertEqual(connection.sock.recv(32),b'controlled bytes')
            self.assertEqual(list(proxy.records)[0]['method'],'CONNECT')
        finally:connection.close();proxy.shutdown();proxy.server_close();listener.close()
    def test_authenticated_socks_target_relay(self):
        target=fixture.make_server();proxy=proxy_fixture.make_socks('S',{('127.0.0.1',target.server_port)},'fixture','controlled-only')
        for server in (target,proxy):threading.Thread(target=server.serve_forever,daemon=True).start()
        stream=None
        try:
            stream,absolute=open_upstream({'server':'socks5://127.0.0.1:'+str(proxy.server_address[1]),'username':'fixture','password':'controlled-only'},'127.0.0.1',target.server_port)
            self.assertFalse(absolute)
            stream.sendall(b'GET /http/echo HTTP/1.0\r\nHost: 127.0.0.1\r\n\r\n')
            data=b''
            while True:
                chunk=stream.recv(4096)
                if not chunk:break
                data+=chunk
            self.assertIn(b'200 OK',data);self.assertIn(b'"method": "GET"',data)
            self.assertTrue(list(proxy.records)[0]['authenticated']);self.assertNotIn('controlled-only',str(list(proxy.records)))
        finally:
            if stream:stream.close()
            for server in (proxy,target):server.shutdown();server.server_close()
