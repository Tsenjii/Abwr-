import base64
from contextlib import redirect_stdout
import importlib.util
import io
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("prepare_signing", ROOT / "scripts/prepare_signing.py")
signing = importlib.util.module_from_spec(spec)
spec.loader.exec_module(signing)


class DistributionSigningTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.environment = {"RUNNER_TEMP": str(self.root), "GITHUB_ENV": str(self.root / "env")}

    def prepare(self, encoded=""):
        with patch.dict(os.environ, {**self.environment, "ABWR_SIGNING_KEY_B64": encoded}), redirect_stdout(io.StringIO()) as output:
            signing.prepare()
        return output.getvalue()

    def assert_no_signing_output(self):
        self.assertFalse((self.root / "abwr-distribution.jks").exists())
        self.assertFalse((self.root / "env").exists())

    def test_missing_secret_refuses_existing_legacy_cache(self):
        legacy = self.root / ".android" / "debug.keystore"
        legacy.parent.mkdir()
        legacy.write_bytes(b"legacy signing key")
        with patch.object(Path, "home", return_value=self.root), self.assertRaisesRegex(RuntimeError, "secret missing"):
            self.prepare()
        self.assert_no_signing_output()
        self.assertEqual(legacy.read_bytes(), b"legacy signing key")

    def test_malformed_secret_is_not_written_or_exported(self):
        with self.assertRaises(ValueError):
            self.prepare("this is not base64!")
        self.assert_no_signing_output()

    def test_invalid_size_is_not_written_or_exported(self):
        for size in (999, 65537):
            with self.subTest(size=size), self.assertRaisesRegex(RuntimeError, "valid-sized"):
                self.prepare(base64.b64encode(b"x" * size).decode())
            self.assert_no_signing_output()

    def test_secret_bytes_are_private_and_never_echoed(self):
        dummy_key = bytes(range(256)) * 8
        encoded = base64.b64encode(dummy_key).decode()
        target = self.root / "abwr-distribution.jks"
        target.write_bytes(b"old task data")
        target.chmod(0o644)
        output = self.prepare(encoded)
        self.assertEqual(target.read_bytes(), dummy_key)
        self.assertEqual(target.stat().st_mode & 0o777, 0o600)
        self.assertEqual((self.root / "env").read_text(), "ABWR_KEYSTORE_PATH=" + str(target) + "\n")
        self.assertNotIn(encoded, output)
        self.assertIn("repository secret", output)


if __name__ == "__main__":
    unittest.main()
