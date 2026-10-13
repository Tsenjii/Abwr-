import json
from pathlib import Path
import tempfile
import unittest
from scripts.termux_network import manifest_rows
from scripts.termux_lifecycle import write_record


class TermuxEvidenceTests(unittest.TestCase):
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
