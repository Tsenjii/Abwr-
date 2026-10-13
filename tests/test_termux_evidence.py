import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock,patch
from bridge.abwr_shim import AbwrError
from scripts import termux_network,termux_lifecycle
from scripts.termux_network import manifest_rows
from scripts.termux_lifecycle import write_record


class TermuxEvidenceTests(unittest.TestCase):
    def run_lifecycle(self,root,restart_during_final_capture=False):
        root=Path(root);(root/'ledger').mkdir()
        browser=Mock(root=root);page=browser.context.return_value.page.return_value
        clock={'now':0.,'slept':0.,'document':0,'captures':0,'color':'green'}
        def diagnostics():
            restarted=restart_during_final_capture and clock['captures']==4
            return {'processId':741,'processInstanceId':'replacement' if restarted else 'original',
                'screenInteractive':True,'backgroundRendererActive':False,'apiWakeLeases':0}
        def request(method,path,*args,**kwargs):
            if path=='/v1/status':return {'profile':'OWNER'}
            if path=='/v1/diagnostics':return diagnostics()
            return {}
        browser.request.side_effect=request
        def goto(url):
            clock['document']+=1;clock['color']='blue' if 'c=blue' in url else 'green'
        page.goto.side_effect=goto
        page.status.side_effect=lambda:{'documentId':'doc'+str(clock['document']),
            'viewAttached':True,'extensionPortReady':True}
        page.command.side_effect=lambda *_: {'text':clock['color']+' lower viewport'}
        def screenshot():
            clock['captures']+=1
            path=root/('shot'+str(clock['captures'])+'.jpg')
            path.write_bytes(b'\xff\xd8\xff'+str(clock['document']).encode())
            path.with_suffix('.json').write_text(json.dumps({'capturedAtMs':int(clock['now']*1000),
                'documentId':'doc'+str(clock['document'])}))
            clock['now']+=7  # Capture work must not shorten the next idle interval.
            return path
        page.screenshot.side_effect=screenshot
        def pause(seconds):clock['now']+=seconds;clock['slept']+=seconds
        with patch.object(termux_lifecycle,'AbwrBrowser',return_value=browser),patch.object(termux_lifecycle,'make_server',return_value=Mock()),patch.object(termux_lifecycle.threading,'Thread'),patch.object(termux_lifecycle.subprocess,'run',side_effect=OSError),patch.object(termux_lifecycle.time,'monotonic',side_effect=lambda:clock['now']),patch.object(termux_lifecycle.time,'sleep',side_effect=pause),patch('sys.argv',['lifecycle','--mode','foreground','--create-test-profile','--cleanup-created']):
            try:termux_lifecycle.main()
            except AbwrError as error:
                if not restart_during_final_capture:raise
                self.assertEqual(error.code,'PROCESS_RESTARTED')
        browser.request.assert_any_call('POST','/v1/profiles/switch',{'id':'OWNER'})
        created=browser.context.call_args.args[0]
        browser.request.assert_any_call('POST','/v1/profiles/delete',{'id':created})
        return clock,json.loads(next((root/'ledger').glob('lifecycle_*.json')).read_text())

    def test_every_lifecycle_checkpoint_has_a_full_new_idle_interval(self):
        with tempfile.TemporaryDirectory() as root:
            clock,record=self.run_lifecycle(root)
        self.assertTrue(record['completed'])
        self.assertEqual([c['requestedIdleSeconds'] for c in record['checks']],[60,600,1800])
        self.assertEqual([c['idleSeconds'] for c in record['checks']],[60,600,1800])
        self.assertEqual(clock['slept'],2460)
        self.assertGreater(record['checks'][-1]['elapsedSeconds'],2460)

    def test_restart_during_final_capture_cannot_certify_lifecycle(self):
        with tempfile.TemporaryDirectory() as root:
            _,record=self.run_lifecycle(root,True)
        self.assertFalse(record['completed']);self.assertEqual(record['failureCode'],'PROCESS_RESTARTED')
        self.assertEqual(len(record['checks']),2)

    def test_historical_gateway_ip_cannot_certify_a_current_exit(self):
        with tempfile.TemporaryDirectory() as root:
            root=Path(root);(root/'ledger').mkdir();manifest=root/'probes.json'
            manifest.write_text(json.dumps({'exits':[{'port':7901,'target':'https://example.org/fixture','expectedSha256':'0'*64,'expectedExitIp':'198.51.100.7'}]}))
            browser=Mock(root=root);browser.request.return_value={'profile':'OWNER'}
            browser.context.return_value.page.return_value.probe_proxy.return_value={'healthy':True,'networkObserved':True,'tlsVerified':True}
            gateway={'healthy':True,'tlsVerified':True,'bodySha256':'0'*64,'exitIp':None}
            historical={'slots':[{'port':7901,'exitIp':'198.51.100.7'}]}
            with patch.object(termux_network,'AbwrBrowser',return_value=browser),patch.object(termux_network,'control',side_effect=[historical,gateway,historical]),patch.dict('os.environ',{'ABWR_GATEWAY_TOKEN':'fixture-local'}),patch('sys.argv',['network','--manifest',str(manifest),'--create-test-profiles','--cleanup-created']):
                with self.assertRaises(AbwrError) as error:termux_network.main()
            self.assertEqual(error.exception.code,'EXIT_IP_MISMATCH')
            record=json.loads(next((root/'ledger').glob('network_*.json')).read_text())
            self.assertFalse(record['completed']);self.assertEqual(record['results'],[])
            self.assertEqual(record['failureCode'],'EXIT_IP_MISMATCH')

    def test_gateway_success_without_gecko_response_is_not_completed_or_counted(self):
        with tempfile.TemporaryDirectory() as root:
            root=Path(root);(root/'ledger').mkdir();manifest=root/'probes.json'
            manifest.write_text(json.dumps({'exits':[{'port':7901,'target':'https://example.org/fixture','expectedSha256':'0'*64}]}))
            browser=Mock(root=root);browser.request.return_value={'profile':'OWNER'}
            browser.context.return_value.page.return_value.probe_proxy.return_value={'healthy':False,'networkObserved':False,'tlsVerified':False}
            with patch.object(termux_network,'AbwrBrowser',return_value=browser),patch.object(termux_network,'control',side_effect=[{'slots':[{'port':7901}]},{'healthy':True,'tlsVerified':True}]),patch.dict('os.environ',{'ABWR_GATEWAY_TOKEN':'fixture-local'}),patch('sys.argv',['network','--manifest',str(manifest),'--create-test-profiles','--cleanup-created']):
                with self.assertRaises(AbwrError) as error:termux_network.main()
            self.assertEqual(error.exception.code,'PROBE_FAILED')
            record=json.loads(next((root/'ledger').glob('network_*.json')).read_text())
            self.assertFalse(record['completed']);self.assertEqual(record['results'],[])
            self.assertEqual(record['failureCode'],'PROBE_FAILED')
            browser.request.assert_any_call('POST','/v1/profiles/switch',{'id':'OWNER'})

    def test_network_manifest_does_not_accept_a_loopback_bypass_as_exit_proof(self):
        with tempfile.TemporaryDirectory() as root:
            path=Path(root)/'manifest.json'
            for target in ['https://127.0.0.1/','https://user:pass@example.org/','http://example.org/']:
                path.write_text(json.dumps({'exits':[{'port':7901,'target':target,'expectedSha256':'0'*64}]}))
                with self.assertRaises(ValueError):manifest_rows(path)

    def test_duplicate_slots_and_unknown_content_cannot_certify_fifteen_exits(self):
        with tempfile.TemporaryDirectory() as root:
            path=Path(root)/'manifest.json'
            row={'port':7901,'target':'https://example.org/fixture','expectedSha256':'0'*64}
            path.write_text(json.dumps({'exits':[row,row]}))
            with self.assertRaises(ValueError):manifest_rows(path)
            row['expectedSha256']='';path.write_text(json.dumps({'exits':[row]}))
            with self.assertRaises(ValueError):manifest_rows(path)

    def test_interrupted_checkpoint_keeps_last_complete_owner_only_record(self):
        with tempfile.TemporaryDirectory() as root:
            target=Path(root)/'evidence.json'
            write_record(target,{'completed':False,'failureCode':'STALE_EVIDENCE'})
            self.assertEqual(target.stat().st_mode & 0o777,0o600)
            self.assertEqual(json.loads(target.read_text())['failureCode'],'STALE_EVIDENCE')
            self.assertFalse(target.with_suffix('.part').exists())


if __name__=='__main__':unittest.main()
