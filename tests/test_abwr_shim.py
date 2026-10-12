import base64
import json
import hashlib
from pathlib import Path
import tempfile
import unittest
from bridge.abwr_shim import AbwrBrowser, AbwrError, QuotaLedger, browser_backend

class ShimTests(unittest.TestCase):
    def fake(self,method,path,data,profile):
        self.calls.append((method,path,data,profile))
        if path=='/v1/capabilities':return {'apiVersion':'0.14.0','bridgeSchemaVersion':4,'mcpToolCount':82}
        if path=='/v1/diagnostics':return {'installedApplication':{'versionName':'0.14.0'}}
        if path=='/v1/status':return {'profile':profile,'activeTab':'main','url':'https://example.test/','documentId':'doc1','contentBridgeVersion':'0.14.0'}
        if path=='/v1/screenshot/evidence':return {'metadata':{'profile':profile,'tab':'main','documentId':'doc1','stale':False,'capturedAtMs':100,'url':'https://example.test/?token=private#secret'},'imageBase64':base64.b64encode(b'\xff\xd8\xfftest').decode()}
        return {'ok':True,'result':{'dispatched':True,'documentId':'doc1'}}
    def setUp(self):
        self.calls=[];self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.browser=AbwrBrowser(token='test-only',root=self.temp.name,transport=self.fake)
    def test_fill_is_one_scoped_write_and_observation(self):
        result=self.browser.context('TEST01').page().fill('#field','normal')
        writes=[x for x in self.calls if x[1]=='/v1/command' and x[2]['action']=='type']
        self.assertEqual(len(writes),1);self.assertEqual(writes[0][2]['documentId'],'doc1')
        self.assertEqual(writes[0][2]['tab'],'main');self.assertFalse(result['siteCompleted'])
        self.assertEqual(result['after']['documentId'],'doc1')
    def test_uncertain_write_is_never_replayed(self):
        original=self.browser._transport
        def fail(method,path,data,profile):
            if path=='/v1/command' and data['action']=='click':
                self.calls.append((method,path,data,profile));raise AbwrError('CONNECTION_FAILED','uncertain')
            return original(method,path,data,profile)
        self.browser._transport=fail
        with self.assertRaises(AbwrError):self.browser.context('TEST01').page().click('#button')
        self.assertEqual(sum(x[1]=='/v1/command' and x[2]['action']=='click' for x in self.calls),1)
        self.assertIn('reserved',(Path(self.temp.name)/'state/direct.jsonl').read_text())
    def test_version_mismatch_stops_before_write(self):
        original=self.browser._transport
        self.browser._transport=lambda m,p,d,c: {'apiVersion':'0.11.0','bridgeSchemaVersion':3,'mcpToolCount':59} if p=='/v1/capabilities' else original(m,p,d,c)
        with self.assertRaises(AbwrError) as error:self.browser.context('TEST01').page().click('#button')
        self.assertEqual(error.exception.code,'VERSION_MISMATCH')
        self.assertFalse(any(c[1]=='/v1/command' for c in self.calls))
    def test_screenshot_filename_and_sidecar_have_no_url_secrets(self):
        file=self.browser.context('TEST01').page().screenshot()
        self.assertTrue(file.name.startswith('TEST01_main_doc1_'))
        self.assertFalse('private' in file.name)
        self.assertEqual(json.loads(file.with_suffix('.json').read_text())['url'],'https://example.test/')
        self.assertTrue(file.read_bytes().startswith(b'\xff\xd8\xff'))
    def test_wait_for_timeout_uses_native_wait(self):
        self.browser.context('TEST01').page().wait_for_timeout(50)
        args=next(c[2] for c in self.calls if c[1]=='/v1/command')
        self.assertEqual(args['condition'],'delay');self.assertEqual(args['durationMs'],50)
    def test_ledger_persists_quota_and_spacing(self):
        ledger=QuotaLedger(Path(self.temp.name)/'ledger',limit=2)
        self.assertEqual(ledger.reserve('slot01',100),0)
        self.assertEqual(ledger.reserve('slot01',100),1200)
        with self.assertRaises(AbwrError) as error:QuotaLedger(Path(self.temp.name)/'ledger',limit=2).reserve('slot01',101)
        self.assertEqual(error.exception.code,'QUOTA_REACHED')
    def test_abwr_backend_never_imports_playwright_or_desktop_path(self):
        browser=browser_backend({'backend':'abwr','token':'test-only','root':self.temp.name,'executable_path':'C:\\unused\\chrome.exe'})
        self.assertIsInstance(browser,AbwrBrowser)
        with self.assertRaises(AbwrError):self.browser.context('TEST01').page().evaluate('arbitrary source')
    def test_loopback_authority_is_exact(self):
        for host in ['http://0.0.0.0:8765','http://127.0.0.1:8765@other.test','http://127.0.0.1:7901']:
            with self.assertRaises(AbwrError):AbwrBrowser(host=host,token='test-only',root=self.temp.name)
    def test_proxy_probe_requires_body_and_same_document_network_evidence(self):
        original=self.browser._transport;body=b'controlled probe';events=[]
        def transport(method,path,data,profile):
            if path=='/v1/proxy':return {'mode':'per_profile','initialized':True}
            if path=='/v1/network/observe':return {'currentSeq':7,'nextSeq':8,'events':events if 'afterSeq' in data else []}
            if path=='/v1/http/request':return {'result':{'status':200,'bodyEncoding':'base64','body':base64.b64encode(body).decode()}}
            return original(method,path,data,profile)
        self.browser._transport=transport;page=self.browser.context('TEST01').page()
        digest=hashlib.sha256(body).hexdigest()
        self.assertFalse(page.probe_proxy('https://example.test/probe',digest)['healthy'],'HTTP 200 alone was accepted')
        events.append({'seq':8,'phase':'response','url':'https://example.test/probe','documentId':'doc1','status':200})
        self.assertTrue(page.probe_proxy('https://example.test/probe',digest)['healthy'])
        self.assertFalse(page.probe_proxy('https://example.test/probe','0'*64)['healthy'],'Wrong response body was accepted')
    def test_proxy_probe_refuses_a_different_origin_before_http_effect(self):
        page=self.browser.context('TEST01').page()
        with self.assertRaises(AbwrError) as error:page.probe_proxy('https://other.test/','0'*64)
        self.assertEqual(error.exception.code,'PROXY_SCOPE_UNSUPPORTED')
        self.assertFalse(any(c[1]=='/v1/http/request' for c in self.calls))
