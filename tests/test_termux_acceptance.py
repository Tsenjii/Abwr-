import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock,patch
from bridge.abwr_shim import AbwrError
from scripts import termux_acceptance

class TermuxAcceptanceTests(unittest.TestCase):
    def test_failure_preserves_accounts_and_cleans_only_this_runs_created_profiles(self):
        with tempfile.TemporaryDirectory() as root:
            browser=Mock(root=Path(root));(Path(root)/'ledger').mkdir()
            browser.request.return_value={'profile':'OWNED_ACCOUNT'}
            browser.context.return_value.page.return_value.goto.side_effect=AbwrError('WAIT_TIMEOUT','uncertain')
            server=Mock()
            with patch.object(termux_acceptance,'AbwrBrowser',return_value=browser),patch.object(termux_acceptance,'make_server',return_value=server),patch.object(termux_acceptance.threading,'Thread'),patch('sys.argv',['acceptance','--create-test-profiles','--cleanup-created','--profiles','15']):
                with self.assertRaises(AbwrError):termux_acceptance.main()
            created=browser.context.call_args.args[0]
            self.assertTrue(created.startswith('TEST_AUTO_'));self.assertEqual(browser.context.call_args.kwargs['exit_bucket'],'fixture')
            deletions=[c.args[2]['id'] for c in browser.request.call_args_list if len(c.args)>1 and c.args[1]=='/v1/profiles/delete']
            self.assertEqual(deletions,[created]);self.assertNotIn('OWNED_ACCOUNT',deletions)
            browser.request.assert_any_call('POST','/v1/profiles/switch',{'id':'OWNED_ACCOUNT'})
            record=json.loads(next((Path(root)/'ledger').glob('acceptance_*.json')).read_text())
            self.assertFalse(record['completed']);self.assertEqual(record['failureCode'],'WAIT_TIMEOUT')
            self.assertEqual(record['createdProfiles'],[created]);self.assertEqual(record['results'],[])
            server.shutdown.assert_called_once();server.server_close.assert_called_once()
